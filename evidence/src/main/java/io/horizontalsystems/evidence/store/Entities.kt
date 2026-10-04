package io.horizontalsystems.evidence.store

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object CaptureState {
    const val OPEN = "OPEN"
    const val READY = "READY"
    const val REGISTERED = "REGISTERED"
    const val COMPLETE = "COMPLETE"
    const val FAILED = "FAILED"
}

object ArtifactState {
    const val PENDING = "PENDING"
    const val UPLOADED = "UPLOADED"
    const val VERIFIED = "VERIFIED"
    const val VERIFY_FAILED = "VERIFY_FAILED"
}

/** One bundle = one Opie evidence_capture. State: OPEN → READY → REGISTERED → COMPLETE, or FAILED. */
@Entity(tableName = "capture")
data class CaptureEntity(
    @PrimaryKey val clientRequestId: String,
    val serverCaptureId: String?,
    val network: String?,
    val address: String?,
    val notes: String?,
    val projectUuid: String,
    val captureMethod: String,
    // JSON array of artifact kinds, declared in full at registration
    val expectedArtifacts: String,
    val state: String,
    val openedAt: Long,
    // JSON array of net.Candidate, sent in the registration envelope
    val candidatesJson: String = "[]",
    // client_captured_at for the envelope
    val capturedAtIso: String = "",
)

/**
 * Sealed bytes live on disk at [filePath], not in the DB. [sourcePath] is the unsealed image and
 * [sealMetaJson] its seal.SealMeta, kept so the uploader can re-seal with the server capture id.
 * State: PENDING → UPLOADED → VERIFIED, or VERIFY_FAILED.
 */
@Entity(
    tableName = "artifact",
    foreignKeys = [
        ForeignKey(
            entity = CaptureEntity::class,
            parentColumns = ["clientRequestId"],
            childColumns = ["clientRequestId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("clientRequestId")],
)
data class ArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val clientRequestId: String,
    val artifactKind: String,
    val filePath: String,
    val clientSha256: String,
    val serverFileHash: String?,
    val state: String,
    val sourcePath: String? = null,
    val sealMetaJson: String? = null,
)
