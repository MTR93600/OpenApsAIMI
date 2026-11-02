package app.aaps.plugins.source.activities

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import app.aaps.core.interfaces.R as InterfacesR
import app.aaps.plugins.eversense.EversenseCGMPlugin
import app.aaps.plugins.eversense.callbacks.EversenseScanCallback
import app.aaps.plugins.eversense.callbacks.EversenseWatcher
import app.aaps.plugins.eversense.enums.EversenseType
import app.aaps.plugins.eversense.models.EversenseCGMResult
import app.aaps.plugins.eversense.models.EversenseScanResult
import app.aaps.plugins.eversense.models.EversenseState
import app.aaps.plugins.source.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Settings -> Eversense -> Status: connection, battery, calibration state, and the buttons to
 * connect, change the transmitter or start a full sync. Ported from dev_OAPSAIMI with the same
 * behaviour. It needs nothing from the graph, so it is a plain AppCompatActivity.
 */
class EversenseStatusActivity : AppCompatActivity(), EversenseWatcher {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val eversense get() = EversenseCGMPlugin.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Other apps may not draw over this screen: an overlay could hide what a tap really does,
        // here a connect or a transmitter change. Same as CgmDriverLogActivity.
        window.setHideOverlayWindows(true)
        setContentView(R.layout.activity_eversense_status)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.eversense_status_title)

        updateStatus()

        findViewById<Button>(R.id.eversense_btn_connect).setOnClickListener {
            handleConnectTap()
        }

        findViewById<Button>(R.id.eversense_btn_change_transmitter).setOnClickListener {
            handleChangeTransmitterTap()
        }

        findViewById<Button>(R.id.eversense_btn_sync).setOnClickListener {
            if (eversense.isConnected()) {
                ioScope.launch { eversense.triggerFullSync(force = true) }
                updateStatus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Safe to call every time: addWatcher() skips a watcher that is already in the list.
        eversense.addWatcher(this)
        // Pick up anything that changed while this screen was not visible.
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        eversense.removeWatcher(this)
    }

    // EversenseWatcher: redraw as soon as the link, the auth handshake or the state changes.
    override fun onConnectionChanged(connected: Boolean) {
        mainHandler.post { updateStatus() }
    }

    override fun onStateChanged(state: EversenseState) {
        mainHandler.post { updateStatus() }
    }

    // isConnected() is "BLE link up AND transmitter ready", two separate flags.
    // onConnectionChanged(true) fires as soon as the raw BLE link comes up, while the auth
    // handshake is still running, so isConnected() is still false and the screen draws the red
    // cross. The second flag flips later, and that moment is reported through onTransmitterReady()
    // only. Without a refresh here the screen keeps showing the red cross until it is reopened.
    override fun onTransmitterReady() {
        mainHandler.post { updateStatus() }
    }

    override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {}

    /** One "label: value" line, through a translatable template. */
    private fun line(@StringRes label: Int, value: String): String =
        getString(InterfacesR.string.confirmation_line, getString(label), value)

    private fun formatDate(timestamp: Long): String = dateFormatter.format(Date(timestamp))

    private fun updateStatus() {
        val state = eversense.getCurrentState()
        val notConnected = getString(R.string.eversense_not_connected)

        findViewById<TextView>(R.id.eversense_status_connected).text =
            line(R.string.eversense_information_connected, getString(if (eversense.isConnected()) R.string.eversense_mark_yes else R.string.eversense_mark_no))
        findViewById<TextView>(R.id.eversense_status_battery).text =
            line(R.string.eversense_status_battery, state?.let { getString(R.string.eversense_percent, it.batteryPercentage) } ?: notConnected)
        findViewById<TextView>(R.id.eversense_status_insertion).text =
            line(R.string.eversense_information_insertion_date, state?.let { formatDate(it.insertionDate) } ?: notConnected)
        findViewById<TextView>(R.id.eversense_status_last_sync).text =
            line(R.string.eversense_status_last_sync, state?.let { formatDate(it.lastSync) } ?: notConnected)
        findViewById<TextView>(R.id.eversense_status_signal).text =
            line(R.string.eversense_placement_signal, state?.let { signalToLabel(it.sensorSignalStrength) } ?: notConnected)
        findViewById<TextView>(R.id.eversense_status_phase).text =
            line(R.string.eversense_status_calibration_phase, state?.calibrationPhase?.name ?: notConnected)
        findViewById<TextView>(R.id.eversense_status_readiness).text =
            line(R.string.eversense_status_calibration_readiness, state?.calibrationReadiness?.name ?: notConnected)
        findViewById<TextView>(R.id.eversense_status_last_cal).text =
            line(R.string.eversense_calibration_last, state?.let { if (it.lastCalibrationDate > 0) formatDate(it.lastCalibrationDate) else notConnected } ?: notConnected)
        findViewById<TextView>(R.id.eversense_status_next_cal).text =
            line(R.string.eversense_calibration_next, state?.let { if (it.nextCalibrationDate > 0) formatDate(it.nextCalibrationDate) else notConnected } ?: notConnected)
        findViewById<Button>(R.id.eversense_btn_connect).text =
            getString(if (eversense.isConnected()) R.string.eversense_disconnect else R.string.eversense_connect)
        findViewById<Button>(R.id.eversense_btn_sync).isEnabled = eversense.isConnected()
    }

    private fun handleConnectTap() {
        if (eversense.isConnected()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.eversense_scan_title))
                .setMessage(getString(R.string.eversense_disconnect_confirm))
                .setPositiveButton(getString(R.string.eversense_disconnect)) { _, _ ->
                    eversense.clearStoredDevice()
                    eversense.disconnect()
                    mainHandler.postDelayed({ updateStatus() }, 500)
                }
                .setNegativeButton(getString(R.string.eversense_scan_cancel), null)
                .show()
        } else {
            val prefs = getSharedPreferences("EversenseCGMManager", Context.MODE_PRIVATE)
            val hasStoredDevice = prefs.getString("eversense_remote_device", null) != null
            if (hasStoredDevice) {
                ioScope.launch { eversense.connect(null) }
                mainHandler.postDelayed({ updateStatus() }, 3000)
            } else {
                showDeviceSelectionDialog()
            }
        }
    }

    // Forgets the stored address of the paired transmitter and scans again. This is the way to
    // move to a replacement transmitter: handleConnectTap() only opens the scan dialog when no
    // address is stored, so with a stored address the app would keep trying the old transmitter
    // forever. The address is cleared BEFORE the disconnect on purpose: scheduleReconnect() in
    // EversenseGattCallback reads that address, and with it already gone it stops instead of
    // scheduling a reconnect to the old transmitter that would race the new one.
    private fun handleChangeTransmitterTap() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.eversense_change_transmitter))
            .setMessage(getString(R.string.eversense_change_transmitter_confirm))
            .setPositiveButton(getString(R.string.eversense_change_transmitter)) { _, _ ->
                eversense.clearStoredDevice()
                if (eversense.isConnected()) eversense.disconnect()
                updateStatus()
                showDeviceSelectionDialog()
            }
            .setNegativeButton(getString(R.string.eversense_scan_cancel), null)
            .show()
    }

    private fun showDeviceSelectionDialog() {
        val foundDevices = mutableListOf<EversenseScanResult>()
        var isCancelled = false
        var dialog: AlertDialog? = null

        val scanCallback = object : EversenseScanCallback {
            override fun onResult(item: EversenseScanResult) {
                if (!isCancelled && item.name.matches(TRANSMITTER_NAME) && foundDevices.none { it.name == item.name })
                    foundDevices.add(item)
            }
        }

        eversense.startScan(scanCallback)

        mainHandler.postDelayed({
            if (isCancelled) return@postDelayed
            eversense.stopScan()
            dialog?.dismiss()
            if (foundDevices.isEmpty()) {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.eversense_scan_title))
                    .setMessage(getString(R.string.eversense_scan_none_found))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.eversense_scan_title))
                    .setItems(foundDevices.map { it.name }.toTypedArray()) { _, position ->
                        ioScope.launch { eversense.connect(foundDevices[position].device) }
                        mainHandler.postDelayed({ updateStatus() }, 3000)
                    }
                    .setNegativeButton(getString(R.string.eversense_scan_cancel), null)
                    .show()
            }
        }, SCAN_DURATION_MS)

        dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.eversense_scan_title))
            .setMessage(getString(R.string.eversense_scan_in_progress))
            .setNegativeButton(getString(R.string.eversense_scan_cancel)) { _, _ ->
                isCancelled = true
                eversense.stopScan()
            }
            .setCancelable(false)
            .show()
    }

    private fun signalToLabel(strength: Int): String = getString(
        when {
            strength >= 75 -> R.string.eversense_signal_excellent
            strength >= 48 -> R.string.eversense_signal_good
            strength >= 30 -> R.string.eversense_signal_low
            strength >= 25 -> R.string.eversense_signal_poor
            strength > 0   -> R.string.eversense_signal_very_poor
            else           -> R.string.eversense_not_connected
        }
    )

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {

        /** Eversense transmitters advertise as "T" followed by digits. */
        val TRANSMITTER_NAME = Regex("T\\d+.*")
        const val SCAN_DURATION_MS = 10_000L
    }
}
