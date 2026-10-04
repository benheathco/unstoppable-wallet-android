package io.horizontalsystems.evidence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import io.horizontalsystems.evidence.model.DecodedScan
import io.horizontalsystems.evidence.model.EvidenceStatus
import io.horizontalsystems.evidence.model.SendRecord
import io.horizontalsystems.evidence.net.CAPTURE_METHOD_MOBILE_APP
import io.horizontalsystems.evidence.net.Candidate
import io.horizontalsystems.evidence.net.OpieClient
import io.horizontalsystems.evidence.net.mapNetworkKey
import io.horizontalsystems.evidence.net.sendCandidates
import io.horizontalsystems.evidence.net.sendNotes
import io.horizontalsystems.evidence.net.unmappedNetworkNote
import io.horizontalsystems.evidence.seal.SealMeta
import io.horizontalsystems.evidence.seal.sealPng
import io.horizontalsystems.evidence.seal.sha256Hex
import io.horizontalsystems.evidence.store.ArtifactEntity
import io.horizontalsystems.evidence.store.ArtifactState
import io.horizontalsystems.evidence.store.CaptureEntity
import io.horizontalsystems.evidence.store.CaptureState
import io.horizontalsystems.evidence.store.EvidenceDao
import io.horizontalsystems.evidence.store.EvidenceDb
import io.horizontalsystems.evidence.upload.UploadDependencies
import io.horizontalsystems.evidence.upload.UploadWorker
import io.horizontalsystems.evidence.upload.enqueueUpload
import io.horizontalsystems.evidence.upload.finalizeBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Durability first: the bundle row is written to Room before [recordScan]/[recordSend] return.
 * Sealing to disk then runs on a serial background queue, so scan → send → finalize always
 * happen in call order. Nothing here ever throws into the wallet.
 */
class DefaultEvidenceRecorder(
    private val context: Context,
    private val config: () -> EvidenceConfig?,
    private val dao: EvidenceDao = EvidenceDb.get(context).dao(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    private val now: () -> String = { Instant.now().toString() },
    client: OpieClient = OpieClient(),
    onAuthRejected: (apiKeyUsed: String) -> Unit = {},
) : EvidenceRecorder {

    // The bundle a later send attaches to: the most recent one this recorder opened
    @Volatile
    private var openBundleId: String? = null

    // Background work suspends inside Room calls, freeing the serial dispatcher for the next job;
    // the FIFO lock keeps each job whole, so a finalize never overtakes an earlier artifact write
    private val queue = Mutex()

    init {
        UploadWorker.dependencies = UploadDependencies(dao, client, config, onAuthRejected)
        // A bundle finalized just before process death may have no queued work; KEEP makes this idempotent
        scope.launch {
            guard {
                (dao.capturesInState(CaptureState.READY) + dao.capturesInState(CaptureState.REGISTERED))
                    .forEach { enqueueUpload(context, it.clientRequestId) }
            }
        }
    }

    override val status: StateFlow<EvidenceStatus> = dao.openBundles()
        .map { EvidenceStatus(enrolled = config() != null, pendingBundles = it.size) }
        .stateIn(scope, SharingStarted.Eagerly, EvidenceStatus(enrolled = config() != null, pendingBundles = 0))

    override fun recordScan(frame: Bitmap, decoded: DecodedScan) = guard {
        val cfg = config() ?: return@guard
        val image = frame.copy(Bitmap.Config.ARGB_8888, false)
        val network = decoded.network?.let { mapNetworkKey(it).key }
        val clientRequestId = UUID.randomUUID().toString()
        val capturedAt = now()
        val candidates = if (!cfg.attributionEnabled) emptyList() else decoded.address?.let {
            listOf(Candidate(identifier = it, network = network.orEmpty(), subjectType = Candidate.DEPOSIT_ADDRESS).clamped())
        }.orEmpty()
        val notes = listOfNotNull(
            "scan",
            "payload: ${decoded.rawPayload}",
            decoded.network?.let { unmappedNetworkNote(it) },
            decoded.notes?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString("\n")

        writeNow(
            CaptureEntity(
                clientRequestId, null, network, decoded.address, notes, cfg.projectUuid, CAPTURE_METHOD_MOBILE_APP,
                "[]", CaptureState.OPEN, System.currentTimeMillis(), encode(candidates), capturedAt,
            )
        )
        openBundleId = clientRequestId

        val meta = SealMeta(clientRequestId, network, decoded.address, null, capturedAt, cfg.deviceId, cfg.appVersion, null)
        enqueue { writeArtifact(clientRequestId, KIND_SCAN_FRAME, image, meta) }
    }

    override fun recordSend(send: SendRecord, confirmationView: View) = guard {
        // A failed render (e.g. hardware bitmaps on a software canvas) still records the send
        recordSend(send, runCatching { renderView(confirmationView) }.getOrNull())
    }

    /** [confirmation] is the already-rendered confirmation screen; null records the send data only. */
    fun recordSend(send: SendRecord, confirmation: Bitmap?) = guard {
        val cfg = config() ?: return@guard
        val image = confirmation?.copy(Bitmap.Config.ARGB_8888, false)
        val network = mapNetworkKey(send.network).key
        // After process death the in-memory id is gone: fall back to the newest OPEN bundle
        val open = runBlocking(Dispatchers.IO) { openBundleId?.let { dao.capture(it) } ?: dao.newestOpen() }
            ?.takeIf { it.state == CaptureState.OPEN }

        val capture = if (open != null) {
            open.copy(
                network = network,
                address = send.address,
                notes = listOfNotNull(open.notes, sendNotes(send)).joinToString("\n"),
                candidatesJson = encode(mergeCandidates(decode(open.candidatesJson), if (cfg.attributionEnabled) sendCandidates(send) else emptyList())),
            )
        } else {
            CaptureEntity(
                UUID.randomUUID().toString(), null, network, send.address, sendNotes(send), cfg.projectUuid,
                CAPTURE_METHOD_MOBILE_APP, "[]", CaptureState.OPEN, System.currentTimeMillis(),
                encode(if (cfg.attributionEnabled) sendCandidates(send) else emptyList()), send.capturedAtIso,
            )
        }
        writeNow(capture)
        val clientRequestId = capture.clientRequestId
        openBundleId = clientRequestId

        // A known tx hash means the broadcast succeeded: the bundle is complete
        val finalize = send.txHashIfKnown != null
        if (finalize) openBundleId = null
        val meta = SealMeta(
            clientRequestId, network, send.address, send.amount, send.capturedAtIso, cfg.deviceId, cfg.appVersion, null,
        )
        enqueue {
            image?.let { writeArtifact(clientRequestId, KIND_SCREENSHOT, it, meta) }
            if (finalize) finalizeBundle(context, dao, clientRequestId)
        }
    }

    /**
     * Manual Submit: finalize every OPEN bundle that has something to upload. Runs on the same
     * FIFO queue as artifact writes, so it never overtakes a pending write. Blocks; call off the main thread.
     */
    fun submitAll(): Int = runBlocking(Dispatchers.IO) {
        scope.async {
            queue.withLock {
                var submitted = 0
                dao.capturesInState(CaptureState.OPEN).forEach { capture ->
                    if (dao.artifacts(capture.clientRequestId).isNotEmpty()) {
                        finalizeBundle(context, dao, capture.clientRequestId)
                        submitted++
                    }
                }
                openBundleId = null
                submitted
            }
        }.await()
    }

    private fun enqueue(work: suspend () -> Unit) {
        scope.launch { queue.withLock { guard { work() } } }
    }

    private fun writeNow(capture: CaptureEntity) = runBlocking(Dispatchers.IO) { dao.upsertCapture(capture) }

    private suspend fun writeArtifact(clientRequestId: String, kind: String, image: Bitmap, meta: SealMeta) {
        val dir = File(context.filesDir, "evidence/$clientRequestId").apply { mkdirs() }
        val index = dao.artifacts(clientRequestId).size + 1
        val source = File(dir, "$kind-$index.source.png")
        source.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val sealedBytes = sealPng(image, meta)
        image.recycle()
        val sealed = File(dir, "$kind-$index.png").apply { writeBytes(sealedBytes) }
        dao.insertArtifact(
            ArtifactEntity(
                0, clientRequestId, kind, sealed.path, sha256Hex(sealedBytes), null, ArtifactState.PENDING,
                sourcePath = source.path, sealMetaJson = json.encodeToString(SealMeta.serializer(), meta),
            )
        )
    }

    private fun renderView(view: View): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    // A send to the scanned address is the same attribution fact: keep one, with the send's detail
    private fun mergeCandidates(existing: List<Candidate>, incoming: List<Candidate>): List<Candidate> {
        fun Candidate.key() = Triple(subjectType, identifier.lowercase(), network)
        val replaced = incoming.map { it.key() }.toSet()
        return existing.filterNot { it.key() in replaced } + incoming
    }

    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "evidence capture failed", e)
        }
    }

    companion object {
        private const val TAG = "Evidence"
        const val KIND_SCAN_FRAME = "scan_frame"
        const val KIND_SCREENSHOT = "screenshot"

        private val json = Json { ignoreUnknownKeys = true }
        private val candidatesSerializer = ListSerializer(Candidate.serializer())
        private fun encode(candidates: List<Candidate>) = json.encodeToString(candidatesSerializer, candidates)
        private fun decode(candidatesJson: String) = json.decodeFromString(candidatesSerializer, candidatesJson)
    }
}
