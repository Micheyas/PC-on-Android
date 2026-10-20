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
                "Wine + Box64: arrives with the full engine rootfs",
                "Free space: ${ContainerManager.freeSpaceMb(this)} MB"
            ).joinToString("\n")
        }
        refreshEngineStatus()

        val engineLog = findViewById<TextView>(R.id.engineLog)
        val installButton = findViewById<Button>(R.id.installEngineButton)
        val testButton = findViewById<Button>(R.id.runEngineTestButton)

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
