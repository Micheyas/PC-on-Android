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

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) showPickedFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pickedFileText = findViewById(R.id.pickedFile)

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
    }

    private fun showPickedFile(uri: Uri) {
        val name = queryFileName(uri) ?: uri.lastPathSegment ?: "unknown file"
        val result = try {
            contentResolver.openInputStream(uri)?.use { ExeInspector.inspect(it) }
        } catch (e: Exception) {
            null
        }
        pickedFileText.text = if (result == null) {
            "You picked: $name\n\nCouldn't open that file for inspection."
        } else {
            "You picked: $name\n\n${result.summary}\n${result.detail}"
        }
    }

    private fun queryFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }
}
