package io.mahajan.partymusik.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.diagnostics.HelpArticle
import io.mahajan.partymusik.diagnostics.Troubleshooting
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyShape
import io.mahajan.partymusik.ui.theme.PartyType

/** Height the floating tab bar covers, so the last row of a screen can scroll clear of it. */
val TabBarHeight = 64.dp

/** A tab's page: large title, scrolling content, capped width on big windows. */
@Composable
fun ScreenScroll(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = PartyType.largeTitle, color = Party.colors.label, modifier = Modifier.semantics { heading() })
                    if (subtitle != null) Text(subtitle, style = PartyType.subhead, color = Party.colors.secondary)
                }
                trailing?.invoke()
            }
            content()
            Spacer(Modifier.height(TabBarHeight + 24.dp))
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            text, style = PartyType.title3, color = Party.colors.label,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailing != null) Text(trailing, style = PartyType.subhead, color = Party.colors.secondary)
    }
}

/** Grouped rows on a rounded card (iOS inset-grouped list). */
@Composable
fun InsetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(PartyShape.group).background(Party.colors.card), content = content)
}

/** A padded card for free-form content. */
@Composable
fun Tile(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(PartyShape.tile).background(Party.colors.card).padding(padding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** One row of an [InsetGroup]. Pass interaction (clickable / selectable / toggleable) through [modifier]. */
@Composable
fun GroupRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = Party.colors.secondary,
    subtitleBold: Boolean = false,
    titleColor: Color = Party.colors.label,
    divider: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val sep = Party.colors.separator
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp)
                .drawBehind { if (divider) drawLine(sep, Offset(0f, size.height), Offset(size.width, size.height), 1f) }
                .padding(top = 10.dp, bottom = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = PartyType.body, color = titleColor)
                if (subtitle != null) {
                    Text(
                        subtitle, style = PartyType.footnote, color = subtitleColor,
                        fontWeight = if (subtitleBold) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
    }
}

/** Rounded-square icon holder used at the start of rows and tiles. */
@Composable
fun IconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = Party.colors.label,
    background: Color = Party.colors.ground,
    size: Dp = 38.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier.size(size).clip(androidx.compose.foundation.shape.RoundedCornerShape(size * 0.29f)).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun Footnote(text: String, modifier: Modifier = Modifier, color: Color = Party.colors.secondary) {
    Text(text, style = PartyType.footnote, color = color, modifier = modifier.padding(horizontal = 4.dp))
}

/** Disclosure text (AC9) as a quiet footnote: title plus its sentences. */
@Composable
fun DisclosureNote(key: String, modifier: Modifier = Modifier) {
    val article = Troubleshooting.disclosures.first { it.key == key }
    Column(modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(article.title, style = PartyType.footnote, fontWeight = FontWeight.SemiBold, color = Party.colors.secondary)
        Text(article.steps.joinToString(" "), style = PartyType.footnote, color = Party.colors.secondary)
    }
}

/** Expandable help article row. */
@Composable
fun HelpRow(article: HelpArticle, divider: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column {
        GroupRow(
            article.title,
            modifier = Modifier.clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
            divider = divider && !open,
            trailing = {
                Icon(
                    PartyIcons.Chevron, null, tint = Party.colors.tertiary,
                    modifier = Modifier.size(14.dp).rotate(if (open) 90f else 0f),
                )
            },
        )
        if (open) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                article.steps.forEach { Text("• $it", style = PartyType.subhead, color = Party.colors.secondary) }
            }
        }
    }
}

fun formatMs(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
