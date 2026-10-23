package app.aaps.plugins.constraints.storage

import android.os.Environment
import android.os.StatFs

/** The data partition, read with `StatFs`, exactly as the plugin did before it moved to common code. */
internal actual fun availableInternalStorageMb(): Long {
    val path = Environment.getDataDirectory()
    val stat = StatFs(path.path)
    val blockSize = stat.blockSizeLong
    val blocksAvailable = stat.availableBlocksLong
    val size = 1048576 // block size of 1 Mb
    return blocksAvailable * blockSize / size
}
