package app.aaps.implementation.glucose

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever

class GlucoseCorrectionImplTest : TestBaseWithProfile() {

    @Mock lateinit var autosensDataStore: AutosensDataStore

    private lateinit var sut: GlucoseCorrectionImpl

    private val head = 1_700_000_000_000L
    private val fiveMin = T.mins(5).msecs()

    @BeforeEach
    fun setup() {
        whenever(iobCobCalculator.ads).thenReturn(autosensDataStore)
        sut = GlucoseCorrectionImpl(iobCobCalculator)
    }

    /** Corrected series, newest first, five minutes apart. */
    private fun series(vararg corrected: Double) = corrected
        .mapIndexed { i, value -> InMemoryGlucoseValue(timestamp = head - i * fiveMin, value = 100.0, smoothed = value) }
        .toMutableList()

    private fun givenSeries(vararg corrected: Double) {
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(series(*corrected))
    }

    @Test
    fun `no corrected series keeps the stored value`() {
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(null)
        assertThat(sut.correctedMgdl(head, 56.0)).isNull()
    }

    @Test
    fun `empty corrected series keeps the stored value`() {
        whenever(autosensDataStore.getBucketedDataTableCopy()).thenReturn(mutableListOf())
        assertThat(sut.correctedMgdl(head, 56.0)).isNull()
    }

    @Test
    fun `reading on the newest point takes the value the dashboard shows`() {
        givenSeries(65.0, 70.0, 75.0)
        assertThat(sut.correctedMgdl(head, 56.0)).isEqualTo(65.0)
    }

    @Test
    fun `reading newer than the newest point still takes the newest value`() {
        // A one minute sensor sends readings between two points of the five minute series.
        givenSeries(65.0, 70.0, 75.0)
        assertThat(sut.correctedMgdl(head + T.mins(2).msecs(), 52.0)).isEqualTo(65.0)
    }

    @Test
    fun `reading between two points is interpolated`() {
        givenSeries(65.0, 75.0)
        // Halfway between the point at now-5min (75) and the point at now (65).
        assertThat(sut.correctedMgdl(head - T.mins(2).msecs() - T.secs(30).msecs(), 60.0)).isEqualTo(70.0)
    }

    @Test
    fun `reading exactly on an older point takes that point`() {
        givenSeries(65.0, 75.0, 85.0)
        assertThat(sut.correctedMgdl(head - 2 * fiveMin, 70.0)).isEqualTo(85.0)
    }

    @Test
    fun `reading older than the whole series keeps the stored value`() {
        givenSeries(65.0, 75.0, 85.0)
        assertThat(sut.correctedMgdl(head - 3 * fiveMin, 90.0)).isNull()
    }

    @Test
    fun `stored value of zero keeps the stored value`() {
        givenSeries(65.0)
        assertThat(sut.correctedMgdl(head, 0.0)).isNull()
    }

    @Test
    fun `correction far below the plausible range is refused`() {
        givenSeries(20.0)
        assertThat(sut.correctedMgdl(head, 56.0)).isNull()
    }

    @Test
    fun `correction far above the plausible range is refused`() {
        givenSeries(450.0)
        assertThat(sut.correctedMgdl(head, 300.0)).isNull()
    }

    @Test
    fun `correction more than double the stored value is refused`() {
        givenSeries(160.0)
        assertThat(sut.correctedMgdl(head, 60.0)).isNull()
    }

    @Test
    fun `correction below half the stored value is refused`() {
        givenSeries(90.0)
        assertThat(sut.correctedMgdl(head, 200.0)).isNull()
    }

    // ---- whole mg/dL on the way out -------------------------------------------------------------

    /**
     * The bug seen on Nightscout on 2026-09-13: `sgv` read 83.17856343415423 while the phone showed
     * 83. Both sensors build their reading from an `Int`, so before calibration existed the value
     * sent out was always whole and nothing on the way out ever rounded.
     */
    @Test
    fun `an interpolated reading comes back whole`() {
        givenSeries(80.0, 90.0)
        // Two minutes past the older point of a five minute span: 90 - (2 / 5) * 10 = 86.0 exactly,
        // so shift by one more second to land on a value that is not whole.
        val value = sut.correctedMgdl(head - T.mins(3).msecs() + T.secs(1).msecs(), 85.0)
        assertThat(value).isNotNull()
        assertThat(value).isEqualTo(value!!.toLong().toDouble())
    }

    @Test
    fun `a corrected value with decimals is rounded to the nearest whole number`() {
        givenSeries(83.17856343415423)
        assertThat(sut.correctedMgdl(head, 83.0)).isEqualTo(83.0)

        givenSeries(83.6)
        assertThat(sut.correctedMgdl(head, 83.0)).isEqualTo(84.0)
    }

    /** Rounding happens after the plausibility net, so the net keeps working on the exact value. */
    @Test
    fun `rounding does not let an implausible correction through`() {
        // 38.6 is under the 39 floor. Rounding it first would have made it 39 and let it pass.
        givenSeries(38.6)
        assertThat(sut.correctedMgdl(head, 56.0)).isNull()
    }

    @Test
    fun `rounding does not change a correction that is already whole`() {
        givenSeries(65.0, 70.0, 75.0)
        assertThat(sut.correctedMgdl(head, 56.0)).isEqualTo(65.0)
    }

    @Test
    fun `series without smoothing falls back to the calibrated value`() {
        whenever(autosensDataStore.getBucketedDataTableCopy())
            .thenReturn(mutableListOf(InMemoryGlucoseValue(timestamp = head, value = 56.0, calibrated = 65.0)))
        assertThat(sut.correctedMgdl(head, 56.0)).isEqualTo(65.0)
    }

    @Test
    fun `series without calibration and smoothing returns the plain value`() {
        whenever(autosensDataStore.getBucketedDataTableCopy())
            .thenReturn(mutableListOf(InMemoryGlucoseValue(timestamp = head, value = 56.0)))
        assertThat(sut.correctedMgdl(head, 56.0)).isEqualTo(56.0)
    }
}
