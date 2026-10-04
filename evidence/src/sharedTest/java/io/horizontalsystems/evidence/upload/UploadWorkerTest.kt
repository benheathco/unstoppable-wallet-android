package io.horizontalsystems.evidence.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import io.horizontalsystems.evidence.EvidenceConfig
import io.horizontalsystems.evidence.net.OpieClient
import io.horizontalsystems.evidence.seal.SealMeta
import io.horizontalsystems.evidence.seal.sealPng
import io.horizontalsystems.evidence.seal.sha256Hex
import io.horizontalsystems.evidence.store.ArtifactEntity
import io.horizontalsystems.evidence.store.ArtifactState
import io.horizontalsystems.evidence.store.CaptureEntity
import io.horizontalsystems.evidence.store.CaptureState
import io.horizontalsystems.evidence.store.EvidenceDb
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

@RunWith(AndroidJUnit4::class)
class UploadWorkerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, EvidenceDb::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    private val server = MockWebServer()
    private val dir = File(context.filesDir, "evidence-test").apply { mkdirs() }

    private val meta = SealMeta("req-1", "Ethereum", "0xabc", null, "2026-10-04T00:00:00Z", "dev-1", "1.0", null)
    private val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }

    @After
    fun tearDown() {
        UploadWorker.dependencies = null
        server.shutdown()
        db.close()
        dir.deleteRecursively()
    }

    private fun config(serverUrl: String) = EvidenceConfig(serverUrl, "k", "proj-1", "dev-1", "1.0")

    private fun deps(serverUrl: String) =
        UploadDependencies(dao, OpieClient(retryDelayMs = 10), { config(serverUrl) })

    private fun liveUrl() = server.url("/").toString().trimEnd('/')

    // Nothing listens on port 1: every request fails at connect, like a device with no network
    private val offlineUrl = "http://127.0.0.1:1"

    private suspend fun seedReadyBundle(serverCaptureId: String? = null, state: String = CaptureState.READY): Long {
        dao.upsertCapture(
            CaptureEntity(
                "req-1", serverCaptureId, "Ethereum", "0xabc", "scan", "proj-1", "mobile_app",
                "[\"scan_frame\"]", state, 1L,
                candidatesJson = """[{"identifier":"0xabc","network":"Ethereum","subject_type":"DEPOSIT_ADDRESS"}]""",
                capturedAtIso = "2026-10-04T00:00:00Z",
            )
        )
        val sourceFile = File(dir, "src.png").apply { outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val sealed = sealPng(source, meta)
        val sealedFile = File(dir, "sealed.png").apply { writeBytes(sealed) }
        return dao.insertArtifact(
            ArtifactEntity(
                0, "req-1", "scan_frame", sealedFile.path, sha256Hex(sealed), null, ArtifactState.PENDING,
                sourcePath = sourceFile.path, sealMetaJson = Json.encodeToString(SealMeta.serializer(), meta),
            )
        )
    }

    // The bytes the worker must upload once the server id is known: the source re-sealed with it
    private fun resealedHash(captureId: String) = sha256Hex(sealPng(source, meta.copy(captureId = captureId)))

    private fun runWorker(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(UploadWorker.inputFor("req-1"))
            .build()
            .doWork()
    }

    private fun MockWebServer.requests(): List<RecordedRequest> = List(requestCount) { takeRequest() }
    private fun List<RecordedRequest>.capturePosts() = count { it.path == OpieClient.EVIDENCE_CAPTURES_PATH }

    @Test
    fun ready_bundle_registers_then_uploads_then_completes() = runBlocking {
        val artifactId = seedReadyBundle()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"uuid":"u1","file_hash":"${resealedHash("cap-9")}"}"""))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        assertEquals(ListenableWorker.Result.success(), runWorker())

        val capture = dao.capture("req-1")!!
        assertEquals(CaptureState.COMPLETE, capture.state)
        assertEquals("cap-9", capture.serverCaptureId)
        val artifact = dao.artifacts("req-1").single { it.id == artifactId }
        assertEquals(ArtifactState.VERIFIED, artifact.state)
        assertEquals(resealedHash("cap-9"), artifact.clientSha256)
        assertEquals(1, server.requests().capturePosts())
    }

    @Test
    fun registration_envelope_declares_expected_artifacts_and_candidates() = runBlocking {
        seedReadyBundle()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"file_hash":"${resealedHash("cap-9")}"}"""))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())
        runWorker()
        val body = server.takeRequest().body.readUtf8()
        listOf("\"client_request_id\":\"req-1\"", "\"expected_artifacts\":[\"scan_frame\"]", "\"project\":\"proj-1\"", "DEPOSIT_ADDRESS")
            .forEach { assertTrue(it, body.contains(it)) }
    }

    @Test
    fun offline_then_retry_keeps_single_capture() = runBlocking {
        seedReadyBundle()
        UploadWorker.dependencies = deps(offlineUrl)

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        val untouched = dao.capture("req-1")!!
        assertEquals(CaptureState.READY, untouched.state)
        assertNull(untouched.serverCaptureId)
        assertEquals(ArtifactState.PENDING, dao.artifacts("req-1").single().state)

        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"file_hash":"${resealedHash("cap-9")}"}"""))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(CaptureState.COMPLETE, dao.capture("req-1")!!.state)
        assertEquals(1, server.requests().capturePosts())
    }

    @Test
    fun registered_bundle_resumes_without_registering_again() = runBlocking {
        // Process died after registration: the server id is already on the row
        seedReadyBundle(serverCaptureId = "cap-9", state = CaptureState.REGISTERED)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"file_hash":"${resealedHash("cap-9")}"}"""))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(CaptureState.COMPLETE, dao.capture("req-1")!!.state)
        assertEquals(0, server.requests().capturePosts())
    }

    @Test
    fun hash_mismatch_marks_verify_failed_and_never_completes() = runBlocking {
        seedReadyBundle()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"file_hash":"deadbeef"}"""))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        runWorker()
        val artifact = dao.artifacts("req-1").single()
        assertEquals(ArtifactState.VERIFY_FAILED, artifact.state)
        assertEquals("deadbeef", artifact.serverFileHash)
        assertEquals(CaptureState.FAILED, dao.capture("req-1")!!.state)
    }

    @Test
    fun rejected_registration_fails_the_bundle_without_retry() = runBlocking {
        seedReadyBundle()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"project":"bad"}"""))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        assertEquals(ListenableWorker.Result.failure(), runWorker())
        assertEquals(CaptureState.FAILED, dao.capture("req-1")!!.state)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun unauthorized_registration_retries_and_keeps_bundle_queued() = runBlocking {
        seedReadyBundle()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(CaptureState.READY, dao.capture("req-1")!!.state)
    }

    @Test
    fun unauthorized_registration_reports_auth_rejected() = runBlocking {
        seedReadyBundle()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        var rejected = false
        UploadWorker.dependencies = UploadDependencies(dao, OpieClient(retryDelayMs = 10), { config(liveUrl()) }) { rejected = true }
        runWorker()
        assertTrue(rejected)
    }

    @Test
    fun candidates_rejected_registers_again_without_them_and_keeps_them_in_notes() = runBlocking {
        seedReadyBundle()
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"candidates":["Candidates require a project with crypto attribution enabled."]}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"file_hash":"${resealedHash("cap-9")}"}"""))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(CaptureState.COMPLETE, dao.capture("req-1")!!.state)
        server.takeRequest()
        val retryBody = server.takeRequest().body.readUtf8()
        assertTrue(retryBody, !retryBody.contains("\"candidates\""))
        assertTrue(retryBody, retryBody.contains("0xabc"))
        assertTrue(retryBody, retryBody.contains("\"client_request_id\":\"req-1\""))
    }

    @Test
    fun missing_artifact_file_marks_it_verify_failed_instead_of_crashing() = runBlocking {
        seedReadyBundle()
        File(dir, "src.png").delete()
        File(dir, "sealed.png").delete()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(ArtifactState.VERIFY_FAILED, dao.artifacts("req-1").single().state)
        assertEquals(CaptureState.FAILED, dao.capture("req-1")!!.state)
    }

    @Test
    fun finalize_without_artifacts_leaves_bundle_open() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        dao.upsertCapture(CaptureEntity("req-2", null, null, null, null, "proj-1", "mobile_app", "[]", CaptureState.OPEN, 1L))

        finalizeBundle(context, dao, "req-2")

        assertEquals(CaptureState.OPEN, dao.capture("req-2")!!.state)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(UploadWorker.uniqueName("req-2")).get().isEmpty())
    }

    @Test
    fun upload_server_error_retries_and_keeps_artifact_pending() = runBlocking {
        seedReadyBundle()
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"cap-9"}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()
        UploadWorker.dependencies = deps(liveUrl())

        assertEquals(ListenableWorker.Result.retry(), runWorker())
        assertEquals(CaptureState.REGISTERED, dao.capture("req-1")!!.state)
        assertEquals(ArtifactState.PENDING, dao.artifacts("req-1").single().state)
    }

    @Test
    fun missing_dependencies_retries_instead_of_failing() = runBlocking {
        seedReadyBundle()
        assertEquals(ListenableWorker.Result.retry(), runWorker())
    }

    @Test
    fun finalize_marks_ready_declares_artifacts_and_enqueues_upload() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        seedReadyBundle(state = CaptureState.OPEN)
        dao.insertArtifact(ArtifactEntity(0, "req-1", "screenshot", "/s.png", "s", null, ArtifactState.PENDING))

        finalizeBundle(context, dao, "req-1")

        val capture = dao.capture("req-1")!!
        assertEquals(CaptureState.READY, capture.state)
        assertEquals("""["scan_frame","screenshot"]""", capture.expectedArtifacts)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UploadWorker.uniqueName("req-1")).get()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
    }
}
