// SPDX-FileCopyrightText: 2026 KR Climax Scramble Mobile contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.krclimax

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.model.MinimalDocumentFile
import org.yuzu.yuzu_emu.utils.FileUtil

/**
 * Two permissions are needed:
 *  1. "All files access" so the native core can use KRiderClimax/saves directly
 *     (the same mechanism Eden uses for its custom save path) and so we can create
 *     the folder layout and copy keys/firmware.
 *  2. A Storage Access Framework grant on KRiderClimax/ — Eden's game scanner,
 *     metadata reader and content installer are built around content:// URIs,
 *     so the game and update files go through that proven path.
 */
object KrStorage {
    private const val PREF_TREE = "kr_tree_uri"

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                .setData("package:${context.packageName}".toUri())
        } else {
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${context.packageName}".toUri())
        }

    /** Initial location for the folder picker: KRiderClimax on internal storage. */
    fun initialPickerUri(): Uri =
        DocumentsContract.buildDocumentUri(KrPaths.EXTERNAL_STORAGE_AUTHORITY, KrPaths.TREE_DOC_ID)

    fun isExpectedTree(uri: Uri): Boolean =
        runCatching {
            uri.authority == KrPaths.EXTERNAL_STORAGE_AUTHORITY &&
                DocumentsContract.getTreeDocumentId(uri) == KrPaths.TREE_DOC_ID
        }.getOrDefault(false)

    fun saveTree(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF_TREE, uri.toString())
        }
    }

    /** The granted KRiderClimax tree, or null when the grant is missing or was revoked. */
    fun grantedTree(context: Context): Uri? {
        val stored = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(PREF_TREE, null) ?: return null
        val uri = stored.toUri()
        val stillGranted = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (stillGranted && FileUtil.isTreeUriValid(uri)) uri else null
    }

    /** Document URI of a direct child folder (e.g. "Updates") inside the granted tree. */
    fun childFolderUri(tree: Uri, name: String): Uri? {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            ?: return null
        val child = FileUtil.listFiles(tree).firstOrNull {
            it.isDirectory && it.filename.equals(name, ignoreCase = true)
        }
        return child?.uri ?: DocumentsContract.buildDocumentUriUsingTree(tree, "$treeId/$name")
    }

    fun listGameDocuments(folder: Uri): List<MinimalDocumentFile> =
        FileUtil.listFiles(folder).filter {
            !it.isDirectory &&
                it.filename.substringAfterLast('.', "").lowercase() in KrPaths.gameExtensions
        }
}
