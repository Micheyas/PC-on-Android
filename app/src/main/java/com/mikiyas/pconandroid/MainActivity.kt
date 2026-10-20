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
    }

    private fun showPickedFile(uri: Uri) {
        val name = queryFileName(uri) ?: uri.lastPathSegment ?: "unknown file"
        pickedFileText.text =
            "You picked: $name\n\nRunning Windows programs arrives in the next step — " +
            "the Wine + Box64 engine gets bundled here. This screen proves the picker " +
            "and the phone detection work."
    }

    private fun queryFileName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }
}
