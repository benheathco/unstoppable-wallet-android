package io.horizontalsystems.evidence.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed class CaptureResult {
    data class Ok(val captureId: String) : CaptureResult()

    // retryable = transient (network/5xx/untrustworthy 2xx); false = 4xx rejection, final
    data class Err(val message: String, val retryable: Boolean) : CaptureResult()
}

sealed class UploadResult {
    object Verified : UploadResult()
    data class HashMismatch(val server: String?, val client: String) : UploadResult()
    data class Err(val message: String, val retryable: Boolean) : UploadResult()
}

/** Opie evidence endpoints — the same wire contract as the KnowYourChain extension. */
class OpieClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val retryDelayMs: Long = 500,
) {
    /**
     * Registers the capture. Network errors, 5xx and an id-less/unparsable 2xx get ONE retry
     * with the identical body — client_request_id makes it idempotent, so a lost response
     * returns the already-created capture instead of duplicating it. 4xx is final.
     */
    suspend fun createCapture(serverUrl: String, apiKey: String, envelopeJson: String): CaptureResult {
        var lastError = "Capture registration failed."
        repeat(2) { attempt ->
            if (attempt > 0) delay(retryDelayMs)
            val request = Request.Builder()
                .url("$serverUrl$EVIDENCE_CAPTURES_PATH")
                .header("Authorization", "Api-Key $apiKey")
                .post(envelopeJson.toRequestBody(JSON))
                .build()
            val (code, body) = try {
                execute(request)
            } catch (e: IOException) {
                lastError = "Capture registration failed: ${e.message}"
                return@repeat
            }
            when {
                code >= 500 -> lastError = "Capture registration returned $code"
                code !in 200..299 ->
                    return CaptureResult.Err("Capture registration returned $code: ${body.take(200)}", code in RETRY_LATER)
                else -> {
                    val id = parseObject(body)?.get("id")?.jsonPrimitive?.content
                    if (id.isNullOrEmpty()) {
                        lastError = "Capture registration response had no id."
                    } else {
                        return CaptureResult.Ok(id)
                    }
                }
            }
        }
        return CaptureResult.Err(lastError, true)
    }

    /**
     * Uploads one sealed artifact and verifies the server-computed `file_hash` against
     * [clientSha256]. A missing or different hash is a [UploadResult.HashMismatch] — never a pass.
     */
    suspend fun uploadArtifact(
        serverUrl: String,
        apiKey: String,
        bytes: ByteArray,
        filename: String,
        projectUuid: String,
        captureId: String,
        artifactKind: String,
        clientSha256: String,
    ): UploadResult {
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(PNG))
            .addFormDataPart("project_uuid", projectUuid)
            .addFormDataPart("evidence_capture", captureId)
            .addFormDataPart("artifact_kind", artifactKind)
            .build()
        val request = Request.Builder()
            .url("$serverUrl$VAULT_FILES_PATH?allow_duplicate=true")
            .header("Authorization", "Api-Key $apiKey")
            .post(form)
            .build()
        val (code, body) = try {
            execute(request)
        } catch (e: IOException) {
            return UploadResult.Err("Upload failed: ${e.message}", true)
        }
        if (code !in 200..299) {
            return UploadResult.Err("Upload returned $code: ${body.take(200)}", code >= 500 || code in RETRY_LATER)
        }
        val serverHash = parseObject(body)?.get("file_hash")?.jsonPrimitive?.content
        return if (serverHash != null && serverHash.equals(clientSha256, ignoreCase = true)) {
            UploadResult.Verified
        } else {
            UploadResult.HashMismatch(serverHash, clientSha256)
        }
    }

    private suspend fun execute(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun parseObject(body: String): JsonObject? =
        try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            null
        }

    companion object {
        const val EVIDENCE_CAPTURES_PATH = "/opie/api/v1/evidence-captures/"
        const val VAULT_FILES_PATH = "/opie/api/v1/vault-files/"

        // Not a verdict on the evidence: a revoked/rotated key (spec §6 — keep queueing until
        // re-enrolled), a timeout or throttling. Only other 4xx are final.
        private val RETRY_LATER = setOf(401, 403, 408, 429)
        private val JSON = "application/json".toMediaType()
        private val PNG = "image/png".toMediaType()
    }
}
