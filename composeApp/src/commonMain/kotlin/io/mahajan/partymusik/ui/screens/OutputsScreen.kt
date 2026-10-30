package io.mahajan.partymusik.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.domain.AppPermission
import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.DeviceRef
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.OutputMode
import io.mahajan.partymusik.domain.PermissionStatus
import io.mahajan.partymusik.domain.UnavailableReason
import io.mahajan.partymusik.engine.RoutingSnapshot
import io.mahajan.partymusik.engine.targets
import io.mahajan.partymusik.ports.AudioSharingState
import io.mahajan.partymusik.ports.BtAdapterState
import io.mahajan.partymusik.ui.UiSession
import io.mahajan.partymusik.ui.components.AccentButton
import io.mahajan.partymusik.ui.components.CapsuleSlider
import io.mahajan.partymusik.ui.components.CircleIconButton
import io.mahajan.partymusik.ui.components.Footnote
import io.mahajan.partymusik.ui.components.GrayButton
import io.mahajan.partymusik.ui.components.GroupRow
import io.mahajan.partymusik.ui.components.IconTile
import io.mahajan.partymusik.ui.components.InsetGroup
import io.mahajan.partymusik.ui.components.RouteGlyph
import io.mahajan.partymusik.ui.components.RouteGlyphIcon
import io.mahajan.partymusik.ui.components.RouteStatusText
import io.mahajan.partymusik.ui.components.ScreenScroll
import io.mahajan.partymusik.ui.components.SectionTitle
import io.mahajan.partymusik.ui.components.SegmentedControl
import io.mahajan.partymusik.ui.components.Tile
import io.mahajan.partymusik.ui.components.TintedButton
import io.mahajan.partymusik.ui.components.detailLabel
import io.mahajan.partymusik.ui.components.endpointGlyph
import io.mahajan.partymusik.ui.components.glyphColor
import io.mahajan.partymusik.ui.components.glyphStatus
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyShape
import io.mahajan.partymusik.ui.theme.PartyType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Outputs: every device, what plays where, volume and sync (merges the old Devices and Routing screens). DEV-001..008, MED-002, MED-007, MED-008. */
@Composable
fun OutputsScreen(core: PartyMusikCore, session: UiSession) {
    val s by core.engine.state.collectAsState()
    val inventory by core.registry.inventory.collectAsState()
    val perms by core.ports.permissions.statuses.collectAsState()
    val sharing by core.ports.systemAudio.audioSharing.collectAsState(AudioSharingState.UNAVAILABLE)
    val scope = rememberCoroutineScope()
    val c = Party.colors
    val multiSupported = core.ports.info.supportsMultiOutput
    var together by remember { mutableStateOf(multiSupported && s.media.mode == OutputMode.MULTI) }
    LaunchedEffect(s.media.mode) { together = multiSupported && s.media.mode == OutputMode.MULTI }
    var detail by remember { mutableStateOf<AudioEndpoint?>(null) }

    ScreenScroll(
        "Outputs",
        trailing = { CircleIconButton(PartyIcons.Plus, "Pair new device", { scope.launch { core.ports.bluetooth.launchPairing() } }) },
    ) {
        if (inventory.bluetooth == BtAdapterState.OFF) {
            NoticeTile(PartyIcons.Bluetooth, "Bluetooth is off", "Bluetooth headphones and speakers are hidden until it's on.", "Turn on Bluetooth") {
                scope.launch { core.ports.bluetooth.requestEnable() }
            }
        }
        val nearby = perms[AppPermission.NEARBY_DEVICES]
        if (nearby == PermissionStatus.DENIED || nearby == PermissionStatus.PERMANENTLY_DENIED) {
            NoticeTile(PartyIcons.Info, "See your devices", AppPermission.NEARBY_DEVICES.purpose, "Allow") {
                if (nearby == PermissionStatus.PERMANENTLY_DENIED) {
                    core.ports.permissions.openAppSettings()
                } else {
                    scope.launch { core.ports.permissions.request(AppPermission.NEARBY_DEVICES) }
                }
            }
        }

        if (multiSupported) {
            SegmentedControl(listOf("One", "Together"), if (together) 1 else 0, { together = it == 1 })
        }

        val playingIds = (s.media.active + s.media.state.targets()).distinct()
        val playing = playingIds.mapNotNull { s.endpoint(it) }
        SectionTitle(
            if (together) "Playing together" else "Playing",
            trailing = if (playing.size > 1) "${playing.size} outputs" else null,
        )
        RouteStatusText(s.media.state, s, style = PartyType.footnote)
        if (playing.isEmpty()) {
            Footnote("Nothing is playing to an output yet. Pick one below.")
        } else {
            val report = session.syncReport
            playing.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEach { ep ->
                        val offset = report?.compensationMs?.get(ep.id)?.takeIf { session.syncApplied }
                        OutputTile(
                            ep, s, Modifier.weight(1f),
                            showVolume = s.media.mode == OutputMode.MULTI && ep.id in s.media.active,
                            gain = session.gains[ep.id] ?: 1f,
                            onGain = { v -> session.gains[ep.id] = v; core.ports.multiOutput.setGain(ep.id, v) },
                            offsetMs = offset,
                            onClick = { detail = ep },
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        if (s.media.mode == OutputMode.MULTI && s.media.active.size >= 2) SyncCard(core, session, s)

        val others = s.endpoints.filter { it.id !in playingIds }
        SectionTitle("Other outputs")
        InsetGroup {
            val rows = others.size + inventory.unavailable.size
            var index = 0
            others.forEach { ep ->
                index++
                val media = ep.canCarry(ChannelType.MEDIA)
                val member = Capability.MULTI_OUTPUT_MEMBER in ep.capabilities
                val glyph = endpointGlyph(s.comm, ep)
                GroupRow(
                    ep.name,
                    modifier = Modifier.clickable(role = Role.Button) { detail = ep },
                    subtitle = when {
                        !media -> "Calls only · can't play music"
                        together && !member -> "Can't play together with others"
                        glyph == RouteGlyph.ACTIVE -> "${ep.detailLabel()} · taking calls"
                        else -> ep.detailLabel()
                    },
                    divider = index < rows,
                    leading = { IconTile(PartyIcons.forEndpoint(ep), tint = if (media) c.label else c.secondary) },
                    trailing = {
                        if (media) {
                            if (together) {
                                TintedButton("Add", { core.engine.request(ChannelType.MEDIA, s.media.active + ep.id) }, enabled = member && s.media.active.isNotEmpty())
                            } else {
                                TintedButton("Use", { core.engine.request(ChannelType.MEDIA, listOf(ep.id)) })
                            }
                        }
                    },
                )
            }
            inventory.unavailable.forEach { u ->
                index++
                GroupRow(
                    u.device.name,
                    subtitle = u.reason.explanation,
                    titleColor = c.secondary,
                    divider = index < rows,
                    leading = { IconTile(PartyIcons.Bluetooth, tint = c.secondary) },
                    trailing = if (u.reason == UnavailableReason.NOT_CONNECTED) {
                        {
                            GrayPill("Reconnect") {
                                scope.launch {
                                    core.ports.bluetooth.launchReconnect(DeviceRef(u.device.stableKey, u.device.name, EndpointKind.OTHER))
                                }
                            }
                        }
                    } else null,
                )
            }
            if (rows == 0) GroupRow("No other outputs", divider = false)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GrayButton("Pair new device", { scope.launch { core.ports.bluetooth.launchPairing() } }, Modifier.weight(1f), icon = PartyIcons.Plus)
            GrayButton("System picker", { core.ports.systemAudio.showSystemOutputPicker() }, Modifier.weight(1f), icon = PartyIcons.Route)
        }
        if (sharing != AudioSharingState.UNAVAILABLE) {
            Footnote(
                "System audio sharing (LE Audio broadcast / Share Audio) is ${sharing.name.lowercase()}. " +
                    "The system manages it, separately from PartyMusik.",
            )
        }
    }

    detail?.let { ep -> DeviceDetailSheet(core, ep.id, onDismiss = { detail = null }) }
}

@Composable
private fun OutputTile(
    ep: AudioEndpoint,
    s: RoutingSnapshot,
    modifier: Modifier,
    showVolume: Boolean,
    gain: Float,
    onGain: (Float) -> Unit,
    offsetMs: Int?,
    onClick: () -> Unit,
) {
    val c = Party.colors
    val glyph = endpointGlyph(s.media, ep)
    val status = glyphStatus(glyph) ?: "Not playing"
    Tile(modifier, padding = 14.dp) {
        Column(
            Modifier.clickable(role = Role.Button, onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                IconTile(PartyIcons.forEndpoint(ep), tint = c.accent, background = c.accentSoft, size = 42.dp, iconSize = 24.dp)
                Spacer(Modifier.weight(1f))
                RouteGlyphIcon(glyph, size = 22.dp)
            }
            Column {
                Text(ep.name, style = PartyType.subhead, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 2)
                Text(
                    status + (offsetMs?.let { " · +$it ms" } ?: ""),
                    style = PartyType.footnote, fontWeight = FontWeight.SemiBold, color = glyphColor(glyph),
                )
            }
        }
        if (showVolume) CapsuleSlider(gain, onGain, "Volume for ${ep.name}")
    }
}

/** Measures each output with the mic and lines them up (MED-007). Audio is analysed in memory only. */
@Composable
private fun SyncCard(core: PartyMusikCore, session: UiSession, s: RoutingSnapshot) {
    val c = Party.colors
    val scope = rememberCoroutineScope()
    var measuring by remember { mutableStateOf(false) }
    val report = session.syncReport
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sync", style = PartyType.headline, color = c.label, modifier = Modifier.weight(1f).semantics { heading() })
            if (report != null && session.syncApplied) {
                Row(
                    Modifier.clip(PartyShape.pill).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(PartyIcons.Check, null, tint = c.accent, modifier = Modifier.width(14.dp).height(14.dp))
                    Text("Aligned", style = PartyType.footnote, fontWeight = FontWeight.SemiBold, color = c.accent)
                }
            }
        }
        if (report != null) {
            val slowest = report.outputs.maxOfOrNull { it.medianMs }?.coerceAtLeast(1.0) ?: 1.0
            report.outputs.forEach { o ->
                val comp = report.compensationMs[o.endpoint] ?: 0
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "${s.displayName(o.endpoint)}: ${o.medianMs.roundToInt()} milliseconds, add $comp"
                    },
                ) {
                    Text(s.displayName(o.endpoint), style = PartyType.footnote, color = c.label, maxLines = 1, modifier = Modifier.width(96.dp))
                    Row(Modifier.weight(1f).height(10.dp).clip(PartyShape.pill).background(c.ground)) {
                        Box(Modifier.fillMaxHeight().weight((o.medianMs / slowest).toFloat().coerceAtLeast(0.01f)).background(c.accent))
                        if (comp > 0) Box(Modifier.fillMaxHeight().weight((comp / slowest).toFloat()).background(c.accent.copy(alpha = 0.35f)))
                        val rest = 1f - ((o.medianMs + comp) / slowest).toFloat()
                        if (rest > 0.001f) Spacer(Modifier.weight(rest))
                    }
                    Text(
                        "${o.medianMs.roundToInt()}${if (comp > 0) "+$comp" else ""} ms",
                        style = PartyType.footnote, color = c.secondary, modifier = Modifier.width(72.dp),
                    )
                }
            }
            if (report.failed.isNotEmpty()) Footnote("No measurement for ${report.failed.joinToString { s.displayName(it) }}")
            Footnote("Skew ${report.skewMs.roundToInt()} ms before aligning (±${report.outputs.maxOfOrNull { it.iqrMs / 2 }?.roundToInt() ?: 0} ms).")
        } else {
            Footnote("Plays a short chirp on each output and listens with the mic. The audio stays in memory and is never stored.")
        }
        AccentButton(
            when {
                measuring -> "Measuring…"
                report == null -> "Align outputs"
                else -> "Align again"
            },
            {
                measuring = true
                scope.launch {
                    val r = core.sync.measure(s.media.active)
                    session.syncReport = r
                    if (r.outputs.size >= 2) core.sync.applyCompensation(r)
                    session.syncApplied = r.outputs.size >= 2
                    measuring = false
                }
            },
            enabled = !measuring,
        )
        Footnote("Close, not guaranteed: Bluetooth delay can drift.")
    }
}

/** Device details and direct actions (DEV-002, DEV-007). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DeviceDetailSheet(core: PartyMusikCore, id: io.mahajan.partymusik.domain.EndpointId, onDismiss: () -> Unit) {
    val s by core.engine.state.collectAsState()
    val c = Party.colors
    val ep = s.endpoint(id)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.ground,
        shape = PartyShape.sheet,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.tertiary) },
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ep == null) {
                Text("This device disconnected.", style = PartyType.body, color = c.secondary)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(PartyIcons.forEndpoint(ep), tint = c.accent, background = c.accentSoft, size = 52.dp, iconSize = 28.dp)
                    Column(Modifier.weight(1f)) {
                        Text(ep.name, style = PartyType.title2, color = c.label, modifier = Modifier.semantics { heading() })
                        Text(ep.kind.label, style = PartyType.subhead, color = c.secondary)
                    }
                }
                InsetGroup {
                    val media = endpointGlyph(s.media, ep)
                    val comm = endpointGlyph(s.comm, ep)
                    GroupRow(
                        "Music", subtitle = glyphStatus(media) ?: if (ep.canCarry(ChannelType.MEDIA)) "Not playing here" else "Can't play music",
                        subtitleColor = glyphColor(media), trailing = { RouteGlyphIcon(media) },
                    )
                    GroupRow(
                        "Calls", subtitle = glyphStatus(comm, "Taking calls") ?: if (ep.canCarry(ChannelType.COMMUNICATION)) "Not in use" else "Can't carry calls",
                        subtitleColor = glyphColor(comm), trailing = { RouteGlyphIcon(comm) }, divider = false,
                    )
                }
                Text("Can do", style = PartyType.footnote, fontWeight = FontWeight.SemiBold, color = c.secondary, modifier = Modifier.padding(horizontal = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ep.capabilities.sortedBy { it.ordinal }.forEach { cap ->
                        Text(
                            cap.label, style = PartyType.footnote, fontWeight = FontWeight.Medium, color = c.label,
                            modifier = Modifier.clip(PartyShape.pill).background(c.card).padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                if (ep.sampleRates.isNotEmpty() || ep.channelCounts.isNotEmpty()) {
                    Footnote(
                        listOfNotNull(
                            ep.sampleRates.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Sample rates: ", postfix = " Hz"),
                            ep.channelCounts.takeIf { it.isNotEmpty() }?.joinToString(prefix = "Channels: "),
                        ).joinToString(" · "),
                    )
                }
                if (ep.canCarry(ChannelType.MEDIA)) AccentButton("Play music here", { core.engine.request(ChannelType.MEDIA, listOf(ep.id)); onDismiss() })
                if (ep.canCarry(ChannelType.COMMUNICATION)) {
                    GrayButton("Use for calls", { core.engine.request(ChannelType.COMMUNICATION, listOf(ep.id)); onDismiss() }, icon = PartyIcons.Call, fill = true)
                }
            }
            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
private fun NoticeTile(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, action: String, onAction: () -> Unit) {
    val c = Party.colors
    Tile {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon, tint = c.accent, background = c.accentSoft)
            Column(Modifier.weight(1f)) {
                Text(title, style = PartyType.headline, color = c.label)
                Text(text, style = PartyType.footnote, color = c.secondary)
            }
        }
        AccentButton(action, onAction)
    }
}

@Composable
private fun GrayPill(text: String, onClick: () -> Unit) {
    val c = Party.colors
    Box(
        Modifier.clip(PartyShape.pill).background(c.fill).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = PartyType.subhead, fontWeight = FontWeight.SemiBold, color = c.label)
    }
}
