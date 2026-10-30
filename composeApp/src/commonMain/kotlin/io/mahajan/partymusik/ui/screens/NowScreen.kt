package io.mahajan.partymusik.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.diagnostics.Troubleshooting
import io.mahajan.partymusik.domain.ChannelSnapshot
import io.mahajan.partymusik.domain.CommSession
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.engine.EngineEvent
import io.mahajan.partymusik.engine.RoutingSnapshot
import io.mahajan.partymusik.engine.targets
import io.mahajan.partymusik.ui.Tab
import io.mahajan.partymusik.ui.UiSession
import io.mahajan.partymusik.ui.components.Artwork
import io.mahajan.partymusik.ui.components.CircleIconButton
import io.mahajan.partymusik.ui.components.GroupRow
import io.mahajan.partymusik.ui.components.IconTile
import io.mahajan.partymusik.ui.components.InsetGroup
import io.mahajan.partymusik.ui.components.PillSwitcher
import io.mahajan.partymusik.ui.components.RouteGlyph
import io.mahajan.partymusik.ui.components.RouteGlyphIcon
import io.mahajan.partymusik.ui.components.ScreenScroll
import io.mahajan.partymusik.ui.components.ScrubBar
import io.mahajan.partymusik.ui.components.SectionTitle
import io.mahajan.partymusik.ui.components.TextAction
import io.mahajan.partymusik.ui.components.Tile
import io.mahajan.partymusik.ui.components.formatMs
import io.mahajan.partymusik.ui.components.routeLabel
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyShape
import io.mahajan.partymusik.ui.theme.PartyType
import kotlinx.coroutines.launch

/** Now: what is playing and where (merges the old Home and Player screens). MED-001..004, CFG-005, CFG-008. */
@Composable
fun NowScreen(core: PartyMusikCore, session: UiSession) {
    val s by core.engine.state.collectAsState()
    val profiles by core.profiles.profiles.collectAsState()
    val playback by core.ports.playback.status.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Party.colors
    val item = playback.item

    ScreenScroll(
        "Now",
        trailing = { CircleIconButton(PartyIcons.Route, "Choose where audio plays", { session.outputSheetOpen = true }) },
    ) {
        PillSwitcher(
            profiles.map { it.name },
            profiles.indexOfFirst { it.id == s.activeProfileId },
            { i -> scope.launch { core.profiles.apply(profiles[i].id, RequestSource.USER) } },
        )
        OverrideNotice(core, s)

        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            Artwork(item?.id, item?.title ?: "nothing loaded", 240.dp)
        }

        Column {
            Text(item?.title ?: "Nothing loaded", style = PartyType.title2, color = c.label)
            Text(if (item != null) "Library" else "Pick something from the library below", style = PartyType.subhead, color = c.secondary)
        }

        val duration = playback.durationMs.coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ScrubBar(
                playback.positionMs.toFloat() / duration,
                { f -> core.ports.playback.seekTo((f * duration).toLong()) },
                "${formatMs(playback.positionMs)} of ${formatMs(playback.durationMs)}",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMs(playback.positionMs), style = PartyType.caption, color = c.secondary)
                Text("−" + formatMs(playback.durationMs - playback.positionMs), style = PartyType.caption, color = c.secondary)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            SkipButton(PartyIcons.SkipBack, "Back 10 seconds") { core.ports.playback.seekTo(playback.positionMs - 10_000) }
            Box(
                Modifier.size(68.dp).shadow(10.dp, CircleShape, spotColor = c.accent).clip(CircleShape).background(c.accent)
                    .clickable(role = Role.Button) { if (playback.playing) core.ports.playback.pause() else core.ports.playback.play() }
                    .semantics { contentDescription = if (playback.playing) "Pause" else "Play" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (playback.playing) PartyIcons.Pause else PartyIcons.Play, null, tint = c.onAccent, modifier = Modifier.size(30.dp))
            }
            SkipButton(PartyIcons.SkipForward, "Forward 10 seconds") { core.ports.playback.seekTo(playback.positionMs + 10_000) }
        }
        playback.error?.let { Text(it, style = PartyType.footnote, color = c.error) }

        RouteCard(
            caption = "Playing on",
            ch = s.media, s = s,
            icon = (s.media.active + s.media.state.targets() + s.media.requested).firstOrNull()
                ?.let { s.endpoint(it) }?.let { PartyIcons.forEndpoint(it) } ?: PartyIcons.Speaker,
            tint = c.accent, tileColor = c.accentSoft,
            onClick = { session.outputSheetOpen = true },
        )
        HelpForRejected(s.media)

        RouteCard(
            caption = when (s.commSession) {
                CommSession.VOIP -> "In-app call on"
                CommSession.CELLULAR -> "Phone call on · system controlled"
                null -> "Calls go to"
            },
            ch = s.comm, s = s,
            icon = PartyIcons.Call, tint = c.callGo, tileColor = c.callGoSoft,
            onClick = { session.tab = Tab.CALLS },
        )

        if (core.ports.playback.library.isNotEmpty()) {
            SectionTitle("Library")
            InsetGroup {
                val library = core.ports.playback.library
                library.forEachIndexed { i, li ->
                    val current = item?.id == li.id
                    GroupRow(
                        li.title,
                        modifier = Modifier.selectable(current, role = Role.Button) {
                            scope.launch { core.ports.playback.load(li); core.ports.playback.play() }
                        },
                        subtitle = formatMs(li.durationMs),
                        divider = i < library.lastIndex,
                        titleColor = if (current) c.accent else c.label,
                        leading = { IconTile(PartyIcons.Note, tint = if (current) c.accent else c.secondary, background = if (current) c.accentSoft else c.ground) },
                        trailing = if (current && playback.playing) {
                            { Icon(PartyIcons.Waveform, "Playing", tint = c.accent, modifier = Modifier.size(20.dp)) }
                        } else null,
                    )
                }
            }
        }
    }
}

/** Shows that a manual choice is holding automations off, with a way to undo it (CFG-008). */
@Composable
private fun OverrideNotice(core: PartyMusikCore, s: RoutingSnapshot) {
    val override = s.override ?: return
    val now = core.clock.nowMillis()
    if (!override.isActive(now)) return
    val c = Party.colors
    val left = override.until?.let { (it - now) / 60_000 }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(PartyIcons.Clock, null, tint = c.secondary, modifier = Modifier.size(14.dp))
        Text(
            "You chose this · automations paused" + (left?.let { " for ${if (it >= 60) "${it / 60} h ${it % 60} m" else "$it m"}" } ?: ""),
            style = PartyType.footnote, color = c.secondary, modifier = Modifier.weight(1f),
        )
        TextAction("Resume", { core.engine.dispatch(EngineEvent.ClearOverride) })
    }
}

@Composable
private fun SkipButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val c = Party.colors
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = c.label, modifier = Modifier.size(32.dp))
        Text("10", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = c.label, modifier = Modifier.offset(y = 1.dp))
    }
}

/** A channel's route as one tappable row: device names, status words when not plainly active, glyph. */
@Composable
internal fun RouteCard(
    caption: String,
    ch: ChannelSnapshot,
    s: RoutingSnapshot,
    icon: ImageVector,
    tint: Color,
    tileColor: Color,
    onClick: () -> Unit,
) {
    val c = Party.colors
    val label = routeLabel(ch.state, s)
    val shown = ch.active.ifEmpty { ch.state.targets() }.ifEmpty { ch.requested }
    val names = shown.joinToString(" + ") { s.displayName(it) }.ifEmpty { "Not set · tap to choose" }
    Row(
        Modifier.fillMaxWidth().clip(PartyShape.group).background(c.card)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = "$caption. ${label.spoken}. Change"
                role = Role.Button
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon, tint = tint, background = tileColor)
        Column(Modifier.weight(1f)) {
            Text(caption, style = PartyType.caption, color = c.secondary)
            Text(names, style = PartyType.headline, color = c.label)
            if (label.glyph != RouteGlyph.ACTIVE && ch.state != RouteState.Idle) {
                Text(label.text, style = PartyType.footnote, fontWeight = FontWeight.Medium, color = label.color)
            }
        }
        RouteGlyphIcon(label.glyph)
        Icon(PartyIcons.Chevron, null, tint = c.tertiary, modifier = Modifier.size(14.dp))
    }
}

/** What to do about a rejected route (DIA-007). */
@Composable
internal fun HelpForRejected(ch: ChannelSnapshot) {
    val st = ch.state as? RouteState.Rejected ?: return
    val help = Troubleshooting.articleFor(st.error.code)
    val c = Party.colors
    Tile {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(PartyIcons.Warning, null, tint = c.error, modifier = Modifier.size(20.dp))
            Text(help.title, style = PartyType.headline, color = c.label)
        }
        help.steps.forEach { Text("• $it", style = PartyType.subhead, color = c.secondary) }
    }
}
