package app.aaps.plugins.constraints.storage

/**
 * Free space, in MB (1 MB = 1048576 bytes), on the volume where the app keeps its database and logs.
 *
 * The only platform call `StorageConstraintPlugin` makes. Each platform reads it its own way, and the
 * rule that uses it - closed loop off below `Constants.MINIMUM_FREE_SPACE` - stays shared.
 */
internal expect fun availableInternalStorageMb(): Long
