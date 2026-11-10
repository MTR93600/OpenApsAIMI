package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import androidx.annotation.StringRes

/** One BG sample taken from the AIMI decision log. */
data class AssistantSample(
    val timeMs: Long,
    val bgMgdl: Double,
    val iob: Double,
    val isSmb: Boolean,
)

/** TIR / low / mean for a time window. */
data class AssistantStats(
    val count: Int,
    val tirPercent: Int,
    val lowPercent: Double,
    val meanMmol: Double,
)

/** A low BG episode (below 3.5 mmol/L) and what happened before it. */
data class LowEpisode(
    val timeMs: Long,
    val minMmol: Double,
    val smbCount2h: Int,
    val smbMinutesBetween: Double,
    val iobStart: Double,
)

/** BG went above 8.0 mmol/L and the first SMB came late. */
data class LateEpisode(
    val timeMs: Long,
    val delayMinutes: Int,
)

/** A setting the assistant is allowed to change. */
data class AssistantSetting(
    /** English name, as in the AIMI settings of the app. */
    val englishName: String,
    val kind: Kind,
    val prefKey: String,
    val default: Double,
    val min: Double,
    val max: Double,
    val step: Double,
    @StringRes val whatItDoes: Int,
    /** Text about other settings that change the same value. */
    @StringRes val related: Int,
    /** True if the TPO module may overwrite this value for a while. */
    val tpoWritable: Boolean = false,
) {
    enum class Kind { DOUBLE, INT }
}

/** One proposal. The reason is built from evidence numbers and a Russian text. */
data class AssistantProposal(
    val setting: AssistantSetting,
    val current: Double,
    val proposed: Double,
    @StringRes val reasonRes: Int,
    val reasonArgs: List<Any>,
)

/** One saved change of a setting. */
data class ChangeRecord(
    val prefKey: String,
    val from: Double,
    val to: Double,
    val timeMs: Long,
    val before: AssistantStats?,
)
