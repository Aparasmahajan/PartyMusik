package io.mahajan.partymusik.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.diagnostics.CompatOutcome
import io.mahajan.partymusik.diagnostics.Troubleshooting
import io.mahajan.partymusik.domain.AppPermission
import io.mahajan.partymusik.domain.ChannelSnapshot
import io.mahajan.partymusik.domain.PermissionStatus
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.engine.RoutingSnapshot
import io.mahajan.partymusik.ui.UiSession
import io.mahajan.partymusik.ui.components.Footnote
import io.mahajan.partymusik.ui.components.GroupRow
import io.mahajan.partymusik.ui.components.HelpRow
import io.mahajan.partymusik.ui.components.InsetGroup
import io.mahajan.partymusik.ui.components.PartySwitch
import io.mahajan.partymusik.ui.components.RouteGlyph
import io.mahajan.partymusik.ui.components.RouteGlyphIcon
import io.mahajan.partymusik.ui.components.ScreenScroll
import io.mahajan.partymusik.ui.components.SectionTitle
import io.mahajan.partymusik.ui.components.TextAction
import io.mahajan.partymusik.ui.components.Tile
import io.mahajan.partymusik.ui.components.TintedButton
import io.mahajan.partymusik.ui.components.routeLabel
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyType
import kotlinx.coroutines.launch

/** Lab: is everything working? Health, compatibility, permissions, help; developer tools folded away. DIA-001..007. */
@Composable
fun LabScreen(core: PartyMusikCore, session: UiSession) {
    val s by core.engine.state.collectAsState()
    val perms by core.ports.permissions.statuses.collectAsState()
    val events by core.diagnostics.events.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Party.colors
    var developerOpen by remember { mutableStateOf(false) }

    ScreenScroll("Lab", subtitle = "Is everything working?") {
        HealthCard(s)

        val report = session.compatReport
        val results = report?.results ?: session.compatProgress.toList()
        Tile {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val passed = results.count { it.outcome == CompatOutcome.PASS }
                val checks = results.count { it.outcome != CompatOutcome.INFO }
                ProgressRing(
                    if (session.compatRunning || checks == 0) null else passed.toFloat() / checks,
                    if (checks == 0) "—" else "$passed/$checks",
                )
                Column(Modifier.weight(1f)) {
                    Text("Compatibility test", style = PartyType.headline, color = c.label, modifier = Modifier.semantics { heading() })
                    Text(
                        when {
                            session.compatRunning -> "Running… ${results.size} checks so far"
                            report != null -> "${report.passed} passed · ${report.failed} failed · " +
                                "${report.results.count { it.outcome == CompatOutcome.SKIPPED }} skipped"
                            else -> "Tries every output for music, calls and together, and records what the system confirmed."
                        },
                        style = PartyType.footnote, color = c.secondary,
                    )
                }
                TintedButton(if (session.compatRunning) "Running" else "Run", {
                    session.compatRunning = true
                    session.compatProgress.clear()
                    session.compatReport = null
                    scope.launch {
                        session.compatReport = core.compatibility.run { r -> session.compatProgress.add(r) }
                        session.compatRunning = false
                    }
                }, enabled = !session.compatRunning)
            }
            results.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    when (r.outcome) {
                        CompatOutcome.PASS -> RouteGlyphIcon(RouteGlyph.ACTIVE, size = 18.dp)
                        CompatOutcome.FAIL -> RouteGlyphIcon(RouteGlyph.ERROR, size = 18.dp)
                        CompatOutcome.SKIPPED -> RouteGlyphIcon(RouteGlyph.NONE, size = 18.dp)
                        CompatOutcome.INFO -> Icon(PartyIcons.Info, null, tint = c.secondary, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text("${r.testId} · ${r.subject}", style = PartyType.subhead, color = if (r.outcome == CompatOutcome.FAIL) c.error else c.label)
                        if (r.detail.isNotEmpty()) Text(r.detail, style = PartyType.footnote, color = c.secondary)
                    }
                    r.latencyMs?.let { Text("$it ms", style = PartyType.footnote, color = c.secondary) }
                }
            }
        }

        SectionTitle("Permissions")
        InsetGroup {
            AppPermission.entries.forEachIndexed { i, p ->
                val status = perms[p] ?: PermissionStatus.NOT_REQUIRED
                GroupRow(
                    p.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                    subtitle = p.purpose,
                    divider = i < AppPermission.entries.lastIndex,
                    trailing = {
                        when (status) {
                            PermissionStatus.GRANTED -> Text("Allowed", style = PartyType.subhead, color = c.secondary)
                            PermissionStatus.NOT_REQUIRED -> Text("Not needed", style = PartyType.subhead, color = c.secondary)
                            PermissionStatus.DENIED -> TintedButton("Allow", { scope.launch { core.ports.permissions.request(p) } })
                            PermissionStatus.PERMANENTLY_DENIED -> TintedButton("Settings", { core.ports.permissions.openAppSettings() })
                        }
                    },
                )
            }
        }

        SectionTitle("Help & limitations")
        InsetGroup {
            val seenCodes = events.map { it.code }.filter { it.startsWith("RTE_") || it == "BT_OFF" }.distinct()
            val articles = Troubleshooting.disclosures + seenCodes.map { Troubleshooting.articleFor(it) }
            articles.forEachIndexed { i, a -> HelpRow(a, divider = i < articles.lastIndex) }
        }

        InsetGroup(Modifier.padding(top = 8.dp)) {
            GroupRow(
                "Developer",
                modifier = Modifier.clickable(role = Role.Button) { developerOpen = !developerOpen }
                    .semantics { stateDescription = if (developerOpen) "Expanded" else "Collapsed" },
                subtitle = "Spike measurements and the event log",
                divider = false,
                trailing = {
                    Icon(PartyIcons.Chevron, null, tint = c.tertiary, modifier = Modifier.size(14.dp).rotate(if (developerOpen) 90f else 0f))
                },
            )
        }
        if (developerOpen) DeveloperSection(core)
    }
}

/** Requested · Available · Active for each channel, with an overall verdict (DIA-001). */
@Composable
private fun HealthCard(s: RoutingSnapshot) {
    val c = Party.colors
    fun troubled(ch: ChannelSnapshot) = when (val st = ch.state) {
        is RouteState.Rejected, is RouteState.Paused, is RouteState.PartiallyConfirmed -> true
        is RouteState.Requested -> st.needsSystemPicker
        is RouteState.Confirmed -> st.fallback != null
        else -> false
    }
    val issues = listOf(s.media, s.comm).count(::troubled)
    val pending = listOf(s.media, s.comm).any { it.state is RouteState.Requested && !troubled(it) }
    Tile {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RouteGlyphIcon(
                when {
                    issues > 0 -> RouteGlyph.ERROR
                    pending -> RouteGlyph.SWITCHING
                    else -> RouteGlyph.ACTIVE
                },
                size = 44.dp,
            )
            Column {
                Text(
                    when {
                        issues == 1 -> "1 route needs attention"
                        issues > 1 -> "$issues routes need attention"
                        pending -> "Waiting for the system…"
                        else -> "All routes confirmed"
                    },
                    style = PartyType.headline, color = c.label, modifier = Modifier.semantics { heading() },
                )
                Text("Active means the system reported it — a request alone never counts.", style = PartyType.footnote, color = c.secondary)
            }
        }
        Pipeline("Music", s.media, s)
        Pipeline("Calls", s.comm, s)
    }
}

@Composable
private fun Pipeline(title: String, ch: ChannelSnapshot, s: RoutingSnapshot) {
    val c = Party.colors
    fun names(ids: List<io.mahajan.partymusik.domain.EndpointId>) = ids.joinToString { s.displayName(it) }.ifEmpty { "—" }
    val label = routeLabel(ch.state, s)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = PartyType.footnote, fontWeight = FontWeight.SemiBold, color = c.label, modifier = Modifier.weight(1f))
            Text("${ch.mode.name.lowercase()} · gen ${ch.generation}", style = PartyType.caption, color = c.secondary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Step("Requested", names(ch.requested), Modifier.weight(1f))
            Icon(PartyIcons.Chevron, null, tint = c.tertiary, modifier = Modifier.size(12.dp))
            Step("Available", "${ch.available.size} outputs", Modifier.weight(1f))
            Icon(PartyIcons.Chevron, null, tint = c.tertiary, modifier = Modifier.size(12.dp))
            Step("Active · OS", names(ch.active), Modifier.weight(1f), highlighted = ch.active.isNotEmpty())
        }
        if (label.glyph != RouteGlyph.ACTIVE) Text(label.text, style = PartyType.footnote, color = label.color)
    }
}

@Composable
private fun Step(label: String, value: String, modifier: Modifier, highlighted: Boolean = false) {
    val c = Party.colors
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(if (highlighted) c.accentSoft else c.ground).padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(label, style = PartyType.caption, color = if (highlighted) c.accent else c.secondary)
        Text(value, style = PartyType.footnote, fontWeight = FontWeight.SemiBold, color = c.label, maxLines = 2)
    }
}

/** Ring for the compatibility test; spins while [fraction] is null. */
@Composable
private fun ProgressRing(fraction: Float?, text: String) {
    val c = Party.colors
    Box(Modifier.size(54.dp).semantics { contentDescription = if (fraction == null) "Running" else "$text passed" }, contentAlignment = Alignment.Center) {
        if (fraction == null) {
            RouteGlyphIcon(RouteGlyph.SWITCHING, size = 54.dp)
        } else {
            Canvas(Modifier.size(54.dp)) {
                val w = 7.dp.toPx()
                drawCircle(c.fill, radius = size.width / 2 - w / 2, style = Stroke(w))
                drawArc(c.accent, -90f, 360f * fraction, false, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
            }
            Text(text, style = PartyType.caption, fontWeight = FontWeight.Bold, color = c.label)
        }
    }
}

/** Spike measurements (docs/16) and the event log, for the device lab. */
@Composable
private fun DeveloperSection(core: PartyMusikCore) {
    val c = Party.colors
    val events by core.diagnostics.events.collectAsState()
    val syncReports by core.spikeData.syncReports.collectAsState()
    val compatReports by core.spikeData.compatReports.collectAsState()
    val spikeSummary = remember(events, syncReports, compatReports) { core.spikeData.summary() }
    var anonymize by remember { mutableStateOf(true) }
    var export by remember { mutableStateOf<String?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }

    Tile {
        Text("Spike measurements", style = PartyType.headline, color = c.label, modifier = Modifier.semantics { heading() })
        Footnote("Figures for the device-lab spikes (docs/16). Reset before each run, then save the CSV files.")
        spikeSummary.forEach { m ->
            val numbers = if (m.p50 != null) " · p50 ${stat(m.p50)} · p95 ${stat(m.p95)} · max ${stat(m.max)}" else ""
            Text(
                "${m.spike} · ${m.metric}: n=${m.n}$numbers" + (if (m.note.isNotEmpty()) " · ${m.note}" else ""),
                style = PartyType.footnote, color = c.label,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (core.canExportFiles) TintedButton("Save CSV files", { savedTo = core.exportSpikeData(anonymize) })
            TextAction("Reset run", { core.spikeData.clear(); savedTo = null })
        }
        savedTo?.let { SelectionContainer { Footnote("Saved to $it") } }
    }

    Tile {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Event log (${events.size})", style = PartyType.headline, color = c.label, modifier = Modifier.weight(1f).semantics { heading() })
            TextAction("Export", {
                export = core.diagnostics.export(
                    anonymize,
                    mapOf("platform" to "${core.ports.info.os} ${core.ports.info.osVersion}", "model" to core.ports.info.model),
                )
            })
            TextAction("Clear", { core.diagnostics.clear() })
        }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(anonymize, role = Role.Switch) { anonymize = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Hide device names in exports", style = PartyType.subhead, color = c.label, modifier = Modifier.weight(1f))
            PartySwitch(anonymize, null)
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)).background(c.ground)
                .verticalScroll(rememberScrollState()).padding(10.dp),
        ) {
            events.takeLast(150).asReversed().forEach { e ->
                Text(
                    "${e.seq} ${e.channel?.name?.take(5) ?: "-"} ${e.code} " + e.detail.entries.joinToString(" ") { "${it.key}=${it.value}" },
                    style = PartyType.caption, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal, color = c.label,
                )
            }
        }
    }

    export?.let { text ->
        AlertDialog(
            onDismissRequest = { export = null },
            confirmButton = { TextButton(onClick = { export = null }) { Text("Close") } },
            title = { Text("Diagnostics export") },
            text = {
                SelectionContainer {
                    Text(
                        text, style = PartyType.caption, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
        )
    }
}

private fun stat(value: Double?): String = when (value) {
    null -> "—"
    else -> "${kotlin.math.round(value).toLong()} ms"
}
