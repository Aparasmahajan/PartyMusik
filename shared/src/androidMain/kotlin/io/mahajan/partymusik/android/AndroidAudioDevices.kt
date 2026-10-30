package io.mahajan.partymusik.android

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import io.mahajan.partymusik.diagnostics.Redaction
import io.mahajan.partymusik.domain.AudioEndpoint
import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.EndpointKind
import io.mahajan.partymusik.ports.AudioDevicePort
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Identity rules shared by all Android adapters.
 * Bluetooth A2DP / SCO / LE entries of one accessory share an address, so they collapse into one endpoint
 * whose key is a salted hash of that address (raw addresses never leave this file).
 */
object AndroidIds {
    private val bluetoothTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
    )

    fun isBluetooth(type: Int) = type in bluetoothTypes

    fun keyForAddress(address: String): String = "bt-" + Redaction.fnv1a64("pm:" + address.uppercase()).take(12)

    fun stableKey(info: AudioDeviceInfo): String =
        if (isBluetooth(info.type) && !info.address.isNullOrEmpty()) {
            keyForAddress(info.address)
        } else {
            "t${groupType(info.type)}-${info.address.orEmpty()}"
        }

    fun endpointId(info: AudioDeviceInfo) = EndpointId(stableKey(info))

    /** Wired headset/headphones and USB device/headset are one physical output each. */
    private fun groupType(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioDeviceInfo.TYPE_WIRED_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioDeviceInfo.TYPE_USB_HEADSET
        else -> type
    }

    val mediaTypes = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_HEARING_AID,
    )

    fun kindOf(types: Set<Int>): EndpointKind = when {
        AudioDeviceInfo.TYPE_BLE_BROADCAST in types -> EndpointKind.BT_LE_BROADCAST
        AudioDeviceInfo.TYPE_BLE_HEADSET in types || AudioDeviceInfo.TYPE_BLE_SPEAKER in types -> EndpointKind.BT_LE_AUDIO
        AudioDeviceInfo.TYPE_HEARING_AID in types -> EndpointKind.HEARING_AID
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types -> EndpointKind.BT_CLASSIC_MEDIA
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types -> EndpointKind.BT_CLASSIC_HFP
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in types -> EndpointKind.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE in types -> EndpointKind.BUILTIN_EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADSET in types || AudioDeviceInfo.TYPE_WIRED_HEADPHONES in types -> EndpointKind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE in types || AudioDeviceInfo.TYPE_USB_HEADSET in types -> EndpointKind.USB
        AudioDeviceInfo.TYPE_HDMI in types -> EndpointKind.HDMI
        else -> EndpointKind.OTHER
    }
}

/** DEV-001/002/004 via AudioManager.getDevices + AudioDeviceCallback (spike S-01). */
class AndroidAudioDevices(context: Context) : AudioDevicePort {
    private val am = context.getSystemService(AudioManager::class.java)

    override val endpoints: Flow<List<AudioEndpoint>> = callbackFlow {
        fun emit() {
            trySend(snapshot())
        }
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = emit()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = emit()
        }
        am.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper()))
        emit()
        awaitClose { am.unregisterAudioDeviceCallback(cb) }
    }.distinctUntilChanged()

    fun snapshot(): List<AudioEndpoint> {
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val commKeys = am.availableCommunicationDevices.map { AndroidIds.stableKey(it) }.toSet()
        val micKeys = inputs.map { AndroidIds.stableKey(it) }.toSet()

        return outputs.filter { it.isSink }.groupBy { AndroidIds.stableKey(it) }.map { (key, infos) ->
            val types = infos.map { it.type }.toSet()
            val kind = AndroidIds.kindOf(types)
            val media = types.any { it in AndroidIds.mediaTypes }
            val caps = buildSet {
                if (media) {
                    add(Capability.MEDIA_OUTPUT)
                    add(Capability.MULTI_OUTPUT_MEMBER) // AudioTrack fan-out; real support is per device (compat matrix)
                    add(Capability.INDEPENDENT_VOLUME)  // app-side gain per sink
                }
                if (key in commKeys) add(Capability.COMMUNICATION_OUTPUT)
                if (key in micKeys || kind == EndpointKind.BUILTIN_EARPIECE) add(Capability.MICROPHONE)
                if (kind == EndpointKind.BT_LE_BROADCAST) add(Capability.BROADCAST_SINK)
                if (kind in setOf(EndpointKind.BUILTIN_SPEAKER, EndpointKind.WIRED, EndpointKind.USB, EndpointKind.BT_LE_AUDIO)) {
                    add(Capability.LOW_LATENCY)
                }
                if (kind.isBluetooth) add(Capability.RECONNECTABLE)
            }
            val primary = infos.firstOrNull { it.type in AndroidIds.mediaTypes } ?: infos.first()
            AudioEndpoint(
                id = EndpointId(key),
                stableKey = key,
                name = primary.productName?.toString()?.takeIf { it.isNotBlank() && !kind.isBuiltIn } ?: kind.label,
                kind = kind,
                capabilities = caps,
                sampleRates = infos.flatMap { it.sampleRates.toList() }.distinct().sorted(),
                channelCounts = infos.flatMap { it.channelCounts.toList() }.distinct().sorted(),
            )
        }.sortedBy { it.kind.ordinal }
    }

    /** Finds the AudioDeviceInfo to hand to setPreferredDevice for media. */
    fun mediaDevice(id: EndpointId): AudioDeviceInfo? =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { AndroidIds.stableKey(it) == id.value && it.type in AndroidIds.mediaTypes }
            // prefer the media profile of a Bluetooth accessory (A2DP / LE) over SCO
            .minByOrNull { if (it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) 1 else 0 }
}
