package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.CGM_LABELLED_COLUMN_COUNT
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.COLUMN_COUNT
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.CURRENT_VERSION
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_BG
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_FUTURE_BG
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_HYPER
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_HYPO
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_SCHEMA_VERSION
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.IDX_TIMESTAMP
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDatasetSchema.VERSION_LEGACY_UNLABELLED
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlin.concurrent.Volatile
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * 🧹 Autodrive Data Backfiller
 *
 * Reopens `autodrive_dataset.csv` and fills the columns left empty at collection time
 * (`Future_BG_45m`, `Hypo_Occurred`, `Hyper_Occurred`) from the CGM history, prunes rows past the
 * retention window, and migrates rows written by older builds to the current schema.
 */
@SingleIn(AppScope::class)
class AutodriveDataBackfiller @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val storage: AimiStorage,
    private val persistenceLayer: PersistenceLayer,
) {
    companion object {
        @Volatile
        var instance: AutodriveDataBackfiller? = null
            internal set
    }

    init {
        // Scheduling lives in AimiMlTrainingScheduler, which the plugin starts and stops. Enqueuing
        // from a constructor fires at DI graph construction, in no defined order, and the constraints
        // used here (charging + device idle) almost never coincide on a real phone — the same reason
        // the basal trainer had to drop them.
        instance = this
    }

    private val csvFileName = AutodriveDataLake.FILE_NAME
    private val tmpCsvFileName = "autodrive_dataset_tmp.csv"

    /** Fenêtre d'historique conservée dans le CSV d'entraînement. */
    private val RETENTION_DAYS = 60L
    private val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60L * 60L * 1000L
    private val MIN_MILLIS_FOR_FUTURE = 45 * 60 * 1000L // 45 minutes
    private val MAX_MILLIS_FOR_HYPO = 60 * 60 * 1000L   // 60 minutes de fenêtre

    /**
     * Read-modify-rename over the whole dataset; held under [AutodriveDatasetLock] as one transaction.
     *
     * Outcome labels come from the **CGM history**, not from the rows of this CSV. Rows are only
     * written on ticks where Autodrive engaged, and a hypoglycaemia is precisely what makes Autodrive
     * disengage — so labelling from the CSV censored the positive class exactly where it matters, and
     * silently wrote `0`.
     *
     * @return the number of rows whose outcome columns were filled and persisted.
     */
    suspend fun processPendingLines(): Int {
        val readings = loadGlucoseWindow()
        return AutodriveDatasetLock.withDataset { processPendingLinesLocked(readings) }
    }

    /**
     * CGM readings covering every pending row's outcome window, read once rather than per row.
     *
     * The file scan that establishes the time span is taken under the dataset lock; the database
     * query deliberately is not — it is slow, it does not touch the file, and holding the file lock
     * across it would put a database round-trip in front of the APS thread's row append.
     */
    private suspend fun loadGlucoseWindow(): List<Pair<Long, Double>> = try {
        val span = AutodriveDatasetLock.withDataset {
            val path = storage.file(csvFileName)
            if (!storage.exists(path)) {
                null
            } else {
                val stamps = mutableListOf<Long>()
                var isFirstLine = true
                val readWholeFile = storage.forEachLine(path) { line ->
                    if (isFirstLine) {
                        isFirstLine = false
                    } else {
                        line.split(",").getOrNull(IDX_TIMESTAMP)?.toLongOrNull()
                            ?.takeIf { it > 0L }
                            ?.let { stamps.add(it) }
                    }
                }
                // A read that stopped early gives a shorter span, so the CGM window would be too
                // narrow and some rows would stay unlabelled without anyone knowing why. Before the
                // port the same failure threw and was caught below, which loaded no history at all;
                // this keeps that, and the rows are labelled on the next pass either way.
                if (!readWholeFile || stamps.isEmpty()) null else stamps.min() to stamps.max()
            }
        }
        if (span == null) {
            emptyList()
        } else {
            persistenceLayer
                .getBgReadingsDataFromTimeToTime(span.first, span.second + MAX_MILLIS_FOR_HYPO, true)
                .map { it.timestamp to it.value }
        }
    } catch (e: Exception) {
        aapsLogger.error(LTag.AIMI, "Backfill: glucose history unavailable — ${e.message}")
        emptyList()
    }

    private fun processPendingLinesLocked(readings: List<Pair<Long, Double>>): Int {
        val originalPath = storage.file(csvFileName)
        if (!storage.exists(originalPath)) return 0

        // `readLines` answers an empty list both when the file cannot be read and when it holds
        // nothing at all. Either way there is no header row to work from, so the pass stops here.
        // Before the port the unreadable case logged and returned 0, while the truly empty case fell
        // through and threw on `lines[0]`; the two cannot be told apart through the port, and
        // returning 0 is the safe half of that pair.
        val lines = storage.readLines(originalPath)
        if (lines.isEmpty()) {
            aapsLogger.error(LTag.AIMI, "Backfiller Error reading CSV: no lines in ${storage.displayPath(originalPath)}")
            return 0
        }

        // The header is rewritten from the schema, never echoed back. Existing installs carry an
        // 18-column header over rows that have had 19 for a while: the old pass copied the header
        // verbatim, so the mismatch survived every rewrite.
        val headerWasStale = lines[0].trim() != AutodriveDatasetSchema.HEADER

        if (lines.size <= 1) {
            // Header only. Still worth fixing, otherwise the next appended rows sit under a header
            // that names the wrong columns.
            if (headerWasStale) {
                if (!storage.writeText(originalPath, AutodriveDatasetSchema.HEADER + "\n")) {
                    aapsLogger.error(LTag.AIMI, "Backfill: header rewrite failed")
                }
            }
            return 0
        }
        var modifiedCount = 0
        var migratedCount = 0
        var malformedCount = 0

        val parsedLines = lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val cols = line.split(",")
            if (cols.size > IDX_HYPER) {
                val timestamp = cols[IDX_TIMESTAMP].toLongOrNull() ?: 0L
                val bg = cols[IDX_BG].toDoubleOrNull() ?: 0.0
                ParsedRow(cols.toMutableList(), timestamp, bg)
            } else {
                malformedCount++
                null
            }
        }
        if (malformedCount > 0) {
            aapsLogger.warn(LTag.AIMI, "Backfill: dropped $malformedCount malformed rows (fewer than ${IDX_HYPER + 1} columns)")
        }

        // Schema migration, before labelling, so migrated rows are re-labelled in the same pass.
        for (row in parsedLines) {
            if (migrateToCurrentSchema(row)) migratedCount++
        }
        if (migratedCount > 0) {
            aapsLogger.info(LTag.AIMI, "Backfill: migrated $migratedCount rows to schema v$CURRENT_VERSION")
        }

        for (currentRow in parsedLines) {
            val timestampNow = currentRow.timestamp
            val futureBgStr = currentRow.cols[IDX_FUTURE_BG]

            // Si la ligne n'a pas encore son 'Reward' validé
            if (futureBgStr.isBlank() && timestampNow > 0) {

                val targetMillis = timestampNow + MIN_MILLIS_FOR_FUTURE
                val maxWindowMillis = timestampNow + MAX_MILLIS_FOR_HYPO

                var futureBgVal: Double? = null
                var hypoOccurred = false
                var hyperOccurred = false

                // Étiquetage depuis la glycémie réelle : continue par construction, donc un
                // décrochage d'Autodrive ne masque plus une hypo.
                val windowReadings = readings.filter { (ts, _) -> ts in (timestampNow + 1)..maxWindowMillis }
                if (windowReadings.isNotEmpty()) {
                    hypoOccurred = windowReadings.any { (_, bgV) -> bgV > 0.0 && bgV < 70.0 }
                    // Première mesure au-delà de +45 min, mais **dans** la fenêtre : sans borne haute,
                    // une valeur trois heures plus tard était estampillée comme le résultat à 45 min.
                    windowReadings.firstOrNull { (ts, _) -> ts >= targetMillis }?.let { (_, bgV) ->
                        futureBgVal = bgV
                        if (bgV >= 180.0) hyperOccurred = true
                    }
                }

                if (futureBgVal != null) {
                    currentRow.cols[IDX_FUTURE_BG] = futureBgVal.toString()
                    currentRow.cols[IDX_HYPO] = if (hypoOccurred) "1" else "0"
                    currentRow.cols[IDX_HYPER] = if (hyperOccurred) "1" else "0"
                    modifiedCount++
                }
            }
        }

        // Rétention glissante. Le fichier n'avait aucun plafond : il est relu **intégralement** par
        // cette passe toutes les 6 h, par la porte de volume et par l'entraîneur. Enregistrer chaque
        // tick au lieu des seuls ticks engagés multiplie sa croissance par ~3,5, donc le plafond doit
        // exister avant. La purge se fait ici parce que cette passe réécrit le fichier de toute façon.
        val retentionCutoff = aimiWallClockMs() - RETENTION_MILLIS
        val retained = parsedLines.filter { it.timestamp <= 0L || it.timestamp >= retentionCutoff }
        val prunedCount = parsedLines.size - retained.size
        if (prunedCount > 0) {
            aapsLogger.info(LTag.AIMI, "Backfill: pruned $prunedCount rows older than $RETENTION_DAYS days")
        }

        val mustRewrite = modifiedCount > 0 || prunedCount > 0 || migratedCount > 0 ||
            malformedCount > 0 || headerWasStale
        if (mustRewrite) {
            // Header first, then the kept rows, produced one at a time. The corpus is already the
            // biggest file AIMI writes, so it is never joined into one string: `rewriteLines` builds
            // the new file in the scratch copy and swaps it in, which is what the hand written
            // temp-file-then-rename here did before.
            val newLines = sequenceOf(AutodriveDatasetSchema.HEADER) +
                retained.asSequence().map { row -> row.cols.joinToString(",") }
            val swapped = storage.rewriteLines(originalPath, storage.file(tmpCsvFileName), newLines)
            if (swapped) {
                aapsLogger.info(
                    LTag.AIMI,
                    "Backfill: $modifiedCount rows labelled, $migratedCount migrated, $prunedCount pruned" +
                        if (headerWasStale) ", header rewritten" else "",
                )
            } else {
                // The rewrite is what persists the work. Reporting the labelled count after a failed
                // rewrite tells the caller N rows were backfilled when none reached the disk.
                aapsLogger.error(LTag.AIMI, "Backfiller Error writing CSV: rewrite of ${storage.displayPath(originalPath)} failed")
                return 0
            }
        }

        return modifiedCount
    }

    /**
     * Brings one row up to [CURRENT_VERSION] in place.
     *
     * A row written before the outcome labels came from CGM keeps a `Hypo_Occurred` that means "the
     * old pass gave up", not "no hypo happened" — and it has no `Engaged` field, which the trainer
     * reads as `1.0`. The two are the same set of rows, so the `Engaged` feature would carry the
     * labelling bug. Blanking the outcome columns sends them back through the CGM labelling above;
     * a row too old for the CGM history simply stays unlabelled and out of training, which is the
     * honest outcome.
     *
     * @return true when the row was changed.
     */
    private fun migrateToCurrentSchema(row: ParsedRow): Boolean {
        val version = AutodriveDatasetSchema.versionOf(row.cols)
        if (version >= CURRENT_VERSION && row.cols.size == COLUMN_COUNT) return false

        if (version == VERSION_LEGACY_UNLABELLED) {
            row.cols[IDX_FUTURE_BG] = ""
            row.cols[IDX_HYPO] = ""
            row.cols[IDX_HYPER] = ""
        }
        // Pre-Engaged rows were only written on engaged ticks, so 1 is the truthful value for them.
        while (row.cols.size < CGM_LABELLED_COLUMN_COUNT) row.cols.add("1")
        if (row.cols.size < COLUMN_COUNT) {
            row.cols.add(CURRENT_VERSION.toString())
        } else {
            row.cols[IDX_SCHEMA_VERSION] = CURRENT_VERSION.toString()
        }
        // Anything beyond the known layout is a row from a future build; leave the extra fields alone.
        return true
    }

    /**
     * Phase 8 : Data Quality Gate (Sécurité Volumétrique)
     *
     * Counts rows whose outcome is known (`Future_BG_45m` filled) to decide whether training can run
     * without overfitting. Read under [AutodriveDatasetLock]: a count taken across the backfiller's
     * rename sees whichever half of the transaction is on disk.
     */
    fun isDatasetReadyForTraining(minimumValidLines: Int = 2880): Boolean =
        AutodriveDatasetLock.withDataset {
            val path = storage.file(csvFileName)
            if (!storage.exists(path)) return@withDataset false

            var validCount = 0
            var isFirstLine = true
            // `forEachLine` cannot be stopped from the outside, so the counting stops instead of the
            // reading. The answer is the same either way - `validCount` only ever grows and the test
            // below is the same test - and the one caller is the neural trainer worker, which reads
            // the whole file straight afterwards anyway.
            val readWholeFile = storage.forEachLine(path) { line ->
                if (isFirstLine) {
                    isFirstLine = false
                } else if (validCount < minimumValidLines) {
                    val cols = line.split(",")
                    if (cols.size > IDX_FUTURE_BG && cols[IDX_FUTURE_BG].isNotBlank()) {
                        validCount++
                    }
                }
            }
            if (!readWholeFile) {
                aapsLogger.error(LTag.AIMI, "Gate Error: cannot read ${storage.displayPath(path)}")
                return@withDataset false
            }
            validCount >= minimumValidLines
        }

    private data class ParsedRow(
        val cols: MutableList<String>,
        val timestamp: Long,
        val bg: Double,
    )
}
