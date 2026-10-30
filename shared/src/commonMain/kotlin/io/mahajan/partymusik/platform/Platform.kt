package io.mahajan.partymusik.platform

/** Wall-clock milliseconds since the Unix epoch. */
expect fun currentTimeMillis(): Long

fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val System: Clock = Clock { currentTimeMillis() }
    }
}

/** Minimal text persistence used by [io.mahajan.partymusik.data.LocalStore]. */
interface TextStore {
    fun read(): String?
    fun write(text: String)
}

/**
 * Saves exported files where a tester can collect them: a folder on desktop, the app's external files directory
 * on Android (`adb pull`), the app's Documents folder on iOS (visible in the Files app). Returns the location.
 */
fun interface ExportSink {
    fun write(folder: String, files: Map<String, String>): String
}

class InMemoryTextStore(private var content: String? = null) : TextStore {
    override fun read(): String? = content
    override fun write(text: String) {
        content = text
    }
}
