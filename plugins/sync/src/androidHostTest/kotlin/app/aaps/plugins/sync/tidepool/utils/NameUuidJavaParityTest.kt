package app.aaps.plugins.sync.tidepool.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

/** [NameUuid] against the Java implementation it replaces, on many names of every length. */
class NameUuidJavaParityTest {

    @Test
    fun sameAsJavaForManyNames() {
        val random = Random(42)
        val names = buildList {
            for (length in 0..130) add(String(CharArray(length) { 'a' + random.nextInt(26) }))
            for (i in 0 until 300) add("AAPS-cgm" + random.nextLong(1_500_000_000_000L, 2_000_000_000_000L))
            add("AAPS-note-event1700000000000")
            add("ü€ñ漢字")
        }
        for (name in names) {
            assertThat(NameUuid.fromName(name)).isEqualTo(UUID.nameUUIDFromBytes(name.toByteArray()).toString())
        }
    }
}
