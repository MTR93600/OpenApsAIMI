package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.core.keys.BooleanKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * The auditor ISF/target factor preference is opt-in and off unless the user turns it on,
 * and it stays hidden until the auditor itself is enabled.
 */
class AuditorProfileFactorsKeyTest {

    @Test
    fun `auditor profile factors default off and depend on the auditor`() {
        val key = BooleanKey.OApsAIMIAuditorProfileFactors
        assertEquals("key_aimi_auditor_profile_factors", key.key)
        assertFalse(key.defaultValue)
        assertSame(BooleanKey.AimiAuditorEnabled, key.dependency)
    }
}
