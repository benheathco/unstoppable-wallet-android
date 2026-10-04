package io.horizontalsystems.evidenceui.registration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.evidence.net.DeviceApiResult
import io.horizontalsystems.evidence.net.OpieDeviceApi
import io.horizontalsystems.evidence.net.OpieProject
import io.horizontalsystems.evidenceui.pairing.PairingPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

sealed class RegStep {
    object Intro : RegStep()
    object Checking : RegStep()
    data class Confirm(val payload: PairingPayload, val projects: List<OpieProject>, val selected: OpieProject?) : RegStep()
    object Done : RegStep()
}

data class RegUiState(val step: RegStep = RegStep.Intro, val consent: Boolean = false, val error: String? = null)

sealed class UnregisterResult {
    object Revoked : UnregisterResult()
    data class Failed(val message: String) : UnregisterResult()
}

class RegistrationViewModel(
    private val store: RegistrationStore,
    private val api: OpieDeviceApi,
    private val dispatcherForTests: CoroutineDispatcher? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RegUiState())
    val uiState: StateFlow<RegUiState> = _uiState.asStateFlow()

    // Keystore work (key generation, destroy) must stay off the main thread
    private val io: CoroutineDispatcher get() = dispatcherForTests ?: Dispatchers.IO

    fun setConsent(value: Boolean) = _uiState.update { it.copy(consent = value) }

    fun onCodeScanned(text: String) {
        val payload = PairingPayload.parse(text).getOrElse {
            _uiState.update { s -> s.copy(error = it.message ?: "Not a registration code") }
            return
        }
        _uiState.update { it.copy(step = RegStep.Checking, error = null) }
        launch {
            when (val result = api.captureProjects(payload.server, payload.key)) {
                is DeviceApiResult.Ok -> {
                    if (result.value.isEmpty()) {
                        fail("This key has no projects you can capture into")
                    } else {
                        val selected = result.value.firstOrNull { it.uuid == payload.project }
                        _uiState.update { it.copy(step = RegStep.Confirm(payload, result.value, selected)) }
                    }
                }
                DeviceApiResult.Unauthorized -> fail("Opie rejected this code. It may have been revoked.")
                is DeviceApiResult.Failed -> fail("Couldn't reach Opie: ${result.message}")
            }
        }
    }

    fun selectProject(project: OpieProject) = _uiState.update { s ->
        val step = s.step as? RegStep.Confirm ?: return@update s
        s.copy(step = step.copy(selected = project), error = null)
    }

    fun register() {
        val step = _uiState.value.step as? RegStep.Confirm ?: return
        val project = step.selected ?: run {
            _uiState.update { it.copy(error = "Choose a project") }
            return
        }
        launch {
            val previous = store.registration.value
            val saved = runCatching { withContext(io) { store.save(step.payload, project) } }
            if (saved.isFailure) {
                _uiState.update { it.copy(error = "Couldn't store the device key securely on this phone") }
                return@launch
            }
            _uiState.update { it.copy(step = RegStep.Done, error = null) }
            // Re-register: retire the old key; a failure is reported, never silent
            if (previous != null && previous.apiKey != step.payload.key) {
                val r = api.selfRevoke(previous.server, previous.apiKey)
                if (r !is DeviceApiResult.Ok) {
                    _uiState.update { it.copy(error = "Registered, but the old key couldn't be revoked. Revoke it in Opie.") }
                }
            }
        }
    }

    /** Revoke in Opie first; only then forget locally (unless the operator forces removal). */
    suspend fun unregister(force: Boolean): UnregisterResult {
        val current = store.registration.value ?: return UnregisterResult.Revoked
        if (!force) {
            val r = api.selfRevoke(current.server, current.apiKey)
            if (r is DeviceApiResult.Failed) return UnregisterResult.Failed(r.message)
        }
        withContext(io) { runCatching { store.clear() } }
        _uiState.value = RegUiState()
        return UnregisterResult.Revoked
    }

    private fun fail(message: String) = _uiState.update { it.copy(step = RegStep.Intro, error = message) }

    private fun launch(block: suspend () -> Unit) {
        val d = dispatcherForTests
        if (d != null) runBlocking(d) { block() } else viewModelScope.launch(Dispatchers.Main) { block() }
    }
}
