package io.horizontalsystems.evidence.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpieDeviceApiTest {
    private val s = MockWebServer()
    private val api = OpieDeviceApi()

    @After
    fun tearDown() = s.shutdown()

    private fun url() = s.url("/").toString().trimEnd('/')

    @Test
    fun captureProjects_follows_pagination_and_maps_attribution() = runBlocking {
        s.enqueue(MockResponse().setBody("""{"count":2,"next":"${'$'}{NEXT}","results":[{"uuid":"p1","name":"Retail","crypto_attribution_enabled":true}]}"""
            .replace("${'$'}{NEXT}", "/opie/api/v1/projects/?capture_eligible=1&page=2")))
        s.enqueue(MockResponse().setBody("""{"count":2,"next":null,"results":[{"uuid":"p2","name":"Exchange","crypto_attribution_enabled":false}]}"""))
        s.start()
        val r = api.captureProjects(url(), "k")
        assertEquals(
            DeviceApiResult.Ok(listOf(OpieProject("p1", "Retail", true), OpieProject("p2", "Exchange", false))),
            r,
        )
        val first = s.takeRequest()
        assertEquals("/opie/api/v1/projects/?capture_eligible=1", first.path)
        assertEquals("Api-Key k", first.getHeader("Authorization"))
    }

    @Test
    fun captureProjects_401_is_unauthorized() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(401))
        s.start()
        assertEquals(DeviceApiResult.Unauthorized, api.captureProjects(url(), "k"))
    }

    @Test
    fun captureProjects_offline_is_failed() = runBlocking {
        s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        s.start()
        assertTrue(api.captureProjects(url(), "k") is DeviceApiResult.Failed)
    }

    @Test
    fun selfRevoke_204_and_401_both_mean_revoked() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(204))
        s.enqueue(MockResponse().setResponseCode(401))
        s.start()
        assertEquals(DeviceApiResult.Ok(Unit), api.selfRevoke(url(), "k"))
        assertEquals(DeviceApiResult.Ok(Unit), api.selfRevoke(url(), "k"))
        val req = s.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/opie/api/v1/devices/self/revoke/", req.path)
    }

    @Test
    fun selfRevoke_server_error_is_failed() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(503))
        s.start()
        assertTrue(api.selfRevoke(url(), "k") is DeviceApiResult.Failed)
    }
}
