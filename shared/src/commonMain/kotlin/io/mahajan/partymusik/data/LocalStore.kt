package io.mahajan.partymusik.data

import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.platform.TextStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val activeProfileId: String? = null,
    val automationEnabled: Boolean = true,
    val confirmTimeoutMs: Long = 3_000,
    val experimentalCombosEnabled: Boolean = false,
    val diagnosticsSalt: String = "",
    val onboardingDone: Boolean = false,
)

@Serializable
data class PersistedData(
    val schemaVersion: Int = 1,
    val profiles: List<Profile> = emptyList(),
    val settings: AppSettings = AppSettings(),
)

/**
 * Local, offline persistence for profiles and settings (CFG-007, NFR-005).
 * POC implementation: one JSON document behind [TextStore]. ADR-002 targets Room KMP + DataStore with
 * encryption at rest; callers only depend on this class's API so the swap is contained.
 */
class LocalStore(private val textStore: TextStore) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
    private val mutex = Mutex()

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** Returns false when stored data was unreadable and defaults were used. */
    fun load(): Boolean {
        val text = textStore.read() ?: return true
        return try {
            val data = json.decodeFromString(PersistedData.serializer(), text)
            _profiles.value = data.profiles
            _settings.value = data.settings
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun updateProfiles(transform: (List<Profile>) -> List<Profile>) = mutex.withLock {
        _profiles.value = transform(_profiles.value)
        persist()
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) = mutex.withLock {
        _settings.value = transform(_settings.value)
        persist()
    }

    /**
     * Returns the diagnostics salt, creating and saving one if there is none yet. The composition root calls this
     * before anything is logged, so first-launch events are pseudonymised with the real salt as well.
     */
    fun diagnosticsSalt(create: () -> String): String {
        _settings.value.diagnosticsSalt.takeIf { it.isNotEmpty() }?.let { return it }
        val salt = create()
        _settings.value = _settings.value.copy(diagnosticsSalt = salt)
        persist()
        return salt
    }

    fun exportProfiles(): String = json.encodeToString(PersistedData.serializer(), PersistedData(profiles = _profiles.value))

    private fun persist() {
        textStore.write(json.encodeToString(PersistedData.serializer(), PersistedData(profiles = _profiles.value, settings = _settings.value)))
    }
}
