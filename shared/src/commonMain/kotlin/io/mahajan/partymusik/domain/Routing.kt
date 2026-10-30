package io.mahajan.partymusik.domain

enum class RequestSource(val priority: Int) { AUTOMATION(1), PROFILE(2), FALLBACK(3), USER(4) }

enum class AppPermission(val purpose: String) {
    NEARBY_DEVICES("Show names and connection state of your Bluetooth audio devices."),
    MICROPHONE("Carry your voice in in-app calls and measure speaker delay during the sync test."),
    NOTIFICATIONS("Show playback and call controls while the app is in the background."),
    COMPANION_PRESENCE("Apply a profile automatically when a chosen device connects."),
}

enum class PermissionStatus { GRANTED, DENIED, PERMANENTLY_DENIED, NOT_REQUIRED }

/** Stable, user-facing error model. Codes are what diagnostics record (DIA-003). */
sealed interface RouteError {
    val code: String
    val message: String

    data object Timeout : RouteError {
        override val code = "RTE_TIMEOUT"
        override val message = "The system did not confirm the new route in time."
    }

    data class OsRedirected(val actual: List<EndpointId>) : RouteError {
        override val code = "RTE_OS_REDIRECTED"
        override val message = "The system routed audio to a different output."
    }

    data object DeviceGone : RouteError {
        override val code = "RTE_DEVICE_GONE"
        override val message = "The device is no longer connected."
    }

    data object NotAMediaDevice : RouteError {
        override val code = "RTE_NOT_MEDIA_CAPABLE"
        override val message = "This device cannot play media (for example, a calls-only car kit)."
    }

    data object NotACommDevice : RouteError {
        override val code = "RTE_NOT_COMM_CAPABLE"
        override val message = "This device cannot carry call audio."
    }

    data object UnsupportedCombo : RouteError {
        override val code = "RTE_UNSUPPORTED_COMBO"
        override val message = "This combination of outputs cannot play at the same time on this phone."
    }

    data object SessionConflict : RouteError {
        override val code = "RTE_SESSION_CONFLICT"
        override val message = "Another app or a call currently controls audio."
    }

    data object BluetoothOff : RouteError {
        override val code = "BT_OFF"
        override val message = "Bluetooth is turned off."
    }

    data class PermissionMissing(val permission: AppPermission) : RouteError {
        override val code = "PERM_${permission.name}_DENIED"
        override val message = "Permission needed: ${permission.purpose}"
    }

    data class OsRejected(val detail: String) : RouteError {
        override val code = "RTE_OS_REJECTED"
        override val message = "The system rejected the route request."
    }
}

/** Result of a native routing command. Never means "active" — only observation confirms (RTE-008). */
sealed interface RouteCommandResult {
    data object Submitted : RouteCommandResult
    data object NeedsSystemPicker : RouteCommandResult
    data class Rejected(val error: RouteError) : RouteCommandResult
}

/** Remembers what a fallback replaced so the original can be restored when it returns. */
data class FallbackInfo(val lost: List<DeviceRef>, val original: List<DeviceRef>)

enum class SuspendReason { VOIP_CALL, CELLULAR_CALL }

sealed interface RouteState {
    data object Idle : RouteState

    data class Requested(
        val targets: List<EndpointId>,
        val since: Long,
        val needsSystemPicker: Boolean = false,
        val fallback: FallbackInfo? = null,
    ) : RouteState

    data class PartiallyConfirmed(
        val confirmed: List<EndpointId>,
        val pending: List<EndpointId>,
        val since: Long,
        val fallback: FallbackInfo? = null,
    ) : RouteState

    data class Confirmed(val active: List<EndpointId>, val fallback: FallbackInfo? = null) : RouteState

    data class Rejected(val targets: List<EndpointId>, val error: RouteError) : RouteState

    /** Playback held until one of [waitingFor] returns (fallback rule PAUSE or nothing available). */
    data class Paused(
        val waitingFor: List<DeviceRef>,
        val resumePlayback: Boolean,
        val restoreTargets: List<DeviceRef> = waitingFor,
    ) : RouteState

    /** Media yielded to a call; [resumeTargets] are restored afterwards (RTE-006). */
    data class Suspended(val reason: SuspendReason, val resumeTargets: List<DeviceRef>) : RouteState
}

enum class OutputMode { SINGLE, MULTI }

enum class CommSession { VOIP, CELLULAR }

data class ChannelSnapshot(
    val type: ChannelType,
    val requested: List<EndpointId> = emptyList(),
    val available: List<EndpointId> = emptyList(),
    /** Written only from OS observations (ADR-003). */
    val active: List<EndpointId> = emptyList(),
    val state: RouteState = RouteState.Idle,
    val mode: OutputMode = OutputMode.SINGLE,
    val generation: Long = 0,
)

/** Blocks automation until [until] (epoch ms) or until cleared when null (CFG-008). */
data class ManualOverride(val until: Long?) {
    fun isActive(now: Long): Boolean = until == null || now < until
}
