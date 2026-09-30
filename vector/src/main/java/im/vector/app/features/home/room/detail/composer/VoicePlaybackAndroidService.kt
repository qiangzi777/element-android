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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.R
import im.vector.app.core.extensions.createIgnoredUri
import im.vector.app.core.platform.PendingIntentCompat
import im.vector.app.core.services.VectorAndroidService
import im.vector.app.features.home.HomeActivity
import im.vector.app.features.home.room.detail.RoomDetailActivity
import im.vector.app.features.home.room.detail.arguments.TimelineArgs
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressUpdatesScheduled = false

    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateNotificationAndSession()
            if (audioMessageHelper.isPlaying()) {
                mainHandler.postDelayed(this, PROGRESS_UPDATE_INTERVAL_MS)
            } else {
                progressUpdatesScheduled = false
            }
        }
    }

    private val activityListener = AudioMessagePlaybackTracker.ActivityListener { isPlayingOrRecording ->
        if (!isPlayingOrRecording && !audioMessageHelper.isPlayingOrPaused()) {
            stopSelfSafely()
        } else {
            updateNotificationAndSession()
            scheduleProgressUpdatesIfNeeded()
        }
    }

    private val mediaSessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() {
            audioMessageHelper.resumePlaybackIfPaused()
            updateNotificationAndSession()
            scheduleProgressUpdatesIfNeeded()
        }

        override fun onPause() {
            audioMessageHelper.pausePlaybackKeepPosition()
            updateNotificationAndSession()
        }

        override fun onStop() {
            audioMessageHelper.stopPlayback()
            stopSelfSafely()
        }

        override fun onSeekTo(pos: Long) {
            audioMessageHelper.seekTo(pos.toInt())
            updateNotificationAndSession()
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
                scheduleProgressUpdatesIfNeeded()
            }
        }
        return START_STICKY
    }

    private fun scheduleProgressUpdatesIfNeeded() {
        if (audioMessageHelper.isPlaying() && !progressUpdatesScheduled) {
            progressUpdatesScheduled = true
            mainHandler.postDelayed(progressUpdateRunnable, PROGRESS_UPDATE_INTERVAL_MS)
        }
    }

    private fun updateNotificationAndSession() {
        val playing = audioMessageHelper.isPlaying()
        val positionMs = audioMessageHelper.getPlaybackPositionMs().toLong().coerceAtLeast(0L)
        val durationMs = audioMessageHelper.getPlaybackDurationMs().toLong().coerceAtLeast(0L)
        val title = audioMessageHelper.getCurrentTitle()
                ?.takeIf { it.isNotBlank() }
                ?: getString(CommonStrings.notification_voice_playback_title)
        val roomId = audioMessageHelper.getCurrentRoomId()
        val eventId = audioMessageHelper.getCurrentPlayingId()
                ?.takeIf { it != AudioMessagePlaybackTracker.RECORDING_ID }

        val playbackState = if (playing) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }
        val speed = if (playing) 1f else 0f

        mediaSession?.setMetadata(
                MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                        .putString(
                                MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE,
                                getString(CommonStrings.notification_voice_playback_title)
                        )
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
                        .build()
        )

        val openChatIntent = buildOpenChatPendingIntent(roomId, eventId)
        mediaSession?.setSessionActivity(openChatIntent)
        mediaSession?.setPlaybackState(
                PlaybackStateCompat.Builder()
                        .setActions(
                                PlaybackStateCompat.ACTION_PLAY or
                                        PlaybackStateCompat.ACTION_PAUSE or
                                        PlaybackStateCompat.ACTION_STOP or
                                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                                        PlaybackStateCompat.ACTION_SEEK_TO
                        )
                        .setState(playbackState, positionMs, speed, SystemClock.elapsedRealtime())
                        .setBufferedPosition(durationMs)
                        .build()
        )

        val playPauseAction = if (playing) {
            NotificationCompat.Action(
                    R.drawable.ic_play_pause_pause,
                    getString(CommonStrings.a11y_pause_voice_message),
                    pendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                    R.drawable.ic_play_pause_play,
                    getString(CommonStrings.action_play),
                    pendingIntent(ACTION_PLAY)
            )
        }

        val notificationBuilder = NotificationCompat.Builder(this, "LISTEN_FOR_EVENTS_NOTIFICATION_CHANNEL_ID")
                .setContentTitle(title)
                .setContentText(getString(CommonStrings.notification_voice_playback_title))
                .setSmallIcon(R.drawable.ic_play_pause_play)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .addAction(playPauseAction)
                .setStyle(
                        androidx.media.app.NotificationCompat.MediaStyle()
                                .setMediaSession(mediaSession?.sessionToken)
                                .setShowActionsInCompactView(0)
                )

        if (openChatIntent != null) {
            notificationBuilder.setContentIntent(openChatIntent)
        }

        // Fallback progress when the system MediaStyle scrubber is unavailable.
        if (durationMs > 0) {
            notificationBuilder.setProgress(durationMs.toInt(), positionMs.toInt().coerceAtMost(durationMs.toInt()), false)
        }

        ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notificationBuilder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
    }

    private fun buildOpenChatPendingIntent(roomId: String?, eventId: String?): PendingIntent? {
        if (roomId.isNullOrBlank()) return null
        val roomIntent = RoomDetailActivity.newIntent(
                this,
                TimelineArgs(
                        roomId = roomId,
                        eventId = eventId,
                        switchToParentSpace = true,
                ),
                firstStartMainActivity = true,
        ).apply {
            action = ACTION_OPEN_CHAT
            data = createIgnoredUri("voicePlayback?roomId=$roomId&eventId=${eventId.orEmpty()}")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return TaskStackBuilder.create(this)
                .addNextIntentWithParentStack(HomeActivity.newIntent(this, firstStartMainActivity = false))
                .addNextIntent(roomIntent)
                .getPendingIntent(
                        (roomId + eventId.orEmpty()).hashCode(),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntentCompat.FLAG_IMMUTABLE
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
        mainHandler.removeCallbacks(progressUpdateRunnable)
        progressUpdatesScheduled = false
        stopForegroundCompat()
        myStopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(progressUpdateRunnable)
        progressUpdatesScheduled = false
        playbackTracker.untrackActivity(activityListener)
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val NOTIFICATION_ID = 73
        private const val PROGRESS_UPDATE_INTERVAL_MS = 1000L
        const val ACTION_PLAY = "im.vector.app.VoicePlaybackAndroidService.PLAY"
        const val ACTION_PAUSE = "im.vector.app.VoicePlaybackAndroidService.PAUSE"
        const val ACTION_STOP = "im.vector.app.VoicePlaybackAndroidService.STOP"
        private const val ACTION_OPEN_CHAT = "im.vector.app.VoicePlaybackAndroidService.OPEN_CHAT"

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
