package io.mahajan.partymusik.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.mahajan.partymusik.domain.AppPermission
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.domain.PermissionStatus
import io.mahajan.partymusik.ports.BtAdapterState
import io.mahajan.partymusik.ports.CellularCallState
import io.mahajan.partymusik.simulation.SimulatedPlatform
import io.mahajan.partymusik.ui.theme.Party
import io.mahajan.partymusik.ui.theme.PartyType

/** Desktop-only controls that play the role of the phone OS and accessories. */
@Composable
fun SimulatorPanel(sim: SimulatedPlatform, modifier: Modifier = Modifier) {
    val devices by sim.devices.collectAsState()
    val bt by sim.bluetoothState.collectAsState()
    val behaviour by sim.behaviour.collectAsState()
    val cellular by sim.cellularState.collectAsState()
    val perms by sim.ports.permissions.statuses.collectAsState()

    Surface(modifier, color = Party.colors.card) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Simulator", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "Acts as the phone OS and accessories. Not part of the mobile app.",
                style = PartyType.footnote,
                color = Party.colors.secondary,
            )

            Toggle("Bluetooth", bt == BtAdapterState.ON) { sim.setBluetooth(it) }
            HorizontalDivider()
            Text("Accessories", fontWeight = FontWeight.Medium)
            devices.filter { it.kind == null || !it.kind!!.isBuiltIn }.forEach { d ->
                Toggle(d.name + (d.kind?.let { " · ${it.label} · ${d.latencyMs} ms" } ?: " · not audio"), d.connected) {
                    sim.setConnected(d.key, it)
                }
            }

            HorizontalDivider()
            Text("Phone call (system dialer)", fontWeight = FontWeight.Medium)
            if (cellular == CellularCallState.NONE) {
                Button(onClick = { sim.startCellularCall() }) { Text("Incoming phone call") }
            } else {
                OutlinedButton(onClick = { sim.endCellularCall() }) { Text("End phone call (${cellular.name.lowercase()})") }
            }

            HorizontalDivider()
            Text("OS behaviour", fontWeight = FontWeight.Medium)
            Toggle("Ignore media requests for JBL (→ timeout)", "bt-jbl-charge6" in behaviour.ignoreMediaFor) { on ->
                sim.setBehaviour { b -> b.copy(ignoreMediaFor = if (on) b.ignoreMediaFor + "bt-jbl-charge6" else b.ignoreMediaFor - "bt-jbl-charge6") }
            }
            Toggle("Redirect media to phone speaker", behaviour.redirectMediaTo != null) { on ->
                sim.setBehaviour { it.copy(redirectMediaTo = if (on) "builtin-speaker" else null) }
            }
            Toggle("iOS-style: Bluetooth needs system picker", EndpointKind.BT_CLASSIC_MEDIA in behaviour.pickerOnlyKinds) { on ->
                sim.setBehaviour {
                    it.copy(pickerOnlyKinds = if (on) setOf(EndpointKind.BT_CLASSIC_MEDIA, EndpointKind.BT_LE_AUDIO) else emptySet())
                }
            }
            Toggle("Sony can't join multi-output", "bt-sony-xm5" in behaviour.multiRejectFor) { on ->
                sim.setBehaviour { b -> b.copy(multiRejectFor = if (on) b.multiRejectFor + "bt-sony-xm5" else b.multiRejectFor - "bt-sony-xm5") }
            }
            Text("Route switch delay: ${behaviour.confirmDelayMs} ms")
            Slider(
                value = behaviour.confirmDelayMs.toFloat(),
                valueRange = 50f..5_000f,
                onValueChange = { v -> sim.setBehaviour { it.copy(confirmDelayMs = v.toLong()) } },
            )
            Toggle("Microphone permission", perms[AppPermission.MICROPHONE] == PermissionStatus.GRANTED) { on ->
                sim.setPermission(AppPermission.MICROPHONE, if (on) PermissionStatus.GRANTED else PermissionStatus.DENIED)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
