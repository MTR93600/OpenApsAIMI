package app.aaps.core.keys

/**
 * Stored names of the AIMI settings deleted in steps S3, S3b and D1 of the AIMI settings plan, because
 * nothing read them: no dosing path, no screen, no Advisor, TPO or tuning proposal.
 *
 * The keys are gone from the key enums. Their old values can still sit in the store of a phone that
 * ran an older build, and in a backup file. [removeStored] removes them: the preference migrations
 * (`PreferenceMigrations`) call it on Android start-up and on every import, and the AIMI plugin start
 * calls it on both platforms, so an iPhone that imported a backup before this build loses them too.
 * An old backup writes none of them and a stored value does not stay forever. The five context keys
 * are the main reason: four of them could hold an API key.
 *
 * Step S3b (owner decision 2026-11-15) added four dead keys of the protected families. One of them,
 * the old due date in milliseconds, is first copied into the due date text when that text is empty,
 * so no pregnancy loses its date ([removeStored]).
 *
 * Step D1 (owner decision 2026-11-15) added the xDrip one minute switch: the KMP port lost its reader,
 * and the owner keeps all readings for every xDrip user, so the switch had no effect.
 *
 * A test checks that no current key uses one of these names, so a name is never reused by mistake.
 */
object AimiRetiredSettings {

    /** The old due date, in milliseconds. Nothing ever wrote it from code; only a backup can hold it. */
    const val PREGNANCY_DUE_DATE_MS = "oa_aimi_pregnancy_due_date_ms"

    /** The due date the gestation code reads (`AimiStringKey.PregnancyDueDateString`), as `YYYY-MM-DD`. */
    const val PREGNANCY_DUE_DATE_TEXT = "aimi_pregnancy_due_date_string"

    /** 2000-01-01T00:00:00Z. An older value is not a due date, so it is not copied. */
    private const val EARLIEST_DUE_DATE_MS = 946_684_800_000L

    private const val DAY_MS = 86_400_000L

    val storageNames: List<String> = listOf(
        // Basal kicker and zero resume (no reader since the basal rewrite).
        "OApsAIMIKickerStep",
        "OApsAIMIKickerMinUph",
        "OApsAIMIKickerStartMin",
        "OApsAIMIKickerMaxMin",
        "OApsAIMIZeroResumeFrac",
        "OApsAIMIZeroResumeMin",
        "OApsAIMIZeroResumeMax",
        "OApsAIMIAntiStallBias",
        "OApsAIMIDeltaPosRelease",
        "OApsAIMIPlateauBandAbs",
        "OApsAIMIR2Confident",
        "key_enable_basal",
        // Night growth: the learner derives these values, the keys were never read.
        "key_oaps_aimi_ngr_min_rise_slope",
        "key_oaps_aimi_ngr_smb_multiplier",
        "key_oaps_aimi_ngr_basal_multiplier",
        "key_oaps_aimi_ngr_max_smb_clamp",
        "key_oaps_aimi_ngr_min_duration",
        "key_oaps_aimi_ngr_min_eventual_over_target",
        "key_oaps_aimi_ngr_decay_minutes",
        // Meal and Autodrive.
        "key_oaps_aimi_FCL_factor",
        "key_mindeviation_autodrive_mode",
        "key_oaps_aimi_autodriveTarget",
        "key_oaps_aimi_autodriveBG",
        "key_use_aimi_autodrive_v3_enhanced_gater",
        // Other.
        "key_use_AimiForceLimits",
        "key_oaps_aimi_logsize",
        "key_oaps_aimi_mode_state",
        // Context module: the provider and four API keys, replaced by the AI Advisor keys.
        "aimi_context_llm_provider",
        "aimi_context_llm_openai_key",
        "aimi_context_llm_gemini_key",
        "aimi_context_llm_deepseek_key",
        "aimi_context_llm_claude_key",
        // Step S3b, protected families (owner decision 2026-11-15).
        "aimi_physio_debug_logs",
        "aimi_physio_llm_provider",
        "aimi_endo_suppression",
        PREGNANCY_DUE_DATE_MS,
        // Step D1 (owner decision 2026-11-15): the xDrip one minute switch, no reader since the port.
        "key_use_Aimi_xdripOM",
    )

    /**
     * Removes every name of [storageNames] that is stored. Guarded by its data alone: it removes only
     * names that are present, so running it again does nothing.
     *
     * Before that, a stored old due date ([PREGNANCY_DUE_DATE_MS]) is copied into the due date text
     * ([PREGNANCY_DUE_DATE_TEXT]) when the text is empty. A text the person typed is kept.
     *
     * @param contains true when the store holds a value for the name
     * @param read the stored value of the name: a number on a phone, text in a backup file, or null
     * @param putString writes a text value
     * @param remove removes the value of the name from the store
     * @return the names it removed
     */
    fun removeStored(
        contains: (String) -> Boolean,
        read: (String) -> Any?,
        putString: (String, String) -> Unit,
        remove: (String) -> Unit,
    ): List<String> {
        if (contains(PREGNANCY_DUE_DATE_MS) && (read(PREGNANCY_DUE_DATE_TEXT) as? String).isNullOrEmpty()) {
            dueDateMsOf(read(PREGNANCY_DUE_DATE_MS))?.let(::dueDateText)?.let { putString(PREGNANCY_DUE_DATE_TEXT, it) }
        }
        return storageNames.filter(contains).onEach(remove)
    }

    /**
     * The calendar day of [epochMs] as `YYYY-MM-DD`, the format of the due date text, or null when
     * the value is not a due date (before the year 2000).
     *
     * The day is the UTC day nearest to [epochMs]. The value was a day, not a moment: a date picker
     * stores the start of a day, in UTC or in the local time zone. Rounding to the nearest UTC day
     * gives the same day for both, for every time zone from UTC-12 to UTC+12. Pure arithmetic, so no
     * time zone and no date library are needed here.
     */
    fun dueDateText(epochMs: Long): String? {
        if (epochMs < EARLIEST_DUE_DATE_MS) return null
        val days = (epochMs + DAY_MS / 2).floorDiv(DAY_MS)
        // Days since 1970-01-01 to a civil date (Howard Hinnant's algorithm).
        val z = days + 719_468
        val era = z.floorDiv(146_097)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val mp = (5 * dayOfYear + 2) / 153
        val day = dayOfYear - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yearOfEra + era * 400 + if (month <= 2) 1 else 0
        return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    private fun dueDateMsOf(value: Any?): Long? = when (value) {
        is Long   -> value
        is Int    -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else      -> null
    }
}
