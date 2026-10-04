package io.horizontalsystems.evidenceui

import android.content.Context
import io.horizontalsystems.evidence.DefaultEvidenceRecorder
import io.horizontalsystems.evidence.net.OpieDeviceApi
import io.horizontalsystems.evidenceui.registration.KeystoreKeyWrapper
import io.horizontalsystems.evidenceui.registration.RegistrationStore

/** Process singletons for the evidence build (manual DI, like walletkit's App). */
object EvidenceGraph {
    lateinit var store: RegistrationStore
        private set
    lateinit var recorder: DefaultEvidenceRecorder
        private set
    val api = OpieDeviceApi()

    @Synchronized
    fun init(context: Context) {
        if (::store.isInitialized) return
        val app = context.applicationContext
        val version = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
        store = RegistrationStore(
            app.getSharedPreferences(RegistrationStore.PREFS_NAME, Context.MODE_PRIVATE),
            KeystoreKeyWrapper(),
            version,
        )
        recorder = DefaultEvidenceRecorder(app, { store.config() }, onAuthRejected = { used -> store.onAuthRejected(used) })
    }
}
