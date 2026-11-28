/*
 * SPDX-License-Identifier: MPL-2.0
 */

package emu.skyline.utils

import android.app.ActivityManager
import android.content.Context
import emu.skyline.EmulationActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * File-level operations on the per-game data stored in the public files directory: shader (pipeline) caches and save data
 * @note Every function takes the public files directory explicitly so none of them depend on an Android [Context], except [isEmulationRunning]
 * @note Paths mirror the native side: `gpu.cpp` (pipeline caches) and `IFileSystemProxy::OpenSaveDataFileSystem` (save data)
 */
object GameDataManager {
    /**
     * Long-running file operations must outlive the dialog that started them (rotation, dismissal), so they run on an application-wide scope
     */
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Thrown when the game has no save data to export or delete */
    class NoSavesException : IOException("No save data found")

    /** Thrown when an archive does not contain save data of the expected game */
    class WrongGameArchiveException : IOException("The archive does not contain save data for this game")

    /** Thrown when an archive is malformed, unsafe or exceeds the import limits */
    class InvalidSaveArchiveException(message : String) : IOException(message)

    /** Thrown when an import failed halfway and the previous saves could not be restored, they are kept in the backup directory named in the message */
    private class RollbackFailedException(message : String) : IOException(message)

    private val titleIdRegex = Regex("[0-9A-F]{16}")
    private val userIdRegex = Regex("[0-9A-F]{32}")
    private const val AccountSaveSpace = "0000000000000000"
    private const val MaxImportEntries = 20_000
    private const val MaxImportBytes = 1L shl 30 // Native save data is capped at 200 MB per type, this leaves a wide margin while bounding decompression bombs
    private const val CopyBufferSize = 64 * 1024

    /**
     * @return If the title ID has the canonical form used by the native side, which is also what makes it safe to use in a file path
     */
    fun isValidTitleId(titleId : String?) = titleId != null && titleIdRegex.matches(titleId)

    private fun checkedTitleId(titleId : String?) : String {
        require(titleId != null && titleIdRegex.matches(titleId)) { "Invalid title ID" }
        return titleId
    }

    private fun userRoot(filesDir : File) = File(filesDir, "switch/nand/user")

    /**
     * @return If an emulation session is currently alive, in the same or in a separate process, in which case game data must not be touched
     */
    fun isEmulationRunning(context : Context) : Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        val emulationActivity = EmulationActivity::class.java.name
        return activityManager.appTasks.any { task ->
            val info = task.taskInfo
            info.topActivity?.className == emulationActivity || info.baseActivity?.className == emulationActivity
        }
    }

    // ---------------------------------------------------------------- Shader cache

    /**
     * @return Every file or directory that makes up the shader cache of the game: the graphics pipeline cache with its staging and loading-marker files and the Vulkan driver cache directory
     * @note `shader_replacements` (user supplied) and `shader_dumps` (diagnostics) are deliberately excluded, they are not caches
     */
    fun shaderCachePaths(filesDir : File, titleId : String) : List<File> {
        val id = checkedTitleId(titleId)
        val pipelineCache = File(filesDir, "graphics_pipeline_cache")
        return listOf(File(pipelineCache, id), File(pipelineCache, "$id.staging"), File(pipelineCache, "$id.loading"), File(filesDir, "vk_graphics_pipeline_cache/$id"))
    }

    /**
     * Deletes the shader cache of the game
     * @return The amount of bytes freed, or null if there was no shader cache
     */
    fun deleteShaderCache(filesDir : File, titleId : String) : Long? {
        val existing = shaderCachePaths(filesDir, titleId).filter { it.exists() }
        if (existing.isEmpty())
            return null

        var freed = 0L
        for (path in existing) {
            freed += sizeOf(path)
            if (!path.deleteRecursively() && path.exists())
                throw IOException("Failed to delete ${path.name}")
        }
        return freed
    }

    private fun sizeOf(file : File) = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()

    // ---------------------------------------------------------------- Save data

    /**
     * @return The directories holding the save data of the game: one per user (and the device save, user 0) plus the cache save
     */
    private fun saveDirectories(filesDir : File, titleId : String) : List<File> {
        val root = userRoot(filesDir)
        val directories = mutableListOf<File>()

        File(root, "save/$AccountSaveSpace").listFiles { file -> file.isDirectory && userIdRegex.matches(file.name) }?.sortedBy { it.name }?.forEach { userDirectory ->
            File(userDirectory, titleId).takeIf { it.isDirectory }?.let { directories.add(it) }
        }
        File(root, "save/cache/$titleId").takeIf { it.isDirectory }?.let { directories.add(it) }

        return directories
    }

    fun hasSaves(filesDir : File, titleId : String) = saveDirectories(filesDir, checkedTitleId(titleId)).isNotEmpty()

    /**
     * Deletes every save of the game
     * @throws NoSavesException If there was nothing to delete
     */
    fun deleteSaves(filesDir : File, titleId : String) {
        val directories = saveDirectories(filesDir, checkedTitleId(titleId))
        if (directories.isEmpty())
            throw NoSavesException()

        for (directory in directories)
            if (!directory.deleteRecursively() && directory.exists())
                throw IOException("Failed to delete ${directory.name}")
    }

    /**
     * Writes every save of the game to [output] as a zip, entry names are relative to `switch/nand/user/` (e.g. `save/0000000000000000/<user>/<titleId>/...`)
     * @note [output] is closed
     * @throws NoSavesException If there was nothing to export
     */
    fun exportSaves(filesDir : File, titleId : String, output : OutputStream) {
        val directories = saveDirectories(filesDir, checkedTitleId(titleId))
        if (directories.isEmpty())
            throw NoSavesException()

        val root = userRoot(filesDir)
        ZipOutputStream(output.buffered()).use { zip ->
            for (directory in directories) {
                for (file in directory.walkTopDown()) {
                    val name = file.relativeTo(root).invariantSeparatorsPath
                    if (file.isDirectory) {
                        zip.putNextEntry(ZipEntry("$name/"))
                    } else {
                        zip.putNextEntry(ZipEntry(name))
                        file.inputStream().use { it.copyTo(zip, CopyBufferSize) }
                    }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Replaces every save of the game with the content of a zip produced by [exportSaves]
     * @note [input] is closed
     * @note The archive is fully extracted and validated in a staging directory before anything existing is touched, the swap then moves directories with renames and rolls back on failure
     * @throws WrongGameArchiveException If the archive holds anything other than save data of this game, nothing is imported then
     * @throws InvalidSaveArchiveException If the archive is malformed, unsafe or too large
     */
    fun importSaves(filesDir : File, titleId : String, input : InputStream) {
        val id = checkedTitleId(titleId)
        val root = userRoot(filesDir)

        // Same filesystem as the saves so that the final swap can use renames
        val workDirectory = File(filesDir, "switch/.save_import_$id")
        if (workDirectory.exists()) {
            if (File(workDirectory, "backup").list()?.isNotEmpty() == true) {
                // A previous import died after moving saves away and could not restore them, keep that data instead of deleting it
                val recovered = File(workDirectory.parentFile, "${workDirectory.name}_recovered")
                recovered.deleteRecursively()
                workDirectory.renameTo(recovered)
            } else {
                workDirectory.deleteRecursively()
            }
        }
        val staging = File(workDirectory, "staging")
        val backup = File(workDirectory, "backup")

        var keepWorkDirectory = false
        try {
            extractValidated(input, staging, id)

            val staged = stagedSaveDirectories(staging, id)
            if (staged.isEmpty())
                throw InvalidSaveArchiveException("The archive contains no save directory")

            swapSaves(root, staging, backup, staged, saveDirectories(filesDir, id))
        } catch (e : RollbackFailedException) {
            keepWorkDirectory = true
            throw e
        } finally {
            if (!keepWorkDirectory)
                workDirectory.deleteRecursively()
        }
    }

    /**
     * Splits a zip entry name into its path components
     * @return null if the name is unsafe (absolute, backslashes, `.` or `..` components)
     */
    private fun splitEntryName(name : String) : List<String>? {
        if (name.startsWith("/") || name.contains('\\'))
            return null
        val parts = name.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." })
            return null
        return parts
    }

    private fun isSavePathOfTitle(parts : List<String>, titleId : String) = when {
        parts.size >= 4 && parts[0] == "save" && parts[1] == AccountSaveSpace && userIdRegex.matches(parts[2]) && parts[3] == titleId -> true
        parts.size >= 3 && parts[0] == "save" && parts[1] == "cache" && parts[2] == titleId -> true
        else -> false
    }

    /**
     * @return If the directory is a parent that some archivers emit explicitly (`save/`, `save/0000000000000000/`, ...), such entries carry no data and are skipped
     */
    private fun isAncestorOfSavePath(parts : List<String>) = when (parts.size) {
        1 -> parts[0] == "save"
        2 -> parts[0] == "save" && (parts[1] == AccountSaveSpace || parts[1] == "cache")
        3 -> parts[0] == "save" && parts[1] == AccountSaveSpace && userIdRegex.matches(parts[2])
        else -> false
    }

    private fun extractValidated(input : InputStream, staging : File, titleId : String) {
        if (!staging.mkdirs())
            throw IOException("Failed to create the staging directory")
        val stagingPath = staging.canonicalPath + File.separator

        var entries = 0
        var totalBytes = 0L
        val buffer = ByteArray(CopyBufferSize)

        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > MaxImportEntries)
                    throw InvalidSaveArchiveException("The archive has too many entries")

                val parts = splitEntryName(entry.name) ?: throw InvalidSaveArchiveException("The archive has an unsafe entry name")
                if (entry.isDirectory && isAncestorOfSavePath(parts))
                    continue
                if (!isSavePathOfTitle(parts, titleId))
                    throw WrongGameArchiveException()

                val target = File(staging, parts.joinToString("/"))
                if (!target.canonicalPath.startsWith(stagingPath)) // Defence in depth on top of splitEntryName (Zip Slip)
                    throw InvalidSaveArchiveException("The archive has an unsafe entry name")

                if (entry.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs())
                        throw IOException("Failed to create ${target.name}")
                    continue
                }

                val parent = target.parentFile ?: throw IOException("Invalid entry ${entry.name}")
                if (!parent.isDirectory && !parent.mkdirs())
                    throw IOException("Failed to create ${parent.name}")

                target.outputStream().buffered().use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0)
                            break
                        totalBytes += read
                        if (totalBytes > MaxImportBytes)
                            throw InvalidSaveArchiveException("The archive is too large")
                        out.write(buffer, 0, read)
                    }
                }
            }
        }

        if (entries == 0)
            throw InvalidSaveArchiveException("The archive is empty")
    }

    /**
     * @return The save directories that were extracted, as paths relative to the staging directory
     */
    private fun stagedSaveDirectories(staging : File, titleId : String) : List<String> {
        val staged = mutableListOf<String>()

        File(staging, "save/$AccountSaveSpace").listFiles { file -> file.isDirectory && userIdRegex.matches(file.name) }?.sortedBy { it.name }?.forEach { userDirectory ->
            if (File(userDirectory, titleId).isDirectory)
                staged.add("save/$AccountSaveSpace/${userDirectory.name}/$titleId")
        }
        if (File(staging, "save/cache/$titleId").isDirectory)
            staged.add("save/cache/$titleId")

        return staged
    }

    /**
     * Moves the current saves into [backup], moves the staged ones into place, and restores the backup if anything fails
     * @throws RollbackFailedException If the restore itself failed, the backup is then left on disk
     */
    private fun swapSaves(root : File, staging : File, backup : File, staged : List<String>, current : List<File>) {
        val moved = mutableListOf<Pair<File, File>>() // Original location to backup location
        val placed = mutableListOf<File>()

        try {
            for (directory in current) {
                val backupLocation = File(backup, directory.relativeTo(root).path)
                backupLocation.parentFile?.mkdirs()
                if (!directory.renameTo(backupLocation))
                    throw IOException("Failed to move the existing saves aside")
                moved.add(directory to backupLocation)
            }

            for (relative in staged) {
                val source = File(staging, relative)
                val destination = File(root, relative)
                destination.parentFile?.mkdirs()
                if (!source.renameTo(destination))
                    throw IOException("Failed to install the imported saves")
                placed.add(destination)
            }
        } catch (e : Exception) {
            placed.forEach { it.deleteRecursively() }

            var restored = true
            for ((original, backupLocation) in moved.asReversed()) {
                original.parentFile?.mkdirs()
                if (!backupLocation.renameTo(original))
                    restored = false
            }

            if (!restored)
                throw RollbackFailedException("Import failed and the previous saves could not be restored, they are kept in ${backup.path}").also { it.initCause(e) }
            throw e
        }
    }
}
