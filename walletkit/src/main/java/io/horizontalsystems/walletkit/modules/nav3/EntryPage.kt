package io.horizontalsystems.walletkit.modules.nav3

import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.horizontalsystems.walletkit.core.App
import io.horizontalsystems.walletkit.core.evidence.EvidenceHooksRegistry
import io.horizontalsystems.walletkit.modules.intro.IntroScreen
import kotlinx.serialization.Serializable

@Serializable
data object EntryPage : HSPage(accessibleWhileLocked = true) {
    @Composable
    override fun GetContent(navigation: HSNavigation) {
        val mainShowedOnce by
            App.localStorage.mainShowedOnceFlow.collectAsStateWithLifecycle()

        val hooks = EvidenceHooksRegistry.hooks
        val needsRegistration by hooks.needsRegistration.collectAsStateWithLifecycle()
        val gate = hooks.registrationGate

        Crossfade(mainShowedOnce to (needsRegistration && gate != null)) { (shownOnce, gated) ->
            when {
                !shownOnce -> IntroScreen()
                gated -> gate!!.GetContent(navigation)
                else -> MainScreen(navigation, contentKey())
            }
        }
    }
}
