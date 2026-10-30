package io.mahajan.partymusik.diagnostics

import io.mahajan.partymusik.domain.ChannelType
import io.mahajan.partymusik.platform.Clock
import io.mahajan.partymusik.platform.TextStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DiagEvent(
    val seq: Long,
    val timestamp: Long,
    val code: String,
    val channel: ChannelType?,
    val detail: Map<String, String>,
)

/**
 * In-memory ring buffer of technical events (DIA-003).
 * Records codes and state transitions only — never audio. Hardware addresses are hashed at write time.
 */
class Diagnostics(
    private val clock: Clock,
    internal val salt: String,
    private val capacity: Int = 5_000,
) {
    private val _events = MutableStateFlow<List<DiagEvent>>(emptyList())
    val events: StateFlow<List<DiagEvent>> = _events.asStateFlow()

    fun log(code: String, channel: ChannelType? = null, detail: Map<String, String> = emptyMap()) {
        val redacted = detail.mapValues { (_, v) -> Redaction.redactAddresses(v, salt) }
        _events.update { list ->
            val seq = (list.lastOrNull()?.seq ?: 0) + 1
            (list + DiagEvent(seq, clock.nowMillis(), code, channel, redacted)).takeLast(capacity)
        }
    }

    fun clear() = _events.update { emptyList() }

    /** Replaces the buffer with events restored from storage (oldest first); new events continue the sequence. */
    fun restore(saved: List<DiagEvent>) {
        _events.value = saved.takeLast(capacity)
    }

    /** Plain-text export for support; [anonymizeNames] replaces device names with stable hashes. */
    fun export(anonymizeNames: Boolean, header: Map<String, String> = emptyMap()): String = buildString {
        appendLine("PartyMusik diagnostics export")
        header.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine("events: ${_events.value.size}")
        appendLine()
        _events.value.forEach { e ->
            val detail = detailFor(e, anonymizeNames).entries.joinToString(" ") { (k, v) -> "$k=\"$v\"" }
            appendLine("${e.seq}\t${e.timestamp}\t${e.channel ?: "-"}\t${e.code}\t$detail")
        }
    }

    /** Event detail as exported: device names hashed when [anonymizeNames] is set. */
    internal fun detailFor(e: DiagEvent, anonymizeNames: Boolean): Map<String, String> =
        if (!anonymizeNames) e.detail else e.detail.mapValues { (k, v) -> if (k in NAME_KEYS) Redaction.hashNames(v, salt) else v }

    private companion object {
        val NAME_KEYS = setOf("device", "targets", "actual", "expected", "lost", "to", "confirmed", "missing")
    }
}

/**
 * Keeps the event log across process restarts (doc 12: diagnostic events are kept for 7 days). Spikes S-13/S-14
 * count presence wake-ups over 50 cycles in which the OS starts and kills the app, so memory alone loses data.
 * Writes are debounced; events are already redacted when logged.
 */
class DiagnosticsPersistence(
    private val diagnostics: Diagnostics,
    private val store: TextStore,
    private val clock: Clock,
    private val retentionMs: Long = 7 * 24 * 60 * 60 * 1000L,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Loads saved events younger than the retention period; unreadable data is dropped, never fatal. */
    fun load() {
        val text = store.read() ?: return
        val saved = runCatching { json.decodeFromString<List<DiagEvent>>(text) }.getOrNull() ?: return
        val cutoff = clock.nowMillis() - retentionMs
        diagnostics.restore(saved.filter { it.timestamp >= cutoff })
    }

    @OptIn(FlowPreview::class)
    fun launchIn(scope: CoroutineScope): Job = scope.launch {
        diagnostics.events.drop(1).debounce(WRITE_DEBOUNCE_MS).collect { store.write(json.encodeToString(it)) }
    }

    private companion object {
        const val WRITE_DEBOUNCE_MS = 1_000L
    }
}

object Redaction {
    private val macPattern = Regex("(?i)\\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\\b")

    fun redactAddresses(text: String, salt: String): String =
        macPattern.replace(text) { "addr#" + fnv1a64(salt + it.value.uppercase()).take(10) }

    fun hashNames(list: String, salt: String): String =
        list.split(", ").filter { it.isNotBlank() }.joinToString(", ") { "device#" + fnv1a64(salt + it).take(8) }

    /** FNV-1a 64-bit; used for pseudonymous identifiers, not for security. */
    fun fnv1a64(input: String): String {
        var hash = 0xcbf29ce484222325UL
        input.encodeToByteArray().forEach { b ->
            hash = hash xor b.toUByte().toULong()
            hash *= 0x100000001b3UL
        }
        return hash.toString(16).padStart(16, '0')
    }
}
