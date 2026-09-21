package app.aaps.plugins.aps.openAPSAIMI.retention

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AimiTimestampKeyTest {

    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun readsTheValueOfItsOwnKey() {
        val key = AimiTimestampKey("timestamp")
        val line = bytes("""{"event_id":"a","timestamp":1758240000000,"trigger":"loop"}""")

        assertThat(key.extract(line)).isEqualTo(1758240000000L)
    }

    @Test
    fun readsWallMsForTheBlackbox() {
        val key = AimiTimestampKey("wall_ms")
        val line = bytes("""{"type":"loop_pulse","wall_ms":1758240000000,"uptime_ms":42}""")

        assertThat(key.extract(line)).isEqualTo(1758240000000L)
    }

    @Test
    fun returnsNullWhenTheKeyIsAbsent() {
        val key = AimiTimestampKey("timestamp")

        assertThat(key.extract(bytes("""{"type":"loop_pulse","wall_ms":17}"""))).isNull()
    }

    @Test
    fun returnsNullWhenTheValueIsNotANumber() {
        val key = AimiTimestampKey("timestamp")

        assertThat(key.extract(bytes("""{"timestamp":"2026-09-19T00:00:00Z"}"""))).isNull()
    }

    @Test
    fun returnsNullOnAPrefixCutBeforeTheDigits() {
        val key = AimiTimestampKey("timestamp")

        assertThat(key.extract(bytes("""{"event_id":"a","timestamp":"""))).isNull()
    }

    @Test
    fun ignoresAKeyThatIsOnlyASuffixOfAnotherKey() {
        val key = AimiTimestampKey("timestamp")
        val line = bytes("""{"parent_timestamp":111,"timestamp":222}""")

        assertThat(key.extract(line)).isEqualTo(222L)
    }
}
