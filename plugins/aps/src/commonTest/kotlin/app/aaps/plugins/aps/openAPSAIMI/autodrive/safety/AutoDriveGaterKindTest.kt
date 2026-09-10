package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GateKind
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GatingResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The gate must say why it stayed shut in a form that can be counted.
 *
 * Measured on 2026-09-07: the gate was shut on 884 ticks out of 1351 and nothing exported said why.
 * Two thirds of the day had no explanation and every field the barrier writes only exists on the
 * other third. `reason` cannot fill that gap on its own: it embeds the live glucose and trend so
 * every string is unique and no two ticks ever group together.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `078a51514c` (Kind) then `0ca7744184` (meal-first).
 * Study source set: [AutoDriveGater] is commonMain (ports + Metro only) so these live in
 * `commonTest` (`kotlin.test`) — not `androidHostTest` (mockito/Robolectric). Backtick names omit
 * commas: Kotlin/Native rejects them (see `e34b01f666`).
 */
class AutoDriveGaterKindTest {

    @Test
    fun `every kind is distinct so counting them means something`() {
        val kinds = GateKind.entries
        assertEquals(kinds.size, kinds.map { it.name }.toSet().size)
        assertTrue(
            kinds.containsAll(
                listOf(
                    GateKind.HR_HIGH,
                    GateKind.ACTIVITY,
                    GateKind.RISE_TOO_WEAK,
                    GateKind.HIGH_PLATEAU,
                    GateKind.MEAL_AWARE_RISE,
                    GateKind.STRONG_RISE,
                )
            )
        )
    }

    /** The reason stays human text; the kind is what a count can rely on. */
    @Test
    fun `two disengaged ticks share a kind even when their reasons differ`() {
        val first = GatingResult(engage = false, reason = "🧘 BG stable (BG=101.0, Trend=0.2)", kind = GateKind.RISE_TOO_WEAK)
        val second = GatingResult(engage = false, reason = "🧘 BG stable (BG=118.0, Trend=0.9)", kind = GateKind.RISE_TOO_WEAK)

        assertNotEquals(first.reason, second.reason)
        assertEquals(first.kind, second.kind)
    }

    /** The default keeps the old two-argument call sites compiling and it is a shut gate. */
    @Test
    fun `the default kind is the common disengaged case`() {
        assertEquals(GateKind.RISE_TOO_WEAK, GatingResult(engage = false, reason = "test").kind)
    }
}
