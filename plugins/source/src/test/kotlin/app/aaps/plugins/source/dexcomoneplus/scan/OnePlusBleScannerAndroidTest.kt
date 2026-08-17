package app.aaps.plugins.source.dexcomoneplus.scan

import android.bluetooth.le.ScanCallback
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OnePlusBleScannerAndroidTest {

    @Test
    fun `nameMatches accepts DX02 DX01 DXCM family only`() {
        assertThat(OnePlusBleScannerAndroid.nameMatches("DXCM12")).isTrue()
        assertThat(OnePlusBleScannerAndroid.nameMatches("DX02aS")).isTrue()
        assertThat(OnePlusBleScannerAndroid.nameMatches("DX01xx")).isTrue()
        assertThat(OnePlusBleScannerAndroid.nameMatches("DexcomONE")).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("Dexcom65")).isFalse()
    }

    @Test
    fun `one silent scan window is not enough to back off`() {
        val state = OnePlusBleScannerAndroid.nextSilentScanState(previousSilentScans = 0, heardAnything = false)

        assertThat(state.silentScans).isEqualTo(1)
        assertThat(state.backOff).isFalse()
    }

    @Test
    fun `two silent windows in a row trigger the cool-down and restart the counter`() {
        val first = OnePlusBleScannerAndroid.nextSilentScanState(previousSilentScans = 0, heardAnything = false)
        val second = OnePlusBleScannerAndroid.nextSilentScanState(first.silentScans, heardAnything = false)

        assertThat(second.backOff).isTrue()
        assertThat(second.silentScans).isEqualTo(0)
    }

    @Test
    fun `a still blind scanner backs off again after the next pair of silent windows`() {
        // The back-off must be repeatable, not a one-shot: a sensor out of range for hours keeps the
        // app scanning less until it answers again.
        var silent = 0
        val backOffs = (1..4).count {
            val state = OnePlusBleScannerAndroid.nextSilentScanState(silent, heardAnything = false)
            silent = state.silentScans
            state.backOff
        }

        assertThat(backOffs).isEqualTo(2)
    }

    @Test
    fun `hearing any advertisement clears the silent counter`() {
        val state = OnePlusBleScannerAndroid.nextSilentScanState(previousSilentScans = 1, heardAnything = true)

        assertThat(state.silentScans).isEqualTo(0)
        assertThat(state.backOff).isFalse()
    }

    @Test
    fun `filtered silence does not itself request a probe until five windows`() {
        assertThat(OnePlusBleScannerAndroid.shouldRunUnfilteredThrottleProbe(0)).isFalse()
        assertThat(OnePlusBleScannerAndroid.shouldRunUnfilteredThrottleProbe(2)).isFalse()
        assertThat(OnePlusBleScannerAndroid.shouldRunUnfilteredThrottleProbe(4)).isFalse()
        assertThat(OnePlusBleScannerAndroid.shouldRunUnfilteredThrottleProbe(5)).isTrue()
        assertThat(OnePlusBleScannerAndroid.shouldRunUnfilteredThrottleProbe(10)).isTrue()
    }

    @Test
    fun `scan-failed back-off is the OS refusal codes only`() {
        assertThat(
            OnePlusBleScannerAndroid.shouldBackOffOnScanFailed(
                ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED,
            ),
        ).isTrue()
        assertThat(
            OnePlusBleScannerAndroid.shouldBackOffOnScanFailed(
                ScanCallback.SCAN_FAILED_ALREADY_STARTED,
            ),
        ).isFalse()
    }

    @Test
    fun `nameMatches rejects unrelated names`() {
        assertThat(OnePlusBleScannerAndroid.nameMatches(null)).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("")).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("Eversense365")).isFalse()
        assertThat(OnePlusBleScannerAndroid.nameMatches("Galaxy Watch")).isFalse()
    }

    @Test
    fun `target address is normalized for hardware scan filter`() {
        assertThat(
            OnePlusBleScannerAndroid.normalizeTargetAddress("da:3b:12:0d:5b:b7"),
        ).isEqualTo("DA:3B:12:0D:5B:B7")
    }

    @Test
    fun `FEBC neighbours count as G7 family unless they carry a G6 marketing name`() {
        // G7 / ONE+ frequently advertise without a local name.
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement(null)).isTrue()
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement("DX021H")).isTrue()
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement("DXCM99")).isTrue()
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement("Dexcom65")).isFalse()
        assertThat(OnePlusBleScannerAndroid.isG7FamilyAdvertisement("Galaxy Watch")).isFalse()
    }

    @Test
    fun `wait result exposes the strongest foreign transmitter for the stale-MAC diagnostic`() {
        val weak = OnePlusScanResult(address = "AA:BB:CC:DD:EE:01", name = "DX021H", rssi = -88)
        val strong = OnePlusScanResult(address = "AA:BB:CC:DD:B8:92", name = "DX021H", rssi = -51)

        val result = OnePlusAdvWaitResult(target = null, foreign = listOf(weak, strong))

        assertThat(result.strongestForeign()).isEqualTo(strong)
        assertThat(OnePlusAdvWaitResult().strongestForeign()).isNull()
    }
}
