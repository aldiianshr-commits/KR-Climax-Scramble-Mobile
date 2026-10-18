// SPDX-FileCopyrightText: 2026 KR Climax Scramble Mobile contributors
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.krclimax

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.utils.NativeConfig

/**
 * Global emulator presets tuned for a Snapdragon 870 / Adreno 650 tablet
 * (Xiaomi Pad 6). Values are the ordinals of Eden's settings enums
 * (src/common/settings_enums.h at the pinned Eden commit):
 *
 *   resolution_setup: 0=1/4x 1=1/2x 2=3/4x 3=1x 4=5/4x 5=3/2x 6=2x
 *   cpu_backend:      0=Dynarmic (JIT) 1=NCE (native execution, fastest on ARM64)
 *   cpu_accuracy:     0=Auto 1=Accurate 2=Unsafe
 *   gpu_accuracy:     0=Low 1=Medium 2=High
 *   accelerate_astc:  0=CPU 1=GPU 2=CPU async
 *   use_vsync:        0=Immediate 1=Mailbox 2=FIFO 3=FIFO relaxed
 *   scaling_filter:   1=Bilinear 6=FSR
 *   anti_aliasing:    0=None 1=FXAA
 *   nvdec_emulation:  2=GPU (cutscene video decode)
 */
enum class KrPreset(val prefValue: String) {
    PERFORMANCE("performance"),
    BALANCED("balanced"),
    QUALITY("quality"),
    COMPATIBILITY("compatibility");

    companion object {
        private const val PREF = "kr_preset"

        fun current(context: Context): KrPreset? {
            val v = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF, null)
            return entries.firstOrNull { it.prefValue == v }
        }

        fun apply(context: Context, preset: KrPreset) {
            if (NativeConfig.isPerGameConfigLoaded()) {
                NativeConfig.unloadPerGameConfig()
            }

            // Shared by every preset: Vulkan, async shaders + disk cache so stutter only
            // happens the first time an effect is seen, GPU ASTC + video decode, 16:9.
            IntSetting.RENDERER_BACKEND.setInt(1)
            IntSetting.RENDERER_ASTC_DECODE_METHOD.setInt(1)
            IntSetting.RENDERER_NVDEC_EMULATION.setInt(2)
            IntSetting.RENDERER_ASPECT_RATIO.setInt(0)
            BooleanSetting.RENDERER_USE_DISK_SHADER_CACHE.setBoolean(true)
            BooleanSetting.RENDERER_ASYNCHRONOUS_SHADERS.setBoolean(true)
            BooleanSetting.RENDERER_ASYNCHRONOUS_GPU_EMULATION.setBoolean(true)
            BooleanSetting.RENDERER_USE_SPEED_LIMIT.setBoolean(true)
            IntSetting.MEMORY_LAYOUT.setInt(0)

            when (preset) {
                PERFORMANCE -> {
                    IntSetting.CPU_BACKEND.setInt(1)
                    IntSetting.CPU_ACCURACY.setInt(0)
                    IntSetting.RENDERER_RESOLUTION.setInt(2)
                    IntSetting.RENDERER_ACCURACY.setInt(0)
                    IntSetting.RENDERER_SCALING_FILTER.setInt(6)
                    IntSetting.RENDERER_ANTI_ALIASING.setInt(0)
                    IntSetting.RENDERER_VSYNC.setInt(1)
                    BooleanSetting.USE_DOCKED_MODE.setBoolean(false)
                    BooleanSetting.RENDERER_REACTIVE_FLUSHING.setBoolean(false)
                }
                BALANCED -> {
                    IntSetting.CPU_BACKEND.setInt(1)
                    IntSetting.CPU_ACCURACY.setInt(0)
                    IntSetting.RENDERER_RESOLUTION.setInt(3)
                    IntSetting.RENDERER_ACCURACY.setInt(0)
                    IntSetting.RENDERER_SCALING_FILTER.setInt(1)
                    IntSetting.RENDERER_ANTI_ALIASING.setInt(0)
                    IntSetting.RENDERER_VSYNC.setInt(2)
                    BooleanSetting.USE_DOCKED_MODE.setBoolean(false)
                    BooleanSetting.RENDERER_REACTIVE_FLUSHING.setBoolean(false)
                }
                QUALITY -> {
                    IntSetting.CPU_BACKEND.setInt(1)
                    IntSetting.CPU_ACCURACY.setInt(0)
                    IntSetting.RENDERER_RESOLUTION.setInt(5)
                    IntSetting.RENDERER_ACCURACY.setInt(1)
                    IntSetting.RENDERER_SCALING_FILTER.setInt(6)
                    IntSetting.RENDERER_ANTI_ALIASING.setInt(1)
                    IntSetting.RENDERER_VSYNC.setInt(2)
                    BooleanSetting.USE_DOCKED_MODE.setBoolean(true)
                    BooleanSetting.RENDERER_REACTIVE_FLUSHING.setBoolean(true)
                }
                COMPATIBILITY -> {
                    // JIT CPU + medium GPU accuracy: slower, but rules out NCE/accuracy
                    // shortcuts when a scene crashes or renders wrong.
                    IntSetting.CPU_BACKEND.setInt(0)
                    IntSetting.CPU_ACCURACY.setInt(1)
                    IntSetting.RENDERER_RESOLUTION.setInt(3)
                    IntSetting.RENDERER_ACCURACY.setInt(1)
                    IntSetting.RENDERER_SCALING_FILTER.setInt(1)
                    IntSetting.RENDERER_ANTI_ALIASING.setInt(0)
                    IntSetting.RENDERER_VSYNC.setInt(2)
                    BooleanSetting.USE_DOCKED_MODE.setBoolean(false)
                    BooleanSetting.RENDERER_REACTIVE_FLUSHING.setBoolean(true)
                }
            }

            NativeConfig.saveGlobalConfig()
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(PREF, preset.prefValue)
            }
        }
    }
}
