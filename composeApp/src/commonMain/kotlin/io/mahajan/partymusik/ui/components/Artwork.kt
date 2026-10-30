package io.mahajan.partymusik.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyShape
import kotlin.math.abs

private val Rings = listOf(Color(0xFF3A2B8C), Color(0xFF5B3FD1), Color(0xFF8C74FF))
private val Arcs = listOf(Color(0xFFFF9F0A), Color(0xFFFFD60A), Color(0xFF64D2FF))

/** Generated artwork for library items: concentric rings with two arcs placed by the item id. */
@Composable
fun Artwork(seed: String?, title: String, size: Dp, modifier: Modifier = Modifier) {
    val h = abs((seed ?: "none").hashCode())
    val hole = Party.colors.ground
    Box(
        modifier.size(size).shadow(18.dp, PartyShape.artwork, ambientColor = Color(0xFF241A5C), spotColor = Color(0xFF241A5C))
            .clip(PartyShape.artwork).background(Color(0xFF21174F))
            .semantics { contentDescription = "Artwork for $title" },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = this.size.width / 240f
            val c = Offset(120f * u, 120f * u)
            val w = 14f * u
            listOf(104f, 78f, 52f).forEachIndexed { i, r -> drawCircle(Rings[i], r * u, c, style = Stroke(w)) }
            fun arc(r: Float, start: Float, sweep: Float, color: Color) = drawArc(
                color, start, sweep, false, Offset(c.x - r * u, c.y - r * u), Size(2 * r * u, 2 * r * u),
                style = Stroke(w, cap = StrokeCap.Round),
            )
            arc(104f, -90f + (h % 360), 70f + (h / 7 % 60), Arcs[h % 3])
            arc(78f, 180f + (h / 11 % 360), 50f + (h / 13 % 70), Arcs[(h / 3 + 1) % 3])
            drawCircle(hole, 22f * u, c)
            drawCircle(Color(0xFF21174F), 5f * u, c)
        }
    }
}
