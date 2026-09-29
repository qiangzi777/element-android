/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home.room.detail.timeline.helper

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.core.view.isVisible
import androidx.vectordrawable.graphics.drawable.Animatable2Compat
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import dagger.hilt.android.scopes.ActivityScoped
import im.vector.app.R
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.features.home.room.detail.timeline.item.MessageFileItem
import org.matrix.android.sdk.api.session.file.ContentDownloadStateTracker
import javax.inject.Inject

@ActivityScoped
class ContentDownloadStateTrackerBinder @Inject constructor(private val activeSessionHolder: ActiveSessionHolder) {

    private val updateListeners = mutableMapOf<String, ContentDownloadUpdater>()

    fun bind(
            mxcUrl: String,
            holder: MessageFileItem.Holder
    ) {
        bind(mxcUrl, holder.fileImageView, holder.fileDownloadProgress)
    }

    fun bind(
            mxcUrl: String,
            imageView: ImageView,
            progressBar: ProgressBar,
    ) {
        activeSessionHolder.getSafeActiveSession()?.also { session ->
            val downloadStateTracker = session.contentDownloadProgressTracker()
            updateListeners[mxcUrl]?.also {
                it.stop()
                downloadStateTracker.unTrack(mxcUrl, it)
            }
            val updateListener = ContentDownloadUpdater(imageView, progressBar)
            updateListeners[mxcUrl] = updateListener
            downloadStateTracker.track(mxcUrl, updateListener)
        }
    }

    fun unbind(mxcUrl: String) {
        activeSessionHolder.getSafeActiveSession()?.also { session ->
            val downloadStateTracker = session.contentDownloadProgressTracker()
            updateListeners[mxcUrl]?.also {
                it.stop()
                downloadStateTracker.unTrack(mxcUrl, it)
            }
            updateListeners.remove(mxcUrl)
        }
    }

    fun clear() {
        activeSessionHolder.getSafeActiveSession()?.also {
            it.contentDownloadProgressTracker().clear()
        }
        updateListeners.clear()
    }
}

private class ContentDownloadUpdater(
        private val imageView: ImageView,
        private val progressBar: ProgressBar,
) : ContentDownloadStateTracker.UpdateListener {

    override fun onDownloadStateUpdate(state: ContentDownloadStateTracker.State) {
        when (state) {
            ContentDownloadStateTracker.State.Idle -> handleIdle()
            is ContentDownloadStateTracker.State.Downloading -> handleProgress(state)
            ContentDownloadStateTracker.State.Decrypting -> handleDecrypting()
            ContentDownloadStateTracker.State.Success -> handleSuccess()
            is ContentDownloadStateTracker.State.Failure -> handleFailure()
        }
    }

    private var animatedDrawable: AnimatedVectorDrawableCompat? = null
    private var animationLoopCallback = object : Animatable2Compat.AnimationCallback() {
        override fun onAnimationEnd(drawable: Drawable?) {
            animatedDrawable?.start()
        }
    }

    fun stop() {
        animatedDrawable?.unregisterAnimationCallback(animationLoopCallback)
        animatedDrawable?.stop()
        animatedDrawable = null
    }

    private fun handleIdle() {
        progressBar.isVisible = true
        progressBar.progress = 0
        progressBar.isIndeterminate = false
        imageView.setImageResource(R.drawable.ic_download)
    }

    private fun handleDecrypting() {
        progressBar.isVisible = true
        progressBar.isIndeterminate = true
    }

    private fun handleProgress(state: ContentDownloadStateTracker.State.Downloading) {
        doHandleProgress(state.current, state.total)
    }

    private fun doHandleProgress(current: Long, total: Long) {
        val percent = 100L * (current.toFloat() / total.toFloat())
        progressBar.isVisible = true
        progressBar.isIndeterminate = false
        progressBar.progress = percent.toInt()
        if (animatedDrawable == null) {
            animatedDrawable = AnimatedVectorDrawableCompat.create(imageView.context, R.drawable.ic_download_anim)
            imageView.setImageDrawable(animatedDrawable)
            animatedDrawable?.start()
            animatedDrawable?.registerAnimationCallback(animationLoopCallback)
        }
    }

    private fun handleFailure() {
        stop()
        progressBar.isVisible = true
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        imageView.setImageResource(R.drawable.ic_close_round)
    }

    private fun handleSuccess() {
        stop()
        progressBar.isVisible = false
        progressBar.isIndeterminate = false
        progressBar.progress = 0
        imageView.visibility = View.GONE
    }
}
