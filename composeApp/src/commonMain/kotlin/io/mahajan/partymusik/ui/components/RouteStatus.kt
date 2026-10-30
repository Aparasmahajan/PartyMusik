package io.mahajan.partymusik.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.ChannelSnapshot
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.engine.RoutingSnapshot
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyColors
import io.mahajan.partymusik.ui.theme.PartyType

/** The shapes a route status can take (docs/18 §B.5). Only [ACTIVE] and [ACTIVE_FALLBACK] carry a check. */
enum class RouteGlyph { NONE, SWITCHING, PICKER, PARTIAL, ACTIVE, ACTIVE_FALLBACK, ERROR, PAUSED }

data class RouteLabel(val text: String, val spoken: String, val color: Color, val glyph: RouteGlyph)

/**
 * The single mapping from a channel's [RouteState] to words, colour and glyph.
 * Only `Confirmed` says "Active" — a request is never shown as success (RTE-008).
 */
@Composable
fun routeLabel(state: RouteState, s: RoutingSnapshot): RouteLabel {
    val c = Party.colors
    fun names(ids: List<EndpointId>) = ids.joinToString { s.displayName(it) }.ifEmpty { "—" }
    return when (state) {
        RouteState.Idle -> RouteLabel("Not set", "No route requested", c.secondary, RouteGlyph.NONE)
        is RouteState.Requested -> if (state.needsSystemPicker) {
            RouteLabel(
                "Choose ${names(state.targets)} in the system picker",
                "${names(state.targets)} requested, confirm in the system output picker",
                c.warn, RouteGlyph.PICKER,
            )
        } else {
            RouteLabel(
                "Switching to ${names(state.targets)}…",
                "${names(state.targets)}, switching, waiting for system confirmation",
                c.secondary, RouteGlyph.SWITCHING,
            )
        }
        is RouteState.PartiallyConfirmed -> RouteLabel(
            "Active on ${names(state.confirmed)} · waiting for ${names(state.pending)}",
            "Partly active on ${names(state.confirmed)}, waiting for ${names(state.pending)}",
            c.warn, RouteGlyph.PARTIAL,
        )
        is RouteState.Confirmed -> {
            val fb = state.fallback
            if (fb != null) {
                val lost = fb.lost.joinToString { it.displayName }
                RouteLabel(
                    "Active on ${names(state.active)} · $lost disconnected",
                    "$lost disconnected, playing on ${names(state.active)}",
                    c.warn, RouteGlyph.ACTIVE_FALLBACK,
                )
            } else {
                RouteLabel("Active on ${names(state.active)}", "${names(state.active)}, active", c.accent, RouteGlyph.ACTIVE)
            }
        }
        is RouteState.Rejected -> RouteLabel(
            "${names(state.targets)}: ${state.error.message}",
            "${names(state.targets)}, request rejected: ${state.error.message}",
            c.error, RouteGlyph.ERROR,
        )
        is RouteState.Paused -> RouteLabel(
            "Waiting for ${state.waitingFor.joinToString { it.displayName }}",
            "Paused until ${state.waitingFor.joinToString { it.displayName }} reconnects",
            c.secondary, RouteGlyph.PAUSED,
        )
        is RouteState.Suspended -> RouteLabel(
            "Paused for the call · returns to ${state.resumeTargets.joinToString { it.displayName }.ifEmpty { "—" }}",
            "Media paused during the call",
            c.secondary, RouteGlyph.PAUSED,
        )
    }
}

/**
 * Per-output glyph for a row. `active` comes only from OS observation, so an output the system still
 * reports keeps its check while a switch to another output is pending.
 */
fun endpointGlyph(ch: ChannelSnapshot, ep: AudioEndpoint): RouteGlyph {
    val id = ep.id
    val osActive = id in ch.active
    return when (val st = ch.state) {
        RouteState.Idle -> if (osActive) RouteGlyph.ACTIVE else RouteGlyph.NONE
        is RouteState.Requested -> when {
            id in st.targets -> if (st.needsSystemPicker) RouteGlyph.PICKER else RouteGlyph.SWITCHING
            osActive -> RouteGlyph.ACTIVE
            else -> RouteGlyph.NONE
        }
        is RouteState.PartiallyConfirmed -> when {
            id in st.pending -> RouteGlyph.SWITCHING
            id in st.confirmed || osActive -> RouteGlyph.ACTIVE
            else -> RouteGlyph.NONE
        }
        is RouteState.Confirmed -> when {
            id !in st.active -> RouteGlyph.NONE
            st.fallback != null -> RouteGlyph.ACTIVE_FALLBACK
            else -> RouteGlyph.ACTIVE
        }
        is RouteState.Rejected -> when {
            id in st.targets -> RouteGlyph.ERROR
            osActive -> RouteGlyph.ACTIVE
            else -> RouteGlyph.NONE
        }
        is RouteState.Paused -> if (st.waitingFor.any { it.stableKey == ep.stableKey }) RouteGlyph.PAUSED else RouteGlyph.NONE
        is RouteState.Suspended -> if (st.resumeTargets.any { it.stableKey == ep.stableKey }) RouteGlyph.PAUSED else RouteGlyph.NONE
    }
}

/** Kind of output for a subtitle, without repeating a built-in device's name ("Phone speaker · Phone speaker"). */
fun AudioEndpoint.detailLabel(): String = if (kind.label.equals(name, ignoreCase = true)) "Built in" else kind.label

/** Short row subtitle for a glyph, or null when the row should show its own detail instead. */
fun glyphStatus(glyph: RouteGlyph, activeWord: String = "Playing"): String? = when (glyph) {
    RouteGlyph.NONE -> null
    RouteGlyph.SWITCHING -> "Switching… waiting for the system"
    RouteGlyph.PICKER -> "Confirm it in the system picker"
    RouteGlyph.PARTIAL -> "Partly active"
    RouteGlyph.ACTIVE, RouteGlyph.ACTIVE_FALLBACK -> activeWord
    RouteGlyph.ERROR -> "Didn't switch"
    RouteGlyph.PAUSED -> "Waiting for it to return"
}

@Composable
fun glyphColor(glyph: RouteGlyph): Color {
    val c = Party.colors
    return when (glyph) {
        RouteGlyph.ACTIVE, RouteGlyph.ACTIVE_FALLBACK -> c.accent
        RouteGlyph.PICKER, RouteGlyph.PARTIAL -> c.warn
        RouteGlyph.ERROR -> c.error
        else -> c.secondary
    }
}

/** Route status line for a whole channel; announced politely when it changes. */
@Composable
fun RouteStatusText(state: RouteState, s: RoutingSnapshot, modifier: Modifier = Modifier, style: TextStyle = PartyType.subhead) {
    val label = routeLabel(state, s)
    Text(
        label.text,
        color = label.color,
        style = style,
        fontWeight = FontWeight.Medium,
        modifier = modifier.semantics {
            contentDescription = label.spoken
            liveRegion = LiveRegionMode.Polite
        },
    )
}

/** Draws a [RouteGlyph]. Decorative: the row or text next to it carries the words. */
@Composable
fun RouteGlyphIcon(glyph: RouteGlyph, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val c = Party.colors
    if (glyph == RouteGlyph.SWITCHING) {
        val spin by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))
        Canvas(modifier.size(size)) {
            val u = this.size.width / 24f
            drawCircle(c.separator, 9f * u, style = Stroke(2.4f * u))
            rotate(spin) {
                drawArc(c.secondary, -90f, 90f, false, Offset(3f * u, 3f * u), Size(18f * u, 18f * u), style = Stroke(2.4f * u, cap = StrokeCap.Round))
            }
        }
        return
    }
    Canvas(modifier.size(size)) { drawGlyph(glyph, c) }
}

private fun DrawScope.drawGlyph(glyph: RouteGlyph, c: PartyColors) {
    val u = size.width / 24f
    val center = Offset(12f * u, 12f * u)
    fun check(color: Color) {
        val p = Path().apply {
            moveTo(7.5f * u, 12.4f * u)
            lineTo(10.5f * u, 15.4f * u)
            lineTo(16.5f * u, 9.2f * u)
        }
        drawPath(p, color, style = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    when (glyph) {
        RouteGlyph.NONE -> drawCircle(c.tertiary, 9.5f * u, center, style = Stroke(1.6f * u))
        RouteGlyph.SWITCHING -> Unit
        RouteGlyph.PICKER -> {
            drawCircle(c.warn, 10f * u, center)
            val p = Path().apply {
                moveTo(9f * u, 15f * u); lineTo(15f * u, 9f * u)
                moveTo(10f * u, 9f * u); lineTo(15f * u, 9f * u); lineTo(15f * u, 14f * u)
            }
            drawPath(p, Color.White, style = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        RouteGlyph.PARTIAL -> {
            drawCircle(c.warn, 9.5f * u, center, style = Stroke(2f * u))
            drawArc(c.warn, -90f, 180f, true, Offset(2.5f * u, 2.5f * u), Size(19f * u, 19f * u))
        }
        RouteGlyph.ACTIVE -> {
            drawCircle(c.accent, 10f * u, center)
            check(c.onAccent)
        }
        RouteGlyph.ACTIVE_FALLBACK -> {
            drawCircle(c.accent, 10f * u, center)
            check(c.onAccent)
            drawCircle(c.card, 5.5f * u, Offset(20.5f * u, 3.5f * u))
            drawCircle(c.warn, 4f * u, Offset(20.5f * u, 3.5f * u))
        }
        RouteGlyph.ERROR -> {
            val t = Path().apply {
                moveTo(12f * u, 2.5f * u); lineTo(22f * u, 20.5f * u); lineTo(2f * u, 20.5f * u); close()
            }
            drawPath(t, c.error)
            drawPath(t, c.error, style = Stroke(1.5f * u, join = StrokeJoin.Round))
            drawLine(Color.White, Offset(12f * u, 9.5f * u), Offset(12f * u, 14.5f * u), 2.2f * u, StrokeCap.Round)
            drawLine(Color.White, Offset(12f * u, 17.4f * u), Offset(12f * u, 17.6f * u), 2.2f * u, StrokeCap.Round)
        }
        RouteGlyph.PAUSED -> {
            drawCircle(c.secondary, 9.5f * u, center, style = Stroke(1.8f * u))
            drawRect(c.secondary, Offset(8.8f * u, 8f * u), Size(2.2f * u, 8f * u))
            drawRect(c.secondary, Offset(13f * u, 8f * u), Size(2.2f * u, 8f * u))
        }
    }
}
