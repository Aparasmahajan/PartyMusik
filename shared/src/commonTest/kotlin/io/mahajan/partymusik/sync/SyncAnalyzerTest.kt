package io.mahajan.partymusik.sync

import io.mahajan.partymusik.domain.EndpointId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncAnalyzerTest {

    @Test
    fun finds_chirp_position_in_noisy_recording() {
        val sr = 16_000
        val signal = SyncAnalyzer.chirp(sr)
        val rnd = Random(7)
        listOf(0, 37, 1_234, 3_999).forEach { offset ->
            val rec = FloatArray(offset + signal.size + 500) { rnd.nextFloat() * 0.1f - 0.05f }
            signal.forEachIndexed { i, v -> rec[offset + i] += v * 0.5f }
            assertEquals(offset, SyncAnalyzer.findArrival(rec, signal))
        }
    }

    @Test
    fun summary_and_compensation_align_to_slowest_output() {
        val a = SyncAnalyzer.summarize(EndpointId("a"), listOf(100.0, 102.0, 98.0, 101.0, 99.0))
        val b = SyncAnalyzer.summarize(EndpointId("b"), listOf(220.0, 221.0, 219.0))
        assertEquals(100.0, a.medianMs)
        assertEquals(2.0, a.iqrMs)
        val report = SyncReport(0, listOf(a, b), emptyList())
        assertEquals(120.0, report.skewMs)
        assertEquals(mapOf(EndpointId("a") to 120, EndpointId("b") to 0), report.compensationMs)
    }

    @Test
    fun chirp_is_windowed() {
        val c = SyncAnalyzer.chirp(48_000)
        assertEquals(960, c.size)
        assertTrue(kotlin.math.abs(c.first()) < 1e-6 && kotlin.math.abs(c.last()) < 1e-3)
    }
}
