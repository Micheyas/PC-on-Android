package com.mikiyas.pconandroid

import android.content.Context
import android.system.Os
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

    /**
     * NOTE: bin/sh is an ABSOLUTE symlink to /bin/busybox. File.exists()
     * follows it to the phone's real root, where /bin/busybox does not
     * exist, so it always returned false even after a perfect extraction
     * (that was the v0.4 "bin/sh is missing" bug). Inside PRoot the link
     * resolves inside the rootfs and works. Check the link itself with
     * lstat, plus the real busybox binary it points to.
     */
    fun isInstalled(context: Context): Boolean {
        val rootfs = rootfsDir(context)
        if (!File(rootfs, "bin/busybox").exists()) return false
        return try {
            Os.lstat(File(rootfs, "bin/sh").absolutePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Extracts the bundled Linux rootfs into the container. Returns a status line. */
    fun install(context: Context): String {
        val rootfs = rootfsDir(context)
        rootfs.mkdirs()
        File(ContainerManager.containerDir(context), "runtime/tmp").mkdirs()
        return try {
            context.assets.open("rootfs.tgz").use { input ->
                TarExtractor.extractTarGz(input, rootfs)
            }
            when {
                isInstalled(context) ->
                    "Engine installed: Linux rootfs ready. Now tap RUN ENGINE TEST."
                !File(rootfs, "bin/busybox").exists() ->
                    "Extraction finished but bin/busybox is missing — the archive may be damaged."
                else ->
                    "Extraction finished but the bin/sh link is missing — please tap INSTALL ENGINE again."
            }
        } catch (e: Exception) {
            "Install failed: ${e.message}"
        }
    }

    data class TestResult(val success: Boolean, val log: String)

    /** Boots the rootfs through PRoot and runs a short self-check inside it. */
    fun runTest(context: Context): TestResult {
        val script = listOf(
            "echo ENGINE_TEST_OK",
            "uname -a",
            "grep PRETTY_NAME /etc/os-release",
            "ls /bin | head -3",
            "echo ENGINE_TEST_DONE"
        ).joinToString("; ")

        // v0.5's test PASSED but printed "uname/ls/grep/head: not found":
        // PRoot inherits the Android host PATH, which has no /bin or
        // /usr/bin in it, so the shell couldn't find any busybox applets.
        // echo worked only because it's a shell builtin. Always hand the
        // guest a proper Linux PATH (done in execInRootfs).
        val (exitCode, log) = execInRootfs(context, rootfsDir(context), script, 30)
        // A real pass means the busybox tools actually ran too:
        // uname prints "Linux ..." and os-release says Alpine.
        return TestResult(
            success = exitCode == 0 &&
                log.contains("ENGINE_TEST_OK") &&
                log.contains("ENGINE_TEST_DONE") &&
                log.contains("Linux") &&
                log.contains("Alpine") &&
                !log.contains("not found"),
            log = log.ifBlank { "[no output — exit code $exitCode]" }
        )
    }

    // ------------------------------------------------------------------
    // Engine layer 2: glibc rootfs (Ubuntu) + Box64 translation.
    //
    // Alpine is musl-based; Box64 and Wine builds are glibc binaries, so
    // they need this second rootfs. Assets are fetched at build time by
    // scripts/fetch-engine2.sh (never committed to git):
    //   ubuntu.tgz          Ubuntu 24.04 base, arm64
    //   engine2-overlay.tgz Box64 (/usr/local/bin/box64, its box64rc)
    //                       + an x86_64 test program (/opt/tests/)
    // ------------------------------------------------------------------

    fun glibcRootfsDir(context: Context): File =
        File(ContainerManager.containerDir(context), "runtime/rootfs-glibc")

    fun isGlibcInstalled(context: Context): Boolean =
        File(glibcRootfsDir(context), "usr/bin/bash").exists()

    fun isBox64Installed(context: Context): Boolean {
        val rootfs = glibcRootfsDir(context)
        return File(rootfs, "usr/local/bin/box64").exists() &&
            File(rootfs, "opt/tests/busybox-x86_64").exists()
    }

    fun isEngine2Installed(context: Context): Boolean =
        isGlibcInstalled(context) && isBox64Installed(context)

    /** Extracts the Ubuntu rootfs and the Box64 overlay into the container. */
    fun installEngine2(context: Context): String {
        val rootfs = glibcRootfsDir(context)
        rootfs.mkdirs()
        return try {
            context.assets.open("ubuntu.tgz").use { input ->
                TarExtractor.extractTarGz(input, rootfs)
            }
            context.assets.open("engine2-overlay.tgz").use { input ->
                TarExtractor.extractTarGz(input, rootfs)
            }
            when {
                isEngine2Installed(context) ->
                    "Engine 2 installed: Ubuntu + Box64 ready. Now tap RUN TRANSLATION TEST."
                !isGlibcInstalled(context) ->
                    "Extraction finished but Ubuntu's bash is missing — the rootfs archive may be damaged."
                else ->
                    "Ubuntu is in, but Box64 pieces are missing — please tap INSTALL ENGINE 2 again."
            }
        } catch (e: Exception) {
            "Engine 2 install failed: ${e.message}"
        }
    }

    /**
     * The translation proof: run an x86_64 Linux program (static busybox)
     * through Box64 inside the Ubuntu rootfs. If Box64 works, the guest
     * program reports its own architecture as x86_64 — on an ARM phone.
     */
    fun runTranslationTest(context: Context): TestResult {
        val script = listOf(
            "echo TRANS_TEST_OK",
            "echo -n 'host shell arch: '; uname -m",
            "echo -n 'guest program arch: '",
            "box64 /opt/tests/busybox-x86_64 uname -m",
            "box64 /opt/tests/busybox-x86_64 echo X86_PROGRAM_RAN",
            "echo TRANS_TEST_DONE"
        ).joinToString("; ")

        val (exitCode, log) = execInRootfs(context, glibcRootfsDir(context), script, 60)
        return TestResult(
            success = exitCode == 0 &&
                log.contains("TRANS_TEST_OK") &&
                log.contains("TRANS_TEST_DONE") &&
                log.contains("x86_64") &&
                log.contains("X86_PROGRAM_RAN") &&
                !log.contains("not found"),
            log = log.ifBlank { "[no output — exit code $exitCode]" }
        )
    }

    /** Runs /bin/sh -c [script] inside [rootfs] through PRoot. Returns (exitCode, combinedOutput). */
    private fun execInRootfs(
        context: Context,
        rootfs: File,
        script: String,
        timeoutSeconds: Long
    ): Pair<Int?, String> {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val tmpDir = File(ContainerManager.containerDir(context), "runtime/tmp").apply { mkdirs() }

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
                    environment()["PATH"] =
                        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                }
                .start()

            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { output.append(it).append('\n') }
                } catch (e: Exception) { /* stream closed on timeout */ }
            }
            reader.start()
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                reader.join(2000)
                return null to (output.toString() + "\n[test timed out after ${timeoutSeconds}s]")
            }
            reader.join(3000)
            process.exitValue() to output.toString()
        } catch (e: Exception) {
            null to "Could not start PRoot: ${e.message}"
        }
    }
}
