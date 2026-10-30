package io.mahajan.partymusik.engine

import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.Capability.COMMUNICATION_OUTPUT
import io.mahajan.partymusik.domain.Capability.MEDIA_OUTPUT
import io.mahajan.partymusik.domain.Capability.MICROPHONE
import io.mahajan.partymusik.domain.Capability.MULTI_OUTPUT_MEMBER
import io.mahajan.partymusik.domain.ChannelType.COMMUNICATION
import io.mahajan.partymusik.domain.ChannelType.MEDIA
import io.mahajan.partymusik.domain.CommSession
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.FallbackPolicyConfig
import io.mahajan.partymusik.domain.FallbackRule
import io.mahajan.partymusik.domain.ManualOverride
import io.mahajan.partymusik.domain.OutputMode
import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.domain.ProfileTemplate
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteError
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.domain.SuspendReason
import io.mahajan.partymusik.domain.Trigger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoutingReducerTest {

    private val speaker = ep("speaker", EndpointKind.BUILTIN_SPEAKER, MEDIA_OUTPUT, COMMUNICATION_OUTPUT, MULTI_OUTPUT_MEMBER)
    private val earpiece = ep("earpiece", EndpointKind.BUILTIN_EARPIECE, COMMUNICATION_OUTPUT)
    private val headphones = ep("sony", EndpointKind.BT_CLASSIC_MEDIA, MEDIA_OUTPUT, COMMUNICATION_OUTPUT, MICROPHONE, MULTI_OUTPUT_MEMBER)
    private val jbl = ep("jbl", EndpointKind.BT_CLASSIC_MEDIA, MEDIA_OUTPUT, MULTI_OUTPUT_MEMBER)
    private val carKit = ep("car", EndpointKind.BT_CLASSIC_HFP, COMMUNICATION_OUTPUT, MICROPHONE)
    private val soloSpeaker = ep("solo", EndpointKind.BT_CLASSIC_MEDIA, MEDIA_OUTPUT) // not a multi-output member

    private fun ep(key: String, kind: EndpointKind, vararg caps: io.mahajan.partymusik.domain.Capability) =
        AudioEndpoint(EndpointId("t:$key"), key, key, kind, capabilities = caps.toSet())

    private var now = 1_000L
    private val effects = mutableListOf<Effect>()

    private fun RoutingSnapshot.on(event: EngineEvent): RoutingSnapshot {
        now += 10
        val t = RoutingReducer.reduce(this, event, now)
        effects += t.effects
        return t.state
    }

    private fun start(vararg eps: AudioEndpoint, policy: RoutingPolicy = RoutingPolicy()) =
        RoutingSnapshot(policy = policy).on(EngineEvent.EndpointsChanged(eps.toList()))

    private fun requestMedia(vararg eps: AudioEndpoint, source: RequestSource = RequestSource.USER) =
        EngineEvent.Request(MEDIA, eps.map { it.id }, source)

    @Test
    fun media_request_is_not_confirmed_until_os_reports_it() {
        var s = start(speaker, headphones).on(requestMedia(headphones))
        assertIs<RouteState.Requested>(s.media.state)
        assertTrue(effects.any { it == Effect.RouteMediaSingle(headphones.id, s.media.generation) })

        s = s.on(EngineEvent.MediaObserved(listOf(speaker.id)))
        assertIs<RouteState.Requested>(s.media.state, "observation of another device must not confirm")

        s = s.on(EngineEvent.MediaObserved(listOf(headphones.id)))
        assertEquals(RouteState.Confirmed(listOf(headphones.id)), s.media.state)
        assertEquals(listOf(headphones.id), s.media.active)
    }

    @Test
    fun timeout_without_observation_rejects_with_timeout() {
        var s = start(speaker, headphones).on(requestMedia(headphones))
        s = s.on(EngineEvent.ConfirmTimeout(MEDIA, s.media.generation))
        val st = assertIs<RouteState.Rejected>(s.media.state)
        assertEquals(RouteError.Timeout, st.error)
    }

    @Test
    fun timeout_with_other_observed_route_reports_where_audio_actually_is() {
        var s = start(speaker, headphones)
            .on(EngineEvent.MediaObserved(listOf(speaker.id)))
            .on(requestMedia(headphones))
        s = s.on(EngineEvent.ConfirmTimeout(MEDIA, s.media.generation))
        val st = assertIs<RouteState.Rejected>(s.media.state)
        assertEquals(RouteError.OsRedirected(listOf(speaker.id)), st.error)
    }

    @Test
    fun stale_generation_results_are_ignored() {
        var s = start(speaker, headphones, jbl).on(requestMedia(headphones))
        val oldGen = s.media.generation
        s = s.on(requestMedia(jbl))
        s = s.on(EngineEvent.ConfirmTimeout(MEDIA, oldGen))
        s = s.on(EngineEvent.CommandRejected(MEDIA, oldGen, RouteError.DeviceGone))
        val st = assertIs<RouteState.Requested>(s.media.state)
        assertEquals(listOf(jbl.id), st.targets)
    }

    @Test
    fun system_picker_extends_the_confirmation_window() {
        var s = start(speaker, headphones).on(requestMedia(headphones))
        val gen = s.media.generation
        s = s.on(EngineEvent.SystemPickerRequired(MEDIA, gen))
        assertTrue((s.media.state as RouteState.Requested).needsSystemPicker)
        s = s.on(EngineEvent.ConfirmTimeout(MEDIA, gen)) // the short timeout fires first
        assertIs<RouteState.Requested>(s.media.state)
        now += 31_000
        s = s.on(EngineEvent.ConfirmTimeout(MEDIA, gen))
        assertIs<RouteState.Rejected>(s.media.state)
    }

    @Test
    fun request_validation_produces_actionable_errors() {
        val base = start(speaker, earpiece, headphones, carKit, soloSpeaker)
        assertEquals(RouteError.NotAMediaDevice, (base.on(requestMedia(carKit)).media.state as RouteState.Rejected).error)
        assertEquals(
            RouteError.UnsupportedCombo,
            (base.on(requestMedia(headphones, soloSpeaker)).media.state as RouteState.Rejected).error,
        )
        assertEquals(
            RouteError.NotACommDevice,
            (base.on(EngineEvent.Request(COMMUNICATION, listOf(soloSpeaker.id), RequestSource.USER)).comm.state as RouteState.Rejected).error,
        )
        assertEquals(
            RouteError.DeviceGone,
            (base.on(EngineEvent.Request(MEDIA, listOf(EndpointId("t:missing")), RequestSource.USER)).media.state as RouteState.Rejected).error,
        )
    }

    @Test
    fun multi_output_confirms_per_output() {
        var s = start(speaker, headphones, jbl).on(requestMedia(headphones, jbl))
        assertEquals(OutputMode.MULTI, s.media.mode)
        assertTrue(effects.any { it is Effect.RouteMediaMulti })

        s = s.on(EngineEvent.MultiObserved(listOf(jbl.id)))
        val partial = assertIs<RouteState.PartiallyConfirmed>(s.media.state)
        assertEquals(listOf(jbl.id), partial.confirmed)

        s = s.on(EngineEvent.MultiObserved(listOf(jbl.id, headphones.id)))
        assertIs<RouteState.Confirmed>(s.media.state)
    }

    @Test
    fun multi_output_loss_keeps_playing_on_survivors() {
        var s = start(speaker, headphones, jbl, policy = RoutingPolicy(fallback = FallbackPolicyConfig()))
            .on(requestMedia(headphones, jbl))
            .on(EngineEvent.MultiObserved(listOf(headphones.id, jbl.id)))
        assertIs<RouteState.Confirmed>(s.media.state)

        effects.clear()
        s = s.on(EngineEvent.EndpointsChanged(listOf(speaker, jbl)))
        val st = assertIs<RouteState.Requested>(s.media.state)
        assertEquals(listOf(jbl.id), st.targets)
        assertEquals(listOf("sony"), st.fallback!!.lost.map { it.stableKey })
        assertTrue(Effect.StopMulti in effects, "dropping to a single output stops the fan-out")
    }

    @Test
    fun single_output_loss_follows_priority_then_restores_when_device_returns() {
        val policy = RoutingPolicy(
            mediaPriority = listOf(headphones.toRef(), jbl.toRef()),
            fallback = FallbackPolicyConfig(listOf(FallbackRule.NextInPriority, FallbackRule.Pause)),
        )
        var s = start(speaker, headphones, jbl, policy = policy)
            .on(requestMedia(headphones, source = RequestSource.PROFILE))
            .on(EngineEvent.MediaObserved(listOf(headphones.id)))

        s = s.on(EngineEvent.EndpointsChanged(listOf(speaker, jbl)))
            .on(EngineEvent.MediaObserved(listOf(jbl.id)))
        val fallback = assertIs<RouteState.Confirmed>(s.media.state)
        assertEquals(listOf(jbl.id), fallback.active)
        assertEquals("sony", fallback.fallback!!.lost.single().stableKey)

        // Headphones come back with a new session id but the same stable key.
        val returned = headphones.copy(id = EndpointId("t:sony#2"))
        s = s.on(EngineEvent.EndpointsChanged(listOf(speaker, jbl, returned)))
        assertEquals(listOf(returned.id), (s.media.state as RouteState.Requested).targets)
    }

    @Test
    fun pause_rule_pauses_playback_and_resumes_on_return() {
        val policy = RoutingPolicy(fallback = FallbackPolicyConfig(listOf(FallbackRule.Pause)))
        var s = start(speaker, headphones, policy = policy)
            .on(EngineEvent.PlaybackChanged(true))
            .on(requestMedia(headphones))
            .on(EngineEvent.MediaObserved(listOf(headphones.id)))

        effects.clear()
        s = s.on(EngineEvent.EndpointsChanged(listOf(speaker)))
        assertIs<RouteState.Paused>(s.media.state)
        assertTrue(Effect.PausePlayback in effects)

        effects.clear()
        s = s.on(EngineEvent.EndpointsChanged(listOf(speaker, headphones)))
        assertIs<RouteState.Requested>(s.media.state)
        assertTrue(Effect.ResumePlayback in effects)
    }

    @Test
    fun device_lost_after_os_already_moved_audio_still_triggers_fallback() {
        var s = start(speaker, headphones, jbl)
            .on(requestMedia(headphones))
            .on(EngineEvent.MediaObserved(listOf(headphones.id)))
            .on(EngineEvent.MediaObserved(listOf(speaker.id))) // OS moved audio first
        assertIs<RouteState.Rejected>(s.media.state)
        s = s.on(EngineEvent.EndpointsChanged(listOf(speaker, jbl)))
        assertIs<RouteState.Requested>(s.media.state, "fallback runs once the device is gone")
    }

    @Test
    fun voip_session_routes_comm_separately_and_releases_on_end() {
        var s = start(speaker, earpiece, headphones, jbl)
            .on(requestMedia(jbl))
            .on(EngineEvent.MediaObserved(listOf(jbl.id)))

        s = s.on(EngineEvent.CommSessionStarted(CommSession.VOIP))
        assertEquals(listOf(headphones.id), (s.comm.state as RouteState.Requested).targets, "best comm device is the headset")
        assertIs<RouteState.Confirmed>(s.media.state, "split routing keeps media on the speaker")

        s = s.on(EngineEvent.CommObserved(headphones.id))
        assertIs<RouteState.Confirmed>(s.comm.state)

        effects.clear()
        s = s.on(EngineEvent.CommSessionEnded)
        assertTrue(Effect.ReleaseComm in effects)
        assertEquals(RouteState.Idle, s.comm.state)
    }

    @Test
    fun cellular_call_suspends_media_and_restores_afterwards() {
        var s = start(speaker, headphones)
            .on(EngineEvent.PlaybackChanged(true))
            .on(requestMedia(headphones))
            .on(EngineEvent.MediaObserved(listOf(headphones.id)))

        effects.clear()
        s = s.on(EngineEvent.CommSessionStarted(CommSession.CELLULAR))
        val suspended = assertIs<RouteState.Suspended>(s.media.state)
        assertEquals(SuspendReason.CELLULAR_CALL, suspended.reason)
        assertTrue(Effect.PausePlayback in effects)
        s = s.on(EngineEvent.PlaybackChanged(false))

        effects.clear()
        s = s.on(EngineEvent.CommSessionEnded)
        // The OS still reports the headphones, so the restored route confirms immediately.
        assertEquals(RouteState.Confirmed(listOf(headphones.id)), s.media.state)
        assertTrue(Effect.ResumePlayback in effects)
        assertTrue(Effect.ReleaseComm !in effects, "cellular calls never touch the communication device")
    }

    @Test
    fun media_without_split_support_is_suspended_during_voip() {
        val s = start(speaker, earpiece, headphones, policy = RoutingPolicy(splitMediaAndComm = false))
            .on(requestMedia(speaker))
            .on(EngineEvent.MediaObserved(listOf(speaker.id)))
            .on(EngineEvent.CommSessionStarted(CommSession.VOIP))
        assertEquals(SuspendReason.VOIP_CALL, (s.media.state as RouteState.Suspended).reason)
    }

    @Test
    fun manual_override_blocks_automation_until_it_expires() {
        val profile = Profile(
            "p1", "Car", ProfileTemplate.CUSTOM, mediaTargets = listOf(jbl.toRef()),
            triggers = listOf(Trigger.DeviceConnected(jbl.toRef())),
        )
        var s = start(speaker, headphones, jbl).on(requestMedia(headphones)) // USER → override
        assertTrue(s.override!!.isActive(now))
        val before = s.media
        s = s.on(EngineEvent.ApplyProfile(profile, RequestSource.AUTOMATION))
        assertEquals(before, s.media)

        s = s.copy(override = ManualOverride(until = now - 1))
        s = s.on(EngineEvent.ApplyProfile(profile, RequestSource.AUTOMATION))
        assertEquals(listOf(jbl.id), (s.media.state as RouteState.Requested).targets)
        assertEquals("p1", s.activeProfileId)
    }

    @Test
    fun profile_waits_when_its_devices_are_absent() {
        val profile = Profile("p2", "Buds", ProfileTemplate.CUSTOM, mediaTargets = listOf(carKit.toRef().copy(stableKey = "buds")))
        val s = start(speaker).on(EngineEvent.ApplyProfile(profile, RequestSource.USER))
        assertIs<RouteState.Paused>(s.media.state)
    }

    /** Invariant from ADR-003, checked over random event sequences: never "Confirmed" for a route the OS did not report. */
    @Test
    fun confirmed_routes_are_always_backed_by_observation() {
        val all = listOf(speaker, earpiece, headphones, jbl, carKit, soloSpeaker)
        val rnd = Random(42)
        repeat(300) {
            var s = start(*all.toTypedArray())
            repeat(60) {
                val connected = all.filter { rnd.nextInt(4) != 0 }
                val pick = { all.shuffled(rnd).take(rnd.nextInt(1, 3)).map { it.id } }
                val event = when (rnd.nextInt(10)) {
                    0 -> EngineEvent.EndpointsChanged(connected)
                    1 -> EngineEvent.Request(MEDIA, pick(), RequestSource.entries.random(rnd))
                    2 -> EngineEvent.Request(COMMUNICATION, pick().take(1), RequestSource.USER)
                    3 -> EngineEvent.MediaObserved(pick())
                    4 -> EngineEvent.MultiObserved(pick())
                    5 -> EngineEvent.CommObserved(pick().first())
                    6 -> EngineEvent.ConfirmTimeout(if (rnd.nextBoolean()) MEDIA else COMMUNICATION, s.media.generation)
                    7 -> if (rnd.nextBoolean()) EngineEvent.CommSessionStarted(CommSession.entries.random(rnd)) else EngineEvent.CommSessionEnded
                    8 -> EngineEvent.CommandRejected(MEDIA, s.media.generation, RouteError.OsRejected("x"))
                    else -> EngineEvent.Release(if (rnd.nextBoolean()) MEDIA else COMMUNICATION)
                }
                s = s.on(event)
                val media = s.media.state
                if (media is RouteState.Confirmed) {
                    val observed = if (s.media.mode == OutputMode.MULTI) s.observedMulti else s.observedMedia
                    assertTrue(observed.isEmpty() || observed.containsAll(media.active), "media confirmed without observation after $event")
                }
                val comm = s.comm.state
                if (comm is RouteState.Confirmed) {
                    assertTrue(s.observedComm == null || comm.active == listOf(s.observedComm), "comm confirmed without observation after $event")
                }
            }
        }
    }
}
