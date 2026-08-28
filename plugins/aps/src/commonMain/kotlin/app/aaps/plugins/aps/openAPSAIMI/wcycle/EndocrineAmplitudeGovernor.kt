package app.aaps.plugins.aps.openAPSAIMI.wcycle

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Production endocrine amplitude owner (Lots B–D).
 *
 * Effective amps are applied on the pump dose path — not shadow/compare.
 * User WCycle shadow/confirm prefs still force applicationMode ≠ APPLIED → amps 1.0 at apply sites.
 */
object EndocrineAmplitudeGovernor {

    /** Hypo load at/above this forces all effective amps to 1.0 (production protect). */
    const val HARD_UNITY_HYPO_LOAD = 0.45

    /** Under HYPO_GUARD, lower threshold for hard unity. */
    const val HARD_UNITY_HYPO_GUARD_LOAD = 0.25

    fun from(
        info: WCycleInfo?,
        prefs: WCyclePreferences,
        hypoLoad: Double = 0.0,
        hypoGuardActive: Boolean = false,
        hourOfDay: Int = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).hour,
    ): WCycleBelief {
        if (info == null || !info.enabled) return WCycleBelief.DISABLED

        val trackingMode = prefs.trackingMode()
        val contraceptive = prefs.contraceptive()
        val thyroid = prefs.thyroid()
        val verneuil = prefs.verneuil()
        val ampContraceptive = WCycleDefaults.amplitudeScale(contraceptive)
        val ampTrackingMode = when (trackingMode) {
            CycleTrackingMode.PERIMENOPAUSE -> 0.8
            CycleTrackingMode.NO_MENSES_LARC -> 0.6
            else -> 1.0
        }
        val ampCombined = ampContraceptive * ampTrackingMode
        val dawnBias =
            if (info.phase == CyclePhase.LUTEAL && hourOfDay in 4..7) 1.10 else 1.0

        val applicationMode = when {
            prefs.shadow() -> EndocrineApplicationMode.SHADOW
            prefs.requireConfirm() -> EndocrineApplicationMode.CONFIRM_PENDING
            info.applied -> EndocrineApplicationMode.APPLIED
            else -> EndocrineApplicationMode.SHADOW
        }

        // Dawn owned here (removed from WCycleAdjuster) so hypo-dampen can collapse luteal dawn yoyo.
        val intendedBasal = (info.baseBasalMultiplier * info.learnedBasalMultiplier * dawnBias)
            .coerceIn(prefs.clampMin(), prefs.clampMax())
        val intendedSmb = (info.baseSmbMultiplier * info.learnedSmbMultiplier)
            .coerceIn(prefs.clampMin(), prefs.clampMax())
        val intendedIc = if (info.applied) {
            info.icMultiplier
        } else {
            val ic0 = WCycleDefaults.icMultiplier(info.phase)
            (1.0 + (ic0 - 1.0) * ampCombined).coerceIn(prefs.clampMin(), prefs.clampMax())
        }

        val load = hypoLoad.coerceIn(0.0, 1.0)
        val forceUnity =
            load >= HARD_UNITY_HYPO_LOAD ||
                (hypoGuardActive && load >= HARD_UNITY_HYPO_GUARD_LOAD)

        val guardFloor = if (hypoGuardActive) 0.15 else 0.0
        val hypoDampen = if (forceUnity) {
            0.0
        } else {
            (1.0 - load * (0.85 + guardFloor)).coerceIn(0.0, 1.0)
        }

        fun dampTowardUnity(amp: Double): Double =
            if (forceUnity) 1.0 else 1.0 + (amp - 1.0) * hypoDampen

        val inflamBudget = WCycleDefaults.verneuilBump(verneuil).first
        val effectiveBasal = dampTowardUnity(intendedBasal)
        val effectiveSmb = dampTowardUnity(intendedSmb)
        val effectiveIc = dampTowardUnity(intendedIc)

        val phaseConfidence = when (info.phase) {
            CyclePhase.LUTEAL -> 0.72
            CyclePhase.OVULATION -> 0.55
            CyclePhase.MENSTRUATION -> 0.40
            CyclePhase.FOLLICULAR -> 0.28
            CyclePhase.UNKNOWN -> 0.10
        }
        val ampSignal = ((effectiveBasal - 1.0).coerceAtLeast(0.0) / 0.35).coerceIn(0.0, 1.0)
        val confidence = (phaseConfidence * 0.65 + ampSignal * 0.35).coerceIn(0.0, 1.0)

        val reasons = buildList {
            add("phase=${info.phase.name}")
            add("day=${info.dayInCycle}")
            add("mode=${applicationMode.name}")
            add("intended_basal=${fmt(intendedBasal)}")
            add("effective_basal=${fmt(effectiveBasal)}")
            add("adjuster_basal=${fmt(info.basalMultiplier)}")
            add("hypo_dampen=${fmt(hypoDampen)}")
            if (forceUnity) add("hard_unity_hypo")
            if (dawnBias > 1.0) add("dawn_bias=${fmt(dawnBias)}")
            if (inflamBudget > 1.0) add("verneuil_budget=${fmt(inflamBudget)}")
            if (hypoGuardActive) add("hypo_guard")
            add("dose_path=${EndocrineDosePathOwner.PRODUCTION_GOVERNOR_DIRECT.name}")
        }

        return WCycleBelief(
            enabled = true,
            phase = info.phase,
            dayInCycle = info.dayInCycle,
            trackingMode = trackingMode,
            contraceptive = contraceptive,
            thyroid = thyroid,
            verneuil = verneuil,
            applicationMode = applicationMode,
            ampContraceptive = ampContraceptive,
            ampTrackingMode = ampTrackingMode,
            ampCombined = ampCombined,
            dawnBias = dawnBias,
            intendedBasalAmp = intendedBasal,
            intendedSmbAmp = intendedSmb,
            intendedIcAmp = intendedIc,
            hypoLoad = load,
            hypoLoadDampen = hypoDampen,
            hypoGuardActive = hypoGuardActive,
            inflamSharedBudgetHint = inflamBudget,
            effectiveBasalAmp = effectiveBasal,
            effectiveSmbAmp = effectiveSmb,
            effectiveIcAmp = effectiveIc,
            legacyDoseBasalAmp = info.basalMultiplier,
            legacyDoseSmbAmp = info.smbMultiplier,
            legacyDoseIcAmp = info.icMultiplier,
            dosePathOwner = EndocrineDosePathOwner.PRODUCTION_GOVERNOR_DIRECT,
            confidence = confidence,
            reasons = reasons,
        )
    }

    /** Production amp for dose path — 1.0 unless APPLIED. */
    fun productionAmp(belief: WCycleBelief?, axis: EndocrineAmpAxis): Double {
        if (belief == null || !belief.enabled) return 1.0
        if (belief.applicationMode != EndocrineApplicationMode.APPLIED) return 1.0
        return when (axis) {
            EndocrineAmpAxis.BASAL -> belief.effectiveBasalAmp
            EndocrineAmpAxis.SMB -> belief.effectiveSmbAmp
            EndocrineAmpAxis.IC -> belief.effectiveIcAmp
        }
    }

    private fun fmt(x: Double): String =
        NumberFormat.withDecimals(3).format(x, NumberFormatPlatform.SEPARATOR_DOT)
}

enum class EndocrineAmpAxis {
    BASAL,
    SMB,
    IC,
}
