package dev.pagedwallpaper.rust

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object WallpaperBackup {
    private const val BACKUP_NAME = "paged-wallpaper-backup.zip"
    private const val MAX_ARCHIVE_BYTES = 1024L * 1024L * 1024L
    private const val MAX_ARCHIVE_ENTRIES = 10000

    fun export(context: Context, output: OutputStream) {
        val files = context.filesDir
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            addFile(zip, File(files, "config.json"), "config.json")
            addFile(zip, File(files, "originals_meta.json"), "originals_meta.json")
            addDirectory(zip, File(files, "originals"), "originals")
            addDirectory(zip, File(files, "cropped"), "cropped")
            zip.finish()
        }
    }

    fun exportToTree(context: Context, treeUri: Uri) {
        try {
            val resolver = context.contentResolver
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            val directory = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document._ID, DocumentsContract.Document.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameColumn) == BACKUP_NAME) {
                        val existing = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idColumn))
                        DocumentsContract.deleteDocument(resolver, existing)
                    }
                }
            }
            val created = DocumentsContract.createDocument(
                resolver,
                directory,
                "application/zip",
                BACKUP_NAME,
            ) ?: throw IOException("The selected folder refused to create the backup file.")
            resolver.openOutputStream(created, "wt")?.use { export(context, it) }
                ?: throw IOException("Could not write to the selected folder.")
        } catch (error: SecurityException) {
            throw IOException("Access to the selected backup folder was denied.", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("The selected backup folder is no longer available.", error)
        }
    }

    fun restore(context: Context, input: InputStream) {
        val root = context.filesDir
        val staging = File(root, ".restore-${UUID.randomUUID()}")
        if (!staging.mkdirs()) throw IOException("Could not create a restore staging directory.")
        try {
            extractArchive(input, staging)
            validateStagedBackup(staging)
            installStagedBackup(root, staging)
        } finally {
            deleteTree(staging)
        }
    }

    private fun extractArchive(input: InputStream, staging: File) {
        var totalBytes = 0L
        var entryCount = 0
        val seen = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw IOException("Backup contains too many entries.")
                }
                val target = resolveEntry(staging, entry.name, entry.isDirectory)
                if (!seen.add(entry.name)) throw IOException("Backup contains a duplicate entry.")
                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException("Could not create a restore directory.")
                    }
                } else {
                    target.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IOException("Could not create a restore directory.")
                        }
                    }
                    FileOutputStream(target).use { output ->
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            if (totalBytes > MAX_ARCHIVE_BYTES) {
                                throw IOException("Backup is larger than the 1 GiB restore limit.")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun resolveEntry(staging: File, entryName: String, directory: Boolean): File {
        if (entryName == "config.json" || entryName == "originals_meta.json") {
            return File(staging, entryName)
        }
        val normalized = entryName.removeSuffix("/")
        val allowedRoot = when {
            normalized == "originals" || normalized.startsWith("originals/") -> "originals"
            normalized == "cropped" || normalized.startsWith("cropped/") -> "cropped"
            else -> throw IOException("Backup contains an unsupported file.")
        }
        val name = normalized.removePrefix("$allowedRoot").trimStart('/')
        if (name.isNotEmpty() &&
            (directory || name.contains('/') || name == "." || name == ".." || name.contains('\\'))
        ) {
            throw IOException("Backup contains an unsafe path.")
        }
        if (name.isEmpty() && !directory) throw IOException("Backup contains an invalid folder entry.")
        return if (name.isEmpty()) File(staging, allowedRoot) else File(File(staging, allowedRoot), name)
    }

    private fun validateStagedBackup(staging: File) {
        val config = File(staging, "config.json")
        val metadata = File(staging, "originals_meta.json")
        if (!config.isFile || !metadata.isFile) {
            throw IOException("Backup is missing settings or media metadata.")
        }
        try {
            JSONObject(config.readText(Charsets.UTF_8))
            val originals = JSONArray(metadata.readText(Charsets.UTF_8))
            for (index in 0 until originals.length()) {
                val media = originals.optJSONObject(index)
                    ?: throw IOException("Backup media metadata is invalid.")
                val name = media.optString("fileName")
                if (name.isBlank() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
                    throw IOException("Backup contains an unsafe media file name.")
                }
                if (!File(File(staging, "originals"), name).isFile) {
                    throw IOException("Backup is missing an original media file.")
                }
            }
        } catch (error: JSONException) {
            throw IOException("Backup settings are not valid JSON.", error)
        }
        File(staging, "originals").mkdirs()
        File(staging, "cropped").mkdirs()
        try {
            NativeCore.validateBackup(staging.absolutePath)
        } catch (error: IllegalArgumentException) {
            throw IOException("Backup settings are not compatible with this app.", error)
        }
    }

    private fun installStagedBackup(root: File, staging: File) {
        val rollback = File(root, ".rollback-${UUID.randomUUID()}")
        if (!rollback.mkdirs()) throw IOException("Could not prepare restore rollback.")
        val replacements = listOf(
            File(staging, "originals") to File(root, "originals"),
            File(staging, "cropped") to File(root, "cropped"),
            File(staging, "config.json") to File(root, "config.json"),
            File(staging, "originals_meta.json") to File(root, "originals_meta.json"),
        )
        val movedOld = mutableListOf<Pair<File, File>>()
        val installed = mutableListOf<File>()
        try {
            replacements.forEachIndexed { index, (source, destination) ->
                if (destination.exists()) {
                    val backup = File(rollback, "old-$index")
                    if (!destination.renameTo(backup)) {
                        throw IOException("Could not stage the existing ${destination.name} for replacement.")
                    }
                    movedOld.add(destination to backup)
                }
                if (!source.renameTo(destination)) {
                    throw IOException("Could not install restored ${destination.name}.")
                }
                installed.add(destination)
            }
        } catch (error: IOException) {
            var rollbackFailed = false
            installed.asReversed().forEach { destination ->
                try {
                    deleteTree(destination)
                } catch (cleanupError: IOException) {
                    rollbackFailed = true
                    error.addSuppressed(cleanupError)
                }
            }
            movedOld.asReversed().forEach { (destination, backup) ->
                if (!backup.renameTo(destination)) {
                    rollbackFailed = true
                    error.addSuppressed(IOException("Could not roll back ${destination.name}."))
                }
            }
            if (rollbackFailed) {
                error.addSuppressed(IOException("Rollback files were preserved at ${rollback.absolutePath}."))
            } else {
                try {
                    deleteTree(rollback)
                } catch (cleanupError: IOException) {
                    error.addSuppressed(cleanupError)
                }
            }
            throw error
        }
        deleteTree(rollback)
    }

    private fun addDirectory(zip: ZipOutputStream, directory: File, prefix: String) {
        if (!directory.exists()) return
        val files = directory.listFiles()
            ?: throw IOException("Could not read ${directory.name} for backup.")
        files.filter(File::isFile).sortedBy(File::getName).forEach { file ->
            if (file.name.contains('/') || file.name.contains('\\')) {
                throw IOException("Unexpected file name in ${directory.name}.")
            }
            addFile(zip, file, "$prefix/${file.name}")
        }
    }

    private fun addFile(zip: ZipOutputStream, file: File, entryName: String) {
        if (!file.exists()) {
            if (entryName == "config.json" || entryName == "originals_meta.json") {
                throw IOException("Missing ${file.name}; backup was not created.")
            }
            return
        }
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { input -> input.copyTo(zip) }
        zip.closeEntry()
    }

    private fun deleteTree(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) {
            file.listFiles()?.forEach(::deleteTree)
        }
        if (!file.delete()) throw IOException("Could not clean up ${file.name}.")
    }
}
