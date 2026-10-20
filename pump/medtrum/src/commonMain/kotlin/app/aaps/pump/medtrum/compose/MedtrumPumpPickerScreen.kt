package app.aaps.pump.medtrum.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.aaps.core.interfaces.pump.ble.ScannedDevice
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.pump.BleScanStep
import app.aaps.core.ui.compose.stringResource
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * Choose the Medtrum pump and keep its serial number.
 *
 * This is the one step that stands between a phone and a patch that is already running: the driver
 * matches the pump by serial in every advertisement, so without it nothing can connect. The pump
 * is not typed in, it is read out of the advertisement during a scan, which is also how Android
 * does it in its patch wizard.
 *
 * Nothing here starts a patch. Filling, priming and activating are a different flow and a pump
 * that has never been activated will not answer this scan.
 *
 * Everything is hoisted, so the screen holds no driver of its own: [devices] is what the scan has
 * seen so far, [onSelect] is what keeps one. That is what lets an iPhone and an Android phone show
 * the same screen over two different Bluetooth stacks.
 *
 * @param devices pumps seen so far, already carrying the serial as their address text.
 * @param chosenSerialHex the serial kept from an earlier visit, or null when none was chosen.
 * @param bluetoothOff true when the scan could not even start, so the empty list is explained.
 */
@Composable
fun MedtrumPumpPickerScreen(
    devices: List<ScannedDevice>,
    chosenSerialHex: String?,
    bluetoothOff: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelect: (ScannedDevice) -> Unit,
    onBack: () -> Unit
) {
    AapsTheme {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = if (chosenSerialHex.isNullOrEmpty()) stringResource(MedtrumStrings.medtrum_no_pump_chosen)
                else stringResource(MedtrumStrings.medtrum_chosen_pump, chosenSerialHex),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(AapsTheme.spacing.extraLarge)
            )
            BleScanStep(
                devices = devices,
                onSelectDevice = onSelect,
                onStartScan = onStartScan,
                onStopScan = onStopScan,
                onCancel = onBack,
                // The same filter the Android wizard uses: a Medtrum base advertises as MT-xxxx.
                deviceNameFilter = Regex("^MT"),
                title = stringResource(MedtrumStrings.medtrum_pick_pump_title),
                subtitle = if (bluetoothOff) stringResource(MedtrumStrings.medtrum_bluetooth_off)
                else stringResource(MedtrumStrings.medtrum_pick_pump_summary)
            )
        }
    }
}
