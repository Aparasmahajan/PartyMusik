package io.mahajan.partymusik.calls

import io.mahajan.partymusik.diagnostics.Diagnostics
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.CommSession
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteCommandResult
import io.mahajan.partymusik.engine.EngineEvent
import io.mahajan.partymusik.engine.RoutingEngine
import io.mahajan.partymusik.ports.CallObserverPort
import io.mahajan.partymusik.ports.CallState
import io.mahajan.partymusik.ports.CellularCallState
import io.mahajan.partymusik.ports.Peer
import io.mahajan.partymusik.ports.VoipPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bridges call lifecycles into the routing engine:
 *  - in-app VoIP calls (VOIP-001..006) → communication session with its own route,
 *  - cellular calls, observed only (CALL-001/002) → media suspended and restored (RTE-006/007).
 */
class CallController(
    private val voip: VoipPort,
    private val cellular: CallObserverPort,
    private val engine: RoutingEngine,
    private val diagnostics: Diagnostics,
    private val scope: CoroutineScope,
) {
    val callState: StateFlow<CallState> = voip.callState
    val muted: StateFlow<Boolean> = voip.muted

    fun start() {
        scope.launch {
            voip.callState.map { it is CallState.Active || it is CallState.Held }.distinctUntilChanged().collect { inCall ->
                engine.dispatch(if (inCall) EngineEvent.CommSessionStarted(CommSession.VOIP) else EngineEvent.CommSessionEnded)
            }
        }
        scope.launch {
            cellular.cellularCall.distinctUntilChanged().collect { state ->
                diagnostics.log("CELLULAR_CALL_STATE", ChannelType.COMMUNICATION, mapOf("state" to state.name))
                when (state) {
                    CellularCallState.RINGING, CellularCallState.ACTIVE ->
                        engine.dispatch(EngineEvent.CommSessionStarted(CommSession.CELLULAR))
                    CellularCallState.NONE ->
                        if (engine.state.value.commSession == CommSession.CELLULAR) engine.dispatch(EngineEvent.CommSessionEnded)
                }
            }
        }
    }

    suspend fun startCall(peer: Peer): RouteCommandResult {
        diagnostics.log("VOIP_START", ChannelType.COMMUNICATION)
        return voip.startCall(peer)
    }

    suspend fun hangup() = voip.hangup()

    suspend fun setMuted(muted: Boolean) = voip.setMuted(muted)

    /** VOIP-004: switch the call's output; confirmation comes from the comm-route observation. */
    fun switchOutput(endpoint: EndpointId) = engine.request(ChannelType.COMMUNICATION, listOf(endpoint), RequestSource.USER)
}
