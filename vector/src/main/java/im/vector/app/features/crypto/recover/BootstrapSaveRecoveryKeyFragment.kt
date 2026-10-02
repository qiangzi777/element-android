/*
 * Copyright 2020-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.crypto.recover

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import com.airbnb.mvrx.parentFragmentViewModel
import com.airbnb.mvrx.withState
import dagger.hilt.android.AndroidEntryPoint
import im.vector.app.core.extensions.registerStartForActivityResult
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.utils.PERMISSIONS_FOR_WRITING_FILES
import im.vector.app.core.utils.checkPermissions
import im.vector.app.core.utils.registerForPermissionsResult
import im.vector.app.core.utils.startSharePlainTextIntent
import im.vector.app.databinding.FragmentBootstrapSaveKeyBinding
import im.vector.lib.strings.CommonStrings

@AndroidEntryPoint
class BootstrapSaveRecoveryKeyFragment :
        VectorBaseFragment<FragmentBootstrapSaveKeyBinding>() {

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentBootstrapSaveKeyBinding {
        return FragmentBootstrapSaveKeyBinding.inflate(inflater, container, false)
    }

    val sharedViewModel: BootstrapSharedViewModel by parentFragmentViewModel()

    private var autoSaveAttempted = false

    private val writePermissionLauncher = registerForPermissionsResult { _, _ ->
        // MediaStore can still succeed without the legacy permission; always try.
        sharedViewModel.handle(BootstrapActions.SaveToDownloads)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        views.recoverySave.views.bottomSheetActionClickableZone.debouncedClicks { saveToDownloads() }
        views.recoveryCopy.views.bottomSheetActionClickableZone.debouncedClicks { shareRecoveryKey() }
        views.recoveryContinue.views.bottomSheetActionClickableZone.debouncedClicks {
            sharedViewModel.handle(BootstrapActions.Completed)
        }
    }

    private fun saveToDownloads() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val granted = checkPermissions(
                    PERMISSIONS_FOR_WRITING_FILES,
                    requireActivity(),
                    writePermissionLauncher
            )
            if (!granted) return
        }
        sharedViewModel.handle(BootstrapActions.SaveToDownloads)
    }

    private val copyStartForActivityResult = registerStartForActivityResult { activityResult ->
        if (activityResult.resultCode == android.app.Activity.RESULT_OK) {
            sharedViewModel.handle(BootstrapActions.RecoveryKeySaved)
        }
    }

    private fun shareRecoveryKey() = withState(sharedViewModel) { state ->
        val recoveryKey = state.recoveryKeyCreationInfo?.recoveryKey?.formatRecoveryKey()
                ?: return@withState

        startSharePlainTextIntent(
                requireContext(),
                copyStartForActivityResult,
                context?.getString(CommonStrings.keys_backup_setup_step3_share_intent_chooser_title),
                recoveryKey,
                context?.getString(CommonStrings.recovery_key)
        )
    }

    override fun invalidate() = withState(sharedViewModel) { state ->
        val step = state.step
        if (step !is BootstrapStep.SaveRecoveryKey) return@withState

        views.bootstrapSaveText.text = getString(CommonStrings.recovery_key_save_to_downloads_hint)
        views.bootstrapRecoveryKeyText.text = state.recoveryKeyCreationInfo?.recoveryKey?.formatRecoveryKey()
        views.recoveryContinue.isVisible = step.isSaved
        views.recoverySave.title = if (step.isSaved) {
            getString(CommonStrings.recovery_key_saved_to_downloads)
        } else {
            getString(CommonStrings.recovery_key_save_to_downloads)
        }
        views.bootstrapSaveText.giveAccessibilityFocusOnce()

        if (!step.isSaved && !autoSaveAttempted) {
            autoSaveAttempted = true
            saveToDownloads()
        }
    }
}
