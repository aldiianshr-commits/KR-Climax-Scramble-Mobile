// SPDX-FileCopyrightText: 2026 KR Climax Scramble Mobile contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.krclimax

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.activities.EmulationActivity
import org.yuzu.yuzu_emu.databinding.KrActivityLauncherBinding
import org.yuzu.yuzu_emu.databinding.KrItemStepBinding
import org.yuzu.yuzu_emu.features.settings.model.Settings
import org.yuzu.yuzu_emu.features.settings.model.StringSetting
import org.yuzu.yuzu_emu.features.settings.ui.SettingsActivity
import org.yuzu.yuzu_emu.model.AddonViewModel
import org.yuzu.yuzu_emu.model.Game
import org.yuzu.yuzu_emu.ui.main.MainActivity
import org.yuzu.yuzu_emu.utils.InstallableActions
import org.yuzu.yuzu_emu.utils.Log

/**
 * Single-game home screen. Walks the user through the one-time setup
 * (storage, folder, keys, firmware, driver), then launches Kamen Rider
 * Climax Scramble straight into Eden's EmulationActivity.
 */
class KrLauncherActivity : AppCompatActivity() {

    private lateinit var binding: KrActivityLauncherBinding

    private enum class Level { OK, WARN, BAD, NEUTRAL }

    private data class State(
        val allFiles: Boolean,
        val tree: Uri?,
        val treeIsExpected: Boolean,
        val savesOk: Boolean,
        val keys: KrSetup.KeyState?,
        val firmwareVersion: String?,
        val firmwareSource: Boolean,
        val updateFiles: Int,
        val driverLabel: String,
        val gpu: String?,
        val adreno: Boolean,
        val scan: KrSetup.GameScan?,
        val game: Game?,
        val addons: KrSetup.AddonInfo?
    )

    private var state: State? = null
    private var refreshing = false

    // A full refresh rescans keys/games through the native core. It is only done when
    // something relevant changed, never just because we came back from a running game
    // (the core may still be shutting down at that moment).
    private var needsFullRefresh = true

    private val allFilesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            needsFullRefresh = true
            refresh()
        }

    private val treeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            runCatching { KrStorage.saveTree(this, uri) }
                .onFailure {
                    Toast.makeText(this, it.message ?: "SAF error", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
            if (!KrStorage.isExpectedTree(uri)) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.kr_folder_wrong)
                    .setPositiveButton(R.string.kr_ok, null)
                    .show()
            }
            needsFullRefresh = true
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = KrActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.krDevice.text = buildString {
            append(Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            append(' ')
            append(Build.MODEL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                append(" · ")
                append(Build.SOC_MODEL)
            }
            append(" · Android ")
            append(Build.VERSION.RELEASE)
        }

        binding.krPlay.setOnClickListener { launchGame() }
        binding.krRescan.setOnClickListener {
            needsFullRefresh = true
            refresh(forceToast = true)
        }
        binding.krChooseGame.setOnClickListener { chooseGame() }
        binding.krAdvanced.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.krBtnPreset.setOnClickListener { choosePreset() }
        binding.krBtnGraphics.setOnClickListener { openSettings(Settings.MenuTag.SECTION_RENDERER) }
        binding.krBtnGamepad.setOnClickListener {
            openSettings(Settings.MenuTag.SECTION_INPUT_PLAYER_ONE)
        }
        binding.krBtnTouch.setOnClickListener {
            startActivity(EmulationActivity.launchForOverlayEdit(this))
        }
        binding.krBtnOverlay.setOnClickListener {
            openSettings(Settings.MenuTag.SECTION_INPUT_OVERLAY)
        }
        binding.krBtnAudio.setOnClickListener { openSettings(Settings.MenuTag.SECTION_AUDIO) }
        binding.krBtnSystem.setOnClickListener { openSettings(Settings.MenuTag.SECTION_SYSTEM) }
        binding.krBtnLogs.setOnClickListener { exportLogs() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ------------------------------------------------------------------ state

    private fun refresh(forceToast: Boolean = false) {
        if (refreshing) return
        val previous = state
        val full = needsFullRefresh || previous == null
        if (!full) {
            lifecycleScope.launch {
                val driver = withContext(Dispatchers.IO) {
                    KrSetup.currentDriverLabel(getString(R.string.kr_driver_system))
                }
                val updated = previous!!.copy(
                    driverLabel = driver,
                    updateFiles = if (previous.allFiles) KrPaths.updateFiles().size else 0
                )
                state = updated
                render(updated)
            }
            return
        }
        needsFullRefresh = false
        refreshing = true
        binding.krPlay.isEnabled = false
        binding.krGameTitle.setText(R.string.kr_loading)
        lifecycleScope.launch {
            val newState = withContext(Dispatchers.IO) {
                runCatching { computeState() }
                    .onFailure { Log.error("[KR] refresh failed: ${it.stackTraceToString()}") }
                    .getOrNull()
            }
            refreshing = false
            if (newState != null) {
                state = newState
                render(newState)
                if (forceToast) {
                    val n = newState.scan?.all?.size ?: 0
                    Toast.makeText(this@KrLauncherActivity, "Ditemukan $n game", Toast.LENGTH_SHORT)
                        .show()
                }
            } else {
                needsFullRefresh = true
                binding.krGameTitle.setText(R.string.kr_game_none)
                binding.krPlayHint.text = "Terjadi kesalahan saat memeriksa. Ekspor log untuk detail."
            }
        }
    }

    private fun computeState(): State {
        val ctx = applicationContext
        val allFiles = KrStorage.hasAllFilesAccess()
        val savesOk = if (allFiles) {
            KrPaths.ensureDirs()
            runCatching { KrSetup.ensureSaveDir() }.getOrDefault(false)
        } else {
            false
        }

        runCatching { KrSetup.syncDrivers(ctx) }
            .onFailure { Log.warning("[KR] driver sync: ${it.message}") }

        // First run: tuned defaults for Snapdragon 870 / Adreno 650.
        if (KrPreset.current(ctx) == null) {
            runCatching { KrPreset.apply(ctx, KrPreset.BALANCED) }
                .onFailure { Log.warning("[KR] preset: ${it.message}") }
        }

        val keys = if (allFiles) runCatching { KrSetup.syncKeys() }.getOrNull() else null
        val tree = KrStorage.grantedTree(ctx)

        var scan: KrSetup.GameScan? = null
        var game: Game? = null
        var addons: KrSetup.AddonInfo? = null
        if (tree != null) {
            KrSetup.registerFolders(tree)
            if (keys == KrSetup.KeyState.OK) {
                KrSetup.markEdenSetupDone(ctx)
                scan = KrSetup.scanGames(tree)
                game = KrSetup.selectedGame(ctx, scan)
                game?.let { addons = KrSetup.addonInfo(it) }
            }
        }

        val adreno = KrSetup.isAdreno()
        return State(
            allFiles = allFiles,
            tree = tree,
            treeIsExpected = tree?.let { KrStorage.isExpectedTree(it) } ?: false,
            savesOk = savesOk,
            keys = keys,
            firmwareVersion = KrSetup.firmwareInstalledVersion(),
            firmwareSource = allFiles && KrSetup.firmwareSourceAvailable(),
            updateFiles = if (allFiles) KrPaths.updateFiles().size else 0,
            driverLabel = KrSetup.currentDriverLabel(getString(R.string.kr_driver_system)),
            gpu = KrSetup.gpuModel(),
            adreno = adreno,
            scan = scan,
            game = game,
            addons = addons
        )
    }

    private fun render(s: State) {
        binding.krSteps.removeAllViews()

        addStep(
            if (s.allFiles) Level.OK else Level.BAD,
            getString(R.string.kr_step_storage),
            getString(if (s.allFiles) R.string.kr_step_storage_ok else R.string.kr_step_storage_need),
            if (s.allFiles) null else getString(R.string.kr_action_grant)
        ) { allFilesLauncher.launch(KrStorage.allFilesAccessIntent(this)) }

        addStep(
            when {
                s.tree == null -> Level.BAD
                s.treeIsExpected -> Level.OK
                else -> Level.WARN
            },
            getString(R.string.kr_step_folder),
            getString(if (s.tree != null) R.string.kr_step_folder_ok else R.string.kr_step_folder_need),
            getString(if (s.tree == null) R.string.kr_action_choose else R.string.kr_action_change),
            enabled = s.allFiles
        ) { treeLauncher.launch(KrStorage.initialPickerUri()) }

        addStep(
            when (s.keys) {
                KrSetup.KeyState.OK -> Level.OK
                KrSetup.KeyState.INVALID -> Level.BAD
                else -> Level.BAD
            },
            getString(R.string.kr_step_keys),
            getString(
                when (s.keys) {
                    KrSetup.KeyState.OK -> R.string.kr_step_keys_ok
                    KrSetup.KeyState.INVALID -> R.string.kr_step_keys_invalid
                    else -> R.string.kr_step_keys_missing
                }
            ),
            if (s.keys == KrSetup.KeyState.OK) null else getString(R.string.kr_action_check),
            enabled = s.allFiles
        ) {
            needsFullRefresh = true
            refresh()
        }

        addStep(
            when {
                s.firmwareVersion != null -> Level.OK
                s.firmwareSource -> Level.WARN
                else -> Level.NEUTRAL
            },
            getString(R.string.kr_step_firmware),
            when {
                s.firmwareVersion != null -> getString(R.string.kr_step_firmware_ok, s.firmwareVersion)
                s.firmwareSource -> getString(R.string.kr_step_firmware_ready)
                else -> getString(R.string.kr_step_firmware_none)
            },
            if (s.firmwareSource) getString(R.string.kr_action_install) else null
        ) { installFirmware() }

        addStep(
            if (s.updateFiles > 0) Level.OK else Level.NEUTRAL,
            getString(R.string.kr_step_updates),
            if (s.updateFiles > 0) {
                getString(R.string.kr_step_updates_detail, s.updateFiles)
            } else {
                getString(R.string.kr_step_updates_none)
            },
            if (s.updateFiles > 0 && s.tree != null) getString(R.string.kr_action_install_nand) else null
        ) { installUpdatesToNand() }

        addStep(
            if (s.savesOk) Level.OK else if (s.allFiles) Level.BAD else Level.NEUTRAL,
            getString(R.string.kr_step_saves),
            if (s.savesOk) {
                getString(R.string.kr_step_saves_ok) + savePathSuffix(s.game)
            } else if (s.allFiles) {
                getString(R.string.kr_step_saves_fail)
            } else {
                getString(R.string.kr_step_storage_need)
            },
            null
        ) {}

        addStep(
            if (s.adreno) Level.OK else Level.NEUTRAL,
            getString(R.string.kr_step_driver),
            getString(R.string.kr_step_driver_detail, s.driverLabel, s.gpu ?: "?"),
            getString(R.string.kr_action_change)
        ) { chooseDriver() }

        renderGame(s)

        val preset = KrPreset.current(this)
        binding.krPresetLabel.text = getString(
            R.string.kr_preset_current,
            preset?.let { presetLabel(it) } ?: "-"
        )
    }

    private fun savePathSuffix(game: Game?): String {
        if (game == null) return ""
        val folder = KrSetup.gameSaveFolder(game) ?: return ""
        val rel = folder.absolutePath.removePrefix(KrPaths.saves.absolutePath)
        return if (rel.isNotEmpty()) "\n…$rel" else ""
    }

    private fun renderGame(s: State) {
        val game = s.game
        binding.krGameProgram.text = ""
        binding.krGameDetail.text = ""
        binding.krChooseGame.visibility =
            if ((s.scan?.all?.size ?: 0) > 1 || (game == null && (s.scan?.all?.isNotEmpty() == true))) {
                View.VISIBLE
            } else {
                View.GONE
            }

        if (game == null) {
            binding.krGameTitle.setText(R.string.kr_game_none)
            binding.krPlay.isEnabled = false
            binding.krPlayHint.setText(
                when {
                    !s.allFiles || s.tree == null -> R.string.kr_game_hint_no_folder
                    s.keys != KrSetup.KeyState.OK -> R.string.kr_game_hint_no_keys
                    s.scan?.all?.isNotEmpty() == true -> R.string.kr_game_hint_other
                    else -> R.string.kr_game_hint_no_file
                }
            )
            return
        }

        binding.krGameTitle.text = game.title
        val addons = s.addons
        binding.krGameDetail.text = getString(
            R.string.kr_game_detail,
            game.version.ifEmpty { "?" },
            addons?.update ?: getString(R.string.kr_game_update_none),
            addons?.dlcCount ?: 0
        )
        binding.krGameProgram.text = getString(R.string.kr_game_program, game.programIdHex)
        binding.krPlay.isEnabled = true
        binding.krPlayHint.text = if (s.firmwareVersion == null &&
            runCatching { NativeLibrary.gameRequiresFirmware(game.programId) }.getOrDefault(false)
        ) {
            getString(R.string.kr_step_firmware_none)
        } else {
            ""
        }
    }

    private fun addStep(
        level: Level,
        title: String,
        detail: String,
        action: String?,
        enabled: Boolean = true,
        onAction: () -> Unit
    ) {
        val row = KrItemStepBinding.inflate(LayoutInflater.from(this), binding.krSteps, false)
        row.stepTitle.text = title
        row.stepDetail.text = detail
        val color = when (level) {
            Level.OK -> R.color.kr_ok
            Level.WARN -> R.color.kr_warn
            Level.BAD -> R.color.kr_bad
            Level.NEUTRAL -> R.color.kr_text_dim
        }
        row.stepDot.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, color))
        if (action != null) {
            row.stepAction.visibility = View.VISIBLE
            row.stepAction.text = action
            row.stepAction.isEnabled = enabled
            row.stepAction.setOnClickListener { onAction() }
        }
        binding.krSteps.addView(row.root)
    }

    // ------------------------------------------------------------------ actions

    private fun launchGame() {
        val s = state ?: return
        val game = s.game ?: return
        if (s.keys != KrSetup.KeyState.OK) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.kr_keys_needed)
                .setPositiveButton(R.string.kr_ok, null)
                .show()
            return
        }
        val start = {
            KrSetup.rememberGame(this, game)
            KrSetup.markLaunched(this, game)
            val intent = Intent(this, EmulationActivity::class.java).apply {
                putExtra("game", game)
                putExtra("custom", true)
            }
            startActivity(intent)
        }
        val needsFirmware = runCatching { NativeLibrary.gameRequiresFirmware(game.programId) }
            .getOrDefault(false)
        if (needsFirmware && s.firmwareVersion == null) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.kr_firmware_needed)
                .setPositiveButton(R.string.kr_continue) { _, _ -> start() }
                .setNegativeButton(R.string.kr_cancel, null)
                .show()
        } else {
            start()
        }
    }

    private fun chooseGame() {
        val games = state?.scan?.all ?: return
        if (games.isEmpty()) return
        val labels = games.map { "${it.title}  (${it.version.ifEmpty { "?" }})" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.kr_choose_game)
            .setItems(labels) { _, which ->
                KrSetup.rememberGame(this, games[which])
                needsFullRefresh = true
                refresh()
            }
            .setNegativeButton(R.string.kr_cancel, null)
            .show()
    }

    private fun openSettings(tag: Settings.MenuTag) {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            putExtra("game", null as Game?)
            putExtra("menuTag", tag)
        }
        startActivity(intent)
    }

    private fun presetLabel(p: KrPreset): String = getString(
        when (p) {
            KrPreset.PERFORMANCE -> R.string.kr_preset_performance
            KrPreset.BALANCED -> R.string.kr_preset_balanced
            KrPreset.QUALITY -> R.string.kr_preset_quality
            KrPreset.COMPATIBILITY -> R.string.kr_preset_compat
        }
    )

    private fun choosePreset() {
        val presets = KrPreset.entries
        val labels = presets.map { presetLabel(it) }.toTypedArray()
        val current = presets.indexOf(KrPreset.current(this))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.kr_set_preset)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { KrPreset.apply(applicationContext, presets[which]) }
                    Toast.makeText(this@KrLauncherActivity, R.string.kr_preset_applied, Toast.LENGTH_SHORT)
                        .show()
                    state?.let { render(it) }
                }
            }
            .setNegativeButton(R.string.kr_cancel, null)
            .show()
    }

    private fun chooseDriver() {
        val s = state ?: return
        if (!s.adreno) {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.kr_driver_not_adreno)
                .setPositiveButton(R.string.kr_ok, null)
                .show()
            return
        }
        lifecycleScope.launch {
            val options = withContext(Dispatchers.IO) {
                KrSetup.driverOptions(getString(R.string.kr_driver_system))
            }
            val current = StringSetting.DRIVER_PATH.getString()
            val checked = options.indexOfFirst { it.path == current }.coerceAtLeast(0)
            MaterialAlertDialogBuilder(this@KrLauncherActivity)
                .setTitle(R.string.kr_driver_title)
                .setSingleChoiceItems(options.map { it.label }.toTypedArray(), checked) { dialog, which ->
                    dialog.dismiss()
                    val option = options[which]
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { KrSetup.selectDriver(option.path) }
                        Toast.makeText(
                            this@KrLauncherActivity,
                            getString(R.string.kr_driver_selected, option.label),
                            Toast.LENGTH_SHORT
                        ).show()
                        refresh()
                    }
                }
                .setNegativeButton(R.string.kr_cancel, null)
                .show()
        }
    }

    private fun installFirmware() {
        val progressText = TextView(this).apply {
            setPadding(64, 32, 64, 0)
            setText(R.string.kr_loading)
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.kr_firmware_installing)
            .setView(progressText)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                KrSetup.installFirmware(applicationContext) { msg ->
                    runOnUiThread { progressText.text = msg }
                }
            }
            dialog.dismiss()
            val message = result.fold(
                onSuccess = { getString(R.string.kr_firmware_done, it) },
                onFailure = { getString(R.string.kr_firmware_failed, it.message ?: "?") }
            )
            MaterialAlertDialogBuilder(this@KrLauncherActivity)
                .setMessage(message)
                .setPositiveButton(R.string.kr_ok, null)
                .show()
            needsFullRefresh = true
            refresh()
        }
    }

    private fun installUpdatesToNand() {
        val tree = state?.tree ?: return
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.kr_install_nand_info)
            .setPositiveButton(R.string.kr_continue) { _, _ ->
                lifecycleScope.launch {
                    val docs = withContext(Dispatchers.IO) {
                        val folder = KrStorage.childFolderUri(tree, KrPaths.updates.name)
                        folder?.let { KrStorage.listGameDocuments(it).map { d -> d.uri } } ?: emptyList()
                    }
                    if (docs.isEmpty()) {
                        Toast.makeText(this@KrLauncherActivity, R.string.kr_no_updates, Toast.LENGTH_LONG)
                            .show()
                        return@launch
                    }
                    InstallableActions.installContent(
                        activity = this@KrLauncherActivity,
                        fragmentManager = supportFragmentManager,
                        addonViewModel = ViewModelProvider(this@KrLauncherActivity)[AddonViewModel::class.java],
                        documents = docs
                    )
                }
            }
            .setNegativeButton(R.string.kr_cancel, null)
            .show()
    }

    private fun exportLogs() {
        lifecycleScope.launch {
            val out: File? = withContext(Dispatchers.IO) {
                if (!KrStorage.hasAllFilesAccess()) null else runCatching { KrSetup.exportLogs() }.getOrNull()
            }
            val msg = if (out != null) {
                getString(R.string.kr_logs_saved, out.absolutePath)
            } else {
                getString(R.string.kr_logs_none)
            }
            Toast.makeText(this@KrLauncherActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

}
