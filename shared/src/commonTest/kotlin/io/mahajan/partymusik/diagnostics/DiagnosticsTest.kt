package io.mahajan.partymusik.diagnostics

import io.mahajan.partymusik.platform.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {

    @Test
    fun hardware_addresses_are_hashed_at_write_time() {
        val d = Diagnostics(Clock { 0 }, salt = "s1")
        d.log("DEVICE_CONNECTED", null, mapOf("device" to "Sony (AC:80:0A:12:34:56)"))
        val stored = d.events.value.single().detail.getValue("device")
        assertFalse("AC:80:0A:12:34:56" in stored)
        assertTrue(stored.startsWith("Sony (addr#"))
        // Same address + salt → same pseudonym, so support can correlate events.
        d.log("X", null, mapOf("device" to "ac:80:0a:12:34:56"))
        assertEquals(stored.removePrefix("Sony (").removeSuffix(")"), d.events.value.last().detail.getValue("device"))
    }

    @Test
    fun export_can_anonymize_device_names() {
        val d = Diagnostics(Clock { 0 }, salt = "s1")
        d.log("ROUTE_CONFIRMED", null, mapOf("targets" to "Alex's AirPods, JBL Charge 6", "latencyMs" to "120"))
        val plain = d.export(anonymizeNames = false)
        val anon = d.export(anonymizeNames = true)
        assertTrue("Alex's AirPods" in plain)
        assertFalse("Alex's AirPods" in anon)
        assertTrue("latencyMs=\"120\"" in anon)
    }

    @Test
    fun ring_buffer_is_bounded() {
        val d = Diagnostics(Clock { 0 }, salt = "", capacity = 10)
        repeat(25) { d.log("E$it") }
        assertEquals(10, d.events.value.size)
        assertEquals("E24", d.events.value.last().code)
    }

    @Test
    fun every_engine_error_code_has_help() {
        listOf("RTE_TIMEOUT", "RTE_OS_REDIRECTED", "RTE_DEVICE_GONE", "RTE_NOT_MEDIA_CAPABLE", "RTE_NOT_COMM_CAPABLE",
            "RTE_UNSUPPORTED_COMBO", "RTE_SESSION_CONFLICT", "BT_OFF", "PERM_MICROPHONE_DENIED").forEach { code ->
            assertTrue(Troubleshooting.articleFor(code).title != "Something went wrong", code)
        }
    }
}
