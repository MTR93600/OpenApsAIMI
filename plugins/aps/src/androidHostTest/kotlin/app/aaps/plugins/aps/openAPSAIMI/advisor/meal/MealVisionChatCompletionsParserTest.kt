package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MealVisionChatCompletionsParserTest {

    @Test
    fun `refusal returns secured error result`() {
        val json = """
            {"choices":[{"message":{"refusal":"I cannot analyze medical images.","content":""}}]}
        """.trimIndent()
        val r = MealVisionChatCompletionsParser.parseOpenAiStyleResponse(json, "OpenAI")
        assertEquals("OpenAI Refusal", r.description)
        assertTrue(r.reasoning.contains("medical"))
        assertTrue(r.needsManualConfirmation)
    }

    @Test
    fun `valid content parses through to estimation`() {
        val inner = JSONObject().apply {
            put("food_name", "Rice")
            put("visible_items", JSONArray())
            put("uncertain_items", JSONArray())
            put("carbs_g", JSONObject().put("estimate", 45.0).put("min", 40.0).put("max", 50.0))
            put("protein_g", JSONObject().put("estimate", 4.0).put("min", 3.0).put("max", 5.0))
            put("fat_g", JSONObject().put("estimate", 2.0).put("min", 1.0).put("max", 3.0))
            put("absorption_speed", "MIXED")
            put("glycemic_index", "MEDIUM")
            put("confidence", "HIGH")
            put("portion_confidence", "HIGH")
            put("hidden_carb_risk", "LOW")
            put("needs_manual_confirmation", false)
            put("insulin_relevant_notes", JSONArray())
            put("rationale", "ok")
        }.toString()
        val json = JSONObject().apply {
            put(
                "choices",
                JSONArray().put(
                    JSONObject().put(
                        "message",
                        JSONObject().put("content", inner),
                    ),
                ),
            )
        }.toString()
        val r = MealVisionChatCompletionsParser.parseOpenAiStyleResponse(json, "OpenAI")
        assertEquals("Rice", r.description)
        assertEquals(45.0, r.carbs.estimate, 0.01)
    }
}
