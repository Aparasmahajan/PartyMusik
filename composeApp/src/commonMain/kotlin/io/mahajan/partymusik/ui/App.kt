package io.mahajan.partymusik.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.diagnostics.CompatReport
import io.mahajan.partymusik.diagnostics.CompatResult
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.simulation.SimulatedPlatform
import io.mahajan.partymusik.sync.SyncReport
import io.mahajan.partymusik.ui.components.TabBarHeight
import io.mahajan.partymusik.ui.screens.CallsScreen
import io.mahajan.partymusik.ui.screens.LabScreen
import io.mahajan.partymusik.ui.screens.NowScreen
import io.mahajan.partymusik.ui.screens.OutputSheet
import io.mahajan.partymusik.ui.screens.OutputsScreen
import io.mahajan.partymusik.ui.screens.ProfilesScreen
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyTheme
import io.mahajan.partymusik.ui.theme.PartyType

/** The five destinations (docs/18 §B.2). */
enum class Tab(val title: String, val icon: ImageVector) {
    NOW("Now", PartyIcons.TabNow),
    OUTPUTS("Outputs", PartyIcons.TabOutputs),
    CALLS("Calls", PartyIcons.Call),
    PROFILES("Profiles", PartyIcons.TabProfiles),
    LAB("Lab", PartyIcons.TabLab),
}

/**
 * UI state that must survive switching tabs: the open sheet, per-output gain and the latest sync and
 * compatibility results. Routing state itself always comes from the core.
 */
@Stable
class UiSession {
    var tab by mutableStateOf(Tab.NOW)
    var outputSheetOpen by mutableStateOf(false)
    val gains = mutableStateMapOf<EndpointId, Float>()
    var syncReport by mutableStateOf<SyncReport?>(null)
    var syncApplied by mutableStateOf(false)
    var compatRunning by mutableStateOf(false)
    val compatProgress = mutableStateListOf<CompatResult>()
    var compatReport by mutableStateOf<CompatReport?>(null)
}

/** Root composable shared by Android, iOS and the desktop simulator. */
@Composable
fun App(core: PartyMusikCore, simulator: SimulatedPlatform? = null) {
    PartyTheme {
        Box(Modifier.fillMaxSize().background(Party.colors.ground)) {
            if (simulator == null) {
                MainShell(core)
            } else {
                BoxWithConstraints {
                    if (maxWidth > 900.dp) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) { MainShell(core) }
                            VerticalDivider(color = Party.colors.separator)
                            SimulatorPanel(simulator, Modifier.width(360.dp).fillMaxHeight())
                        }
                    } else {
                        MainShell(core)
                    }
                }
            }
        }
    }
}

@Composable
internal fun MainShell(core: PartyMusikCore, session: UiSession = remember { UiSession() }) {
    Box(Modifier.fillMaxSize()) {
        when (session.tab) {
            Tab.NOW -> NowScreen(core, session)
            Tab.OUTPUTS -> OutputsScreen(core, session)
            Tab.CALLS -> CallsScreen(core)
            Tab.PROFILES -> ProfilesScreen(core)
            Tab.LAB -> LabScreen(core, session)
        }
        PartyTabBar(session.tab, { session.tab = it }, Modifier.align(Alignment.BottomCenter))
    }
    if (session.outputSheetOpen) {
        OutputSheet(core, session, onDismiss = { session.outputSheetOpen = false })
    }
}

/** Bottom tab bar on a translucent chrome fill; content scrolls underneath it. */
@Composable
private fun PartyTabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val c = Party.colors
    Column(modifier.fillMaxWidth().background(c.chrome).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(thickness = 0.5.dp, color = c.separator)
        Row(Modifier.fillMaxWidth().height(TabBarHeight).selectableGroup().padding(horizontal = 8.dp)) {
            Tab.entries.forEach { tab ->
                val on = tab == selected
                val tint = if (on) c.accent else c.secondary
                Column(
                    Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(tab.icon, null, tint = tint, modifier = Modifier.size(26.dp))
                    Text(tab.title, style = PartyType.tab, color = tint, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

/** Lets a screen hand the user to another tab (e.g. "Manage outputs"). */
internal fun UiSession.open(tab: Tab) {
    outputSheetOpen = false
    this.tab = tab
}
