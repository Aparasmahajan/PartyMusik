package io.mahajan.partymusik.android

import android.content.Context
import android.os.Build
import io.mahajan.partymusik.ports.PlatformInfo
import io.mahajan.partymusik.ports.PlatformPorts
import kotlinx.coroutines.CoroutineScope

/** Builds the Android implementation of every port. */
class AndroidPlatform(context: Context, scope: CoroutineScope, activity: ActivityProvider) {
    private val app = context.applicationContext

    val permissions = AndroidPermissions(app, activity)
    val devices = AndroidAudioDevices(app)
    val output = AndroidAudioOutputEngine(app, devices)
    val voip = AndroidVoip(app, scope)
    val bluetooth = AndroidBluetooth(app, permissions, activity)

    val ports = PlatformPorts(
        info = PlatformInfo(
            os = "Android",
            osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            // Validated per device by spike S-06; the engine still confirms every route from observation.
            supportsSplitMediaAndComm = true,
            supportsMultiOutput = true,
        ),
        audioDevices = devices,
        mediaRouting = output,
        commRouting = AndroidCommRouting(app, voip),
        multiOutput = output,
        playback = output,
        voip = voip,
        callObserver = AndroidCallObserver(app),
        bluetooth = bluetooth,
        systemAudio = AndroidSystemAudio(app, devices, activity),
        permissions = permissions,
        presence = AndroidPresence(app, bluetooth),
        syncProbe = AndroidSyncProbe(app, devices),
    )
}
