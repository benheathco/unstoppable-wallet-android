package io.horizontalsystems.evidenceui.registration

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.horizontalsystems.evidence.net.OpieProject
import io.horizontalsystems.evidenceui.EvidenceGraph
import io.horizontalsystems.evidenceui.R
import io.horizontalsystems.walletkit.core.utils.ModuleField
import io.horizontalsystems.walletkit.modules.nav3.HSNavigation
import io.horizontalsystems.walletkit.modules.nav3.HSPage
import io.horizontalsystems.walletkit.modules.qrscanner.QRScannerActivity
import io.horizontalsystems.walletkit.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.walletkit.ui.compose.components.HsCheckbox
import io.horizontalsystems.walletkit.ui.compose.components.HsDivider
import io.horizontalsystems.walletkit.ui.compose.components.RowUniversal
import io.horizontalsystems.walletkit.ui.compose.components.VSpacer
import io.horizontalsystems.walletkit.ui.compose.components.body_leah
import io.horizontalsystems.walletkit.ui.compose.components.caption_lucian
import io.horizontalsystems.walletkit.ui.compose.components.captionSB_grey
import io.horizontalsystems.walletkit.ui.compose.components.headline2_leah
import io.horizontalsystems.walletkit.ui.compose.components.subhead1_grey
import io.horizontalsystems.walletkit.ui.compose.components.subhead1_lucian
import io.horizontalsystems.walletkit.ui.compose.components.subhead2_grey
import io.horizontalsystems.walletkit.uiv3.components.HSScaffold
import kotlinx.serialization.Serializable

/**
 * Register / re-register. Used as the registration gate (the gate disappears once registered, via
 * needsRegistration) and from the status screen ([popOnDone] = true pops back when finished).
 */
@Serializable
data class RegistrationIntroPage(val popOnDone: Boolean = false) : HSPage() {

    @Composable
    override fun GetContent(navigation: HSNavigation) {
        val vm = viewModel { RegistrationViewModel(EvidenceGraph.store, EvidenceGraph.api) }
        val state by vm.uiState.collectAsState()
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.getStringExtra(ModuleField.SCAN_ADDRESS)?.let(vm::onCodeScanned)
            }
        }

        val step = state.step
        val scope = rememberCoroutineScope()
        // Local state flips synchronously, so a second tap can't start a second registration
        var registering by remember { mutableStateOf(false) }

        // A revoke warning must be acknowledged before the page pops, or it would be lost
        LaunchedEffect(step) {
            if (step is RegStep.Done && step.revokeWarning == null && popOnDone) navigation.removeLastOrNull()
        }
        (step as? RegStep.Done)?.revokeWarning?.let { warning ->
            AlertDialog(
                onDismissRequest = {},
                text = { Text(warning) },
                confirmButton = {
                    TextButton(onClick = { if (popOnDone) navigation.removeLastOrNull() }) { Text("OK") }
                },
            )
        }

        HSScaffold(
            title = stringResource(R.string.Evidence_RegisterTitle),
            onBack = if (popOnDone) ({ navigation.removeLastOrNull() }) else null,
            bottomBar = {
                Column(Modifier.padding(16.dp)) {
                    if (step is RegStep.Confirm) {
                        ButtonPrimaryYellow(
                            modifier = Modifier.fillMaxWidth(),
                            title = stringResource(R.string.Evidence_RegisterDevice),
                            enabled = step.selected != null && !registering,
                            loadingIndicator = registering,
                            onClick = {
                                registering = true
                                scope.launch {
                                    try {
                                        vm.register()
                                    } finally {
                                        registering = false
                                    }
                                }
                            },
                        )
                    } else {
                        ButtonPrimaryYellow(
                            modifier = Modifier.fillMaxWidth(),
                            title = stringResource(R.string.Evidence_ScanCode),
                            enabled = state.consent && step !is RegStep.Checking,
                            loadingIndicator = step is RegStep.Checking,
                            // captureEvidence stays false: a registration code is never evidence
                            onClick = { launcher.launch(QRScannerActivity.getScanQrIntent(context, showPasteButton = true)) },
                        )
                    }
                    state.error?.let {
                        caption_lucian(text = it, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            },
        ) {
            if (step is RegStep.Confirm) {
                RegistrationConfirmContent(step, vm::selectProject)
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    VSpacer(24.dp)
                    Image(painterResource(R.drawable.kyc_mark), contentDescription = null, modifier = Modifier.size(64.dp))
                    VSpacer(16.dp)
                    headline2_leah(stringResource(R.string.Evidence_RegisterTitle))
                    VSpacer(8.dp)
                    subhead2_grey(stringResource(R.string.Evidence_RegisterBody))
                    VSpacer(24.dp)
                    Row(verticalAlignment = Alignment.Top) {
                        HsCheckbox(checked = state.consent, onCheckedChange = vm::setConsent)
                        Spacer(Modifier.size(12.dp))
                        subhead2_grey(stringResource(R.string.Evidence_Consent))
                    }
                }
            }
        }
    }
}

/** The confirm step; shown inline by [RegistrationIntroPage]. */
@Composable
fun RegistrationConfirmContent(step: RegStep.Confirm, onSelect: (OpieProject) -> Unit) {
    val host = remember(step.payload.server) { runCatching { java.net.URI(step.payload.server).host }.getOrNull() ?: step.payload.server }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        InfoRow(stringResource(R.string.Evidence_Server), host)
        InfoRow(stringResource(R.string.Evidence_Workspace), step.payload.team)
        InfoRow(stringResource(R.string.Evidence_DeviceKey), "•••• " + step.payload.key.takeLast(4))
        VSpacer(16.dp)
        captionSB_grey(stringResource(R.string.Evidence_ChooseProject), modifier = Modifier.padding(horizontal = 16.dp))
        VSpacer(8.dp)
        step.projects.forEach { project ->
            HsDivider()
            RowUniversal(modifier = Modifier.padding(horizontal = 16.dp), onClick = { onSelect(project) }) {
                HsCheckbox(checked = project.uuid == step.selected?.uuid, onCheckedChange = { onSelect(project) })
                Spacer(Modifier.size(12.dp))
                body_leah(project.name, modifier = Modifier.weight(1f))
                subhead2_grey(
                    stringResource(if (project.attributionEnabled) R.string.Evidence_AttributionOn else R.string.Evidence_NoAttribution)
                )
            }
        }
        HsDivider()
    }
}

@Composable
fun InfoRow(label: String, value: String, error: Boolean = false, onClick: (() -> Unit)? = null) {
    RowUniversal(modifier = Modifier.padding(horizontal = 16.dp), onClick = onClick) {
        body_leah(label, modifier = Modifier.weight(1f))
        if (error) subhead1_lucian(value, maxLines = 1) else subhead1_grey(value, maxLines = 1)
    }
    HsDivider()
}
