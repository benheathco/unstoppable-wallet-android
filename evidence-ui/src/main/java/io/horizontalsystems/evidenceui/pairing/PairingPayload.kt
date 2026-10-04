package io.horizontalsystems.evidenceui.pairing

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Opie "Connect a device" QR, v1. See the device-pairing spec §3.3. */
data class PairingPayload(val server: String, val key: String, val team: String, val project: String?) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Result<PairingPayload> = runCatching {
            val wire = json.decodeFromString(Wire.serializer(), text.trim())
            require(wire.v == 1) { "Unsupported pairing code version ${wire.v}" }
            require(wire.kind == "opie-device") { "Not an Opie device pairing code" }
            val server = wire.server.trim().trimEnd('/')
            require(server.startsWith("https://") || server.startsWith("http://")) { "Invalid server address" }
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
