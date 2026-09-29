/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.di.ActiveSessionHolder
import org.matrix.android.sdk.api.session.room.model.message.MessageWithAttachmentContent
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs media downloads while keeping [MediaDownloadAndroidService] in the foreground
 * so downloads continue when the UI goes to the background.
 */
@Singleton
class MediaDownloadKeeper @Inject constructor(
        @ApplicationContext private val context: Context,
        private val activeSessionHolder: ActiveSessionHolder,
) {

    private val activeDownloads = AtomicInteger(0)

    suspend fun download(messageContent: MessageWithAttachmentContent): File {
        val session = activeSessionHolder.getSafeActiveSession()
                ?: error("No active session")
        startServiceIfNeeded()
        activeDownloads.incrementAndGet()
        return try {
            session.fileService().downloadFile(messageContent = messageContent)
        } finally {
            if (activeDownloads.decrementAndGet() <= 0) {
                stopService()
            }
        }
    }

    private fun startServiceIfNeeded() {
        try {
            ContextCompat.startForegroundService(context, MediaDownloadAndroidService.newStartIntent(context))
        } catch (failure: Throwable) {
            Timber.w(failure, "Unable to start MediaDownloadAndroidService")
        }
    }

    private fun stopService() {
        try {
            context.startService(MediaDownloadAndroidService.newStopIntent(context))
        } catch (failure: Throwable) {
            Timber.w(failure, "Unable to stop MediaDownloadAndroidService")
        }
    }
}
