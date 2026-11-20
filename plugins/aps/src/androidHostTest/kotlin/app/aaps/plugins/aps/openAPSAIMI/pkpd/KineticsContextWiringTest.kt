package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AimiTickWorkingState
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.plugins.aps.openAPSAIMI.advisor.gestation.GestationalAutopilot
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStateId
import app.aaps.plugins.aps.openAPSAIMI.patient.CausalStatePosterior
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditor
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.plugins.aps.openAPSAIMI.validation.PumpCapabilityValidator
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * The wiring that carries the causal posterior of one tick to the kinetics of the next, at the level
 * of `DetermineBasalaimiSMB2`: the tick stores the posterior of its patient state, and the kinetics
 * the plugin resolves before the next tick get it.
 */
class KineticsContextWiringTest {

    private val minute = 60_000L
    private val t0 = 1_800_000_000_000L

    private val stress = CausalStatePosterior(
        stressResistanceProb = 1.0,
        dominant = CausalStateId.STRESS_RESISTANCE,
        dominantConfidence = 1.0,
        learningQuality = 1.0,
    )

    /** PK/PD switch read by the mock. The causal shift doses only with PK/PD on (P1d). */
    private var pkpdEnabled = true

    // The governor switches are on, as their defaults: switched off they also drop the causal
    // shift (P1b). PK/PD is on unless a test turns it off: with PK/PD off there is no causal shift
    // (P1d). The governor bounds are fixed constants (DIA 4-24 h), not keys.
    private val preferences: Preferences = mock(Preferences::class.java).also { prefs ->
        whenever(prefs.get(BooleanKey.OApsAIMIDiaGovernorEnabled)).thenReturn(true)
        whenever(prefs.get(BooleanKey.OApsAIMIPeakGovernorEnabled)).thenReturn(true)
        whenever(prefs.get(BooleanKey.OApsAIMIPkpdEnabled)).thenAnswer { pkpdEnabled }
    }

    private fun newTick(): DetermineBasalaimiSMB2 {
        val dateUtil = mock(DateUtil::class.java)
        whenever(dateUtil.now()).thenReturn(t0)
        val behavior = mock(AimiBehaviorProfileSource::class.java)
        whenever(behavior.read(any())).thenReturn(AimiBehaviorRuntimeProfile(2, 2, 2, 2, AimiAutonomyMode.Observation))
        val tick = DetermineBasalaimiSMB2(
            profileUtil = mock(ProfileUtil::class.java),
            fabricPrivacy = mock(FabricPrivacy::class.java),
            preferences = preferences,
            pkPdLearnedState = mock(PkPdLearnedState::class.java),
            gestationalAutopilot = mock(GestationalAutopilot::class.java),
            auditorOrchestrator = mock(AimiAuditor::class.java),
            behaviorProfileSource = behavior,
            uiInteraction = mock(UiInteraction::class.java),
            notificationManager = mock(NotificationManager::class.java),
            wCycleFacade = mock(WCycleFacade::class.java),
            wCyclePreferences = mock(WCyclePreferences::class.java),
            wCycleLearner = mock(WCycleLearner::class.java),
            pumpCapabilityValidator = mock(PumpCapabilityValidator::class.java),
            dynamicBasalController = mock(DynamicBasalController::class.java),
            autodriveEngine = mock(AutodriveEngine::class.java),
            rh = mock(TextResolver::class.java),
        )
        val dateField = tick.javaClass.getDeclaredField("dateUtil")
        dateField.isAccessible = true
        dateField.set(tick, dateUtil)
        val loggerField = tick.javaClass.getDeclaredField("aapsLogger")
        loggerField.isAccessible = true
        loggerField.set(tick, mock(AAPSLogger::class.java))
        return tick
    }

    /** The patient state that the tick being run leaves behind, as `determine_basal` does. */
    private fun DetermineBasalaimiSMB2.setPatientState(posterior: CausalStatePosterior?, nowMs: Long) {
        val field = javaClass.getDeclaredField("work")
        field.isAccessible = true
        val work = field.get(this) as AimiTickWorkingState
        work.lastPatientState = posterior?.let { PatientStateSnapshot(timestampMs = nowMs, causalPosterior = it) }
    }

    /** Runs one tick through the same wrapper `determine_basal` uses. */
    private fun DetermineBasalaimiSMB2.tick(nowMs: Long, posterior: CausalStatePosterior?) =
        runTickKeepingKineticsContext(nowMs) { setPatientState(posterior, nowMs) }

    /** What the plugin does before the next tick: resolve the dosing kinetics. */
    private fun DetermineBasalaimiSMB2.dosingKinetics(nowMs: Long): Pair<CausalStatePosterior?, Double> {
        var seen: CausalStatePosterior? = null
        val view = dosingView(nowMs) { seen = it }
        return seen to view.effective.diaHours
    }

    private fun DetermineBasalaimiSMB2.dosingView(nowMs: Long, onPosterior: (CausalStatePosterior?) -> Unit = {}) =
        resolveDosingKinetics(nowMs) { posterior ->
            onPosterior(posterior)
            InsulinKineticsAuthority.ResolveInput(
                accountingIobArray = emptyArray<IobTotal>(),
                profileDiaHours = 5.0,
                profilePeakMinutes = 75,
                pkpdRuntime = null,
                causalPosterior = posterior,
                preferences = preferences,
                learningDiagnostics = PkpdLearningDiagnostics.from(
                    causalStatePosterior = posterior,
                    allowLearning = false,
                    exerciseFlag = false,
                    iobU = 0.0,
                    carbsActiveG = 0.0,
                    bg = 120.0,
                    deltaMgDlPer5 = 0.0,
                ),
            )
        }

    @Test
    fun `the dosing path limits the dia step over time`() {
        val tick = newTick()
        // First resolution: no context, no limit, 5.0 h doses.
        assertThat(tick.dosingView(t0).effective.diaHours).isWithin(1e-9).of(5.0)
        tick.tick(t0, stress)
        val view = tick.dosingView(t0 + 5 * minute)
        // Stress asks for 5.55 h (6.0 h blended with the profile DIA as learned DIA); 5 minutes after
        // the last tick the limit lets only 0.25 h through.
        assertThat(view.targetEffective.diaHours).isWithin(1e-9).of(5.55)
        assertThat(view.effective.diaHours).isWithin(1e-9).of(5.25)
    }

    @Test
    fun `the next tick doses with the posterior of the previous tick`() {
        val tick = newTick()
        tick.tick(t0, stress)
        val (posterior, diaHours) = tick.dosingKinetics(t0 + 5 * minute)
        assertThat(posterior).isEqualTo(stress)
        // Stress at full confidence: (5.0 h + 1.0 h) x 0.55 + 5.0 h x 0.45.
        assertThat(diaHours).isWithin(1e-9).of(5.55)
    }

    @Test
    fun `with pkpd off the next tick sees the posterior but doses the profile dia`() {
        pkpdEnabled = false
        val tick = newTick()
        tick.tick(t0, stress)
        val view = tick.dosingView(t0 + 5 * minute)
        val (posterior, diaHours) = tick.dosingKinetics(t0 + 10 * minute)
        // The memory still carries the stress posterior, but with PK/PD off it does not shift the DIA.
        assertThat(posterior).isEqualTo(stress)
        assertThat(diaHours).isWithin(1e-9).of(5.0)
        assertThat(view.targetEffective.diaHours).isWithin(1e-9).of(5.0)
        assertThat(view.contextReason).startsWith(InsulinKineticsAuthority.CONTEXT_PKPD_OFF_PREFIX)
    }

    @Test
    fun `the first tick doses with no context`() {
        val (posterior, diaHours) = newTick().dosingKinetics(t0)
        assertThat(posterior).isNull()
        assertThat(diaHours).isWithin(1e-9).of(5.0)
    }

    @Test
    fun `a tick with no patient state clears the context`() {
        val tick = newTick()
        tick.tick(t0, stress)
        tick.tick(t0 + 5 * minute, null)
        assertThat(tick.dosingKinetics(t0 + 10 * minute).first).isNull()
    }

    @Test
    fun `a posterior older than 15 minutes is not used`() {
        val tick = newTick()
        tick.tick(t0, stress)
        assertThat(tick.dosingKinetics(t0 + 15 * minute + 1).first).isNull()
    }

    @Test
    fun `a tick that throws leaves no context`() {
        val tick = newTick()
        tick.tick(t0, stress)
        assertThrows<IllegalStateException> {
            tick.runTickKeepingKineticsContext(t0 + 5 * minute) {
                tick.setPatientState(stress, t0 + 5 * minute)
                error("tick failed")
            }
        }
        assertThat(tick.dosingKinetics(t0 + 10 * minute).first).isNull()
    }
}
