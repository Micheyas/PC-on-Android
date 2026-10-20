package com.mikiyas.pconandroid

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The engine layer, step 1: PRoot + a real Linux rootfs inside the container.
 *
 * Binaries (proot, its loader, libtalloc, libandroid-shmem) ship as jniLibs
 * so Android extracts them to nativeLibraryDir — the only place an app may
 * execute files from on modern Android. The rootfs ships as an asset and is
 * extracted on "Install engine".
 *
 * Once this layer proves itself on a real phone, the Wine + Box64 rootfs
 * drops into the same machinery.
 */
object EngineManager {

    fun rootfsDir(context: Context): File =
        File(ContainerManager.containerDir(context), "runtime/rootfs")

    fun isInstalled(context: Context): Boolean =
        File(rootfsDir(context), "bin/sh").exists()

    /** Extracts the bundled Linux rootfs into the container. Returns a status line. */
    fun install(context: Context): String {
        val rootfs = rootfsDir(context)
        rootfs.mkdirs()
        File(ContainerManager.containerDir(context), "runtime/tmp").mkdirs()
        return try {
            context.assets.open("rootfs.tgz").use { input ->
                TarExtractor.extractTarGz(input, rootfs)
            }
            if (isInstalled(context)) {
                "Engine installed: Linux rootfs ready at ${rootfs.path}"
            } else {
                "Extraction finished but /bin/sh is missing — the archive may be damaged."
            }
        } catch (e: Exception) {
            "Install failed: ${e.message}"
        }
    }

    data class TestResult(val success: Boolean, val log: String)

    /** Boots the rootfs through PRoot and runs a short self-check inside it. */
    fun runTest(context: Context): TestResult {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val rootfs = rootfsDir(context)
        val tmpDir = File(ContainerManager.containerDir(context), "runtime/tmp").apply { mkdirs() }

        val script = listOf(
            "echo ENGINE_TEST_OK",
            "uname -a",
            "grep PRETTY_NAME /etc/os-release",
            "ls /bin | head -3",
            "echo ENGINE_TEST_DONE"
        ).joinToString("; ")

        val command = listOf(
            "$nativeDir/libproot.so",
            "-r", rootfs.absolutePath,
            "-0",
            "-w", "/root",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "/bin/sh", "-c", script
        )

        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .apply {
                    environment()["PROOT_TMP_DIR"] = tmpDir.absolutePath
                    environment()["PROOT_LOADER"] = "$nativeDir/libproot-loader.so"
                    environment()["LD_LIBRARY_PATH"] = nativeDir
                    environment()["HOME"] = "/root"
                    environment()["TERM"] = "xterm"
                }
                .start()

            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') }
                } catch (e: Exception) { /* stream closed on timeout */ }
            }
            reader.start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(2000)
                return TestResult(false, output.toString() + "\n[test timed out after 30s]")
            }
            reader.join(3000)
            val exitCode = process.exitValue()
            val log = output.toString()
            TestResult(
                success = exitCode == 0 && log.contains("ENGINE_TEST_OK") && log.contains("ENGINE_TEST_DONE"),
                log = log.ifBlank { "[no output — exit code $exitCode]" }
            )
        } catch (e: Exception) {
            TestResult(false, "Could not start PRoot: ${e.message}")
        }
    }
}
