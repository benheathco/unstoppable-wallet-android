package io.horizontalsystems.evidenceui.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI

/** Opie "Connect a device" QR, v1. See the device-pairing spec §3.3. */
data class PairingPayload(val server: String, val key: String, val team: String, val project: String?) {
    override fun toString() = "PairingPayload(server=$server, key=••••${key.takeLast(4)}, team=$team, project=$project)"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Result<PairingPayload> = runCatching {
            val trimmed = text.trim()
            // Typed check: "v" must be a JSON number (not a string) equal to 1
            val jsonElement = Json.parseToJsonElement(trimmed)
            val obj = jsonElement.jsonObject

            val vElement = obj["v"]
            require(vElement is JsonPrimitive && !vElement.isString) { "Unsupported pairing code version" }
            require(vElement.intOrNull == 1) { "Unsupported pairing code version" }

            val wire = json.decodeFromJsonElement(Wire.serializer(), jsonElement)
            require(wire.kind == "opie-device") { "Not an Opie device pairing code" }
            val server = wire.server.trim().trimEnd('/')
            // Validate server URL using java.net.URI
            val uri = URI(server)
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { "Invalid server address" }
            require(!uri.host.isNullOrBlank()) { "Invalid server address" }
            require(wire.key.isNotBlank() && wire.team.isNotBlank()) { "Incomplete pairing code" }
            PairingPayload(server, wire.key.trim(), wire.team.trim(), wire.project?.trim()?.ifEmpty { null })
        }
    }

    @Serializable
    private data class Wire(
        val v: Int,
        val kind: String,
        val server: String,
        val key: String,
        val team: String,
        val project: String? = null,
    )
}
