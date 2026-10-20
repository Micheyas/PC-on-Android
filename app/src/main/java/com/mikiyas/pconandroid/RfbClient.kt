package com.mikiyas.pconandroid

import android.graphics.Bitmap
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * A minimal RFB (VNC) client, just enough to show and drive the
 * guest's X server (x11vnc on localhost:5900): no-auth handshake,
 * 32bpp true-colour, Raw encoding, incremental framebuffer updates,
 * pointer + key events.
 */
class RfbClient(
    private val onReady: (Bitmap) -> Unit,
    private val onFrame: () -> Unit,
    private val onError: (String) -> Unit
) {
    @Volatile private var running = false
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val writeLock = Any()

    var bitmap: Bitmap? = null
        private set
    var frameWidth = 0
        private set
    var frameHeight = 0
        private set

    fun connect(host: String = "127.0.0.1", port: Int = 5900) {
        running = true
        thread(name = "rfb") {
            try {
                runSession(host, port)
            } catch (e: Exception) {
                if (running) onError(e.message ?: "connection failed")
            }
        }
    }

    fun close() {
        running = false
        try { socket?.close() } catch (e: Exception) { /* already closed */ }
    }

    private fun runSession(host: String, port: Int) {
        val s = Socket(host, port)
        socket = s
        val input = DataInputStream(s.getInputStream())
        out = s.getOutputStream()
        val o = out!!

        // --- Protocol handshake (RFB 3.8) ---
        val serverVersion = ByteArray(12)
        input.readFully(serverVersion)
        o.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); o.flush()

        val typeCount = input.readUnsignedByte()
        if (typeCount == 0) {
            val len = input.readInt()
            val reason = ByteArray(len); input.readFully(reason)
            throw IllegalStateException("Server refused: ${String(reason)}")
        }
        val types = ByteArray(typeCount); input.readFully(types)
        if (!types.contains(1)) throw IllegalStateException("Server offers no no-auth security")
        o.write(byteArrayOf(1)); o.flush()
        val securityResult = input.readInt()
        if (securityResult != 0) throw IllegalStateException("Security handshake failed")

        // --- ClientInit / ServerInit ---
        o.write(byteArrayOf(1)); o.flush() // shared session
        frameWidth = input.readUnsignedShort()
        frameHeight = input.readUnsignedShort()
        val serverFormat = ByteArray(16); input.readFully(serverFormat)
        val nameLen = input.readInt()
        if (nameLen > 0) input.readFully(ByteArray(nameLen))

        val bmp = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
        bitmap = bmp
        onReady(bmp)

        // --- SetPixelFormat: 32bpp, depth 24, little-endian, true colour,
        // red shift 16, green 8, blue 0 (bytes arrive B,G,R,X). ---
        val setFormat = ByteArray(20)
        setFormat[0] = 0 // message type
        setFormat[4] = 32 // bits per pixel
        setFormat[5] = 24 // depth
        setFormat[6] = 0 // big-endian flag
        setFormat[7] = 1 // true colour
        setFormat[9] = 255.toByte() // red max (u16 BE, low byte)
        setFormat[11] = 255.toByte() // green max
        setFormat[13] = 255.toByte() // blue max
        setFormat[14] = 16 // red shift
        setFormat[15] = 8 // green shift
        setFormat[16] = 0 // blue shift
        sendRaw(setFormat)

        // --- SetEncodings: Raw only ---
        sendRaw(byteArrayOf(2, 0, 0, 1, 0, 0, 0, 0))

        // First request: full framebuffer.
        requestUpdate(incremental = false)

        // --- Server message loop ---
        val pixelBuf = ByteArray(64 * 1024)
        while (running) {
            val type = input.readUnsignedByte()
            when (type) {
                0 -> { // FramebufferUpdate
                    input.readUnsignedByte() // padding
                    val rects = input.readUnsignedShort()
                    repeat(rects) {
                        val x = input.readUnsignedShort()
                        val y = input.readUnsignedShort()
                        val w = input.readUnsignedShort()
                        val h = input.readUnsignedShort()
                        val encoding = input.readInt()
                        if (encoding != 0) {
                            throw IllegalStateException("Unsupported encoding $encoding")
                        }
                        readRect(input, bmp, x, y, w, h, pixelBuf)
                    }
                    onFrame()
                    if (running) requestUpdate(incremental = true)
                }
                1 -> { // SetColourMapEntries — not expected in true colour; consume safely
                    input.readUnsignedByte()
                    input.readUnsignedShort() // first colour
                    val count = input.readUnsignedShort()
                    repeat(count * 3) { input.readUnsignedShort() }
                }
                2 -> { /* Bell — nothing to do */ }
                3 -> { // ServerCutText
                    input.readFully(ByteArray(3)) // padding
                    val len = input.readInt()
                    if (len > 0) input.readFully(ByteArray(len))
                }
                else -> throw IllegalStateException("Unknown server message $type")
            }
        }
    }

    private fun readRect(
        input: DataInputStream, bmp: Bitmap,
        x: Int, y: Int, w: Int, h: Int, scratch: ByteArray
    ) {
        if (w <= 0 || h <= 0) return
        val need = w * h * 4
        val buf = if (need <= scratch.size) scratch else ByteArray(need)
        input.readFully(buf, 0, need)
        val pixels = IntArray(w * h)
        var i = 0
        var p = 0
        while (p < pixels.size) {
            val b = buf[i].toInt() and 0xFF
            val g = buf[i + 1].toInt() and 0xFF
            val r = buf[i + 2].toInt() and 0xFF
            pixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            i += 4
            p++
        }
        synchronized(bmp) { bmp.setPixels(pixels, 0, w, x, y, w, h) }
    }

    private fun requestUpdate(incremental: Boolean) {
        val msg = ByteArray(10)
        msg[0] = 3
        msg[1] = if (incremental) 1 else 0
        putU16(msg, 2, 0); putU16(msg, 4, 0)
        putU16(msg, 6, frameWidth); putU16(msg, 8, frameHeight)
        sendRaw(msg)
    }

    fun sendPointer(buttonMask: Int, x: Int, y: Int) {
        val msg = ByteArray(6)
        msg[0] = 5
        msg[1] = buttonMask.toByte()
        putU16(msg, 2, x.coerceIn(0, frameWidth - 1))
        putU16(msg, 4, y.coerceIn(0, frameHeight - 1))
        sendRaw(msg)
    }

    fun sendKey(keysym: Int, down: Boolean) {
        val msg = ByteArray(8)
        msg[0] = 4
        msg[1] = if (down) 1 else 0
        putU32(msg, 4, keysym)
        sendRaw(msg)
    }

    fun sendText(text: String) {
        for (c in text) {
            val keysym = when (c) {
                '\n' -> 0xFF0D
                '\t' -> 0xFF09
                else -> c.code
            }
            sendKey(keysym, true)
            sendKey(keysym, false)
        }
    }

    private fun sendRaw(bytes: ByteArray) {
        synchronized(writeLock) {
            try {
                out?.write(bytes)
                out?.flush()
            } catch (e: Exception) { /* closed */ }
        }
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 24).toByte()
        b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
