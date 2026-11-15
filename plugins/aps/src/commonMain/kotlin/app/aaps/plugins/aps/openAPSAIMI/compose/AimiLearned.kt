package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlDashboardSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbDashboardSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdProfileInsulinBounds
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ProfileInsulin
import kotlin.math.roundToInt

/**
 * What the loop knows now, for the "What AIMI learned" page. The plugin fills it; it only reads.
 *
 * @param profileDiaHours DIA of the insulin in the running profile, or null without a profile
 * @param profilePeakMin peak of the insulin in the running profile, in minutes
 * @param isfInUseMgdl the sensitivity the last loop run used (`variable_sens`), or null
 * @param profileIsfMgdl the ISF of the running profile, or null without a profile
 * @param smbModel the SMB learner, or null when it is not set up yet
 * @param basalModel the basal learner, or null when it is not set up yet
 */
internal data class AimiLearnedLive(
    val profileDiaHours: Double? = null,
    val profilePeakMin: Double? = null,
    val isfInUseMgdl: Double? = null,
    val profileIsfMgdl: Double? = null,
    val smbModel: AimiSmbDashboardSnapshot? = null,
    val basalModel: BasalMlDashboardSnapshot? = null,
)

/** The range the PK/PD learner may move the DIA and the peak in. */
internal data class AimiLearnerRange(
    val diaMinH: Double,
    val diaMaxH: Double,
    val peakMinMin: Double,
    val peakMinMax: Double,
)

/** What a machine learning model does for the loop now. */
internal enum class AimiModelState {

    /** The learner is not set up yet, so nothing is known. */
    UNKNOWN,

    /** No model file: the loop runs without this model. */
    NO_MODEL,

    /** A model file exists, but no model is loaded: the loop runs without it. */
    FILE_NOT_LOADED,

    /** A model file exists. The basal learner does not say whether the loop uses it. */
    FILE_PRESENT,

    /** A model is loaded and the loop may use it. */
    IN_USE,

    /** Training failed several times and is paused for a while. */
    PAUSED,
}

/** The pure part of the "What AIMI learned" page, so a test can check it. */
internal object AimiLearned {

    /** The learner range around [profile]: the same bounds the PK/PD learner uses. */
    fun learnerRange(profile: ProfileInsulin): AimiLearnerRange =
        AimiLearnerRange(
            diaMinH = PkpdProfileInsulinBounds.diaMinH(profile),
            diaMaxH = PkpdProfileInsulinBounds.diaMaxH(profile),
            peakMinMin = PkpdProfileInsulinBounds.peakMinMin(profile),
            peakMinMax = PkpdProfileInsulinBounds.peakMinMax(profile),
        )

    /**
     * The ISF in use as a whole percent of the profile ISF, or null when one of them is not a
     * usable positive number. Above 100 means AIMI treats you as more sensitive than the profile.
     */
    fun isfPercentOfProfile(inUseMgdl: Double?, profileMgdl: Double?): Int? {
        if (inUseMgdl == null || profileMgdl == null) return null
        if (!inUseMgdl.isFinite() || !profileMgdl.isFinite() || inUseMgdl <= 0.0 || profileMgdl <= 0.0) return null
        return (inUseMgdl / profileMgdl * 100.0).roundToInt()
    }

    fun smbModelState(snapshot: AimiSmbDashboardSnapshot?): AimiModelState = when {
        snapshot == null            -> AimiModelState.UNKNOWN
        snapshot.circuitOpen        -> AimiModelState.PAUSED
        snapshot.modelLoaded        -> AimiModelState.IN_USE
        snapshot.modelFileExists    -> AimiModelState.FILE_NOT_LOADED
        else                        -> AimiModelState.NO_MODEL
    }

    fun basalModelState(snapshot: BasalMlDashboardSnapshot?): AimiModelState = when {
        snapshot == null            -> AimiModelState.UNKNOWN
        snapshot.circuitOpen        -> AimiModelState.PAUSED
        snapshot.modelFileExists    -> AimiModelState.FILE_PRESENT
        else                        -> AimiModelState.NO_MODEL
    }
}
