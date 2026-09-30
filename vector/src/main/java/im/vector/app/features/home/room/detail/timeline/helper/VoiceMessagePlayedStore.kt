/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import im.vector.app.core.di.DefaultPreferences
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceMessagePlayedStore @Inject constructor(
        @DefaultPreferences private val preferences: SharedPreferences,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    fun markPlayed(eventId: String): Boolean {
        if (eventId.isBlank() || hasPlayed(eventId)) {
            return false
        }
        preferences.edit {
            putBoolean(playedKey(eventId), true)
        }
        mainHandler.post {
            listeners.forEach { it.onPlayed(eventId) }
        }
        return true
    }

    fun hasPlayed(eventId: String): Boolean {
        if (eventId.isBlank()) return false
        return preferences.getBoolean(playedKey(eventId), false)
    }

    fun savePlaybackPosition(eventId: String, positionMs: Int) {
        preferences.edit {
            putInt(positionKey(eventId), positionMs.coerceAtLeast(0))
        }
    }

    fun getPlaybackPosition(eventId: String): Int {
        return preferences.getInt(positionKey(eventId), 0)
    }

    fun clearPlaybackPosition(eventId: String) {
        preferences.edit {
            remove(positionKey(eventId))
        }
    }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun interface Listener {
        fun onPlayed(eventId: String)
    }

    private fun playedKey(eventId: String) = PREFIX_PLAYED + eventId

    private fun positionKey(eventId: String) = PREFIX_POSITION + eventId

    companion object {
        private const val PREFIX_PLAYED = "voice_msg_played_"
        private const val PREFIX_POSITION = "voice_msg_position_"
    }
}
