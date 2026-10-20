package com.mikiyas.pconandroid

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * The built-in screen: shows the guest X server's framebuffer via
 * [RfbClient] and forwards touch as mouse, plus a type-and-send bar
 * (a real keyboard story comes later; this already drives PuTTY).
 */
class ScreenActivity : AppCompatActivity() {

    private var client: RfbClient? = null
    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        statusText = TextView(this).apply {
            text = "Connecting to the Windows screen…"
            setTextColor(Color.WHITE)
            setPadding(24, 16, 24, 8)
            textSize = 14f
        }
        root.addView(statusText)

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.DKGRAY)
        }
        root.addView(imageView)

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 12)
        }
        val input = EditText(this).apply {
            hint = "Type here…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(input)
        val sendButton = Button(this).apply { text = "SEND" }
        bar.addView(sendButton)
        val enterButton = Button(this).apply { text = "ENTER" }
        bar.addView(enterButton)
        root.addView(bar)

        setContentView(root)

        sendButton.setOnClickListener {
            val text = input.text.toString()
            if (text.isNotEmpty()) {
                client?.sendText(text)
                input.text.clear()
            }
        }
        enterButton.setOnClickListener {
            client?.sendKey(0xFF0D, true)
            client?.sendKey(0xFF0D, false)
        }

        imageView.setOnTouchListener { v, event ->
            val c = client ?: return@setOnTouchListener false
            val bmp = c.bitmap ?: return@setOnTouchListener false
            val point = toFramebufferCoords(v as ImageView, bmp, event.x, event.y) ?: return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                    c.sendPointer(1, point.x.toInt(), point.y.toInt())
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    c.sendPointer(0, point.x.toInt(), point.y.toInt())
            }
            true
        }

        connect()
    }

    private fun connect() {
        client?.close()
        val c = RfbClient(
            onReady = { bmp ->
                runOnUiThread {
                    imageView.setImageBitmap(bmp)
                    statusText.text = "Live — the Windows screen. Tap to click; session: PuTTY."
                }
            },
            onFrame = {
                runOnUiThread { imageView.invalidate() }
            },
            onError = { message ->
                runOnUiThread {
                    statusText.text = "Screen connection problem: $message"
                    Toast.makeText(this, "Is the graphics session running? Start it from the main screen first.", Toast.LENGTH_LONG).show()
                }
            }
        )
        client = c
        c.connect()
    }

    /** Maps a touch point on the (letterboxed) ImageView to framebuffer pixels. */
    private fun toFramebufferCoords(view: ImageView, bmp: Bitmap, x: Float, y: Float): PointF? {
        val matrix = Matrix()
        if (!view.imageMatrix.invert(matrix)) return null
        val pts = floatArrayOf(x, y)
        matrix.mapPoints(pts)
        if (pts[0] < 0 || pts[1] < 0 || pts[0] >= bmp.width || pts[1] >= bmp.height) return null
        return PointF(pts[0], pts[1])
    }

    override fun onDestroy() {
        client?.close()
        super.onDestroy()
    }
}
