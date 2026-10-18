// SPDX-FileCopyrightText: 2026 KR Climax Scramble Mobile contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.krclimax

import android.os.Environment
import java.io.File

/**
 * Fixed folder layout on shared storage:
 *
 *   /storage/emulated/0/KRiderClimax/            game .nsp / .xci (base game)
 *   /storage/emulated/0/KRiderClimax/Updates/    update / DLC .nsp
 *   /storage/emulated/0/KRiderClimax/saves/      save data (Eden save root)
 *   /storage/emulated/0/KRiderClimax/keys/       prod.keys (+ title.keys) dumped from your Switch
 *   /storage/emulated/0/KRiderClimax/firmware/   firmware .zip or loose .nca files (optional)
 *   /storage/emulated/0/KRiderClimax/drivers/    extra adrenotools driver .zip files (optional)
 *   /storage/emulated/0/KRiderClimax/logs/       exported logs
 */
object KrPaths {
    const val FOLDER_NAME = "KRiderClimax"
    const val TREE_DOC_ID = "primary:$FOLDER_NAME"
    const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    val root: File get() = File(Environment.getExternalStorageDirectory(), FOLDER_NAME)
    val saves: File get() = File(root, "saves")
    val updates: File get() = File(root, "Updates")
    val keys: File get() = File(root, "keys")
    val firmware: File get() = File(root, "firmware")
    val drivers: File get() = File(root, "drivers")
    val logs: File get() = File(root, "logs")

    val gameExtensions = setOf("nsp", "xci")

    fun ensureDirs(): Boolean {
        var ok = true
        for (dir in listOf(root, saves, updates, keys, firmware, drivers, logs)) {
            if (!dir.isDirectory && !dir.mkdirs()) ok = false
        }
        val readme = File(root, "BACA-SAYA.txt")
        if (ok && !readme.exists()) {
            runCatching { readme.writeText(README) }
        }
        return ok
    }

    /** Base-game candidates placed directly in the root folder. */
    fun rootGameFiles(): List<File> =
        root.listFiles()?.filter { it.isFile && it.extension.lowercase() in gameExtensions }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun updateFiles(): List<File> =
        updates.listFiles()?.filter { it.isFile && it.extension.lowercase() in gameExtensions }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()

    private val README = """
        KR Climax Scramble - susunan folder
        ====================================
        KRiderClimax/            -> taruh file game .nsp / .xci (game utama) di sini
        KRiderClimax/Updates/    -> file update / DLC .nsp
        KRiderClimax/saves/      -> data save (dibuat otomatis oleh aplikasi)
        KRiderClimax/keys/       -> prod.keys (dan title.keys) hasil dump dari Switch Anda sendiri
        KRiderClimax/firmware/   -> firmware .zip atau file .nca (opsional, dari Switch Anda)
        KRiderClimax/drivers/    -> driver GPU tambahan (.zip adrenotools, opsional)
        KRiderClimax/logs/       -> log yang diekspor dari aplikasi

        Aplikasi ini TIDAK menyertakan game, keys, maupun firmware.
        Gunakan hanya file hasil dump dari kartrid/konsol milik Anda sendiri.
    """.trimIndent()
}
