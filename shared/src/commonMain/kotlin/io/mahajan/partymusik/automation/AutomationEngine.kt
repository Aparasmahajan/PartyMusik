package io.mahajan.partymusik.automation

import io.mahajan.partymusik.data.LocalStore
import io.mahajan.partymusik.diagnostics.Diagnostics
import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.Trigger
import io.mahajan.partymusik.engine.RoutingEngine
import io.mahajan.partymusik.ports.PresenceEvent
import io.mahajan.partymusik.ports.PresencePort
import io.mahajan.partymusik.profiles.ProfileManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch

/**
 * Connection-triggered profile automation (CFG-006, ADR-004).
 * Foreground: endpoint diffs from the routing engine. Background (Android): OS presence wake-ups.
 * Manual overrides are enforced by the engine (CFG-008).
 */
class AutomationEngine(
    private val engine: RoutingEngine,
    private val profiles: ProfileManager,
    private val presence: PresencePort,
    private val store: LocalStore,
    private val diagnostics: Diagnostics,
    private val scope: CoroutineScope,
) {
    private data class Diff(val previous: Set<String>, val current: Set<String>)

    fun start() {
        scope.launch {
            engine.state
                // null until the first OS endpoint snapshot has reached the engine
                .map { s -> if (s.knownRefs.isEmpty()) null else s.endpoints.filter { it.isConnected }.map { it.stableKey }.toSet() }
                .filterNotNull()
                .distinctUntilChanged()
                .runningFold(Diff(emptySet(), emptySet())) { acc, keys -> Diff(acc.current, keys) }
                .drop(2) // initial seed + first snapshot: already-connected devices are not "connect" events
                .collect { d ->
                    (d.current - d.previous).forEach { onConnected(it, SOURCE_FOREGROUND) }
                    (d.previous - d.current).forEach { onDisconnected(it, SOURCE_FOREGROUND) }
                }
        }
        scope.launch {
            presence.presence.collect { event ->
                // S-13/S-14 measure how reliably the OS delivers these, so every one is logged.
                when (event) {
                    is PresenceEvent.Appeared -> {
                        diagnostics.log("PRESENCE_EVENT", null, mapOf("event" to "appeared"))
                        onConnected(event.stableKey, SOURCE_OS_PRESENCE)
                    }
                    is PresenceEvent.Disappeared -> {
                        diagnostics.log("PRESENCE_EVENT", null, mapOf("event" to "disappeared"))
                        onDisconnected(event.stableKey, SOURCE_OS_PRESENCE)
                    }
                }
            }
        }
        scope.launch { syncPresenceObservations() }
    }

    /** Applies profiles that trigger on app launch (called once the store is loaded). */
    suspend fun onAppLaunched() {
        if (!store.settings.value.automationEnabled) return
        profiles.profiles.value.firstOrNull { p -> p.triggers.any { it is Trigger.AppLaunched } }?.let {
            fire(it, "app launched", SOURCE_APP)
        }
    }

    /** Registers OS presence observation for every device referenced by a connect/disconnect trigger. */
    suspend fun syncPresenceObservations() {
        profiles.profiles.value.flatMap { it.triggers }.mapNotNull {
            when (it) {
                is Trigger.DeviceConnected -> it.device
                is Trigger.DeviceDisconnected -> it.device
                Trigger.AppLaunched -> null
            }
        }.distinctBy { it.stableKey }.forEach { presence.observe(it) }
    }

    private suspend fun onConnected(stableKey: String, source: String) {
        if (!store.settings.value.automationEnabled) return
        profiles.profiles.value.firstOrNull { p ->
            p.triggers.any { it is Trigger.DeviceConnected && it.device.stableKey == stableKey }
        }?.let { fire(it, "device connected", source) }
    }

    private suspend fun onDisconnected(stableKey: String, source: String) {
        if (!store.settings.value.automationEnabled) return
        profiles.profiles.value.firstOrNull { p ->
            p.triggers.any { it is Trigger.DeviceDisconnected && it.device.stableKey == stableKey }
        }?.let { fire(it, "device disconnected", source) }
    }

    private suspend fun fire(profile: Profile, why: String, source: String) {
        diagnostics.log("AUTOMATION_TRIGGERED", null, mapOf("profile" to profile.name, "trigger" to why, "source" to source))
        profiles.apply(profile.id, RequestSource.AUTOMATION)
    }

    private companion object {
        const val SOURCE_FOREGROUND = "foreground"
        const val SOURCE_OS_PRESENCE = "os-presence"
        const val SOURCE_APP = "app"
    }
}
