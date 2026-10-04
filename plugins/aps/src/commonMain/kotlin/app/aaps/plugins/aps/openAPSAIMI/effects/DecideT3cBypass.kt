package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.T3cTrajectoryContext
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis

/**
 * Android reads and writes of [decideT3cBrittleBypass], each at the line that uses it.
 * Predictions and the trajectory guard stay Android; this function chooses whether they run
 * and which basal [executeT3cBrittleMode] receives.
 */
internal interface AimiT3cBypassCalls {
    fun legacyBypassAllowed(): Boolean
    fun markHistoricalBypassNeutralized()
    fun markRuntimeOwnership(mode: String, reason: String)
    fun setDecisionSource(source: String)
    fun bolusesSince(startMs: Long, ascending: Boolean): List<BS>
    fun internalLastSmbMillis(): Long
    fun iob(): Float
    fun maxIob(): Double
    fun runAutodriveShadow(ctx: AimiTickContext, profile: OapsProfileAimi, shortAvgDeltaAdj: Float)
    fun bg(): Double
    fun delta(): Float
    fun applyAdvancedPredictions(
        bg: Double,
        delta: Float,
        sens: Double,
        iobDataArray: Array<IobTotal>,
        mealData: MealData,
        profile: OapsProfileAimi,
        rT: RT,
    )
    fun bgacc(): Double
    fun iobActivityNow(): Double
    fun cob(): Float
    fun applyTrajectoryAnalysis(
        currentTime: Long,
        bg: Double,
        delta: Double,
        bgacc: Double,
        iobActivityNow: Double,
        iob: Float,
        insulinActionState: InsulinActionState,
        lastBolusAgeMinutes: Double,
        cob: Float,
        targetBg: Double,
        profile: OapsProfileAimi,
        rT: RT,
        uiInteraction: UiInteraction,
        relevanceScore: Double,
    )
    fun eventualBg(): Double
    fun lastTrajectoryAnalysis(): TrajectoryAnalysis?
    fun treeSnapshotMissing(): Boolean
    fun deployPhysioTree(sourceSensor: SourceSensor?)
    fun logPhysioDeployFailure(error: Throwable)
    fun proposeAutodriveBasal(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        shortAvgDeltaAdj: Float,
        lgsThresholdMgdl: Double,
    ): AutodriveEngine.BasalOnlyTbrProposal?
    fun variableSensitivity(): Float
    fun executeT3c(
        bg: Double,
        delta: Float,
        shortAvgDelta: Double,
        longAvgDelta: Double,
        accel: Double,
        duraISFminutes: Double,
        duraISFaverage: Double,
        profile: OapsProfileAimi,
        currentTemp: CurrentTemp,
        iob: IobTotal,
        targetBg: Double,
        variableSensitivity: Double,
        maxIob: Double,
        eventualBg: Double,
        rT: RT,
        trajectoryContext: T3cTrajectoryContext?,
        cgmNoise: Double,
        autodriveBasalProposal: AutodriveEngine.BasalOnlyTbrProposal?,
    ): RT
}

/**
 * `runT3cBrittleBypassOrReturn`.
 *
 * When the legacy brittle bypass is allowed, predictions and the trajectory guard run, then
 * the PI basal is returned. In the locked scene that basal is 1.30 U/h for 30 minutes.
 * The physio deploy still catches every [Throwable] and continues without a tree, as before.
 * The failure is an [OptionalSignal.Failed] plus a console line, and the Android port still
 * writes `aapsLogger.error`. The shadow tick and the basal proposal keep their own logs.
 */
internal fun decideT3cBrittleBypass(
    ctx: AimiTickContext,
    profile: OapsProfileAimi,
    rT: RT,
    originalProfile: OapsProfileAimi,
    pkpdRuntime: PkPdRuntime?,
    shortAvgDeltaAdj: Float,
    physioMultipliers: PhysioMultipliersMTR,
    insulinActionState: InsulinActionState,
    preferences: Preferences,
    consoleLog: MutableList<String>,
    calls: AimiT3cBypassCalls,
): RT? {
    if (!preferences.get(BooleanKey.OApsAIMIT3cBrittleMode)) return null
    if (!calls.legacyBypassAllowed()) {
        calls.markHistoricalBypassNeutralized()
        calls.markRuntimeOwnership("LEGACY_SKIPPED", "native_rbt_owner")
        consoleLog.add("🌳 T3C_NATIVE: legacy bypass skipped (native RBT owns T3C)")
        return null
    }
    calls.markRuntimeOwnership("LEGACY_FALLBACK", "rbt_authority_off")
    calls.setDecisionSource("T3C_LEGACY_BYPASS")
    consoleLog.add("⚡ T3c Brittle Mode Active: Bypassing standard AIMI algorithm.")

    // 🛡️ T3c Pre-bolus Safety Guard
    // Without robust one-shot guards, lag in database persistence can cause a 24U+ runaway (4U every 5min).
    // We now use a triple-layer safety net:
    // 1. Database History (including manual boluses)
    // 2. Internal Memory (last suggested SMB time - lag-free)
    // 3. Absolute IOB Cap (Emergency fallback)

    val t3cCapWindowMs = 20 * 60 * 1000L
    val t3cCapCutoff = aimiWallClockMs() - t3cCapWindowMs

    // 1. Check Database (Harden: count ALL bolus types, not just SMB)
    val recentBolusCount = calls.bolusesSince(t3cCapCutoff, true)
        .count { it.type == BS.Type.SMB || it.type == BS.Type.NORMAL }

    // 2. Check Internal Memory (Ensures 1 tick = 1 dose max even if DB is slow)
    val timeSinceInternalSmbMs = aimiWallClockMs() - calls.internalLastSmbMillis()
    val internalBlock = timeSinceInternalSmbMs < t3cCapWindowMs

    // 3. Absolute IOB Guard (Safety Floor)
    // 🔒 Respect the USER'S maxIob setting. No hardcoded limits.
    val iobSafetyBlock = calls.iob() > calls.maxIob()

    if (recentBolusCount < 2 && !internalBlock && !iobSafetyBlock) {
        // 🍱 Legacy Meal Prebolus Support for T3c (Already handled by top-level call above)
        // internalLastSmbMillis + lastBolusSMBUnit for legacy prebolus: see [markLegacyMealDecision] (async SMB cache can lag).
    } else {
        val reason = when {
            iobSafetyBlock -> "IOB_LIMIT (${aimiFmt2(calls.iob())}U > MaxIOB)"
            internalBlock -> "INTERNAL_LOCKOUT (${timeSinceInternalSmbMs / 60000}m < 20m)"
            else -> "DB_CAP ($recentBolusCount boluses in 20min)"
        }
        consoleLog.add("🛡️ T3c pre-bolus BLOCKED: $reason — skipping applyLegacyMealModes")
    }

    // Autodrive under T3C: when basal-authority fusion is ON, the proposal runs inside
    // executeT3cBrittleMode (TBR fused, SMB stripped). Otherwise keep DataLake shadow only.
    if (!preferences.get(BooleanKey.OApsAIMIT3cAutodriveBasalAuthority)) {
        calls.runAutodriveShadow(ctx, profile, shortAvgDeltaAdj)
    }

    // 🔮 T3c + trajectory / advanced predictions (isolated path — same engines as main loop)
    val iobRowT3c = ctx.iobDataArray.firstOrNull() ?: IobTotal(ctx.currentTime)
    val lastBolusAgeT3c = if (iobRowT3c.lastBolusTime > 0L || calls.internalLastSmbMillis() > 0L) {
        val tEff = kotlin.math.max(iobRowT3c.lastBolusTime, calls.internalLastSmbMillis())
        ((ctx.currentTime - tEff) / 60000.0).coerceAtLeast(0.0)
    } else {
        0.0
    }
    val dynSensT3c = profile.variable_sens.takeIf { it > 0.0 } ?: profile.sens
    val fusedT3c = pkpdRuntime?.fusedIsf
    val sensForT3cPred = (
        when {
            fusedT3c != null && dynSensT3c > 0.0 -> kotlin.math.min(fusedT3c, dynSensT3c)
            fusedT3c != null -> fusedT3c
            else -> dynSensT3c
        }.coerceAtLeast(10.0) * ctx.autosensData.ratio.coerceIn(0.25, 4.0)
        )
    calls.applyAdvancedPredictions(
        bg = calls.bg(),
        delta = calls.delta(),
        sens = sensForT3cPred,
        iobDataArray = ctx.iobDataArray,
        mealData = ctx.mealData,
        profile = ctx.profile,
        rT = rT,
    )
    calls.applyTrajectoryAnalysis(
        currentTime = ctx.currentTime,
        bg = calls.bg(),
        delta = calls.delta().toDouble(),
        bgacc = calls.bgacc(),
        iobActivityNow = calls.iobActivityNow(),
        iob = calls.iob(),
        insulinActionState = insulinActionState,
        lastBolusAgeMinutes = lastBolusAgeT3c,
        cob = calls.cob(),
        targetBg = originalProfile.target_bg,
        profile = profile,
        rT = rT,
        uiInteraction = ctx.uiInteraction,
        relevanceScore = physioMultipliers.trajectoryRelevanceScore,
    )
    // CFRD: enforce the higher LGS floor on the trajectory context as well
    val cfrdLgsFloorForTraj = if (preferences.get(BooleanKey.OApsAIMIT3cCfrdMode))
        preferences.get(DoubleKey.OApsAIMIT3cCfrdLgsFloorMgdl) else 70.0
    val lgsT3c = kotlin.math.min(
        90.0,
        (profile.lgsThreshold?.toDouble() ?: 70.0).coerceAtLeast(cfrdLgsFloorForTraj),
    )
    val minPredT3c = rT.predBGs?.IOB?.minOrNull()?.toDouble() ?: calls.bg()
    val eventualT3c = rT.eventualBG?.takeIf { it.isFinite() } ?: calls.eventualBg().coerceAtLeast(40.0)
    val t3cTrajCtx = T3cTrajectoryContext.build(
        minPredBg = minPredT3c,
        eventualPredBg = eventualT3c,
        bg = calls.bg(),
        lgsThresholdMgdl = lgsT3c,
        trajectoryEnabled = rT.trajectoryEnabled == true,
        lastAnalysis = calls.lastTrajectoryAnalysis(),
    )
    consoleLog.add(
        "🛡️ T3c predict+traj: min=${minPredT3c.toInt()} ev=${eventualT3c.toInt()} LGS=${lgsT3c.toInt()} " +
            "traj=${t3cTrajCtx.trajectoryTypeName ?: "—"} E=${t3cTrajCtx.energyBalance?.let { aimiFmt1(it) } ?: "—"}",
    )

    // T3C dependency guarantee (B): deploy the physiological tree + activity belief for the T3C decision,
    // same as the standard path — even when the autodrive shadow tick above didn't run (autodriveEngine null).
    // Guarded so we never rebuild twice per tick. SMB stays 0 (enforced in executeT3cBrittleMode); this only
    // makes the BASAL decision physio-informed (consumed in executeT3cBrittleMode, workstream C).
    if (calls.treeSnapshotMissing()) {
        val deployed: OptionalSignal<Unit> = runCatching {
            calls.deployPhysioTree(ctx.glucoseStatus.sourceSensor)
        }.fold(
            onSuccess = { OptionalSignal.Ready(Unit) },
            onFailure = { error ->
                calls.logPhysioDeployFailure(error)
                val failed = OptionalSignal.Failed(
                    source = "physioTree",
                    errorType = error::class.simpleName ?: "Throwable",
                    message = error.message,
                )
                consoleLog.add(
                    "T3C physioTree failed (${failed.errorType}): ${failed.message.orEmpty()} — deploy skipped",
                )
                failed
            },
        )
        // Null keeps the reference fallback: the tick continues with no tree deployed.
        deployed.valueOrNull()
    }

    val adBasalProposal = calls.proposeAutodriveBasal(
        ctx = ctx,
        profile = profile,
        shortAvgDeltaAdj = shortAvgDeltaAdj,
        lgsThresholdMgdl = lgsT3c,
    )

    return calls.executeT3c(
        bg = ctx.glucoseStatus.glucose,
        delta = ctx.glucoseStatus.delta.toFloat(),
        shortAvgDelta = ctx.glucoseStatus.shortAvgDelta,
        longAvgDelta = ctx.glucoseStatus.longAvgDelta,
        accel = ctx.glucoseStatus.bgAcceleration,
        duraISFminutes = ctx.glucoseStatus.duraISFminutes,
        duraISFaverage = ctx.glucoseStatus.duraISFaverage,
        profile = profile,
        currentTemp = ctx.currentTemp,
        iob = ctx.iobDataArray.firstOrNull() ?: IobTotal(aimiWallClockMs()),
        targetBg = originalProfile.target_bg,
        variableSensitivity = calls.variableSensitivity().toDouble(),
        maxIob = calls.maxIob(),
        eventualBg = eventualT3c.coerceAtLeast(40.0),
        rT = rT,
        trajectoryContext = t3cTrajCtx,
        cgmNoise = ctx.glucoseStatus.noise,
        autodriveBasalProposal = adBasalProposal,
    )
}
