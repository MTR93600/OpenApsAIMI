package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ContextParserTest {

    private val parser = ContextParser(mockk<AAPSLogger>(relaxed = true))

    @Test
    fun fatMeal_parsesSlowCarb_notMealRisk() {
        val intents = parser.parse("pizza et frites ce soir")
        assertThat(intents.filterIsInstance<ContextIntent.SlowCarbMeal>()).hasSize(1)
        assertThat(intents.filterIsInstance<ContextIntent.UnannouncedMealRisk>()).isEmpty()
    }

    @Test
    fun hypo_parsesHypoRecovery() {
        val intents = parser.parse("en hypo, resucrage en cours 1h")
        val hypo = intents.filterIsInstance<ContextIntent.HypoRecovery>()
        assertThat(hypo).hasSize(1)
        assertThat(hypo.first().durationMs).isEqualTo(60 * 60_000L)
    }

    @Test
    fun genericMeal_stillParsesMealRisk() {
        val intents = parser.parse("unannounced meal")
        assertThat(intents.filterIsInstance<ContextIntent.UnannouncedMealRisk>()).hasSize(1)
        assertThat(intents.filterIsInstance<ContextIntent.SlowCarbMeal>()).isEmpty()
    }
}
