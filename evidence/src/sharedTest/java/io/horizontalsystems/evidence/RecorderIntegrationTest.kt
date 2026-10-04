package io.horizontalsystems.evidence

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.horizontalsystems.evidence.model.DecodedScan
import io.horizontalsystems.evidence.model.EvidenceStatus
import io.horizontalsystems.evidence.model.SendRecord
import io.horizontalsystems.evidence.seal.sha256Hex
import io.horizontalsystems.evidence.store.ArtifactEntity
import io.horizontalsystems.evidence.store.ArtifactState
import io.horizontalsystems.evidence.store.CaptureEntity
import io.horizontalsystems.evidence.store.CaptureState
import io.horizontalsystems.evidence.store.EvidenceDao
import io.horizontalsystems.evidence.store.EvidenceDb
import io.horizontalsystems.evidence.upload.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class RecorderIntegrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = Room.inMemoryDatabaseBuilder(context, EvidenceDb::class.java).allowMainThreadQueries().build()
    private val dao = db.dao()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO.limitedParallelism(1))
    private val config = EvidenceConfig("https://opie.test", "k", "proj-1", "dev-1", "1.0")

    init {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        UploadWorker.dependencies = null
    }

    // Long-lived jobs each recorder starts (its status collector) — never part of "idle"
    private val collectors = mutableSetOf<Job>()

    private fun recorder(config: EvidenceConfig? = this.config, scope: CoroutineScope = this.scope) =
        DefaultEvidenceRecorder(context, { config }, dao, scope, now = { "2026-10-04T00:00:00Z" })
            .also { collectors += job.children }

    private fun frame() = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }

    private fun send(txHash: String? = null) =
        SendRecord("0xabc", "ethereum", "USDT", "0xdac", "100.0", "0.5", null, txHash, null, "2026-10-04T00:01:00Z")

    // Async sealing runs on the recorder's serial queue; wait for it to drain
    private fun awaitIdle() = runBlocking { withTimeout(10_000) { (job.children - collectors).forEach { it.join() } } }

    private fun only() = runBlocking { dao.openBundles().first().single() }

    @Test
    fun recordScan_writes_open_bundle_with_sealed_scan_frame_on_disk() = runBlocking {
        recorder().recordScan(frame(), DecodedScan("ethereum:0xabc", "0xabc", "ethereum", notes = "Shop"))
        awaitIdle()

        val capture = only()
        assertEquals(CaptureState.OPEN, capture.state)
        assertEquals("proj-1", capture.projectUuid)
        assertEquals("mobile_app", capture.captureMethod)
        assertTrue(capture.candidatesJson.contains("\"identifier\":\"0xabc\""))
        assertTrue(capture.candidatesJson.contains("\"network\":\"Ethereum\""))
        assertTrue(capture.notes!!.contains("Shop"))

        val artifact = dao.artifacts(capture.clientRequestId).single()
        assertEquals("scan_frame", artifact.artifactKind)
        assertEquals(ArtifactState.PENDING, artifact.state)
        val sealed = File(artifact.filePath)
        assertTrue(sealed.exists())
        assertEquals(sha256Hex(sealed.readBytes()), artifact.clientSha256)
        assertTrue(File(artifact.sourcePath!!).exists())
        assertNotNull(artifact.sealMetaJson)
    }

    @Test
    fun recordScan_persists_the_bundle_before_returning() = runBlocking {
        // A queue that never runs: only the synchronous part of recordScan can have happened
        val stalled = CoroutineScope(StandardTestDispatcher())
        recorder(scope = stalled).recordScan(frame(), DecodedScan("0xabc", "0xabc", "ethereum"))
        assertEquals(CaptureState.OPEN, only().state)
        stalled.cancel()
    }

    @Test
    fun recordScan_with_garbage_payload_still_records_a_bundle() = runBlocking {
        recorder().recordScan(frame(), DecodedScan("hello world", null, null))
        awaitIdle()
        val capture = only()
        assertEquals("[]", capture.candidatesJson)
        assertTrue(capture.notes!!.contains("hello world"))
        assertEquals(1, dao.artifacts(capture.clientRequestId).size)
    }

    @Test
    fun recordScan_when_unenrolled_records_nothing() = runBlocking {
        recorder(config = null).recordScan(frame(), DecodedScan("0xabc", "0xabc", "ethereum"))
        awaitIdle()
        assertTrue(dao.openBundles().first().isEmpty())
    }

    @Test
    fun recordSend_with_tx_attaches_to_scan_bundle_and_finalizes() = runBlocking {
        val recorder = recorder()
        recorder.recordScan(frame(), DecodedScan("0xabc", "0xabc", "ethereum"))
        recorder.recordSend(send(txHash = "0xtx"), frame())
        awaitIdle()

        val capture = dao.capturesInState(CaptureState.READY).single()
        assertEquals("""["scan_frame","screenshot"]""", capture.expectedArtifacts)
        assertTrue(capture.notes!!.contains("100.0"))
        assertTrue(capture.notes!!.contains("0xtx"))
        // The send's payee is the scanned address: one candidate, not two
        assertEquals(1, Regex("\"identifier\"").findAll(capture.candidatesJson).count())
        assertEquals(listOf("scan_frame", "screenshot"), dao.artifacts(capture.clientRequestId).map { it.artifactKind })
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(UploadWorker.uniqueName(capture.clientRequestId)).get()
        assertEquals(1, work.size)
    }

    @Test
    fun queued_work_runs_in_call_order_even_when_a_job_suspends() = runBlocking {
        // The scan's sealing job suspends inside its first DAO read; the send's job must still wait
        val slowFirstRead = object : EvidenceDao by dao {
            private var first = true
            override suspend fun artifacts(clientRequestId: String): List<ArtifactEntity> {
                if (first) {
                    first = false
                    delay(300)
                }
                return dao.artifacts(clientRequestId)
            }
        }
        val recorder = DefaultEvidenceRecorder(context, { config }, slowFirstRead, scope, now = { "2026-10-04T00:00:00Z" })
            .also { collectors += job.children }
        recorder.recordScan(frame(), DecodedScan("0xabc", "0xabc", "ethereum"))
        recorder.recordSend(send(txHash = "0xtx"), frame())
        awaitIdle()

        val capture = dao.capturesInState(CaptureState.READY).single()
        assertEquals("""["scan_frame","screenshot"]""", capture.expectedArtifacts)
    }

    @Test
    fun recordSend_without_tx_keeps_bundle_open() = runBlocking {
        val recorder = recorder()
        recorder.recordScan(frame(), DecodedScan("0xabc", "0xabc", "ethereum"))
        recorder.recordSend(send(), frame())
        awaitIdle()
        val capture = only()
        assertEquals(CaptureState.OPEN, capture.state)
        assertEquals(2, dao.artifacts(capture.clientRequestId).size)
    }

    @Test
    fun recordSend_without_scan_opens_its_own_bundle() = runBlocking {
        recorder().recordSend(send(), frame())
        awaitIdle()
        val capture = only()
        assertTrue(capture.candidatesJson.contains("0xabc"))
        assertEquals(listOf("screenshot"), dao.artifacts(capture.clientRequestId).map { it.artifactKind })
    }

    @Test
    fun recordSend_renders_the_confirmation_view() = runBlocking {
        val view = View(context).apply {
            setBackgroundColor(Color.WHITE)
            layout(0, 0, 120, 60)
        }
        recorder().recordSend(send(), view)
        awaitIdle()
        val artifact = dao.artifacts(only().clientRequestId).single()
        assertTrue(File(artifact.filePath).length() > 0)
    }

    @Test
    fun render_failure_still_records_the_send_and_finalizes() = runBlocking {
        val recorder = recorder()
        recorder.recordScan(frame(), DecodedScan("0xabc", "0xabc", "ethereum"))
        val broken = object : View(context) {
            override fun draw(canvas: android.graphics.Canvas) = throw IllegalArgumentException("hardware bitmap")
        }.apply { layout(0, 0, 50, 50) }
        recorder.recordSend(send(txHash = "0xtx"), broken)
        awaitIdle()

        val capture = dao.capturesInState(CaptureState.READY).single()
        assertTrue(capture.notes!!.contains("0xtx"))
        assertEquals("""["scan_frame"]""", capture.expectedArtifacts)
    }

    @Test
    fun a_new_recorder_attaches_a_send_to_the_bundle_left_open_before_process_death() = runBlocking {
        recorder().recordScan(frame(), DecodedScan("0xabc", "0xabc", "ethereum"))
        awaitIdle()
        recorder().recordSend(send(txHash = "0xtx"), frame())
        awaitIdle()

        val capture = dao.capturesInState(CaptureState.READY).single()
        assertEquals(listOf("scan_frame", "screenshot"), dao.artifacts(capture.clientRequestId).map { it.artifactKind })
    }

    @Test
    fun startup_requeues_bundles_finalized_before_process_death() = runBlocking {
        dao.upsertCapture(CaptureEntity("req-r", null, null, null, null, "proj-1", "mobile_app", "[\"scan_frame\"]", CaptureState.READY, 1L))
        dao.upsertCapture(CaptureEntity("req-g", "cap-1", null, null, null, "proj-1", "mobile_app", "[\"scan_frame\"]", CaptureState.REGISTERED, 1L))
        recorder()
        val workManager = WorkManager.getInstance(context)
        withTimeout(10_000) {
            while (listOf("req-r", "req-g").any { workManager.getWorkInfosForUniqueWork(UploadWorker.uniqueName(it)).get().isEmpty() }) {
                delay(50)
            }
        }
    }

    @Test
    fun status_reports_enrollment_and_pending_bundles() = runBlocking {
        val recorder = recorder()
        recorder.recordScan(frame(), DecodedScan("0xabc", "0xabc", "ethereum"))
        awaitIdle()
        val status = withTimeout(5_000) { recorder.status.first { it.pendingBundles == 1 } }
        assertEquals(EvidenceStatus(enrolled = true, pendingBundles = 1), status)
        assertFalse(recorder(config = null).status.value.enrolled)
    }

    @Test
    fun recorder_never_throws_into_the_wallet() {
        val recycled = frame().apply { recycle() }
        recorder().recordScan(recycled, DecodedScan("0xabc", "0xabc", "ethereum"))
        awaitIdle()
    }
}
