package io.mahajan.partymusik.sync

import io.mahajan.partymusik.diagnostics.Diagnostics
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.ports.MultiOutputPort
import io.mahajan.partymusik.ports.SyncProbePort
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

data class OutputLatency(
    val endpoint: EndpointId,
    val medianMs: Double,
    val iqrMs: Double,
    val samplesMs: List<Double>,
)

data class SyncReport(val timestamp: Long, val outputs: List<OutputLatency>, val failed: List<EndpointId>) {
    /** Spread between the fastest and slowest output before compensation. */
    val skewMs: Double get() = if (outputs.size < 2) 0.0 else outputs.maxOf { it.medianMs } - outputs.minOf { it.medianMs }

    /** Delay to add to each output so all align with the slowest one. */
    val compensationMs: Map<EndpointId, Int>
        get() {
            val slowest = outputs.maxOfOrNull { it.medianMs } ?: return emptyMap()
            return outputs.associate { it.endpoint to (slowest - it.medianMs).roundToInt() }
        }
}

/**
 * Acoustic latency measurement for multi-output playback (MED-007, docs/06 §7).
 * A short windowed chirp is played on one output at a time and located in the mic recording by
 * cross-correlation. Recordings stay in memory and are discarded after analysis (DIA-006).
 */
class SyncAnalyzer(
    private val probePort: SyncProbePort,
    private val multiOutput: MultiOutputPort,
    private val diagnostics: Diagnostics,
    private val onReport: (SyncReport) -> Unit = {},
    private val now: () -> Long,
) {
    suspend fun measure(outputs: List<EndpointId>, repeats: Int = 5, sampleRate: Int = 16_000): SyncReport {
        val signal = chirp(sampleRate)
        val results = mutableListOf<OutputLatency>()
        val failed = mutableListOf<EndpointId>()
        for (endpoint in outputs) {
            val samples = (1..repeats).mapNotNull {
                val capture = probePort.probe(endpoint, signal, sampleRate) ?: return@mapNotNull null
                val arrival = findArrival(capture.recording, signal)
                (arrival - capture.emitFrame) * 1000.0 / capture.sampleRate
            }
            if (samples.isEmpty()) failed += endpoint else results += summarize(endpoint, samples)
        }
        val report = SyncReport(now(), results, failed)
        diagnostics.log(
            "SYNC_MEASURED", null,
            mapOf("outputs" to results.size.toString(), "skewMs" to report.skewMs.roundToInt().toString(), "failed" to failed.size.toString()),
        )
        onReport(report)
        return report
    }

    fun applyCompensation(report: SyncReport) {
        report.compensationMs.forEach { (id, ms) -> multiOutput.setDelay(id, ms) }
        diagnostics.log("SYNC_COMPENSATION_APPLIED", null, mapOf("outputs" to report.compensationMs.size.toString()))
    }

    companion object {
        /** Hann-windowed linear chirp, [f0]→[f1] Hz. */
        fun chirp(sampleRate: Int, durationMs: Int = 20, f0: Double = 2_000.0, f1: Double = 8_000.0): FloatArray {
            val n = sampleRate * durationMs / 1000
            val t = durationMs / 1000.0
            val k = (f1 - f0) / t
            return FloatArray(n) { i ->
                val x = i.toDouble() / sampleRate
                val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                (window * sin(2 * PI * (f0 * x + 0.5 * k * x * x))).toFloat()
            }
        }

        /** Index in [recording] where [signal] best matches (argmax of cross-correlation). */
        fun findArrival(recording: FloatArray, signal: FloatArray): Int {
            var best = 0
            var bestScore = Double.NEGATIVE_INFINITY
            for (lag in 0..(recording.size - signal.size)) {
                var acc = 0.0
                for (i in signal.indices) acc += recording[lag + i] * signal[i]
                if (acc > bestScore) {
                    bestScore = acc
                    best = lag
                }
            }
            return best
        }

        fun summarize(endpoint: EndpointId, samples: List<Double>): OutputLatency {
            val sorted = samples.sorted()
            fun q(p: Double): Double {
                val pos = p * (sorted.size - 1)
                val lo = pos.toInt()
                val hi = minOf(lo + 1, sorted.size - 1)
                return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
            }
            return OutputLatency(endpoint, q(0.5), q(0.75) - q(0.25), samples)
        }
    }
}
