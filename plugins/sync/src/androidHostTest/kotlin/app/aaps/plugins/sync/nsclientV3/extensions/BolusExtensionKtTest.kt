package app.aaps.plugins.sync.nsclientV3.extensions

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.ICfg
import app.aaps.core.data.model.IDs
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.nssdk.localmodel.treatment.EventType
import app.aaps.core.nssdk.localmodel.treatment.NSBolus
import app.aaps.core.nssdk.mapper.convertToRemoteAndBack
import app.aaps.plugins.sync.extensions.contentEqualsTo
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

internal class BolusExtensionKtTest : TestBase() {


    val iCfg = ICfg(insulinLabel = "Fake", insulinEndTime = 9 * 3600 * 1000, insulinPeakTime = 60 * 60 * 1000, concentration = 1.0)

    @Test
    fun toBolus() {
        var bolus = BS(
            timestamp = 10000,
            isValid = true,
            amount = 1.0,
            type = BS.Type.SMB,
            notes = "aaaa",
            isBasalInsulin = false,
            ids = IDs(
                nightscoutId = "nightscoutId",
                pumpId = 11000,
                pumpType = PumpType.DANA_I,
                pumpSerial = "bbbb"
            ),
            iCfg = iCfg
        )

        var bolus2 = (bolus.toNSBolus().convertToRemoteAndBack() as NSBolus).toBolus(iCfg)
        assertThat(bolus.contentEqualsTo(bolus2)).isTrue()
        assertThat(bolus.ids.contentEqualsTo(bolus2.ids)).isTrue()

        bolus = BS(
            timestamp = 10000,
            isValid = false,
            amount = 1.0,
            type = BS.Type.NORMAL,
            notes = "aaaa",
            isBasalInsulin = true,
            ids = IDs(
                nightscoutId = "nightscoutId",
                pumpId = 11000,
                pumpType = PumpType.DANA_I,
                pumpSerial = "bbbb"
            ),
            iCfg = iCfg
        )

        bolus2 = (bolus.toNSBolus().convertToRemoteAndBack() as NSBolus).toBolus(iCfg)
        assertThat(bolus.contentEqualsTo(bolus2)).isTrue()
        assertThat(bolus.ids.contentEqualsTo(bolus2.ids)).isTrue()
        assertThat(bolus2.iCfgFromNightscout).isTrue()
    }

    @Test
    fun `toBolus without an insulin curve keeps the fallback and does not mark it as coming from Nightscout`() {
        val fallback = ICfg("client", 18_000_000L, 3_600_000L, 1.0)
        val document = NSBolus(
            date = 10_000L,
            identifier = "nightscoutId",
            utcOffset = 0L,
            isValid = true,
            eventType = EventType.MEAL_BOLUS,
            notes = null,
            pumpId = null,
            endId = null,
            pumpType = null,
            pumpSerial = null,
            insulin = 1.0,
            type = NSBolus.BolusType.NORMAL,
            isBasalInsulin = false,
            iCfg = null
        )

        val bolus = document.toBolus(fallback)

        assertThat(bolus.iCfgFromNightscout).isFalse()
        assertThat(bolus.iCfg).isEqualTo(fallback)
    }
}
