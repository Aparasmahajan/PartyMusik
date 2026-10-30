package io.mahajan.partymusik.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.CommSession
import io.mahajan.partymusik.domain.RouteCommandResult
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.engine.RoutingSnapshot
import io.mahajan.partymusik.ports.CallState
import io.mahajan.partymusik.ports.Peer
import io.mahajan.partymusik.ports.SettingsTarget
import io.mahajan.partymusik.ui.components.DisclosureNote
import io.mahajan.partymusik.ui.components.GrayButton
import io.mahajan.partymusik.ui.components.GroupRow
import io.mahajan.partymusik.ui.components.IconTile
import io.mahajan.partymusik.ui.components.InsetGroup
import io.mahajan.partymusik.ui.components.RoundButton
import io.mahajan.partymusik.ui.components.RouteGlyph
import io.mahajan.partymusik.ui.components.RouteGlyphIcon
import io.mahajan.partymusik.ui.components.RouteStatusText
import io.mahajan.partymusik.ui.components.ScreenScroll
import io.mahajan.partymusik.ui.components.SectionTitle
import io.mahajan.partymusik.ui.components.Tile
import io.mahajan.partymusik.ui.components.detailLabel
import io.mahajan.partymusik.ui.components.endpointGlyph
import io.mahajan.partymusik.ui.components.formatMs
import io.mahajan.partymusik.ui.components.glyphColor
import io.mahajan.partymusik.ui.components.glyphStatus
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyShape
import io.mahajan.partymusik.ui.theme.PartyType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val CallCard = Color(0xFF1C1C1E)
private val CallButton = Color(0xFF3A3A3C)
private val CallSecondary = Color(0xFF98989F)
private val CallLabel = Color(0xFFEBEBF5)

/** Calls: the loopback test call and where call audio goes. VOIP-001..006, CALL-001..003. */
@Composable
fun CallsScreen(core: PartyMusikCore) {
    val s by core.engine.state.collectAsState()
    val call by core.calls.callState.collectAsState()
    val muted by core.calls.muted.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Party.colors
    var lastError by remember { mutableStateOf<String?>(null) }
    var audioSheet by remember { mutableStateOf(false) }
    val inCall = call is CallState.Active || call is CallState.Dialing || call is CallState.Held || call is CallState.Ringing
    val now by produceState(core.clock.nowMillis(), call) {
        while (call is CallState.Active) {
            value = core.clock.nowMillis()
            delay(1_000)
        }
    }

    ScreenScroll("Calls") {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(if (c.isDark) c.elevated else CallCard)
                .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(64.dp).clip(PartyShape.pill).background(CallButton), contentAlignment = Alignment.Center) {
                Icon(PartyIcons.Waveform, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Echo test", style = PartyType.title2.copy(fontSize = 28.sp, lineHeight = 34.sp), color = Color.White)
            Text(
                when (val st = call) {
                    CallState.Idle -> "Loopback test call"
                    CallState.Dialing -> "Calling…"
                    CallState.Ringing -> "Ringing…"
                    is CallState.Active -> "In call · ${formatMs(now - st.since)}"
                    CallState.Held -> "On hold"
                    is CallState.Ended -> "Call ended · ${st.reason}"
                },
                style = PartyType.subhead, color = CallSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.height(16.dp))
            if (inCall) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    RoundButton(
                        if (muted) PartyIcons.MicOff else PartyIcons.Mic, if (muted) "Muted" else "Mute",
                        { scope.launch { core.calls.setMuted(!muted) } },
                        container = if (muted) Color.White else CallButton,
                        content = if (muted) CallCard else Color.White,
                        labelColor = CallLabel,
                        contentDescription = "Mute",
                        stateDescription = if (muted) "On" else "Off",
                    )
                    val activeName = s.comm.active.firstOrNull()?.let { s.displayName(it) }
                    RoundButton(
                        s.comm.active.firstOrNull()?.let { s.endpoint(it) }?.let { PartyIcons.forEndpoint(it) } ?: PartyIcons.SpeakerWave,
                        activeName?.substringBefore(' ') ?: "Audio",
                        { audioSheet = true },
                        container = Color.White, content = CallCard, labelColor = CallLabel,
                        contentDescription = "Call audio: ${activeName ?: "not reported"}. Change",
                    )
                    RoundButton(
                        PartyIcons.EndCall, "End", { scope.launch { core.calls.hangup() } },
                        container = c.callEnd, content = Color.White, labelColor = CallLabel, contentDescription = "End call",
                    )
                }
            } else {
                RoundButton(
                    PartyIcons.Call, "Start test call",
                    {
                        scope.launch {
                            val r = core.calls.startCall(Peer("Echo test", "loopback://echo"))
                            lastError = (r as? RouteCommandResult.Rejected)?.error?.message
                        }
                    },
                    container = Color(0xFF248A3D), content = Color.White, labelColor = CallLabel,
                )
            }
            lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = PartyType.footnote, color = Color(0xFFFF6961))
            }
        }

        SectionTitle("Call audio")
        RouteStatusText(s.comm.state, s, style = PartyType.footnote)
        CallAudioList(core, s)

        Tile(padding = 14.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                IconTile(PartyIcons.Pause, tint = c.accent, background = c.accentSoft, size = 32.dp, iconSize = 18.dp)
                Text(
                    if (s.media.state is RouteState.Suspended) {
                        "Music is paused for the call and resumes when you hang up."
                    } else {
                        "Music keeps its own output during in-app calls where the phone allows it."
                    },
                    style = PartyType.subhead, color = c.label, modifier = Modifier.weight(1f),
                )
            }
        }

        if (s.commSession == CommSession.CELLULAR) {
            Tile {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(PartyIcons.Call, tint = c.warn, background = c.ground)
                    Column(Modifier.weight(1f)) {
                        Text("Phone call in progress", style = PartyType.headline, color = c.label, modifier = Modifier.semantics { heading() })
                        Text(
                            "Routed by your phone's dialer. The system reports: " +
                                s.comm.active.joinToString { s.displayName(it) }.ifEmpty { "not reported" },
                            style = PartyType.footnote, color = c.secondary,
                        )
                    }
                }
                GrayButton("Open sound settings", { core.ports.systemAudio.openSystemSettings(SettingsTarget.SOUND) }, fill = true)
            }
        }
        DisclosureNote("limit_cellular")
    }

    if (audioSheet) CallAudioSheet(core) { audioSheet = false }
}

@Composable
private fun CallAudioList(core: PartyMusikCore, s: RoutingSnapshot) {
    val c = Party.colors
    val candidates = s.endpoints.filter { it.canCarry(ChannelType.COMMUNICATION) }
    InsetGroup(Modifier.selectableGroup()) {
        candidates.forEach { ep ->
            val glyph = endpointGlyph(s.comm, ep)
            val status = glyphStatus(glyph, "Active")
            GroupRow(
                ep.name,
                modifier = Modifier.selectable(s.comm.requested == listOf(ep.id), role = Role.RadioButton) { core.calls.switchOutput(ep.id) },
                subtitle = status ?: ep.detailLabel(),
                subtitleColor = if (status != null) glyphColor(glyph) else c.secondary,
                subtitleBold = glyph == RouteGlyph.ACTIVE,
                leading = {
                    IconTile(
                        PartyIcons.forEndpoint(ep),
                        tint = if (glyph == RouteGlyph.ACTIVE) c.accent else c.label,
                        background = if (glyph == RouteGlyph.ACTIVE) c.accentSoft else c.ground,
                    )
                },
                trailing = { RouteGlyphIcon(glyph) },
            )
        }
        GroupRow(
            "Let the system choose",
            modifier = Modifier.clickable(role = Role.Button) { core.engine.release(ChannelType.COMMUNICATION) },
            subtitle = "Releases PartyMusik's call output",
            titleColor = c.accent,
            divider = false,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CallAudioSheet(core: PartyMusikCore, onDismiss: () -> Unit) {
    val s by core.engine.state.collectAsState()
    val c = Party.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.ground,
        shape = PartyShape.sheet,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.tertiary) },
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Call audio", style = PartyType.title2, color = c.label, modifier = Modifier.semantics { heading() })
            RouteStatusText(s.comm.state, s, style = PartyType.footnote)
            CallAudioList(core, s)
            Text(
                "Done", style = PartyType.headline, fontWeight = FontWeight.SemiBold, color = c.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally).clickable(role = Role.Button, onClick = onDismiss).padding(12.dp),
            )
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
