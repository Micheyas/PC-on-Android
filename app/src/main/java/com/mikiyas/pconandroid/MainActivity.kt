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
        val engineReady = ContainerManager.engineInstalled(this)
        findViewById<TextView>(R.id.engineStatus).text = listOf(
            "Container: ${if (containerReady) "ready" else "could not be created"}",
            "Engine (Wine + Box64): ${if (engineReady) "installed" else "not bundled yet — arrives in the next build step"}",
            "Free space: ${ContainerManager.freeSpaceMb(this)} MB"
        ).joinToString("\n")
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
