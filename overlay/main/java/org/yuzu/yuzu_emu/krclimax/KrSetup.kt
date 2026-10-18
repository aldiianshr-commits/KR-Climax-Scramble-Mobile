// SPDX-FileCopyrightText: 2026 KR Climax Scramble Mobile contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.krclimax

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.model.GameDir
import org.yuzu.yuzu_emu.model.Patch
import org.yuzu.yuzu_emu.model.PatchType
import org.yuzu.yuzu_emu.utils.DirectoryInitialization
import org.yuzu.yuzu_emu.utils.GameHelper
import org.yuzu.yuzu_emu.utils.GpuDriverHelper
import org.yuzu.yuzu_emu.utils.Log
import org.yuzu.yuzu_emu.utils.NativeConfig

/** All filesystem / config work behind the launcher. Call from a background thread. */
object KrSetup {
    private const val PREF_SELECTED_GAME = "kr_selected_game_path"
    private const val PREF_DRIVER_INIT = "kr_turnip_initialized"
    const val BUNDLED_DRIVER_ASSET = "kr_drivers/turnip.zip"
    private const val BUNDLED_DRIVER_FILE = "kr_turnip_bundled.zip"

    private val titleMatcher = Regex(
        "climax\\s*scramble|クライマックス\\s*スクランブル|クライマックススクランブル",
        RegexOption.IGNORE_CASE
    )

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    private fun ensureGlobalConfigActive() {
        if (NativeConfig.isPerGameConfigLoaded()) {
            NativeConfig.unloadPerGameConfig()
        }
    }

    // ---------------------------------------------------------------- saves

    /** Points Eden's save root at KRiderClimax/saves (migrating existing saves once). */
    fun ensureSaveDir(): Boolean {
        val target = KrPaths.saves
        if (!target.isDirectory && !target.mkdirs()) return false
        val targetPath = target.absolutePath
        val current = NativeConfig.getSaveDir()
        if (File(current).absolutePath == targetPath) return true

        val oldSaves = File(current, "user/save")
        val newSaves = File(target, "user/save")
        if (oldSaves.isDirectory && (oldSaves.list()?.isNotEmpty() == true) &&
            (newSaves.list()?.isEmpty() != false)
        ) {
            runCatching { oldSaves.copyRecursively(newSaves, overwrite = false) }
                .onFailure { Log.warning("[KR] save migration failed: ${it.message}") }
        }

        ensureGlobalConfigActive()
        NativeConfig.setSaveDir(targetPath)
        NativeConfig.saveGlobalConfig()
        NativeConfig.reloadGlobalConfig()
        return File(NativeConfig.getSaveDir()).absolutePath == targetPath
    }

    fun gameSaveFolder(game: Game): File? = runCatching {
        File(NativeConfig.getSaveDir() + NativeLibrary.getSavePath(game.programId))
    }.getOrNull()

    // ---------------------------------------------------------------- keys

    enum class KeyState { OK, MISSING_FILE, INVALID }

    private fun findKeyFile(name: String): File? =
        listOf(File(KrPaths.keys, name), File(KrPaths.root, name)).firstOrNull { it.isFile }

    /** Copies prod.keys / title.keys from KRiderClimax/keys into Eden's key folder. */
    fun syncKeys(): KeyState {
        val prod = findKeyFile("prod.keys")
        val edenKeys = File(DirectoryInitialization.userDirectory!!, "keys")
        if (prod == null) {
            return if (File(edenKeys, "prod.keys").isFile && NativeLibrary.reloadKeys()) {
                KeyState.OK
            } else {
                KeyState.MISSING_FILE
            }
        }
        edenKeys.mkdirs()
        var changed = false
        for (src in listOfNotNull(prod, findKeyFile("title.keys"))) {
            val dst = File(edenKeys, src.name)
            if (!dst.isFile || dst.length() != src.length() || dst.lastModified() < src.lastModified()) {
                src.copyTo(dst, overwrite = true)
                changed = true
            }
        }
        val ok = NativeLibrary.reloadKeys()
        if (changed) Log.info("[KR] keys copied from ${prod.parent}")
        return if (ok) KeyState.OK else KeyState.INVALID
    }

    // ---------------------------------------------------------------- firmware

    fun firmwareSourceAvailable(): Boolean =
        KrPaths.firmware.listFiles()?.any {
            it.isFile && (it.extension.equals("zip", true) || it.extension.equals("nca", true))
        } == true

    fun firmwareInstalledVersion(): String? =
        if (NativeLibrary.isFirmwareAvailable()) {
            runCatching { NativeLibrary.firmwareVersion() }.getOrNull()?.ifEmpty { null } ?: "?"
        } else {
            null
        }

    /**
     * Installs firmware from KRiderClimax/firmware: either the newest .zip in it or the
     * loose .nca files. Mirrors Eden's own firmware installer (NCA files into the NAND
     * registered folder, then a system re-init).
     */
    fun installFirmware(context: Context, progress: (String) -> Unit): Result<String> = runCatching {
        val dir = KrPaths.firmware
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        val zip = files.filter { it.extension.equals("zip", true) }.maxByOrNull { it.lastModified() }
        val ncas = files.filter { it.extension.equals("nca", true) }
        require(zip != null || ncas.isNotEmpty()) { "Folder firmware kosong" }

        val staging = File(context.cacheDir, "kr_firmware")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            if (zip != null) {
                progress("Mengekstrak ${zip.name}…")
                ZipInputStream(zip.inputStream().buffered()).use { zin ->
                    var entry = zin.nextEntry
                    var count = 0
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = entry.name.substringAfterLast('/')
                            require(name.endsWith(".nca", true)) {
                                "Isi zip bukan firmware Switch (ditemukan $name)"
                            }
                            File(staging, name).outputStream().use { zin.copyTo(it) }
                            count++
                            if (count % 20 == 0) progress("Mengekstrak… $count file")
                        }
                        entry = zin.nextEntry
                    }
                }
            } else {
                progress("Menyalin ${ncas.size} file .nca…")
                ncas.forEach { it.copyTo(File(staging, it.name), overwrite = true) }
            }
            val ncaCount = staging.list()?.size ?: 0
            require(ncaCount > 0) { "Tidak ada file .nca" }

            progress("Memasang firmware ($ncaCount NCA)…")
            val dest = File(NativeConfig.getNandDir() + "/system/Contents/registered/")
            dest.deleteRecursively()
            dest.mkdirs()
            staging.copyRecursively(dest, overwrite = true)
            NativeLibrary.initializeSystem(true)
            firmwareInstalledVersion() ?: "terpasang"
        } finally {
            staging.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------- GPU driver

    data class DriverOption(val path: String, val label: String)

    fun isAdreno(): Boolean = runCatching { GpuDriverHelper.supportsCustomDriverLoading() }
        .getOrDefault(false)

    fun gpuModel(): String? = runCatching {
        GpuDriverHelper.getGpuModel(hookLibPath = GpuDriverHelper.hookLibPath!!)
    }.getOrNull()

    /**
     * Copies the Turnip build bundled in the APK (and any zips the user put in
     * KRiderClimax/drivers) into Eden's driver store. On the first run on an Adreno
     * device the bundled Turnip becomes the selected driver.
     */
    fun syncDrivers(context: Context) {
        val storeDir = File(GpuDriverHelper.driverStoragePath)
        storeDir.mkdirs()

        val bundled = File(storeDir, BUNDLED_DRIVER_FILE)
        val hasAsset = runCatching { context.assets.open(BUNDLED_DRIVER_ASSET).close() }.isSuccess
        if (hasAsset) {
            val assetSize = runCatching {
                context.assets.openFd(BUNDLED_DRIVER_ASSET).use { it.length }
            }.getOrDefault(-1L)
            if (!bundled.isFile || (assetSize > 0 && bundled.length() != assetSize)) {
                context.assets.open(BUNDLED_DRIVER_ASSET).use { input ->
                    bundled.outputStream().use { input.copyTo(it) }
                }
            }
        }

        KrPaths.drivers.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }
            ?.forEach { src ->
                val dst = File(storeDir, src.name)
                if (!dst.isFile || dst.length() != src.length()) {
                    src.copyTo(dst, overwrite = true)
                    if (GpuDriverHelper.getMetadataFromZip(dst).name == null) dst.delete()
                }
            }

        val prefs = prefs(context)
        if (!prefs.getBoolean(PREF_DRIVER_INIT, false)) {
            if (isAdreno() && bundled.isFile && GpuDriverHelper.getMetadataFromZip(bundled).name != null) {
                ensureGlobalConfigActive()
                StringSetting.DRIVER_PATH.setString(bundled.path)
                NativeConfig.saveGlobalConfig()
                Log.info("[KR] Turnip selected as default driver")
            }
            prefs.edit { putBoolean(PREF_DRIVER_INIT, true) }
        }
    }

    fun driverOptions(systemLabel: String): List<DriverOption> {
        val list = mutableListOf(DriverOption("", systemLabel))
        GpuDriverHelper.getDrivers().forEach { (path, meta) ->
            val version = meta.version?.let { " $it" } ?: ""
            list.add(DriverOption(path, "${meta.name}$version"))
        }
        return list
    }

    fun currentDriverLabel(systemLabel: String): String {
        val path = StringSetting.DRIVER_PATH.getString()
        if (path.isEmpty() || !File(path).isFile) return systemLabel
        val meta = GpuDriverHelper.getMetadataFromZip(File(path))
        return listOfNotNull(meta.name, meta.version).joinToString(" ").ifEmpty { File(path).name }
    }

    fun selectDriver(path: String) {
        ensureGlobalConfigActive()
        StringSetting.DRIVER_PATH.setString(path)
        NativeConfig.saveGlobalConfig()
    }

    // ---------------------------------------------------------------- game + content

    /** Registers KRiderClimax as a (deep-scanned) game folder and Updates/ as external content. */
    fun registerFolders(tree: Uri) {
        ensureGlobalConfigActive()
        val treeString = tree.toString()
        val dirs = NativeConfig.getGameDirs().toMutableList()
        val existing = dirs.firstOrNull { it.uriString == treeString }
        var changed = false
        if (existing == null) {
            dirs.add(GameDir(treeString, true))
            changed = true
        } else if (!existing.deepScan) {
            existing.deepScan = true
            changed = true
        }
        if (changed) NativeConfig.setGameDirs(dirs.toTypedArray())

        val updatesUri = KrStorage.childFolderUri(tree, KrPaths.updates.name)?.toString()
        if (updatesUri != null) {
            val ext = NativeConfig.getExternalContentDirs().toMutableList()
            if (updatesUri !in ext) {
                ext.add(updatesUri)
                NativeConfig.setExternalContentDirs(ext.toTypedArray())
                changed = true
            }
        }
        if (changed) NativeConfig.saveGlobalConfig()
    }

    fun markEdenSetupDone(context: Context) {
        prefs(context).edit { putBoolean(Settings.PREF_FIRST_APP_LAUNCH, false) }
    }

    data class GameScan(val all: List<Game>, val matches: List<Game>)

    /** Runs Eden's scanner over the registered folders and picks out Climax Scramble. */
    fun scanGames(tree: Uri): GameScan {
        val treePrefix = tree.toString()
        val treeId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(tree)
        }.getOrDefault(KrPaths.TREE_DOC_ID)
        val updatesPrefix = "$treeId/${KrPaths.updates.name}/"
        val games = GameHelper.getGames().filter { game ->
            // Only titles from our folder, and never something inside Updates/.
            game.path.startsWith(treePrefix) &&
                !Uri.decode(game.path).contains(updatesPrefix, ignoreCase = true) &&
                !game.isHomebrew
        }
        val matches = games.filter { titleMatcher.containsMatchIn(it.title) }
        return GameScan(games, matches)
    }

    fun selectedGame(context: Context, scan: GameScan): Game? {
        val saved = prefs(context).getString(PREF_SELECTED_GAME, null)
        scan.all.firstOrNull { it.path == saved }?.let { return it }
        return when {
            scan.matches.size == 1 -> scan.matches.first()
            scan.matches.isEmpty() && scan.all.size == 1 -> scan.all.first()
            // Prefer the original Climax Scramble over the Zi-O sequel when both exist.
            scan.matches.size > 1 ->
                scan.matches.firstOrNull { !it.title.contains("zi-o", true) && !it.title.contains("ジオウ") }
                    ?: scan.matches.first()
            else -> null
        }
    }

    fun rememberGame(context: Context, game: Game) {
        prefs(context).edit { putString(PREF_SELECTED_GAME, game.path) }
    }

    fun markLaunched(context: Context, game: Game) {
        prefs(context).edit { putLong(game.keyLastPlayedTime, System.currentTimeMillis()) }
    }

    data class AddonInfo(val update: String?, val dlcCount: Int)

    fun addonInfo(game: Game): AddonInfo {
        val patches: Array<Patch> = runCatching {
            NativeLibrary.getPatchesForFile(game.path, game.programId)
        }.getOrNull() ?: emptyArray()
        val update = patches.filter { PatchType.from(it.type) == PatchType.Update && it.enabled }
            .maxByOrNull { it.numericVersion }?.version
        val dlc = patches.count { PatchType.from(it.type) == PatchType.DLC }
        return AddonInfo(update, dlc)
    }

    // ---------------------------------------------------------------- logs

    fun exportLogs(): File? {
        val logDir = File(DirectoryInitialization.userDirectory!!, "log")
        val logs = logDir.listFiles()?.filter { it.isFile } ?: return null
        if (logs.isEmpty()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(KrPaths.logs, "log-$stamp")
        out.mkdirs()
        logs.forEach { it.copyTo(File(out, it.name), overwrite = true) }
        File(out, "device.txt").writeText(
            buildString {
                appendLine("model=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("android=${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})")
                if (android.os.Build.VERSION.SDK_INT >= 31) appendLine("soc=${android.os.Build.SOC_MODEL}")
                appendLine("gpu=${gpuModel()}")
                appendLine("driver=${StringSetting.DRIVER_PATH.getString()}")
                appendLine("firmware=${firmwareInstalledVersion()}")
            }
        )
        return out
    }
}
