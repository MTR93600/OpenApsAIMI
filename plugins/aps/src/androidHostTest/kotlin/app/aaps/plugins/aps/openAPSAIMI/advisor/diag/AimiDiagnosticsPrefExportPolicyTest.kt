package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AimiDiagnosticsPrefExportPolicyTest {

    @Test
    fun `advisor api keys are secret keys`() {
        assertTrue(AimiDiagnosticsPrefExportPolicy.isSecretPreferenceKey("aimi_advisor_openai_key"))
        assertTrue(AimiDiagnosticsPrefExportPolicy.isSecretPreferenceKey("aimi_advisor_gemini_key"))
        assertTrue(AimiDiagnosticsPrefExportPolicy.isSecretPreferenceKey("aimi_remote_control_pin"))
    }

    @Test
    fun `routine aimi prefs are not secret keys`() {
        assertFalse(AimiDiagnosticsPrefExportPolicy.isSecretPreferenceKey("key_oaps_aimi_meal_interval"))
        assertFalse(AimiDiagnosticsPrefExportPolicy.isSecretPreferenceKey("key_openapsaimi_max_smb"))
    }

    @Test
    fun `sk and AIza string values are redacted`() {
        assertEquals(
            "***REDACTED***",
            AimiDiagnosticsPrefExportPolicy.formatExportValue("safe_key_name", "sk-proj-abcdefghijklmnopqrstuvwxyz")
        )
        assertEquals(
            "***REDACTED***",
            AimiDiagnosticsPrefExportPolicy.formatExportValue("other", "AIzaSyA0P1nxroP_WIOI0xZYP8E9XOSFAZ2vNLo")
        )
    }

    @Test
    fun `secret key omits raw value entirely`() {
        assertEquals("***REDACTED***", AimiDiagnosticsPrefExportPolicy.formatExportValue("aimi_advisor_openai_key", "x"))
    }
}
