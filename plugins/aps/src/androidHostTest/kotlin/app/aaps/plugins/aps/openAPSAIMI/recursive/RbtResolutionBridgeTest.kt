package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RbtResolutionBridgeTest {

    @Test
    fun partial_hypo_guard_uses_partial_credibility_not_full_ignore() {
        val hints = RbtResolutionBridge.apply(
            resolution = DoseChannelResolution(
                smbDemandU = 1.0,
                tbrDemandFraction = 1.0,
                waitBias = 0.65,
                dominantScaleMinutes = 60,
                releaseAuthority = ReleaseAuthority.SOFT,
                hypoGuardMode = HypoGuardMode.PARTIAL,
                autodriveModeHint = AutodriveModeHint.V3,
                mealChannel = MealChannelHint.PRIORITY,
                suppressTrajBasalShift = false,
                hypoMinPredIgnored = false,
                reasonCodes = listOf("P1"),
            ),
            effectiveAuthority = ReleaseAuthority.SOFT,
            chaos = null,
            episode = null,
            defaultMealPriority = false,
        )
        assertThat(hints.ignoreMinPredictedCurve).isFalse()
        assertThat(hints.partialMinPredCredibility).isTrue()
        assertThat(hints.mealPriorityContext).isTrue()
        assertThat(hints.waitBiasMultiplier).isLessThan(1.0)
    }

    @Test
    fun authority_none_blocks_live_dosing_hints() {
        val hints = RbtResolutionBridge.apply(
            resolution = DoseChannelResolution(
                smbDemandU = 1.0,
                tbrDemandFraction = 1.0,
                waitBias = 0.65,
                dominantScaleMinutes = 60,
                releaseAuthority = ReleaseAuthority.HARD,
                hypoGuardMode = HypoGuardMode.IGNORE_MINPRED,
                autodriveModeHint = AutodriveModeHint.V3,
                mealChannel = MealChannelHint.PRIORITY,
                suppressTrajBasalShift = false,
                hypoMinPredIgnored = true,
                reasonCodes = listOf("P1"),
            ),
            effectiveAuthority = ReleaseAuthority.NONE,
            chaos = null,
            episode = null,
            defaultMealPriority = false,
        )
        assertThat(hints.ignoreMinPredictedCurve).isFalse()
        assertThat(hints.partialMinPredCredibility).isFalse()
        assertThat(hints.mealPriorityContext).isFalse()
        assertThat(hints.suppressMealInterpretation).isFalse()
        assertThat(hints.waitBiasMultiplier).isEqualTo(1.0)
        assertThat(hints.mealChannel).isNull()
    }

    @Test
    fun suppress_meal_channel_blocks_meal_priority() {
        val hints = RbtResolutionBridge.apply(
            resolution = DoseChannelResolution(
                smbDemandU = 0.2,
                tbrDemandFraction = 1.0,
                waitBias = 0.15,
                dominantScaleMinutes = 60,
                releaseAuthority = ReleaseAuthority.NONE,
                hypoGuardMode = HypoGuardMode.FULL,
                autodriveModeHint = AutodriveModeHint.V3,
                mealChannel = MealChannelHint.SUPPRESS,
                suppressTrajBasalShift = false,
                hypoMinPredIgnored = false,
                reasonCodes = listOf("PATTERN_MEAL_SUPPRESS"),
            ),
            effectiveAuthority = ReleaseAuthority.SOFT,
            chaos = null,
            episode = null,
            defaultMealPriority = true,
        )
        assertThat(hints.mealPriorityContext).isFalse()
        assertThat(hints.suppressMealInterpretation).isTrue()
    }

    @Test
    fun chaos_reduces_wait_bias_multiplier() {
        val resolution = DoseChannelResolution(
            smbDemandU = 1.0,
            tbrDemandFraction = 1.0,
            waitBias = 0.15,
            dominantScaleMinutes = 60,
            releaseAuthority = ReleaseAuthority.HARD,
            hypoGuardMode = HypoGuardMode.FULL,
            autodriveModeHint = AutodriveModeHint.V3,
            mealChannel = MealChannelHint.NORMAL,
            suppressTrajBasalShift = false,
            hypoMinPredIgnored = false,
            reasonCodes = emptyList(),
        )
        val plain = RbtResolutionBridge.apply(resolution, ReleaseAuthority.HARD, null, null, defaultMealPriority = false)
        val chaotic = RbtResolutionBridge.apply(
            resolution,
            ReleaseAuthority.HARD,
            RbtChaosEvaluator.Result(0.80, active = true, caution = true, reasonCodes = listOf("TENSION")),
            null,
            defaultMealPriority = false,
        )
        assertThat(chaotic.waitBiasMultiplier).isLessThan(plain.waitBiasMultiplier)
    }
}
