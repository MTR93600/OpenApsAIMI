package app.aaps.plugins.aimiio

import kotlin.test.Test
import kotlin.test.assertEquals

class AimiIoTest {

    @Test
    fun hello_is_a_fixed_string_with_no_aimi_logic() {
        assertEquals("aimi-io", AimiIo.hello())
    }
}
