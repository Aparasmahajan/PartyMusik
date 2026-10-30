package io.mahajan.partymusik.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.telecom.DisconnectCause
import androidx.core.content.ContextCompat
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import io.mahajan.partymusik.domain.AppPermission
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.RouteCommandResult
import io.mahajan.partymusik.domain.RouteError
import io.mahajan.partymusik.platform.currentTimeMillis
import io.mahajan.partymusik.ports.CallObserverPort
import io.mahajan.partymusik.ports.CallState
import io.mahajan.partymusik.ports.CellularCallState
import io.mahajan.partymusik.ports.CommRoutingPort
import io.mahajan.partymusik.ports.Peer
import io.mahajan.partymusik.ports.VoipPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * In-app VoIP through Jetpack Core-Telecom (spike S-11). The POC call is an echo loopback, which proves
 * mic capture, call-audio routing, mute, interruption and teardown without a signalling backend.
 */
class AndroidVoip(private val context: Context, private val scope: CoroutineScope) : VoipPort {
    private val calls = CallsManager(context).apply { registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    override val callState: StateFlow<CallState> = _state.asStateFlow()
    private val _muted = MutableStateFlow(false)
    override val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private sealed interface Cmd {
        data object Hangup : Cmd
        data class Endpoint(val endpoint: CallEndpointCompat, val reply: (Boolean) -> Unit) : Cmd
    }

    private var commands: Channel<Cmd>? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var callJob: Job? = null
    private var loopback: EchoLoopback? = null

    val inTelecomCall: Boolean get() = commands != null

    override suspend fun startCall(peer: Peer): RouteCommandResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return RouteCommandResult.Rejected(RouteError.PermissionMissing(AppPermission.MICROPHONE))
        }
        if (commands != null) return RouteCommandResult.Rejected(RouteError.SessionConflict)
        val cmds = Channel<Cmd>(Channel.UNLIMITED)
        commands = cmds
        _state.value = CallState.Dialing
        val attributes = CallAttributesCompat(
            displayName = peer.name,
            address = Uri.parse(peer.uri),
            direction = CallAttributesCompat.DIRECTION_OUTGOING,
            callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            callCapabilities = CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        callJob = scope.launch {
            try {
                calls.addCall(
                    attributes,
                    onAnswer = { },
                    onDisconnect = { cause -> finish("telecom: ${cause.reason ?: cause.code}") },
                    onSetActive = { _state.value = CallState.Active(currentTimeMillis()) },
                    onSetInactive = { _state.value = CallState.Held },
                ) {
                    launch { controlCall(this@addCall, cmds) }
                }
            } catch (e: Exception) {
                finish("error: ${e.message}")
            }
        }
        return RouteCommandResult.Submitted
    }

    private suspend fun controlCall(control: CallControlScope, cmds: Channel<Cmd>) = with(control) {
        scope.launch { availableEndpoints.collect { endpoints = it } }
        scope.launch { isMuted.collect { m -> _muted.value = m; loopback?.muted = m } }
        if (setActive() is CallControlResult.Success) {
            _state.value = CallState.Active(currentTimeMillis())
            loopback = EchoLoopback().also { it.start() }
        }
        for (cmd in cmds) {
            when (cmd) {
                Cmd.Hangup -> {
                    disconnect(DisconnectCause(DisconnectCause.LOCAL))
                    finish("local")
                    break
                }
                is Cmd.Endpoint -> cmd.reply(requestEndpointChange(cmd.endpoint) is CallControlResult.Success)
            }
        }
    }

    private fun finish(reason: String) {
        loopback?.stop()
        loopback = null
        commands?.close()
        commands = null
        _muted.value = false
        _state.value = CallState.Ended(reason)
        scope.launch {
            kotlinx.coroutines.delay(300)
            if (_state.value is CallState.Ended) _state.value = CallState.Idle
        }
    }

    override suspend fun hangup() {
        commands?.trySend(Cmd.Hangup) ?: finish("local")
    }

    override suspend fun setMuted(muted: Boolean) {
        _muted.value = muted
        loopback?.muted = muted
    }

    /** VOIP-004: during a Telecom call, endpoint changes go through Telecom rather than AudioManager. */
    suspend fun requestEndpoint(device: AudioDeviceInfo): Boolean {
        val cmds = commands ?: return false
        val wanted = when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> CallEndpointCompat.TYPE_EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> CallEndpointCompat.TYPE_SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> CallEndpointCompat.TYPE_WIRED_HEADSET
            else -> CallEndpointCompat.TYPE_BLUETOOTH
        }
        val match = endpoints.filter { it.type == wanted }.let { list ->
            list.firstOrNull { it.name.toString() == device.productName?.toString() } ?: list.firstOrNull()
        } ?: return false
        val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
        cmds.trySend(Cmd.Endpoint(match) { result.complete(it) })
        return result.await()
    }

    /** Mic → 250 ms delay → call output. Audio stays in memory (DIA-006). */
    private inner class EchoLoopback {
        @Volatile var muted = false

        @Volatile private var running = false
        private var worker: Thread? = null

        @SuppressLint("MissingPermission") // checked in startCall
        fun start() {
            running = true
            worker = thread(name = "partymusik-voip", isDaemon = true) {
                val rate = 16_000
                val frames = rate / 50
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), frames * 8),
                )
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                    )
                    .setBufferSizeInBytes(frames * 2 * 8)
                    .build()
                val delayLine = ArrayDeque<ShortArray>()
                val buf = ShortArray(frames)
                rec.startRecording()
                track.play()
                while (running) {
                    val n = rec.read(buf, 0, frames)
                    if (n <= 0) continue
                    delayLine.addLast(if (muted) ShortArray(n) else buf.copyOf(n))
                    if (delayLine.size > 12) track.write(delayLine.removeFirst(), 0, n)
                }
                rec.stop()
                rec.release()
                track.stop()
                track.release()
            }
        }

        fun stop() {
            running = false
            worker?.join(500)
        }
    }
}

/** RTE-002/003, VOIP-002/004/005 */
class AndroidCommRouting(context: Context, private val voip: AndroidVoip) : CommRoutingPort {
    private val am = context.getSystemService(AudioManager::class.java)

    override suspend fun request(endpoint: EndpointId): RouteCommandResult {
        val device = am.availableCommunicationDevices.firstOrNull { AndroidIds.stableKey(it) == endpoint.value }
            ?: return RouteCommandResult.Rejected(RouteError.NotACommDevice)
        if (voip.inTelecomCall) {
            return if (voip.requestEndpoint(device)) {
                RouteCommandResult.Submitted
            } else {
                RouteCommandResult.Rejected(RouteError.OsRejected("Telecom requestEndpointChange failed"))
            }
        }
        return if (am.setCommunicationDevice(device)) {
            RouteCommandResult.Submitted
        } else {
            RouteCommandResult.Rejected(RouteError.OsRejected("setCommunicationDevice returned false"))
        }
    }

    override suspend fun release() = am.clearCommunicationDevice()

    /** Ground truth comes from AudioManager even when Telecom performed the switch. */
    override val observed: Flow<EndpointId?> = callbackFlow {
        val listener = AudioManager.OnCommunicationDeviceChangedListener { trySend(it?.let(AndroidIds::endpointId)) }
        am.addOnCommunicationDeviceChangedListener(Dispatchers.Default.asExecutor(), listener)
        trySend(am.communicationDevice?.let(AndroidIds::endpointId))
        awaitClose { am.removeOnCommunicationDeviceChangedListener(listener) }
    }.distinctUntilChanged()
}

/** CALL-001/002: observes the audio mode only; never touches the dialer's routing. */
class AndroidCallObserver(context: Context) : CallObserverPort {
    private val am = context.getSystemService(AudioManager::class.java)

    override val cellularCall: Flow<CellularCallState> = callbackFlow {
        fun map(mode: Int) = when (mode) {
            AudioManager.MODE_IN_CALL, AudioManager.MODE_CALL_SCREENING, AudioManager.MODE_CALL_REDIRECT -> CellularCallState.ACTIVE
            AudioManager.MODE_RINGTONE -> CellularCallState.RINGING
            else -> CellularCallState.NONE // MODE_IN_COMMUNICATION is our own (or another app's) VoIP
        }
        val listener = AudioManager.OnModeChangedListener { trySend(map(it)) }
        am.addOnModeChangedListener(Dispatchers.Default.asExecutor(), listener)
        trySend(map(am.mode))
        awaitClose { am.removeOnModeChangedListener(listener) }
    }.distinctUntilChanged()
}
