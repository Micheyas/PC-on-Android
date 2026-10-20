package com.mikiyas.pconandroid

import java.io.InputStream

/**
 * Reads a Windows program's PE header and reports what it is and which
 * translation path it will take in this app (Box64 for 64-bit, Box86 for
 * 32-bit). This is the first real step of "running" a program: know the file.
 *
 * Since v0.19 it also reads the optional header's Subsystem field, which
 * says how the program must be run: 2 = it opens its own window (GUI —
 * needs the graphics screen), 3 = text-only console program (runs in
 * place and prints its output). The Subsystem field sits at offset 68
 * of the optional header for both PE32 and PE32+, and the optional
 * header starts 24 bytes after the PE signature offset.
 */
object ExeInspector {

    data class Result(
        val isWindowsExe: Boolean,
        val summary: String,
        val detail: String,
        val subsystem: Int? = null,
        val isDll: Boolean = false
    ) {
        /** True when the program opens its own window (subsystem 2). */
        val isGui: Boolean get() = subsystem == 2
    }

    fun inspect(input: InputStream): Result {
        return try {
            val head = ByteArray(65536)
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
            // Characteristics is the last field of the 20-byte file
            // header; the IMAGE_FILE_DLL bit (0x2000) marks a library.
            val characteristics = if (peOffset + 24 <= read) int16LE(head, peOffset + 22) else 0
            val isDll = characteristics and 0x2000 != 0
            // Optional header starts after signature (4) + file header (20).
            val optStart = peOffset + 24
            val subsystem = if (optStart + 70 <= read) int16LE(head, optStart + 68) else null

            val arch = when (machine) {
                0x8664 -> "Windows program — 64-bit (x86-64)" to
                    "Translation path: Box64 + Wine. This is the most common kind — the main target of this app."
                0x014C -> "Windows program — 32-bit (x86)" to
                    "Translation path: Box86/Box64 + Wine. Older programs are usually this kind."
                0xAA64 -> "Windows program — ARM64" to
                    "A rare Windows-on-ARM build. Different path: Wine ARM, no x86 translation needed."
                else -> "Windows program — machine type 0x${machine.toString(16)}" to
                    "A valid .exe, but an unusual processor type."
            }

            val kind = when {
                isDll ->
                    "It's a .dll library, not a program — libraries can't be run directly."
                subsystem == 2 ->
                    "Windowed program: it opens its own window, so it runs on the graphics screen (OPEN SCREEN shows it)."
                subsystem == 3 ->
                    "Console program: text-only, so it runs right here and prints its output in the log below."
                subsystem != null ->
                    "Subsystem type $subsystem — unusual; it will be tried as a console program."
                else ->
                    "Couldn't read the program kind from its header; it will be tried as a console program."
            }

            Result(true, arch.first, "${arch.second} $kind", subsystem, isDll)
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
