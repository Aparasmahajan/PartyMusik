package io.mahajan.partymusik.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProfileTemplate(val label: String) {
    MUSIC("Music"),
    CALLS("Calls"),
    MULTI_DEVICE("Multi-Device"),
    CUSTOM("Custom"),
}

@Serializable
sealed interface Trigger {
    @Serializable
    @SerialName("device_connected")
    data class DeviceConnected(val device: DeviceRef) : Trigger

    @Serializable
    @SerialName("device_disconnected")
    data class DeviceDisconnected(val device: DeviceRef) : Trigger

    @Serializable
    @SerialName("app_launched")
    data object AppLaunched : Trigger
}

@Serializable
sealed interface FallbackRule {
    val label: String

    @Serializable
    @SerialName("stop_multi_keep_rest")
    data object StopMultiKeepRest : FallbackRule {
        override val label = "Multi-output: keep playing on the remaining outputs"
    }

    @Serializable
    @SerialName("next_in_priority")
    data object NextInPriority : FallbackRule {
        override val label = "Next device in the priority list, else best available"
    }

    @Serializable
    @SerialName("builtin_speaker")
    data object BuiltInSpeaker : FallbackRule {
        override val label = "Phone speaker"
    }

    @Serializable
    @SerialName("last_known_good")
    data object LastKnownGood : FallbackRule {
        override val label = "Last output that worked"
    }

    @Serializable
    @SerialName("specific")
    data class Specific(val device: DeviceRef) : FallbackRule {
        override val label get() = "Specific device: ${device.displayName}"
    }

    @Serializable
    @SerialName("pause")
    data object Pause : FallbackRule {
        override val label = "Pause until the device returns"
    }
}

@Serializable
data class FallbackPolicyConfig(
    val rules: List<FallbackRule> = listOf(
        FallbackRule.StopMultiKeepRest,
        FallbackRule.NextInPriority,
        FallbackRule.Pause,
    ),
    val autoResumeWhenPreferredReturns: Boolean = true,
    val avoidEarpieceForMedia: Boolean = true,
)

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val template: ProfileTemplate,
    /** Ordered priority list (DEV-005). Empty = best available output. */
    val mediaTargets: List<DeviceRef> = emptyList(),
    val multiOutput: Boolean = false,
    val perDeviceGain: Map<String, Float> = emptyMap(),
    val perDeviceDelayMs: Map<String, Int> = emptyMap(),
    /** Null = best available communication device when a call starts. */
    val commTarget: DeviceRef? = null,
    val fallback: FallbackPolicyConfig = FallbackPolicyConfig(),
    val triggers: List<Trigger> = emptyList(),
    val restoreAfterCall: Boolean = true,
    val autoSync: Boolean = false,
    val builtIn: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

object BuiltInProfiles {
    const val MUSIC_ID = "builtin-music"
    const val CALLS_ID = "builtin-calls"
    const val MULTI_ID = "builtin-multi"
    const val CUSTOM_ID = "builtin-custom"

    fun all(now: Long): List<Profile> = listOf(
        Profile(
            id = MUSIC_ID, name = "Music", template = ProfileTemplate.MUSIC,
            fallback = FallbackPolicyConfig(listOf(FallbackRule.NextInPriority, FallbackRule.Pause)),
            builtIn = true, createdAt = now, updatedAt = now,
        ),
        Profile(
            id = CALLS_ID, name = "Calls", template = ProfileTemplate.CALLS,
            fallback = FallbackPolicyConfig(listOf(FallbackRule.NextInPriority, FallbackRule.BuiltInSpeaker)),
            builtIn = true, createdAt = now, updatedAt = now,
        ),
        Profile(
            id = MULTI_ID, name = "Multi-Device", template = ProfileTemplate.MULTI_DEVICE, multiOutput = true,
            autoSync = true,
            fallback = FallbackPolicyConfig(listOf(FallbackRule.StopMultiKeepRest, FallbackRule.NextInPriority)),
            builtIn = true, createdAt = now, updatedAt = now,
        ),
        Profile(
            id = CUSTOM_ID, name = "Custom", template = ProfileTemplate.CUSTOM,
            builtIn = true, createdAt = now, updatedAt = now,
        ),
    )
}
