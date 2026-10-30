package io.mahajan.partymusik

import io.mahajan.partymusik.automation.AutomationEngine
import io.mahajan.partymusik.calls.CallController
import io.mahajan.partymusik.data.LocalStore
import io.mahajan.partymusik.diagnostics.CompatibilityTest
import io.mahajan.partymusik.diagnostics.Diagnostics
import io.mahajan.partymusik.diagnostics.DiagnosticsPersistence
import io.mahajan.partymusik.diagnostics.SpikeData
import io.mahajan.partymusik.domain.BuiltInProfiles
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.engine.RoutingEngine
import io.mahajan.partymusik.engine.RoutingPolicy
import io.mahajan.partymusik.platform.Clock
import io.mahajan.partymusik.platform.ExportSink
import io.mahajan.partymusik.platform.TextStore
import io.mahajan.partymusik.ports.PlatformPorts
import io.mahajan.partymusik.profiles.ProfileManager
import io.mahajan.partymusik.registry.DeviceRegistry
import io.mahajan.partymusik.sync.SyncAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Composition root shared by Android, iOS and the desktop simulator.
 * Platforms supply [PlatformPorts] and a [TextStore]; everything else is common code.
 */
class PartyMusikCore(
    val ports: PlatformPorts,
    textStore: TextStore,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    val clock: Clock = Clock.System,
    private val exportSink: ExportSink? = null,
    diagnosticsStore: TextStore? = null,
) {
    val store = LocalStore(textStore)
    private val storeLoadedCleanly = store.load()

    // Random UUIDs come from the platform's secure random source.
    @OptIn(ExperimentalUuidApi::class)
    val diagnostics = Diagnostics(clock, salt = store.diagnosticsSalt { Uuid.random().toHexString() })
    private val diagnosticsPersistence = diagnosticsStore?.let { DiagnosticsPersistence(diagnostics, it, clock).apply { load() } }
    val engine = RoutingEngine(
        ports, scope, clock, diagnostics,
        RoutingPolicy(
            splitMediaAndComm = ports.info.supportsSplitMediaAndComm,
            confirmTimeoutMs = store.settings.value.confirmTimeoutMs,
        ),
    )
    val registry = DeviceRegistry(ports, scope)
    val profiles = ProfileManager(store, engine, clock)
    val automation = AutomationEngine(engine, profiles, ports.presence, store, diagnostics, scope)
    val calls = CallController(ports.voip, ports.callObserver, engine, diagnostics, scope)
    val spikeData = SpikeData(diagnostics)
    val sync = SyncAnalyzer(ports.syncProbe, ports.multiOutput, diagnostics, spikeData::record) { clock.nowMillis() }
    val compatibility = CompatibilityTest(ports, engine, diagnostics, clock, spikeData::record)

    /** Whether [exportSpikeData] can save files on this platform. */
    val canExportFiles: Boolean get() = exportSink != null

    /** Writes the spike CSVs into a timestamped folder; returns where they went, or null without an [ExportSink]. */
    fun exportSpikeData(anonymizeNames: Boolean): String? {
        val sink = exportSink ?: return null
        val snapshot = engine.state.value
        val files = spikeData.exportCsv(ports.info, { snapshot.displayName(it) }, anonymizeNames)
        return sink.write("spike-${clock.nowMillis()}", files)
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        diagnosticsPersistence?.launchIn(scope)
        if (!storeLoadedCleanly) diagnostics.log("STORE_UNREADABLE_DEFAULTS_USED")
        diagnostics.log(
            "APP_STARTED", null,
            mapOf("os" to ports.info.os, "osVersion" to ports.info.osVersion, "model" to ports.info.model),
        )
        engine.start()
        calls.start()
        automation.start()
        scope.launch {
            profiles.ensureBuiltIns()
            // Resolve the profile against real devices: wait for the first OS endpoint snapshot.
            withTimeoutOrNull(2_000) { engine.state.first { it.knownRefs.isNotEmpty() } }
            val active = store.settings.value.activeProfileId ?: BuiltInProfiles.MUSIC_ID
            profiles.apply(active, RequestSource.PROFILE)
            automation.onAppLaunched()
        }
    }

    fun stop() = scope.cancel()
}
