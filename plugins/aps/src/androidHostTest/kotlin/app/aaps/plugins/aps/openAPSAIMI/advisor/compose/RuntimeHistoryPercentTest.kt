package app.aaps.plugins.aps.openAPSAIMI.advisor.compose

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The share maths behind every "count (percent)" row of the two runtime-history cards. */
class RuntimeHistoryPercentTest {

    @Test
    fun an_empty_window_gives_zero_instead_of_dividing_by_zero() {
        assertThat(percentOf(count = 0, total = 0)).isEqualTo(0)
        assertThat(percentOf(count = 5, total = 0)).isEqualTo(0)
    }

    @Test
    fun a_count_of_zero_gives_zero() {
        assertThat(percentOf(count = 0, total = 24)).isEqualTo(0)
    }

    @Test
    fun a_full_window_gives_one_hundred() {
        assertThat(percentOf(count = 24, total = 24)).isEqualTo(100)
    }

    @Test
    fun a_share_is_rounded_to_the_nearest_percent() {
        // 1/3 is 33.33 and 2/3 is 66.67, so one rounds down and the other rounds up.
        assertThat(percentOf(count = 1, total = 3)).isEqualTo(33)
        assertThat(percentOf(count = 2, total = 3)).isEqualTo(67)
    }

    @Test
    fun a_negative_count_never_prints_a_negative_share() {
        assertThat(percentOf(count = -3, total = 24)).isEqualTo(0)
    }
}
