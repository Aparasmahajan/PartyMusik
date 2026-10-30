package io.mahajan.partymusik.desktop

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.platform.DirectoryExportSink
import io.mahajan.partymusik.platform.FileTextStore
import io.mahajan.partymusik.simulation.SimulatedPlatform
import io.mahajan.partymusik.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Desktop host for the UI prototype (SRS deliverable 3): the shared app running on the simulated phone.
 * Data is stored in ~/.partymusik-sim (data.json, diagnostics.json); spike CSV exports go to ~/.partymusik-sim/exports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun main() {
    // One serial dispatcher for the engine and the simulator mirrors the single-threaded engine actor.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    val simulator = SimulatedPlatform(scope)
    val home = File(System.getProperty("user.home"), ".partymusik-sim")
    val store = FileTextStore(File(home, "data.json"))
    val core = PartyMusikCore(
        simulator.ports,
        store,
        scope,
        exportSink = DirectoryExportSink(File(home, "exports")),
        diagnosticsStore = FileTextStore(File(home, "diagnostics.json")),
    )
    core.start()

    application {
        Window(
            onCloseRequest = {
                core.stop()
                exitApplication()
            },
            title = "PartyMusik — POC (simulated phone)",
            state = rememberWindowState(width = 1320.dp, height = 900.dp),
        ) {
            App(core, simulator)
        }
    }
}
