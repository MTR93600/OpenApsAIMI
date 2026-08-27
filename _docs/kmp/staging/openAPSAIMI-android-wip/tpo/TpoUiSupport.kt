package app.aaps.plugins.aps.openAPSAIMI.tpo

import androidx.annotation.StringRes
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningChange
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningContextApplySupport
import kotlin.math.max

internal data class TpoActiveSessionUi(
    @StringRes val packTitleResId: Int,
    val tierLabel: String,
    val remainingMinutes: Int,
    val changedKeyCount: Int,
    val status: TpoSessionStatus,
    val deltaPreviewLines: List<String>,
    val extraChangeCount: Int,
)

internal object TpoUiSupport {

    fun buildActiveSessionUi(session: TpoSessionDocument?, nowMs: Long): TpoActiveSessionUi? {
        if (session == null) return null
        if (session.status != TpoSessionStatus.ACTIVE && session.status != TpoSessionStatus.PENDING_LLM) {
            return null
        }
        val remainingMs = max(0L, session.expiresAtMs - nowMs)
        val preview = buildPreviewLines(session)
        val maxPreview = 4
        return TpoActiveSessionUi(
            packTitleResId = packTitleResId(session.packId),
            tierLabel = session.tier.name,
            remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt(),
            changedKeyCount = session.overlay.size,
            status = session.status,
            deltaPreviewLines = preview.take(maxPreview),
            extraChangeCount = max(0, preview.size - maxPreview),
        )
    }

    private fun buildPreviewLines(session: TpoSessionDocument): List<String> =
        session.overlay.mapNotNull { (key, newValue) ->
            val oldValue = session.baseline[key] ?: return@mapNotNull null
            val preferenceKey = TpoPreferenceKeys.fromKey(key) ?: return@mapNotNull null
            TuningContextApplySupport.formatChangeLine(
                TuningChange(
                    key = preferenceKey,
                    labelKey = key,
                    oldValue = oldValue,
                    newValue = newValue,
                    reason = "TPO",
                    tier = session.tier,
                ),
            )
        }

    @StringRes
    private fun packTitleResId(packId: TpoPackId): Int =
        when (packId) {
            TpoPackId.POST_HYPO_RECOVERY -> R.string.aimi_tpo_pack_post_hypo
            TpoPackId.POOR_SLEEP_WINDOW -> R.string.aimi_tpo_pack_poor_sleep
            TpoPackId.EXHAUSTED_RECOVERY -> R.string.aimi_tpo_pack_exhausted
        }
}
