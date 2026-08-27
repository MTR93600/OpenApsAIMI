package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import android.content.Context
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
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
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
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
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val storageHelper: AimiStorageHelper,
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
            val file = storageHelper.getAimiFile(csvFileName)
            if (!file.exists()) {
                null
            } else {
                val stamps = file.useLines { lines ->
                    lines.drop(1).mapNotNull { it.split(",").getOrNull(IDX_TIMESTAMP)?.toLongOrNull() }
                        .filter { it > 0L }
                        .toList()
                }
                if (stamps.isEmpty()) null else stamps.min() to stamps.max()
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
        aapsLogger.error(LTag.APS, "Backfill: glucose history unavailable — ${e.message}")
        emptyList()
    }

    private fun processPendingLinesLocked(readings: List<Pair<Long, Double>>): Int {
        val originalFile = storageHelper.getAimiFile(csvFileName)
        if (!originalFile.exists()) return 0

        val lines = try {
            originalFile.readLines()
        } catch (e: Exception) {
            aapsLogger.error(LTag.APS, "Backfiller Error reading CSV: ${e.message}")
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
                runCatching { originalFile.writeText(AutodriveDatasetSchema.HEADER + "\n") }
                    .onFailure { aapsLogger.error(LTag.APS, "Backfill: header rewrite failed — ${it.message}") }
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
            aapsLogger.warn(LTag.APS, "Backfill: dropped $malformedCount malformed rows (fewer than ${IDX_HYPER + 1} columns)")
        }

        // Schema migration, before labelling, so migrated rows are re-labelled in the same pass.
        for (row in parsedLines) {
            if (migrateToCurrentSchema(row)) migratedCount++
        }
        if (migratedCount > 0) {
            aapsLogger.info(LTag.APS, "Backfill: migrated $migratedCount rows to schema v$CURRENT_VERSION")
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
        val retentionCutoff = System.currentTimeMillis() - RETENTION_MILLIS
        val retained = parsedLines.filter { it.timestamp <= 0L || it.timestamp >= retentionCutoff }
        val prunedCount = parsedLines.size - retained.size
        if (prunedCount > 0) {
            aapsLogger.info(LTag.APS, "Backfill: pruned $prunedCount rows older than $RETENTION_DAYS days")
        }

        val mustRewrite = modifiedCount > 0 || prunedCount > 0 || migratedCount > 0 ||
            malformedCount > 0 || headerWasStale
        if (mustRewrite) {
            val tmpFile = storageHelper.getAimiFile(tmpCsvFileName)
            try {
                tmpFile.bufferedWriter().use { writer ->
                    writer.write(AutodriveDatasetSchema.HEADER)
                    writer.newLine()
                    retained.forEach { row ->
                        writer.write(row.cols.joinToString(","))
                        writer.newLine()
                    }
                }

                // Swap atomique
                val swapped = if (tmpFile.renameTo(originalFile)) {
                    true
                } else {
                    // Fallback rename manuel sur certains Androids
                    tmpFile.copyTo(originalFile, overwrite = true)
                    tmpFile.delete()
                    true
                }
                if (swapped) {
                    aapsLogger.info(
                        LTag.APS,
                        "Backfill: $modifiedCount rows labelled, $migratedCount migrated, $prunedCount pruned" +
                            if (headerWasStale) ", header rewritten" else "",
                    )
                }
            } catch (e: Exception) {
                // The rewrite is what persists the work. Reporting the labelled count after a failed
                // rewrite tells the caller N rows were backfilled when none reached the disk.
                aapsLogger.error(LTag.APS, "Backfiller Error writing CSV: ${e.message}")
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
            val file = storageHelper.getAimiFile(csvFileName)
            if (!file.exists()) return@withDataset false

            try {
                var validCount = 0
                file.useLines { lines ->
                    lines.drop(1).forEach { line ->
                        val cols = line.split(",")
                        if (cols.size > IDX_FUTURE_BG && cols[IDX_FUTURE_BG].isNotBlank()) {
                            validCount++
                        }
                        if (validCount >= minimumValidLines) return@useLines
                    }
                }
                validCount >= minimumValidLines
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "Gate Error: ${e.message}")
                false
            }
        }

    private data class ParsedRow(
        val cols: MutableList<String>,
        val timestamp: Long,
        val bg: Double,
    )
}
