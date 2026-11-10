package app.aaps.database

/**
 * Tracks long-running SQLite maintenance (VACUUM / WAL checkpoint) so callers can avoid
 * piling on heavy reads while the DB is locked.
 */
object DatabaseMaintenanceCoordinator {

    @Volatile
    var isCompactionInProgress: Boolean = false
        private set

    fun markCompactionStarted() {
        isCompactionInProgress = true
    }

    fun markCompactionFinished() {
        isCompactionInProgress = false
    }
}
