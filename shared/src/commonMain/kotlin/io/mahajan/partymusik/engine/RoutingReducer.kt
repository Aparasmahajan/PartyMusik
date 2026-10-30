package io.mahajan.partymusik.engine

import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.ChannelSnapshot
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.ChannelType.COMMUNICATION
import io.mahajan.partymusik.domain.ChannelType.MEDIA
import io.mahajan.partymusik.domain.CommSession
import io.mahajan.partymusik.domain.DeviceRef
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.FallbackInfo
import io.mahajan.partymusik.domain.FallbackRule
import io.mahajan.partymusik.domain.ManualOverride
import io.mahajan.partymusik.domain.OutputMode
import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteError
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.domain.SuspendReason

/**
 * Pure state machine for media and communication routing (docs/06-routing-engine.md).
 *
 * Invariants (covered by tests):
 *  1. A channel is only `Confirmed` for endpoints present in the latest OS observation (RTE-008).
 *  2. Every request bumps the channel generation; late results from older generations are ignored.
 *  3. Ending a VoIP session always releases the communication device (VOIP-005).
 *  4. `reduce` never throws (NFR-001).
 */
object RoutingReducer {

    fun reduce(state: RoutingSnapshot, event: EngineEvent, now: Long): Transition {
        val fx = mutableListOf<Effect>()
        val next = when (event) {
            is EngineEvent.EndpointsChanged -> onEndpoints(state, event, now, fx)
            is EngineEvent.MediaObserved -> reconcileMedia(state.copy(observedMedia = event.ids), now, fx)
            is EngineEvent.MultiObserved -> reconcileMedia(state.copy(observedMulti = event.ids), now, fx)
            is EngineEvent.CommObserved -> reconcileComm(state.copy(observedComm = event.id), now, fx)
            is EngineEvent.Request -> onRequest(state, event, now, fx)
            is EngineEvent.Release -> onRelease(state, event.channel, fx)
            is EngineEvent.ApplyProfile -> onApplyProfile(state, event.profile, event.source, now, fx)
            is EngineEvent.CommandRejected -> onCommandRejected(state, event, fx)
            is EngineEvent.SystemPickerRequired -> onPickerRequired(state, event, fx)
            is EngineEvent.ConfirmTimeout -> onTimeout(state, event, now, fx)
            is EngineEvent.CommSessionStarted -> onCommStarted(state, event.session, now, fx)
            EngineEvent.CommSessionEnded -> onCommEnded(state, now, fx)
            is EngineEvent.PlaybackChanged -> state.copy(isPlaying = event.playing)
            is EngineEvent.SetOverride -> {
                fx += Effect.Log("OVERRIDE_SET", null, mapOf("until" to (event.until?.toString() ?: "cleared manually")))
                state.copy(override = ManualOverride(event.until))
            }
            EngineEvent.ClearOverride -> {
                fx += Effect.Log("OVERRIDE_CLEARED", null)
                state.copy(override = null)
            }
        }
        return Transition(next, fx)
    }

    // region endpoints & observation

    private fun onEndpoints(s: RoutingSnapshot, e: EngineEvent.EndpointsChanged, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        val before = s.endpoints.filter { it.isConnected }.associateBy { it.id }
        val after = e.endpoints.filter { it.isConnected }.associateBy { it.id }
        (after.keys - before.keys).forEach { id ->
            val ep = after.getValue(id)
            fx += Effect.Log("DEVICE_CONNECTED", null, mapOf("device" to ep.name, "kind" to ep.kind.name))
        }
        (before.keys - after.keys).forEach { id ->
            fx += Effect.Log("DEVICE_DISCONNECTED", null, mapOf("device" to before.getValue(id).name))
        }

        // An output that no longer exists cannot be active, whatever the last observation said.
        var n = s.copy(
            endpoints = e.endpoints,
            knownRefs = s.knownRefs + e.endpoints.associate { it.id to it.toRef() },
            observedMedia = s.observedMedia.filter { it in after },
            observedMulti = s.observedMulti.filter { it in after },
            observedComm = s.observedComm?.takeIf { it in after },
        ).withAvailability()

        // A device can vanish after the OS already moved audio away (observation arrived first) — treat that as a loss too.
        val heldMedia = n.media.state.heldTargets()
        val lostMedia = heldMedia.filter { n.endpoint(it)?.canCarry(MEDIA) != true }
        n = if (lostMedia.isNotEmpty()) applyMediaFallback(n, heldMedia, lostMedia, now, fx) else maybeRestoreMedia(n, now, fx)

        val heldComm = n.comm.state.heldTargets()
        val lostComm = heldComm.filter { n.endpoint(it)?.canCarry(COMMUNICATION) != true }
        if (lostComm.isNotEmpty()) n = applyCommFallback(n, lostComm, now, fx)

        return reconcileComm(reconcileMedia(n, now, fx), now, fx)
    }

    private fun RoutingSnapshot.withAvailability(): RoutingSnapshot = copy(
        media = media.copy(available = endpoints.filter { it.canCarry(MEDIA) }.map { it.id }),
        comm = comm.copy(available = endpoints.filter { it.canCarry(COMMUNICATION) }.map { it.id }),
    )

    private fun reconcileMedia(s: RoutingSnapshot, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        val observed = if (s.media.mode == OutputMode.MULTI) s.observedMulti else s.observedMedia
        var media = s.media.copy(active = observed)
        var history = s.history
        when (val st = media.state) {
            is RouteState.Requested -> {
                val ok = st.targets.filter { it in observed }
                if (ok.size == st.targets.size) {
                    media = media.copy(state = RouteState.Confirmed(st.targets, st.fallback))
                    history = pushHistory(s, st.targets)
                    fx += confirmedLog(s, MEDIA, st.targets, now - st.since)
                } else if (media.mode == OutputMode.MULTI && ok.isNotEmpty()) {
                    media = media.copy(state = RouteState.PartiallyConfirmed(ok, st.targets - ok.toSet(), st.since, st.fallback))
                }
            }
            is RouteState.PartiallyConfirmed -> {
                val all = st.confirmed + st.pending
                val ok = all.filter { it in observed }
                if (ok.size == all.size) {
                    media = media.copy(state = RouteState.Confirmed(all, st.fallback))
                    history = pushHistory(s, all)
                    fx += confirmedLog(s, MEDIA, all, now - st.since)
                } else if (ok.isNotEmpty()) {
                    media = media.copy(state = st.copy(confirmed = ok, pending = all - ok.toSet()))
                } else {
                    media = media.copy(state = RouteState.Requested(all, st.since, fallback = st.fallback))
                }
            }
            is RouteState.Confirmed -> {
                // Only react when the OS reports a *different* route; an empty observation (e.g. sink idle) is not a move.
                val missing = st.active.filter { it !in observed }
                val stillConnected = missing.filter { s.endpoint(it)?.canCarry(MEDIA) == true }
                if (observed.isNotEmpty() && stillConnected.isNotEmpty()) {
                    if (media.mode == OutputMode.MULTI && missing.size < st.active.size) {
                        val present = st.active - missing.toSet()
                        media = media.copy(state = RouteState.PartiallyConfirmed(present, missing, now, st.fallback))
                        fx += Effect.ScheduleTimeout(MEDIA, media.generation, s.policy.confirmTimeoutMs)
                    } else {
                        media = media.copy(state = RouteState.Rejected(st.active, RouteError.OsRedirected(observed)))
                        fx += Effect.Log(
                            "RTE_OS_REDIRECTED", MEDIA,
                            mapOf("expected" to names(s, st.active), "actual" to names(s, observed)),
                        )
                    }
                }
            }
            else -> Unit
        }
        return s.copy(media = media, history = history)
    }

    private fun reconcileComm(s: RoutingSnapshot, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        val observed = listOfNotNull(s.observedComm)
        var comm = s.comm.copy(active = observed)
        when (val st = comm.state) {
            is RouteState.Requested -> if (st.targets.all { it in observed }) {
                comm = comm.copy(state = RouteState.Confirmed(st.targets, st.fallback))
                fx += confirmedLog(s, COMMUNICATION, st.targets, now - st.since)
            }
            is RouteState.Confirmed -> {
                val moved = observed.isNotEmpty() && st.active.any { it !in observed }
                val stillConnected = st.active.all { s.endpoint(it)?.canCarry(COMMUNICATION) == true }
                if (moved && stillConnected) {
                    comm = comm.copy(state = RouteState.Rejected(st.active, RouteError.OsRedirected(observed)))
                    fx += Effect.Log(
                        "RTE_OS_REDIRECTED", COMMUNICATION,
                        mapOf("expected" to names(s, st.active), "actual" to names(s, observed)),
                    )
                }
            }
            else -> Unit
        }
        return s.copy(comm = comm)
    }

    // endregion

    // region requests

    private fun onRequest(s: RoutingSnapshot, e: EngineEvent.Request, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        if (e.source == RequestSource.AUTOMATION && s.override?.isActive(now) == true) {
            fx += Effect.Log("AUTOMATION_BLOCKED_BY_OVERRIDE", e.channel)
            return s
        }
        var n = s
        if (e.source == RequestSource.USER) {
            n = n.copy(override = ManualOverride(now + s.policy.overrideDurationMs))
            val refs = e.targets.mapNotNull { n.refOf(it) }
            n = when (e.channel) {
                MEDIA -> n.copy(policy = n.policy.copy(mediaPriority = mergePriority(refs, n.policy.mediaPriority)))
                COMMUNICATION -> n.copy(policy = n.policy.copy(commPriority = mergePriority(refs, n.policy.commPriority)))
            }
        }
        return when (e.channel) {
            MEDIA -> {
                val st = n.media.state
                if (st is RouteState.Suspended) {
                    fx += Effect.Log("ROUTE_DEFERRED_UNTIL_CALL_ENDS", MEDIA, mapOf("targets" to names(n, e.targets)))
                    n.copy(media = n.media.copy(requested = e.targets, state = st.copy(resumeTargets = e.targets.mapNotNull { n.refOf(it) })))
                } else {
                    startMediaRequest(n, e.targets, now, fx)
                }
            }
            COMMUNICATION -> startCommRequest(n, e.targets, now, fx)
        }
    }

    private fun mergePriority(first: List<DeviceRef>, rest: List<DeviceRef>): List<DeviceRef> =
        (first + rest).distinctBy { it.stableKey }

    private fun validateMedia(s: RoutingSnapshot, targets: List<EndpointId>): RouteError? {
        if (targets.isEmpty()) return RouteError.OsRejected("no targets")
        val eps = targets.map { s.endpoint(it) }
        if (eps.any { it == null || !it.isConnected }) return RouteError.DeviceGone
        if (eps.any { !it!!.canCarry(MEDIA) }) return RouteError.NotAMediaDevice
        if (targets.size > 1 && eps.any { Capability.MULTI_OUTPUT_MEMBER !in it!!.capabilities }) return RouteError.UnsupportedCombo
        return null
    }

    private fun startMediaRequest(
        s: RoutingSnapshot,
        targets: List<EndpointId>,
        now: Long,
        fx: MutableList<Effect>,
        fallback: FallbackInfo? = null,
    ): RoutingSnapshot {
        val gen = s.generationCounter + 1
        val distinct = targets.distinct()
        val error = validateMedia(s, distinct)
        if (error != null) {
            fx += Effect.Log("ROUTE_REJECTED", MEDIA, mapOf("error" to error.code, "targets" to names(s, distinct)))
            return s.copy(
                generationCounter = gen,
                media = s.media.copy(requested = distinct, generation = gen, state = RouteState.Rejected(distinct, error)),
            )
        }
        val mode = if (distinct.size > 1) OutputMode.MULTI else OutputMode.SINGLE
        if (s.media.mode == OutputMode.MULTI && mode == OutputMode.SINGLE) fx += Effect.StopMulti
        if (s.media.mode == OutputMode.SINGLE && mode == OutputMode.MULTI) fx += Effect.ClearMediaPreference
        fx += if (mode == OutputMode.MULTI) Effect.RouteMediaMulti(distinct, gen) else Effect.RouteMediaSingle(distinct.single(), gen)
        fx += Effect.ScheduleTimeout(MEDIA, gen, s.policy.confirmTimeoutMs)
        fx += Effect.Log("ROUTE_REQUESTED", MEDIA, mapOf("targets" to names(s, distinct), "mode" to mode.name))
        val next = s.copy(
            generationCounter = gen,
            media = s.media.copy(
                requested = distinct,
                mode = mode,
                generation = gen,
                state = RouteState.Requested(distinct, now, fallback = fallback),
            ),
        )
        return reconcileMedia(next, now, fx)
    }

    private fun startCommRequest(
        s: RoutingSnapshot,
        targets: List<EndpointId>,
        now: Long,
        fx: MutableList<Effect>,
        fallback: FallbackInfo? = null,
    ): RoutingSnapshot {
        val gen = s.generationCounter + 1
        val target = targets.singleOrNull()
        val ep = target?.let { s.endpoint(it) }
        val error = when {
            targets.size > 1 -> RouteError.UnsupportedCombo
            ep == null || !ep.isConnected -> RouteError.DeviceGone
            !ep.canCarry(COMMUNICATION) -> RouteError.NotACommDevice
            else -> null
        }
        if (error != null) {
            fx += Effect.Log("ROUTE_REJECTED", COMMUNICATION, mapOf("error" to error.code, "targets" to names(s, targets)))
            return s.copy(
                generationCounter = gen,
                comm = s.comm.copy(requested = targets, generation = gen, state = RouteState.Rejected(targets, error)),
            )
        }
        target!!
        fx += Effect.RouteComm(target, gen)
        fx += Effect.ScheduleTimeout(COMMUNICATION, gen, s.policy.confirmTimeoutMs)
        fx += Effect.Log("ROUTE_REQUESTED", COMMUNICATION, mapOf("targets" to names(s, targets)))
        val next = s.copy(
            generationCounter = gen,
            comm = s.comm.copy(
                requested = listOf(target),
                generation = gen,
                state = RouteState.Requested(listOf(target), now, fallback = fallback),
            ),
        )
        return reconcileComm(next, now, fx)
    }

    private fun onRelease(s: RoutingSnapshot, channel: ChannelType, fx: MutableList<Effect>): RoutingSnapshot {
        val gen = s.generationCounter + 1
        fx += Effect.Log("ROUTE_RELEASED", channel)
        return when (channel) {
            MEDIA -> {
                fx += if (s.media.mode == OutputMode.MULTI) Effect.StopMulti else Effect.ClearMediaPreference
                s.copy(
                    generationCounter = gen,
                    media = s.media.copy(requested = emptyList(), state = RouteState.Idle, mode = OutputMode.SINGLE, generation = gen),
                )
            }
            COMMUNICATION -> {
                fx += Effect.ReleaseComm
                s.copy(generationCounter = gen, comm = s.comm.copy(requested = emptyList(), state = RouteState.Idle, generation = gen))
            }
        }
    }

    private fun onApplyProfile(
        s: RoutingSnapshot,
        profile: Profile,
        source: RequestSource,
        now: Long,
        fx: MutableList<Effect>,
    ): RoutingSnapshot {
        if (source == RequestSource.AUTOMATION && s.override?.isActive(now) == true) {
            fx += Effect.Log("AUTOMATION_BLOCKED_BY_OVERRIDE", null, mapOf("profile" to profile.name))
            return s
        }
        var n = s.copy(
            activeProfileId = profile.id,
            override = if (source == RequestSource.USER) ManualOverride(now + s.policy.overrideDurationMs) else s.override,
            policy = s.policy.copy(
                mediaPriority = profile.mediaTargets,
                commPriority = listOfNotNull(profile.commTarget),
                fallback = profile.fallback,
                restoreAfterCall = profile.restoreAfterCall,
            ),
        )
        fx += Effect.Log("PROFILE_APPLIED", null, mapOf("profile" to profile.name, "source" to source.name))

        val targets = ProfileResolver.mediaTargets(profile, n.endpoints)
        val mediaState = n.media.state
        n = when {
            mediaState is RouteState.Suspended ->
                n.copy(media = n.media.copy(state = mediaState.copy(resumeTargets = targets.mapNotNull { n.refOf(it) })))
            targets.isNotEmpty() -> startMediaRequest(n, targets, now, fx)
            profile.mediaTargets.isNotEmpty() -> {
                fx += Effect.Log("PROFILE_WAITING_FOR_DEVICES", MEDIA, mapOf("profile" to profile.name))
                if (n.isPlaying) fx += Effect.PausePlayback
                n.copy(media = n.media.copy(state = RouteState.Paused(profile.mediaTargets, n.isPlaying)))
            }
            else -> n
        }
        targets.forEach { id ->
            val key = n.endpoint(id)?.stableKey ?: return@forEach
            profile.perDeviceGain[key]?.let { fx += Effect.SetGain(id, it) }
            profile.perDeviceDelayMs[key]?.let { fx += Effect.SetDelay(id, it) }
        }
        if (n.commSession == CommSession.VOIP) {
            ProfileResolver.commTarget(profile, n.endpoints)?.let { n = startCommRequest(n, listOf(it), now, fx) }
        }
        return n
    }

    // endregion

    // region command results & timeouts

    private fun onCommandRejected(s: RoutingSnapshot, e: EngineEvent.CommandRejected, fx: MutableList<Effect>): RoutingSnapshot {
        val ch = s.channel(e.channel)
        if (ch.generation != e.generation) return s
        val targets = when (val st = ch.state) {
            is RouteState.Requested -> st.targets
            is RouteState.PartiallyConfirmed -> st.confirmed + st.pending
            else -> return s
        }
        fx += Effect.Log("ROUTE_REJECTED", e.channel, mapOf("error" to e.error.code, "targets" to names(s, targets)))
        return s.withChannel(ch.copy(state = RouteState.Rejected(targets, e.error)))
    }

    private fun onPickerRequired(s: RoutingSnapshot, e: EngineEvent.SystemPickerRequired, fx: MutableList<Effect>): RoutingSnapshot {
        val ch = s.channel(e.channel)
        val st = ch.state
        if (ch.generation != e.generation || st !is RouteState.Requested) return s
        fx += Effect.Log("ROUTE_NEEDS_SYSTEM_PICKER", e.channel, mapOf("targets" to names(s, st.targets)))
        fx += Effect.ScheduleTimeout(e.channel, e.generation, s.policy.pickerTimeoutMs)
        return s.withChannel(ch.copy(state = st.copy(needsSystemPicker = true)))
    }

    private fun onTimeout(s: RoutingSnapshot, e: EngineEvent.ConfirmTimeout, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        val ch = s.channel(e.channel)
        if (ch.generation != e.generation) return s
        return when (val st = ch.state) {
            is RouteState.Requested -> {
                if (st.needsSystemPicker && now - st.since < s.policy.pickerTimeoutMs) return s
                val error = if (ch.active.isNotEmpty() && !ch.active.containsAll(st.targets)) {
                    RouteError.OsRedirected(ch.active)
                } else {
                    RouteError.Timeout
                }
                fx += Effect.Log(error.code, e.channel, mapOf("targets" to names(s, st.targets), "actual" to names(s, ch.active)))
                s.withChannel(ch.copy(state = RouteState.Rejected(st.targets, error)))
            }
            is RouteState.PartiallyConfirmed -> {
                fx += Effect.Log(
                    "MULTI_PARTIAL", e.channel,
                    mapOf("confirmed" to names(s, st.confirmed), "missing" to names(s, st.pending)),
                )
                s.withChannel(ch.copy(state = RouteState.Confirmed(st.confirmed, st.fallback)))
            }
            else -> s
        }
    }

    // endregion

    // region fallback (MED-006, CFG-004)

    private fun applyMediaFallback(
        s: RoutingSnapshot,
        current: List<EndpointId>,
        lost: List<EndpointId>,
        now: Long,
        fx: MutableList<Effect>,
    ): RoutingSnapshot {
        val st = s.media.state
        val lostRefs = lost.mapNotNull { s.refOf(it) }
        val original = st.fallbackInfo()?.original ?: current.mapNotNull { s.refOf(it) }
        val survivors = current.filter { it !in lost && s.endpoint(it)?.canCarry(MEDIA) == true }
        val info = FallbackInfo(lost = lostRefs, original = original)
        val usable = s.endpoints.filter {
            it.canCarry(MEDIA) && it.id !in lost &&
                !(s.policy.fallback.avoidEarpieceForMedia && it.kind == EndpointKind.BUILTIN_EARPIECE)
        }

        for (rule in s.policy.fallback.rules) {
            val pick: List<EndpointId>? = when (rule) {
                FallbackRule.StopMultiKeepRest -> survivors.takeIf { s.media.mode == OutputMode.MULTI && it.isNotEmpty() }
                FallbackRule.NextInPriority -> {
                    val byPriority = s.policy.mediaPriority.firstNotNullOfOrNull { ref -> usable.firstOrNull { it.stableKey == ref.stableKey } }
                    (byPriority ?: ProfileResolver.bestMedia(usable))?.let { listOf(it.id) }
                }
                FallbackRule.BuiltInSpeaker -> usable.firstOrNull { it.kind == EndpointKind.BUILTIN_SPEAKER }?.let { listOf(it.id) }
                FallbackRule.LastKnownGood -> s.history.firstNotNullOfOrNull { set ->
                    val resolved = set.mapNotNull { ref -> usable.firstOrNull { it.stableKey == ref.stableKey } }
                    resolved.takeIf { it.isNotEmpty() && it.size == set.size }?.map { it.id }
                }
                is FallbackRule.Specific -> usable.firstOrNull { it.stableKey == rule.device.stableKey }?.let { listOf(it.id) }
                FallbackRule.Pause -> {
                    fx += Effect.Log("FALLBACK_APPLIED", MEDIA, mapOf("rule" to "pause", "lost" to lostRefs.joinToString { it.displayName }))
                    return pauseMedia(s, lostRefs, original, fx)
                }
            }
            if (!pick.isNullOrEmpty()) {
                fx += Effect.Log(
                    "FALLBACK_APPLIED", MEDIA,
                    mapOf("rule" to rule.label, "lost" to lostRefs.joinToString { it.displayName }, "to" to names(s, pick)),
                )
                return startMediaRequest(s, pick, now, fx, fallback = info)
            }
        }
        fx += Effect.Log("FALLBACK_EXHAUSTED", MEDIA, mapOf("lost" to lostRefs.joinToString { it.displayName }))
        return pauseMedia(s, lostRefs, original, fx)
    }

    private fun pauseMedia(s: RoutingSnapshot, waitingFor: List<DeviceRef>, restore: List<DeviceRef>, fx: MutableList<Effect>): RoutingSnapshot {
        if (s.isPlaying) fx += Effect.PausePlayback
        if (s.media.mode == OutputMode.MULTI) fx += Effect.StopMulti
        return s.copy(
            media = s.media.copy(
                mode = OutputMode.SINGLE,
                state = RouteState.Paused(waitingFor.ifEmpty { restore }, s.isPlaying, restore),
            ),
        )
    }

    private fun maybeRestoreMedia(s: RoutingSnapshot, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        when (val st = s.media.state) {
            is RouteState.Paused -> {
                val anyBack = st.waitingFor.any { ref -> s.endpoints.any { it.stableKey == ref.stableKey && it.canCarry(MEDIA) } }
                if (!anyBack) return s
                val restore = resolveMedia(s, st.restoreTargets)
                if (restore.isEmpty()) return s
                fx += Effect.Log("ROUTE_RESTORED", MEDIA, mapOf("targets" to names(s, restore)))
                if (st.resumePlayback) fx += Effect.ResumePlayback
                return startMediaRequest(s, restore, now, fx)
            }
            is RouteState.Confirmed -> {
                val info = st.fallback ?: return s
                if (!s.policy.fallback.autoResumeWhenPreferredReturns) return s
                val restore = resolveMedia(s, info.original)
                if (restore.size != info.original.size || restore.toSet() == st.active.toSet()) return s
                fx += Effect.Log("FALLBACK_RESTORED", MEDIA, mapOf("targets" to names(s, restore)))
                return startMediaRequest(s, restore, now, fx)
            }
            else -> return s
        }
    }

    private fun resolveMedia(s: RoutingSnapshot, refs: List<DeviceRef>): List<EndpointId> {
        val resolved = refs.mapNotNull { ref -> s.endpoints.firstOrNull { it.stableKey == ref.stableKey && it.canCarry(MEDIA) } }
        // Multi-output only with members that support it; otherwise keep the first.
        return if (resolved.size > 1 && resolved.all { Capability.MULTI_OUTPUT_MEMBER in it.capabilities }) {
            resolved.map { it.id }
        } else {
            resolved.take(1).map { it.id }
        }
    }

    private fun applyCommFallback(s: RoutingSnapshot, lost: List<EndpointId>, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        val usable = s.endpoints.filter { it.canCarry(COMMUNICATION) && it.id !in lost }
        val pick = s.policy.commPriority.firstNotNullOfOrNull { ref -> usable.firstOrNull { it.stableKey == ref.stableKey } }
            ?: ProfileResolver.bestComm(usable)
        val lostRefs = lost.mapNotNull { s.refOf(it) }
        if (pick == null) {
            fx += Effect.Log("FALLBACK_EXHAUSTED", COMMUNICATION, mapOf("lost" to lostRefs.joinToString { it.displayName }))
            return s.copy(comm = s.comm.copy(state = RouteState.Rejected(lost, RouteError.DeviceGone)))
        }
        fx += Effect.Log(
            "FALLBACK_APPLIED", COMMUNICATION,
            mapOf("lost" to lostRefs.joinToString { it.displayName }, "to" to pick.name),
        )
        val original = s.comm.state.fallbackInfo()?.original ?: lostRefs
        return startCommRequest(s, listOf(pick.id), now, fx, FallbackInfo(lostRefs, original))
    }

    // endregion

    // region communication sessions (RTE-006, RTE-007, VOIP-005)

    private fun onCommStarted(s: RoutingSnapshot, session: CommSession, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        if (s.commSession == session) return s
        val before = when (val st = s.media.state) {
            is RouteState.Paused -> st.restoreTargets
            is RouteState.Suspended -> st.resumeTargets
            else -> st.targets().mapNotNull { s.refOf(it) }
        }
        var n = s.copy(commSession = session, mediaBeforeComm = before)
        fx += Effect.Log("COMM_SESSION_STARTED", COMMUNICATION, mapOf("session" to session.name))

        val suspendMedia = session == CommSession.CELLULAR || !s.policy.splitMediaAndComm
        val mediaState = n.media.state
        if (suspendMedia && mediaState !is RouteState.Idle && mediaState !is RouteState.Suspended) {
            if (n.isPlaying) fx += Effect.PausePlayback
            if (n.media.mode == OutputMode.MULTI) fx += Effect.StopMulti
            val reason = if (session == CommSession.CELLULAR) SuspendReason.CELLULAR_CALL else SuspendReason.VOIP_CALL
            n = n.copy(
                wasPlayingBeforeSuspend = n.isPlaying,
                media = n.media.copy(mode = OutputMode.SINGLE, state = RouteState.Suspended(reason, before)),
            )
        }
        if (session == CommSession.VOIP) {
            val comm = n.endpoints.filter { it.canCarry(COMMUNICATION) }
            val target = n.policy.commPriority.firstNotNullOfOrNull { ref -> comm.firstOrNull { it.stableKey == ref.stableKey } }
                ?: ProfileResolver.bestComm(comm)
            if (target != null) n = startCommRequest(n, listOf(target.id), now, fx)
        }
        return n
    }

    private fun onCommEnded(s: RoutingSnapshot, now: Long, fx: MutableList<Effect>): RoutingSnapshot {
        val session = s.commSession ?: return s
        var n = s.copy(commSession = null)
        fx += Effect.Log("COMM_SESSION_ENDED", COMMUNICATION, mapOf("session" to session.name))
        if (session == CommSession.VOIP) {
            fx += Effect.ReleaseComm
            val gen = n.generationCounter + 1
            n = n.copy(generationCounter = gen, comm = n.comm.copy(requested = emptyList(), state = RouteState.Idle, generation = gen))
        }
        val st = n.media.state
        if (st is RouteState.Suspended) {
            n = if (n.policy.restoreAfterCall) {
                val back = resolveMedia(n, st.resumeTargets)
                if (back.isNotEmpty()) {
                    fx += Effect.Log("ROUTE_RESTORED", MEDIA, mapOf("targets" to names(n, back)))
                    if (n.wasPlayingBeforeSuspend) fx += Effect.ResumePlayback
                    startMediaRequest(n, back, now, fx)
                } else {
                    n.copy(media = n.media.copy(state = RouteState.Paused(st.resumeTargets, n.wasPlayingBeforeSuspend)))
                }
            } else {
                n.copy(media = n.media.copy(state = RouteState.Idle))
            }
        } else if (n.policy.restoreAfterCall && n.mediaBeforeComm.isNotEmpty()) {
            val currentKeys = n.media.state.targets().mapNotNull { n.refOf(it)?.stableKey }.toSet()
            if (currentKeys != n.mediaBeforeComm.map { it.stableKey }.toSet()) {
                val back = resolveMedia(n, n.mediaBeforeComm)
                if (back.isNotEmpty()) {
                    fx += Effect.Log("ROUTE_RESTORED", MEDIA, mapOf("targets" to names(n, back)))
                    n = startMediaRequest(n, back, now, fx)
                }
            }
        }
        return n.copy(wasPlayingBeforeSuspend = false, mediaBeforeComm = emptyList())
    }

    // endregion

    // region helpers

    /** Endpoints a channel holds or was moved away from by the OS; their loss triggers fallback. */
    private fun RouteState.heldTargets(): List<EndpointId> = when (this) {
        is RouteState.Rejected -> if (error is RouteError.OsRedirected) targets else emptyList()
        else -> targets()
    }

    private fun RouteState.fallbackInfo(): FallbackInfo? = when (this) {
        is RouteState.Requested -> fallback
        is RouteState.PartiallyConfirmed -> fallback
        is RouteState.Confirmed -> fallback
        else -> null
    }

    private fun RoutingSnapshot.withChannel(ch: ChannelSnapshot): RoutingSnapshot =
        if (ch.type == MEDIA) copy(media = ch) else copy(comm = ch)

    private fun pushHistory(s: RoutingSnapshot, ids: List<EndpointId>): List<List<DeviceRef>> {
        val refs = ids.mapNotNull { s.refOf(it) }
        if (refs.isEmpty()) return s.history
        val keys = refs.map { it.stableKey }
        return (listOf(refs) + s.history.filter { set -> set.map { it.stableKey } != keys }).take(5)
    }

    private fun confirmedLog(s: RoutingSnapshot, channel: ChannelType, ids: List<EndpointId>, latencyMs: Long) =
        Effect.Log("ROUTE_CONFIRMED", channel, mapOf("targets" to names(s, ids), "latencyMs" to latencyMs.toString()))

    private fun names(s: RoutingSnapshot, ids: List<EndpointId>): String = ids.joinToString { s.displayName(it) }

    // endregion
}
