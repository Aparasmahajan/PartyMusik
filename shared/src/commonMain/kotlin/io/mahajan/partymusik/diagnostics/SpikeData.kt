package io.mahajan.partymusik.diagnostics

import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.domain.EndpointId
import io.mahajan.partymusik.ports.PlatformInfo
import io.mahajan.partymusik.sync.SyncReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.ceil

/** One row of the spike summary; numeric columns are null when the metric is a plain count. */
data class SpikeMetric(
    val spike: String,
    val metric: String,
    val n: Int,
    val p50: Double? = null,
    val p95: Double? = null,
    val max: Double? = null,
    val note: String = "",
)

/**
 * Raw measurements from device-lab spike runs (POC deliverable #4, docs/16). Collects sync and compatibility
 * reports, derives per-spike metrics from the diagnostics log, and exports everything as CSV.
 * Same privacy rules as [Diagnostics.export]: codes and numbers only, device names hashed on request.
 */
class SpikeData(private val diagnostics: Diagnostics) {
    private val _syncReports = MutableStateFlow<List<SyncReport>>(emptyList())
    val syncReports: StateFlow<List<SyncReport>> = _syncReports.asStateFlow()

    private val _compatReports = MutableStateFlow<List<CompatReport>>(emptyList())
    val compatReports: StateFlow<List<CompatReport>> = _compatReports.asStateFlow()

    fun record(report: SyncReport) = _syncReports.update { (it + report).takeLast(MAX_REPORTS) }

    fun record(report: CompatReport) = _compatReports.update { (it + report).takeLast(MAX_REPORTS) }

    fun clear() {
        _syncReports.value = emptyList()
        _compatReports.value = emptyList()
        diagnostics.clear()
    }

    fun summary(): List<SpikeMetric> {
        val events = diagnostics.events.value
        fun count(code: String) = events.count { it.code == code }
        fun latencies(channel: ChannelType) = events
            .filter { it.code == "ROUTE_CONFIRMED" && it.channel == channel }
            .mapNotNull { it.detail["latencyMs"]?.toDoubleOrNull() }
        fun breakdown(values: List<String>) =
            values.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString("; ") { "${it.key}×${it.value}" }

        val failures = events.mapNotNull { e ->
            when {
                e.code == "ROUTE_REJECTED" -> e.detail["error"] ?: e.code
                e.code.startsWith("RTE_") -> e.code
                else -> null
            }
        }
        val presence = events.filter { it.code == "PRESENCE_EVENT" }
        val triggers = events.filter { it.code == "AUTOMATION_TRIGGERED" }
        val skews = _syncReports.value.filter { it.outputs.size >= 2 }.map { it.skewMs }
        val lastCompat = _compatReports.value.lastOrNull()

        return listOf(
            stats("S-04/S-05", "Media route: request → OS-confirmed (ms)", latencies(ChannelType.MEDIA)),
            stats("S-06/S-07/S-11/S-12", "Comm route: request → OS-confirmed (ms)", latencies(ChannelType.COMMUNICATION)),
            SpikeMetric("S-04…S-07", "Route requests not confirmed", failures.size, note = breakdown(failures)),
            SpikeMetric("S-08/S-09", "Multi-output requests only partly confirmed", count("MULTI_PARTIAL")),
            stats("S-10", "Multi-output skew before compensation (ms)", skews),
            SpikeMetric(
                "S-10", "Sync outputs measured / failed",
                _syncReports.value.sumOf { it.outputs.size },
                note = "failed=${_syncReports.value.sumOf { it.failed.size }}",
            ),
            SpikeMetric("S-13/S-14", "OS presence events received", presence.size, note = breakdown(presence.mapNotNull { it.detail["event"] })),
            SpikeMetric("S-13/S-14", "Automation triggers fired", triggers.size, note = breakdown(triggers.mapNotNull { it.detail["source"] })),
            SpikeMetric("S-16", "Cellular call state changes seen", count("CELLULAR_CALL_STATE")),
            SpikeMetric(
                "S-16", "Communication sessions started", count("COMM_SESSION_STARTED"),
                note = breakdown(events.filter { it.code == "COMM_SESSION_STARTED" }.mapNotNull { it.detail["session"] }),
            ),
            SpikeMetric("S-18", "Device connect / disconnect events", count("DEVICE_CONNECTED") + count("DEVICE_DISCONNECTED")),
            SpikeMetric("S-18", "Fallbacks applied", count("FALLBACK_APPLIED"), note = "exhausted=${count("FALLBACK_EXHAUSTED")}"),
            SpikeMetric("S-18", "Engine errors (must be 0)", count("ENGINE_REDUCER_ERROR")),
            SpikeMetric(
                "DIA-002", "Compatibility test (last run)", lastCompat?.results?.size ?: 0,
                note = lastCompat?.let { "pass=${it.passed}; fail=${it.failed}" } ?: "not run",
            ),
        )
    }

    /** CSV files keyed by file name. [names] maps endpoint ids in sync reports to display names. */
    fun exportCsv(info: PlatformInfo, names: (EndpointId) -> String, anonymizeNames: Boolean): Map<String, String> {
        fun name(id: EndpointId) = names(id).let { if (anonymizeNames) Redaction.hashNames(it, diagnostics.salt) else it }
        val events = diagnostics.events.value
        return mapOf(
            "device.csv" to csv(
                listOf("os", "osVersion", "model", "splitMediaAndComm", "multiOutput"),
                listOf(listOf(info.os, info.osVersion, info.model, info.supportsSplitMediaAndComm.toString(), info.supportsMultiOutput.toString())),
            ),
            "summary.csv" to csv(
                listOf("spike", "metric", "n", "p50", "p95", "max", "note"),
                summary().map { m -> listOf(m.spike, m.metric, m.n.toString(), num(m.p50), num(m.p95), num(m.max), m.note) },
            ),
            "events.csv" to csv(
                listOf("seq", "timestamp", "channel", "code", "detail"),
                events.map { e ->
                    val detail = diagnostics.detailFor(e, anonymizeNames).entries.joinToString("; ") { (k, v) -> "$k=$v" }
                    listOf(e.seq.toString(), e.timestamp.toString(), e.channel?.name.orEmpty(), e.code, detail)
                },
            ),
            "route_latency.csv" to csv(
                listOf("timestamp", "channel", "outcome", "latencyMs", "targets"),
                events.mapNotNull { e ->
                    val outcome = when {
                        e.code == "ROUTE_CONFIRMED" -> "CONFIRMED"
                        e.code.startsWith("RTE_") -> e.code
                        e.code == "ROUTE_REJECTED" -> e.detail["error"] ?: e.code
                        else -> return@mapNotNull null
                    }
                    val detail = diagnostics.detailFor(e, anonymizeNames)
                    listOf(e.timestamp.toString(), e.channel?.name.orEmpty(), outcome, detail["latencyMs"].orEmpty(), detail["targets"].orEmpty())
                },
            ),
            "sync.csv" to csv(
                listOf("timestamp", "output", "medianMs", "iqrMs", "compensationMs", "samplesMs"),
                _syncReports.value.flatMap { r ->
                    r.outputs.map { o ->
                        listOf(
                            r.timestamp.toString(), name(o.endpoint), num(o.medianMs), num(o.iqrMs),
                            r.compensationMs[o.endpoint]?.toString().orEmpty(), o.samplesMs.joinToString(" ") { num(it) },
                        )
                    } + r.failed.map { listOf(r.timestamp.toString(), name(it), "", "", "", "FAILED") }
                },
            ),
            "compat.csv" to csv(
                listOf("startedAt", "testId", "subject", "outcome", "errorCode", "latencyMs", "detail"),
                _compatReports.value.flatMap { r ->
                    r.results.map { c ->
                        val subject = if (anonymizeNames && c.testId in DEVICE_TESTS) Redaction.hashNames(c.subject, diagnostics.salt) else c.subject
                        listOf(r.startedAt.toString(), c.testId, subject, c.outcome.name, c.errorCode.orEmpty(), c.latencyMs?.toString().orEmpty(), c.detail)
                    }
                },
            ),
        )
    }

    companion object {
        private const val MAX_REPORTS = 200
        private val DEVICE_TESTS = setOf("media_route", "comm_route", "multi_output")

        /** Nearest-rank percentile of [values]; null when empty. */
        fun percentile(values: List<Double>, p: Double): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            return sorted[(ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]
        }

        internal fun stats(spike: String, metric: String, values: List<Double>) =
            SpikeMetric(spike, metric, values.size, percentile(values, 50.0), percentile(values, 95.0), values.maxOrNull())

        internal fun csv(header: List<String>, rows: List<List<String>>): String = buildString {
            appendLine(header.joinToString(",") { escape(it) })
            rows.forEach { row -> appendLine(row.joinToString(",") { escape(it) }) }
        }

        private fun escape(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value

        private fun num(value: Double?): String = when {
            value == null -> ""
            value == value.toLong().toDouble() -> value.toLong().toString()
            else -> (kotlin.math.round(value * 10) / 10).toString()
        }
    }
}
