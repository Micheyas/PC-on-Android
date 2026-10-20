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

    // ------------------------------------------------------------------
    // Engine layer 3: Wine (downloadable pack).
    //
    // The pack (~199 MB) is built by scripts/make-wine-pack.sh and
    // published on this repo's "wine-pack-11.20" release. The app
    // downloads it once and extracts it into the Ubuntu rootfs:
    //   opt/wine/          Wine 11.20 amd64-wow64 (Kron4ek build)
    //   usr/lib/x86_64-linux-gnu/ + lib64/   x86_64 glibc & friends
    //   usr/lib/box64-x86_64-linux-gnu/      Box64's bundled x86 libs
    // Windows programs then run as: box64 /opt/wine/bin/wine <exe>
    // ------------------------------------------------------------------

    const val WINE_PACK_URL =
        "https://github.com/Micheyas/PC-on-Android/releases/download/wine-pack-11.20/wine-pack.tgz"

    fun isWineInstalled(context: Context): Boolean =
        File(glibcRootfsDir(context), "opt/wine/bin/wine").exists()

    private fun winePackFile(context: Context): File =
        File(ContainerManager.containerDir(context), "downloads/wine-pack.tgz")

    /**
     * Downloads the Wine pack (if needed) and extracts it into the
     * Ubuntu rootfs. [onProgress] receives human-readable status lines.
     * The downloaded archive is deleted after a successful install to
     * avoid keeping two copies of ~199 MB on the phone.
     */
    fun installWine(context: Context, onProgress: (String) -> Unit): String {
        val pack = winePackFile(context)
        return try {
            if (!pack.exists() || pack.length() == 0L) {
                pack.parentFile?.mkdirs()
                onProgress("Downloading Wine pack (about 199 MB)…")
                downloadFile(WINE_PACK_URL, pack) { mb ->
                    onProgress("Downloading Wine pack… $mb MB")
                }
            }
            onProgress("Download complete. Installing Wine into the engine…")
            glibcRootfsDir(context).mkdirs()
            pack.inputStream().use { input ->
                TarExtractor.extractTarGz(input, glibcRootfsDir(context))
            }
            if (isWineInstalled(context)) {
                pack.delete()
                "Wine installed: Wine 11.20 ready. Now tap RUN WINE TEST."
            } else {
                "Wine pack extracted but opt/wine/bin/wine is missing — the pack may be damaged."
            }
        } catch (e: Exception) {
            "Wine install failed: ${e.message}"
        }
    }


    /** Environment every Wine invocation needs inside the guest. */
    private fun wineEnv(): Map<String, String> = mapOf(
        "WINEPREFIX" to "/root/.wine",
        "WINEDEBUG" to "-all",
        "BOX64_LD_LIBRARY_PATH" to
            "/usr/lib/x86_64-linux-gnu:/usr/lib/box64-x86_64-linux-gnu:/opt/wine/lib",
        "BOX64_PATH" to "/opt/wine/bin:/usr/local/bin:/usr/bin:/bin"
    )

    /** Copies the bundled test program (plink.exe) into the Ubuntu rootfs. */
    private fun ensureTestExe(context: Context): File {
        val dest = File(glibcRootfsDir(context), "opt/tests/plink.exe")
        if (!dest.exists()) {
            dest.parentFile?.mkdirs()
            context.assets.open("plink.exe").use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return dest
    }

    /**
     * The first real Windows program: plink.exe (PuTTY's console tool,
     * a 64-bit Windows binary) run through Box64 + Wine, asked for its
     * version. The first Wine program run also creates the Wine prefix
     * (/root/.wine), so this can take a couple of minutes.
     */
    fun runFirstExeTest(context: Context): TestResult {
        ensureTestExe(context)
        val script = listOf(
            "echo EXE_TEST_OK",
            "box64 /opt/wine/bin/wine /opt/tests/plink.exe -V",
            "echo EXE_TEST_DONE"
        ).joinToString("; ")

        val wineEnv = wineEnv()
        val (exitCode, log) = execInRootfs(context, glibcRootfsDir(context), script, 240, wineEnv)
        return TestResult(
            success = exitCode == 0 &&
                log.contains("EXE_TEST_OK") &&
                log.contains("EXE_TEST_DONE") &&
                log.contains("plink"),
            log = log.ifBlank { "[no output — exit code $exitCode]" }
        )
    }

    /** First Wine proof: ask Wine (through Box64) for its version. */
    fun runWineTest(context: Context): TestResult {
        val script = listOf(
            "echo WINE_TEST_OK",
            "box64 /opt/wine/bin/wine --version",
            "echo WINE_TEST_DONE"
        ).joinToString("; ")

        val wineEnv = wineEnv()
        val (exitCode, log) = execInRootfs(context, glibcRootfsDir(context), script, 120, wineEnv)
        return TestResult(
            success = exitCode == 0 &&
                log.contains("WINE_TEST_OK") &&
                log.contains("WINE_TEST_DONE") &&
                log.contains("wine-11"),
            log = log.ifBlank { "[no output — exit code $exitCode]" }
        )
    }

    /** Streams [url] to [dest], reporting whole megabytes downloaded. */
    private fun downloadFile(url: String, dest: File, onMb: (Long) -> Unit) {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Download server answered ${conn.responseCode}")
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        conn.inputStream.use { input ->
            java.io.FileOutputStream(tmp).use { out ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                var lastReported = -1L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    val mb = total / (1024 * 1024)
                    if (mb != lastReported) {
                        lastReported = mb
                        onMb(mb)
                    }
                }
            }
        }
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
    }

    /** Runs /bin/sh -c [script] inside [rootfs] through PRoot. Returns (exitCode, combinedOutput). */
    private fun execInRootfs(
        context: Context,
        rootfs: File,
        script: String,
        timeoutSeconds: Long,
        extraEnv: Map<String, String> = emptyMap()
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
                    extraEnv.forEach { (k, v) -> environment()[k] = v }
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
