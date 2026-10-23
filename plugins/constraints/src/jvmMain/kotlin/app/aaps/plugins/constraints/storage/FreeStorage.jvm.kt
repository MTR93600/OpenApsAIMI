package app.aaps.plugins.constraints.storage

import java.io.File

/** The volume that holds the user's home directory, which is where the desktop app keeps its data. */
internal actual fun availableInternalStorageMb(): Long =
    File(System.getProperty("user.home") ?: ".").usableSpace / BYTES_PER_MB

private const val BYTES_PER_MB = 1048576L
