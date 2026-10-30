package io.mahajan.partymusik.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

/** Plain file in the app-private files directory, written atomically. */
class FileTextStore(private val file: File) : TextStore {
    override fun read(): String? = file.takeIf { it.exists() }?.readText()

    override fun write(text: String) = writeAtomically(file, text.toByteArray())
}

/**
 * NFR-006: app data encrypted at rest with AES-256-GCM. The key is generated inside the Android Keystore
 * and never leaves it. File layout: `PMv1` magic, 12-byte IV, ciphertext + tag.
 *
 * A file written by the older [FileTextStore] (plain JSON) is still read, and is encrypted on the next write.
 * If the key is lost (app data cleared from Settings, Keystore reset) the file cannot be decrypted; [read]
 * then returns null and the app starts from defaults, as on first launch.
 */
class EncryptedFileTextStore(
    private val file: File,
    private val keyAlias: String = "partymusik.store.v1",
) : TextStore {
    override fun read(): String? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (!bytes.startsWith(MAGIC)) return String(bytes) // legacy plain JSON
        return try {
            val iv = bytes.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(bytes, MAGIC.size + IV_BYTES, bytes.size - MAGIC.size - IV_BYTES))
        } catch (e: Exception) {
            null
        }
    }

    override fun write(text: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the Keystore picks a fresh random IV
        writeAtomically(file, MAGIC + cipher.iv + cipher.doFinal(text.toByteArray()))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val MAGIC = "PMv1".toByteArray()
    }
}

private fun writeAtomically(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
    }
}

/**
 * Writes each export into a sub-folder of [root]. With `Context.getExternalFilesDir("exports")` as root the
 * lab collects files with `adb pull /sdcard/Android/data/io.mahajan.partymusik/files/exports` (no permission needed).
 */
class DirectoryExportSink(private val root: File) : ExportSink {
    override fun write(folder: String, files: Map<String, String>): String {
        val dir = File(root, folder).apply { mkdirs() }
        files.forEach { (name, text) -> File(dir, name).writeText(text) }
        return dir.absolutePath
    }
}
