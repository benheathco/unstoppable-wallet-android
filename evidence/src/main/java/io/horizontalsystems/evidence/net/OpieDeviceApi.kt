package io.horizontalsystems.evidence.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class OpieProject(val uuid: String, val name: String, val attributionEnabled: Boolean)

sealed class DeviceApiResult<out T> {
    data class Ok<T>(val value: T) : DeviceApiResult<T>()
    object Unauthorized : DeviceApiResult<Nothing>()
    data class Failed(val message: String) : DeviceApiResult<Nothing>()
}

/** Device-side calls used by registration: the project picker and unregister. */
class OpieDeviceApi(private val http: OkHttpClient = OkHttpClient()) {

    suspend fun captureProjects(serverUrl: String, apiKey: String): DeviceApiResult<List<OpieProject>> {
        val projects = mutableListOf<OpieProject>()
        var next: String? = "$serverUrl$PROJECTS_PATH"
        while (next != null) {
            val (code, body) = get(next, apiKey) ?: return DeviceApiResult.Failed("Opie is unreachable")
            if (code == 401 || code == 403) return DeviceApiResult.Unauthorized
            if (code !in 200..299) return DeviceApiResult.Failed("Opie returned $code")
            val page = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return DeviceApiResult.Failed("Unexpected response from Opie")
            page["results"]?.jsonArray?.forEach { item ->
                val o = item.jsonObject
                projects += OpieProject(
                    uuid = o.getValue("uuid").jsonPrimitive.content,
                    name = o.getValue("name").jsonPrimitive.content,
                    attributionEnabled = o["crypto_attribution_enabled"]?.jsonPrimitive?.boolean ?: false,
                )
            }
            next = page["next"]?.jsonPrimitive?.contentOrNull?.let { absolute(serverUrl, it) }
        }
        return DeviceApiResult.Ok(projects)
    }

    suspend fun selfRevoke(serverUrl: String, apiKey: String): DeviceApiResult<Unit> {
        val request = Request.Builder()
            .url("$serverUrl$SELF_REVOKE_PATH")
            .header("Authorization", "Api-Key $apiKey")
            .post(ByteArray(0).toRequestBody())
            .build()
        val code = try {
            withContext(Dispatchers.IO) { http.newCall(request).execute().use { it.code } }
        } catch (e: IOException) {
            return DeviceApiResult.Failed("Opie is unreachable")
        }
        // 401/403 = the key is already revoked or unknown: as good as revoked
        return if (code in 200..299 || code == 401 || code == 403) DeviceApiResult.Ok(Unit)
        else DeviceApiResult.Failed("Opie returned $code")
    }

    private suspend fun get(url: String, apiKey: String): Pair<Int, String>? = try {
        withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Authorization", "Api-Key $apiKey").build())
                .execute().use { it.code to it.body.string() }
        }
    } catch (e: IOException) {
        null
    }

    private fun absolute(serverUrl: String, next: String) = if (next.startsWith("http")) next else "$serverUrl$next"

    companion object {
        const val PROJECTS_PATH = "/opie/api/v1/projects/?capture_eligible=1"
        const val SELF_REVOKE_PATH = "/opie/api/v1/devices/self/revoke/"
    }
}
