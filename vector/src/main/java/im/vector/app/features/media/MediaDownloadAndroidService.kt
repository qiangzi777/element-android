/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.media

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.services.VectorAndroidService
import im.vector.app.features.notifications.NotificationUtils
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/**
 * Foreground service that keeps media downloads alive while the app is in the background.
 */
@AndroidEntryPoint
class MediaDownloadAndroidService : VectorAndroidService() {

    @Inject lateinit var notificationUtils: NotificationUtils

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForegroundCompat()
                myStopSelf()
            }
            else -> showNotification()
        }
        return START_STICKY
    }

    private fun showNotification() {
        val notification = notificationUtils.buildForegroundServiceNotification(
                CommonStrings.notification_media_download_title,
                withProgress = true
        )
        ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 72
        const val ACTION_STOP = "im.vector.app.MediaDownloadAndroidService.STOP"

        fun newStartIntent(context: Context): Intent {
            return Intent(context, MediaDownloadAndroidService::class.java)
        }

        fun newStopIntent(context: Context): Intent {
            return Intent(context, MediaDownloadAndroidService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
