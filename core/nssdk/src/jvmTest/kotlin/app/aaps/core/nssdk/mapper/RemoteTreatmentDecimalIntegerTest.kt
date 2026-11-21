package app.aaps.core.nssdk.mapper

import app.aaps.core.nssdk.localmodel.treatment.NSTemporaryBasal
import app.aaps.core.nssdk.nsSdkJson
import app.aaps.core.nssdk.remotemodel.RemoteTreatment
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Integral decimals in the integer fields of a treatment. Gson accepted `30.0` and refused `30.5`.
 * kotlinx refused both, and one such field used to fail the whole page.
 */
class RemoteTreatmentDecimalIntegerTest {

    private fun parse(json: String): RemoteTreatment =
        nsSdkJson.decodeFromString(RemoteTreatment.serializer(), json)

    @Test
    fun `duration30point0_decodesAs30`() {
        val parsed = parse("""{"eventType":"Temp Basal","date":1785990980390,"duration":30.0,"absolute":1.0,"identifier":"tbr"}""")

        assertThat(parsed.duration).isEqualTo(30L)
        val treatment = parsed.toTreatment()
        assertThat(treatment).isInstanceOf(NSTemporaryBasal::class.java)
        assertThat((treatment as NSTemporaryBasal).duration).isEqualTo(1_800_000L)
    }

    @Test
    fun `pumpId_decimal_decodes`() {
        assertThat(parse("""{"pumpId":1785992181495.0}""").pumpId).isEqualTo(1785992181495L)
    }

    @Test
    fun `protein_decimal_decodes`() {
        assertThat(parse("""{"protein":12.0}""").protein).isEqualTo(12)
    }

    @Test
    fun `duration30point5_rejectsDocument`() {
        assertThrows<SerializationException> { parse("""{"duration":30.5}""") }
    }

    @Test
    fun `encode_writesPlainInteger`() {
        val written = nsSdkJson.encodeToString(
            RemoteTreatment.serializer(),
            RemoteTreatment(duration = 30, pumpId = 4102, protein = 12)
        )

        assertThat(written).contains("\"duration\":30")
        assertThat(written).doesNotContain("\"duration\":30.")
        assertThat(written).contains("\"pumpId\":4102")
        assertThat(written).contains("\"protein\":12")

        val again = nsSdkJson.encodeToString(
            RemoteTreatment.serializer(),
            parse("""{"duration":30.0,"pumpId":4102.0,"protein":12.0}""")
        )
        assertThat(again).contains("\"duration\":30")
        assertThat(again).doesNotContain("30.0")
    }

    /** A plain integer, a numeric string, an explicit null and an absent field stay as they were. */
    @Test
    fun `plain integers strings and nulls are unchanged`() {
        val parsed = parse("""{"duration":30,"durationInMilliseconds":"1800000","protein":null,"pumpId":"4102"}""")

        assertThat(parsed.duration).isEqualTo(30L)
        assertThat(parsed.durationInMilliseconds).isEqualTo(1_800_000L)
        assertThat(parsed.protein).isNull()
        assertThat(parsed.pumpId).isEqualTo(4102L)

        val absent = parse("""{"eventType":"Note"}""")
        assertThat(absent.duration).isNull()
        assertThat(absent.fat).isNull()
        assertThat(absent.utcOffset).isNull()
        assertThat(absent.preBolus).isNull()
    }

    /** The other integer fields named for this change, in one document. */
    @Test
    fun `listed integer fields accept an integral decimal`() {
        val parsed = parse(
            """{"utcOffset":120.0,"fat":8.0,"durationInMilliseconds":1800000.0,"preBolus":15.0,"splitNow":50.0,"splitExt":50.0,"endId":99.0}"""
        )

        assertThat(parsed.utcOffset).isEqualTo(120L)
        assertThat(parsed.fat).isEqualTo(8)
        assertThat(parsed.durationInMilliseconds).isEqualTo(1_800_000L)
        assertThat(parsed.preBolus).isEqualTo(15)
        assertThat(parsed.splitNow).isEqualTo(50)
        assertThat(parsed.splitExt).isEqualTo(50)
        assertThat(parsed.endId).isEqualTo(99L)
    }
}
