package io.mahajan.partymusik.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.OutputMode
import io.mahajan.partymusik.ui.Tab
import io.mahajan.partymusik.ui.UiSession
import io.mahajan.partymusik.ui.components.AccentButton
import io.mahajan.partymusik.ui.components.CircleIconButton
import io.mahajan.partymusik.ui.components.DisclosureNote
import io.mahajan.partymusik.ui.components.Footnote
import io.mahajan.partymusik.ui.components.GrayButton
import io.mahajan.partymusik.ui.components.GroupRow
import io.mahajan.partymusik.ui.components.IconTile
import io.mahajan.partymusik.ui.components.InsetGroup
import io.mahajan.partymusik.ui.components.RouteGlyph
import io.mahajan.partymusik.ui.components.RouteGlyphIcon
import io.mahajan.partymusik.ui.components.RouteStatusText
import io.mahajan.partymusik.ui.components.SegmentedControl
import io.mahajan.partymusik.ui.components.SelectBox
import io.mahajan.partymusik.ui.components.TextAction
import io.mahajan.partymusik.ui.components.detailLabel
import io.mahajan.partymusik.ui.components.endpointGlyph
import io.mahajan.partymusik.ui.components.glyphColor
import io.mahajan.partymusik.ui.components.glyphStatus
import io.mahajan.partymusik.ui.open
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyShape
import io.mahajan.partymusik.ui.theme.PartyType

/** "Play on" sheet (AirPlay-picker pattern). DEV-005, RTE-001..002, MED-002, EXT-002. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutputSheet(core: PartyMusikCore, session: UiSession, onDismiss: () -> Unit) {
    val c = Party.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.ground,
        shape = PartyShape.sheet,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.tertiary) },
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Play on", style = PartyType.title2, color = c.label, modifier = Modifier.weight(1f).semantics { heading() })
                CircleIconButton(PartyIcons.Close, "Close", onDismiss, tint = c.secondary, background = c.fill, size = 32.dp, iconSize = 14.dp)
            }
            MediaOutputPicker(core, onManage = { session.open(Tab.OUTPUTS) })
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun MediaOutputPicker(core: PartyMusikCore, onManage: () -> Unit) {
    val s by core.engine.state.collectAsState()
    val inventory by core.registry.inventory.collectAsState()
    val c = Party.colors
    val multiSupported = core.ports.info.supportsMultiOutput
    var together by remember { mutableStateOf(multiSupported && s.media.mode == OutputMode.MULTI) }
    var selection by remember { mutableStateOf(s.media.active.ifEmpty { s.media.requested }.toSet()) }
    val candidates = s.endpoints.filter { it.canCarry(ChannelType.MEDIA) }

    RouteStatusText(s.media.state, s)
    if (multiSupported) {
        SegmentedControl(listOf("One", "Together"), if (together) 1 else 0, { i ->
            together = i == 1
            if (together) selection = s.media.active.ifEmpty { s.media.requested }.toSet()
        })
    }

    InsetGroup {
        candidates.forEachIndexed { i, ep ->
            val glyph = endpointGlyph(s.media, ep)
            val status = glyphStatus(glyph)
            val member = Capability.MULTI_OUTPUT_MEMBER in ep.capabilities
            val detail = if (together && !member) "Can't play together with others" else ep.detailLabel()
            val rowModifier = if (together) {
                Modifier.toggleable(ep.id in selection, enabled = member, role = Role.Checkbox) { on ->
                    selection = if (on) selection + ep.id else selection - ep.id
                }
            } else {
                Modifier.selectable(glyph == RouteGlyph.ACTIVE || glyph == RouteGlyph.ACTIVE_FALLBACK, role = Role.RadioButton) {
                    core.engine.request(ChannelType.MEDIA, listOf(ep.id))
                }
            }
            GroupRow(
                ep.name,
                modifier = rowModifier,
                subtitle = status?.let { if (glyph == RouteGlyph.ACTIVE || glyph == RouteGlyph.ACTIVE_FALLBACK) "$it · $detail" else it } ?: detail,
                subtitleColor = if (status != null) glyphColor(glyph) else c.secondary,
                subtitleBold = glyph == RouteGlyph.ACTIVE || glyph == RouteGlyph.ACTIVE_FALLBACK,
                titleColor = if (together && !member) c.secondary else c.label,
                divider = i < candidates.lastIndex,
                leading = { IconTile(PartyIcons.forEndpoint(ep)) },
                trailing = {
                    if (together) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (glyph == RouteGlyph.SWITCHING) RouteGlyphIcon(glyph, size = 20.dp)
                            SelectBox(ep.id in selection, enabled = member)
                        }
                    } else {
                        RouteGlyphIcon(glyph)
                    }
                },
            )
        }
        if (candidates.isEmpty()) GroupRow("No outputs available", subtitle = "Connect a device, or check Bluetooth", divider = false)
    }

    if (together) {
        val chosen = candidates.filter { it.id in selection }.map { it.id }
        AccentButton(
            if (chosen.size < 2) "Pick at least 2 outputs" else "Play on ${chosen.size} outputs",
            { core.engine.request(ChannelType.MEDIA, chosen) },
            enabled = chosen.size >= 2,
        )
        Footnote("Bluetooth outputs drift apart a little. PartyMusik measures and compensates, but perfect sync isn't guaranteed.")
    }

    val callsOnly = s.endpoints.filter { !it.canCarry(ChannelType.MEDIA) && it.canCarry(ChannelType.COMMUNICATION) }
    if (callsOnly.isNotEmpty()) Footnote("Calls only, can't play music: ${callsOnly.joinToString { it.name }}")
    if (inventory.unavailable.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Footnote(
                "${inventory.unavailable.joinToString { it.device.name }} ${if (inventory.unavailable.size == 1) "isn't" else "aren't"} available.",
                Modifier.weight(1f),
            )
            TextAction("Manage outputs", onManage)
        }
    }
    GrayButton("System output picker · for other apps", { core.ports.systemAudio.showSystemOutputPicker() }, icon = PartyIcons.Route, fill = true)
    HelpForRejected(s.media)
    DisclosureNote("limit_third_party")
}
