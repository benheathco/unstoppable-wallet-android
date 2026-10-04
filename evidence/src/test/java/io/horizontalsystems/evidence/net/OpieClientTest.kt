package io.horizontalsystems.evidence.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpieClientTest {
    private val s = MockWebServer()
    private val client = OpieClient(retryDelayMs = 10)

    @After
    fun tearDown() = s.shutdown()

    private fun url() = s.url("/").toString().trimEnd('/')

    @Test
    fun `createCapture retries once on 500 then succeeds`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(500))
        s.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        s.start()
        val r = client.createCapture(url(), "k", "{}")
        assertEquals(CaptureResult.Ok("cap-9"), r)
        assertEquals(2, s.requestCount)
    }

    @Test
    fun `createCapture posts json with api key auth`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        s.start()
        client.createCapture(url(), "secret", """{"a":1}""")
        val req = s.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/opie/api/v1/evidence-captures/", req.path)
        assertEquals("Api-Key secret", req.getHeader("Authorization"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"a":1}""", req.body.readUtf8())
    }

    @Test
    fun `createCapture treats 4xx as final without retry`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(400).setBody("""{"candidates":"bad"}"""))
        s.start()
        val r = client.createCapture(url(), "k", "{}")
        assertTrue(r is CaptureResult.Err && !r.retryable)
        assertEquals(1, s.requestCount)
    }

    @Test
    fun `createCapture auth and throttling rejections are retryable later`() = runBlocking {
        // 408 is left out: OkHttp itself re-sends a request that got a 408
        listOf(401, 403, 429).forEach { s.enqueue(MockResponse().setResponseCode(it)) }
        s.start()
        repeat(3) {
            val r = client.createCapture(url(), "k", "{}")
            assertTrue("$r", r is CaptureResult.Err && r.retryable)
        }
        assertEquals(3, s.requestCount)
    }

    @Test
    fun `uploadArtifact throttling is retryable`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(429))
        s.start()
        val r = client.uploadArtifact(url(), "k", ByteArray(3), "f.png", "p", "cap-9", "screenshot", "cafe")
        assertTrue(r is UploadResult.Err && r.retryable)
    }

    @Test
    fun `createCapture retries an id-less 2xx`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(200).setBody("""{}"""))
        s.enqueue(MockResponse().setResponseCode(200).setBody("""{"id":"cap-9"}"""))
        s.start()
        assertEquals(CaptureResult.Ok("cap-9"), client.createCapture(url(), "k", "{}"))
        assertEquals(2, s.requestCount)
    }

    @Test
    fun `createCapture network failure twice is retryable`() = runBlocking {
        s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        s.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        s.start()
        val r = client.createCapture(url(), "k", "{}")
        assertTrue(r is CaptureResult.Err && r.retryable)
    }

    @Test
    fun `uploadArtifact flags hash mismatch`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(201).setBody("""{"uuid":"u1","file_hash":"deadbeef"}"""))
        s.start()
        val r = client.uploadArtifact(url(), "k", ByteArray(3), "f.png", "p", "cap-9", "screenshot", clientSha256 = "cafe")
        assertEquals(UploadResult.HashMismatch("deadbeef", "cafe"), r)
    }

    @Test
    fun `uploadArtifact verifies matching hash case-insensitively and sends multipart fields`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(201).setBody("""{"uuid":"u1","file_hash":"CAFE"}"""))
        s.start()
        val r = client.uploadArtifact(url(), "k", "png".toByteArray(), "f.png", "p-1", "cap-9", "scan_frame", "cafe")
        assertEquals(UploadResult.Verified, r)
        val req = s.takeRequest()
        assertEquals("/opie/api/v1/vault-files/?allow_duplicate=true", req.path)
        assertEquals("Api-Key k", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        listOf(
            "name=\"evidence_capture\"", "cap-9", "name=\"artifact_kind\"", "scan_frame",
            "name=\"project_uuid\"", "p-1", "filename=\"f.png\"", "png",
        ).forEach { assertTrue(it, body.contains(it)) }
    }

    @Test
    fun `uploadArtifact missing file_hash is a mismatch, never a silent pass`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(201).setBody("""{"uuid":"u1"}"""))
        s.start()
        val r = client.uploadArtifact(url(), "k", ByteArray(3), "f.png", "p", "cap-9", "screenshot", "cafe")
        assertEquals(UploadResult.HashMismatch(null, "cafe"), r)
    }

    @Test
    fun `uploadArtifact 5xx is retryable and 4xx is final`() = runBlocking {
        s.enqueue(MockResponse().setResponseCode(503))
        s.enqueue(MockResponse().setResponseCode(400))
        s.start()
        val first = client.uploadArtifact(url(), "k", ByteArray(3), "f.png", "p", "cap-9", "screenshot", "cafe")
        val second = client.uploadArtifact(url(), "k", ByteArray(3), "f.png", "p", "cap-9", "screenshot", "cafe")
        assertTrue(first is UploadResult.Err && first.retryable)
        assertTrue(second is UploadResult.Err && !second.retryable)
    }
}
