package com.mikiyas.pconandroid

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
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
        val chip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL ?: Build.HARDWARE
        } else {
            Build.HARDWARE
        }

        // The GPU renderer string needs a live GL context — query it through a
        // tiny offscreen EGL context. If that fails, infer the family from the chip.
        var gpu = queryGpuRenderer() ?: "unknown"
        var gpuFamily = familyOf(gpu)
        if (gpuFamily == "Other") {
            gpuFamily = familyFromChip(chip)
            if (gpu == "unknown" && gpuFamily != "Other") {
                gpu = "inferred from chip"
            }
        }

        val driver = when (gpuFamily) {
            "Adreno (Snapdragon)" -> "Turnip"
            "Mali (MediaTek / Exynos)" -> "VirGL / Vortek"
            else -> "VirGL"
        }

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)

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

    private fun familyOf(gpu: String): String = when {
        gpu.contains("Adreno", ignoreCase = true) -> "Adreno (Snapdragon)"
        gpu.contains("Mali", ignoreCase = true) -> "Mali (MediaTek / Exynos)"
        else -> "Other"
    }

    /** Chip-name based GPU family guess, used when the GL query fails. */
    private fun familyFromChip(chip: String): String {
        val c = chip.uppercase()
        return when {
            // MediaTek: MTxxxx model numbers, Helio, Dimensity — all Mali in modern chips
            c.startsWith("MT") || c.contains("MEDIATEK") || c.contains("HELIO") ||
                c.contains("DIMENSITY") -> "Mali (MediaTek / Exynos)"
            // Samsung Exynos ships Mali (or Xclipse on flagships)
            c.contains("EXYNOS") -> "Mali (MediaTek / Exynos)"
            // Qualcomm: SMxxxx / SDMxxx model numbers, Snapdragon
            c.startsWith("SM") || c.startsWith("SDM") || c.contains("SNAPDRAGON") ||
                c.contains("QCOM") || c.contains("MSM") -> "Adreno (Snapdragon)"
            else -> "Other"
        }
    }

    /**
     * Reads GL_RENDERER through a temporary offscreen (pbuffer) EGL context.
     * glGetString returns null without a current context, which is why the
     * first version of this app showed "GPU: unknown" on real hardware.
     */
    private fun queryGpuRenderer(): String? {
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
                numConfigs[0] == 0
            ) return null

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, configs[0], surfaceAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (e: Exception) {
            null
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT
                    )
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (e: Exception) {
                // best-effort cleanup only
            }
        }
    }
}
