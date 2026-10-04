package io.horizontalsystems.evidence.store

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EvidenceDaoTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), EvidenceDb::class.java)
        .allowMainThreadQueries()
        .build()
    private val dao = db.dao()

    @After
    fun tearDown() = db.close()

    private fun capture(id: String, state: String = CaptureState.OPEN) =
        CaptureEntity(id, null, "ethereum", "0xabc", null, "p", "mobile_app", "[\"scan_frame\"]", state, 1L)

    @Test
    fun insert_bundle_with_artifacts_and_read_back() = runBlocking {
        dao.upsertCapture(capture("req-1"))
        dao.insertArtifact(ArtifactEntity(0, "req-1", "scan_frame", "/f.png", "cafe", null, "PENDING"))
        assertEquals(1, dao.pendingArtifacts("req-1").size)
    }

    @Test
    fun pending_artifacts_exclude_verified_and_other_bundles() = runBlocking {
        dao.upsertCapture(capture("req-1"))
        dao.upsertCapture(capture("req-2"))
        val id = dao.insertArtifact(ArtifactEntity(0, "req-1", "scan_frame", "/a.png", "a", null, ArtifactState.PENDING))
        dao.insertArtifact(ArtifactEntity(0, "req-1", "screenshot", "/b.png", "b", null, ArtifactState.PENDING))
        dao.insertArtifact(ArtifactEntity(0, "req-2", "screenshot", "/c.png", "c", null, ArtifactState.PENDING))
        dao.markArtifact(id, ArtifactState.VERIFIED, "a")
        assertEquals(listOf("/b.png"), dao.pendingArtifacts("req-1").map { it.filePath })
        assertEquals(ArtifactState.VERIFIED, dao.artifacts("req-1").first { it.id == id }.state)
        assertEquals("a", dao.artifacts("req-1").first { it.id == id }.serverFileHash)
    }

    @Test
    fun open_bundles_exclude_complete() = runBlocking {
        dao.upsertCapture(capture("req-1"))
        dao.upsertCapture(capture("req-2", CaptureState.READY))
        dao.upsertCapture(capture("req-3", CaptureState.COMPLETE))
        assertEquals(setOf("req-1", "req-2"), dao.openBundles().first().map { it.clientRequestId }.toSet())
    }

    @Test
    fun mark_state_and_server_capture_id_persist() = runBlocking {
        dao.upsertCapture(capture("req-1", CaptureState.READY))
        dao.setServerCaptureId("req-1", "cap-9")
        dao.markState("req-1", CaptureState.REGISTERED)
        val c = dao.capture("req-1")!!
        assertEquals("cap-9", c.serverCaptureId)
        assertEquals(CaptureState.REGISTERED, c.state)
        assertEquals(listOf("req-1"), dao.capturesInState(CaptureState.REGISTERED).map { it.clientRequestId })
    }

    @Test
    fun resealed_artifact_updates_path_and_hash() = runBlocking {
        dao.upsertCapture(capture("req-1"))
        val id = dao.insertArtifact(ArtifactEntity(0, "req-1", "scan_frame", "/a.png", "a", null, ArtifactState.PENDING))
        dao.updateSealed(id, "/a2.png", "a2")
        val a = dao.artifacts("req-1").single()
        assertEquals("/a2.png", a.filePath)
        assertEquals("a2", a.clientSha256)
    }
}
