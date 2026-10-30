package io.mahajan.partymusik.platform

import java.io.File

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/** Atomic-ish file store: write to a temp file, then rename over the target. */
class FileTextStore(private val file: File) : TextStore {
    override fun read(): String? = file.takeIf { it.exists() }?.readText()

    override fun write(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

/** Writes each export into a sub-folder of [root]. */
class DirectoryExportSink(private val root: File) : ExportSink {
    override fun write(folder: String, files: Map<String, String>): String {
        val dir = File(root, folder).apply { mkdirs() }
        files.forEach { (name, text) -> File(dir, name).writeText(text) }
        return dir.absolutePath
    }
}
