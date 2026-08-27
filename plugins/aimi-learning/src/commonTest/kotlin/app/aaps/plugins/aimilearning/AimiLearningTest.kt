package app.aaps.plugins.aimilearning

import kotlin.test.Test
import kotlin.test.assertEquals

class AimiLearningTest {

    @Test
    fun hello_is_a_fixed_string_with_no_aimi_logic() {
        assertEquals("aimi-learning", AimiLearning.hello())
    }
}
