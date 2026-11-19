package com.hotspotmonitor.agent.ui

import android.content.Context
import com.hotspotmonitor.agent.AgentApp
import com.hotspotmonitor.agent.core.AgentStartReason
import com.hotspotmonitor.agent.service.AgentService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairUiState {
    data object Idle : PairUiState
    data class Running(val server: String) : PairUiState
    data class Success(val deviceId: String, val serverUrl: String) : PairUiState
    data class Failed(val message: String) : PairUiState
}

/**
 * Runs one pairing at a time in the process scope, so leaving the screen (or a headless PairActivity
 * finishing) does not cancel it. Shared by PairingScreen and PairActivity.
 */
object PairingRunner {
    private val _state = MutableStateFlow<PairUiState>(PairUiState.Idle)
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    /** Starts pairing; false when one is already running. */
    fun start(context: Context, serverUrl: String, code: String): Boolean {
        if (_state.value is PairUiState.Running) return false
        val app = context.applicationContext
        val graph = AgentApp.graph(app)
        _state.value = PairUiState.Running(serverUrl.trim())
        graph.appScope.launch {
            graph.pairing.pair(serverUrl, code)
                .onSuccess { response ->
                    _state.value = PairUiState.Success(response.deviceId, response.serverUrl)
                    // The link only comes up while the service runs; make sure it does.
                    graph.prefs.putBoolean(AgentService.KEY_USER_STOPPED, false)
                    AgentService.start(app, AgentStartReason.MANUAL)
                }
                .onFailure { error ->
                    _state.value = PairUiState.Failed(error.message ?: error.javaClass.simpleName)
                }
        }
        return true
    }

    /** Clears a finished result (not a running pairing). */
    fun reset() {
        if (_state.value !is PairUiState.Running) _state.value = PairUiState.Idle
    }
}
