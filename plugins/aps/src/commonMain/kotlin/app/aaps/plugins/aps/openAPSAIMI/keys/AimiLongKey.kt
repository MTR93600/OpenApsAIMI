package app.aaps.plugins.aps.openAPSAIMI.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class AimiLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    PregnancyDueDate("oa_aimi_pregnancy_due_date_ms", 0L),
    LastPrebolusTime("oa_aimi_last_prebolus_time_ms", 0L),
    LastLegacyPrebolusTime("oa_aimi_last_legacy_prebolus_time_ms", 0L),
    PendingLegacyPrebolusUnitMilli("oa_aimi_pending_legacy_prebolus_unit_milli", 0L),
    PendingLegacyPrebolusExpiry("oa_aimi_pending_legacy_prebolus_expiry_ms", 0L)
}
