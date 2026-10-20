package com.mikiyas.pconandroid

import android.content.Context
import java.io.File

/**
 * A container is one isolated Windows environment: its own drive C:,
 * settings, and installed programs. Same idea as Winlator containers.
 *
 * v0.3 creates the folder structure. The Wine + Box64 engine gets
 * installed into it in the next build step.
 */
object ContainerManager {

    fun containerDir(context: Context): File =
        File(context.filesDir, "containers/default")

    /** Creates the container skeleton if missing. Returns true if it exists/ready. */
    fun ensureContainer(context: Context): Boolean {
        val root = containerDir(context)
        val dirs = listOf(
            File(root, "drive_c/Program Files"),
            File(root, "drive_c/Program Files (x86)"),
            File(root, "drive_c/windows/system32"),
            File(root, "drive_c/users/user"),
            File(root, "prefix")
        )
        return try {
            dirs.forEach { it.mkdirs() }
            root.exists()
        } catch (e: Exception) {
            false
        }
    }

    fun engineInstalled(context: Context): Boolean =
        File(containerDir(context), "engine/wine").exists()

    /** Free space on the phone's internal storage, in MB — the engine needs room. */
    fun freeSpaceMb(context: Context): Long =
        context.filesDir.usableSpace / (1024 * 1024)
}
