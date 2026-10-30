package io.mahajan.partymusik.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothLeAudio
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.MediaRouter2
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.mahajan.partymusik.domain.AppPermission
import io.mahajan.partymusik.domain.BondedDevice
import io.mahajan.partymusik.domain.BtProfile
import io.mahajan.partymusik.domain.DeviceRef
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.PermissionStatus
import io.mahajan.partymusik.ports.AudioSharingState
import io.mahajan.partymusik.ports.BluetoothPort
import io.mahajan.partymusik.ports.BtAdapterState
import io.mahajan.partymusik.ports.PermissionPort
import io.mahajan.partymusik.ports.PresenceEvent
import io.mahajan.partymusik.ports.PresencePort
import io.mahajan.partymusik.ports.ProbeCapture
import io.mahajan.partymusik.ports.SettingsTarget
import io.mahajan.partymusik.ports.SyncProbePort
import io.mahajan.partymusik.ports.SystemAudioPort
import io.mahajan.partymusik.ports.SystemFlowResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Supplies the foreground Activity for system UI flows (pairing sheet, permission dialogs). */
fun interface ActivityProvider {
    fun current(): Activity?
}

// region Bluetooth (DEV-003, DEV-006, DEV-007, DEV-008)

class AndroidBluetooth(
    private val context: Context,
    private val permissions: AndroidPermissions,
    private val activity: ActivityProvider,
) : BluetoothPort {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val proxies = mutableMapOf<Int, BluetoothProfile>()
    private val refresh = MutableStateFlow(0)

    init {
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxies[profile] = proxy
                refresh.value++
            }

            override fun onServiceDisconnected(profile: Int) {
                proxies.remove(profile)
                refresh.value++
            }
        }
        listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO, BluetoothProfile.HEARING_AID).forEach {
            adapter?.getProfileProxy(context, listener, it)
        }
    }

    private fun broadcasts(vararg actions: String): Flow<Intent?> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(intent)
            }
        }
        val filter = IntentFilter().apply { actions.forEach(::addAction) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        trySend(null)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    override val adapterState: Flow<BtAdapterState> = broadcasts(BluetoothAdapter.ACTION_STATE_CHANGED).map {
        when {
            adapter == null -> BtAdapterState.UNSUPPORTED
            !permissions.granted(Manifest.permission.BLUETOOTH_CONNECT) -> BtAdapterState.UNAUTHORIZED
            else -> when (adapter.state) {
                BluetoothAdapter.STATE_ON -> BtAdapterState.ON
                BluetoothAdapter.STATE_TURNING_ON -> BtAdapterState.TURNING_ON
                BluetoothAdapter.STATE_TURNING_OFF -> BtAdapterState.TURNING_OFF
                else -> BtAdapterState.OFF
            }
        }
    }.distinctUntilChanged()

    @SuppressLint("MissingPermission") // guarded by permissions.granted
    override val bonded: Flow<List<BondedDevice>> = broadcasts(
        BluetoothAdapter.ACTION_STATE_CHANGED,
        BluetoothDevice.ACTION_BOND_STATE_CHANGED,
        BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED,
        BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED,
        BluetoothLeAudio.ACTION_LE_AUDIO_CONNECTION_STATE_CHANGED,
    ).map {
        if (adapter == null || !permissions.granted(Manifest.permission.BLUETOOTH_CONNECT)) return@map emptyList()
        adapter.bondedDevices.orEmpty().map { device -> device.toBonded() }
    }

    @SuppressLint("MissingPermission")
    private fun BluetoothDevice.toBonded(): BondedDevice {
        val connected = buildSet {
            fun check(profile: Int, p: BtProfile) {
                if (proxies[profile]?.getConnectionState(this@toBonded) == BluetoothProfile.STATE_CONNECTED) add(p)
            }
            check(BluetoothProfile.A2DP, BtProfile.A2DP)
            check(BluetoothProfile.HEADSET, BtProfile.HFP)
            check(BluetoothProfile.LE_AUDIO, BtProfile.LE_AUDIO)
            check(BluetoothProfile.HEARING_AID, BtProfile.HEARING_AID)
        }
        val isAudioClass = bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO
        val profiles = connected.ifEmpty { if (isAudioClass) setOf(BtProfile.A2DP) else setOf(BtProfile.OTHER) }
        return BondedDevice(AndroidIds.keyForAddress(address), name ?: "Bluetooth device", profiles, connected.isNotEmpty())
    }

    @SuppressLint("MissingPermission")
    fun addressFor(stableKey: String): String? =
        if (!permissions.granted(Manifest.permission.BLUETOOTH_CONNECT)) null
        else adapter?.bondedDevices?.firstOrNull { AndroidIds.keyForAddress(it.address) == stableKey }?.address

    /** Pairing goes through the Companion Device Manager sheet; Settings is the fallback. */
    override suspend fun launchPairing(): SystemFlowResult {
        // The executor-based CDM API is API 33+; older releases use system Bluetooth settings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        val act = activity.current() ?: return openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        val cdm = context.getSystemService(CompanionDeviceManager::class.java) ?: return openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        val request = AssociationRequest.Builder().addDeviceFilter(BluetoothDeviceFilter.Builder().build()).build()
        return suspendCancellableCoroutine { cont ->
            cdm.associate(request, ContextCompat.getMainExecutor(context), object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) {
                    act.startIntentSenderForResult(intentSender, 0x5041, null, 0, 0, 0)
                    if (cont.isActive) cont.resume(SystemFlowResult.LAUNCHED)
                }

                override fun onAssociationCreated(associationInfo: AssociationInfo) {
                    if (cont.isActive) cont.resume(SystemFlowResult.COMPLETED)
                }

                override fun onFailure(error: CharSequence?) {
                    if (cont.isActive) cont.resume(SystemFlowResult.CANCELLED)
                }
            })
        }
    }

    /** DEV-006: reconnection only through system UI (no hidden BluetoothA2dp.connect). */
    override suspend fun launchReconnect(device: DeviceRef): SystemFlowResult = openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)

    override suspend fun requestEnable(): SystemFlowResult = openSettings(BluetoothAdapter.ACTION_REQUEST_ENABLE)

    private fun openSettings(action: String): SystemFlowResult = try {
        val launcher = activity.current() ?: context
        launcher.startActivity(Intent(action).apply { if (launcher !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        SystemFlowResult.LAUNCHED
    } catch (e: Exception) {
        SystemFlowResult.UNAVAILABLE
    }
}

// endregion

// region Presence (CFG-006, ADR-004)

/** Bridges CompanionDeviceService callbacks (which can start a killed process) into the shared core. */
object PresenceBus {
    internal val events = MutableSharedFlow<PresenceEvent>(replay = 8, extraBufferCapacity = 16)

    fun appeared(address: String) = events.tryEmit(PresenceEvent.Appeared(AndroidIds.keyForAddress(address)))
    fun disappeared(address: String) = events.tryEmit(PresenceEvent.Disappeared(AndroidIds.keyForAddress(address)))
}

class AndroidPresence(private val context: Context, private val bluetooth: AndroidBluetooth) : PresencePort {
    private val cdm = context.getSystemService(CompanionDeviceManager::class.java)

    @Suppress("DEPRECATION") // ObservingDevicePresenceRequest replaces this on API 36; the address form works from API 31
    override suspend fun observe(device: DeviceRef): Boolean {
        val address = bluetooth.addressFor(device.stableKey) ?: return false
        val associated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm?.myAssociations?.any { it.deviceMacAddress?.toString().equals(address, ignoreCase = true) } == true
        } else {
            cdm?.associations?.any { it.equals(address, ignoreCase = true) } == true
        }
        if (!associated) return false // the user associates the device once via "Pair new device"
        return try {
            cdm?.startObservingDevicePresence(address)
            true
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun stopObserving(device: DeviceRef) {
        val address = bluetooth.addressFor(device.stableKey) ?: return
        runCatching { cdm?.stopObservingDevicePresence(address) }
    }

    override val presence: Flow<PresenceEvent> = PresenceBus.events.asSharedFlow()
}

// endregion

// region System audio (EXT-002, EXT-004, CALL-003)

class AndroidSystemAudio(
    private val context: Context,
    private val devices: AndroidAudioDevices,
    private val activity: ActivityProvider,
) : SystemAudioPort {

    /** Lets the user move any app's audio, including third-party apps, through the OS switcher. */
    override fun showSystemOutputPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && MediaRouter2.getInstance(context).showSystemOutputSwitcher()) return
        openSystemSettings(SettingsTarget.BLUETOOTH)
    }

    override val audioSharing: Flow<AudioSharingState> = devices.endpoints.map { eps ->
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        when {
            eps.any { it.kind == io.mahajan.partymusik.domain.EndpointKind.BT_LE_BROADCAST } -> AudioSharingState.ACTIVE
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                adapter?.isLeAudioBroadcastSourceSupported() == BluetoothStatusCodes.FEATURE_SUPPORTED -> AudioSharingState.AVAILABLE
            else -> AudioSharingState.UNAVAILABLE
        }
    }.distinctUntilChanged()

    override fun openSystemSettings(target: SettingsTarget) {
        val intent = when (target) {
            SettingsTarget.BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            SettingsTarget.SOUND -> Intent(Settings.ACTION_SOUND_SETTINGS)
            SettingsTarget.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }
        val launcher = activity.current() ?: context.also { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { launcher.startActivity(intent) }
    }
}

// endregion

// region Permissions (DIA-004, DIA-005)

/**
 * Runtime permissions are requested just in time by the feature that needs them.
 * [requester] is installed by MainActivity (ActivityResult API).
 */
class AndroidPermissions(private val context: Context, private val activity: ActivityProvider) : PermissionPort {
    var requester: (suspend (Array<String>) -> Map<String, Boolean>)? = null
    private val asked = mutableSetOf<AppPermission>()

    private fun manifest(p: AppPermission): Array<String> = when (p) {
        AppPermission.NEARBY_DEVICES -> arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        AppPermission.MICROPHONE -> arrayOf(Manifest.permission.RECORD_AUDIO)
        AppPermission.NOTIFICATIONS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
        AppPermission.COMPANION_PRESENCE -> emptyArray() // install-time permission
    }

    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun statusOf(p: AppPermission): PermissionStatus {
        val perms = manifest(p)
        if (perms.isEmpty()) return PermissionStatus.NOT_REQUIRED
        if (perms.all(::granted)) return PermissionStatus.GRANTED
        val act = activity.current()
        val permanently = p in asked && act != null && perms.none { act.shouldShowRequestPermissionRationale(it) }
        return if (permanently) PermissionStatus.PERMANENTLY_DENIED else PermissionStatus.DENIED
    }

    private val _statuses = MutableStateFlow(AppPermission.entries.associateWith(::statusOf))
    override val statuses: StateFlow<Map<AppPermission, PermissionStatus>> = _statuses.asStateFlow()

    fun refresh() {
        _statuses.value = AppPermission.entries.associateWith(::statusOf)
    }

    override suspend fun request(permission: AppPermission): PermissionStatus {
        val perms = manifest(permission)
        if (perms.isNotEmpty() && !perms.all(::granted)) {
            asked += permission
            requester?.invoke(perms)
        }
        refresh()
        return _statuses.value.getValue(permission)
    }

    override fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val launcher = activity.current() ?: context.also { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        runCatching { launcher.startActivity(intent) }
    }
}

// endregion

// region Sync probe (MED-007, S-10)

/**
 * Plays the probe on one output while recording the microphone, and reports the frame at which the probe was
 * handed to the output. Microphone input latency is common to every output, so it cancels out of the skew.
 */
class AndroidSyncProbe(private val context: Context, private val devices: AndroidAudioDevices) : SyncProbePort {
    private val am = context.getSystemService(AudioManager::class.java)

    @SuppressLint("MissingPermission") // checked below
    override suspend fun probe(endpoint: EndpointId, signal: FloatArray, sampleRate: Int): ProbeCapture? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@withContext null
        val device: AudioDeviceInfo = devices.mediaDevice(endpoint) ?: return@withContext null
        val source = if (am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
        val inFormat = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        val rec = AudioRecord.Builder().setAudioSource(source).setAudioFormat(inFormat)
            .setBufferSizeInBytes(sampleRate * 4).build()
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(signal.size * 4)
            .build()
        try {
            track.setPreferredDevice(device)
            track.write(signal, 0, signal.size, AudioTrack.WRITE_BLOCKING)
            val recording = FloatArray(sampleRate) // 1 s window, memory only
            val chunk = sampleRate / 100
            var pos = 0
            var emitFrame = -1
            rec.startRecording()
            while (pos < recording.size) {
                val n = rec.read(recording, pos, minOf(chunk, recording.size - pos), AudioRecord.READ_BLOCKING)
                if (n <= 0) break
                pos += n
                if (emitFrame < 0 && pos >= sampleRate / 10) {
                    emitFrame = pos
                    track.play()
                }
            }
            if (emitFrame < 0) null else ProbeCapture(recording, sampleRate, emitFrame)
        } finally {
            runCatching { rec.stop() }
            rec.release()
            track.release()
        }
    }
}

// endregion
