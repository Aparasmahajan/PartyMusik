package io.mahajan.partymusik.registry

import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.BondedDevice
import io.mahajan.partymusik.domain.BtProfile
import io.mahajan.partymusik.domain.UnavailableDevice
import io.mahajan.partymusik.domain.UnavailableReason
import io.mahajan.partymusik.ports.BtAdapterState
import io.mahajan.partymusik.ports.PlatformPorts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class DeviceInventory(
    val bluetooth: BtAdapterState = BtAdapterState.ON,
    val endpoints: List<AudioEndpoint> = emptyList(),
    /** Paired devices that are not usable as an output right now, with the reason (DEV-007). */
    val unavailable: List<UnavailableDevice> = emptyList(),
)

/** Joins OS audio endpoints with the paired-device list so the UI can explain every device (DEV-001, DEV-007, DEV-008). */
class DeviceRegistry(ports: PlatformPorts, scope: CoroutineScope) {

    val inventory: StateFlow<DeviceInventory> = combine(
        ports.bluetooth.adapterState,
        ports.audioDevices.endpoints,
        ports.bluetooth.bonded,
    ) { bt, endpoints, bonded -> classify(bt, endpoints, bonded) }
        .stateIn(scope, SharingStarted.Eagerly, DeviceInventory())

    companion object {
        fun classify(bt: BtAdapterState, endpoints: List<AudioEndpoint>, bonded: List<BondedDevice>): DeviceInventory {
            val endpointKeys = endpoints.map { it.stableKey }.toSet()
            val unavailable = bonded.filter { it.stableKey !in endpointKeys }.map { device ->
                val reason = when {
                    bt == BtAdapterState.OFF -> UnavailableReason.BT_OFF
                    bt == BtAdapterState.UNAUTHORIZED -> UnavailableReason.PERMISSION
                    !device.isAudio -> UnavailableReason.NOT_AUDIO_DEVICE
                    !device.connected -> UnavailableReason.NOT_CONNECTED
                    device.profiles == setOf(BtProfile.HFP) -> UnavailableReason.CALLS_ONLY
                    else -> UnavailableReason.UNSUPPORTED
                }
                UnavailableDevice(device, reason)
            }
            return DeviceInventory(bt, endpoints, unavailable)
        }
    }
}
