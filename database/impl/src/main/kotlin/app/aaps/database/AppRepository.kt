package app.aaps.database

import android.os.StatFs
import androidx.room.Transactor.SQLiteTransactionType
import androidx.room.useWriterConnection
import app.aaps.database.entities.APSResult
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.BolusCalculatorResult
import app.aaps.database.entities.CalibrationEntry
import app.aaps.database.entities.Carbs
import app.aaps.database.entities.DeviceStatus
import app.aaps.database.entities.EffectiveProfileSwitch
import app.aaps.database.entities.ExtendedBolus
import app.aaps.database.entities.Food
import app.aaps.database.entities.GlucoseValue
import app.aaps.database.entities.HeartRate
import app.aaps.database.entities.ProfileSwitch
import app.aaps.database.entities.RunningMode
import app.aaps.database.entities.StepsCount
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.TemporaryTarget
import app.aaps.database.entities.TherapyEvent
import app.aaps.database.entities.TotalDailyDose
import app.aaps.database.entities.UserEntry
import app.aaps.database.entities.data.NewEntries
import app.aaps.database.entities.embedments.InterfaceIDs
import app.aaps.database.entities.interfaces.DBEntry
import app.aaps.database.transactions.InsertBolusWithTempIdTransaction
import app.aaps.database.transactions.InsertTemporaryBasalWithTempIdTransaction
import app.aaps.database.transactions.Transaction
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days

/** Module-local carrier for [AppRepository.databaseMaintenanceInfo]; mapped to `DatabaseMaintenanceInfo`. */
data class DatabaseMaintenanceRaw(
    val dbSizeBytes: Long,
    val availableBytes: Long,
    val totalRows: Long,
    val deletableRows: Long,
    val changeRows: Long,
    val report: String
)

@Singleton
class AppRepository @Inject internal constructor(
    internal val database: AppDatabase
) : Closeable {

    private val changeSubject = PublishSubject.create<List<DBEntry>>()

    /**
     * Coroutine scope for Flow emissions
     * Using SupervisorJob so failures don't cancel the entire scope
     */
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * SharedFlow for broadcasting database changes
     * - replay = 0: No replay, only new changes
     * - extraBufferCapacity = 64: Buffer fast emissions
     * - onBufferOverflow = DROP_OLDEST: Drop old events if buffer full
     */
    private val _changeFlow = MutableSharedFlow<List<DBEntry>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _databaseClearedFlow = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Observe ALL database changes as Flow
     */
    fun changeFlow(): Flow<List<DBEntry>> = _changeFlow.asSharedFlow()

    fun databaseClearedFlow(): Flow<Unit> = _databaseClearedFlow.asSharedFlow()

    /**
     * Observe database changes filtered by entity type
     * Example: repository.changesOfType<TemporaryBasal>()
     */
    inline fun <reified T : DBEntry> changesOfType(): Flow<List<T>> =
        changeFlow()
            .map { changes -> changes.filterIsInstance<T>() }
            .filter { it.isNotEmpty() }

    /**
     * Executes a transaction ignoring its result (coroutine version)
     * Uses Room's suspend withTransaction API for proper coroutine support
     * Emits to BOTH RxJava (existing) AND Flow (new)
     */
    suspend fun <T> runTransactionSuspend(transaction: Transaction<T>) {
        // The COMMIT and its change-notification must be one atomic, uninterruptible unit. If the
        // caller (e.g. a WorkManager worker being stopped by Doze/standby/resource pressure) is
        // cancelled in the window between the SQLite COMMIT and the emit below, the row would be
        // durably persisted while observers (loop/overview/sync via observeChanges) never fire —
        // a silently dropped reading with no loop run. NonCancellable closes that window.
        withContext(NonCancellable) {
            val changes = mutableListOf<DBEntry>()
            database.useWriterConnection { connection ->
                connection.withTransaction(SQLiteTransactionType.IMMEDIATE) {
                    transaction.database = DelegatedAppDatabase(changes, database)
                    transaction.run()
                }
            }
            // Drop the temp basal and bolus copies first, so nobody reads stale data after the change event.
            invalidateSnapshotsIfNeeded(changes, transaction)
            // Emit to RxJava (existing) - for backwards compatibility
            changeSubject.onNext(changes)

            // Emit to Flow (new)
            if (changes.isNotEmpty()) {
                _changeFlow.emit(changes)
            }
        }
    }

    /**
     * Executes a transaction and returns its result (coroutine version)
     * Uses Room's suspend withTransaction API for proper coroutine support
     * Emits to BOTH RxJava (existing) AND Flow (new)
     */
    suspend fun <T : Any> runTransactionForResultSuspend(transaction: Transaction<T>): T =
    // The COMMIT and its change-notification must be one atomic, uninterruptible unit. If the
    // caller (e.g. a WorkManager worker being stopped by Doze/standby/resource pressure) is
    // cancelled in the window between the SQLite COMMIT and the emit below, the row would be
    // durably persisted while observers (loop/overview/sync via observeChanges) never fire —
        // a silently dropped reading with no loop run. NonCancellable closes that window.
        withContext(NonCancellable) {
            val changes = mutableListOf<DBEntry>()
            val result = database.useWriterConnection { connection ->
                connection.withTransaction(SQLiteTransactionType.IMMEDIATE) {
                    transaction.database = DelegatedAppDatabase(changes, database)
                    transaction.run()
                }
            }
            // Drop the temp basal and bolus copies first, so nobody reads stale data after the change event.
            invalidateSnapshotsIfNeeded(changes, transaction)
            // Emit to RxJava (existing) - for backwards compatibility
            changeSubject.onNext(changes)

            // Emit to Flow (new)
            if (changes.isNotEmpty()) {
                _changeFlow.emit(changes)
            }
            result
        }

    fun clearDatabases() {
        invalidateTbrSnapshot()
        invalidateBolusSnapshot()
        database.clearAllTables()
        repositoryScope.launch { _databaseClearedFlow.emit(Unit) }
    }

    fun clearApsResults() = database.apsResultDao.deleteAllEntries()

    suspend fun cleanupDatabase(keepDays: Long, deleteTrackedChanges: Boolean, runVacuum: Boolean = false): String {
        invalidateTbrSnapshot()
        invalidateBolusSnapshot()
        database.useWriterConnection { connection -> connection.usePrepared("PRAGMA optimize") { it.step() } }
        val than = System.currentTimeMillis() - keepDays.days.inWholeMilliseconds
        val removed = mutableListOf<Pair<String, Int>>()
        removed.add(Pair("APSResult", database.apsResultDao.deleteOlderThan(than)))
        removed.add(Pair("GlucoseValue", database.glucoseValueDao.deleteOlderThan(than)))
        removed.add(Pair("TherapyEvent", database.therapyEventDao.deleteOlderThan(than)))
        removed.add(Pair("TemporaryBasal", database.temporaryBasalDao.deleteOlderThan(than)))
        removed.add(Pair("ExtendedBolus", database.extendedBolusDao.deleteOlderThan(than)))
        removed.add(Pair("Bolus", database.bolusDao.deleteOlderThan(than)))
        removed.add(Pair("TotalDailyDose", database.totalDailyDoseDao.deleteOlderThan(than)))
        removed.add(Pair("Carbs", database.carbsDao.deleteOlderThan(than)))
        removed.add(Pair("TemporaryTarget", database.temporaryTargetDao.deleteOlderThan(than)))
        removed.add(Pair("BolusCalculatorResult", database.bolusCalculatorResultDao.deleteOlderThan(than)))
        // keep at least one permanent EPS (don't delete if only expired temporaries exist within window)
        if (database.effectiveProfileSwitchDao.hasPermanentRecordSince(than + 1))
            removed.add(Pair("EffectiveProfileSwitch", database.effectiveProfileSwitchDao.deleteOlderThan(than)))
        // keep at least one permanent PS
        if (database.profileSwitchDao.hasPermanentRecordSince(than + 1))
            removed.add(Pair("ProfileSwitch", database.profileSwitchDao.deleteOlderThan(than)))
        removed.add(Pair("ApsResult", database.apsResultDao.deleteOlderThan(than)))
        // keep version history database.versionChangeDao.deleteOlderThan(than)
        removed.add(Pair("UserEntry", database.userEntryDao.deleteOlderThan(than)))
        removed.add(Pair("PreferenceChange", database.preferenceChangeDao.deleteOlderThan(than)))
        // keep foods database.foodDao.deleteOlderThan(than)
        removed.add(Pair("DeviceStatus", database.deviceStatusDao.deleteOlderThan(than)))
        // keep at least one permanent RM (don't delete if only expired temporaries exist within window)
        if (database.runningModeDao.hasPermanentRecordSince(than + 1))
            removed.add(Pair("RunningMode", database.runningModeDao.deleteOlderThan(than)))
        removed.add(Pair("HeartRate", database.heartRateDao.deleteOlderThan(than)))
        removed.add(Pair("StepsCount", database.stepsCountDao.deleteOlderThan(than)))

        if (deleteTrackedChanges) {
            removed.add(Pair("CHANGES APSResult", database.apsResultDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES GlucoseValue", database.glucoseValueDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES TherapyEvent", database.therapyEventDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES TemporaryBasal", database.temporaryBasalDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES Bolus", database.bolusDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES ExtendedBolus", database.extendedBolusDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES TotalDailyDose", database.totalDailyDoseDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES Carbs", database.carbsDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES TemporaryTarget", database.temporaryTargetDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES BolusCalculatorResult", database.bolusCalculatorResultDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES EffectiveProfileSwitch", database.effectiveProfileSwitchDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES ProfileSwitch", database.profileSwitchDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES ApsResult", database.apsResultDao.deleteTrackedChanges()))
            // keep food database.foodDao.deleteHistory()
            removed.add(Pair("CHANGES RunningMode", database.runningModeDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES HeartRate", database.heartRateDao.deleteTrackedChanges()))
            removed.add(Pair("CHANGES StepsCount", database.stepsCountDao.deleteTrackedChanges()))
        }
        repositoryScope.launch { _databaseClearedFlow.emit(Unit) }
        val ret = StringBuilder()
        removed
            .filter { it.second > 0 }
            .forEach { ret.append(it.first + " " + it.second + "<br>") }
        // VACUUM is intentionally NOT run inline here (SQLITE_NOMEM risk); use [vacuumDatabase] when
        // [runVacuum] is requested from maintenance UI. Startup uses [maintainDatabaseAtStartup] only.
        database.useWriterConnection { connection -> connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() } }
        if (runVacuum) {
            DatabaseMaintenanceCoordinator.markCompactionStarted()
            try {
                vacuumDatabase()
            } catch (e: Exception) {
                ret.append("VACUUM failed: ${e.message}<br>")
            } finally {
                DatabaseMaintenanceCoordinator.markCompactionFinished()
            }
        }
        return ret.toString()
    }

    /**
     * Startup-safe maintenance without VACUUM (see [vacuumDatabase] for full defragmentation).
     */
    suspend fun maintainDatabaseAtStartup() {
        DatabaseMaintenanceCoordinator.markCompactionStarted()
        try {
            database.useWriterConnection { connection ->
                connection.usePrepared("PRAGMA optimize") { it.step() }
                connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() }
            }
        } finally {
            DatabaseMaintenanceCoordinator.markCompactionFinished()
        }
    }

    /**
     * Full VACUUM: defragments the DB file and returns free pages to the OS. Heavy and memory
     * intensive — use only for explicit user-initiated maintenance ([cleanupDatabase] with
     * [runVacuum]). May throw if the DB is busy/locked.
     */
    suspend fun vacuumDatabase() {
        DatabaseMaintenanceCoordinator.markCompactionStarted()
        try {
            database.useWriterConnection { connection ->
                connection.usePrepared("PRAGMA wal_checkpoint(TRUNCATE)") { it.step() }
                connection.usePrepared("VACUUM") { it.step() }
            }
        } finally {
            DatabaseMaintenanceCoordinator.markCompactionFinished()
        }
    }

    /**
     * Read-only diagnostics gathered before a startup VACUUM (sizes, free space, aggregate row
     * counts and a human-readable per-table report). Tables are discovered from `sqlite_master`, and
     * every count is independent and failure-tolerant (missing column → -1), so a schema quirk can
     * never break logging. Returned as a module-local type because `database:impl` does not depend on
     * `core:interfaces`; [app.aaps.database.persistence] maps it to `DatabaseMaintenanceInfo`.
     */
    suspend fun databaseMaintenanceInfo(retentionDays: Long): DatabaseMaintenanceRaw {
        val cutoff = System.currentTimeMillis() - retentionDays.days.inWholeMilliseconds
        return database.useWriterConnection { connection ->
            suspend fun count(sql: String): Long =
                try {
                    connection.usePrepared(sql) { if (it.step()) it.getLong(0) else 0L }
                } catch (_: Throwable) {
                    -1L
                }

            // Driver mode has no openHelper.path, so resolve the file from PRAGMA database_list.
            var path = ""
            connection.usePrepared("PRAGMA database_list") { stmt ->
                while (stmt.step()) if (stmt.getText(1) == "main") path = stmt.getText(2)
            }
            val dbFile = File(path)
            val dbSize = dbFile.length() + File("$path-wal").length() + File("$path-shm").length()
            val available = dbFile.parent?.let { runCatching { StatFs(it).availableBytes }.getOrDefault(-1L) } ?: -1L

            val tables = mutableListOf<String>()
            connection.usePrepared(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' AND name != 'android_metadata' ORDER BY name"
            ) { stmt -> while (stmt.step()) tables.add(stmt.getText(0)) }

            val sb = StringBuilder()
            sb.append("file=${dbFile.name} size=${dbSize / 1024}KB free=${if (available < 0) "?" else "${available / 1_048_576}MB"} tables=${tables.size}\n")
            var totRows = 0L
            var totOld = 0L
            var totChanges = 0L
            for (t in tables) {
                val all = count("SELECT COUNT(*) FROM `$t`")
                val old = count("SELECT COUNT(*) FROM `$t` WHERE timestamp < $cutoff")
                val changes = count("SELECT COUNT(*) FROM `$t` WHERE referenceId IS NOT NULL")
                if (all > 0) totRows += all
                if (old > 0) totOld += old
                if (changes > 0) totChanges += changes
                sb.append("  $t total=$all old=$old changes=$changes\n")
            }
            sb.append("TOTAL rows=$totRows deletable(>${retentionDays}d)=$totOld changes=$totChanges")
            DatabaseMaintenanceRaw(dbSize, available, totRows, totOld, totChanges, sb.toString())
        }
    }

    suspend fun clearCachedTddData(from: Long) = database.totalDailyDoseDao.deleteNewerThan(from, InterfaceIDs.PumpType.CACHE)

    //BG READINGS -- only valid records
    suspend fun compatGetBgReadingsDataFromTime(timestamp: Long, ascending: Boolean): List<GlucoseValue> =
        database.glucoseValueDao.compatGetBgReadingsDataFromTime(timestamp).reversedIf(!ascending)

    suspend fun compatGetBgReadingsDataFromTime(start: Long, end: Long, ascending: Boolean): List<GlucoseValue> =
        database.glucoseValueDao.compatGetBgReadingsDataFromTime(start, end).reversedIf(!ascending)

    //BG READINGS -- including invalid/history records
    suspend fun findBgReadingByNSId(nsId: String): GlucoseValue? =
        database.glucoseValueDao.findByNSId(nsId)

    suspend fun getLastGlucoseValueId(): Long? =
        database.glucoseValueDao.getLastId()

    suspend fun getLastGlucoseValue(): GlucoseValue? =
        database.glucoseValueDao.getLast()

    /*
       * returns a Pair of the next entity to sync and the ID of the "update".
       * The update id might either be the entry id itself if it is a new entry - or the id
       * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
       *
       * It is a Maybe as there might be no next element.
       * */
    suspend fun getNextSyncElementGlucoseValue(id: Long): Pair<GlucoseValue, GlucoseValue>? {
        val nextIdElement = database.glucoseValueDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.glucoseValueDao.getCurrentFromHistoric(nextIdElemReferenceId) ?: return null
            historic to nextIdElement
        }
    }

    // CALIBRATION ENTRIES
    suspend fun getLastCalibrationEntryId(): Long? =
        database.calibrationEntryDao.getLastId()

    suspend fun getValidCalibrationEntriesSince(from: Long): List<CalibrationEntry> =
        database.calibrationEntryDao.getValidSince(from)

    suspend fun getAllValidCalibrationEntries(): List<CalibrationEntry> =
        database.calibrationEntryDao.getAllValid()

    suspend fun findCalibrationEntryByNSId(nsId: String): CalibrationEntry? =
        database.calibrationEntryDao.findByNSId(nsId)

    suspend fun getNextSyncElementCalibrationEntry(id: Long): Pair<CalibrationEntry, CalibrationEntry>? {
        val nextIdElement = database.calibrationEntryDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.calibrationEntryDao.getCurrentFromHistoric(nextIdElemReferenceId) ?: return null
            historic to nextIdElement
        }
    }

    // TEMP TARGETS
    suspend fun findTemporaryTargetByNSId(nsId: String): TemporaryTarget? =
        database.temporaryTargetDao.findByNSId(nsId)

    /*
       * returns a Pair of the next entity to sync and the ID of the "update".
       * The update id might either be the entry id itself if it is a new entry - or the id
       * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
       *
       * It is a Maybe as there might be no next element.
       * */
    suspend fun getNextSyncElementTemporaryTarget(id: Long): Pair<TemporaryTarget, TemporaryTarget>? {
        val nextIdElement = database.temporaryTargetDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.temporaryTargetDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    suspend fun getTemporaryTargetDataFromTime(timestamp: Long, ascending: Boolean): List<TemporaryTarget> =
        database.temporaryTargetDao.getTemporaryTargetDataFromTime(timestamp).reversedIf(!ascending)

    suspend fun getTemporaryTargetDataIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<TemporaryTarget> =
        database.temporaryTargetDao.getTemporaryTargetDataIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getTemporaryTargetActiveAt(timestamp: Long): TemporaryTarget? =
        database.temporaryTargetDao.getTemporaryTargetActiveAt(timestamp)

    suspend fun getLastTempTargetId(): Long? =
        database.temporaryTargetDao.getLastId()

    // USER ENTRY
    suspend fun getUserEntryDataFromTime(timestamp: Long): List<UserEntry> =
        database.userEntryDao.getUserEntryDataFromTime(timestamp)

    suspend fun getUserEntryFilteredDataFromTime(timestamp: Long): List<UserEntry> =
        database.userEntryDao.getUserEntryFilteredDataFromTime(UserEntry.Sources.Loop, timestamp)

    suspend fun insert(word: UserEntry) {
        database.userEntryDao.insert(word)
        changeSubject.onNext(mutableListOf(word)) // Not TraceableDao
    }

    // PROFILE SWITCH

    suspend fun findProfileSwitchByNSId(nsId: String): ProfileSwitch? =
        database.profileSwitchDao.findByNSId(nsId)

    suspend fun getNextSyncElementProfileSwitch(id: Long): Pair<ProfileSwitch, ProfileSwitch>? {
        val nextIdElement = database.profileSwitchDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.profileSwitchDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    suspend fun getProfileSwitchActiveAt(timestamp: Long): ProfileSwitch? {
        val tps = database.profileSwitchDao.getTemporaryProfileSwitchActiveAt(timestamp)
        val ps = database.profileSwitchDao.getPermanentProfileSwitchActiveAt(timestamp)
        if (tps != null && ps != null)
            return if (ps.timestamp > tps.timestamp) ps else tps
        if (ps == null) return tps
        return ps // if (tps == null)
    }

    suspend fun getPermanentProfileSwitchActiveAt(timestamp: Long): ProfileSwitch? =
        database.profileSwitchDao.getPermanentProfileSwitchActiveAt(timestamp)

    suspend fun getAllProfileSwitches(): List<ProfileSwitch> =
        database.profileSwitchDao.getAllProfileSwitches()

    suspend fun bulkMigrateProfileSwitchInsulinConfig(label: String, end: Long, peak: Long, conc: Double): Int =
        database.profileSwitchDao.bulkMigrateInsulinConfig(label, end, peak, conc)

    suspend fun getProfileSwitchesFromTime(timestamp: Long, ascending: Boolean): List<ProfileSwitch> =
        database.profileSwitchDao.getProfileSwitchDataFromTime(timestamp).reversedIf(!ascending)

    suspend fun getProfileSwitchesIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<ProfileSwitch> =
        database.profileSwitchDao.getProfileSwitchDataIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getLastProfileSwitchId(): Long? =
        database.profileSwitchDao.getLastId()

    // RUNNING MODE

    suspend fun findRunningModeByNSId(nsId: String): RunningMode? =
        database.runningModeDao.findByNSId(nsId)

    suspend fun getNextSyncElementRunningMode(id: Long): Pair<RunningMode, RunningMode>? {
        val nextIdElement = database.runningModeDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.runningModeDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    suspend fun getRunningModeActiveAt(timestamp: Long): RunningMode? {
        val trm = database.runningModeDao.getTemporaryRunningModeActiveAt(timestamp)
        val prm = database.runningModeDao.getPermanentRunningModeActiveAt(timestamp)
        if (trm != null && prm != null)
            return if (prm.timestamp > trm.timestamp) prm else trm
        if (prm == null) return trm
        return prm // if (trm == null)
    }

    suspend fun getPermanentRunningModeActiveAt(timestamp: Long): RunningMode? =
        database.runningModeDao.getPermanentRunningModeActiveAt(timestamp)

    suspend fun getAllRunningModes(): List<RunningMode> =
        database.runningModeDao.getAllRunningModes()

    suspend fun getRunningModesFromTime(timestamp: Long, ascending: Boolean): List<RunningMode> =
        database.runningModeDao.getRunningModeDataFromTime(timestamp).reversedIf(!ascending)

    suspend fun getRunningModesFromTimeToTime(startTime: Long, endTime: Long, ascending: Boolean): List<RunningMode> =
        database.runningModeDao.getRunningModeDataFromTimeToTime(startTime, endTime).reversedIf(!ascending)

    suspend fun getRunningModesIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<RunningMode> =
        database.runningModeDao.getRunningModeDataIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getLastRunningModeId(): Long? =
        database.runningModeDao.getLastId()

    // EFFECTIVE PROFILE SWITCH
    suspend fun findEffectiveProfileSwitchByNSId(nsId: String): EffectiveProfileSwitch? =
        database.effectiveProfileSwitchDao.findByNSId(nsId)

    /*
       * returns a Pair of the next entity to sync and the ID of the "update".
       * The update id might either be the entry id itself if it is a new entry - or the id
       * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
       *
       * It is a Maybe as there might be no next element.
       * */
    suspend fun getNextSyncElementEffectiveProfileSwitch(id: Long): Pair<EffectiveProfileSwitch, EffectiveProfileSwitch>? {
        val nextIdElement = database.effectiveProfileSwitchDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.effectiveProfileSwitchDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    suspend fun getOldestEffectiveProfileSwitchRecord(): EffectiveProfileSwitch? =
        database.effectiveProfileSwitchDao.getOldestEffectiveProfileSwitchRecord()

    suspend fun getEffectiveProfileSwitchActiveAt(timestamp: Long): EffectiveProfileSwitch? {
        val earliest = EffectiveProfileSwitchQueryBounds.earliestTimestampForActiveAt(timestamp)
        var id = database.effectiveProfileSwitchDao.findActiveIdAt(timestamp, earliest)
        if (id == null && earliest > 0L) {
            id = database.effectiveProfileSwitchDao.findActiveIdAtUnbounded(timestamp)
        }
        return id?.let { database.effectiveProfileSwitchDao.findById(it) }
    }

    suspend fun getEffectiveProfileSwitchesFromTime(timestamp: Long, ascending: Boolean): List<EffectiveProfileSwitch> =
        database.effectiveProfileSwitchDao.getEffectiveProfileSwitchDataFromTime(timestamp).reversedIf(!ascending)

    suspend fun getEffectiveProfileSwitchesIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<EffectiveProfileSwitch> =
        database.effectiveProfileSwitchDao.getEffectiveProfileSwitchDataIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getEffectiveProfileSwitchesFromTimeToTime(start: Long, end: Long, ascending: Boolean): List<EffectiveProfileSwitch> =
        database.effectiveProfileSwitchDao.getEffectiveProfileSwitchDataFromTimeToTime(start, end).reversedIf(!ascending)

    suspend fun getLastEffectiveProfileSwitchId(): Long? =
        database.effectiveProfileSwitchDao.getLastId()

    suspend fun getAllEffectiveProfileSwitches(): List<EffectiveProfileSwitch> =
        database.effectiveProfileSwitchDao.getAllEffectiveProfileSwitches()

    suspend fun bulkMigrateEffectiveProfileSwitchInsulinConfig(label: String, end: Long, peak: Long, conc: Double): Int =
        database.effectiveProfileSwitchDao.bulkMigrateInsulinConfig(label, end, peak, conc)

    // THERAPY EVENT
    /*
       * returns a Pair of the next entity to sync and the ID of the "update".
       * The update id might either be the entry id itself if it is a new entry - or the id
       * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
       *
       * It is a Maybe as there might be no next element.
       * */
    suspend fun findTherapyEventByNSId(nsId: String): TherapyEvent? =
        database.therapyEventDao.findByNSId(nsId)

    suspend fun getNextSyncElementTherapyEvent(id: Long): Pair<TherapyEvent, TherapyEvent>? {
        val nextIdElement = database.therapyEventDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.therapyEventDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    suspend fun getTherapyEventDataFromTime(timestamp: Long, ascending: Boolean): List<TherapyEvent> =
        database.therapyEventDao.getTherapyEventDataFromTime(timestamp).reversedIf(!ascending)

    suspend fun getTherapyEventDataFromTime(timestamp: Long, type: TherapyEvent.Type, ascending: Boolean): List<TherapyEvent> =
        database.therapyEventDao.getTherapyEventDataFromTime(timestamp, type).reversedIf(!ascending)

    suspend fun getTherapyEventDataIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<TherapyEvent> =
        database.therapyEventDao.getTherapyEventDataIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getLastTherapyRecordUpToNow(type: TherapyEvent.Type): TherapyEvent? =
        database.therapyEventDao.getLastTherapyRecord(type, System.currentTimeMillis())

    suspend fun compatGetTherapyEventDataFromToTime(from: Long, to: Long): List<TherapyEvent> =
        database.therapyEventDao.compatGetTherapyEventDataFromToTime(from, to)

    suspend fun getLastTherapyEventId(): Long? =
        database.therapyEventDao.getLastId()
    suspend fun deleteLastEventMatchingKeyword(noteKeyword: String) {
        database.therapyEventDao.deleteLastEventMatchingKeyword(noteKeyword)
    }

    // FOOD
    /*
       * returns a Pair of the next entity to sync and the ID of the "update".
       * The update id might either be the entry id itself if it is a new entry - or the id
       * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
       *
       * It is a Maybe as there might be no next element.
       * */
    suspend fun getNextSyncElementFood(id: Long): Pair<Food, Food>? {
        val nextIdElement = database.foodDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.foodDao.getCurrentFromHistoric(nextIdElemReferenceId) ?: return null
            historic to nextIdElement
        }
    }

    suspend fun getFoodData(): List<Food> {
        return database.foodDao.getFoodData()
    }

    suspend fun getLastFoodId(): Long? =
        database.foodDao.getLastId()

    // BOLUS
    suspend fun getBolusByNSId(nsId: String): Bolus? =
        database.bolusDao.getByNSId(nsId)

    /*
      * returns a Pair of the next entity to sync and the ID of the "update".
      * The update id might either be the entry id itself if it is a new entry - or the id
      * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
      *
      * It is a Maybe as there might be no next element.
      * */
    suspend fun getNextSyncElementBolus(id: Long): Pair<Bolus, Bolus>? {
        val nextIdElement = database.bolusDao.getNextModifiedOrNewAfterExclude(id, Bolus.Type.PRIMING) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.bolusDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    suspend fun getNewestBolus(): Bolus? =
        database.bolusDao.getLastBolusRecord()

    suspend fun getLastBolusRecordOfType(type: Bolus.Type): Bolus? =
        database.bolusDao.getLastBolusRecordOfType(type)

    suspend fun getOldestBolus(): Bolus? =
        database.bolusDao.getOldestBolusRecord()

    suspend fun getBoluses(): List<Bolus> =
        database.bolusDao.getAllBoluses()

    // A direct write, outside a transaction: the bolus copy must be dropped here, or the IOB code keeps reading
    // the old insulin settings from memory.
    suspend fun bulkMigrateBolusInsulinConfig(label: String, end: Long, peak: Long, conc: Double): Int =
        database.bolusDao.bulkMigrateInsulinConfig(label, end, peak, conc).also { invalidateBolusSnapshot() }

    suspend fun getBolusesDataFromTime(timestamp: Long, ascending: Boolean): List<Bolus> {
        val snapshot = bolusSnapshotCovering(timestamp)
        val rows = snapshot?.rows?.filter { it.timestamp >= timestamp } ?: database.bolusDao.getBolusesFromTime(timestamp)
        return rows.reversedIf(!ascending)
    }

    suspend fun getBolusesDataFromTimeToTime(from: Long, to: Long, ascending: Boolean): List<Bolus> {
        val snapshot = bolusSnapshotCovering(from)
        val rows = snapshot?.rows?.filter { it.timestamp in from..to } ?: database.bolusDao.getBolusesFromTime(from, to)
        return rows.reversedIf(!ascending)
    }

    suspend fun getBolusesIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<Bolus> =
        database.bolusDao.getBolusesIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getLastBolusId(): Long? =
        database.bolusDao.getLastId()
    // CARBS

    suspend fun getCarbsByNSId(nsId: String): Carbs? =
        database.carbsDao.getByNSId(nsId)

    private fun expandCarbs(carbs: Carbs): List<Carbs> =
        if (carbs.duration == 0L) {
            listOf(carbs)
        } else {
            var remainingCarbs = carbs.amount
            val ticks = (carbs.duration / 1000 / 60 / 15).coerceAtLeast(1L)
            (0 until ticks).map {
                val carbTime = carbs.timestamp + it * 15 * 60 * 1000
                val smallCarbAmount = (1.0 * remainingCarbs / (ticks - it)).roundToInt() //on last iteration (ticks-i) is 1 -> smallCarbAmount == remainingCarbs
                remainingCarbs -= smallCarbAmount.toLong()
                Carbs(timestamp = carbTime, amount = smallCarbAmount.toDouble(), duration = 0)
            }.filter { it.amount != 0.0 }
        }

    /*
      * returns a Pair of the next entity to sync and the ID of the "update".
      * The update id might either be the entry id itself if it is a new entry - or the id
      * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
      *
      * It is a Maybe as there might be no next element.
      * */
    suspend fun getNextSyncElementCarbs(id: Long): Pair<Carbs, Carbs>? {
        val nextIdElement = database.carbsDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.carbsDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    suspend fun getLastCarbs(): Carbs? =
        database.carbsDao.getLastCarbsRecord()

    suspend fun getOldestCarbs(): Carbs? =
        database.carbsDao.getOldestCarbsRecord()

    suspend fun getCarbsDataFromTime(timestamp: Long, ascending: Boolean): List<Carbs> =
        database.carbsDao.getCarbsFromTime(timestamp).reversedIf(!ascending)

    suspend fun getCarbsDataFromTimeExpanded(timestamp: Long, ascending: Boolean): List<Carbs> {
        val data = database.carbsDao.getCarbsFromTimeExpandable(timestamp)
        val expanded = data.map(::expandCarbs).flatten()
        val filtered = expanded.filter { it.timestamp >= timestamp }
        return filtered.reversedIf(!ascending)
    }

    suspend fun getCarbsDataFromTimeNotExpanded(timestamp: Long, ascending: Boolean): List<Carbs> {
        return database.carbsDao.getCarbsFromTimeExpandable(timestamp).reversedIf(!ascending)
    }

    suspend fun getCarbsDataFromTimeToTimeExpanded(from: Long, to: Long, ascending: Boolean): List<Carbs> =
        database.carbsDao.getCarbsFromTimeToTimeExpandable(from, to)
            .map(::expandCarbs).flatten()
            .filter { it.timestamp in from..to }
            .sortedBy { it.timestamp }
            .reversedIf(!ascending)

    suspend fun getCarbsIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<Carbs> =
        database.carbsDao.getCarbsIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getLastCarbsId(): Long? =
        database.carbsDao.getLastId()

    // BOLUS CALCULATOR RESULT
    suspend fun findBolusCalculatorResultByNSId(nsId: String): BolusCalculatorResult? =
        database.bolusCalculatorResultDao.findByNSId(nsId)

    /*
      * returns a Pair of the next entity to sync and the ID of the "update".
      * The update id might either be the entry id itself if it is a new entry - or the id
      * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
      *
      * It is a Maybe as there might be no next element.
      * */
    suspend fun getNextSyncElementBolusCalculatorResult(id: Long): Pair<BolusCalculatorResult, BolusCalculatorResult>? {
        val nextIdElement = database.bolusCalculatorResultDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.bolusCalculatorResultDao.getCurrentFromHistoric(nextIdElemReferenceId) ?: return null
            historic to nextIdElement
        }
    }

    suspend fun getBolusCalculatorResultsDataFromTime(timestamp: Long, ascending: Boolean): List<BolusCalculatorResult> =
        database.bolusCalculatorResultDao.getBolusCalculatorResultsFromTime(timestamp).reversedIf(!ascending)

    suspend fun getBolusCalculatorResultsIncludingInvalidFromTime(timestamp: Long, ascending: Boolean): List<BolusCalculatorResult> =
        database.bolusCalculatorResultDao.getBolusCalculatorResultsIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getLastBolusCalculatorResultId(): Long? =
        database.bolusCalculatorResultDao.getLastId()

    // DEVICE STATUS
    fun insert(deviceStatus: DeviceStatus) {
        database.deviceStatusDao.insert(deviceStatus)
        val changes = mutableListOf<DBEntry>(deviceStatus) // Not TraceableDao
        changeSubject.onNext(changes)
        _changeFlow.tryEmit(changes)
    }

    /*
       * returns a Pair of the next entity to sync and the ID of the "update".
       * The update id might either be the entry id itself if it is a new entry - or the id
       * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
       *
       * It is a Maybe as there might be no next element.
       * */

    suspend fun getNextSyncElementDeviceStatus(id: Long): DeviceStatus? =
        database.deviceStatusDao.getNextModifiedOrNewAfter(id)

    suspend fun getLastDeviceStatusId(): Long? =
        database.deviceStatusDao.getLastId()

    // TEMPORARY BASAL
    suspend fun findTemporaryBasalByNSId(nsId: String): TemporaryBasal? =
        database.temporaryBasalDao.findByNSId(nsId)

    /*
        * returns a Pair of the next entity to sync and the ID of the "update".
        * The update id might either be the entry id itself if it is a new entry - or the id
        * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
        *
        * It is a Maybe as there might be no next element.
        * */

    suspend fun getNextSyncElementTemporaryBasal(id: Long): Pair<TemporaryBasal, TemporaryBasal>? {
        val nextIdElement = database.temporaryBasalDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.temporaryBasalDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    private companion object {
        /**
         * The copy covers this much history (5 days) and is reloaded after the max age (6 hours) at the latest.
         * It must be longer than [TemporaryBasalQueryBounds.MAX_ACTIVE_LOOKBACK_MS] (4 days): an "active at T"
         * question reads from T minus 4 days, and only a copy that reaches that far back can answer it. With 5
         * days the copy answers every question up to one day before it was loaded; older ones go to SQL.
         */
        const val TBR_SNAPSHOT_WINDOW_MS = 5L * 24 * 60 * 60 * 1000
        const val TBR_SNAPSHOT_MAX_AGE_MS = 6L * 60 * 60 * 1000
    }

    /**
     * In-memory copy of the recent valid temp basals (oldest first). The "which temp basal is active at T"
     * question is asked very often (IOB/COB, graphs, notifications, watch), and every ask used to scan
     * the table. The copy is dropped at once when any temp basal is written (see
     * [invalidateSnapshotsIfNeeded]), so an answer from memory is the same as the SQL answer.
     */
    /**
     * [epoch] is the write count when the copy was loaded. The copy is used only while that count has not
     * changed, so a copy loaded while a write ran is never used after that write, even if it is stored after
     * the write dropped the previous one.
     */
    private class TbrSnapshot(val fromTimestamp: Long, val loadedAtMs: Long, val epoch: Long, val rows: List<TemporaryBasal>)

    @Volatile
    private var tbrSnapshot: TbrSnapshot? = null
    private val tbrEpoch = AtomicLong(0)

    private fun invalidateTbrSnapshot() {
        tbrEpoch.incrementAndGet()
        tbrSnapshot = null
    }

    /**
     * Drops the copies after a transaction that wrote a temp basal or a bolus.
     *
     * [changes] holds what the delegated DAOs recorded, which covers `insertNewEntry` and `updateExistingEntry`.
     * Two transactions write with a plain `insert`, which records nothing: the pump sync with a temporary id
     * (Medtrum, Medtronic, Apex). Without the check on [transaction], a bolus or temp basal written that way
     * stayed out of the copy - out of the IOB - until the next write, or for up to 6 hours.
     */
    private fun invalidateSnapshotsIfNeeded(changes: List<DBEntry>, transaction: Transaction<*>) {
        if (transaction is InsertTemporaryBasalWithTempIdTransaction || changes.any { it is TemporaryBasal }) invalidateTbrSnapshot()
        if (transaction is InsertBolusWithTempIdTransaction || changes.any { it is Bolus }) invalidateBolusSnapshot()
    }

    /** Same idea for boluses (valid, not historic, newest id first), used by the IOB and graph code. */
    private class BolusSnapshot(val fromTimestamp: Long, val loadedAtMs: Long, val epoch: Long, val rows: List<Bolus>)

    @Volatile
    private var bolusSnapshot: BolusSnapshot? = null
    private val bolusEpoch = AtomicLong(0)

    private fun invalidateBolusSnapshot() {
        bolusEpoch.incrementAndGet()
        bolusSnapshot = null
    }

    private suspend fun bolusSnapshotCovering(from: Long): BolusSnapshot? {
        val current = bolusSnapshot?.takeIf {
            it.epoch == bolusEpoch.get() && System.currentTimeMillis() - it.loadedAtMs <= TBR_SNAPSHOT_MAX_AGE_MS
        }
        val snapshot = current ?: run {
            // The epoch is read before the rows, see TbrSnapshot.
            val epoch = bolusEpoch.get()
            val now = System.currentTimeMillis()
            val start = now - TBR_SNAPSHOT_WINDOW_MS
            BolusSnapshot(start, now, epoch, database.bolusDao.getBolusesFromTime(start)).also { bolusSnapshot = it }
        }
        return snapshot.takeIf { from >= it.fromTimestamp }
    }

    /** The current temp basal copy, loaded again when a write happened since or it is too old. */
    private suspend fun currentTbrSnapshot(): TbrSnapshot {
        tbrSnapshot?.takeIf { it.epoch == tbrEpoch.get() && System.currentTimeMillis() - it.loadedAtMs <= TBR_SNAPSHOT_MAX_AGE_MS }
            ?.let { return it }
        // The epoch is read before the rows: a write that commits during the load counts after it, so this copy
        // is never used again. This read still gets an answer the SQL could have given at that moment.
        val epoch = tbrEpoch.get()
        val now = System.currentTimeMillis()
        val from = now - TBR_SNAPSHOT_WINDOW_MS
        return TbrSnapshot(from, now, epoch, database.temporaryBasalDao.getTemporaryBasalDataFromTime(from)).also { tbrSnapshot = it }
    }

    suspend fun getTemporaryBasalActiveAt(timestamp: Long): TemporaryBasal? {
        val earliest = TemporaryBasalQueryBounds.earliestTimestampForActiveAt(timestamp)
        val maxDuration = TemporaryBasalQueryBounds.MAX_REASONABLE_DURATION_MS
        val snapshot = currentTbrSnapshot()
        if (earliest >= snapshot.fromTimestamp) {
            // Same conditions as TemporaryBasalDao.getTemporaryBasalActiveAt. Rows are valid, not historic, oldest first.
            return snapshot.rows.lastOrNull {
                it.timestamp <= timestamp && it.timestamp >= earliest &&
                    it.timestamp + it.duration > timestamp && it.duration <= maxDuration
            }
        }
        return database.temporaryBasalDao.getTemporaryBasalActiveAt(
            timestamp = timestamp,
            earliestTimestamp = earliest,
            maxReasonableDurationMs = maxDuration
        )
    }

    suspend fun getTemporaryBasalsActiveBetweenTimeAndTime(from: Long, to: Long): List<TemporaryBasal> =
        database.temporaryBasalDao.getTemporaryBasalActiveBetweenTimeAndTime(from, to)

    suspend fun getTemporaryBasalsStartingFromTime(timestamp: Long, ascending: Boolean): List<TemporaryBasal> =
        database.temporaryBasalDao.getTemporaryBasalDataFromTime(timestamp).reversedIf(!ascending)

    suspend fun getTemporaryBasalsStartingFromTimeToTime(from: Long, to: Long, ascending: Boolean): List<TemporaryBasal> {
        val snapshot = currentTbrSnapshot()
        val rows = if (from >= snapshot.fromTimestamp) {
            // Same conditions as the SQL query: valid, not historic, start time inside the range, oldest first.
            snapshot.rows.filter { it.timestamp in from..to }
        } else {
            database.temporaryBasalDao.getTemporaryBasalStartingFromTimeToTime(from, to)
        }
        return rows.reversedIf(!ascending)
    }

    suspend fun getTemporaryBasalsStartingFromTimeIncludingInvalid(timestamp: Long, ascending: Boolean): List<TemporaryBasal> =
        database.temporaryBasalDao.getTemporaryBasalDataIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getOldestTemporaryBasalRecord(): TemporaryBasal? =
        database.temporaryBasalDao.getOldestRecord()

    suspend fun getLastTemporaryBasalId(): Long? =
        database.temporaryBasalDao.getLastId()

    // EXTENDED BOLUS
    suspend fun findExtendedBolusByNSId(nsId: String): ExtendedBolus? =
        database.extendedBolusDao.findByNSId(nsId)

    /*
       * returns a Pair of the next entity to sync and the ID of the "update".
       * The update id might either be the entry id itself if it is a new entry - or the id
       * of the update ("historic") entry. The sync counter should be incremented to that id if it was synced successfully.
       *
       * It is a Maybe as there might be no next element.
       * */

    suspend fun getNextSyncElementExtendedBolus(id: Long): Pair<ExtendedBolus, ExtendedBolus>? {
        val nextIdElement = database.extendedBolusDao.getNextModifiedOrNewAfter(id) ?: return null
        val nextIdElemReferenceId = nextIdElement.referenceId
        return if (nextIdElemReferenceId == null) {
            nextIdElement to nextIdElement
        } else {
            val historic = database.extendedBolusDao.getCurrentFromHistoric(nextIdElemReferenceId)
            historic?.let { it to nextIdElement }
        }
    }

    suspend fun getExtendedBolusActiveAt(timestamp: Long): ExtendedBolus? =
        database.extendedBolusDao.getExtendedBolusActiveAt(timestamp)

    suspend fun getExtendedBolusesStartingFromTime(timestamp: Long, ascending: Boolean): List<ExtendedBolus> =
        database.extendedBolusDao.getExtendedBolusesStartingFromTime(timestamp).reversedIf(!ascending)

    suspend fun getExtendedBolusesStartingFromTimeToTime(start: Long, end: Long, ascending: Boolean): List<ExtendedBolus> =
        database.extendedBolusDao.getExtendedBolusDataFromTimeToTime(start, end).reversedIf(!ascending)

    suspend fun getExtendedBolusStartingFromTimeIncludingInvalid(timestamp: Long, ascending: Boolean): List<ExtendedBolus> =
        database.extendedBolusDao.getExtendedBolusDataIncludingInvalidFromTime(timestamp).reversedIf(!ascending)

    suspend fun getOldestExtendedBolusRecord(): ExtendedBolus? =
        database.extendedBolusDao.getOldestRecord()

    suspend fun getLastExtendedBolusId(): Long? =
        database.extendedBolusDao.getLastId()

    // TotalDailyDose
    suspend fun getLastTotalDailyDoses(count: Int, ascending: Boolean): List<TotalDailyDose> =
        database.totalDailyDoseDao.getLastTotalDailyDoses(count).reversedIf(!ascending)

    suspend fun getCalculatedTotalDailyDose(timestamp: Long): TotalDailyDose? =
        database.totalDailyDoseDao.findByTimestamp(timestamp, InterfaceIDs.PumpType.CACHE)

// HEART RATES

    suspend fun getHeartRatesFromTime(timeMillis: Long): List<HeartRate> =
        database.heartRateDao.getFromTime(timeMillis)

    suspend fun getHeartRatesFromTimeToTime(startMillis: Long, endMillis: Long): List<HeartRate> =
        database.heartRateDao.getFromTimeToTime(startMillis, endMillis)

    suspend fun getStepsCountFromTime(timeMillis: Long): List<StepsCount> =
        database.stepsCountDao.getFromTime(timeMillis)

    suspend fun getStepsCountFromTimeToTime(startMillis: Long, endMillis: Long): List<StepsCount> =
        database.stepsCountDao.getFromTimeToTime(startMillis, endMillis)

    suspend fun getLastStepsCountFromTimeToTime(startMillis: Long, endMillis: Long): StepsCount? =
        database.stepsCountDao.getLastStepsCountFromTimeToTime(startMillis, endMillis)

    suspend fun collectNewEntriesSince(since: Long, until: Long, limit: Int, offset: Int) = NewEntries(
        apsResults = database.apsResultDao.getNewEntriesSince(since, until, limit, offset),
        bolusCalculatorResults = database.bolusCalculatorResultDao.getNewEntriesSince(since, until, limit, offset),
        boluses = database.bolusDao.getNewEntriesSince(since, until, limit, offset),
        carbs = database.carbsDao.getNewEntriesSince(since, until, limit, offset),
        effectiveProfileSwitches = database.effectiveProfileSwitchDao.getNewEntriesSince(since, until, limit, offset),
        extendedBoluses = database.extendedBolusDao.getNewEntriesSince(since, until, limit, offset),
        glucoseValues = database.glucoseValueDao.getNewEntriesSince(since, until, limit, offset),
        runningModes = database.runningModeDao.getNewEntriesSince(since, until, limit, offset),
        preferencesChanges = database.preferenceChangeDao.getNewEntriesSince(since, until, limit, offset),
        profileSwitches = database.profileSwitchDao.getNewEntriesSince(since, until, limit, offset),
        temporaryBasals = database.temporaryBasalDao.getNewEntriesSince(since, until, limit, offset),
        temporaryTarget = database.temporaryTargetDao.getNewEntriesSince(since, until, limit, offset),
        therapyEvents = database.therapyEventDao.getNewEntriesSince(since, until, limit, offset),
        totalDailyDoses = database.totalDailyDoseDao.getNewEntriesSince(since, until, limit, offset),
        versionChanges = database.versionChangeDao.getNewEntriesSince(since, until, limit, offset),
        heartRates = database.heartRateDao.getNewEntriesSince(since, until, limit, offset),
        stepsCount = database.stepsCountDao.getNewEntriesSince(since, until, limit, offset),
    )

    suspend fun getApsResultCloseTo(timestamp: Long): APSResult? =
        database.apsResultDao.getApsResult(timestamp - 5 * 60 * 1000, timestamp)

    suspend fun getApsResults(start: Long, end: Long): List<APSResult> =
        database.apsResultDao.getApsResults(start, end)

    suspend fun getGlucoseValueByPumpIdAndSource(source: String, pumpId: Long): GlucoseValue? =
        database.glucoseValueDao.getGlucoseValueByPumpIdAndSource(source, pumpId)

    suspend fun getGlucoseValuesByPumpIdRange(source: String, startPumpId: Long, endPumpId: Long): List<GlucoseValue> =
        database.glucoseValueDao.getGlucoseValuesByPumpIdRange(source, startPumpId, endPumpId)

    /**
     * Clean up Flow scope and release resources
     *
     * NOTE: AppRepository is a singleton that typically lives for the entire app lifecycle.
     * This method is primarily useful for:
     * - Unit/integration tests to properly clean up between test runs
     * - Explicit app shutdown scenarios
     *
     * The scope will be automatically cleaned up when the app process terminates.
     *   @Test
     *   fun myTest() {
     *       repository.use { repo ->
     *           // test code
     *       } // automatically calls close()
     *   }
     */
    override fun close() {
        repositoryScope.cancel()
    }

    fun <T> Iterable<T>.reversedIf(reverse: Boolean): List<T> = if (reverse) this.reversed() else this.toList()
}