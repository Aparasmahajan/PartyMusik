package io.mahajan.partymusik.diagnostics

import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.ports.PlatformInfo
import io.mahajan.partymusik.sync.OutputLatency
import io.mahajan.partymusik.sync.SyncReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpikeDataTest {

    private val info = PlatformInfo("TestOS", "1", "Phone", supportsSplitMediaAndComm = true, supportsMultiOutput = true)

    @Test
    fun percentile_is_nearest_rank() {
        val values = (1..20).map { it.toDouble() }
        assertEquals(10.0, SpikeData.percentile(values, 50.0))
        assertEquals(19.0, SpikeData.percentile(values, 95.0))
        assertEquals(7.0, SpikeData.percentile(listOf(7.0), 95.0))
        assertNull(SpikeData.percentile(emptyList(), 50.0))
    }

    @Test
    fun summary_derives_spike_metrics_from_the_event_log() {
        val d = Diagnostics(io.mahajan.partymusik.platform.Clock { 0 }, salt = "s")
        listOf(100, 300, 200).forEach {
            d.log("ROUTE_CONFIRMED", ChannelType.MEDIA, mapOf("targets" to "Sony", "latencyMs" to "$it"))
        }
        d.log("ROUTE_CONFIRMED", ChannelType.COMMUNICATION, mapOf("targets" to "Car", "latencyMs" to "900"))
        d.log("RTE_TIMEOUT", ChannelType.MEDIA, mapOf("targets" to "JBL"))
        d.log("ROUTE_REJECTED", ChannelType.MEDIA, mapOf("error" to "RTE_DEVICE_GONE"))
        d.log("PRESENCE_EVENT", null, mapOf("event" to "appeared"))
        d.log("AUTOMATION_TRIGGERED", null, mapOf("source" to "os-presence"))
        val spike = SpikeData(d)

        val summary = spike.summary().associateBy { it.metric }
        val media = summary.getValue("Media route: request → OS-confirmed (ms)")
        assertEquals(3, media.n)
        assertEquals(200.0, media.p50)
        assertEquals(300.0, media.max)
        assertEquals(900.0, summary.getValue("Comm route: request → OS-confirmed (ms)").p50)
        val failures = summary.getValue("Route requests not confirmed")
        assertEquals(2, failures.n)
        assertTrue("RTE_TIMEOUT×1" in failures.note && "RTE_DEVICE_GONE×1" in failures.note)
        assertEquals(1, summary.getValue("OS presence events received").n)
        assertEquals("os-presence×1", summary.getValue("Automation triggers fired").note)
        assertEquals(0, summary.getValue("Engine errors (must be 0)").n)
    }

    @Test
    fun csv_export_escapes_values_and_hashes_names_on_request() {
        val d = Diagnostics(io.mahajan.partymusik.platform.Clock { 42 }, salt = "s")
        d.log("ROUTE_CONFIRMED", ChannelType.MEDIA, mapOf("targets" to "Alex's \"Pods\", JBL", "latencyMs" to "150"))
        val spike = SpikeData(d)
        val sony = EndpointId("sony")
        spike.record(SyncReport(7, listOf(OutputLatency(sony, 220.0, 4.5, listOf(219.0, 221.0))), failed = emptyList()))

        val plain = spike.exportCsv(info, { "Sony XM5" }, anonymizeNames = false)
        assertEquals(setOf("device.csv", "summary.csv", "events.csv", "route_latency.csv", "sync.csv", "compat.csv"), plain.keys)
        val row = plain.getValue("route_latency.csv").lines()[1]
        assertEquals("42,MEDIA,CONFIRMED,150,\"Alex's \"\"Pods\"\", JBL\"", row)
        assertEquals("7,Sony XM5,220,4.5,0,219 221", plain.getValue("sync.csv").lines()[1])

        val anon = spike.exportCsv(info, { "Sony XM5" }, anonymizeNames = true)
        anon.values.forEach { text ->
            assertFalse("Alex" in text, text)
            assertFalse("Sony XM5" in text, text)
        }
    }
}
