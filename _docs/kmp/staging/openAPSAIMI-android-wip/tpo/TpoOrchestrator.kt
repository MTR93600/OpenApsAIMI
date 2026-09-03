package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.ports.AimiTpo
import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.advisor.data.AdvisorHistoryRepository
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.compose.authorityRank
import app.aaps.plugins.aps.openAPSAIMI.compose.readAimiControlCenterDraft
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

@SingleIn(AppScope::class)
class TpoOrchestrator @Inject constructor(
    private val preferences: Preferences,
    private val storageHelper: AimiStorageHelper,
    private val aiCoachingService: AiCoachingService,
    private val sp: SP,
    private val aapsLogger: AAPSLogger,
    private val tpoNotificationManager: TpoNotificationManager,
    private val context: Context,
) : AimiTpo {
    private val persistence = TpoPersistence(storageHelper)
    private val sessionManager = TpoSessionManager(persistence)
    private val llmValidator = TpoLlmValidator(context, sp, aiCoachingService, aapsLogger)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val historyRepo by lazy { AdvisorHistoryRepository(context) }

    @Volatile
    private var prefsChangedThisTick: Boolean = false

    @Volatile
    private var llmValidationInFlight: Boolean = false

    override fun consumePrefsChangedThisTick(): Boolean {
        val changed = prefsChangedThisTick
        prefsChangedThisTick = false
        return changed
    }

    fun currentSession(): TpoSessionDocument? = sessionManager.currentSession()

    fun revertNow(): Boolean {
        val changed = sessionManager.revertNow(preferences, historyRepo, System.currentTimeMillis())
        if (changed) {
            prefsChangedThisTick = true
            tpoNotificationManager.showSessionEnded(TpoEndReason.MANUAL_REVERT)
        }
        return changed
    }

    override fun onTickStart(nowMs: Long): Boolean {
        // Always expire/revert active sessions even when TPO master switch is off.
        val changed = sessionManager.expireIfNeeded(nowMs, preferences, historyRepo)
        if (changed) {
            prefsChangedThisTick = true
            tpoNotificationManager.showSessionEnded(TpoEndReason.EXPIRED)
        }
        return changed
    }

    override fun onPatientStateReady(
        patientState: PatientStateSnapshot,
        patientModeName: String,
        patientModeConfidence: Double,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
        bgMgdl: Double,
        deltaMgdl5m: Double,
        cobGrams: Double,
        minBgLookback75m: Double,
        nowMs: Long,
    ): Boolean {
        if (!isTpoEnabled()) return false
        if (!hasRequiredAutonomy()) return false

        val input = TpoTickInput(
            nowMs = nowMs,
            bgMgdl = bgMgdl,
            deltaMgdl5m = deltaMgdl5m,
            cobGrams = cobGrams,
            minBgLookback75m = minBgLookback75m,
            mealProb = patientState.mealProb,
            sleepDebtScore = patientState.sleepDebtScore,
            thermalRecoveryBurden = patientState.thermalRecoveryBurden,
            postHypoReboundProb = patientState.postHypoReboundProb,
            patientModeName = patientModeName,
            patientModeConfidence = patientModeConfidence,
            causalDominantName = patientState.causalPosterior.dominant.name,
            causalDominantConfidence = patientState.causalPosterior.dominantConfidence,
            eventMemory = patientState.eventMemory,
            reboundGuardActive = correctionAggressionDecision?.tier == CorrectionAggressionGate.Tier.REBOUND_GUARD,
            dawnEndogenousDrive = patientState.endogenousGlucoseDrive,
        )

        val ledger = TpoEpisodeLedger.update(persistence.loadLedger(), input)
        persistence.saveLedger(ledger)

        val active = sessionManager.currentSession()
        val evaluation = TpoTriggerEngine.evaluate(
            input = input,
            ledger = ledger,
            activePackId = active?.takeIf {
                it.status == TpoSessionStatus.ACTIVE || it.status == TpoSessionStatus.PENDING_LLM
            }?.packId,
            lastRevertAtMsByPack = persistence.loadLastRevertAtMsByPack(),
        )
        val proposal = evaluation.proposal ?: run {
            if (evaluation.blockedReason != null && evaluation.blockedReason != "no_trigger") {
                aapsLogger.debug(LTag.APS, "TPO blocked: ${evaluation.blockedReason}")
            }
            return false
        }

        if (active?.status == TpoSessionStatus.ACTIVE || active?.status == TpoSessionStatus.PENDING_LLM) {
            if (proposal.packId.priority <= active.packId.priority) {
                return false
            }
            sessionManager.supersedeActiveSession(preferences, historyRepo, nowMs)
            tpoNotificationManager.showSessionEnded(TpoEndReason.SUPERSEDED)
        }

        val plan = TpoDeltaBuilder.buildPlan(
            proposal = proposal,
            preferences = preferences,
            hypoLoad = patientState.eventMemory.recentHypoLoad,
            t3cBrittle = preferences.get(BooleanKey.OApsAIMIT3cBrittleMode),
        )
        if (plan.changes.isEmpty()) {
            aapsLogger.debug(LTag.APS, "TPO plan empty for ${proposal.packId.name}")
            return false
        }

        val llmConfirmEnabled = preferences.get(BooleanKey.OApsAIMITpoLlmConfirmEnabled)
        if (!llmConfirmEnabled) {
            val session = sessionManager.startSession(plan, preferences, nowMs, llmResult = null, historyRepo)
            prefsChangedThisTick = true
            tpoNotificationManager.showSessionStarted(session)
            aapsLogger.info(LTag.APS, "TPO applied ${proposal.packId.name} algo-only")
            return true
        }

        if (llmValidationInFlight) return false
        sessionManager.savePendingSession(plan, nowMs)
        llmValidationInFlight = true
        scope.launch {
            try {
                val result = llmValidator.validate(proposal, plan, input, ledger)
                val pending = sessionManager.currentSession()
                if (pending?.status != TpoSessionStatus.PENDING_LLM) return@launch
                if (llmValidator.shouldApply(result, llmConfirmEnabled = true)) {
                    if (sessionManager.activatePendingSession(pending, preferences, result, historyRepo)) {
                        prefsChangedThisTick = true
                        sessionManager.currentSession()?.let { tpoNotificationManager.showSessionStarted(it) }
                    }
                    aapsLogger.info(LTag.APS, "TPO applied ${proposal.packId.name} LLM=${result.verdict}")
                } else {
                    sessionManager.cancelPendingSession(
                        reason = "LLM ${result.verdict}: ${result.rationale}",
                        historyRepo = historyRepo,
                    )
                    aapsLogger.info(LTag.APS, "TPO veto ${proposal.packId.name}: ${result.rationale}")
                }
            } finally {
                llmValidationInFlight = false
            }
        }
        return false
    }

    private fun isTpoEnabled(): Boolean = preferences.get(BooleanKey.OApsAIMITpoEnabled)

    private fun hasRequiredAutonomy(): Boolean {
        val draft = readAimiControlCenterDraft(preferences)
        return draft.autonomyMode.authorityRank() >= AimiAutonomyMode.AssistedApplication.authorityRank()
    }
}
