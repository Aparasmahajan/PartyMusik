package io.mahajan.partymusik.domain

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Session-scoped identifier of an audio endpoint as reported by the OS adapter. */
@Serializable
@JvmInline
value class EndpointId(val value: String) {
    override fun toString(): String = value
}

enum class EndpointKind(val label: String) {
    BUILTIN_SPEAKER("Phone speaker"),
    BUILTIN_EARPIECE("Phone earpiece"),
    WIRED("Wired"),
    USB("USB audio"),
    HDMI("HDMI"),
    BT_CLASSIC_MEDIA("Bluetooth"),
    BT_CLASSIC_HFP("Bluetooth (calls)"),
    BT_LE_AUDIO("Bluetooth LE Audio"),
    BT_LE_BROADCAST("LE Audio broadcast"),
    HEARING_AID("Hearing aid"),
    AIRPLAY("AirPlay"),
    CAR_AUDIO("Car audio"),
    OTHER("Other");

    val isBluetooth: Boolean
        get() = this == BT_CLASSIC_MEDIA || this == BT_CLASSIC_HFP || this == BT_LE_AUDIO ||
            this == BT_LE_BROADCAST || this == HEARING_AID

    val isBuiltIn: Boolean get() = this == BUILTIN_SPEAKER || this == BUILTIN_EARPIECE
}

enum class ConnectionState { CONNECTED, CONNECTING, PAIRED_NOT_CONNECTED, UNAVAILABLE }

enum class Capability(val label: String) {
    MEDIA_OUTPUT("Media"),
    COMMUNICATION_OUTPUT("Calls"),
    MICROPHONE("Mic"),
    MULTI_OUTPUT_MEMBER("Multi-output"),
    BROADCAST_SINK("Broadcast"),
    INDEPENDENT_VOLUME("Own volume"),
    ABSOLUTE_VOLUME("Absolute volume"),
    LOW_LATENCY("Low latency"),
    RECONNECTABLE("Reconnectable"),
}

/** Why a known device cannot be used as an audio output right now (DEV-007). */
enum class UnavailableReason(val explanation: String) {
    NOT_CONNECTED("Paired, but not connected. Turn the device on or reconnect it from system Bluetooth settings."),
    CALLS_ONLY("Connected for calls only (hands-free profile). It cannot play media from this app."),
    NOT_AUDIO_DEVICE("Paired, but this device does not offer an audio profile."),
    BT_OFF("Bluetooth is turned off."),
    PERMISSION("Nearby-devices permission is needed to show Bluetooth device details."),
    UNSUPPORTED("The system does not expose this device as an audio output."),
}

enum class ChannelType { MEDIA, COMMUNICATION }

data class AudioEndpoint(
    val id: EndpointId,
    /** Survives reconnects: hashed Bluetooth address, iOS port UID, or a fixed key for built-ins. */
    val stableKey: String,
    val name: String,
    val kind: EndpointKind,
    val connection: ConnectionState = ConnectionState.CONNECTED,
    val capabilities: Set<Capability> = emptySet(),
    val sampleRates: List<Int> = emptyList(),
    val channelCounts: List<Int> = emptyList(),
) {
    val isConnected: Boolean get() = connection == ConnectionState.CONNECTED

    fun canCarry(channel: ChannelType): Boolean = isConnected && when (channel) {
        ChannelType.MEDIA -> Capability.MEDIA_OUTPUT in capabilities
        ChannelType.COMMUNICATION -> Capability.COMMUNICATION_OUTPUT in capabilities
    }

    fun toRef(): DeviceRef = DeviceRef(stableKey, name, kind)
}

/** Persistable reference to a device, used by profiles and fallback memory. */
@Serializable
data class DeviceRef(val stableKey: String, val displayName: String, val kind: EndpointKind)

enum class BtProfile { A2DP, HFP, LE_AUDIO, HEARING_AID, OTHER }

data class BondedDevice(
    val stableKey: String,
    val name: String,
    val profiles: Set<BtProfile>,
    val connected: Boolean,
) {
    val isAudio: Boolean get() = profiles.any { it != BtProfile.OTHER }
}

/** A paired device that is not currently usable, with the reason shown to the user. */
data class UnavailableDevice(val device: BondedDevice, val reason: UnavailableReason)
