package app.aaps.core.keys

/**
 * Stored names of the AIMI settings deleted in step S3 of the AIMI settings plan, because nothing
 * read them: no dosing path, no screen, no Advisor, TPO or tuning proposal.
 *
 * The keys are gone from the key enums. Their old values can still sit in the store of a phone that
 * ran an older build, and in a backup file. [removeStored] removes them: the preference migrations
 * (`PreferenceMigrations`) call it on Android start-up and on every import, and the AIMI plugin start
 * calls it on both platforms, so an iPhone that imported a backup before this build loses them too.
 * An old backup writes none of them and a stored value does not stay forever. The five context keys
 * are the main reason: four of them could hold an API key.
 *
 * Not here on purpose (they wait for the owner, see `docs/aimi-settings/PLAN.md`): the dead keys of the
 * protected families and the xDrip one minute switch.
 *
 * A test checks that no current key uses one of these names, so a name is never reused by mistake.
 */
object AimiRetiredSettings {

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
    )

    /**
     * Removes every name of [storageNames] that is stored. Guarded by its data alone: it removes only
     * names that are present, so running it again does nothing.
     *
     * @param contains true when the store holds a value for the name
     * @param remove removes the value of the name from the store
     * @return the names it removed
     */
    fun removeStored(contains: (String) -> Boolean, remove: (String) -> Unit): List<String> =
        storageNames.filter(contains).onEach(remove)
}
