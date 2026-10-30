package io.mahajan.partymusik.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyShape
import io.mahajan.partymusik.ui.theme.PartyType
import kotlin.math.roundToInt

/** Primary call to action: full width, filled accent. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Party.colors
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(PartyShape.button)
            .background(if (enabled) c.accent else c.accent.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PartyType.headline, color = c.onAccent)
    }
}

/** Secondary action: accent text on a soft accent pill. */
@Composable
fun TintedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Party.colors
    Box(
        modifier.heightIn(min = 34.dp).clip(PartyShape.pill).background(c.accentSoft)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PartyType.subhead, fontWeight = FontWeight.SemiBold, color = if (enabled) c.accent else c.tertiary)
    }
}

/** Neutral action on a grey pill, optionally with an icon. */
@Composable
fun GrayButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, fill: Boolean = false) {
    val c = Party.colors
    Row(
        modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).heightIn(min = 44.dp).clip(PartyShape.button)
            .background(c.card).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = c.label, modifier = Modifier.size(20.dp))
        Text(text, style = PartyType.subhead, fontWeight = FontWeight.SemiBold, color = c.label)
    }
}

/** Plain accent text link with a 44 dp touch target. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Party.colors.accent) {
    Box(
        modifier.heightIn(min = 44.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PartyType.subhead, fontWeight = FontWeight.SemiBold, color = if (enabled) color else Party.colors.tertiary)
    }
}

/** Round icon-only button (header actions). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Party.colors.accent,
    background: Color = Party.colors.card,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier.size(size.coerceAtLeast(44.dp)).clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size).clip(PartyShape.pill).background(background), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/** Big round button with a caption underneath (call controls). */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    container: Color,
    content: Color,
    labelColor: Color,
    modifier: Modifier = Modifier,
    contentDescription: String = label,
    stateDescription: String? = null,
    size: Dp = 66.dp,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(size).clip(PartyShape.pill).background(container)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics {
                    this.contentDescription = contentDescription
                    if (stateDescription != null) this.stateDescription = stateDescription
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = content, modifier = Modifier.size(size * 0.42f))
        }
        Text(label, style = PartyType.caption, color = labelColor, maxLines = 1)
    }
}

/** Horizontal row of selectable pills (profile switcher). */
@Composable
fun PillSwitcher(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Party.colors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.heightIn(min = 36.dp).clip(PartyShape.pill).background(if (on) c.accent else c.card)
                    .selectable(on, role = Role.RadioButton) { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = PartyType.subhead, fontWeight = FontWeight.SemiBold, color = if (on) c.onAccent else c.label)
            }
        }
    }
}

/** iOS-style segmented control. */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Party.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(2.dp).selectableGroup()) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = 32.dp)
                    .then(if (on) Modifier.shadow(2.dp, RoundedCornerShape(8.dp)) else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) (if (c.isDark) c.tertiary else c.card) else Color.Transparent)
                    .selectable(on, role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = PartyType.footnote, fontWeight = FontWeight.SemiBold, color = c.label)
            }
        }
    }
}

/** Capsule volume slider (Control Center style). [value] is 0..1; steps of 5 % for assistive tech. */
@Composable
fun CapsuleSlider(value: Float, onValueChange: (Float) -> Unit, contentDescription: String, modifier: Modifier = Modifier) {
    val c = Party.colors
    val change by rememberUpdatedState(onValueChange)
    var width by remember { mutableIntStateOf(1) }
    Box(
        modifier.fillMaxWidth().height(32.dp).clip(PartyShape.pill).background(c.fill)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { o -> change((o.x / width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> change((ch.position.x / width).coerceIn(0f, 1f)) } }
            .semantics {
                this.contentDescription = contentDescription
                stateDescription = "${(value * 100).roundToInt()} percent"
                progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f, steps = 19)
                setProgress { v -> change(v.coerceIn(0f, 1f)); true }
            },
    ) {
        // Labels drawn twice: dark on the track, light clipped to the fill, so they stay legible at any value.
        val full = with(LocalDensity.current) { width.toDp() }
        CapsuleLabels(value, c.secondary, c.label, full)
        Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(c.accent).clipToBounds()) {
            CapsuleLabels(value, c.onAccent, c.onAccent, full)
        }
    }
}

@Composable
private fun CapsuleLabels(value: Float, iconTint: Color, textColor: Color, width: Dp) {
    Row(
        Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(width).fillMaxHeight().padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(PartyIcons.Volume, null, tint = iconTint, modifier = Modifier.size(16.dp))
        Text("${(value * 100).roundToInt()}%", style = PartyType.caption, color = textColor)
    }
}

/** Playback scrubber: thin track, seeks when the drag ends. */
@Composable
fun ScrubBar(fraction: Float, onSeek: (Float) -> Unit, valueText: String, modifier: Modifier = Modifier) {
    val c = Party.colors
    val seek by rememberUpdatedState(onSeek)
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fraction).coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(24.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { o -> seek((o.x / width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragging?.let(seek); dragging = null },
                    onDragCancel = { dragging = null },
                ) { ch, _ -> dragging = (ch.position.x / width).coerceIn(0f, 1f) }
            }
            .semantics {
                contentDescription = "Playback position"
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { v -> seek(v.coerceIn(0f, 1f)); true }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val h = if (dragging != null) 10.dp else 6.dp
        Box(Modifier.fillMaxWidth().height(h).clip(PartyShape.pill).background(c.separator)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(shown).clip(PartyShape.pill).background(c.label))
        }
    }
}

@Composable
fun PartySwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val c = Party.colors
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White, checkedTrackColor = c.accent, checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White, uncheckedTrackColor = c.fill, uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/**
 * Square selection box. Deliberately not round: round checks mean "the system confirmed this route",
 * squares mean "you picked this".
 */
@Composable
fun SelectBox(checked: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, size: Dp = 24.dp) {
    val c = Party.colors
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        if (checked) {
            drawRoundRect(if (enabled) c.accent else c.tertiary, Offset(2.5f * u, 2.5f * u), Size(19f * u, 19f * u), CornerRadius(6f * u))
            val p = Path().apply { moveTo(7.5f * u, 12.4f * u); lineTo(10.5f * u, 15.4f * u); lineTo(16.5f * u, 9.2f * u) }
            drawPath(p, c.onAccent, style = Stroke(2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawRoundRect(
                if (enabled) c.tertiary else c.separator, Offset(3f * u, 3f * u), Size(18f * u, 18f * u), CornerRadius(5.5f * u),
                style = Stroke(1.6f * u),
            )
        }
    }
}

/** Dashed outline button ("New profile"). */
@Composable
fun DashedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Party.colors
    Row(
        modifier.fillMaxWidth().height(52.dp).clip(PartyShape.group)
            .clickable(role = Role.Button, onClick = onClick)
            .dashedBorder(c.tertiary),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PartyIcons.Plus, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Text(text, style = PartyType.headline, color = c.accent)
    }
}

private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    drawRoundRect(
        color, Offset(1f, 1f), Size(size.width - 2f, size.height - 2f), CornerRadius(18.dp.toPx()),
        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
    )
}
