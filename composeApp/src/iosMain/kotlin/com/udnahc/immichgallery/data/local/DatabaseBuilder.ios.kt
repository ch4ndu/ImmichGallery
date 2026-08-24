@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.udnahc.immichgallery.data.local

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUserDomainMask

fun createDatabase(): AppDatabase {
    val dbFilePath = prepareDatabasePath()
    return Room.databaseBuilder<AppDatabase>(name = dbFilePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .addMigrations(MIGRATION_1_2)
        .fallbackToDestructiveMigration(true)
        .build()
}

private fun prepareDatabasePath(): String {
    val fileManager = NSFileManager.defaultManager
    val applicationSupport = requireNotNull(
        fileManager.URLForDirectory(
            directory = NSApplicationSupportDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = true,
            error = null,
        )
    ) { "Unable to resolve application cache storage." }
    val cacheDirectory = requireNotNull(
        applicationSupport.URLByAppendingPathComponent(
            CACHE_DIRECTORY_NAME,
            isDirectory = true,
        )
    ) { "Unable to resolve application cache directory." }
    val cacheDirectoryPath = requireNotNull(cacheDirectory.path)
    val targetPath = "$cacheDirectoryPath/$DATABASE_FILE_NAME"
    val legacyPath = "${NSHomeDirectory()}/Documents/$DATABASE_FILE_NAME"

    if (!fileManager.fileExistsAtPath(cacheDirectoryPath)) {
        check(
            fileManager.createDirectoryAtURL(
                cacheDirectory,
                true,
                null,
                null,
            )
        ) { "Unable to create application cache directory." }
    }
    val isExcludedFromBackup = cacheDirectory.setResourceValue(
        true,
        NSURLIsExcludedFromBackupKey,
        null,
    )
    if (!isExcludedFromBackup) {
        val targetDeleted = deleteCacheFiles(fileManager, databaseFileSet(targetPath))
        val legacyDeleted = deleteCacheFiles(fileManager, databaseFileSet(legacyPath))
        check(targetDeleted && legacyDeleted) {
            "Unable to clear application caches after backup exclusion failed."
        }
        error("Unable to exclude the application cache from backup.")
    }

    migrateLegacyCacheFiles(
        fileManager = fileManager,
        legacyDatabasePath = legacyPath,
        targetDatabasePath = targetPath,
    )
    return targetPath
}

/**
 * Moves the cache-only Room database as a unit before Room opens it.
 *
 * If a prior or current move is partial, both copies are discarded. The cache
 * is fully re-fetchable, so opening a fresh target is safer than pairing a
 * database file with the wrong WAL or shared-memory file.
 */
private fun migrateLegacyCacheFiles(
    fileManager: NSFileManager,
    legacyDatabasePath: String,
    targetDatabasePath: String,
) {
    val legacyPaths = databaseFileSet(legacyDatabasePath)
    val targetPaths = databaseFileSet(targetDatabasePath)
    val legacyExists = legacyPaths.map { path -> fileManager.fileExistsAtPath(path) }
    val targetExists = targetPaths.map { path -> fileManager.fileExistsAtPath(path) }
    val hasLegacyFiles = legacyExists.any { it }
    val hasTargetFiles = targetExists.any { it }

    if (!hasLegacyFiles) {
        if (hasTargetFiles && !targetExists.first()) {
            check(deleteCacheFiles(fileManager, targetPaths)) {
                "Unable to reset a partial application cache."
            }
        }
        return
    }

    // Files at both locations, or sidecars without the legacy database, mean
    // an earlier move did not complete. Reset both sides instead of trying to
    // infer which WAL belongs to which database file.
    if (hasTargetFiles || !legacyExists.first()) {
        val targetDeleted = deleteCacheFiles(fileManager, targetPaths)
        val legacyDeleted = deleteCacheFiles(fileManager, legacyPaths)
        check(targetDeleted) {
            "Unable to reset a partial application cache."
        }
        check(legacyDeleted) {
            "Unable to remove the legacy application cache."
        }
        return
    }

    var moveSucceeded = true
    legacyPaths.zip(targetPaths).forEachIndexed { index, (legacyPath, targetPath) ->
        if (legacyExists[index] && !fileManager.moveItemAtPath(legacyPath, targetPath, null)) {
            moveSucceeded = false
        }
    }

    val targetContainsMovedFiles = targetPaths.indices.all { index ->
        !legacyExists[index] || fileManager.fileExistsAtPath(targetPaths[index])
    }
    if (!moveSucceeded || !targetContainsMovedFiles) {
        val targetDeleted = deleteCacheFiles(fileManager, targetPaths)
        val legacyDeleted = deleteCacheFiles(fileManager, legacyPaths)
        check(targetDeleted) {
            "Unable to reset a failed application cache migration."
        }
        check(legacyDeleted) {
            "Unable to remove the legacy application cache."
        }
    } else {
        // On success these paths no longer exist. Retrying deletion ensures no
        // legacy cache sidecar remains in Documents.
        check(deleteCacheFiles(fileManager, legacyPaths)) {
            "Unable to remove the legacy application cache."
        }
    }
}

private fun databaseFileSet(databasePath: String): List<String> =
    DATABASE_FILE_SUFFIXES.map { suffix -> "$databasePath$suffix" }

private fun deleteCacheFiles(
    fileManager: NSFileManager,
    paths: List<String>,
): Boolean {
    var succeeded = true
    paths.forEach { path ->
        if (
            fileManager.fileExistsAtPath(path) &&
            !fileManager.removeItemAtPath(path, null) &&
            fileManager.fileExistsAtPath(path)
        ) {
            succeeded = false
        }
    }
    return succeeded
}

private const val CACHE_DIRECTORY_NAME = "ImmichGallery"
private const val DATABASE_FILE_NAME = "immich_gallery.db"
private val DATABASE_FILE_SUFFIXES = listOf("", "-wal", "-shm")
