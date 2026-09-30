/*
 * Copyright 2021-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.composer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import im.vector.app.core.resources.BuildMeta
import im.vector.app.features.home.room.detail.timeline.helper.AudioMessagePlaybackTracker
import im.vector.app.features.home.room.detail.timeline.helper.VoiceMessagePlayedStore
import im.vector.app.features.voice.VoiceFailure
import im.vector.app.features.voice.VoiceRecorder
import im.vector.app.features.voice.VoiceRecorderProvider
import im.vector.lib.core.utils.timer.CountUpTimer
import im.vector.lib.multipicker.entity.MultiPickerAudioType
import im.vector.lib.multipicker.utils.toMultiPickerAudioType
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.content.ContentAttachmentData
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class to record audio for voice messages and play timeline audio.
 */
@Singleton
class AudioMessageHelper @Inject constructor(
        @ApplicationContext private val context: Context,
        private val playbackTracker: AudioMessagePlaybackTracker,
        private val playedStore: VoiceMessagePlayedStore,
        private val buildMeta: BuildMeta,
        voiceRecorderProvider: VoiceRecorderProvider
) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingId: String? = null
    private var currentRoomId: String? = null
    private var currentTitle: String? = null
    private var maxListenedPositionMs: Int = 0
    private val voiceRecorder: VoiceRecorder by lazy { voiceRecorderProvider.provideVoiceRecorder() }
    private val playerLock = Any()

    private val amplitudeList = mutableListOf<Int>()

    private var amplitudeTicker: CountUpTimer? = null
    private var playbackTicker: CountUpTimer? = null

    fun initializeRecorder(roomId: String, attachmentData: ContentAttachmentData) {
        voiceRecorder.initializeRecord(roomId, attachmentData)
        amplitudeList.clear()
        attachmentData.waveform?.let {
            amplitudeList.addAll(it)
            playbackTracker.updateCurrentRecording(AudioMessagePlaybackTracker.RECORDING_ID, amplitudeList)
        }
    }

    fun startRecording(roomId: String) {
        stopPlayback()
        playbackTracker.pauseAllPlaybacks()
        amplitudeList.clear()

        try {
            voiceRecorder.startRecord(roomId)
        } catch (failure: Throwable) {
            Timber.e(failure, "Unable to start recording")
            throw VoiceFailure.UnableToRecord(failure)
        }
        startRecordingAmplitudes()
    }

    fun stopRecording(): MultiPickerAudioType? {
        val voiceMessageFile = tryOrNull("Cannot stop media recorder!") {
            voiceRecorder.stopRecord()
            voiceRecorder.getVoiceMessageFile()
        }

        tryOrNull("Cannot stop media recording amplitude") {
            stopRecordingAmplitudes()
        }

        return try {
            voiceMessageFile?.let {
                val outputFileUri = FileProvider.getUriForFile(context, buildMeta.applicationId + ".fileProvider", it, "Voice message.${it.extension}")
                outputFileUri
                        .toMultiPickerAudioType(context)
                        ?.apply {
                            waveform = if (amplitudeList.size < 50) {
                                amplitudeList
                            } else {
                                amplitudeList.chunked(amplitudeList.size / 50) { items -> items.maxOrNull() ?: 0 }
                            }
                        }
            }
        } catch (e: FileNotFoundException) {
            Timber.e(e, "Cannot stop voice recording")
            null
        } catch (e: RuntimeException) {
            Timber.e(e, "Error while retrieving metadata")
            null
        }
    }

    /**
     * When entering in playback mode actually.
     */
    fun pauseRecording() {
        // TODO should we pause instead of stop?
        voiceRecorder.stopRecord()
        stopRecordingAmplitudes()
    }

    fun deleteRecording() {
        tryOrNull("Cannot stop media recording amplitude") {
            stopRecordingAmplitudes()
        }
        tryOrNull("Cannot stop media recorder!") {
            voiceRecorder.cancelRecord()
        }
    }

    fun startOrPauseRecordingPlayback() {
        voiceRecorder.getVoiceMessageFile()?.let {
            startOrPausePlayback(AudioMessagePlaybackTracker.RECORDING_ID, it)
        }
    }

    fun startOrPausePlayback(
            id: String,
            file: File,
            roomId: String? = null,
            title: String? = null,
    ) {
        synchronized(playerLock) {
            if (roomId != null) {
                currentRoomId = roomId
            }
            if (title != null) {
                currentTitle = title
            }

            val playbackState = playbackTracker.getPlaybackState(id)
            val wasPlaying = playbackState is AudioMessagePlaybackTracker.Listener.State.Playing ||
                    (currentPlayingId == id && isMediaPlayerPlaying())

            if (wasPlaying) {
                // Pause in-place so progress and MediaPlayer position are preserved.
                if (currentPlayingId == id && mediaPlayer != null) {
                    pausePlaybackKeepPositionLocked()
                } else {
                    val position = playbackTracker.getPlaybackTime(id)
                            ?: playedStore.getPlaybackPosition(id)
                    val percentage = playbackTracker.getPercentage(id) ?: 0f
                    playbackTracker.updatePausedAtPlaybackTime(id, position, percentage)
                    playedStore.savePlaybackPosition(id, position)
                    stopPlaybackTickerQuietly()
                }
                return
            }

            // Resume same item if MediaPlayer was kept paused.
            if (currentPlayingId == id && mediaPlayer != null) {
                resumePlaybackIfPausedLocked()
                return
            }

            // Switching to another (or first) item: tear down previous player safely.
            releaseMediaPlayerLocked(savePosition = true)
            stopPlaybackTickerQuietly()
            stopRecordingAmplitudes()
            startPlayback(id, file)
            playbackTracker.startPlayback(id)
        }
    }

    fun resumePlaybackIfPaused() {
        synchronized(playerLock) {
            resumePlaybackIfPausedLocked()
        }
    }

    private fun resumePlaybackIfPausedLocked() {
        val id = currentPlayingId ?: return
        val player = mediaPlayer ?: return
        if (!isMediaPlayerPlaying()) {
            try {
                player.start()
                playbackTracker.startPlayback(id)
                startPlaybackTicker(id)
            } catch (failure: Throwable) {
                Timber.e(failure, "Unable to resume playback")
            }
        }
    }

    private fun startPlayback(id: String, file: File) {
        val trackerTime = playbackTracker.getPlaybackTime(id) ?: 0
        val storedTime = playedStore.getPlaybackPosition(id)
        val currentPlaybackTime = maxOf(trackerTime, storedTime)
        maxListenedPositionMs = currentPlaybackTime

        try {
            FileInputStream(file).use { fis ->
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                            AudioAttributes.Builder()
                                    // Do not use CONTENT_TYPE_SPEECH / USAGE_VOICE_COMMUNICATION because we want to play loud here
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .build()
                    )
                    setDataSource(fis.fd)
                    prepare()
                    setOnCompletionListener { player ->
                        synchronized(playerLock) {
                            if (mediaPlayer !== player) return@synchronized
                            val completedId = currentPlayingId ?: id
                            markPlayedIfEligible(completedId, force = true)
                            playedStore.clearPlaybackPosition(completedId)
                            playbackTracker.stopPlaybackOrRecorder(completedId)
                            stopPlaybackTickerQuietly()
                            currentPlayingId = null
                            currentRoomId = null
                            currentTitle = null
                            maxListenedPositionMs = 0
                            tryOrNull { player.release() }
                            if (mediaPlayer === player) {
                                mediaPlayer = null
                            }
                        }
                    }
                    // Seek before start so UI does not briefly jump to 0.
                    if (currentPlaybackTime > 0) {
                        seekTo(currentPlaybackTime)
                    }
                    start()
                }
            }
            currentPlayingId = id
        } catch (failure: Throwable) {
            Timber.e(failure, "Unable to start playback")
            releaseMediaPlayerLocked(savePosition = false)
            throw VoiceFailure.UnableToPlay(failure)
        }
        startPlaybackTicker(id)
    }

    fun stopPlayback() {
        synchronized(playerLock) {
            playbackTracker.pausePlayback(AudioMessagePlaybackTracker.RECORDING_ID)
            releaseMediaPlayerLocked(savePosition = true)
            stopPlaybackTickerQuietly()
            currentPlayingId = null
            currentRoomId = null
            currentTitle = null
        }
    }

    /**
     * Pause playback without resetting the MediaPlayer so position is kept for background resume.
     */
    fun pausePlaybackKeepPosition() {
        synchronized(playerLock) {
            pausePlaybackKeepPositionLocked()
        }
    }

    private fun pausePlaybackKeepPositionLocked() {
        val id = currentPlayingId ?: return
        val player = mediaPlayer ?: return
        val currentPosition = tryOrNull { player.currentPosition } ?: playbackTracker.getPlaybackTime(id) ?: 0
        val totalDuration = tryOrNull { player.duration }?.takeIf { it > 0 } ?: 1
        maxListenedPositionMs = maxOf(maxListenedPositionMs, currentPosition)
        val percentage = currentPosition.toFloat() / totalDuration
        tryOrNull { player.pause() }
        // Mark paused BEFORE stopping ticker so the final tick (if any) is ignored.
        playbackTracker.updatePausedAtPlaybackTime(id, currentPosition, percentage)
        playedStore.savePlaybackPosition(id, currentPosition)
        // Pausing after listening far enough should still count as heard.
        markPlayedIfEligible(id, positionMs = maxListenedPositionMs, durationMs = totalDuration)
        stopPlaybackTickerQuietly()
        // Keep currentPlayingId and mediaPlayer instance
    }

    fun isPlaying(): Boolean {
        synchronized(playerLock) {
            return isMediaPlayerPlaying() ||
                    (currentPlayingId != null &&
                            playbackTracker.getPlaybackState(currentPlayingId!!) is AudioMessagePlaybackTracker.Listener.State.Playing)
        }
    }

    fun isPlayingOrPaused(): Boolean {
        synchronized(playerLock) {
            val id = currentPlayingId
            if (mediaPlayer != null && id != null) return true
            if (id == null) return false
            val state = playbackTracker.getPlaybackState(id)
            return state is AudioMessagePlaybackTracker.Listener.State.Playing ||
                    state is AudioMessagePlaybackTracker.Listener.State.Paused
        }
    }

    fun getCurrentPlayingId(): String? = synchronized(playerLock) { currentPlayingId }

    fun getCurrentRoomId(): String? = synchronized(playerLock) { currentRoomId }

    fun getCurrentTitle(): String? = synchronized(playerLock) { currentTitle }

    /**
     * Re-sync tracker UI state with the real MediaPlayer after returning to a chat room.
     */
    fun syncPlaybackTrackerWithPlayer() {
        synchronized(playerLock) {
            val id = currentPlayingId ?: return
            val player = mediaPlayer ?: return
            val position = tryOrNull { player.currentPosition } ?: return
            val duration = tryOrNull { player.duration }?.takeIf { it > 0 } ?: 1
            val percentage = position.toFloat() / duration
            if (isMediaPlayerPlaying()) {
                playbackTracker.updatePlayingAtPlaybackTime(id, position, percentage)
                if (playbackTicker == null) {
                    startPlaybackTicker(id)
                }
            } else {
                playbackTracker.updatePausedAtPlaybackTime(id, position, percentage)
            }
        }
    }

    fun getPlaybackPositionMs(): Int {
        synchronized(playerLock) {
            val id = currentPlayingId
            return tryOrNull { mediaPlayer?.currentPosition }
                    ?: id?.let { playbackTracker.getPlaybackTime(it) }
                    ?: id?.let { playedStore.getPlaybackPosition(it) }
                    ?: 0
        }
    }

    fun getPlaybackDurationMs(): Int {
        synchronized(playerLock) {
            return tryOrNull { mediaPlayer?.duration }?.takeIf { it > 0 } ?: 0
        }
    }

    fun seekTo(positionMs: Int) {
        synchronized(playerLock) {
            val id = currentPlayingId ?: return
            val player = mediaPlayer ?: return
            val duration = tryOrNull { player.duration }?.takeIf { it > 0 } ?: return
            val clamped = positionMs.coerceIn(0, duration)
            tryOrNull { player.seekTo(clamped) }
            val percentage = clamped.toFloat() / duration
            playedStore.savePlaybackPosition(id, clamped)
            if (isMediaPlayerPlaying()) {
                playbackTracker.updatePlayingAtPlaybackTime(id, clamped, percentage)
            } else {
                playbackTracker.updatePausedAtPlaybackTime(id, clamped, percentage)
            }
        }
    }

    fun movePlaybackTo(id: String, percentage: Float, totalDuration: Int) {
        synchronized(playerLock) {
            val toMillisecond = (totalDuration * percentage).toInt()
            playbackTracker.pauseAllPlaybacks()
            playedStore.savePlaybackPosition(id, toMillisecond)

            if (currentPlayingId == id && mediaPlayer != null) {
                tryOrNull { mediaPlayer?.seekTo(toMillisecond) }
                if (isMediaPlayerPlaying()) {
                    playbackTracker.updatePlayingAtPlaybackTime(id, toMillisecond, percentage)
                } else {
                    playbackTracker.updatePausedAtPlaybackTime(id, toMillisecond, percentage)
                }
            } else {
                tryOrNull { mediaPlayer?.pause() }
                playbackTracker.updatePausedAtPlaybackTime(id, toMillisecond, percentage)
                stopPlaybackTickerQuietly()
            }
        }
    }

    private fun startRecordingAmplitudes() {
        amplitudeTicker?.stop()
        amplitudeTicker = CountUpTimer(intervalInMs = 50).apply {
            tickListener = CountUpTimer.TickListener { onAmplitudeTick() }
            start()
        }
    }

    private fun onAmplitudeTick() {
        try {
            val maxAmplitude = voiceRecorder.getMaxAmplitude()
            amplitudeList.add(maxAmplitude)
            playbackTracker.updateCurrentRecording(AudioMessagePlaybackTracker.RECORDING_ID, amplitudeList)
        } catch (e: IllegalStateException) {
            Timber.e(e, "Cannot get max amplitude. Amplitude recording timer will be stopped.")
            stopRecordingAmplitudes()
        } catch (e: RuntimeException) {
            Timber.e(e, "Cannot get max amplitude (native error). Amplitude recording timer will be stopped.")
            stopRecordingAmplitudes()
        }
    }

    private fun stopRecordingAmplitudes() {
        amplitudeTicker?.stop()
        amplitudeTicker = null
    }

    private fun startPlaybackTicker(id: String) {
        playbackTicker?.tickListener = null
        playbackTicker?.stop()
        playbackTicker = CountUpTimer().apply {
            tickListener = CountUpTimer.TickListener { onPlaybackTick(id) }
            start()
        }
        onPlaybackTick(id)
    }

    private fun onPlaybackTick(id: String) {
        synchronized(playerLock) {
            val player = mediaPlayer
            // Prefer real MediaPlayer state. Tracker can be incorrectly marked Paused when leaving
            // the room (VoiceRecorderFragment.pauseAllPlaybacks) while background playback continues.
            if (player != null && isMediaPlayerPlaying()) {
                val currentPosition = tryOrNull { player.currentPosition } ?: return
                val totalDuration = tryOrNull { player.duration } ?: 0
                maxListenedPositionMs = maxOf(maxListenedPositionMs, currentPosition)
                val percentage = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f
                playbackTracker.updatePlayingAtPlaybackTime(id, currentPosition, percentage)
                playedStore.savePlaybackPosition(id, currentPosition)
                markPlayedIfEligible(id, positionMs = maxListenedPositionMs, durationMs = totalDuration)
                return
            }

            // Intentionally paused: keep MediaPlayer and progress; do not treat as completion.
            val trackerState = playbackTracker.getPlaybackState(id)
            if (trackerState is AudioMessagePlaybackTracker.Listener.State.Paused) {
                return
            }

            // Not playing and not marked paused → natural completion or unexpected stop.
            // MediaPlayer may report currentPosition=0 after completion on some devices, so also
            // use the max listened position tracked while playing.
            val reportedPosition = tryOrNull { player?.currentPosition } ?: 0
            val trackedPosition = playbackTracker.getPlaybackTime(id) ?: 0
            val storedPosition = playedStore.getPlaybackPosition(id)
            val currentPosition = maxOf(reportedPosition, trackedPosition, storedPosition, maxListenedPositionMs)
            val totalDuration = tryOrNull { player?.duration } ?: 0
            val reachedEnd = totalDuration > 0 && currentPosition >= (totalDuration - 400)
            if (id != AudioMessagePlaybackTracker.RECORDING_ID) {
                if (reachedEnd) {
                    markPlayedIfEligible(id, force = true)
                    playedStore.clearPlaybackPosition(id)
                } else {
                    markPlayedIfEligible(id, positionMs = currentPosition, durationMs = totalDuration)
                    playedStore.savePlaybackPosition(id, currentPosition)
                }
            }
            playbackTracker.stopPlaybackOrRecorder(id)
            stopPlaybackTickerQuietly()
            currentPlayingId = null
            currentRoomId = null
            currentTitle = null
            maxListenedPositionMs = 0
            releaseMediaPlayerLocked(savePosition = false)
        }
    }

    /**
     * Mark a voice/audio message as heard once playback completes or the user has listened
     * through most of it (including after pausing).
     */
    private fun markPlayedIfEligible(
            id: String,
            positionMs: Int = maxListenedPositionMs,
            durationMs: Int = tryOrNull { mediaPlayer?.duration } ?: 0,
            force: Boolean = false,
    ) {
        if (id == AudioMessagePlaybackTracker.RECORDING_ID) return
        if (force) {
            playedStore.markPlayed(id)
            return
        }
        if (durationMs <= 0) return
        val listenedEnough = positionMs >= (durationMs - 400) ||
                positionMs.toFloat() / durationMs >= PLAYED_PERCENT_THRESHOLD
        if (listenedEnough) {
            playedStore.markPlayed(id)
        }
    }

    /**
     * Stop ticker without invoking a final tick that can race with pause/stop state updates.
     */
    private fun stopPlaybackTickerQuietly() {
        playbackTicker?.tickListener = null
        playbackTicker?.stop()
        playbackTicker = null
    }

    private fun stopPlaybackTicker() {
        stopPlaybackTickerQuietly()
    }

    private fun releaseMediaPlayerLocked(savePosition: Boolean) {
        val id = currentPlayingId
        val player = mediaPlayer
        if (savePosition && id != null && player != null) {
            val position = tryOrNull { player.currentPosition }
                    ?: playbackTracker.getPlaybackTime(id)
                    ?: 0
            playedStore.savePlaybackPosition(id, position)
        }
        tryOrNull { player?.stop() }
        tryOrNull { player?.release() }
        mediaPlayer = null
    }

    private fun isMediaPlayerPlaying(): Boolean {
        return tryOrNull { mediaPlayer?.isPlaying }.orFalse()
    }

    fun stopTracking() {
        playbackTracker.unregisterListeners()
    }

    fun stopAllVoiceActions(deleteRecord: Boolean = true): MultiPickerAudioType? {
        val audioType = stopRecording()
        stopPlayback()
        if (deleteRecord) {
            deleteRecording()
        }
        return audioType
    }

    companion object {
        private const val PLAYED_PERCENT_THRESHOLD = 0.85f
    }
}
