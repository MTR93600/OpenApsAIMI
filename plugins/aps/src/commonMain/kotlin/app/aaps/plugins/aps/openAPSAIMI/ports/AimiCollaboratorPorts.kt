package app.aaps.plugins.aps.openAPSAIMI.ports

import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorJsonlExport
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdict
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.LocalSentinel
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaHarmonizer
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaProductionDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateSnapshot
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.safety.CorrectionAggressionGate

/*
 * Narrow ports onto the AIMI loop's big Android collaborators.
 *
 * Each interface here is deliberately as small as the loop's own use of the class behind it, because
 * that is what keeps the class - and everything it drags in - out of shared code. The auditor is 628
 * lines and pulls in `LiveData`; the comparator is 509 and pulls in file writers; the SOS manager
 * pulls in `SmsManager` and `LocationManager`. The loop needs one or two members of each. Naming just
 * those members here means the implementations can stay on Android.
 *
 * These have no implementation yet. The classes that will implement them are still outside every
 * source set; they get their supertype when they move into `androidMain`.
 */

/**
 * The decision auditor, as the loop sees it.
 *
 * Observe-only from the loop's side: [auditDecision] does not change the dose. It scores the tick and
 * writes telemetry, and hands anything it wants to say back through the `auditDecision` callbacks
 * and [lastSentinelAdvice].
 */
interface AimiAuditor {

    /** Last Tier-1 Sentinel advice - coherence-agreement "gendarme" score, exposed for JSONL telemetry. */
    val lastSentinelAdvice: LocalSentinel.SentinelAdvice?

    /**
     * Scores one loop tick.
     *
     * The parameter list is the loop's whole decision, copied unchanged from the implementation: the
     * auditor is asked to judge the tick, so it has to be told everything the tick decided.
     */
    fun auditDecision(
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        glucoseStatus: GlucoseStatusAIMI?,
        iob: IobTotal,
        cob: Double?,
        profile: OapsProfileAimi,
        pkpdRuntime: PkPdRuntime?,
        isfUsed: Double,
        smbProposed: Double,
        tbrRate: Double?,
        tbrDuration: Int?,
        intervalMin: Double,
        maxSMB: Double,
        maxSMBHB: Double,
        maxIOB: Double,
        maxBasal: Double,
        reasonTags: List<String>,
        modeType: String?,
        modeRuntimeMin: Int?,
        autodriveState: String,
        wcyclePhase: String?,
        wcycleFactor: Double?,
        tbrMaxMode: Double?,
        tbrMaxAutoDrive: Double?,
        smb30min: Double,
        predictionAvailable: Boolean,
        predictedBg: Double?,
        eventualBg: Double?,
        inPrebolusWindow: Boolean,
        effectiveProfile: EffectiveProfile? = null,
        mealCertainty: MealCertainty? = null,
        harmoniaProduction: HarmoniaProductionDecision? = null,
        harmonizerOutcome: HarmoniaHarmonizer.Outcome? = null,
        onSyncDisposition: (AuditorJsonlExport.TickDisposition) -> Unit = {},
        callback: ((AuditorVerdict?, DecisionResult) -> Unit)? = null
    )
}

/**
 * The Total Plan Override session manager, as the loop sees it.
 *
 * Three calls, in tick order: [onTickStart] first, [onPatientStateReady] once the patient state is
 * known, and [consumePrefsChangedThisTick] to learn whether either of them changed a preference the
 * rest of the tick has to re-read.
 */
interface AimiTpo {

    /** `true` when this tick changed a preference, and clears the flag. Read it once per tick. */
    fun consumePrefsChangedThisTick(): Boolean

    /** Expires or reverts an active session. Runs even when the TPO master switch is off. */
    fun onTickStart(nowMs: Long): Boolean

    /** Offers the tick's patient state to the session logic. `true` when something changed. */
    fun onPatientStateReady(
        patientState: PatientStateSnapshot,
        patientModeName: String,
        patientModeConfidence: Double,
        correctionAggressionDecision: CorrectionAggressionGate.Decision?,
        bgMgdl: Double,
        deltaMgdl5m: Double,
        cobGrams: Double,
        minBgLookback75m: Double,
        nowMs: Long,
    ): Boolean
}

/**
 * The shadow comparison against plain SMB, as the loop sees it.
 *
 * One call, and it returns nothing: it runs the reference algorithm beside AIMI's answer and records
 * the pair. Nothing the loop does depends on the result, which is why a 509 line class with its own
 * file writers can stay on Android behind this.
 */
interface AimiSmbComparison {

    /** Records AIMI's answer beside what plain SMB would have done for the same inputs. */
    fun compare(
        aimiResult: RT,
        glucoseStatus: GlucoseStatusAIMI,
        currentTemp: CurrentTemp,
        iobData: Array<IobTotal>,
        profileAimi: OapsProfileAimi,
        autosens: AutosensResult,
        mealData: MealData,
        microBolusAllowed: Boolean,
        currentTime: Long,
        flatBGsDetected: Boolean,
        dynIsfMode: Boolean
    )
}

/**
 * The emergency SOS check, as the loop sees it.
 *
 * It may start background work - a location fix and an SMS - so the loop calls it and moves on. The
 * platform context the implementation needs is not in this signature; it belongs to the Android half.
 *
 * The logger and the preferences stay parameters because that is how the loop has always passed them
 * in. Moving them into the implementation's constructor would be a second change on a path that sends
 * a message to a carer, so it is not made here.
 */
interface AimiEmergencySos {

    /** Checks the SOS condition for this tick and, if it is met, starts the alert. */
    fun evaluate(
        aapsLogger: AAPSLogger,
        bg: Double,
        delta: Double,
        iob: Double,
        preferences: Preferences,
        nowMs: Long,
    )
}


/**
 * The one method `ContextManager` needs of `ContextLLMClient` (605 lines, androidMain).
 *
 * The implementation's `parseWithLLM` also takes a `MedicalContext?`, defaulted to null and declared
 * inside `ContextLLMClient` itself. Every call site passes one argument, so the port does not carry
 * that parameter and the type stays where it is.
 */
interface AimiContextLlm {

    suspend fun parseWithLLM(userText: String): List<ContextIntent>
}

/**
 * What the decision path needs of `HealthContextRepository` (282 lines, androidMain).
 *
 * Three reads, no writes. `fetchSnapshotForAutodriveGater` is the cached variant the gater uses;
 * `fetchSnapshot` refreshes; `getLastSnapshot` returns whatever was read last without touching the
 * platform.
 */
interface AimiHealthContext {

    fun fetchSnapshot(): HealthContextSnapshot

    fun fetchSnapshotForAutodriveGater(): HealthContextSnapshot

    fun getLastSnapshot(): HealthContextSnapshot
}

/**
 * The two values the decision path reads from `AIMIPhysioDataRepositoryMTR` (985 lines, androidMain,
 * twelve Health Connect imports).
 *
 * **This port does not make Health Connect an Android-only concern.** The decision path also consumes
 * HRV, sleep and skin temperature, but it does so through [AimiHealthContext] and its
 * `HealthContextSnapshot` rather than from here - and `hrvRmssd` in that snapshot reaches the dose.
 * An iOS implementation still needs HealthKit for those. See section 11.4 of the migration study for
 * the mapping, including the RMSSD-versus-SDNN mismatch, which is the one that matters.
 */
interface AimiPhysioSource {

    suspend fun fetchLastHeartRate(): Int

    suspend fun fetchStepsData(daysBack: Int = 7, ignoreUnifiedSourceMode: Boolean = false): Int
}


/**
 * Reads the AIMI Control Center's resolved authority profile for one tick.
 *
 * The read path - preferences to draft to snapshot, with two runtime history readers along the way -
 * stays in androidMain, so this is a port rather than a moved function. `AimiBehaviorRuntimeProfile`
 * and `AimiAutonomyMode` are plain data in commonMain; only how they get built from disk is Android.
 */
interface AimiBehaviorProfileSource {

    fun read(preferences: Preferences): AimiBehaviorRuntimeProfile
}
