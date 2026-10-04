package io.horizontalsystems.evidenceui.registration

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreKeyWrapperTest {
    @Test
    fun wraps_unwraps_and_reports_hardware() {
        val wrapper = KeystoreKeyWrapper(alias = "evidence_api_key_wrap_test")
        val secret = "ab.secret".toByteArray()
        val blob = wrapper.wrap(secret)
        assertFalse(blob.contentEquals(secret))
        assertArrayEquals(secret, wrapper.unwrap(blob))
        assertTrue(wrapper.hardware == "StrongBox" || wrapper.hardware == "TEE")
        wrapper.destroy()
    }
}
