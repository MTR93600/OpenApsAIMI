package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.model.AimiDomain
import app.aaps.plugins.aps.openAPSAIMI.model.AimiPriority
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The four metric rules of the Profile Advisor, restored from the code removed in `8c7a6c63a9`.
 * Every threshold here is the original one.
 */
class AdvisorMetricRulesTest : TestBase() {

    private fun metrics(
        tir70_180: Double = 0.85,
        timeBelow70: Double = 0.0,
        timeBelow54: Double = 0.0,
        timeAbove180: Double = 0.0,
        basalPercent: Double = 0.40,
    ) = AdvisorMetrics(
        periodLabel = "7d",
        tir70_180 = tir70_180,
        tir70_140 = tir70_180,
        timeBelow70 = timeBelow70,
        timeBelow54 = timeBelow54,
        timeAbove180 = timeAbove180,
        timeAbove250 = 0.0,
        meanBg = 130.0,
        variabilityCv = 0.30,
        gmi = 6.5,
        tdd = 40.0,
        basalPercent = basalPercent,
        hypoEvents = 0,
        severeHypoEvents = 0,
        hyperEvents = 0,
        todayTir = null,
        todayTdd = null,
    )

    private fun prefs(maxSmb: Double = 2.0, lunchFactor: Double = 1.0) = AimiPrefsSnapshot(
        maxSmb = maxSmb,
        lunchFactor = lunchFactor,
        unifiedReactivityFactor = 1.0,
        autodriveMaxBasal = 1.0,
    )

    private fun update(rec: AimiRecommendation) = rec.action as? AimiAction.PreferenceUpdate

    /** Answers with the name of the reference it was asked for so a test can see which string a rule used. */
    private val textResolver = object : TextResolver {
        override fun gs(ref: TextRef): String = name(ref)
        override fun gs(ref: TextRef, vararg args: Any?): String = name(ref)
        override fun gsNotLocalised(ref: TextRef): String = name(ref)
        override fun shortTextMode(): Boolean = false

        private fun name(ref: TextRef): String = when (ref) {
            is TextRef.Named      -> ref.name
            is TextRef.AndroidRes -> ref.id.toString()
            is TextRef.Literal    -> ref.text
        }
    }

    private fun rules(metrics: AdvisorMetrics, prefs: AimiPrefsSnapshot) =
        metricRecommendations(metrics, prefs, textResolver)

    @Test
    fun good_metrics_produce_no_recommendation() {
        assertThat(rules(metrics(), prefs())).isEmpty()
    }

    // ----- rule 1: hypos -----

    @Test
    fun hypo_rule_fires_above_four_percent_below_seventy() {
        val recs = rules(metrics(timeBelow70 = 0.05, timeBelow54 = 0.02), prefs())
        assertThat(recs).hasSize(1)
        assertThat(recs[0].title).isEqualTo(ApsStrings.aimi_adv_rec_hypos_title)
        assertThat(recs[0].priority).isEqualTo(AimiPriority.Critical)
        assertThat(recs[0].domain).isEqualTo(AimiDomain.Safety)
    }

    @Test
    fun hypo_rule_is_silent_exactly_at_four_percent_below_seventy() {
        assertThat(rules(metrics(timeBelow70 = 0.04), prefs())).isEmpty()
    }

    @Test
    fun hypo_rule_cuts_max_smb_by_twenty_percent() {
        val recs = rules(metrics(timeBelow70 = 0.05), prefs(maxSmb = 2.0))
        val action = update(recs[0])
        assertThat(action).isNotNull()
        assertThat(action!!.key).isEqualTo(DoubleKey.OApsAIMIMaxSMB)
        assertThat(action.newValue).isEqualTo(1.6)
    }

    @Test
    fun hypo_rule_leaves_an_already_small_max_smb_alone() {
        val recs = rules(metrics(timeBelow70 = 0.05), prefs(maxSmb = 1.5))
        assertThat(recs).hasSize(1)
        assertThat(recs[0].action).isNull()
    }

    // ----- rule 2: control -----

    @Test
    fun control_rule_fires_below_seventy_percent_time_in_range() {
        val recs = rules(metrics(tir70_180 = 0.69), prefs())
        assertThat(recs).hasSize(1)
        assertThat(recs[0].title).isEqualTo(ApsStrings.aimi_adv_rec_control_title)
        assertThat(recs[0].priority).isEqualTo(AimiPriority.High)
        assertThat(recs[0].domain).isEqualTo(AimiDomain.Basal)
    }

    @Test
    fun control_rule_is_silent_exactly_at_seventy_percent_time_in_range() {
        assertThat(rules(metrics(tir70_180 = 0.70), prefs())).isEmpty()
    }

    @Test
    fun control_rule_is_silent_when_hypo_pressure_is_above_three_percent() {
        assertThat(rules(metrics(tir70_180 = 0.60, timeBelow70 = 0.031), prefs())).isEmpty()
    }

    @Test
    fun control_rule_still_fires_exactly_at_three_percent_hypo_pressure() {
        val recs = rules(metrics(tir70_180 = 0.60, timeBelow70 = 0.03), prefs())
        assertThat(recs).hasSize(1)
        assertThat(recs[0].title).isEqualTo(ApsStrings.aimi_adv_rec_control_title)
    }

    /**
     * Pins the bracket fix. The original line was `(lunchFactor + 0.1 * 10.0).roundToInt() / 10.0`,
     * which proposed 0.2 for a lunch factor of 1.0.
     */
    @Test
    fun a_lunch_factor_of_one_proposes_one_point_one() {
        val recs = rules(metrics(tir70_180 = 0.60), prefs(lunchFactor = 1.0))
        val action = update(recs[0])
        assertThat(action).isNotNull()
        assertThat(action!!.key).isEqualTo(DoubleKey.OApsAIMILunchFactor)
        assertThat(action.newValue).isEqualTo(1.1)
    }

    @Test
    fun control_rule_leaves_a_lunch_factor_at_the_ceiling_alone() {
        val recs = rules(metrics(tir70_180 = 0.60), prefs(lunchFactor = 1.2))
        assertThat(recs).hasSize(1)
        assertThat(recs[0].action).isNull()
    }

    // ----- rule 3: hypers -----

    @Test
    fun hyper_rule_fires_above_twenty_percent_over_one_eighty() {
        val recs = rules(metrics(timeAbove180 = 0.21), prefs())
        assertThat(recs).hasSize(1)
        assertThat(recs[0].title).isEqualTo(ApsStrings.aimi_adv_rec_hypers_title)
        assertThat(recs[0].priority).isEqualTo(AimiPriority.Medium)
        assertThat(recs[0].domain).isEqualTo(AimiDomain.Isf)
        assertThat(recs[0].action).isNull()
    }

    @Test
    fun hyper_rule_is_silent_exactly_at_twenty_percent_over_one_eighty() {
        assertThat(rules(metrics(timeAbove180 = 0.20), prefs())).isEmpty()
    }

    @Test
    fun hyper_rule_is_silent_when_hypo_pressure_is_above_three_percent() {
        assertThat(rules(metrics(timeAbove180 = 0.30, timeBelow70 = 0.031), prefs())).isEmpty()
    }

    // ----- rule 4: basal dominance -----

    @Test
    fun basal_rule_fires_above_fifty_five_percent_basal_share() {
        val recs = rules(metrics(basalPercent = 0.56), prefs())
        assertThat(recs).hasSize(1)
        assertThat(recs[0].title).isEqualTo(ApsStrings.aimi_adv_rec_basal_title)
        assertThat(recs[0].priority).isEqualTo(AimiPriority.Medium)
        assertThat(recs[0].domain).isEqualTo(AimiDomain.Basal)
        assertThat(recs[0].action).isNull()
    }

    @Test
    fun basal_rule_is_silent_exactly_at_fifty_five_percent_basal_share() {
        assertThat(rules(metrics(basalPercent = 0.55), prefs())).isEmpty()
    }

    @Test
    fun the_rules_keep_their_original_order() {
        val recs = rules(
            metrics(tir70_180 = 0.50, timeBelow70 = 0.05, timeAbove180 = 0.30, basalPercent = 0.60),
            prefs(),
        )
        // Rules 2 and 3 need low hypo pressure, so only rules 1 and 4 can fire together with a hypo.
        assertThat(recs.map { it.title }).containsExactly(
            ApsStrings.aimi_adv_rec_hypos_title,
            ApsStrings.aimi_adv_rec_basal_title,
        ).inOrder()
    }

    // ----- the reason of an action is resolved, not written out in code -----

    @Test
    fun hypo_action_takes_its_reason_from_the_resolver() {
        val action = update(rules(metrics(timeBelow70 = 0.05), prefs(maxSmb = 2.0))[0])
        assertThat(action!!.reason).isEqualTo("aimi_adv_rec_hypos_action_reason")
    }

    @Test
    fun control_action_takes_its_reason_from_the_resolver() {
        val action = update(rules(metrics(tir70_180 = 0.60), prefs(lunchFactor = 1.0))[0])
        assertThat(action!!.reason).isEqualTo("aimi_adv_rec_control_action_reason")
    }

    @Test
    fun a_missing_resolver_still_gives_the_action_a_reason() {
        val action = update(metricRecommendations(metrics(timeBelow70 = 0.05), prefs(maxSmb = 2.0), null)[0])
        assertThat(action!!.reason).isNotEmpty()
    }
}
