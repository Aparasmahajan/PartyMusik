package io.mahajan.partymusik.engine

import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.ChannelSnapshot
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.CommSession
import io.mahajan.partymusik.domain.DeviceRef
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.FallbackPolicyConfig
import io.mahajan.partymusik.domain.ManualOverride
import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteError
import io.mahajan.partymusik.domain.RouteState

data class RoutingPolicy(
    val mediaPriority: List<DeviceRef> = emptyList(),
    val commPriority: List<DeviceRef> = emptyList(),
    val fallback: FallbackPolicyConfig = FallbackPolicyConfig(),
    val restoreAfterCall: Boolean = true,
    val splitMediaAndComm: Boolean = true,
    val confirmTimeoutMs: Long = 3_000,
    val pickerTimeoutMs: Long = 30_000,
    val overrideDurationMs: Long = 2 * 60 * 60 * 1000L,
)

/** Complete engine state. Published to the UI as-is; `media`/`comm` carry the requested/available/active split (DIA-001). */
data class RoutingSnapshot(
    val endpoints: List<AudioEndpoint> = emptyList(),
    val media: ChannelSnapshot = ChannelSnapshot(ChannelType.MEDIA),
    val comm: ChannelSnapshot = ChannelSnapshot(ChannelType.COMMUNICATION),
    val observedMedia: List<EndpointId> = emptyList(),
    val observedMulti: List<EndpointId> = emptyList(),
    val observedComm: EndpointId? = null,
    val policy: RoutingPolicy = RoutingPolicy(),
    val override: ManualOverride? = null,
    val activeProfileId: String? = null,
    val commSession: CommSession? = null,
    val isPlaying: Boolean = false,
    val wasPlayingBeforeSuspend: Boolean = false,
    val mediaBeforeComm: List<DeviceRef> = emptyList(),
    /** Every endpoint seen this session, so lost devices can still be named. */
    val knownRefs: Map<EndpointId, DeviceRef> = emptyMap(),
    /** Recently confirmed media sets, most recent first (LastKnownGood fallback). */
    val history: List<List<DeviceRef>> = emptyList(),
    val generationCounter: Long = 0,
) {
    fun endpoint(id: EndpointId): AudioEndpoint? = endpoints.firstOrNull { it.id == id }
    fun refOf(id: EndpointId): DeviceRef? = endpoint(id)?.toRef() ?: knownRefs[id]
    fun displayName(id: EndpointId): String = refOf(id)?.displayName ?: id.value
    fun channel(type: ChannelType): ChannelSnapshot = if (type == ChannelType.MEDIA) media else comm
}

sealed interface EngineEvent {
    data class EndpointsChanged(val endpoints: List<AudioEndpoint>) : EngineEvent
    data class MediaObserved(val ids: List<EndpointId>) : EngineEvent
    data class MultiObserved(val ids: List<EndpointId>) : EngineEvent
    data class CommObserved(val id: EndpointId?) : EngineEvent
    data class Request(val channel: ChannelType, val targets: List<EndpointId>, val source: RequestSource) : EngineEvent
    data class Release(val channel: ChannelType) : EngineEvent
    data class ApplyProfile(val profile: Profile, val source: RequestSource) : EngineEvent
    data class CommandRejected(val channel: ChannelType, val generation: Long, val error: RouteError) : EngineEvent
    data class SystemPickerRequired(val channel: ChannelType, val generation: Long) : EngineEvent
    data class ConfirmTimeout(val channel: ChannelType, val generation: Long) : EngineEvent
    data class CommSessionStarted(val session: CommSession) : EngineEvent
    data object CommSessionEnded : EngineEvent
    data class PlaybackChanged(val playing: Boolean) : EngineEvent
    data class SetOverride(val until: Long?) : EngineEvent
    data object ClearOverride : EngineEvent
}

sealed interface Effect {
    data class RouteMediaSingle(val target: EndpointId, val generation: Long) : Effect
    data class RouteMediaMulti(val targets: List<EndpointId>, val generation: Long) : Effect
    data object StopMulti : Effect
    data object ClearMediaPreference : Effect
    data class RouteComm(val target: EndpointId, val generation: Long) : Effect
    data object ReleaseComm : Effect
    data class ScheduleTimeout(val channel: ChannelType, val generation: Long, val delayMs: Long) : Effect
    data class SetGain(val endpoint: EndpointId, val gain: Float) : Effect
    data class SetDelay(val endpoint: EndpointId, val ms: Int) : Effect
    data object PausePlayback : Effect
    data object ResumePlayback : Effect
    data class Log(val code: String, val channel: ChannelType?, val detail: Map<String, String> = emptyMap()) : Effect
}

data class Transition(val state: RoutingSnapshot, val effects: List<Effect>)

/** Convenience for UI and tests: the endpoints a channel currently targets or holds. */
fun RouteState.targets(): List<EndpointId> = when (this) {
    is RouteState.Requested -> targets
    is RouteState.PartiallyConfirmed -> confirmed + pending
    is RouteState.Confirmed -> active
    else -> emptyList()
}
