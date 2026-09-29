/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.services.VectorAndroidService
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.lib.strings.CommonStrings
import javax.inject.Inject

/**
 * Foreground service that keeps voice/audio message playback alive in the background
 * with a MediaSession and play/pause notification controls.
 */
@AndroidEntryPoint
class VoicePlaybackAndroidService : VectorAndroidService() {

    @Inject lateinit var audioMessageHelper: AudioMessageHelper
    @Inject lateinit var playbackTracker: AudioMessagePlaybackTracker

    private var mediaSession: MediaSessionCompat? = null

    private val activityListener = AudioMessagePlaybackTracker.ActivityListener { isPlayingOrRecording ->
        if (!isPlayingOrRecording && !audioMessageHelper.isPlayingOrPaused()) {
            stopSelfSafely()
        } else {
            updateNotificationAndSession()
        }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            audioMessageHelper.resumePlaybackIfPaused()
            updateNotificationAndSession()
        }

        override fun onPause() {
            audioMessageHelper.pausePlaybackKeepPosition()
            updateNotificationAndSession()
        }

        override fun onStop() {
            audioMessageHelper.stopPlayback()
            stopSelfSafely()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        mediaSession = MediaSessionCompat(applicationContext, VoicePlaybackAndroidService::class.java.name).apply {
            setCallback(mediaSessionCallback)
            isActive = true
        }
        playbackTracker.trackActivity(activityListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> mediaSessionCallback.onPlay()
            ACTION_PAUSE -> mediaSessionCallback.onPause()
            ACTION_STOP -> mediaSessionCallback.onStop()
            else -> {
                if (!audioMessageHelper.isPlayingOrPaused()) {
                    stopSelfSafely()
                    return START_NOT_STICKY
                }
                updateNotificationAndSession()
            }
        }
        return START_STICKY
    }

    private fun updateNotificationAndSession() {
        val playing = audioMessageHelper.isPlaying()
        val state = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                        .setActions(
                                PlaybackStateCompat.ACTION_PLAY or
                                        PlaybackStateCompat.ACTION_PAUSE or
                                        PlaybackStateCompat.ACTION_STOP or
                                        PlaybackStateCompat.ACTION_PLAY_PAUSE
                        )
                        .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                        .build()
        )

        val playPauseAction = if (playing) {
            NotificationCompat.Action(
                    R.drawable.ic_play_pause_pause,
                    getString(CommonStrings.action_pause),
                    pendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                    R.drawable.ic_play_pause_play,
                    getString(CommonStrings.action_play),
                    pendingIntent(ACTION_PLAY)
            )
        }

        val notification = NotificationCompat.Builder(this, "LISTEN_FOR_EVENTS_NOTIFICATION_CHANNEL_ID")
                .setContentTitle(getString(CommonStrings.notification_voice_playback_title))
                .setSmallIcon(R.drawable.ic_play_pause_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(playPauseAction)
                .setStyle(
                        androidx.media.app.NotificationCompat.MediaStyle()
                                .setMediaSession(mediaSession?.sessionToken)
                                .setShowActionsInCompactView(0)
                )
                .build()

        ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun pendingIntent(action: String): PendingIntent {
        val intent = Intent(this, VoicePlaybackAndroidService::class.java).setAction(action)
        return PendingIntent.getService(
                this,
                action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun stopSelfSafely() {
        stopForegroundCompat()
        myStopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        playbackTracker.untrackActivity(activityListener)
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 73
        const val ACTION_PLAY = "im.vector.app.VoicePlaybackAndroidService.PLAY"
        const val ACTION_PAUSE = "im.vector.app.VoicePlaybackAndroidService.PAUSE"
        const val ACTION_STOP = "im.vector.app.VoicePlaybackAndroidService.STOP"

        @Volatile
        var isRunning: Boolean = false
            private set

        fun newStartIntent(context: Context): Intent {
            return Intent(context, VoicePlaybackAndroidService::class.java)
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, newStartIntent(context))
            } catch (_: Throwable) {
                // Ignore if foreground service cannot be started
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, VoicePlaybackAndroidService::class.java).setAction(ACTION_STOP))
            } catch (_: Throwable) {
            }
        }
    }
}
