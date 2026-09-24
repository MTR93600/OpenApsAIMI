package app.aaps.core.keys

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The opt-in key for the auditor ISF and target factors.
 *
 * It must stay off until the user turns it on, and it must follow the auditor key: with the auditor
 * off there is nothing to make a proposal.
 */
class AuditorProfileFactorsKeyTest {

    @Test
    fun `the key is off by default`() {
        assertThat(BooleanKey.OApsAIMIAuditorProfileFactors.defaultValue).isFalse()
    }

    @Test
    fun `the stored name never changes`() {
        assertThat(BooleanKey.OApsAIMIAuditorProfileFactors.key)
            .isEqualTo("key_aimi_auditor_profile_factors")
    }

    @Test
    fun `the key depends on the auditor key`() {
        assertThat(BooleanKey.OApsAIMIAuditorProfileFactors.dependency)
            .isEqualTo(BooleanKey.AimiAuditorEnabled)
    }
}
