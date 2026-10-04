package io.horizontalsystems.evidenceui.registration

import android.content.SharedPreferences
import android.util.Base64
import io.horizontalsystems.evidence.EvidenceConfig
import io.horizontalsystems.evidence.net.OpieProject
import io.horizontalsystems.evidenceui.pairing.PairingPayload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class Registration(
    val server: String,
    val apiKey: String,
    val team: String,
    val projectUuid: String,
    val projectName: String,
    val attributionEnabled: Boolean,
    val deviceId: String,
    val registeredAt: Long,
    val keyHardware: String,
    val keyRejected: Boolean,
)

/** Registration lives only here; the API key is stored only as Keystore-wrapped ciphertext. */
class RegistrationStore(
    private val prefs: SharedPreferences,
    private val wrapper: KeyWrapper,
    private val appVersion: String,
) {
    private val _registration = MutableStateFlow(load())
    val registration: StateFlow<Registration?> = _registration.asStateFlow()

    fun save(payload: PairingPayload, project: OpieProject) {
        val blob = wrapper.wrap(payload.key.toByteArray())
        prefs.edit()
            .putString(SERVER, payload.server)
            .putString(KEY_BLOB, Base64.encodeToString(blob, Base64.NO_WRAP))
            .putString(TEAM, payload.team)
            .putString(DEVICE_ID, UUID.randomUUID().toString())
            .putLong(REGISTERED_AT, System.currentTimeMillis())
            .putString(KEY_HARDWARE, wrapper.hardware)
            .putBoolean(KEY_REJECTED, false)
            .commit()
        setProject(project)
    }

    fun setProject(project: OpieProject) {
        prefs.edit()
            .putString(PROJECT_UUID, project.uuid)
            .putString(PROJECT_NAME, project.name)
            .putBoolean(ATTRIBUTION, project.attributionEnabled)
            .commit()
        _registration.value = load()
    }

    fun markKeyRejected(rejected: Boolean) {
        prefs.edit().putBoolean(KEY_REJECTED, rejected).commit()
        _registration.value = load()
    }

    fun clear() {
        prefs.edit().clear().commit()
        wrapper.destroy()
        _registration.value = null
    }

    fun config(): EvidenceConfig? = _registration.value?.let {
        EvidenceConfig(it.server, it.apiKey, it.projectUuid, it.deviceId, appVersion, it.attributionEnabled)
    }

    private fun load(): Registration? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        val project = prefs.getString(PROJECT_UUID, null) ?: return null
        val apiKey = runCatching { String(wrapper.unwrap(Base64.decode(blob, Base64.NO_WRAP))) }.getOrNull()
            ?: return null
        return Registration(
            server = prefs.getString(SERVER, "")!!,
            apiKey = apiKey,
            team = prefs.getString(TEAM, "")!!,
            projectUuid = project,
            projectName = prefs.getString(PROJECT_NAME, "")!!,
            attributionEnabled = prefs.getBoolean(ATTRIBUTION, false),
            deviceId = prefs.getString(DEVICE_ID, "")!!,
            registeredAt = prefs.getLong(REGISTERED_AT, 0),
            keyHardware = prefs.getString(KEY_HARDWARE, "")!!,
            keyRejected = prefs.getBoolean(KEY_REJECTED, false),
        )
    }

    companion object {
        const val PREFS_NAME = "evidence_registration"
        private const val SERVER = "server"
        private const val KEY_BLOB = "key_blob"
        private const val TEAM = "team"
        private const val PROJECT_UUID = "project_uuid"
        private const val PROJECT_NAME = "project_name"
        private const val ATTRIBUTION = "attribution"
        private const val DEVICE_ID = "device_id"
        private const val REGISTERED_AT = "registered_at"
        private const val KEY_HARDWARE = "key_hardware"
        private const val KEY_REJECTED = "key_rejected"
    }
}
