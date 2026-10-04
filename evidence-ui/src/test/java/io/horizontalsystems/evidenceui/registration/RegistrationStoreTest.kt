package io.horizontalsystems.evidenceui.registration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.evidence.net.OpieProject
import io.horizontalsystems.evidenceui.pairing.PairingPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegistrationStoreTest {
    private class XorWrapper : KeyWrapper {
        var destroyed = false
        override val hardware = "TEE"
        override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun unwrap(blob: ByteArray) = wrap(blob)
        override fun destroy() { destroyed = true }
    }

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("evidence_registration_test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
    private val payload = PairingPayload("http://localhost:8000", "ab.secret", "kyc", null)
    private val project = OpieProject("p-1", "Retail", attributionEnabled = false)

    @Test
    fun unregistered_has_no_config() {
        assertNull(RegistrationStore(prefs, XorWrapper(), "1.0").config())
    }

    @Test
    fun save_round_trips_and_never_stores_the_key_in_plain_text() {
        val store = RegistrationStore(prefs, XorWrapper(), "1.0")
        store.save(payload, project)
        val reloaded = RegistrationStore(prefs, XorWrapper(), "1.0")
        val config = reloaded.config()!!
        assertEquals("ab.secret", config.apiKey)
        assertEquals("p-1", config.projectUuid)
        assertFalse(config.attributionEnabled)
        assertTrue(config.deviceId.isNotBlank())
        assertFalse(prefs.all.values.any { it.toString().contains("ab.secret") })
    }

    @Test
    fun key_rejected_flag_and_project_change_persist() {
        val store = RegistrationStore(prefs, XorWrapper(), "1.0")
        store.save(payload, project)
        store.markKeyRejected(true)
        store.setProject(OpieProject("p-2", "Exchange", true))
        val r = RegistrationStore(prefs, XorWrapper(), "1.0").registration.value!!
        assertTrue(r.keyRejected)
        assertEquals("p-2", r.projectUuid)
        assertTrue(r.attributionEnabled)
    }

    @Test
    fun rejection_of_the_current_key_marks_it_rejected() {
        val store = RegistrationStore(prefs, XorWrapper(), "1.0")
        store.save(payload, project)
        store.onAuthRejected("ab.secret")
        assertTrue(store.registration.value!!.keyRejected)
    }

    @Test
    fun rejection_reported_for_a_replaced_key_does_not_mark_rejected() {
        val store = RegistrationStore(prefs, XorWrapper(), "1.0")
        store.save(payload, project)
        store.onAuthRejected("old.key")
        assertFalse(store.registration.value!!.keyRejected)
    }

    @Test
    fun rejection_while_unregistered_is_ignored() {
        val store = RegistrationStore(prefs, XorWrapper(), "1.0")
        store.onAuthRejected("ab.secret")
        assertNull(store.registration.value)
    }

    @Test
    fun evidence_config_to_string_redacts_the_api_key() {
        val store = RegistrationStore(prefs, XorWrapper(), "1.0")
        store.save(payload, project)
        val text = store.config().toString()
        assertFalse(text.contains("ab.secret"))
        assertTrue(text.contains("••••cret"))
    }

    @Test
    fun pairing_payload_and_wizard_state_to_string_redact_the_key() {
        assertFalse(payload.toString().contains("ab.secret"))
        assertTrue(payload.toString().contains("••••cret"))
        val step = RegStep.Confirm(payload, listOf(project), project)
        assertFalse(step.toString().contains("ab.secret"))
        assertFalse(RegUiState(step = step).toString().contains("ab.secret"))
    }

    @Test
    fun clear_wipes_prefs_and_destroys_the_wrapping_key() {
        val wrapper = XorWrapper()
        val store = RegistrationStore(prefs, wrapper, "1.0")
        store.save(payload, project)
        store.clear()
        assertNull(store.registration.value)
        assertTrue(prefs.all.isEmpty())
        assertTrue(wrapper.destroyed)
    }

    @Test
    fun registration_to_string_redacts_the_api_key() {
        val store = RegistrationStore(prefs, XorWrapper(), "1.0")
        store.save(payload, project)
        val text = store.registration.value.toString()
        assertFalse(text.contains("ab.secret"))
        assertTrue(text.contains("cret"))
    }

    @Test
    fun clear_resets_state_even_if_destroy_throws() {
        val wrapper = object : KeyWrapper {
            override val hardware = "TEE"
            override fun wrap(plain: ByteArray) = plain
            override fun unwrap(blob: ByteArray) = blob
            override fun destroy() = throw IllegalStateException("keystore gone")
        }
        val store = RegistrationStore(prefs, wrapper, "1.0")
        store.save(payload, project)
        runCatching { store.clear() }
        assertNull(store.registration.value)
        assertNull(store.config())
    }
}
