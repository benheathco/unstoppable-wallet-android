package io.horizontalsystems.evidence.upload

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import io.horizontalsystems.evidence.EvidenceConfig
import io.horizontalsystems.evidence.net.CaptureResult
import io.horizontalsystems.evidence.net.Candidate
import io.horizontalsystems.evidence.net.OpieClient
import io.horizontalsystems.evidence.net.UploadResult
import io.horizontalsystems.evidence.net.buildEnvelope
import io.horizontalsystems.evidence.seal.SealMeta
import io.horizontalsystems.evidence.seal.sealPng
import io.horizontalsystems.evidence.seal.sha256Hex
import io.horizontalsystems.evidence.store.ArtifactEntity
import io.horizontalsystems.evidence.store.ArtifactState
import io.horizontalsystems.evidence.store.CaptureEntity
import io.horizontalsystems.evidence.store.CaptureState
import io.horizontalsystems.evidence.store.EvidenceDao
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException

/** What the worker needs; config returns null while the device is not enrolled. */
class UploadDependencies(
    val dao: EvidenceDao,
    val client: OpieClient,
    val config: () -> EvidenceConfig?,
)

/**
 * Uploads one READY/REGISTERED bundle: register if needed (idempotent on client_request_id)
 * → re-seal each PENDING image with the server capture id + re-hash → upload and compare
 * file_hash → COMPLETE when every artifact is VERIFIED. Every step resumes from the Room rows,
 * so a retry after process death or lost connectivity never duplicates the capture.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    // Anything unexpected (SQLite, OOM while sealing…) is transient: the rows are untouched, try again
    override suspend fun doWork(): Result = try {
        upload()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.retry()
    }

    private suspend fun upload(): Result {
        val deps = dependencies ?: return Result.retry()
        val config = deps.config() ?: return Result.retry()
        val clientRequestId = inputData.getString(KEY_CLIENT_REQUEST_ID) ?: return Result.failure()
        val dao = deps.dao

        var capture = dao.capture(clientRequestId) ?: return Result.success()
        if (capture.state != CaptureState.READY && capture.state != CaptureState.REGISTERED) return Result.success()

        if (capture.serverCaptureId == null) {
            when (val result = register(deps, config, capture)) {
                is CaptureResult.Ok -> {
                    dao.setServerCaptureId(clientRequestId, result.captureId)
                    dao.markState(clientRequestId, CaptureState.REGISTERED)
                    capture = capture.copy(serverCaptureId = result.captureId, state = CaptureState.REGISTERED)
                }
                is CaptureResult.Err -> {
                    if (result.retryable) return Result.retry()
                    dao.markState(clientRequestId, CaptureState.FAILED)
                    return Result.failure()
                }
            }
        }
        val captureId = checkNotNull(capture.serverCaptureId)

        for (pending in dao.pendingArtifacts(clientRequestId)) {
            val artifact = reseal(dao, pending, captureId)
            val bytes = try {
                File(artifact.filePath).readBytes()
            } catch (e: FileNotFoundException) {
                // The sealed file is gone: it can never be verified, so say so rather than crash-loop
                dao.markArtifact(artifact.id, ArtifactState.VERIFY_FAILED, null)
                continue
            }
            val result = deps.client.uploadArtifact(
                config.serverUrl, config.apiKey, bytes, File(artifact.filePath).name,
                capture.projectUuid, captureId, artifact.artifactKind, artifact.clientSha256,
            )
            when (result) {
                UploadResult.Verified -> dao.markArtifact(artifact.id, ArtifactState.VERIFIED, artifact.clientSha256)
                is UploadResult.HashMismatch -> dao.markArtifact(artifact.id, ArtifactState.VERIFY_FAILED, result.server)
                is UploadResult.Err -> {
                    if (result.retryable) return Result.retry()
                    dao.markArtifact(artifact.id, ArtifactState.VERIFY_FAILED, null)
                }
            }
        }

        val allVerified = dao.artifacts(clientRequestId).all { it.state == ArtifactState.VERIFIED }
        dao.markState(clientRequestId, if (allVerified) CaptureState.COMPLETE else CaptureState.FAILED)
        return Result.success()
    }

    /**
     * A project without crypto attribution 400s any candidates. That rejects the attribution
     * hint, not the evidence: register again without them, keeping them readable in notes.
     */
    private suspend fun register(deps: UploadDependencies, config: EvidenceConfig, capture: CaptureEntity): CaptureResult {
        val candidates = json.decodeFromString(ListSerializer(Candidate.serializer()), capture.candidatesJson)
        val result = deps.client.createCapture(config.serverUrl, config.apiKey, envelope(capture, config, candidates, capture.notes))
        if (result !is CaptureResult.Err || result.retryable || candidates.isEmpty() || "\"candidates\"" !in result.message) {
            return result
        }
        val notes = listOfNotNull(
            capture.notes,
            "candidates not registered (project has no crypto attribution):",
            *candidates.map { "${it.identifier} ${it.network} ${it.asset}".trim() }.toTypedArray(),
        ).joinToString("\n")
        return deps.client.createCapture(config.serverUrl, config.apiKey, envelope(capture, config, emptyList(), notes))
    }

    private fun envelope(capture: CaptureEntity, config: EvidenceConfig, candidates: List<Candidate>, notes: String?) =
        buildEnvelope(
            projectUuid = capture.projectUuid,
            capturedAtIso = capture.capturedAtIso,
            clientRequestId = capture.clientRequestId,
            expectedArtifacts = json.decodeFromString(ListSerializer(String.serializer()), capture.expectedArtifacts),
            candidates = candidates,
            notes = notes,
            appVersion = config.appVersion,
        )

    /**
     * Re-stamps the authoritative server id into the banner and re-hashes. Sealing is
     * deterministic, so repeating this after a crash yields identical bytes.
     */
    private suspend fun reseal(dao: EvidenceDao, artifact: ArtifactEntity, captureId: String): ArtifactEntity {
        val sourcePath = artifact.sourcePath ?: return artifact
        val metaJson = artifact.sealMetaJson ?: return artifact
        val source = BitmapFactory.decodeFile(sourcePath) ?: return artifact
        val meta = json.decodeFromString(SealMeta.serializer(), metaJson).copy(captureId = captureId)
        val bytes = sealPng(source, meta)
        source.recycle()
        File(artifact.filePath).writeBytes(bytes)
        val sha = sha256Hex(bytes)
        dao.updateSealed(artifact.id, artifact.filePath, sha)
        return artifact.copy(clientSha256 = sha)
    }

    companion object {
        // Set by the recorder at app start (manual DI, like the App singletons)
        @Volatile
        var dependencies: UploadDependencies? = null

        private const val KEY_CLIENT_REQUEST_ID = "clientRequestId"
        private val json = Json { ignoreUnknownKeys = true }

        fun inputFor(clientRequestId: String): Data = Data.Builder().putString(KEY_CLIENT_REQUEST_ID, clientRequestId).build()

        fun uniqueName(clientRequestId: String) = "evidence-upload-$clientRequestId"
    }
}
