/*
 * Copyright 2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.update

/**
 * Server-side update manifest hosted at:
 * `{homeserverOrigin}/element-classic/update.json`
 */
data class AppUpdateManifest(
        val versionName: String,
        val versionCode: Long,
        val forceUpdate: Boolean = false,
        val releaseNotes: String? = null,
        val apkUrl: String,
        val apkSha256: String,
        val apkSize: Long? = null,
)

data class PreparedAppUpdate(
        val manifest: AppUpdateManifest,
        val apkFile: java.io.File,
)
