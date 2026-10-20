package com.mikiyas.pconandroid

import java.io.InputStream

/**
 * Reads a Windows program's PE header and reports what it is and which
 * translation path it will take in this app (Box64 for 64-bit, Box86 for
 * 32-bit). This is the first real step of "running" a program: know the file.
 */
object ExeInspector {

    data class Result(
        val isWindowsExe: Boolean,
        val summary: String,
        val detail: String
    )

    fun inspect(input: InputStream): Result {
        return try {
            val head = ByteArray(8192)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            if (read < 64 || head[0] != 'M'.code.toByte() || head[1] != 'Z'.code.toByte()) {
                return Result(false, "Not a Windows program", "The file doesn't start with the MZ signature every .exe has.")
            }

            // PE header offset lives at byte 0x3C in the DOS header.
            val peOffset = int32LE(head, 0x3C)
            if (peOffset <= 0 || peOffset + 6 > read ||
                head[peOffset] != 'P'.code.toByte() || head[peOffset + 1] != 'E'.code.toByte()
            ) {
                return Result(false, "Has MZ but no PE header", "Probably a very old DOS program or a damaged file.")
            }

            val machine = int16LE(head, peOffset + 4)
            return when (machine) {
                0x8664 -> Result(
                    true,
                    "Windows program — 64-bit (x86-64)",
                    "Translation path: Box64 + Wine. This is the most common kind — the main target of this app."
                )
                0x014C -> Result(
                    true,
                    "Windows program — 32-bit (x86)",
                    "Translation path: Box86/Box64 + Wine. Older programs are usually this kind."
                )
                0xAA64 -> Result(
                    true,
                    "Windows program — ARM64",
                    "A rare Windows-on-ARM build. Different path: Wine ARM, no x86 translation needed."
                )
                else -> Result(
                    true,
                    "Windows program — machine type 0x${machine.toString(16)}",
                    "A valid .exe, but an unusual processor type."
                )
            }
        } catch (e: Exception) {
            Result(false, "Couldn't read the file", e.message ?: "unknown error")
        }
    }

    private fun int32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun int16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
