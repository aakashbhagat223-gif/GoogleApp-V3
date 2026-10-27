package com.glassremote.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.glassremote.domain.model.TvDevice
import com.glassremote.domain.usecase.DiscoverTvsUseCase
import com.glassremote.domain.usecase.PairTvUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class PairingPhase { Idle, Connecting, AwaitingCode, Verifying, Paired }

data class PairingUiState(
    val devices: List<TvDevice> = emptyList(),
    val selected: TvDevice? = null,
    val phase: PairingPhase = PairingPhase.Idle,
    val error: String? = null,
)

sealed interface PairingIntent {
    data class Select(val device: TvDevice) : PairingIntent
    data class SubmitCode(val code: String) : PairingIntent
    data object Cancel : PairingIntent
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    discover: DiscoverTvsUseCase,
    private val pair: PairTvUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            discover()
                .catch { e -> _state.update { it.copy(error = e.message) } }
                .collect { list -> _state.update { it.copy(devices = list) } }
        }
    }

    fun onIntent(intent: PairingIntent) {
        when (intent) {
            is PairingIntent.Select -> viewModelScope.launch {
                _state.update { it.copy(selected = intent.device, phase = PairingPhase.Connecting, error = null) }
                runCatching { pair.begin(intent.device) }
                    .onSuccess { _state.update { it.copy(phase = PairingPhase.AwaitingCode) } }
                    .onFailure { e -> _state.update { it.copy(phase = PairingPhase.Idle, error = e.message) } }
            }
            is PairingIntent.SubmitCode -> viewModelScope.launch {
                val device = _state.value.selected ?: return@launch
                _state.update { it.copy(phase = PairingPhase.Verifying, error = null) }
                runCatching { pair.submit(device, intent.code) }
                    .onSuccess { _state.update { it.copy(phase = PairingPhase.Paired) } }
                    .onFailure { e -> _state.update { it.copy(phase = PairingPhase.AwaitingCode, error = e.message) } }
            }
            PairingIntent.Cancel -> _state.update { it.copy(selected = null, phase = PairingPhase.Idle, error = null) }
        }
    }
}
