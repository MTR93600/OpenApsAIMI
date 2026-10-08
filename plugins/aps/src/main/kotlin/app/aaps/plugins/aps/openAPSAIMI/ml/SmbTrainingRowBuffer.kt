package app.aaps.plugins.aps.openAPSAIMI.ml

import java.util.Locale

/**
 * Small in-memory queue that holds SMB training rows until their outcome can be observed.
 *
 * Why it exists: the SMB model is trained on the `smbGiven` column, which is the dose that was
 * really delivered. That column says nothing about **where** the dose came from, and nothing about
 * what happened next. Measured on 26 days, about four units in ten leave with the model output at
 * zero, so the model learns to copy the safety net roughly one time in two.
 *
 * This class does not change the label, the training filter or the model input vector. It only
 * carries five origin fields plus one delayed outcome field to the end of each CSV row, so the
 * question can be answered offline.
 *
 * Two things happen with a delay, so a row cannot be written at the moment it is built:
 *  - the origin fields are only complete at the end of the tick, when the binding trace is built;
 *  - the realised glucose is only known about half an hour later.
 *
 * The row therefore waits here and is written once its outcome window has passed. A row whose
 * outcome never arrives inside the window is still written, but with an **empty** outcome field:
 * an empty field must never be read as a value, and a stale reading must never be written as if it
 * were the outcome. This mirrors `BasalNeuralLearner.fillRealisedOutcomes`, which leaves a sample
 * unrealised rather than labelling it with an old value.
 */
internal class SmbTrainingRowBuffer(
    private val maxPendingRows: Int = MAX_PENDING_ROWS,
) {

    /**
     * One CSV row waiting for its label, its origin stamp and its outcome.
     *
     * The row is stored in two halves because the label sits between them and is bound late. See
     * [deliveredUnits]. [valuesHead] is everything up to but not including the label, [valuesTail]
     * everything after it; neither carries the trailing newline.
     */
    internal data class PendingRow(
        val timestampMs: Long,
        val valuesHead: String,
        val valuesTail: String,
        /**
         * The dose really delivered on this tick, in U, stamped at the end of the tick.
         *
         * This is the training label. It used to be written when the row was built, inside the SMB
         * executor, which is BEFORE every floor, cap and guard has run — so the column named "the
         * dose that was really delivered" held the value before capping.
         *
         * `null` means the dose of that tick is UNKNOWN, and the row is then rendered with an empty
         * label that the trainer drops. It does NOT mean "nothing was given": a tick that ran and
         * chose to give nothing is stamped with `0.0`, because "here nothing was needed" is half of
         * what the model has to learn. Writing an empty cell for those ticks hid about one row in
         * eleven from the trainer.
         */
        var deliveredUnits: Double? = null,
        var smbModelU: Double? = null,
        var smbFloorU: Double? = null,
        var bindingStage: String? = null,
        var originOwner: String? = null,
        /** Solver request before the control barrier, in U. `null` means unknown, never zero. */
        var smbMpcRequestedU: Double? = null,
        var originStamped: Boolean = false,
        var bgRealisedAfter: Double? = null,
    )

    private val pending = ArrayDeque<PendingRow>()

    /** Adds the row of the current tick. Oldest rows are dropped if the queue grows past its cap. */
    @Synchronized
    fun enqueue(timestampMs: Long, valuesHead: String, valuesTail: String) {
        pending.addLast(PendingRow(timestampMs = timestampMs, valuesHead = valuesHead, valuesTail = valuesTail))
        while (pending.size > maxPendingRows) pending.removeFirst()
    }

    /**
     * Writes the training label on the row queued by the tick [tickKey].
     *
     * Called once per tick from the tick tail, where the delivered dose is final. Matching is on the
     * tick key for the same reason [stampOrigin] does it: a tick that queued no row of its own must
     * label nothing rather than label someone else's row.
     */
    @Synchronized
    fun stampDeliveredUnits(tickKey: Long, deliveredUnits: Double?) {
        val row = pending.lastOrNull { it.timestampMs == tickKey && it.deliveredUnits == null } ?: return
        row.deliveredUnits = deliveredUnits
    }

    /**
     * Stamps the five origin fields on the row queued by the tick [tickKey].
     *
     * Called at the end of the tick, from the single point every export path goes through. The match
     * is on the tick key and not on "the newest unstamped row": some ticks reach the export without
     * having queued a row, and some queue a row and then leave before the export, so the newest
     * unstamped row can belong to another tick. Writing one tick's origin on another tick's row
     * would be worse than writing nothing. A tick with no row of its own stamps nothing.
     */
    @Synchronized
    fun stampOrigin(
        tickKey: Long,
        smbModelU: Double?,
        smbFloorU: Double?,
        bindingStage: String?,
        originOwner: String?,
        smbMpcRequestedU: Double?,
    ) {
        val row = pending.lastOrNull { it.timestampMs == tickKey && !it.originStamped } ?: return
        row.smbModelU = smbModelU
        row.smbFloorU = smbFloorU
        row.bindingStage = bindingStage
        row.originOwner = originOwner
        row.smbMpcRequestedU = smbMpcRequestedU
        row.originStamped = true
    }

    /**
     * Fills [PendingRow.bgRealisedAfter] on rows that have reached the outcome horizon.
     *
     * `observedBg` is the glucose measured now, which is the realised outcome of a tick recorded
     * about [OUTCOME_HORIZON_MS] ago. Rows outside the acceptance window are left alone.
     */
    @Synchronized
    fun fillRealisedOutcomes(nowMs: Long, observedBg: Double) {
        if (!observedBg.isFinite() || observedBg <= 0.0) return
        pending.forEach { row ->
            if (row.bgRealisedAfter != null) return@forEach
            val age = nowMs - row.timestampMs
            if (age in OUTCOME_HORIZON_MIN_MS..OUTCOME_HORIZON_MAX_MS) {
                row.bgRealisedAfter = observedBg
            }
        }
    }

    /**
     * Removes and renders every row whose outcome window has closed, oldest first.
     *
     * A row leaves with the outcome it got inside the window, or with an empty outcome field if it
     * got none. Rows still inside their window stay here.
     */
    @Synchronized
    fun drainWritableRows(nowMs: Long): List<String> {
        val out = mutableListOf<String>()
        while (true) {
            val head = pending.firstOrNull() ?: break
            if (nowMs - head.timestampMs <= OUTCOME_HORIZON_MAX_MS) break
            pending.removeFirst()
            out.add(render(head))
        }
        return out
    }

    /** Number of rows still waiting. Used by tests and by diagnostics. */
    @Synchronized
    fun pendingCount(): Int = pending.size

    /** Renders one row: the head, the late-bound label, the tail, then the six new fields. */
    internal fun render(row: PendingRow): String =
        row.valuesHead +
            "," + formatUnits(row.deliveredUnits) +
            "," + row.valuesTail +
            "," + formatUnits(row.smbModelU) +
            "," + formatUnits(row.smbFloorU) +
            "," + formatText(row.bindingStage) +
            "," + formatText(row.originOwner) +
            "," + formatGlucose(row.bgRealisedAfter) +
            "," + formatUnits(row.smbMpcRequestedU)

    private fun formatUnits(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.4f", it) } ?: ""

    private fun formatGlucose(value: Double?): String =
        value?.takeIf { it.isFinite() }?.let { String.format(Locale.US, "%.1f", it) } ?: ""

    /** Keeps the CSV grid intact: a separator or a line break inside a name would shift columns. */
    private fun formatText(value: String?): String =
        value?.replace(',', ';')?.replace('\n', ' ')?.replace('\r', ' ') ?: ""

    companion object {

        /** Column names added at the end of the `oapsaimiML2_records.csv` header, in write order. */
        val ADDED_COLUMN_NAMES: List<String> = listOf(
            "smbModelU",
            "smbFloorU",
            "smbBindingStage",
            "smbOriginOwner",
            "bgRealisedAfter",
            // Added last on purpose: a new column at the end leaves every existing column index
            // untouched, so an older parser reads the same cells it always did.
            "smbMpcRequestedU",
        )

        /** Delay after which a tick's outcome is considered observable. Same value as the basal head. */
        const val OUTCOME_HORIZON_MS = 30L * 60_000
        const val OUTCOME_HORIZON_MIN_MS = 20L * 60_000
        const val OUTCOME_HORIZON_MAX_MS = 45L * 60_000

        /** About four hours of five-minute ticks. A safety cap, not a working size. */
        const val MAX_PENDING_ROWS = 64
    }
}
