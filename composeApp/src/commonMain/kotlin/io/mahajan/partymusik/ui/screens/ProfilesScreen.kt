package io.mahajan.partymusik.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.PartyMusikCore
import io.mahajan.partymusik.domain.BuiltInProfiles
import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.DeviceRef
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.FallbackRule
import io.mahajan.partymusik.domain.Profile
import io.mahajan.partymusik.domain.ProfileTemplate
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.Trigger
import io.mahajan.partymusik.ui.components.CircleIconButton
import io.mahajan.partymusik.ui.components.DashedButton
import io.mahajan.partymusik.ui.components.Footnote
import io.mahajan.partymusik.ui.components.GroupRow
import io.mahajan.partymusik.ui.components.InsetGroup
import io.mahajan.partymusik.ui.components.PartySwitch
import io.mahajan.partymusik.ui.components.RouteGlyph
import io.mahajan.partymusik.ui.components.RouteGlyphIcon
import io.mahajan.partymusik.ui.components.ScreenScroll
import io.mahajan.partymusik.ui.components.SectionTitle
import io.mahajan.partymusik.ui.components.SegmentedControl
import io.mahajan.partymusik.ui.components.SelectBox
import io.mahajan.partymusik.ui.components.TextAction
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyIcons
import io.mahajan.partymusik.ui.theme.PartyShape
import io.mahajan.partymusik.ui.theme.PartyType
import kotlinx.coroutines.launch

/** Which profile is open in the editor; `null` profile means "new". */
private data class Editing(val profile: Profile?)

/** Profiles: mode cards, a sheet editor and automations. CFG-001..008. */
@Composable
fun ProfilesScreen(core: PartyMusikCore) {
    val profiles by core.profiles.profiles.collectAsState()
    val s by core.engine.state.collectAsState()
    val settings by core.store.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Party.colors
    var editing by remember { mutableStateOf<Editing?>(null) }
    val active = profiles.firstOrNull { it.id == s.activeProfileId }
    val manual = s.override?.isActive(core.clock.nowMillis()) == true

    ScreenScroll(
        "Profiles",
        subtitle = active?.let { "${it.name} is on" + if (manual) " · chosen by you" else "" } ?: "No profile applied",
    ) {
        profiles.chunked(2).forEach { pair ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { p ->
                    ModeCard(
                        p, p.id == s.activeProfileId, Modifier.weight(1f).fillMaxHeight(),
                        onApply = { scope.launch { core.profiles.apply(p.id, RequestSource.USER) } },
                        onEdit = { editing = Editing(p) },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        DashedButton("New profile", { editing = Editing(null) })

        SectionTitle("Automations")
        InsetGroup {
            GroupRow(
                "Switch profiles automatically",
                modifier = Modifier.toggleable(settings.automationEnabled, role = Role.Switch) { on ->
                    scope.launch { core.store.updateSettings { it.copy(automationEnabled = on) } }
                },
                subtitle = "When a chosen device connects",
                trailing = { PartySwitch(settings.automationEnabled, null) },
                divider = profiles.any { p -> p.triggers.any { it is Trigger.DeviceConnected } },
            )
            val rules = profiles.flatMap { p -> p.triggers.filterIsInstance<Trigger.DeviceConnected>().map { p to it } }
            rules.forEachIndexed { i, (p, t) ->
                GroupRow(
                    p.name,
                    modifier = Modifier.clickable(role = Role.Button) { editing = Editing(p) },
                    subtitle = "When ${t.device.displayName} connects",
                    titleColor = if (settings.automationEnabled) c.label else c.secondary,
                    divider = i < rules.lastIndex,
                    trailing = { Icon(PartyIcons.Chevron, null, tint = c.tertiary, modifier = Modifier.size(14.dp)) },
                )
            }
        }
        Footnote("Picking a profile or output yourself pauses automations for 2 hours.")
    }

    editing?.let { e -> ProfileEditorSheet(core, e.profile, onDismiss = { editing = null }) }
}

private fun visualFor(t: ProfileTemplate): ImageVector = when (t) {
    ProfileTemplate.MUSIC -> PartyIcons.Note
    ProfileTemplate.CALLS -> PartyIcons.Call
    ProfileTemplate.MULTI_DEVICE -> PartyIcons.TabOutputs
    ProfileTemplate.CUSTOM -> PartyIcons.Sliders
}

@Composable
private fun tintFor(t: ProfileTemplate): Pair<Color, Color> {
    val c = Party.colors
    return when (t) {
        ProfileTemplate.MUSIC -> c.accent to c.accentSoft
        ProfileTemplate.CALLS -> c.callGo to c.callGoSoft
        ProfileTemplate.MULTI_DEVICE -> c.warn to c.warn.copy(alpha = 0.14f)
        ProfileTemplate.CUSTOM -> (if (c.isDark) Color(0xFF7FB2F0) else Color(0xFF2F5E9E)) to
            (if (c.isDark) Color(0xFF1B2A3D) else Color(0xFFE4ECF7))
    }
}

@Composable
private fun ModeCard(p: Profile, isActive: Boolean, modifier: Modifier, onApply: () -> Unit, onEdit: () -> Unit) {
    val c = Party.colors
    val (tint, soft) = tintFor(p.template)
    Column(
        modifier.heightIn(min = 140.dp).clip(PartyShape.tile).background(c.card)
            .then(if (isActive) Modifier.background(c.accent.copy(alpha = 0.06f)) else Modifier)
            .selectable(isActive, role = Role.RadioButton, onClick = onApply)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(42.dp).clip(PartyShape.pill).background(if (isActive) tint else soft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(visualFor(p.template), null, tint = if (isActive) (if (c.isDark) Color.Black else Color.White) else tint, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.weight(1f))
            if (isActive) RouteGlyphIcon(RouteGlyph.ACTIVE, size = 22.dp)
        }
        Spacer(Modifier.weight(1f))
        Column {
            Text(p.name, style = PartyType.headline, color = c.label)
            Text(summary(p), style = PartyType.footnote, color = c.secondary, maxLines = 2)
        }
        TextAction("Edit", onEdit, Modifier.align(Alignment.End).semantics { contentDescription = "Edit ${p.name}" })
    }
}

private fun summary(p: Profile): String = buildString {
    append(
        when {
            p.mediaTargets.isEmpty() -> "Best output"
            p.multiOutput -> p.mediaTargets.joinToString(" + ") { it.displayName.substringBefore(' ') }
            else -> p.mediaTargets.first().displayName
        },
    )
    // "Keep the rest" only means something when the profile plays on several outputs.
    when (p.fallback.rules.firstOrNull { p.multiOutput || it != FallbackRule.StopMultiKeepRest }) {
        FallbackRule.Pause -> append(" · pause if lost")
        FallbackRule.BuiltInSpeaker -> append(" · speaker if lost")
        FallbackRule.StopMultiKeepRest -> append(" · keeps the rest if one drops")
        else -> if (p.autoSync && p.multiOutput) append(" · auto-align")
    }
}

/** Plain-words names for fallback rules, in the order they are tried. */
private val ruleChoices = listOf(
    FallbackRule.StopMultiKeepRest to "Keep playing on the rest",
    FallbackRule.NextInPriority to "Then the next one in order",
    FallbackRule.LastKnownGood to "The last output that worked",
    FallbackRule.BuiltInSpeaker to "Phone speaker",
    FallbackRule.Pause to "Pause until it returns",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditorSheet(core: PartyMusikCore, original: Profile?, onDismiss: () -> Unit) {
    val s by core.engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val c = Party.colors
    val creating = original == null
    var template by remember { mutableStateOf(ProfileTemplate.CUSTOM) }
    var p by remember {
        mutableStateOf(original ?: BuiltInProfiles.all(core.clock.nowMillis()).first { it.template == ProfileTemplate.CUSTOM }.copy(builtIn = false))
    }
    var name by remember { mutableStateOf(original?.name ?: "") }
    var nameError by remember { mutableStateOf(false) }

    // Every device seen this session plus the profile's own references, so absent devices stay editable.
    val known: List<Pair<DeviceRef, Set<Capability>>> = run {
        val fromEndpoints = s.endpoints.map { it.toRef() to it.capabilities }
        val seen = s.knownRefs.values.filter { r -> fromEndpoints.none { it.first.stableKey == r.stableKey } }.map { it to emptySet<Capability>() }
        val saved = (p.mediaTargets + listOfNotNull(p.commTarget)).filter { r ->
            (fromEndpoints + seen).none { it.first.stableKey == r.stableKey }
        }.map { it to emptySet<Capability>() }
        (fromEndpoints + seen + saved).distinctBy { it.first.stableKey }
    }

    fun done() = scope.launch {
        val trimmed = name.trim()
        if (creating) {
            if (trimmed.isEmpty()) { nameError = true; return@launch }
            val created = core.profiles.create(template, trimmed)
            core.profiles.save(
                p.copy(id = created.id, name = created.name, template = template, builtIn = false, createdAt = created.createdAt),
            )
        } else {
            if (trimmed != original!!.name && !core.profiles.rename(original.id, trimmed)) { nameError = true; return@launch }
            core.profiles.save(p.copy(name = trimmed))
        }
        core.automation.syncPresenceObservations()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.ground,
        shape = PartyShape.sheet,
        dragHandle = { BottomSheetDefaults.DragHandle(color = c.tertiary) },
    ) {
        Column(Modifier.widthIn(max = 640.dp).align(Alignment.CenterHorizontally).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { TextAction("Cancel", onDismiss) }
                Text(
                    if (creating) "New profile" else original!!.name, style = PartyType.headline, color = c.label,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(2f).semantics { heading() },
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { TextAction("Done", { done() }) }
            }
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (creating) {
                    SegmentedControl(
                        ProfileTemplate.entries.map { it.label },
                        ProfileTemplate.entries.indexOf(template),
                        { i ->
                            template = ProfileTemplate.entries[i]
                            val base = BuiltInProfiles.all(core.clock.nowMillis()).first { it.template == template }
                            p = base.copy(id = p.id, mediaTargets = p.mediaTargets, commTarget = p.commTarget, triggers = p.triggers, builtIn = false)
                        },
                    )
                }
                InsetGroup {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Name", style = PartyType.body, color = c.label)
                        BasicTextField(
                            name, { name = it; nameError = false },
                            singleLine = true,
                            textStyle = PartyType.body.copy(color = c.secondary, textAlign = TextAlign.End),
                            cursorBrush = SolidColor(c.accent),
                            modifier = Modifier.weight(1f).padding(start = 16.dp).semantics { contentDescription = "Profile name" },
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterEnd) {
                                    if (name.isEmpty()) Text("Required", style = PartyType.body, color = c.tertiary)
                                    inner()
                                }
                            },
                        )
                    }
                }
                if (nameError) Footnote("That name is empty or already used.", color = c.error)

                GroupHeader("Outputs, in order")
                InsetGroup {
                    p.mediaTargets.forEachIndexed { i, ref ->
                        GroupRow(
                            ref.displayName,
                            leading = { NumberBadge(i + 1) },
                            trailing = {
                                Row {
                                    CircleIconButton(PartyIcons.ChevronUp, "Move ${ref.displayName} up", { if (i > 0) p = p.copy(mediaTargets = p.mediaTargets.move(i, i - 1)) }, tint = if (i > 0) c.secondary else c.separator, background = Color.Transparent, size = 32.dp, iconSize = 18.dp)
                                    CircleIconButton(PartyIcons.ChevronDown, "Move ${ref.displayName} down", { if (i < p.mediaTargets.lastIndex) p = p.copy(mediaTargets = p.mediaTargets.move(i, i + 1)) }, tint = if (i < p.mediaTargets.lastIndex) c.secondary else c.separator, background = Color.Transparent, size = 32.dp, iconSize = 18.dp)
                                    CircleIconButton(PartyIcons.Close, "Remove ${ref.displayName}", { p = p.copy(mediaTargets = p.mediaTargets - ref) }, tint = c.error, background = Color.Transparent, size = 32.dp, iconSize = 14.dp)
                                }
                            },
                        )
                    }
                    val addable = known.filter { (ref, caps) ->
                        p.mediaTargets.none { it.stableKey == ref.stableKey } && (caps.isEmpty() || Capability.MEDIA_OUTPUT in caps)
                    }
                    addable.forEachIndexed { i, (ref, _) ->
                        GroupRow(
                            "Add ${ref.displayName}",
                            modifier = Modifier.clickable(role = Role.Button) { p = p.copy(mediaTargets = p.mediaTargets + ref) },
                            titleColor = c.accent,
                            leading = { Icon(PartyIcons.Plus, null, tint = c.accent, modifier = Modifier.size(22.dp)) },
                            divider = i < addable.lastIndex,
                        )
                    }
                    if (p.mediaTargets.isEmpty() && addable.isEmpty()) GroupRow("Best available output", divider = false)
                }
                Footnote("Leave empty to use the best available output. Played in this order when one disconnects.")

                InsetGroup(Modifier.padding(top = 8.dp)) {
                    SwitchRow("Play together", p.multiOutput) { p = p.copy(multiOutput = it) }
                    SwitchRow("Align automatically", p.autoSync, enabled = p.multiOutput, divider = false) { p = p.copy(autoSync = it) }
                }

                GroupHeader("Calls")
                InsetGroup(Modifier.selectableGroup()) {
                    val commChoices = known.filter { (_, caps) -> caps.isEmpty() || Capability.COMMUNICATION_OUTPUT in caps }
                    ChoiceRow("Best available", p.commTarget == null) { p = p.copy(commTarget = null) }
                    commChoices.forEach { (ref, _) ->
                        ChoiceRow(ref.displayName, p.commTarget?.stableKey == ref.stableKey) { p = p.copy(commTarget = ref) }
                    }
                    SwitchRow("Resume music after calls", p.restoreAfterCall, divider = false) { p = p.copy(restoreAfterCall = it) }
                }

                GroupHeader("If an output disconnects")
                InsetGroup {
                    ruleChoices.forEach { (rule, label) ->
                        val on = p.fallback.rules.any { it == rule }
                        GroupRow(
                            label,
                            modifier = Modifier.toggleable(on, role = Role.Checkbox) { enable ->
                                val rules = if (enable) ruleChoices.map { it.first }.filter { it == rule || it in p.fallback.rules } else p.fallback.rules - rule
                                p = p.copy(fallback = p.fallback.copy(rules = rules))
                            },
                            titleColor = if (on) c.label else c.secondary,
                            leading = { SelectBox(on) },
                        )
                    }
                    SwitchRow("Switch back when it returns", p.fallback.autoResumeWhenPreferredReturns, divider = false) {
                        p = p.copy(fallback = p.fallback.copy(autoResumeWhenPreferredReturns = it))
                    }
                }
                Footnote("Rules are tried top to bottom.")

                GroupHeader("Automation")
                InsetGroup {
                    val devices = known.filter { (ref, _) -> ref.kind.isBluetooth || ref.kind == EndpointKind.USB }
                    devices.forEachIndexed { i, (ref, _) ->
                        val on = p.triggers.any { it is Trigger.DeviceConnected && it.device.stableKey == ref.stableKey }
                        SwitchRow("When ${ref.displayName} connects", on, divider = i < devices.lastIndex) { enable ->
                            val others = p.triggers.filterNot { it is Trigger.DeviceConnected && it.device.stableKey == ref.stableKey }
                            p = p.copy(triggers = if (enable) others + Trigger.DeviceConnected(ref) else others)
                        }
                    }
                    if (devices.isEmpty()) GroupRow("No Bluetooth or USB devices seen yet", divider = false, titleColor = c.secondary)
                }

                if (!creating) {
                    InsetGroup(Modifier.padding(top = 16.dp)) {
                        GroupRow(
                            "Duplicate profile",
                            modifier = Modifier.clickable(role = Role.Button) { scope.launch { core.profiles.duplicate(original!!.id); onDismiss() } },
                            titleColor = c.accent,
                            divider = !original!!.builtIn,
                        )
                        if (!original.builtIn) {
                            GroupRow(
                                "Delete profile",
                                modifier = Modifier.clickable(role = Role.Button) { scope.launch { core.profiles.delete(original.id); onDismiss() } },
                                titleColor = c.error,
                                divider = false,
                            )
                        }
                    }
                    if (original.builtIn) Footnote("Built-in profiles can be edited but not deleted.")
                }
                Spacer(Modifier.heightIn(min = 16.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text.uppercase(), style = PartyType.footnote, color = Party.colors.secondary,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp).semantics { heading() },
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    GroupRow(
        title,
        modifier = Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = if (checked) "On" else "Off" },
        titleColor = if (enabled) Party.colors.label else Party.colors.secondary,
        divider = divider,
        trailing = { PartySwitch(checked, null, enabled = enabled) },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    val c = Party.colors
    GroupRow(
        label,
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
        trailing = { if (selected) Icon(PartyIcons.Check, null, tint = c.accent, modifier = Modifier.size(20.dp)) },
    )
}

@Composable
private fun NumberBadge(n: Int) {
    val c = Party.colors
    Box(Modifier.size(22.dp).clip(PartyShape.pill).background(c.accent), contentAlignment = Alignment.Center) {
        Text("$n", style = PartyType.caption, fontWeight = FontWeight.Bold, color = c.onAccent)
    }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }
