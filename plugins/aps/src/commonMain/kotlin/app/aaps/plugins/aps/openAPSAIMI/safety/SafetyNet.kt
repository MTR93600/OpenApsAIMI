package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.max

/**
 * SafetyNet: Centralized SMB Safety Logic.
 *
 * Implements a tiered-reaction strategy based on relative glucose zones.
 * All zone thresholds are computed relative to [targetBg] so that the
 * same safety margins apply regardless of the configured profile target.
 *
 * Zone Architecture (offsets from profile target):
 * - Zone 0.5 : Soft Landing  (target < bg ≤ target+15)  — gentle convergence boost
 * - Zone 1   : Strict Guard  (bg < target+20, min 115)  — hypo-adjacent band, UAM bypass
 * - Zone 2   : Buffer        (zone1 … target+70, min 160) — progressive SMB ramp
 * - Zone 3   : Reactor       (bg ≥ zone2 upper bound)   — full aggression
 */
object SafetyNet {

    // Zone thresholds expressed as offsets above targetBg so that all three zones
    // scale together when the clinician adjusts the profile target (e.g. 100 → 140).
    // Absolute floors ensure the thresholds never fall below physiologically safe values.
    private const val ZONE1_OFFSET = 20.0   // strict-guard upper bound = target + 20 mg/dL
    private const val ZONE2_OFFSET = 70.0   // buffer ceiling            = target + 70 mg/dL
    private const val ZONE1_FLOOR  = 115.0  // never lower than this absolute value
    private const val ZONE2_FLOOR  = 160.0  // never lower than this absolute value

    /**
     * Pathological eventual values (wrong temp target, missing pred curves) must not force
     * buffer-zone clamps toward [maxSmbLow] when current BG is mid-range hyper.
     */
    internal fun sanitizeEventualMgdlForSmbZones(bg: Double, targetBg: Double, eventualBg: Double): Double {
        if (!eventualBg.isFinite()) return max(bg + 40.0, targetBg + 30.0)
        if (eventualBg > 300.0 && eventualBg > bg + 80.0) {
            return max(bg + 35.0, targetBg + 25.0)
        }
        return eventualBg
    }

    /**
     * Calculates the safe Maximum SMB allowed for the current context.
     * 
     * @param bg Current Blood Glucose
     * @param targetBg Profile Target BG
     * @param eventualBg Predicted eventual BG (Trajectory)
     * @param delta Instant trend
     * @param shortAvgDelta 15min trend confirmation
     * @param maxSmbLow The user's standard "MaxSMB" setting (Conservative)
     * @param maxSmbHigh The user's "High BG MaxSMB" setting (Aggressive/Rage)
     * @param isExplicitUserAction True if this is a manual user override
     * @param auditorConfidence AI Auditor confidence (0-1), null if unavailable
     * @return The safe SMB limit (U)
     */
    fun calculateSafeSmbLimit(
        bg: Double,
        targetBg: Double,
        eventualBg: Double,
        delta: Double,
        shortAvgDelta: Double,
        maxSmbLow: Double,
        maxSmbHigh: Double,
        isExplicitUserAction: Boolean,
        auditorConfidence: Double? = null,
        mealPriorityContext: Boolean = false,
        /** When false, skip auditor soft-landing boost (e.g. Harmonizer BLOCK / hypo protective). */
        allowAuditorSoftLanding: Boolean = true,
    ): Double {
        val eventualForZones = sanitizeEventualMgdlForSmbZones(bg, targetBg, eventualBg)

        // Zone thresholds scale with the profile target so that the relative distance
        // from target to each boundary stays consistent across different patient configurations.
        val zone1Upper = max(ZONE1_FLOOR, targetBg + ZONE1_OFFSET)
        val zone2Upper = max(ZONE2_FLOOR, targetBg + ZONE2_OFFSET)

        // 1. Manual Override: Full Trust
        if (isExplicitUserAction) return maxSmbHigh

        // 1.5 Meal Priority Context (non-announced/announced meal rise harmonization)
        // Below zone1: stay conservative — we are still in the hypo-adjacent band.
        // From zone1 upward: ramp toward high-BG max so the second SMB limit engages on rise.
        if (mealPriorityContext) {
            return when {
                bg < zone1Upper -> maxSmbLow * 0.70
                bg < zone2Upper -> {
                    val progress = ((bg - zone1Upper) / (zone2Upper - zone1Upper)).coerceIn(0.0, 1.0)
                    val boosted = max(progress, 0.75)
                    val range = maxSmbHigh - maxSmbLow
                    maxSmbLow + (range * boosted)
                }
                else -> maxSmbHigh
            }
        }

        // 2. ZONE 0.5: SOFT LANDING (Target → Target+15)
        // Purpose: Prevent "plateau effect" 10-20 mg/dL above target
        val distanceAboveTarget = bg - targetBg
        val inSoftLandingZone = distanceAboveTarget > 0 && distanceAboveTarget <= 15

        if (inSoftLandingZone) {
            val isCoasting = delta <= 1.0 && eventualForZones > targetBg
            if (isCoasting) {
                val boostFactor = when {
                    !allowAuditorSoftLanding -> 1.0
                    auditorConfidence != null -> when {
                        auditorConfidence >= 0.7 -> 1.10
                        auditorConfidence >= 0.5 -> 1.05
                        else -> 1.0
                    }
                    else -> 1.10
                }
                return maxSmbLow * boostFactor
            }
        }

        // 3. ZONE 1: STRICT GUARD (above soft-landing zone … zone1Upper)
        // Conservative cap in the hypo-adjacent band; UAM rocket bypass remains.
        val isUamRise = delta > 6.0 || shortAvgDelta > 6.0

        if (bg < zone1Upper) {
            if (isUamRise) return maxSmbLow
            return maxSmbLow * 0.5
        }

        // 4. ZONE 2: BUFFER / TRANSITION (zone1Upper … zone2Upper)
        if (bg < zone2Upper) {
            // Clamp to conservative max only when trajectory heads back into Zone 1.
            if (eventualForZones < zone1Upper) return maxSmbLow
            val progress = ((bg - zone1Upper) / (zone2Upper - zone1Upper)).coerceIn(0.0, 1.0)
            val range = maxSmbHigh - maxSmbLow
            return maxSmbLow + (range * progress)
        }

        // 5. ZONE 3: REACTOR MAX (BG >= zone2Upper)
        return maxSmbHigh
    }
}
