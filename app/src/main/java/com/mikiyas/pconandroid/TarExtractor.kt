package com.mikiyas.pconandroid

import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal ustar (.tar.gz) extractor — enough for Linux rootfs archives:
 * directories, regular files with permission bits, symlinks, hardlinks.
 * Written by hand so the app needs no extra libraries.
 */
object TarExtractor {

    fun extractTarGz(input: InputStream, destDir: File) {
        val stream = BufferedInputStream(GZIPInputStream(input), 64 * 1024)
        val header = ByteArray(512)
        var pendingLongName: String? = null

        while (true) {
            if (!readFully(stream, header, 512)) break
            if (header.all { it == 0.toByte() }) break // two zero blocks = end (one is enough here)

            val rawName = pendingLongName ?: (parseString(header, 345, 155).let { prefix ->
                val name = parseString(header, 0, 100)
                if (prefix.isNotEmpty()) "$prefix/$name" else name
            })
            pendingLongName = null
            val mode = parseOctal(header, 100, 8)
            val size = parseOctalLong(header, 124, 12)
            val type = header[156].toInt().toChar()
            val linkName = parseString(header, 157, 100)

            val cleanName = rawName.removePrefix("./").trim()
            val dataBlocks = ((size + 511) / 512) * 512

            if (cleanName.isEmpty() || cleanName.contains("..")) {
                skipFully(stream, dataBlocks)
                continue
            }
            val target = File(destDir, cleanName)

            when (type) {
                '5' -> { // directory
                    target.mkdirs()
                    skipFully(stream, dataBlocks)
                }
                '2' -> { // symlink
                    target.parentFile?.mkdirs()
                    if (target.exists() || isSymlink(target)) target.delete()
                    try { Os.symlink(linkName, target.absolutePath) } catch (e: Exception) { /* ignore */ }
                    skipFully(stream, dataBlocks)
                }
                '1' -> { // hardlink -> point at the already-extracted target
                    target.parentFile?.mkdirs()
                    val src = File(destDir, linkName.removePrefix("./"))
                    try {
                        if (target.exists()) target.delete()
                        Os.link(src.absolutePath, target.absolutePath)
                    } catch (e: Exception) {
                        try { Os.symlink("/" + linkName.removePrefix("./"), target.absolutePath) } catch (e2: Exception) { }
                    }
                    skipFully(stream, dataBlocks)
                }
                'L' -> { // GNU long name: content is the next entry's name
                    val buf = ByteArray(size.toInt())
                    readFully(stream, buf, buf.size)
                    pendingLongName = String(buf, Charsets.UTF_8).trimEnd('\u0000').trim()
                    skipFully(stream, dataBlocks - size)
                }
                'x', 'g' -> { // pax headers: not needed for rootfs archives
                    skipFully(stream, dataBlocks)
                }
                else -> { // regular file ('0' or '\0')
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        var remaining = size
                        val chunk = ByteArray(64 * 1024)
                        while (remaining > 0) {
                            val toRead = minOf(chunk.size.toLong(), remaining).toInt()
                            val n = stream.read(chunk, 0, toRead)
                            if (n < 0) break
                            out.write(chunk, 0, n)
                            remaining -= n
                        }
                    }
                    skipFully(stream, dataBlocks - size)
                    try { Os.chmod(target.absolutePath, mode) } catch (e: Exception) {
                        target.setExecutable((mode and 0b001001001) != 0, false)
                    }
                }
            }
        }
    }

    private fun isSymlink(f: File): Boolean = try {
        val canonical = f.canonicalPath
        f.absolutePath != canonical
    } catch (e: Exception) {
        false
    }

    private fun parseString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(buf: ByteArray, offset: Int, length: Int): Int =
        parseOctalLong(buf, offset, length).toInt()

    private fun parseOctalLong(buf: ByteArray, offset: Int, length: Int): Long {
        val s = parseString(buf, offset, length).trim().trimStart('0')
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8) ?: 0
    }

    private fun readFully(stream: InputStream, buf: ByteArray, count: Int): Boolean {
        var read = 0
        while (read < count) {
            val n = stream.read(buf, read, count - read)
            if (n < 0) return read > 0
            read += n
        }
        return true
    }

    private fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        val chunk = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = try {
                stream.skip(remaining)
            } catch (e: Exception) {
                0
            }
            if (skipped > 0) {
                remaining -= skipped
            } else {
                val toRead = minOf(chunk.size.toLong(), remaining).toInt()
                val n = stream.read(chunk, 0, toRead)
                if (n < 0) break
                remaining -= n
            }
        }
    }
}
