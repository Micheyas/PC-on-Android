package com.mikiyas.pconandroid

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES20
import android.os.Build
import java.io.File

/**
 * The seed of the auto-settings brain.
 *
 * Later this decides, per phone: which graphics driver to use
 * (Turnip for Adreno, VirGL/Vortek for Mali), which resolution
 * preset, and which Box64 preset — so the user never has to.
 */
object DeviceProfiler {

    data class Profile(
        val deviceName: String,
        val chip: String,
        val gpu: String,
        val gpuFamily: String,
        val totalRamMb: Long,
        val recommendedDriver: String
    )

    fun detect(context: Context): Profile {
        val gpu = try {
            GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        val gpuFamily = when {
            gpu.contains("Adreno", ignoreCase = true) -> "Adreno (Snapdragon)"
            gpu.contains("Mali", ignoreCase = true) -> "Mali (MediaTek / Exynos)"
            else -> "Other"
        }

        val driver = when (gpuFamily) {
            "Adreno (Snapdragon)" -> "Turnip"
            "Mali (MediaTek / Exynos)" -> "VirGL / Vortek"
            else -> "VirGL"
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)

        val chip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL ?: Build.HARDWARE
        } else {
            Build.HARDWARE
        }

        return Profile(
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
            chip = chip,
            gpu = gpu,
            gpuFamily = gpuFamily,
            totalRamMb = mem.totalMem / (1024 * 1024),
            recommendedDriver = driver
        )
    }

    /** True if a file looks like a Windows PE executable (starts with "MZ"). */
    fun looksLikeExe(file: File): Boolean = try {
        file.inputStream().use { input ->
            val b = ByteArray(2)
            input.read(b) == 2 && b[0] == 'M'.code.toByte() && b[1] == 'Z'.code.toByte()
        }
    } catch (e: Exception) {
        false
    }
}
