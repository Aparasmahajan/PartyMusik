package io.mahajan.partymusik.profiles

import io.mahajan.partymusik.data.LocalStore
import io.mahajan.partymusik.domain.BuiltInProfiles
import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.domain.ProfileTemplate
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.engine.RoutingEngine
import io.mahajan.partymusik.platform.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** CFG-001..005, CFG-007 */
class ProfileManager(
    private val store: LocalStore,
    private val engine: RoutingEngine,
    private val clock: Clock,
) {
    val profiles: StateFlow<List<Profile>> = store.profiles

    suspend fun ensureBuiltIns() {
        val now = clock.nowMillis()
        store.updateProfiles { existing ->
            val missing = BuiltInProfiles.all(now).filter { b -> existing.none { it.id == b.id } }
            existing + missing
        }
    }

    suspend fun create(template: ProfileTemplate, name: String): Profile {
        val now = clock.nowMillis()
        val base = BuiltInProfiles.all(now).first { it.template == template }
        val profile = base.copy(id = newId(), name = uniqueName(name), builtIn = false, createdAt = now, updatedAt = now)
        store.updateProfiles { it + profile }
        return profile
    }

    suspend fun duplicate(id: String): Profile? {
        val source = profiles.value.firstOrNull { it.id == id } ?: return null
        val now = clock.nowMillis()
        val copy = source.copy(id = newId(), name = uniqueName("${source.name} copy"), builtIn = false, createdAt = now, updatedAt = now)
        store.updateProfiles { it + copy }
        return copy
    }

    /** Saves edits; re-applies the profile if it is the active one so changes take effect immediately. */
    suspend fun save(profile: Profile) {
        val updated = profile.copy(updatedAt = clock.nowMillis())
        store.updateProfiles { list -> list.map { if (it.id == profile.id) updated else it } }
        if (engine.state.value.activeProfileId == profile.id) engine.applyProfile(updated, RequestSource.PROFILE)
    }

    suspend fun rename(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || profiles.value.any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) return false
        val profile = profiles.value.firstOrNull { it.id == id } ?: return false
        save(profile.copy(name = trimmed))
        return true
    }

    /** Built-in profiles can be edited but not deleted. */
    suspend fun delete(id: String): Boolean {
        val profile = profiles.value.firstOrNull { it.id == id } ?: return false
        if (profile.builtIn) return false
        store.updateProfiles { list -> list.filterNot { it.id == id } }
        if (store.settings.value.activeProfileId == id) store.updateSettings { it.copy(activeProfileId = null) }
        return true
    }

    /** CFG-005 (USER), CFG-006 (AUTOMATION); a USER apply also sets the manual override (CFG-008). */
    suspend fun apply(id: String, source: RequestSource): Boolean {
        val profile = profiles.value.firstOrNull { it.id == id } ?: return false
        if (source == RequestSource.AUTOMATION && engine.state.value.override?.isActive(clock.nowMillis()) == true) {
            // The engine logs AUTOMATION_BLOCKED_BY_OVERRIDE; the persisted active profile stays as the user chose.
            engine.applyProfile(profile, source)
            return false
        }
        engine.applyProfile(profile, source)
        store.updateSettings { it.copy(activeProfileId = id) }
        return true
    }

    private fun uniqueName(base: String): String {
        val names = profiles.value.map { it.name.lowercase() }.toSet()
        if (base.lowercase() !in names) return base
        var i = 2
        while ("$base $i".lowercase() in names) i++
        return "$base $i"
    }

    private fun newId(): String = "p-" + Random.nextLong().toULong().toString(16)
}
