package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GateKind
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GatingResult
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The gate must say why it stayed shut in a form that can be counted.
 *
 * Measured on 2026-09-07: the gate was shut on 884 ticks out of 1351, and nothing exported said why.
 * Two thirds of the day had no explanation, and every field the barrier writes only exists on the
 * other third. `reason` cannot fill that gap on its own: it embeds the live glucose and trend, so
 * every string is unique and no two ticks ever group together.
 */
class AutoDriveGaterKindTest {

    @Test
    fun `every kind is distinct, so counting them means something`() {
        val kinds = GateKind.entries
        assertThat(kinds.map { it.name }.toSet()).hasSize(kinds.size)
        assertThat(kinds).containsAtLeast(
            GateKind.HR_HIGH,
            GateKind.ACTIVITY,
            GateKind.RISE_TOO_WEAK,
            GateKind.HIGH_PLATEAU,
            GateKind.MEAL_AWARE_RISE,
            GateKind.STRONG_RISE,
        )
    }

    /** The reason stays human text; the kind is what a count can rely on. */
    @Test
    fun `two disengaged ticks share a kind even when their reasons differ`() {
        val first = GatingResult(engage = false, reason = "🧘 BG stable (BG=101.0, Trend=0.2)", kind = GateKind.RISE_TOO_WEAK)
        val second = GatingResult(engage = false, reason = "🧘 BG stable (BG=118.0, Trend=0.9)", kind = GateKind.RISE_TOO_WEAK)

        assertThat(first.reason).isNotEqualTo(second.reason)
        assertThat(first.kind).isEqualTo(second.kind)
    }

    /** The default keeps the old two-argument call sites compiling, and it is a shut gate. */
    @Test
    fun `the default kind is the common disengaged case`() {
        assertThat(GatingResult(engage = false, reason = "test").kind).isEqualTo(GateKind.RISE_TOO_WEAK)
    }
}
