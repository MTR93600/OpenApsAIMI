package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class UamInputSchemaValidatorTest {

    @Test
    fun expected_feature_count_uses_last_dimension_for_batched_tensor_shapes() {
        val expected = UamInputSchemaValidator.expectedFeatureCount(intArrayOf(1, 18))

        assertThat(expected).isEqualTo(18)
    }

    @Test
    fun mismatch_reason_is_explicit_for_runtime_logs() {
        val reason = UamInputSchemaValidator.mismatchReason(expectedCount = 18, actualCount = 15)

        assertThat(reason).contains("expected 18 features, got 15")
    }
}
