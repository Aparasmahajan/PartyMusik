package io.mahajan.partymusik.android

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.RouteCommandResult
import io.mahajan.partymusik.domain.RouteError
import io.mahajan.partymusik.ports.MediaItemRef
import io.mahajan.partymusik.ports.MediaRoutingPort
import io.mahajan.partymusik.ports.MultiOutputPort
import io.mahajan.partymusik.ports.PlaybackPort
import io.mahajan.partymusik.ports.PlaybackStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.thread

/**
 * App-owned playback through one or more AudioTrack sinks (spikes S-04, S-08).
 *
 * Why AudioTrack instead of ExoPlayer here: each sink's `AudioRouting.OnRoutingChangedListener` reports the device
 * the OS actually routed that track to, which is the ground truth RTE-008 requires. ExoPlayer's
 * `setPreferredAudioDevice` has no equivalent public read-back. Because ExoPlayer is not used, this class also owns
 * audio focus. System media controls come from `PlaybackService` (Media3 MediaSession over this [PlaybackPort]);
 * Media3 decoders can feed PCM into these sinks once real files are needed.
 */
class AndroidAudioOutputEngine(
    context: Context,
    private val devices: AndroidAudioDevices,
) : MediaRoutingPort, MultiOutputPort, PlaybackPort {

    private val sampleRate = 48_000
    private val chunkFrames = 480 // 10 ms
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private inner class Sink(val target: EndpointId?, device: AudioDeviceInfo?) {
        val track: AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(mediaAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 4 / 5)) // ~200 ms of 16-bit stereo
            .build()
        val delay = DelayLine(sampleRate)

        @Volatile var gain = 1f

        @Volatile var routed: EndpointId? = null

        private val listener = AudioRouting.OnRoutingChangedListener { router ->
            routed = router.routedDevice?.let(AndroidIds::endpointId)
            publishRoutes()
        }

        init {
            device?.let { track.setPreferredDevice(it) }
            track.addOnRoutingChangedListener(listener, Handler(Looper.getMainLooper()))
        }

        fun release() {
            track.removeOnRoutingChangedListener(listener)
            track.release()
        }
    }

    private val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
    private val lock = Any()
    private var single: Sink? = null
    private var multi: List<Sink> = emptyList()

    private val singleObserved = MutableStateFlow<List<EndpointId>>(emptyList())
    private val multiObserved = MutableStateFlow<List<EndpointId>>(emptyList())

    // region playback state

    override val library: List<MediaItemRef> = listOf(
        MediaItemRef("tone-1k", "Test tone · 1 kHz", 30_000),
        MediaItemRef("sweep", "Frequency sweep 20 Hz–20 kHz", 45_000),
        MediaItemRef("party-mix", "Party beat (124 BPM)", 214_000),
    )

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    @Volatile private var source: PcmSource? = null

    @Volatile private var position = 0L

    @Volatile private var playing = false

    @Volatile private var primeUntilNanos = 0L
    private var renderer: Thread? = null

    private fun sourceFor(item: MediaItemRef): PcmSource = when (item.id) {
        "tone-1k" -> ToneSource(item, sampleRate, 1_000.0)
        "sweep" -> SweepSource(item, sampleRate)
        else -> BeatSource(item, sampleRate)
    }

    override suspend fun load(item: MediaItemRef) {
        source = sourceFor(item)
        position = 0
        _status.value = PlaybackStatus(item = item, durationMs = item.durationMs)
    }

    override fun play() {
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Refused while a call holds focus; the call interruption itself is reported via CallObserverPort.
            _status.update { it.copy(playing = false, error = "Playback blocked: a call or another app is using audio") }
            return
        }
        resumeOnFocusGain = false
        startPlayback()
    }

    override fun pause() {
        resumeOnFocusGain = false
        stopPlayback()
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun startPlayback() {
        if (source == null) {
            val first = library.first()
            source = sourceFor(first)
            _status.value = PlaybackStatus(item = first, durationMs = first.durationMs)
        }
        playing = true
        _status.update { it.copy(playing = true, error = null) }
        ensureRenderer()
    }

    private fun stopPlayback() {
        playing = false
        _status.update { it.copy(playing = false) }
    }

    override fun seekTo(positionMs: Long) {
        val src = source ?: return
        position = (positionMs.coerceIn(0, src.item.durationMs) * sampleRate / 1000)
        _status.update { it.copy(positionMs = position * 1000 / sampleRate) }
    }

    // endregion

    // region audio focus (RTE-007)

    @Volatile private var resumeOnFocusGain = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(mediaAttributes)
        .setOnAudioFocusChangeListener(::onFocusChange, Handler(Looper.getMainLooper()))
        .build()

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                startPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) {
                resumeOnFocusGain = true
                stopPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS -> pause()
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: the system ducks media itself on Android 12+ (minSdk 31).
        }
    }

    // endregion

    // region MediaRoutingPort (single output)

    override suspend fun requestOutput(endpoint: EndpointId): RouteCommandResult {
        val device = devices.mediaDevice(endpoint) ?: return RouteCommandResult.Rejected(RouteError.DeviceGone)
        synchronized(lock) {
            val sink = single
            if (sink == null || sink.target != endpoint) {
                sink?.release()
                single = Sink(endpoint, device)
            } else if (!sink.track.setPreferredDevice(device)) {
                return RouteCommandResult.Rejected(RouteError.OsRejected("AudioTrack.setPreferredDevice returned false"))
            }
        }
        prime()
        return RouteCommandResult.Submitted
    }

    override suspend fun clearPreference() {
        synchronized(lock) {
            single?.release()
            single = null
        }
        publishRoutes()
    }

    override val observedRoute: Flow<List<EndpointId>> = singleObserved

    // endregion

    // region MultiOutputPort

    override suspend fun start(targets: List<EndpointId>): RouteCommandResult {
        val resolved = targets.map { id -> id to (devices.mediaDevice(id) ?: return RouteCommandResult.Rejected(RouteError.DeviceGone)) }
        synchronized(lock) {
            multi.forEach { it.release() }
            single?.track?.pause()
            multi = resolved.map { (id, dev) -> Sink(id, dev) }
        }
        publishRoutes()
        prime()
        return RouteCommandResult.Submitted
    }

    override suspend fun stop() {
        synchronized(lock) {
            multi.forEach { it.release() }
            multi = emptyList()
        }
        publishRoutes()
    }

    override fun setDelay(endpoint: EndpointId, ms: Int) {
        synchronized(lock) { multi.firstOrNull { it.target == endpoint }?.delay?.setMs(ms) }
    }

    override fun setGain(endpoint: EndpointId, gain: Float) {
        synchronized(lock) { multi.firstOrNull { it.target == endpoint }?.gain = gain.coerceIn(0f, 1f) }
    }

    override val observedRoutes: Flow<List<EndpointId>> = multiObserved

    // endregion

    /** A sink must be playing for the OS to report its route, so new routes are confirmed by briefly playing silence. */
    private fun prime() {
        primeUntilNanos = System.nanoTime() + 1_500_000_000L
        ensureRenderer()
    }

    private fun publishRoutes() {
        synchronized(lock) {
            singleObserved.value = listOfNotNull(single?.routed)
            // A multi sink counts only when the OS routed it to the device it was meant for.
            multiObserved.value = multi.filter { it.routed != null && it.routed == it.target }.mapNotNull { it.target }
        }
    }

    private fun activeSinks(): List<Sink> = synchronized(lock) {
        multi.ifEmpty {
            val s = single ?: Sink(null, null).also { single = it }
            listOf(s)
        }
    }

    @Synchronized
    private fun ensureRenderer() {
        if (renderer?.isAlive == true) return
        renderer = thread(name = "partymusik-render", isDaemon = true) { renderLoop() }
    }

    private fun renderLoop() {
        val buf = ShortArray(chunkFrames * 2)
        val out = ShortArray(chunkFrames * 2)
        var lastStatus = 0L
        while (true) {
            val src = source
            val active = playing && src != null
            val priming = System.nanoTime() < primeUntilNanos
            if (!active && !priming) {
                synchronized(lock) { (multi + listOfNotNull(single)).forEach { if (it.track.playState == AudioTrack.PLAYSTATE_PLAYING) it.track.pause() } }
                Thread.sleep(20)
                continue
            }
            if (active) {
                src!!.render(buf, position, chunkFrames)
                position += chunkFrames
                if (position >= src.durationFrames) {
                    position = 0
                    playing = false
                    _status.update { it.copy(playing = false, positionMs = 0) }
                    audioManager.abandonAudioFocusRequest(focusRequest)
                }
            } else {
                buf.fill(0)
            }
            for (sink in activeSinks()) {
                if (sink.track.playState != AudioTrack.PLAYSTATE_PLAYING) sink.track.play()
                sink.delay.process(buf, buf.size, out, sink.gain)
                sink.track.write(out, 0, out.size, AudioTrack.WRITE_BLOCKING)
            }
            val now = System.nanoTime()
            if (active && now - lastStatus > 250_000_000L) {
                lastStatus = now
                _status.update { it.copy(positionMs = position * 1000 / sampleRate) }
            }
        }
    }
}
