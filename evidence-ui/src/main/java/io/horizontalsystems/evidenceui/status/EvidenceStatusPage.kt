package io.horizontalsystems.evidenceui.status

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.horizontalsystems.evidence.net.DeviceApiResult
import io.horizontalsystems.evidence.net.OpieProject
import io.horizontalsystems.evidenceui.EvidenceGraph
import io.horizontalsystems.evidenceui.R
import io.horizontalsystems.evidenceui.registration.InfoRow
import io.horizontalsystems.evidenceui.registration.RegistrationIntroPage
import io.horizontalsystems.evidenceui.registration.RegistrationViewModel
import io.horizontalsystems.evidenceui.registration.UnregisterResult
import io.horizontalsystems.walletkit.helpers.HudHelper
import io.horizontalsystems.walletkit.modules.nav3.HSNavigation
import io.horizontalsystems.walletkit.modules.nav3.HSPage
import io.horizontalsystems.walletkit.ui.compose.components.ButtonPrimaryDefault
import io.horizontalsystems.walletkit.ui.compose.components.ButtonPrimaryRed
import io.horizontalsystems.walletkit.ui.compose.components.ButtonPrimaryYellow
import io.horizontalsystems.walletkit.ui.compose.components.VSpacer
import io.horizontalsystems.walletkit.ui.compose.components.caption_lucian
import io.horizontalsystems.walletkit.uiv3.components.HSScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
data object EvidenceStatusPage : HSPage() {

    @Composable
    override fun GetContent(navigation: HSNavigation) {
        val store = EvidenceGraph.store
        val registration by store.registration.collectAsState()
        val reg = registration
        // Unregistering clears the store: leave the screen
        LaunchedEffect(reg == null) { if (reg == null) navigation.removeLastOrNull() }
        if (reg == null) return

        val vm = viewModel { RegistrationViewModel(store, EvidenceGraph.api) }
        val status by EvidenceGraph.recorder.status.collectAsState()
        val scope = rememberCoroutineScope()
        val view = LocalView.current

        var submitting by remember { mutableStateOf(false) }
        var confirmUnregister by remember { mutableStateOf(false) }
        var unregisterFailure by remember { mutableStateOf<String?>(null) }
        var busy by remember { mutableStateOf(false) }
        var projects by remember { mutableStateOf<List<OpieProject>?>(null) }
        var projectsError by remember { mutableStateOf<String?>(null) }

        HSScaffold(
            title = stringResource(R.string.Evidence_SettingsTitle),
            onBack = { navigation.removeLastOrNull() },
            bottomBar = {
                Column(Modifier.padding(16.dp)) {
                    ButtonPrimaryYellow(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Evidence_Submit),
                        enabled = !reg.keyRejected && !submitting,
                        loadingIndicator = submitting,
                        onClick = {
                            submitting = true
                            scope.launch {
                                try {
                                    val n = withContext(Dispatchers.IO) { EvidenceGraph.recorder.submitAll() }
                                    HudHelper.showSuccessMessage(view, view.context.getString(R.string.Evidence_SubmittedCount, n))
                                } finally {
                                    submitting = false
                                }
                            }
                        },
                    )
                    VSpacer(8.dp)
                    ButtonPrimaryDefault(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Evidence_Reregister),
                        onClick = { navigation.slideFromRight(RegistrationIntroPage(popOnDone = true)) },
                    )
                    VSpacer(8.dp)
                    ButtonPrimaryRed(
                        modifier = Modifier.fillMaxWidth(),
                        title = stringResource(R.string.Evidence_Unregister),
                        enabled = !busy,
                        onClick = { confirmUnregister = true },
                    )
                }
            },
        ) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (reg.keyRejected) {
                    caption_lucian(
                        text = stringResource(R.string.Evidence_KeyRejectedBanner),
                        modifier = Modifier.padding(16.dp),
                    )
                }
                InfoRow(
                    stringResource(R.string.Evidence_Status),
                    stringResource(if (reg.keyRejected) R.string.Evidence_KeyRejected else R.string.Evidence_Registered),
                    error = reg.keyRejected,
                )
                InfoRow(stringResource(R.string.Evidence_Project), reg.projectName, onClick = {
                    scope.launch {
                        when (val r = EvidenceGraph.api.captureProjects(reg.server, reg.apiKey)) {
                            is DeviceApiResult.Ok -> projects = r.value
                            DeviceApiResult.Unauthorized -> projectsError = view.context.getString(R.string.Evidence_KeyRejected)
                            is DeviceApiResult.Failed -> projectsError = r.message
                        }
                    }
                })
                InfoRow(stringResource(R.string.Evidence_Server), reg.server.substringAfter("://").substringBefore('/'))
                InfoRow(stringResource(R.string.Evidence_DeviceKey), "•••• ${reg.apiKey.takeLast(4)} · ${reg.keyHardware}")
                InfoRow(stringResource(R.string.Evidence_Pending), status.pendingBundles.toString())
            }
        }

        projects?.let { list ->
            AlertDialog(
                onDismissRequest = { projects = null },
                title = { Text(stringResource(R.string.Evidence_ChooseProject)) },
                text = {
                    Column {
                        list.forEach { p ->
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) { store.setProject(p) }
                                projects = null
                            }) { Text(p.name) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { projects = null }) { Text(stringResource(R.string.Evidence_Cancel)) } },
            )
        }

        projectsError?.let { message ->
            AlertDialog(
                onDismissRequest = { projectsError = null },
                title = { Text(stringResource(R.string.Evidence_ProjectsFailed)) },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = { projectsError = null }) { Text("OK") } },
            )
        }

        fun unregister(force: Boolean) {
            busy = true
            scope.launch {
                try {
                    val r = vm.unregister(force)
                    if (r is UnregisterResult.Failed) unregisterFailure = r.message
                } finally {
                    busy = false
                }
            }
        }

        if (confirmUnregister) {
            AlertDialog(
                onDismissRequest = { confirmUnregister = false },
                title = { Text(stringResource(R.string.Evidence_UnregisterTitle)) },
                text = { Text(stringResource(R.string.Evidence_UnregisterBody, status.pendingBundles)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmUnregister = false
                        unregister(force = false)
                    }) { Text(stringResource(R.string.Evidence_Unregister)) }
                },
                dismissButton = { TextButton(onClick = { confirmUnregister = false }) { Text(stringResource(R.string.Evidence_Cancel)) } },
            )
        }

        unregisterFailure?.let { message ->
            AlertDialog(
                onDismissRequest = { unregisterFailure = null },
                title = { Text(stringResource(R.string.Evidence_UnregisterFailedTitle)) },
                text = { Text(stringResource(R.string.Evidence_UnregisterFailedBody, message)) },
                confirmButton = {
                    TextButton(onClick = {
                        unregisterFailure = null
                        unregister(force = false)
                    }) { Text(stringResource(R.string.Evidence_TryAgain)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        unregisterFailure = null
                        unregister(force = true)
                    }) { Text(stringResource(R.string.Evidence_RemoveAnyway)) }
                },
            )
        }
    }
}
