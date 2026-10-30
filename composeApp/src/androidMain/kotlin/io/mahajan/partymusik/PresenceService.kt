package io.mahajan.partymusik

import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import io.mahajan.partymusik.android.PresenceBus

/**
 * Started by the system when an associated accessory comes into or leaves range, even if the app was killed.
 * The Application has already created the core, so events flow straight into the AutomationEngine.
 */
class PresenceService : CompanionDeviceService() {

    @Deprecated("API 31-32 callback")
    override fun onDeviceAppeared(address: String) {
        PresenceBus.appeared(address)
    }

    @Deprecated("API 31-32 callback")
    override fun onDeviceDisappeared(address: String) {
        PresenceBus.disappeared(address)
    }

    @Deprecated("API 33-35 callback")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let(PresenceBus::appeared)
    }

    @Deprecated("API 33-35 callback")
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        associationInfo.deviceMacAddress?.toString()?.let(PresenceBus::disappeared)
    }

    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        val address = getSystemService(CompanionDeviceManager::class.java)
            ?.myAssociations?.firstOrNull { it.id == event.associationId }?.deviceMacAddress?.toString()
            ?: return
        when (event.event) {
            DevicePresenceEvent.EVENT_BT_CONNECTED, DevicePresenceEvent.EVENT_BLE_APPEARED -> PresenceBus.appeared(address)
            DevicePresenceEvent.EVENT_BT_DISCONNECTED, DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> PresenceBus.disappeared(address)
        }
    }
}
