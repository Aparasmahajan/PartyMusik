package io.mahajan.partymusik

import io.mahajan.partymusik.data.LocalStore
import io.mahajan.partymusik.diagnostics.CompatOutcome
import io.mahajan.partymusik.domain.BuiltInProfiles
import io.mahajan.partymusik.domain.ChannelType.MEDIA
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.ProfileTemplate
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteError
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.domain.Trigger
import io.mahajan.partymusik.platform.Clock
import io.mahajan.partymusik.platform.ExportSink
import io.mahajan.partymusik.platform.InMemoryTextStore
import io.mahajan.partymusik.platform.TextStore
import io.mahajan.partymusik.ports.Peer
import io.mahajan.partymusik.simulation.SimulatedPlatform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** End-to-end tests of the shared core against the simulated phone (virtual time). */
@OptIn(ExperimentalCoroutinesApi::class)
class CoreIntegrationTest {

    private val sony = EndpointId("sim:bt-sony-xm5")
    private val jbl = EndpointId("sim:bt-jbl-charge6")
    private val buds = EndpointId("sim:bt-buds3pro")

    private class Rig(val core: PartyMusikCore, val sim: SimulatedPlatform) {
        val state get() = core.engine.state.value
    }

    private fun TestScope.rig(store: TextStore = InMemoryTextStore(), exportSink: ExportSink? = null): Rig {
        val clock = Clock { testScheduler.currentTime }
        val sim = SimulatedPlatform(backgroundScope, clock)
        val core = PartyMusikCore(sim.ports, store, backgroundScope, clock, exportSink)
        core.start()
        settle()
        return Rig(core, sim)
    }

    private fun TestScope.settle(ms: Long = 5_000) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun startup_applies_music_profile_and_confirms_best_output() = runTest {
        val r = rig()
        assertEquals(BuiltInProfiles.MUSIC_ID, r.state.activeProfileId)
        assertEquals(RouteState.Confirmed(listOf(sony)), r.state.media.state)
        assertEquals(listOf(sony), r.state.media.active)
    }

    @Test
    fun disconnect_falls_back_and_restores_when_device_returns() = runTest {
        val r = rig()
        r.sim.setConnected("bt-sony-xm5", false)
        settle()
        val fallback = assertIs<RouteState.Confirmed>(r.state.media.state)
        assertEquals(listOf(jbl), fallback.active)
        assertEquals("bt-sony-xm5", fallback.fallback!!.lost.single().stableKey)

        r.sim.setConnected("bt-sony-xm5", true)
        settle()
        assertEquals(RouteState.Confirmed(listOf(sony)), r.state.media.state)
    }

    @Test
    fun survives_200_connect_disconnect_cycles() = runTest {
        val r = rig()
        val rnd = Random(1)
        val keys = listOf("bt-sony-xm5", "bt-jbl-charge6", "bt-buds3pro", "usb-dac", "bt-carkit")
        repeat(200) { i ->
            r.sim.setConnected(keys[i % keys.size], rnd.nextBoolean())
            if (i % 37 == 0) r.sim.setBluetooth(i % 74 != 0)
            if (i % 23 == 0) r.core.engine.request(MEDIA, r.state.media.available.shuffled(rnd).take(rnd.nextInt(1, 3)))
            settle(rnd.nextLong(20, 1_500))
        }
        r.sim.setBluetooth(true)
        settle(10_000)

        assertTrue(r.core.diagnostics.events.value.none { it.code == "ENGINE_REDUCER_ERROR" })
        val st = r.state.media.state
        assertTrue(st is RouteState.Confirmed || st is RouteState.Paused || st is RouteState.Rejected, "settled: $st")
        if (st is RouteState.Confirmed) {
            val connected = r.state.endpoints.map { it.id }
            assertTrue(connected.containsAll(st.active), "active outputs are connected")
        }
    }

    @Test
    fun voip_call_uses_its_own_route_and_releases_it() = runTest {
        val r = rig()
        r.core.engine.request(MEDIA, listOf(jbl))
        settle()
        r.core.calls.startCall(Peer("Loopback", "loopback://echo"))
        settle()
        assertEquals(RouteState.Confirmed(listOf(sony)), r.state.comm.state)
        assertEquals(RouteState.Confirmed(listOf(jbl)), r.state.media.state, "media stays on the speaker")

        r.core.calls.hangup()
        settle()
        assertEquals(RouteState.Idle, r.state.comm.state)
        assertEquals(null, r.state.observedComm, "communication device released")
    }

    @Test
    fun cellular_call_pauses_media_and_resumes_on_the_same_output() = runTest {
        val r = rig()
        r.core.ports.playback.play()
        settle(1_000)
        r.sim.startCellularCall()
        settle()
        assertIs<RouteState.Suspended>(r.state.media.state)
        assertFalse(r.core.ports.playback.status.value.playing)

        r.sim.endCellularCall()
        settle()
        assertEquals(RouteState.Confirmed(listOf(sony)), r.state.media.state)
        assertTrue(r.core.ports.playback.status.value.playing)
    }

    @Test
    fun connection_trigger_applies_profile_unless_user_overrode() = runTest {
        val r = rig()
        val budsRef = r.sim.devices.value.first { it.key == "bt-buds3pro" }.endpoint!!.toRef()
        val p = r.core.profiles.create(ProfileTemplate.CUSTOM, "Gym")
        r.core.profiles.save(p.copy(mediaTargets = listOf(budsRef), triggers = listOf(Trigger.DeviceConnected(budsRef))))

        r.sim.setConnected("bt-buds3pro", true)
        settle()
        assertEquals(p.id, r.state.activeProfileId)
        assertEquals(RouteState.Confirmed(listOf(buds)), r.state.media.state)

        r.core.engine.request(MEDIA, listOf(jbl), RequestSource.USER) // manual override
        settle()
        r.sim.setConnected("bt-buds3pro", false)
        settle()
        r.sim.setConnected("bt-buds3pro", true)
        settle()
        assertEquals(RouteState.Confirmed(listOf(jbl)), r.state.media.state)
        assertTrue(r.core.diagnostics.events.value.any { it.code == "AUTOMATION_BLOCKED_BY_OVERRIDE" })
    }

    @Test
    fun profiles_persist_across_restarts() = runTest {
        val store = InMemoryTextStore()
        val r = rig(store)
        val p = r.core.profiles.create(ProfileTemplate.MULTI_DEVICE, "Party")
        assertTrue(r.core.profiles.rename(p.id, "Garden party"))
        assertFalse(r.core.profiles.delete(BuiltInProfiles.MUSIC_ID), "built-ins cannot be deleted")

        val reloaded = LocalStore(store).apply { load() }
        val names = reloaded.profiles.value.map { it.name }
        assertTrue("Garden party" in names)
        assertTrue(names.containsAll(listOf("Music", "Calls", "Multi-Device", "Custom")))
    }

    @Test
    fun compatibility_test_reports_confirmed_routes() = runTest {
        val r = rig()
        val report = r.core.compatibility.run()
        val media = report.results.filter { it.testId == "media_route" }
        assertEquals(setOf("Phone speaker", "Sony WH-1000XM5", "JBL Charge 6"), media.map { it.subject }.toSet())
        assertTrue(media.all { it.outcome == CompatOutcome.PASS })
        assertEquals(CompatOutcome.PASS, report.results.single { it.testId == "multi_output" }.outcome)
        settle()
        assertEquals(RouteState.Confirmed(listOf(sony)), r.state.media.state, "previous route restored")
    }

    @Test
    fun sync_measurement_matches_device_latency_and_compensates() = runTest {
        val r = rig()
        r.core.engine.request(MEDIA, listOf(sony, jbl))
        settle()
        assertIs<RouteState.Confirmed>(r.state.media.state)
        val report = r.core.sync.measure(listOf(sony, jbl), repeats = 3)
        assertTrue(abs(report.skewMs - 40.0) < 5.0, "skew ${report.skewMs}")
        r.core.sync.applyCompensation(report)
        assertTrue(abs((r.sim.delays.value[jbl] ?: 0) - 40) <= 5)
        assertEquals(0, r.sim.delays.value[sony])
    }

    @Test
    fun picker_only_outputs_wait_for_the_system_picker() = runTest {
        val r = rig()
        r.sim.setBehaviour { it.copy(pickerOnlyKinds = setOf(EndpointKind.BT_CLASSIC_MEDIA)) }
        settle(100)
        r.core.engine.request(MEDIA, listOf(jbl))
        settle(4_000)
        assertTrue((r.state.media.state as RouteState.Requested).needsSystemPicker)
        r.core.ports.systemAudio.showSystemOutputPicker()
        settle()
        assertEquals(RouteState.Confirmed(listOf(jbl)), r.state.media.state)
    }

    @Test
    fun os_redirect_is_reported_not_hidden() = runTest {
        val r = rig()
        r.sim.setBehaviour { it.copy(redirectMediaTo = "builtin-speaker") }
        settle(100)
        r.core.engine.request(MEDIA, listOf(jbl))
        settle()
        val st = assertIs<RouteState.Rejected>(r.state.media.state)
        assertEquals(RouteError.OsRedirected(listOf(EndpointId("sim:builtin-speaker"))), st.error)
    }

    @Test
    fun spike_export_contains_route_latencies_and_compatibility_results() = runTest {
        val written = mutableMapOf<String, Map<String, String>>()
        val r = rig(exportSink = ExportSink { folder, files -> written[folder] = files; "mem://$folder" })
        r.core.engine.request(MEDIA, listOf(jbl))
        settle()
        r.core.compatibility.run()
        settle()

        val location = r.core.exportSpikeData(anonymizeNames = false)
        val files = written.values.single()
        assertEquals("mem://${written.keys.single()}", location)
        val latencies = files.getValue("route_latency.csv").lines().drop(1).filter { it.isNotBlank() }
        assertTrue(latencies.any { ",MEDIA,CONFIRMED," in it && "JBL" in it }, latencies.joinToString("\n"))
        assertTrue(files.getValue("compat.csv").lines().any { "media_route" in it && "PASS" in it })
        val media = r.core.spikeData.summary().first { it.spike == "S-04/S-05" }
        assertTrue(media.n >= 2 && media.p50 != null)
    }

    @Test
    fun diagnostics_survive_a_process_restart() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val profiles = InMemoryTextStore()
        val events = InMemoryTextStore()
        val first = PartyMusikCore(SimulatedPlatform(backgroundScope, clock).ports, profiles, backgroundScope, clock, diagnosticsStore = events)
        first.start()
        settle()
        first.diagnostics.log("PRESENCE_EVENT", null, mapOf("event" to "appeared"))
        settle()
        val before = first.diagnostics.events.value

        // A new process (OS wake-up) restores the log and keeps counting.
        val second = PartyMusikCore(SimulatedPlatform(backgroundScope, clock).ports, profiles, backgroundScope, clock, diagnosticsStore = events)
        assertEquals(before, second.diagnostics.events.value)
        second.start()
        settle()
        val after = second.diagnostics.events.value
        assertEquals(1, after.count { it.code == "PRESENCE_EVENT" })
        assertEquals(2, after.count { it.code == "APP_STARTED" })
        assertEquals(after.map { it.seq }.sorted(), after.map { it.seq })
    }

    @Test
    fun diagnostics_salt_exists_before_the_first_event_and_is_kept() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val profiles = InMemoryTextStore()
        val first = PartyMusikCore(SimulatedPlatform(backgroundScope, clock).ports, profiles, backgroundScope, clock)
        val salt = first.store.settings.value.diagnosticsSalt
        assertEquals(32, salt.length)
        assertTrue(salt in profiles.read()!!, "salt is saved before anything is logged")
        first.diagnostics.log("DEVICE_CONNECTED", null, mapOf("device" to "Sony (AC:80:0A:12:34:56)"))
        assertTrue("addr#" in first.diagnostics.events.value.single().detail.getValue("device"))

        val second = PartyMusikCore(SimulatedPlatform(backgroundScope, clock).ports, profiles, backgroundScope, clock)
        assertEquals(salt, second.store.settings.value.diagnosticsSalt)
        val fresh = PartyMusikCore(SimulatedPlatform(backgroundScope, clock).ports, InMemoryTextStore(), backgroundScope, clock)
        assertTrue(fresh.store.settings.value.diagnosticsSalt != salt)
    }
}
