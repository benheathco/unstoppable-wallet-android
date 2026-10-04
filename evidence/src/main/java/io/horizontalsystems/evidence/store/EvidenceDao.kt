package io.horizontalsystems.evidence.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface EvidenceDao {
    @Upsert
    suspend fun upsertCapture(capture: CaptureEntity)

    @Insert
    suspend fun insertArtifact(artifact: ArtifactEntity): Long

    @Query("SELECT * FROM capture WHERE clientRequestId = :clientRequestId")
    suspend fun capture(clientRequestId: String): CaptureEntity?

    @Query("SELECT * FROM capture WHERE state = :state ORDER BY openedAt")
    suspend fun capturesInState(state: String): List<CaptureEntity>

    // Every bundle not yet COMPLETE — the operator's pending queue
    @Query("SELECT * FROM capture WHERE state != 'COMPLETE' ORDER BY openedAt")
    fun openBundles(): Flow<List<CaptureEntity>>

    @Query("UPDATE capture SET state = :state WHERE clientRequestId = :clientRequestId")
    suspend fun markState(clientRequestId: String, state: String)

    @Query("UPDATE capture SET serverCaptureId = :serverCaptureId WHERE clientRequestId = :clientRequestId")
    suspend fun setServerCaptureId(clientRequestId: String, serverCaptureId: String)

    @Query("SELECT * FROM artifact WHERE clientRequestId = :clientRequestId ORDER BY id")
    suspend fun artifacts(clientRequestId: String): List<ArtifactEntity>

    @Query("SELECT * FROM artifact WHERE clientRequestId = :clientRequestId AND state = 'PENDING' ORDER BY id")
    suspend fun pendingArtifacts(clientRequestId: String): List<ArtifactEntity>

    @Query("UPDATE artifact SET state = :state, serverFileHash = :serverFileHash WHERE id = :id")
    suspend fun markArtifact(id: Long, state: String, serverFileHash: String?)

    @Query("UPDATE artifact SET filePath = :filePath, clientSha256 = :clientSha256 WHERE id = :id")
    suspend fun updateSealed(id: Long, filePath: String, clientSha256: String)
}
