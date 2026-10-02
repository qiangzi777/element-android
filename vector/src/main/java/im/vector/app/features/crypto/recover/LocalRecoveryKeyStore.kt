/*
 * Copyright 2026 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.crypto.recover

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private-deployment helper: persist the recovery key as a well-known file in Downloads
 * so a reinstall can restore encryption without typing the key.
 */
@Singleton
class LocalRecoveryKeyStore @Inject constructor(
        @ApplicationContext private val context: Context,
) {

    fun hasRecoveryKey(): Boolean = !readRecoveryKey().isNullOrBlank()

    fun readRecoveryKey(): String? {
        candidateFiles().forEach { file ->
            val text = readFileIfPossible(file)
            if (!text.isNullOrBlank()) return text
        }
        readFromMediaStore()?.let { return it }
        return null
    }

    fun writeRecoveryKey(recoveryKey: String): Boolean {
        if (recoveryKey.isBlank()) return false
        val file = File(downloadsDirectory(), FILE_NAME)
        try {
            file.parentFile?.mkdirs()
            file.writeText(recoveryKey)
            if (file.exists() && file.length() > 0) {
                Timber.i("Recovery key saved to ${file.absolutePath}")
                return true
            }
        } catch (failure: Throwable) {
            Timber.w(failure, "Failed to write recovery key to ${file.absolutePath}")
        }
        return writeViaMediaStore(recoveryKey)
    }

    private fun downloadsDirectory(): File {
        val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (publicDownloads != null) return publicDownloads
        return File("/storage/emulated/0/Download")
    }

    private fun candidateFiles(): List<File> {
        val dir = downloadsDirectory()
        return listOf(
                File(dir, FILE_NAME),
                File(dir, "$FILE_NAME.txt"),
                File("/storage/emulated/0/Download/$FILE_NAME"),
                File("/storage/emulated/0/Download/$FILE_NAME.txt"),
        ).distinctBy { it.absolutePath }
    }

    private fun readFileIfPossible(file: File): String? {
        return try {
            if (file.exists() && file.canRead() && file.length() > 0) {
                file.readText().trim().takeIf { it.isNotBlank() }
            } else {
                null
            }
        } catch (failure: Throwable) {
            Timber.w(failure, "Cannot read recovery key from ${file.absolutePath}")
            null
        }
    }

    private fun readFromMediaStore(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? OR ${MediaStore.Downloads.DISPLAY_NAME}=?"
            val args = arrayOf(FILE_NAME, "$FILE_NAME.txt")
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                while (cursor.moveToNext()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                    val text = context.contentResolver.openInputStream(uri)
                            ?.bufferedReader()
                            ?.use { it.readText() }
                            ?.trim()
                    if (!text.isNullOrBlank()) return text
                }
            }
            null
        } catch (failure: Throwable) {
            Timber.w(failure, "Cannot read recovery key from MediaStore")
            null
        }
    }

    private fun writeViaMediaStore(recoveryKey: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val existing = findMediaStoreUri()
            val uri = existing ?: run {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                context.contentResolver.insert(collection, values)
            } ?: return false
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(recoveryKey.toByteArray()) }
                    ?: return false
            if (existing == null) {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
            }
            Timber.i("Recovery key saved via MediaStore")
            true
        } catch (failure: Throwable) {
            Timber.w(failure, "Failed to write recovery key via MediaStore")
            false
        }
    }

    private fun findMediaStoreUri(): android.net.Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? OR ${MediaStore.Downloads.DISPLAY_NAME}=?"
            val args = arrayOf(FILE_NAME, "$FILE_NAME.txt")
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    android.content.ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                } else {
                    null
                }
            }
        } catch (failure: Throwable) {
            Timber.w(failure, "Cannot query recovery key in MediaStore")
            null
        }
    }

    companion object {
        const val FILE_NAME = "open_the_heart"
    }
}
