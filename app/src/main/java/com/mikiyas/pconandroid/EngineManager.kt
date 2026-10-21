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
    // published on this repo's "wine-pack-10.0" release. The app
    // downloads it once and extracts it into the Ubuntu rootfs:
    //   opt/wine/          Wine 10.0 amd64-wow64 (Kron4ek build)
    //   usr/lib/x86_64-linux-gnu/ + lib64/   x86_64 glibc & friends
    //   usr/lib/box64-x86_64-linux-gnu/      Box64's bundled x86 libs
    // Windows programs then run as: box64 /opt/wine/bin/wine <exe>
    // ------------------------------------------------------------------

    const val WINE_PACK_URL =
        "https://github.com/Micheyas/PC-on-Android/releases/download/wine-pack-10.0/wine-pack.tgz"

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
                onProgress("Downloading Wine pack (about 148 MB)…")
                downloadFile(WINE_PACK_URL, pack) { mb ->
                    onProgress("Downloading Wine pack… $mb MB")
                }
            }
            onProgress("Download complete. Installing Wine into the engine…")
            val rootfsDir = glibcRootfsDir(context)
            rootfsDir.mkdirs()
            // A previous pack (or an aborted run) must never mix with
            // this one: wipe the old Wine tree, its libraries and the
            // old Wine prefix before extracting.
            File(rootfsDir, "opt/wine").deleteRecursively()
            File(rootfsDir, "usr/lib/box64-x86_64-linux-gnu").deleteRecursively()
            File(rootfsDir, "usr/lib/x86_64-linux-gnu").deleteRecursively()
            File(rootfsDir, "lib64").deleteRecursively()
            File(rootfsDir, "root/.wine").deleteRecursively()
            pack.inputStream().use { input ->
                TarExtractor.extractTarGz(input, glibcRootfsDir(context))
            }
            if (isWineInstalled(context)) {
                pack.delete()
                "Wine installed: ready. Now tap RUN WINE TEST."
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
     * version.
     *
     * Staged on purpose: Wine 11.20 crashed this step with an ntdll
     * assertion, and Wine 10.0 ran it silently empty (all diagnostics
     * were muted), so this version boots the prefix first, keeps
     * Wine's error channel ON, and prints each step's exit code.
     */
    fun runFirstExeTest(context: Context): TestResult {
        ensureTestExe(context)
        val script = listOf(
            "echo EXE_TEST_OK",
            "box64 /opt/wine/bin/wine wineboot -i > /root/boot.log 2>&1",
            "echo BOOT_EXIT=\$?",
            "tail -n 10 /root/boot.log",
            "box64 /opt/wine/bin/wine /opt/tests/plink.exe -V > /root/plink.log 2>&1",
            "echo PLINK_EXIT=\$?",
            "cat /root/plink.log",
            "echo EXE_TEST_DONE"
        ).joinToString("; ")

        // Same environment as the other Wine runs, but with the error
        // channel audible so a failure explains itself.
        val wineEnv = wineEnv() + ("WINEDEBUG" to "err")
        val (_, log) = execInRootfs(context, glibcRootfsDir(context), script, 360, wineEnv)
        val plinkExit = Regex("PLINK_EXIT=(\\d+)").find(log)?.groupValues?.get(1)
        return TestResult(
            success = log.contains("EXE_TEST_DONE") &&
                plinkExit == "0" &&
                log.contains("plink:"),
            log = log.ifBlank { "[no output]" }
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
                log.contains("wine-"),
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

    // ------------------------------------------------------------------
    // Run the user's own Windows programs (v0.19).
    //
    // A picked .exe is copied into the guest at /root/userapps/ and run
    // through the same Box64 + Wine path as the built-in tests. The PE
    // Subsystem field (read by ExeInspector) decides the route:
    // windowed programs go through the graphics session below, console
    // programs run in place and their output lands in the log.
    // ------------------------------------------------------------------

    /** Makes a file name safe as a guest path component (no spaces/quotes). */
    private fun sanitizeExeName(name: String): String {
        val cleaned = name.map { c ->
            if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '_') c else '_'
        }.joinToString("")
        return cleaned.ifBlank { "program.exe" }
    }

    /**
     * Copies a picked Windows program from app storage into the Ubuntu
     * guest and returns its guest path (/root/userapps/<name>).
     */
    fun stageUserExe(context: Context, source: File, displayName: String): String {
        val dir = File(glibcRootfsDir(context), "root/userapps").apply { mkdirs() }
        val dest = File(dir, sanitizeExeName(displayName))
        source.copyTo(dest, overwrite = true)
        return "/root/userapps/${dest.name}"
    }

    /**
     * Runs a staged console program: boots the Wine prefix first (same
     * staging that made the plink test diagnosable), then the program,
     * keeping Wine's error channel audible and printing its exit code.
     */
    fun runUserExeConsole(context: Context, guestPath: String): TestResult {
        val script = listOf(
            "echo USER_EXE_START",
            "box64 /opt/wine/bin/wine wineboot -i > /root/user-boot.log 2>&1",
            "echo BOOT_EXIT=\$?",
            "box64 /opt/wine/bin/wine $guestPath > /root/user-run.log 2>&1",
            "echo USER_EXIT=\$?",
            "cat /root/user-run.log",
            "echo USER_EXE_DONE"
        ).joinToString("; ")

        val wineEnv = wineEnv() + ("WINEDEBUG" to "err")
        val (_, log) = execInRootfs(context, glibcRootfsDir(context), script, 360, wineEnv)
        val userExit = Regex("USER_EXIT=(\\d+)").find(log)?.groupValues?.get(1)
        return TestResult(
            success = log.contains("USER_EXE_DONE") && userExit == "0",
            log = log.ifBlank { "[no output]" }
        )
    }

    // ------------------------------------------------------------------
    // Graphics phase: an X server (Xvfb) + VNC inside the Ubuntu guest,
    // so windowed Windows programs have a screen to draw on, and the
    // app's built-in viewer (ScreenActivity) can show it.
    // ------------------------------------------------------------------

    fun isGraphicsInstalled(context: Context): Boolean {
        if (!isGlibcInstalled(context)) return false
        val rootfs = glibcRootfsDir(context)
        fun exists(path: String) = try {
            android.system.Os.lstat(File(rootfs, path).absolutePath); true
        } catch (e: Exception) { false }
        return exists("usr/bin/Xvfb") && exists("usr/bin/x11vnc")
    }

    /**
     * Installs the X server + VNC server into the Ubuntu guest with apt
     * (the guest shares the phone's network). One-time, a few minutes.
     */
    const val GRAPHICS_PACK_URL =
        "https://github.com/Micheyas/PC-on-Android/releases/download/graphics-pack-2/graphics-pack.tgz"

    private fun graphicsPackFile(context: Context): File =
        File(ContainerManager.containerDir(context), "downloads/graphics-pack-2.tgz")

    /**
     * Installs the graphics layer from its downloadable pack: Xvfb +
     * x11vnc and their full arm64 dependency closure from Ubuntu
     * noble, extracted raw into the rootfs. (v0.15/v0.16 tried apt
     * inside the guest; the guest has no working DNS route on the
     * user's carrier — "Temporary failure resolving
     * ports.ubuntu.com" — so, like Wine, graphics ships as a pack
     * the app itself downloads.) Archive deleted after install.
     */
    fun installGraphics(context: Context, onProgress: (String) -> Unit): String {
        val pack = graphicsPackFile(context)
        return try {
            if (!pack.exists() || pack.length() == 0L) {
                pack.parentFile?.mkdirs()
                onProgress("Downloading graphics pack (about 78 MB)…")
                downloadFile(GRAPHICS_PACK_URL, pack) { mb ->
                    onProgress("Downloading graphics pack… $mb MB")
                }
            }
            onProgress("Download complete. Installing graphics into the engine…")
            glibcRootfsDir(context).mkdirs()
            pack.inputStream().use { input ->
                TarExtractor.extractTarGz(input, glibcRootfsDir(context))
            }
            if (isGraphicsInstalled(context)) {
                // Build the X font indexes once, here: raw extraction
                // runs no postinst. This used to be the session script's
                // first line — one-time setup doesn't belong in the
                // session path, and it was the prime suspect when
                // sessions died before Xvfb ever started (v0.24).
                val fonts = "for d in /usr/share/fonts/X11/*/; do mkfontdir \"\$d\" > /dev/null 2>&1; done; touch /root/.fonts-done; true"
                execInRootfs(context, glibcRootfsDir(context), fonts, 90)
                pack.delete()
                "Graphics installed: X server + VNC are ready. Now tap START WINDOWS SCREEN (PUTTY)."
            } else {
                "Graphics pack extracted but usr/bin/Xvfb is missing — the pack may be damaged."
            }
        } catch (e: Exception) {
            "Graphics install failed: ${e.message}"
        }
    }

    private var graphicsProcess: Process? = null
    private val graphicsLog = StringBuilder()

    fun isGraphicsRunning(): Boolean = try {
        graphicsProcess?.isAlive == true
    } catch (e: Exception) { false }

    fun graphicsSessionLog(): String =
        synchronized(graphicsLog) { graphicsLog.toString() }

    /** Copies a bundled test program (asset) into the guest, if missing. */
    private fun ensureGuestProgram(context: Context, assetName: String): File {
        val dest = File(glibcRootfsDir(context), "opt/tests/$assetName")
        if (!dest.exists()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            }
        }
        return dest
    }

    /**
     * Starts the graphics session and keeps it running: Xvfb on :0,
     * x11vnc on localhost:5900, and PuTTY (a windowed Windows program)
     * as the first graphical test. The session lives until
     * [stopGraphics]; the viewer connects to it while it runs.
     */
    fun startGraphics(context: Context, guestExePath: String = "/opt/tests/putty.exe"): String {
        if (isGraphicsRunning()) return "Graphics session is already running."
        if (guestExePath == "/opt/tests/putty.exe") ensureGuestProgram(context, "putty.exe")
        val programBase = guestExePath.substringAfterLast('/')
        val programPattern =
            if (programBase.isEmpty()) "[p]utty.exe"
            else "[" + programBase.first() + "]" + programBase.drop(1)
        // Kill leftovers from a previous session in a SEPARATE one-shot
        // process. Doing this inside the session script was fatal: the
        // bracket trick only stops pkill matching ITSELF — the session
        // shell's command line embeds the whole script, including the
        // unbracketed words Xvfb / x11vnc / putty.exe from the launch
        // lines below, so pkill -f matched and killed its own parent
        // shell before Xvfb ever started (on-device 2026-10-21: session
        // stopped instantly, x.log never even created). This one-shot's
        // command line contains ONLY the bracketed patterns, so it
        // cannot match itself or its own shell.
        val cleanup = listOf(
            "pkill -f '[X]vfb' 2>/dev/null",
            "pkill -f '[x]11vnc' 2>/dev/null",
            "pkill -f '$programPattern' 2>/dev/null",
            "pkill -f '[w]ineserver' 2>/dev/null",
            "sleep 1",
            "rm -f /tmp/.X0-lock /tmp/.X11-unix/X0",
            "true"
        ).joinToString("; ")
        execInRootfs(context, glibcRootfsDir(context), cleanup, 20)
        val script = listOf(
            // Trace everything the session shell does into a file the
            // DIAGNOSE button can read back. Until v0.25 a session that
            // died early left no evidence anywhere: its stdout went to
            // an in-memory buffer the UI never showed, and the servers'
            // own logs were only created once Xvfb started.
            "exec > /root/session.log 2>&1",
            "set -x",
            "echo SESSION_START",
            "Xvfb :0 -screen 0 1024x600x24 > /root/x.log 2>&1 &",
            "sleep 2",
            "x11vnc -display :0 -rfbport 5900 -localhost -forever -shared -nopw > /root/vnc.log 2>&1 &",
            "sleep 1",
            "box64 /opt/wine/bin/wine $guestExePath > /root/gui.log 2>&1 &",
            "echo GRAPHICS_SESSION_UP",
            "wait"
        ).joinToString("; ")
        val env = wineEnv() + mapOf("WINEDEBUG" to "err", "DISPLAY" to ":0")
        return try {
            val process = startInRootfs(context, glibcRootfsDir(context), script, env)
            graphicsProcess = process
            synchronized(graphicsLog) { graphicsLog.setLength(0) }
            Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(graphicsLog) { graphicsLog.append(line).append('\n') }
                    }
                } catch (e: Exception) { /* session ended */ }
            }.start()
            "Graphics starting: X server + $programBase. Give it ~20 seconds, then tap OPEN SCREEN."
        } catch (e: Exception) {
            "Could not start graphics: ${e.message}"
        }
    }

    fun stopGraphics() {
        try {
            graphicsProcess?.destroy()
        } catch (e: Exception) { /* already gone */ }
        graphicsProcess = null
    }

    /**
     * One-shot guest probe for when OPEN SCREEN gets "connection refused":
     * shows whether Xvfb / x11vnc / wine are actually alive, whether
     * anything listens on port 5900 (0x170C in /proc/net/tcp), and the
     * tails of the three server logs the session script writes.
     */
    fun graphicsDiagnostics(context: Context): String {
        val script = listOf(
            "echo '== processes =='",
            "ps -ef 2>/dev/null | grep -E '[X]vfb|[x]11vnc|[w]ine|[b]ox64' || echo '(ps found none)'",
            "echo '== X socket =='",
            "ls -la /tmp/.X11-unix/ 2>&1",
            "echo '== port 5900 =='",
            "grep -i ':170C' /proc/net/tcp /proc/net/tcp6 2>/dev/null || echo 'NOTHING listening on 5900'",
            "echo '== binaries =='",
            "ls -l /usr/bin/Xvfb /usr/bin/x11vnc 2>&1",
            "echo '== session.log (session trace) =='",
            "tail -n 60 /root/session.log 2>&1",
            "echo '== x.log (Xvfb) =='",
            "tail -n 30 /root/x.log 2>&1",
            "echo '== vnc.log (x11vnc) =='",
            "tail -n 40 /root/vnc.log 2>&1",
            "echo '== gui.log (wine program) =='",
            "tail -n 20 /root/gui.log 2>&1"
        ).joinToString("; ")
        val (_, output) = execInRootfs(context, glibcRootfsDir(context), script, 30)
        val appSide = graphicsSessionLog().trim()
        return output.trim() +
            "\n== app-side session output ==\n" +
            appSide.ifBlank { "(empty)" }
    }

    /** Starts /bin/sh -c [script] inside [rootfs] through PRoot and returns the live process. */
    private fun startInRootfs(
        context: Context,
        rootfs: File,
        script: String,
        extraEnv: Map<String, String> = emptyMap()
    ): Process {
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

        return ProcessBuilder(command)
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
    }

    /** Runs /bin/sh -c [script] inside [rootfs] through PRoot. Returns (exitCode, combinedOutput). */
    private fun execInRootfs(
        context: Context,
        rootfs: File,
        script: String,
        timeoutSeconds: Long,
        extraEnv: Map<String, String> = emptyMap()
    ): Pair<Int?, String> {
        return try {
            val process = startInRootfs(context, rootfs, script, extraEnv)

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
