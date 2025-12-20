package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class MealAggressionContext(
    val mealModeActive: Boolean,
    val predictedBgMgdl: Double? = null,
    val targetBgMgdl: Double? = null
)

class PkPdIntegration(private val preferences: Preferences) {

    private data class Config(
        val enabled: Boolean,
        val bounds: PkPdBounds,
        val initial: PkPdParams,
        val isfBounds: IsfFusionBounds,
        val tailPolicy: TailAwareSmbPolicy
    )

    private var cachedConfig: Config? = null
    private var estimator: AdaptivePkPdEstimator? = null
    private var lastBounds: PkPdBounds? = null
    private var fusion: IsfFusion? = null
    private var lastFusionBounds: IsfFusionBounds? = null
    private var damping: SmbDamping? = null
    private var lastTailPolicy: TailAwareSmbPolicy? = null
    private var lastPersisted: PkPdParams? = null
@Synchronized
    fun computeRuntime(
        epochMillis: Long,
        bg: Double,
        deltaMgDlPer5: Double,
        iobU: Double,
        carbsActiveG: Double,
        windowMin: Int,
        exerciseFlag: Boolean,
        profileIsf: Double,
        tdd24h: Double,
        mealContext: MealAggressionContext? = null,
        consoleLog: MutableList<String>? = null
    ): PkPdRuntime? {
        val config = readConfig()
        // If the configuration changed, clear cached objects so they are rebuilt
        if (cachedConfig == null || cachedConfig != config) {
            cachedConfig = config
            estimator = null
            fusion = null
            damping = null
            lastBounds = null
            lastFusionBounds = null
            lastTailPolicy = null
        }
        if (!config.enabled) {
            consoleLog?.add("PKPD Debug: Config ENABLED is FALSE. Check OApsAIMIPkpdEnabled preference.")
            // When disabled we also clear caches

            estimator = null
            fusion = null
            damping = null
            lastBounds = null
            lastFusionBounds = null
            lastTailPolicy = null
            cachedConfig = null
            return null
        }

        if (lastPersisted == null) {
            lastPersisted = clampParams(config.initial, config.bounds)
        }

        // Objects are created lazily; ensure* will reuse existing instances when possible
        val estimator = ensureEstimator(config)
        val fusion = ensureFusion(config.isfBounds)
        val damping = ensureDamping(config.tailPolicy)
        val tddIsf = computeTddIsf(tdd24h, profileIsf)
        IsfTddProvider.set(tddIsf)
        val epochMin = TimeUnit.MILLISECONDS.toMinutes(epochMillis)
        estimator.update(
            epochMin = epochMin,
            bg = bg,
            deltaMgDlPer5 = deltaMgDlPer5,
            iobU = iobU,
            carbsActiveG = carbsActiveG,
            windowMin = windowMin,
            exerciseFlag = exerciseFlag
        )
        val params = estimator.params()
        persistStateIfNeeded(params, config.bounds)
        val tailFraction = estimator.iobResidualAt(windowMin.toDouble()).coerceIn(0.0, 1.0)
        val activityState = estimator.activityStateAt(windowMin.toDouble())
        val freshness = (1.0 - activityState.postWindowFraction).coerceIn(0.0, 1.0)
        val activityBlend = (0.6 * activityState.relativeActivity + 0.4 * freshness).coerceIn(0.0, 1.0)
        val anticipatoryBoost = activityState.anticipationWeight * 0.1
        val mealBoost = mealContext?.let { ctx ->
            if (!ctx.mealModeActive) return@let 0.0
            val predicted = ctx.predictedBgMgdl
            val target = ctx.targetBgMgdl
            val normalizedRise = if (predicted != null && target != null) {
                ((predicted - target).coerceAtLeast(0.0) / 70.0).coerceIn(0.0, 1.0)
            } else 0.0
            0.05 + 0.15 * normalizedRise
        } ?: 0.0
        val minScale = if (mealContext?.mealModeActive == true) 0.9 else 0.8
        val maxScale = if (mealContext?.mealModeActive == true) 1.5 else 1.4
        val pkpdScale = (1.0 + 0.12 * tailFraction + 0.22 * activityBlend + anticipatoryBoost + mealBoost)
            .coerceIn(minScale, maxScale)
        val fusedIsf = fusion.fused(profileIsf, tddIsf, pkpdScale)
        return PkPdRuntime(
            params = params,
            tailFraction = tailFraction,
            fusedIsf = fusedIsf,
            profileIsf = profileIsf,
            tddIsf = tddIsf,
            pkpdScale = pkpdScale,
            damping = damping,
            activity = activityState
        )
    }

    private fun readConfig(): Config {
        val enabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled)
        val bounds = PkPdBounds(
            diaMinH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH),
            diaMaxH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH),
            peakMinMin = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
            peakMinMax = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
            maxDiaChangePerDayH = preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH),
            maxPeakChangePerDayMin = preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin)
        )
        val initial = PkPdParams(
            diaHrs = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH),
            peakMin = preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin)
        )
        val isfBounds = IsfFusionBounds(
            minFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
            maxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
            maxChangePer5Min = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick)
        )
        val tailPolicy = TailAwareSmbPolicy(
            tailIobHigh = preferences.get(DoubleKey.OApsAIMISmbTailThreshold),
            smbDampingAtTail = preferences.get(DoubleKey.OApsAIMISmbTailDamping),
            postExerciseDamping = preferences.get(DoubleKey.OApsAIMISmbExerciseDamping),
            lateFattyMealDamping = preferences.get(DoubleKey.OApsAIMISmbLateFatDamping)
        )
        return Config(enabled, bounds, initial, isfBounds, tailPolicy)
    }

    private fun ensureEstimator(config: Config): AdaptivePkPdEstimator {
        val learningCfg = PkPdLearningConfig(bounds = config.bounds)
        // Re‑create estimator only when we have never created one or the bounds changed
        if (estimator == null || lastBounds != config.bounds) {
            val start = estimator?.params()?.let { clampParams(it, config.bounds) }
                ?: clampParams(lastPersisted ?: config.initial, config.bounds)
            estimator = AdaptivePkPdEstimator(LogNormalKernel(), learningCfg, start)
            lastBounds = config.bounds
        }
        return estimator!!
    }

    private fun ensureFusion(bounds: IsfFusionBounds): IsfFusion {
        if (fusion == null || lastFusionBounds != bounds) {
            fusion = IsfFusion(bounds)
            lastFusionBounds = bounds
        }
        return fusion!!
    }

    private fun ensureDamping(policy: TailAwareSmbPolicy): SmbDamping {
        if (damping == null || lastTailPolicy != policy) {
            damping = SmbDamping(policy)
            lastTailPolicy = policy
        }
        return damping!!
    }

    private fun clampParams(params: PkPdParams, bounds: PkPdBounds): PkPdParams {
        val dia = params.diaHrs.coerceIn(bounds.diaMinH, bounds.diaMaxH)
        val peak = params.peakMin.coerceIn(bounds.peakMinMin, bounds.peakMinMax)
        return PkPdParams(dia, peak)
    }

    private fun persistStateIfNeeded(params: PkPdParams, bounds: PkPdBounds) {
        val clamped = clampParams(params, bounds)
        val last = lastPersisted
        val shouldPersist = last == null || abs(last.diaHrs - clamped.diaHrs) > 0.01 || abs(last.peakMin - clamped.peakMin) > 0.5
        if (shouldPersist) {
            preferences.put(DoubleKey.OApsAIMIPkpdStateDiaH, clamped.diaHrs)
            preferences.put(DoubleKey.OApsAIMIPkpdStatePeakMin, clamped.peakMin)
            lastPersisted = clamped
        }
    }

    private fun computeTddIsf(tdd24h: Double, fallback: Double): Double {
        if (tdd24h <= 0.1) return fallback
        val anchored = 1800.0 / tdd24h
        
        // 🛡️ CLAMP: Prevent TDD-ISF from deviating more than ±50% from profile
        // Protects against temporary TDD anomalies (new site, atypical day, etc.)
        // Example: Profile ISF = 147, TDD-ISF raw = 57 → clamped to 73.5
        val maxDeviation = fallback * 0.5
        val clamped = anchored.coerceIn(
            fallback - maxDeviation,  // Min: profile × 0.5
            fallback + maxDeviation   // Max: profile × 1.5
        )
        
        return clamped.coerceIn(5.0, 400.0)
    }
}

class PkPdRuntime(
    val params: PkPdParams,
    val tailFraction: Double,
    val fusedIsf: Double,
    val profileIsf: Double,
    val tddIsf: Double,
    val pkpdScale: Double,
    private val damping: SmbDamping,
    val activity: InsulinActivityState
) {

    // ✅ API audit (garde)
    fun dampSmbWithAudit(
        smb: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false
    ): SmbDampingAudit =
        damping.dampWithAudit(smb, tailFraction, exercise, suspectedLateFatMeal, bypassDamping, activity)

    // ✅ API non-audit (garde) — utile si on veut le résultat sans traces
    fun dampSmb(
        smb: Double,
        exercise: Boolean,
        suspectedLateFatMeal: Boolean,
        bypassDamping: Boolean = false
    ): Double =
        damping.damp(smb, tailFraction, exercise, suspectedLateFatMeal, bypassDamping, activity)
}