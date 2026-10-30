package io.mahajan.partymusik.platform

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/**
 * Stores app data in the Documents directory, encrypted at rest by iOS data protection (NFR-006).
 * Uses `CompleteUntilFirstUserAuthentication` rather than `Complete`: a Shortcuts automation (ADR-004) can
 * apply a profile while the phone is locked, and `Complete` would make the file unreadable then.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class FileTextStore(fileName: String) : TextStore {
    private val path: String = run {
        val dirs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        "${dirs.first() as String}/$fileName"
    }

    override fun read(): String? = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null)

    override fun write(text: String) {
        NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding)?.writeToFile(
            path,
            options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication,
            error = null,
        )
    }
}

/**
 * Writes each export into `Documents/exports/<folder>`. `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`
 * in Info.plist make that folder visible in the Files app, so testers can AirDrop the CSVs.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class DocumentsExportSink : ExportSink {
    override fun write(folder: String, files: Map<String, String>): String {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val dir = "$docs/exports/$folder"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        files.forEach { (name, text) ->
            NSString.create(string = text).dataUsingEncoding(NSUTF8StringEncoding)?.writeToFile(
                "$dir/$name",
                options = NSDataWritingAtomic or NSDataWritingFileProtectionCompleteUntilFirstUserAuthentication,
                error = null,
            )
        }
        return "Files app › PartyMusik › exports › $folder"
    }
}
