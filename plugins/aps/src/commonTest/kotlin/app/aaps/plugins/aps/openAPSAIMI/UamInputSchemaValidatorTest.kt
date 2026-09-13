package app.aaps.plugins.aps.openAPSAIMI

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the UAM tensor input-count helper used when a batched shape `[1, 18]` must yield 18 features.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `fe96b64f12`
 * (`plugins/aps/src/test/.../UamInputSchemaValidatorTest.kt`). Study source set:
 * [UamInputSchemaValidator] is commonMain with no File/Android, so the tests live in
 * `commonTest` (`kotlin.test`) — same layout as [AimiSmbCorpusGuardTest], not `androidHostTest`.
 *
 * Adaptations: `org.junit.jupiter` + Truth → `kotlin.test`; snake_case names → backticks without
 * commas (Kotlin/Native). No production change.
 */
class UamInputSchemaValidatorTest {

    @Test
    fun `expected feature count uses last dimension for batched tensor shapes`() {
        val expected = UamInputSchemaValidator.expectedFeatureCount(intArrayOf(1, 18))

        assertEquals(18, expected)
    }

    @Test
    fun `mismatch reason is explicit for runtime logs`() {
        val reason = UamInputSchemaValidator.mismatchReason(expectedCount = 18, actualCount = 15)

        assertTrue(
            "expected 18 features, got 15" in reason,
            "reason=$reason",
        )
    }
}
