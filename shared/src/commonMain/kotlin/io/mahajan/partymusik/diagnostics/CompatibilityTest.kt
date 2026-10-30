package io.mahajan.partymusik.diagnostics

import io.mahajan.partymusik.domain.Capability
import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.domain.PermissionStatus
import io.mahajan.partymusik.domain.RequestSource
import io.mahajan.partymusik.domain.RouteState
import io.mahajan.partymusik.engine.RoutingEngine
import io.mahajan.partymusik.engine.targets
import io.mahajan.partymusik.platform.Clock
import io.mahajan.partymusik.ports.PlatformPorts
import kotlinx.coroutines.flow.first

enum class CompatOutcome { PASS, FAIL, SKIPPED, INFO }

data class CompatResult(
    val testId: String,
    val subject: String,
    val outcome: CompatOutcome,
    val detail: String = "",
    val errorCode: String? = null,
    val latencyMs: Long? = null,
)

data class CompatReport(val platform: String, val startedAt: Long, val finishedAt: Long, val results: List<CompatResult>) {
    val passed: Int get() = results.count { it.outcome == CompatOutcome.PASS }
    val failed: Int get() = results.count { it.outcome == CompatOutcome.FAIL }
}

/**
 * DIA-002: exercises the current phone and connected accessories through the real routing engine and
 * records what the OS actually confirmed. Restores the previous media route afterwards.
 */
class CompatibilityTest(
    private val ports: PlatformPorts,
    private val engine: RoutingEngine,
    private val diagnostics: Diagnostics,
    private val clock: Clock,
    private val onReport: (CompatReport) -> Unit = {},
) {
    suspend fun run(onProgress: (CompatResult) -> Unit = {}): CompatReport {
        val started = clock.nowMillis()
        val results = mutableListOf<CompatResult>()
        fun add(r: CompatResult) {
            results += r
            onProgress(r)
        }
        val info = ports.info
        add(CompatResult("platform", "${info.model} · ${info.os} ${info.osVersion}", CompatOutcome.INFO))
        add(CompatResult("bt_state", "Bluetooth", CompatOutcome.INFO, ports.bluetooth.adapterState.first().name))
        ports.permissions.statuses.value.forEach { (perm, status) ->
            add(
                CompatResult(
                    "permission", perm.name,
                    if (status == PermissionStatus.GRANTED || status == PermissionStatus.NOT_REQUIRED) CompatOutcome.PASS else CompatOutcome.FAIL,
                    status.name, errorCode = if (status == PermissionStatus.DENIED) "PERM_${perm.name}_DENIED" else null,
                ),
            )
        }

        val snapshot = engine.state.value
        val previousMedia = snapshot.media.state.targets()
        val timeout = snapshot.policy.confirmTimeoutMs + 1_000

        val media = snapshot.endpoints.filter { it.canCarry(ChannelType.MEDIA) }
        for (ep in media) add(routeCheck("media_route", ep.name, ChannelType.MEDIA, listOf(ep.id), timeout))

        val comm = snapshot.endpoints.filter { it.canCarry(ChannelType.COMMUNICATION) }
        for (ep in comm) add(routeCheck("comm_route", ep.name, ChannelType.COMMUNICATION, listOf(ep.id), timeout))
        if (comm.isNotEmpty()) engine.release(ChannelType.COMMUNICATION)

        val members = media.filter { Capability.MULTI_OUTPUT_MEMBER in it.capabilities }
        if (info.supportsMultiOutput && members.size >= 2) {
            val pair = members.take(2)
            add(routeCheck("multi_output", pair.joinToString(" + ") { it.name }, ChannelType.MEDIA, pair.map { it.id }, timeout))
        } else {
            add(CompatResult("multi_output", "Multi-output", CompatOutcome.SKIPPED, "Needs two connected multi-output-capable devices"))
        }

        if (previousMedia.isNotEmpty()) {
            engine.requestAndAwait(ChannelType.MEDIA, previousMedia, RequestSource.PROFILE, timeout)
        } else {
            engine.release(ChannelType.MEDIA)
        }

        val report = CompatReport("${info.os} ${info.osVersion} ${info.model}", started, clock.nowMillis(), results)
        diagnostics.log("COMPAT_TEST_DONE", null, mapOf("passed" to report.passed.toString(), "failed" to report.failed.toString()))
        onReport(report)
        return report
    }

    private suspend fun routeCheck(
        id: String,
        subject: String,
        channel: ChannelType,
        targets: List<EndpointId>,
        timeoutMs: Long,
    ): CompatResult {
        val t0 = clock.nowMillis()
        // PROFILE source: test requests must not set the user's manual override.
        val result = engine.requestAndAwait(channel, targets, RequestSource.PROFILE, timeoutMs)
        val latency = clock.nowMillis() - t0
        return when (result) {
            is RouteState.Confirmed -> CompatResult(id, subject, CompatOutcome.PASS, "Confirmed by system", latencyMs = latency)
            is RouteState.Rejected -> CompatResult(id, subject, CompatOutcome.FAIL, result.error.message, result.error.code, latency)
            null -> CompatResult(id, subject, CompatOutcome.FAIL, "No confirmation", "RTE_TIMEOUT", latency)
            else -> CompatResult(id, subject, CompatOutcome.FAIL, result::class.simpleName.orEmpty(), latencyMs = latency)
        }
    }
}
