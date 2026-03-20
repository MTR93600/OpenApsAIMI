package app.aaps.plugins.aps.openAPSAIMI.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class AimiLongKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    // 🤰 Pregnancy Due Date (Timestamp MS)
    PregnancyDueDate("oa_aimi_pregnancy_due_date_ms", 0L),

    // 💉 Last Manual Prebolus Timestamp (Timestamp MS)
    LastPrebolusTime("oa_aimi_last_prebolus_time_ms", 0L)
}
