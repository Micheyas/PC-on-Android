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
                    // A symlink-to-directory at this path must SURVIVE —
                    // mkdirs() works through it. Merged-usr rootfses ship
                    // lib -> usr/lib and bin -> usr/bin as symlinks; the
                    // graphics pack carries a real ./lib directory entry,
                    // and deleting the link to mkdir a real dir orphaned
                    // /lib/ld-linux-aarch64.so.1, so every dynamic binary
                    // in the guest failed to exec (PRoot: execve
                    // "/usr/bin/sh": No such file or directory, on-device
                    // 2026-10-21). Only non-directory links get replaced.
                    if (isSymlinkLexical(target) && !target.isDirectory) deleteLexical(target)
                    target.mkdirs()
                    skipFully(stream, dataBlocks)
                }
                '2' -> { // symlink
                    target.parentFile?.mkdirs()
                    // Rootfs links are often ABSOLUTE (e.g. bin/sh -> /bin/busybox),
                    // so File.exists() is false for them on the host even when the
                    // link is right there. Delete by lstat, not by exists().
                    deleteLexical(target)
                    try { Os.symlink(linkName, target.absolutePath) } catch (e: Exception) { /* ignore */ }
                    skipFully(stream, dataBlocks)
                }
                '1' -> { // hardlink -> point at the already-extracted target
                    target.parentFile?.mkdirs()
                    val src = File(destDir, linkName.removePrefix("./"))
                    try {
                        deleteLexical(target)
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
                '3', '4', '6', '7' -> { // device nodes / fifos: can't and needn't create them
                    skipFully(stream, dataBlocks)
                }
                else -> { // regular file ('0' or '\0')
                    target.parentFile?.mkdirs()
                    // Never write THROUGH a leftover symlink: FileOutputStream
                    // follows links, and an absolute rootfs link would point
                    // outside the container. Remove links/dirs first.
                    if (isSymlinkLexical(target) || (lexists(target) && target.isDirectory)) {
                        deleteLexical(target)
                    }
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

    /** True if anything (file, dir, or link — broken or not) sits at this path. */
    private fun lexists(f: File): Boolean = try {
        Os.lstat(f.absolutePath)
        true
    } catch (e: Exception) {
        false
    }

    private fun isSymlinkLexical(f: File): Boolean = try {
        android.system.OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
    } catch (e: Exception) {
        false
    }

    /**
     * Deletes the entry at this path without following symlinks. A real
     * directory is emptied recursively first (Os.remove refuses non-empty
     * dirs) — needed when a symlink entry (e.g. lib -> usr/lib from the
     * rootfs archive) must replace a real directory left behind by a bad
     * earlier extraction. Children are classified by lstat, so a link
     * inside is removed as a link, never followed out of the container.
     */
    private fun deleteLexical(f: File) {
        try {
            if (!lexists(f)) return
            val st = Os.lstat(f.absolutePath)
            if (android.system.OsConstants.S_ISDIR(st.st_mode) &&
                !android.system.OsConstants.S_ISLNK(st.st_mode)
            ) {
                f.listFiles()?.forEach { deleteLexical(it) }
            }
            Os.remove(f.absolutePath)
        } catch (e: Exception) {
            try { f.delete() } catch (e2: Exception) { /* ignore */ }
        }
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
