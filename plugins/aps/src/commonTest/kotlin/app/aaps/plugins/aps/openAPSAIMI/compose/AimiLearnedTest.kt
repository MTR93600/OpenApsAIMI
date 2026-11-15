package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlDashboardSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbDashboardSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdProfileInsulinBounds
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ProfileInsulin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Step S2: what the read only "What AIMI learned" page computes. */
class AimiLearnedTest {

    @Test
    fun `the learner range is the range the PK PD learner uses`() {
        val profile = ProfileInsulin(diaHours = 6.0, peakMinutes = 60.0)
        val range = AimiLearned.learnerRange(profile)
        assertEquals(PkpdProfileInsulinBounds.diaMinH(profile), range.diaMinH, 1e-9)
        assertEquals(PkpdProfileInsulinBounds.diaMaxH(profile), range.diaMaxH, 1e-9)
        assertEquals(PkpdProfileInsulinBounds.peakMinMin(profile), range.peakMinMin, 1e-9)
        assertEquals(PkpdProfileInsulinBounds.peakMinMax(profile), range.peakMinMax, 1e-9)
        // A fast insulin: the peak floor of the insulin editor holds.
        assertEquals(35.0, AimiLearned.learnerRange(ProfileInsulin(5.0, 28.0)).peakMinMin, 1e-9)
    }

    @Test
    fun `the ISF in use is shown as a percent of the profile ISF`() {
        assertEquals(120, AimiLearned.isfPercentOfProfile(inUseMgdl = 60.0, profileMgdl = 50.0))
        assertEquals(100, AimiLearned.isfPercentOfProfile(inUseMgdl = 50.0, profileMgdl = 50.0))
        assertNull(AimiLearned.isfPercentOfProfile(inUseMgdl = null, profileMgdl = 50.0))
        assertNull(AimiLearned.isfPercentOfProfile(inUseMgdl = 50.0, profileMgdl = 0.0))
        assertNull(AimiLearned.isfPercentOfProfile(inUseMgdl = Double.NaN, profileMgdl = 50.0))
    }

    private fun smb(loaded: Boolean, file: Boolean, circuitOpen: Boolean = false) = AimiSmbDashboardSnapshot(
        lastAttempt = null,
        waiting = null,
        lastAttemptAtMs = 0L,
        lastTrainedAtMs = 0L,
        circuitOpen = circuitOpen,
        modelLoaded = loaded,
        modelFileExists = file,
        modelFileModifiedAtMs = null,
    )

    @Test
    fun `the SMB model state says what the loop can use`() {
        assertEquals(AimiModelState.UNKNOWN, AimiLearned.smbModelState(null))
        assertEquals(AimiModelState.NO_MODEL, AimiLearned.smbModelState(smb(loaded = false, file = false)))
        assertEquals(AimiModelState.FILE_NOT_LOADED, AimiLearned.smbModelState(smb(loaded = false, file = true)))
        assertEquals(AimiModelState.IN_USE, AimiLearned.smbModelState(smb(loaded = true, file = true)))
        assertEquals(AimiModelState.PAUSED, AimiLearned.smbModelState(smb(loaded = true, file = true, circuitOpen = true)))
    }

    @Test
    fun `the basal model state says whether a model file exists`() {
        assertEquals(AimiModelState.UNKNOWN, AimiLearned.basalModelState(null))
        val none = BasalMlDashboardSnapshot(lastTrainedAtMs = 0L, sampleCount = 0, circuitOpen = false, modelFileExists = false, modelFileModifiedAtMs = null)
        assertEquals(AimiModelState.NO_MODEL, AimiLearned.basalModelState(none))
        assertEquals(AimiModelState.FILE_PRESENT, AimiLearned.basalModelState(none.copy(modelFileExists = true)))
        assertEquals(AimiModelState.PAUSED, AimiLearned.basalModelState(none.copy(modelFileExists = true, circuitOpen = true)))
    }
}
