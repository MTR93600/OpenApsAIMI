package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

/**
 * Column layout of `autodrive_dataset.csv`, shared by everything that reads or writes it.
 *
 * The indices used to be copied into [AutodriveDataBackfiller] and [AutodriveNeuralTrainer] as
 * private fields, and the header string lived only in [AutodriveDataLake]. Three copies of one
 * layout is how a file ends up with an 18-column header over 19-column rows.
 *
 * ## Versions
 *
 * A row carries the schema version it was written with, in the last column. The version says how
 * much of the row can be trusted, which is not the same question as how many columns it has:
 *
 * - **[VERSION_LEGACY_UNLABELLED] (18 columns, no version field).** Written before the outcome
 *   labels came from CGM history. Those rows were labelled from the rows of this very CSV, and a
 *   row is only written when Autodrive engages — a hypoglycaemia is precisely what makes it
 *   disengage. The old pass gave up on the first coverage gap and left `Hypo_Occurred = 0`.
 *   Measured on the production corpus of 17 068 rows: **73.2 %** of the rows that carry a label have
 *   an outcome window the CSV does not cover continuously, so their `0` means "not looked at", not
 *   "no hypo". They also have no `Engaged` field, and the trainer reads a missing field as `1.0` —
 *   so "engaged" and "labelled by the broken method" were the **same** column in disguise, and any
 *   weight fitted to `Engaged` was a weight fitted to the labelling bug.
 *   The backfiller blanks their outcome columns so they are re-derived from CGM, or stay unlabelled
 *   and out of training.
 * - **[VERSION_CGM_LABELLED] (19 columns, no version field).** Written once labels came from CGM
 *   and `Engaged` was recorded. Trustworthy; only needs the version stamp appended.
 * - **[CURRENT_VERSION] (20 columns).** Current layout.
 */
internal object AutodriveDatasetSchema {

    /** Outcome labels derived from the CSV itself, and no `Engaged` column. Not trustworthy. */
    const val VERSION_LEGACY_UNLABELLED = 0

    /** Outcome labels derived from CGM history, `Engaged` present, version column not yet added. */
    const val VERSION_CGM_LABELLED = 1

    /** Layout written by this build. Bump whenever older rows cannot be trusted as they stand. */
    const val CURRENT_VERSION = 2

    const val IDX_TIMESTAMP = 0
    const val IDX_DATE = 1
    const val IDX_BG = 2
    const val IDX_BG_VELOCITY = 3
    const val IDX_IOB = 4
    const val IDX_COB = 5
    const val IDX_ESTIMATED_SI = 6
    const val IDX_ESTIMATED_RA = 7
    const val IDX_WEIGHT = 8
    const val IDX_PHYSIO_MASK = 9
    const val IDX_MPC_RAW_SMB = 10
    const val IDX_MPC_RAW_TBR = 11
    const val IDX_CBF_SAFE_SMB = 12
    const val IDX_CBF_SAFE_TBR = 13
    const val IDX_CBF_INTERVENTION = 14
    const val IDX_FUTURE_BG = 15
    const val IDX_HYPO = 16
    const val IDX_HYPER = 17
    const val IDX_ENGAGED = 18
    const val IDX_SCHEMA_VERSION = 19

    /** Number of columns in [CURRENT_VERSION]. */
    const val COLUMN_COUNT = 20

    /** Number of columns in [VERSION_LEGACY_UNLABELLED]. */
    const val LEGACY_UNLABELLED_COLUMN_COUNT = 18

    /** Number of columns in [VERSION_CGM_LABELLED]. */
    const val CGM_LABELLED_COLUMN_COUNT = 19

    val COLUMN_NAMES: List<String> = listOf(
        "Timestamp_Epoch", "Date",
        "BG_Current", "BG_Velocity", "IOB_Net", "COB", "Estimated_SI", "Estimated_Ra", "Patient_Weight",
        "Physio_Mask",
        "MPC_Raw_SMB", "MPC_Raw_TBR",
        "CBF_Safe_SMB", "CBF_Safe_TBR", "CBF_Intervention",
        "Future_BG_45m", "Hypo_Occurred", "Hyper_Occurred",
        "Engaged",
        "Schema_Version",
    )

    /** Canonical header line, without the trailing newline. */
    val HEADER: String = COLUMN_NAMES.joinToString(",")

    /**
     * Version of a parsed row.
     *
     * A row written before the version column existed carries no version, so the column count is
     * the only evidence there is — and it is sufficient, because the two older layouts differ by
     * exactly the `Engaged` field.
     */
    fun versionOf(cols: List<String>): Int = when {
        cols.size > IDX_SCHEMA_VERSION -> cols[IDX_SCHEMA_VERSION].trim().toIntOrNull() ?: VERSION_LEGACY_UNLABELLED
        cols.size >= CGM_LABELLED_COLUMN_COUNT -> VERSION_CGM_LABELLED
        else                                   -> VERSION_LEGACY_UNLABELLED
    }
}
