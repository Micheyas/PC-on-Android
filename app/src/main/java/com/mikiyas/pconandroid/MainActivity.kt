package com.mikiyas.pconandroid

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var pickedFileText: TextView
    private lateinit var runMyExeButton: Button

    // The last program the user picked: a full copy inside app storage
    // (content URIs can't be read by the engine), plus its PE reading.
    private var pickedExeFile: java.io.File? = null
    private var pickedExeInfo: ExeInspector.Result? = null
    private var pickedExeName: String = ""

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) showPickedFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pickedFileText = findViewById(R.id.pickedFile)
        runMyExeButton = findViewById(R.id.runMyExeButton)

        val p = DeviceProfiler.detect(this)
        findViewById<TextView>(R.id.deviceInfo).text = listOf(
            "Device: ${p.deviceName}",
            "Chip: ${p.chip}",
            "GPU: ${p.gpu}",
            "GPU family: ${p.gpuFamily}",
            "RAM: ${p.totalRamMb} MB",
            "Recommended driver: ${p.recommendedDriver}"
        ).joinToString("\n")

        findViewById<Button>(R.id.pickExeButton).setOnClickListener {
            pickFile.launch(arrayOf("*/*"))
        }

        val containerReady = ContainerManager.ensureContainer(this)

        fun refreshEngineStatus() {
            findViewById<TextView>(R.id.engineStatus).text = listOf(
                "Container: ${if (containerReady) "ready" else "could not be created"}",
                "Engine base (PRoot + Linux): ${if (EngineManager.isInstalled(this)) "installed" else "not installed yet"}",
                "Wine: arrives as a downloadable pack on this engine",
                "Free space: ${ContainerManager.freeSpaceMb(this)} MB"
            ).joinToString("\n")
        }
        refreshEngineStatus()

        fun refreshEngine2Status() {
            findViewById<TextView>(R.id.engine2Status).text = listOf(
                "Ubuntu (glibc) rootfs: ${if (EngineManager.isGlibcInstalled(this)) "installed" else "not installed yet"}",
                "Box64 translator: ${if (EngineManager.isBox64Installed(this)) "installed" else "not installed yet"}"
            ).joinToString("\n")
        }
        refreshEngine2Status()

        val engineLog = findViewById<TextView>(R.id.engineLog)
        val installButton = findViewById<Button>(R.id.installEngineButton)
        val testButton = findViewById<Button>(R.id.runEngineTestButton)
        val install2Button = findViewById<Button>(R.id.installEngine2Button)
        val translationTestButton = findViewById<Button>(R.id.runTranslationTestButton)
        val installWineButton = findViewById<Button>(R.id.installWineButton)
        val wineTestButton = findViewById<Button>(R.id.runWineTestButton)
        val exeTestButton = findViewById<Button>(R.id.runExeTestButton)

        fun refreshWineStatus() {
            findViewById<TextView>(R.id.wineStatus).text =
                "Wine: ${if (EngineManager.isWineInstalled(this)) "installed" else "not installed yet (one-time download)"}"
        }
        refreshWineStatus()

        installButton.setOnClickListener {
            installButton.isEnabled = false
            engineLog.text = "Installing engine…"
            Thread {
                val status = EngineManager.install(this)
                runOnUiThread {
                    engineLog.text = status
                    refreshEngineStatus()
                    installButton.isEnabled = true
                }
            }.start()
        }

        testButton.setOnClickListener {
            testButton.isEnabled = false
            engineLog.text = "Running engine test…"
            Thread {
                val result = EngineManager.runTest(this)
                runOnUiThread {
                    engineLog.text =
                        (if (result.success) "ENGINE TEST PASSED ✅\n\n" else "ENGINE TEST DID NOT PASS\n\n") + result.log
                    testButton.isEnabled = true
                }
            }.start()
        }

        install2Button.setOnClickListener {
            install2Button.isEnabled = false
            engineLog.text = "Installing engine 2 (Ubuntu + Box64) — this one is bigger, give it a moment…"
            Thread {
                val status = EngineManager.installEngine2(this)
                runOnUiThread {
                    engineLog.text = status
                    refreshEngine2Status()
                    install2Button.isEnabled = true
                }
            }.start()
        }

        translationTestButton.setOnClickListener {
            translationTestButton.isEnabled = false
            engineLog.text = "Running translation test…"
            Thread {
                val result = EngineManager.runTranslationTest(this)
                runOnUiThread {
                    engineLog.text =
                        (if (result.success) "TRANSLATION TEST PASSED ✅\n\n" else "TRANSLATION TEST DID NOT PASS\n\n") + result.log
                    translationTestButton.isEnabled = true
                }
            }.start()
        }

        installWineButton.setOnClickListener {
            installWineButton.isEnabled = false
            engineLog.text = "Starting Wine download…"
            Thread {
                val status = EngineManager.installWine(this) { progress ->
                    runOnUiThread { engineLog.text = progress }
                }
                runOnUiThread {
                    engineLog.text = status
                    refreshWineStatus()
                    installWineButton.isEnabled = true
                }
            }.start()
        }

        wineTestButton.setOnClickListener {
            wineTestButton.isEnabled = false
            engineLog.text = "Running wine test — first run can take a while…"
            Thread {
                val result = EngineManager.runWineTest(this)
                runOnUiThread {
                    engineLog.text =
                        (if (result.success) "WINE TEST PASSED ✅\n\n" else "WINE TEST DID NOT PASS\n\n") + result.log
                    wineTestButton.isEnabled = true
                }
            }.start()
        }

        exeTestButton.setOnClickListener {
            exeTestButton.isEnabled = false
            engineLog.text = "Running a real Windows program (plink.exe) — the first run also builds the Wine prefix, give it up to a few minutes…"
            Thread {
                val result = EngineManager.runFirstExeTest(this)
                runOnUiThread {
                    engineLog.text =
                        (if (result.success) "FIRST .EXE RAN ✅\n\n" else "EXE TEST DID NOT PASS\n\n") + result.log
                    exeTestButton.isEnabled = true
                }
            }.start()
        }

        // ---- Graphics section ----
        fun refreshGraphicsStatus() {
            findViewById<TextView>(R.id.graphicsStatus).text = listOf(
                "X server + VNC: ${if (EngineManager.isGraphicsInstalled(this)) "installed" else "not installed yet"}",
                "Session: ${if (EngineManager.isGraphicsRunning()) "RUNNING" else "stopped"}"
            ).joinToString("\n")
        }
        refreshGraphicsStatus()

        val setupGraphicsButton = findViewById<Button>(R.id.setupGraphicsButton)
        val runGuiButton = findViewById<Button>(R.id.runGuiButton)
        val openScreenButton = findViewById<Button>(R.id.openScreenButton)
        val stopGraphicsButton = findViewById<Button>(R.id.stopGraphicsButton)

        setupGraphicsButton.setOnClickListener {
            setupGraphicsButton.isEnabled = false
            engineLog.text = "Starting graphics download…"
            Thread {
                val status = EngineManager.installGraphics(this) { progress ->
                    runOnUiThread { engineLog.text = progress }
                }
                runOnUiThread {
                    engineLog.text = status
                    refreshGraphicsStatus()
                    setupGraphicsButton.isEnabled = true
                }
            }.start()
        }

        runGuiButton.setOnClickListener {
            engineLog.text = EngineManager.startGraphics(this)
            refreshGraphicsStatus()
        }

        openScreenButton.setOnClickListener {
            startActivity(android.content.Intent(this, ScreenActivity::class.java))
        }

        stopGraphicsButton.setOnClickListener {
            EngineManager.stopGraphics()
            engineLog.text = "Graphics session stopped."
            refreshGraphicsStatus()
        }

        val diagnoseGraphicsButton = findViewById<Button>(R.id.diagnoseGraphicsButton)
        diagnoseGraphicsButton.setOnClickListener {
            diagnoseGraphicsButton.isEnabled = false
            engineLog.text = "Running graphics diagnostics…"
            Thread {
                val report = EngineManager.graphicsDiagnostics(this)
                runOnUiThread {
                    engineLog.text = report
                    refreshGraphicsStatus()
                    diagnoseGraphicsButton.isEnabled = true
                }
            }.start()
        }

        // ---- Run the user's own program ----
        runMyExeButton.setOnClickListener {
            val src = pickedExeFile
            val info = pickedExeInfo
            if (src == null || info == null) {
                engineLog.text = "Pick a .exe file first."
                return@setOnClickListener
            }
            if (!EngineManager.isEngine2Installed(this)) {
                engineLog.text =
                    "The engine isn't ready yet: tap INSTALL ENGINE 2 below first, then DOWNLOAD + INSTALL WINE, then RUN MY .EXE again."
                return@setOnClickListener
            }
            if (!EngineManager.isWineInstalled(this)) {
                engineLog.text =
                    "Wine isn't installed yet: tap DOWNLOAD + INSTALL WINE below first, then RUN MY .EXE again."
                return@setOnClickListener
            }
            if (info.isGui) {
                if (!EngineManager.isGraphicsInstalled(this)) {
                    engineLog.text =
                        "$pickedExeName is a windowed program — tap SETUP GRAPHICS once first, then RUN MY .EXE again."
                    return@setOnClickListener
                }
                if (EngineManager.isGraphicsRunning()) {
                    engineLog.text =
                        "A graphics session is already running — tap STOP GRAPHICS first, then RUN MY .EXE again."
                    return@setOnClickListener
                }
            }
            runMyExeButton.isEnabled = false
            engineLog.text = "Preparing $pickedExeName…"
            Thread {
                try {
                    val guestPath = EngineManager.stageUserExe(this, src, pickedExeName)
                    if (info.isGui) {
                        runOnUiThread {
                            engineLog.text = EngineManager.startGraphics(this, guestPath)
                            refreshGraphicsStatus()
                            runMyExeButton.isEnabled = true
                        }
                    } else {
                        val result = EngineManager.runUserExeConsole(this, guestPath)
                        runOnUiThread {
                            engineLog.text =
                                (if (result.success) "YOUR .EXE RAN ✅\n\n" else "YOUR .EXE FINISHED — see its exit line in the log\n\n") + result.log
                            runMyExeButton.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        engineLog.text = "Could not run $pickedExeName: ${e.message}"
                        runMyExeButton.isEnabled = true
                    }
                }
            }.start()
        }
    }

    private fun showPickedFile(uri: Uri) {
        val name = queryFileName(uri) ?: uri.lastPathSegment ?: "program.exe"
        pickedExeName = name
        pickedExeFile = null
        pickedExeInfo = null
        runMyExeButton.isEnabled = false
        pickedFileText.text = "You picked: $name\n\nCopying it into the app…"
        Thread {
            try {
                // A picked file can be large, and the engine can only
                // read real files — copy the whole thing into app
                // storage first, then inspect the copy.
                val dir = java.io.File(filesDir, "picked").apply { mkdirs() }
                val dest = java.io.File(dir, name.substringAfterLast('/').ifBlank { "program.exe" })
                val input = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("couldn't open the picked file")
                input.use { stream ->
                    dest.outputStream().use { out -> stream.copyTo(out) }
                }
                val info = dest.inputStream().use { ExeInspector.inspect(it) }
                pickedExeFile = dest
                pickedExeInfo = info
                runOnUiThread {
                    pickedFileText.text =
                        "You picked: $name\n\n${info.summary}\n${info.detail}"
                    runMyExeButton.isEnabled = info.isWindowsExe && !info.isDll
                }
            } catch (e: Exception) {
                runOnUiThread {
                    pickedFileText.text =
                        "You picked: $name\n\nCouldn't copy that file in: ${e.message}"
                }
            }
        }.start()
    }

    private fun queryFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }
}
