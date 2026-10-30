package io.mahajan.partymusik.android

import io.mahajan.partymusik.ports.MediaItemRef
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural PCM sources for the POC library. Routing spikes need deterministic, recognisable audio;
 * decoding user files (MediaExtractor/MediaCodec or Media3) plugs in behind the same interface.
 */
interface PcmSource {
    val item: MediaItemRef
    val sampleRate: Int
    /** Renders [frames] interleaved stereo frames starting at [startFrame]. */
    fun render(out: ShortArray, startFrame: Long, frames: Int)
    val durationFrames: Long get() = item.durationMs * sampleRate / 1000
}

class ToneSource(override val item: MediaItemRef, override val sampleRate: Int, private val hz: Double) : PcmSource {
    override fun render(out: ShortArray, startFrame: Long, frames: Int) {
        for (i in 0 until frames) {
            val v = (sin(2 * PI * hz * (startFrame + i) / sampleRate) * 0.3 * Short.MAX_VALUE).toInt().toShort()
            out[2 * i] = v
            out[2 * i + 1] = v
        }
    }
}

class SweepSource(override val item: MediaItemRef, override val sampleRate: Int) : PcmSource {
    override fun render(out: ShortArray, startFrame: Long, frames: Int) {
        val total = durationFrames.toDouble()
        for (i in 0 until frames) {
            val n = startFrame + i
            val t = n.toDouble() / sampleRate
            // exponential sweep 20 Hz → 20 kHz over the item duration
            val k = kotlin.math.ln(1000.0) / (total / sampleRate)
            val phase = 2 * PI * 20.0 * (exp(k * t) - 1) / k
            val v = (sin(phase) * 0.25 * Short.MAX_VALUE).toInt().toShort()
            out[2 * i] = v
            out[2 * i + 1] = v
        }
    }
}

/** 124 BPM kick + hi-hat pattern: easy to hear echo between outputs during multi-output tests. */
class BeatSource(override val item: MediaItemRef, override val sampleRate: Int) : PcmSource {
    private val beatFrames = (sampleRate * 60.0 / 124).toLong()
    private val noise = Random(3)

    override fun render(out: ShortArray, startFrame: Long, frames: Int) {
        for (i in 0 until frames) {
            val n = startFrame + i
            val inBeat = n % beatFrames
            val t = inBeat.toDouble() / sampleRate
            val kick = sin(2 * PI * (55 + 90 * exp(-t * 30)) * t) * exp(-t * 9)
            val hatPos = (n + beatFrames / 2) % beatFrames
            val hat = if (hatPos < sampleRate / 40) (noise.nextDouble() * 2 - 1) * exp(-hatPos.toDouble() / sampleRate * 120) * 0.25 else 0.0
            val left = ((kick * 0.6 + hat) * Short.MAX_VALUE * 0.5).toInt().coerceIn(-32768, 32767).toShort()
            val right = ((kick * 0.6 + hat * 0.7) * Short.MAX_VALUE * 0.5).toInt().coerceIn(-32768, 32767).toShort()
            out[2 * i] = left
            out[2 * i + 1] = right
        }
    }
}

/** Fixed per-sink delay for multi-output sync compensation (MED-007). */
class DelayLine(private val sampleRate: Int, maxMs: Int = 2_000) {
    private val ring = ShortArray(sampleRate * maxMs / 1000 * 2)
    private var write = 0

    @Volatile
    var delaySamples = 0
        private set

    fun setMs(ms: Int) {
        delaySamples = (ms.coerceIn(0, ring.size / 2 * 1000 / sampleRate - 1) * sampleRate / 1000) * 2
    }

    fun process(input: ShortArray, count: Int, out: ShortArray, gain: Float) {
        for (i in 0 until count) {
            ring[write] = input[i]
            var read = write - delaySamples
            if (read < 0) read += ring.size
            out[i] = (ring[read] * gain).toInt().coerceIn(-32768, 32767).toShort()
            write = (write + 1) % ring.size
        }
    }
}
