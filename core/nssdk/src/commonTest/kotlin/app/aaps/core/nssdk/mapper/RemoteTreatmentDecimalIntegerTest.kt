package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import app.aaps.core.nssdk.nsSdkJson
import app.aaps.core.nssdk.remotemodel.RemoteTreatment
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integral decimals in the integer fields of a treatment. Gson accepted `30.0` and refused `30.5`.
 * kotlinx refused both, and one such field used to fail the whole page.
 *
 * In commonTest, so the iOS client reads these numbers the same way.
 */
class RemoteTreatmentDecimalIntegerTest {

    private fun parse(json: String): RemoteTreatment =
        nsSdkJson.decodeFromString(RemoteTreatment.serializer(), json)

    @Test
    fun duration30point0DecodesAs30() {
        val parsed = parse("""{"eventType":"Temp Basal","date":1785990980390,"duration":30.0,"absolute":1.0,"identifier":"tbr"}""")

        assertEquals(30L, parsed.duration)
        val treatment = parsed.toTreatment()
        assertTrue(treatment is NSTemporaryBasal)
        assertEquals(1_800_000L, treatment.duration)
    }

    @Test
    fun pumpIdDecimalDecodes() {
        assertEquals(1785992181495L, parse("""{"pumpId":1785992181495.0}""").pumpId)
    }

    @Test
    fun proteinDecimalDecodes() {
        assertEquals(12, parse("""{"protein":12.0}""").protein)
    }

    @Test
    fun duration30point5RejectsDocument() {
        assertFailsWith<SerializationException> { parse("""{"duration":30.5}""") }
    }

    @Test
    fun encodeWritesPlainInteger() {
        val written = nsSdkJson.encodeToString(
            RemoteTreatment.serializer(),
            RemoteTreatment(duration = 30, pumpId = 4102, protein = 12)
        )

        assertTrue("\"duration\":30" in written)
        assertTrue("\"duration\":30." !in written)
        assertTrue("\"pumpId\":4102" in written)
        assertTrue("\"protein\":12" in written)

        val again = nsSdkJson.encodeToString(
            RemoteTreatment.serializer(),
            parse("""{"duration":30.0,"pumpId":4102.0,"protein":12.0}""")
        )
        assertTrue("\"duration\":30" in again)
        assertTrue("30.0" !in again)
    }

    /** A plain integer, a numeric string, an explicit null and an absent field stay as they were. */
    @Test
    fun plainIntegersStringsAndNullsAreUnchanged() {
        val parsed = parse("""{"duration":30,"durationInMilliseconds":"1800000","protein":null,"pumpId":"4102"}""")

        assertEquals(30L, parsed.duration)
        assertEquals(1_800_000L, parsed.durationInMilliseconds)
        assertNull(parsed.protein)
        assertEquals(4102L, parsed.pumpId)

        val absent = parse("""{"eventType":"Note"}""")
        assertNull(absent.duration)
        assertNull(absent.fat)
        assertNull(absent.utcOffset)
        assertNull(absent.preBolus)
    }

    /** The other integer fields named for this change, in one document. */
    @Test
    fun listedIntegerFieldsAcceptAnIntegralDecimal() {
        val parsed = parse(
            """{"utcOffset":120.0,"fat":8.0,"durationInMilliseconds":1800000.0,"preBolus":15.0,"splitNow":50.0,"splitExt":50.0,"endId":99.0}"""
        )

        assertEquals(120L, parsed.utcOffset)
        assertEquals(8, parsed.fat)
        assertEquals(1_800_000L, parsed.durationInMilliseconds)
        assertEquals(15, parsed.preBolus)
        assertEquals(50, parsed.splitNow)
        assertEquals(50, parsed.splitExt)
        assertEquals(99L, parsed.endId)
    }
}
