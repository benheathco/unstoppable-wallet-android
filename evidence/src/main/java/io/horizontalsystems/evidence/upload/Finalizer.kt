package io.horizontalsystems.evidence.upload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.horizontalsystems.evidence.store.CaptureState
import io.horizontalsystems.evidence.store.EvidenceDao
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

/**
 * Closes an OPEN bundle: declares its full artifact list (the bundle is complete now, so
 * expected_artifacts is never grown server-side), marks it READY and queues the upload. The
 * upload waits for connectivity, so finalizing offline loses nothing.
 */
suspend fun finalizeBundle(context: Context, dao: EvidenceDao, clientRequestId: String) {
    val capture = dao.capture(clientRequestId) ?: return
    if (capture.state != CaptureState.OPEN) return
    val kinds = dao.artifacts(clientRequestId).map { it.artifactKind }.distinct()
    dao.upsertCapture(
        capture.copy(
            expectedArtifacts = Json.encodeToString(ListSerializer(String.serializer()), kinds),
            state = CaptureState.READY,
        )
    )
    enqueueUpload(context, clientRequestId)
}

fun enqueueUpload(context: Context, clientRequestId: String) {
    val request = OneTimeWorkRequestBuilder<UploadWorker>()
        .setInputData(UploadWorker.inputFor(clientRequestId))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
    WorkManager.getInstance(context)
        .enqueueUniqueWork(UploadWorker.uniqueName(clientRequestId), ExistingWorkPolicy.KEEP, request)
}
