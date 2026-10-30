package io.mahajan.partymusik.engine

import io.mahajan.partymusik.diagnostics.Diagnostics
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteCommandResult
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.platform.Clock
import io.mahajan.partymusik.ports.PlatformPorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs [RoutingReducer] as a single-consumer actor: every OS callback and user intent becomes an
 * [EngineEvent] processed strictly in order, and side effects are executed against the native ports.
 */
class RoutingEngine(
    private val ports: PlatformPorts,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val diagnostics: Diagnostics,
    initialPolicy: RoutingPolicy = RoutingPolicy(splitMediaAndComm = ports.info.supportsSplitMediaAndComm),
) {
    private val events = Channel<EngineEvent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(RoutingSnapshot(policy = initialPolicy))
    val state: StateFlow<RoutingSnapshot> = _state.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (event in events) {
                val t = try {
                    RoutingReducer.reduce(_state.value, event, clock.nowMillis())
                } catch (e: Throwable) {
                    // The reducer is total by design; this is a last line of defence for NFR-001.
                    diagnostics.log("ENGINE_REDUCER_ERROR", null, mapOf("event" to event::class.simpleName.orEmpty(), "error" to e.toString()))
                    continue
                }
                _state.value = t.state
                t.effects.forEach(::execute)
            }
        }
        scope.launch { ports.audioDevices.endpoints.collect { dispatch(EngineEvent.EndpointsChanged(it)) } }
        scope.launch { ports.mediaRouting.observedRoute.distinctUntilChanged().collect { dispatch(EngineEvent.MediaObserved(it)) } }
        scope.launch { ports.multiOutput.observedRoutes.distinctUntilChanged().collect { dispatch(EngineEvent.MultiObserved(it)) } }
        scope.launch { ports.commRouting.observed.distinctUntilChanged().collect { dispatch(EngineEvent.CommObserved(it)) } }
        scope.launch {
            ports.playback.status.map { it.playing }.distinctUntilChanged().collect { dispatch(EngineEvent.PlaybackChanged(it)) }
        }
    }

    fun dispatch(event: EngineEvent) {
        events.trySend(event)
    }

    fun request(channel: ChannelType, targets: List<EndpointId>, source: RequestSource = RequestSource.USER) =
        dispatch(EngineEvent.Request(channel, targets, source))

    fun release(channel: ChannelType) = dispatch(EngineEvent.Release(channel))

    fun applyProfile(profile: Profile, source: RequestSource) = dispatch(EngineEvent.ApplyProfile(profile, source))

    /**
     * Requests a route and suspends until the channel settles (confirmed, rejected or paused) or [timeoutMs] passes.
     * Used by the compatibility test (DIA-002).
     */
    suspend fun requestAndAwait(
        channel: ChannelType,
        targets: List<EndpointId>,
        source: RequestSource,
        timeoutMs: Long,
    ): RouteState? {
        val before = state.value.channel(channel).generation
        dispatch(EngineEvent.Request(channel, targets, source))
        return withTimeoutOrNull(timeoutMs) {
            state.first { s ->
                val ch = s.channel(channel)
                ch.generation > before && ch.state.isSettled()
            }.channel(channel).state
        }
    }

    private fun execute(effect: Effect) {
        when (effect) {
            is Effect.RouteMediaSingle -> scope.launch {
                handleResult(ChannelType.MEDIA, effect.generation, ports.mediaRouting.requestOutput(effect.target))
            }
            is Effect.RouteMediaMulti -> scope.launch {
                handleResult(ChannelType.MEDIA, effect.generation, ports.multiOutput.start(effect.targets))
            }
            Effect.StopMulti -> scope.launch { ports.multiOutput.stop() }
            Effect.ClearMediaPreference -> scope.launch { ports.mediaRouting.clearPreference() }
            is Effect.RouteComm -> scope.launch {
                handleResult(ChannelType.COMMUNICATION, effect.generation, ports.commRouting.request(effect.target))
            }
            Effect.ReleaseComm -> scope.launch { ports.commRouting.release() }
            is Effect.ScheduleTimeout -> scope.launch {
                delay(effect.delayMs)
                dispatch(EngineEvent.ConfirmTimeout(effect.channel, effect.generation))
            }
            is Effect.SetGain -> ports.multiOutput.setGain(effect.endpoint, effect.gain)
            is Effect.SetDelay -> ports.multiOutput.setDelay(effect.endpoint, effect.ms)
            Effect.PausePlayback -> ports.playback.pause()
            Effect.ResumePlayback -> ports.playback.play()
            is Effect.Log -> diagnostics.log(effect.code, effect.channel, effect.detail)
        }
    }

    private fun handleResult(channel: ChannelType, generation: Long, result: RouteCommandResult) {
        when (result) {
            RouteCommandResult.Submitted -> Unit
            RouteCommandResult.NeedsSystemPicker -> dispatch(EngineEvent.SystemPickerRequired(channel, generation))
            is RouteCommandResult.Rejected -> dispatch(EngineEvent.CommandRejected(channel, generation, result.error))
        }
    }
}

fun RouteState.isSettled(): Boolean =
    this is RouteState.Confirmed || this is RouteState.Rejected || this is RouteState.Paused || this is RouteState.Idle
