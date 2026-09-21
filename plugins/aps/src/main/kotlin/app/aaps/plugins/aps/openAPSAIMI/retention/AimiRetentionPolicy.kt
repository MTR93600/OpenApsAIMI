package app.aaps.plugins.aps.openAPSAIMI.retention

/** What the janitor does with one file. */
internal enum class AimiRetentionOp {

    /** Keep the lines newer than `hotDays`, archive the rest. Needs `timestampKey`. */
    TRIM_TIME,

    /** Keep the last `hotLines` lines, archive the rest. For files with no epoch field. */
    TRIM_LINES,

    /** Delete the whole file once it is older than `hotDays`. For generated copies only. */
    DROP,

    /** Archive the whole file, then remove it. For files nothing writes any more. */
    RETIRE,
}

/**
 * One line per managed file.
 *
 * @param hotDays clear-text window for TRIM_TIME and DROP, in days.
 * @param hotLines clear-text window for TRIM_LINES, in lines.
 * @param timestampKey the JSON key holding epoch milliseconds, for TRIM_TIME only.
 * @param archiveMonths how long the compressed history is kept.
 * @param hasHeader true when the first line is a header that must stay at the top of the file.
 * @param hardCapBytes the size at which the append guard rotates the file aside.
 */
internal data class AimiRetentionRule(
    val fileName: String,
    val op: AimiRetentionOp,
    val hotDays: Int = 0,
    val hotLines: Int = 0,
    val timestampKey: String? = null,
    val archiveMonths: Int = 0,
    val hasHeader: Boolean = false,
    val hardCapBytes: Long = DEFAULT_HARD_CAP_BYTES,
) {

    companion object {

        const val DEFAULT_HARD_CAP_BYTES = 256L * 1024 * 1024
    }
}

/**
 * The retention table, and nothing else. Keeping it as plain data means the policy can be read and
 * reviewed without reading the engine, and the engine can be tested without the real file names.
 *
 * Sizes and rates come from the device measurement of 2026-09-19; see the design document.
 */
internal object AimiRetentionPolicy {

    /** A managed file untouched for this long is treated as RETIRE on the next pass. */
    const val STALE_DAYS = 90

    /** Suffix the append guard uses when it rotates a file aside at its hard cap. */
    const val OVERFLOW_SUFFIX = ".overflow"

    val RULES: List<AimiRetentionRule> = listOf(
        // 2.10 GB measured, about 80% of the directory. Every in-app reader uses 24 h; 7 days is
        // seven times that, and the external viewer reads the archives.
        AimiRetentionRule(
            fileName = "AIMI_Decisions.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 12,
            hardCapBytes = 1024L * 1024 * 1024,
        ),
        // 186 MB, about 1 MB/day. Kept at 31 days because the in-app Hormonitor viewer reads it by
        // day: cutting it to 7 days would save about 25 MB and force a change in HormonitorReader.
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_event_stream_v1.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 31,
            timestampKey = "timestamp",
            archiveMonths = 12,
        ),
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_shadow_contributions_v1.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "timestamp",
            archiveMonths = 3,
        ),
        // Writes about 7 lines per tick. Its lines carry "wall_ms", not "timestamp".
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_loop_blackbox_v1.jsonl",
            op = AimiRetentionOp.TRIM_TIME,
            hotDays = 7,
            timestampKey = "wall_ms",
            archiveMonths = 3,
        ),
        // No epoch field: only "generated_at" as an ISO string and "day_local" as a date.
        // Measured at about one line a day, so 400 lines is roughly a year.
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_daily_outcomes_v1.jsonl",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 400,
            archiveMonths = 24,
        ),
        AimiRetentionRule(
            fileName = "AIMI_HORMONITOR_dataset_qa_v1.jsonl",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 400,
            archiveMonths = 24,
        ),
        // Column 0 is a date string and column 1 is epoch MINUTES, so it is trimmed by line count.
        // 290 to 580 lines a day, so 4 000 lines is about a week. PkPdCsvLogger writes no header.
        AimiRetentionRule(
            fileName = "oapsaimi_pkpd_records.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 4_000,
            archiveMonths = 3,
        ),
        // Column 0 is dateUtil.dateAndTimeString(...), which follows the device locale.
        AimiRetentionRule(
            fileName = "oapsaimi2_records.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 2_000,
            archiveMonths = 3,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "oapsaimi_wcycle.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 2_000,
            archiveMonths = 3,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "aimi_reactivity_analysis.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 400,
            archiveMonths = 3,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "comparison_aimi_smb.csv",
            op = AimiRetentionOp.TRIM_LINES,
            hotLines = 2_000,
            archiveMonths = 3,
            hasHeader = true,
        ),
        // Nothing has written these since 2024 or 2025. About 50 MB between them.
        AimiRetentionRule(
            fileName = "oapsaimiHB_records.csv",
            op = AimiRetentionOp.RETIRE,
            archiveMonths = 12,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "oapsaimi_records.csv",
            op = AimiRetentionOp.RETIRE,
            archiveMonths = 12,
            hasHeader = true,
        ),
        AimiRetentionRule(
            fileName = "bg.csv",
            op = AimiRetentionOp.RETIRE,
            archiveMonths = 12,
            hasHeader = true,
        ),
    )

    /** Files matched by name rather than listed one by one. Deleted outright, never archived. */
    val DROP_GLOBS: List<Regex> = listOf(Regex("""^backup_\d{8}_\d{6}\.csv$"""))

    /** How long a dropped file is kept before deletion, in days. */
    const val DROP_AFTER_DAYS = 7

    fun ruleFor(fileName: String): AimiRetentionRule? = RULES.firstOrNull { it.fileName == fileName }
}
