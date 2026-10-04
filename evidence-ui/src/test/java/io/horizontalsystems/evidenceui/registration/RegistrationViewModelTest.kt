package io.horizontalsystems.evidenceui.registration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.evidence.net.OpieDeviceApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RegistrationViewModelTest {
    private class XorWrapper : KeyWrapper {
        override val hardware = "TEE"
        override fun wrap(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun unwrap(blob: ByteArray) = wrap(blob)
        override fun destroy() {}
    }

    private val server = MockWebServer()
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("reg_vm_test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
    private val store = RegistrationStore(prefs, XorWrapper(), "1.0")
    private val vm by lazy { RegistrationViewModel(store, OpieDeviceApi(), dispatcherForTests = kotlinx.coroutines.Dispatchers.Unconfined) }

    @After
    fun tearDown() = server.shutdown()

    private fun code(project: String? = null, key: String = "ab.secret") =
        """{"v":1,"kind":"opie-device","server":"${server.url("/").toString().trimEnd('/')}","key":"$key","team":"kyc"${project?.let { ",\"project\":\"$it\"" } ?: ""}}"""

    private fun projectsBody() =
        """{"next":null,"results":[{"uuid":"p1","name":"Retail","crypto_attribution_enabled":true},{"uuid":"p2","name":"Exchange","crypto_attribution_enabled":false}]}"""

    @Test
    fun code_with_project_preselects_it_and_register_persists() = runBlocking {
        server.enqueue(MockResponse().setBody(projectsBody()))
        server.start()
        vm.setConsent(true)
        vm.onCodeScanned(code(project = "p2"))
        val confirm = vm.uiState.value.step as RegStep.Confirm
        assertEquals("p2", confirm.selected?.uuid)
        vm.register()
        assertEquals(RegStep.Done(null), vm.uiState.value.step)
        assertEquals("p2", store.config()!!.projectUuid)
    }

    @Test
    fun code_without_project_requires_a_choice() = runBlocking {
        server.enqueue(MockResponse().setBody(projectsBody()))
        server.start()
        vm.onCodeScanned(code())
        assertNull((vm.uiState.value.step as RegStep.Confirm).selected)
        vm.register()
        assertNotNull(vm.uiState.value.error)
        assertNull(store.config())
    }

    @Test
    fun invalid_code_unreachable_server_and_401_keep_operator_on_screen_with_message() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        vm.onCodeScanned("bitcoin:bc1q")
        assertNotNull(vm.uiState.value.error)
        vm.onCodeScanned(code())
        assertNotNull(vm.uiState.value.error)
        assertTrue(vm.uiState.value.step !is RegStep.Confirm)
        assertNull(store.config())
    }

    @Test
    fun unregister_revokes_first_then_clears() = runBlocking {
        server.enqueue(MockResponse().setBody(projectsBody()))
        server.enqueue(MockResponse().setResponseCode(204))
        server.start()
        vm.onCodeScanned(code(project = "p1"))
        vm.register()
        assertEquals(UnregisterResult.Revoked, vm.unregister(force = false))
        assertNull(store.config())
        server.takeRequest()
        assertEquals("/opie/api/v1/devices/self/revoke/", server.takeRequest().path)
    }

    @Test
    fun unregister_offline_keeps_registration_unless_forced() = runBlocking {
        // Connection: close so the revoke opens a fresh connection (DISCONNECT_AT_START is ignored on a reused one)
        server.enqueue(MockResponse().setBody(projectsBody()).setHeader("Connection", "close"))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.start()
        vm.onCodeScanned(code(project = "p1"))
        vm.register()
        val r = vm.unregister(force = false); assertTrue(r is UnregisterResult.Failed)
        assertNotNull(store.config())
        assertEquals(UnregisterResult.Revoked, vm.unregister(force = true))
        assertNull(store.config())
    }

    private suspend fun reRegister(revokeCode: Int): RegStep {
        server.enqueue(MockResponse().setBody(projectsBody()))
        server.enqueue(MockResponse().setBody(projectsBody()))
        server.enqueue(MockResponse().setResponseCode(revokeCode))
        server.start()
        vm.onCodeScanned(code(project = "p1"))
        vm.register()
        vm.onCodeScanned(code(project = "p1", key = "cd.newkey"))
        vm.register()
        return vm.uiState.value.step
    }

    @Test
    fun re_register_with_failed_old_key_revoke_reports_a_warning_in_done() = runBlocking {
        val done = reRegister(500) as RegStep.Done
        assertNotNull(done.revokeWarning)
        assertEquals("cd.newkey", store.config()!!.apiKey)
    }

    @Test
    fun re_register_with_successful_old_key_revoke_has_no_warning() = runBlocking {
        assertEquals(RegStep.Done(null), reRegister(204))
    }

    @Test
    fun register_twice_back_to_back_saves_once() = runBlocking {
        var wraps = 0
        val counting = object : KeyWrapper by XorWrapper() {
            override fun wrap(plain: ByteArray): ByteArray { wraps++; return XorWrapper().wrap(plain) }
        }
        val countingVm = RegistrationViewModel(RegistrationStore(prefs, counting, "1.0"), OpieDeviceApi(), dispatcherForTests = kotlinx.coroutines.Dispatchers.Unconfined)
        server.enqueue(MockResponse().setBody(projectsBody()))
        server.start()
        countingVm.onCodeScanned(code(project = "p1"))
        kotlinx.coroutines.coroutineScope {
            launch { countingVm.register() }
            launch { countingVm.register() }
        }
        assertEquals(1, wraps)
    }
}
