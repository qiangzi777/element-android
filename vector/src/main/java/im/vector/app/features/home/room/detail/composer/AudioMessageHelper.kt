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
    private val voiceRecorder: VoiceRecorder by lazy { voiceRecorderProvider.provideVoiceRecorder() }

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

    fun startOrPausePlayback(id: String, file: File) {
        val playbackState = playbackTracker.getPlaybackState(id)
        val wasPlaying = playbackState is AudioMessagePlaybackTracker.Listener.State.Playing
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopPlaybackTicker()
        stopRecordingAmplitudes()
        currentPlayingId = null
        if (wasPlaying) {
            playbackTracker.pausePlayback(id)
            val position = playbackTracker.getPlaybackTime(id) ?: 0
            playedStore.savePlaybackPosition(id, position)
        } else {
            startPlayback(id, file)
            playbackTracker.startPlayback(id)
        }
    }

    fun resumePlaybackIfPaused() {
        val id = currentPlayingId ?: return
        val player = mediaPlayer ?: return
        if (!player.isPlaying) {
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
                    start()
                    seekTo(currentPlaybackTime)
                }
            }
            currentPlayingId = id
        } catch (failure: Throwable) {
            Timber.e(failure, "Unable to start playback")
            throw VoiceFailure.UnableToPlay(failure)
        }
        startPlaybackTicker(id)
    }

    fun stopPlayback() {
        playbackTracker.pausePlayback(AudioMessagePlaybackTracker.RECORDING_ID)
        currentPlayingId?.let { id ->
            val position = mediaPlayer?.currentPosition ?: playbackTracker.getPlaybackTime(id) ?: 0
            playedStore.savePlaybackPosition(id, position)
        }
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        stopPlaybackTicker()
        currentPlayingId = null
    }

    /**
     * Pause playback without resetting the MediaPlayer so position is kept for background resume.
     */
    fun pausePlaybackKeepPosition() {
        val id = currentPlayingId ?: return
        val player = mediaPlayer ?: return
        val currentPosition = tryOrNull { player.currentPosition } ?: 0
        val totalDuration = tryOrNull { player.duration }?.takeIf { it > 0 } ?: 1
        val percentage = currentPosition.toFloat() / totalDuration
        tryOrNull { player.pause() }
        playbackTracker.updatePausedAtPlaybackTime(id, currentPosition, percentage)
        playedStore.savePlaybackPosition(id, currentPosition)
        stopPlaybackTicker()
        // Keep currentPlayingId and mediaPlayer instance
    }

    fun isPlaying(): Boolean {
        return mediaPlayer?.isPlaying.orFalse() ||
                (currentPlayingId != null && playbackTracker.getPlaybackState(currentPlayingId!!) is AudioMessagePlaybackTracker.Listener.State.Playing)
    }

    fun isPlayingOrPaused(): Boolean {
        val id = currentPlayingId ?: return false
        val state = playbackTracker.getPlaybackState(id)
        return state is AudioMessagePlaybackTracker.Listener.State.Playing ||
                state is AudioMessagePlaybackTracker.Listener.State.Paused ||
                mediaPlayer != null
    }

    fun getCurrentPlayingId(): String? = currentPlayingId

    fun movePlaybackTo(id: String, percentage: Float, totalDuration: Int) {
        val toMillisecond = (totalDuration * percentage).toInt()
        playbackTracker.pauseAllPlaybacks()
        playedStore.savePlaybackPosition(id, toMillisecond)

        if (currentPlayingId == id) {
            mediaPlayer?.seekTo(toMillisecond)
            playbackTracker.updatePlayingAtPlaybackTime(id, toMillisecond, percentage)
        } else {
            mediaPlayer?.pause()
            playbackTracker.updatePausedAtPlaybackTime(id, toMillisecond, percentage)
            stopPlaybackTicker()
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
        playbackTicker?.stop()
        playbackTicker = CountUpTimer().apply {
            tickListener = CountUpTimer.TickListener { onPlaybackTick(id) }
            start()
        }
        onPlaybackTick(id)
    }

    private fun onPlaybackTick(id: String) {
        val player = mediaPlayer
        if (player?.isPlaying.orFalse()) {
            val currentPosition = player.currentPosition
            val totalDuration = player.duration
            val percentage = if (totalDuration > 0) currentPosition.toFloat() / totalDuration else 0f
            playbackTracker.updatePlayingAtPlaybackTime(id, currentPosition, percentage)
            playedStore.savePlaybackPosition(id, currentPosition)
        } else {
            val currentPosition = tryOrNull { player?.currentPosition } ?: 0
            val totalDuration = tryOrNull { player?.duration } ?: 0
            val reachedEnd = totalDuration > 0 && currentPosition >= (totalDuration - 250)
            if (reachedEnd && id != AudioMessagePlaybackTracker.RECORDING_ID) {
                playedStore.markPlayed(id)
                playedStore.clearPlaybackPosition(id)
            } else if (id != AudioMessagePlaybackTracker.RECORDING_ID) {
                playedStore.savePlaybackPosition(id, currentPosition)
            }
            playbackTracker.stopPlaybackOrRecorder(id)
            stopPlaybackTicker()
            currentPlayingId = null
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    private fun stopPlaybackTicker() {
        playbackTicker?.stop()
        playbackTicker = null
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
}

