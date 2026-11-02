package app.aaps.plugins.source.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.source.EversenseCalibrationSource
import app.aaps.core.ui.compose.MetroAppCompatActivity
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.eversense.EversenseCGMPlugin
import app.aaps.plugins.eversense.callbacks.EversenseWatcher
import app.aaps.plugins.eversense.enums.CalibrationReadiness
import app.aaps.plugins.eversense.enums.EversenseType
import app.aaps.plugins.eversense.models.ActiveAlarm
import app.aaps.plugins.eversense.models.EversenseCGMResult
import app.aaps.plugins.eversense.models.EversenseState
import app.aaps.plugins.eversense.util.EversenseLogger
import app.aaps.plugins.source.R
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Settings -> Eversense -> Calibration. Sends a fingerstick value straight to the transmitter.
 *
 * Ported from dev_OAPSAIMI with the same behaviour. The bound check uses the same numbers as the
 * quick launch dialog, from [EversenseCalibrationSource].
 */
class EversenseCalibrationActivity : MetroAppCompatActivity() {

    @Inject lateinit var profileUtil: ProfileUtil

    private val mainHandler = Handler(Looper.getMainLooper())
    private var connectionWatcher: EversenseWatcher? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Other apps may not draw over this screen: an overlay could hide what a tap really does,
        // here a calibration that changes every later reading. Same as CgmDriverLogActivity.
        window.setHideOverlayWindows(true)
        setContentView(R.layout.activity_eversense_calibration)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.eversense_calibration_action)

        val state = EversenseCGMPlugin.instance.getCurrentState()
        EversenseLogger.info(TAG, "Activity opened - readiness: ${state?.calibrationReadiness}, phase: ${state?.calibrationPhase}, connected: ${EversenseCGMPlugin.instance.isConnected()}")

        val statusText = findViewById<TextView>(R.id.calibration_status)
        statusText.text = when {
            state == null                                            -> getString(R.string.eversense_not_connected)
            state.calibrationReadiness == CalibrationReadiness.READY -> getString(R.string.eversense_calibration_ready)
            else                                                     -> readinessMessage(state.calibrationReadiness)
        }

        val unitLabel = findViewById<TextView>(R.id.calibration_unit_label)
        unitLabel.text = profileUtil.units.asText

        val bgInput = findViewById<EditText>(R.id.calibration_bg_input)
        bgInput.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        bgInput.isEnabled = state?.calibrationReadiness == CalibrationReadiness.READY

        val submitButton = findViewById<Button>(R.id.calibration_submit_button)
        submitButton.isEnabled = state?.calibrationReadiness == CalibrationReadiness.READY

        // Refresh the button and the status whenever the transmitter state changes while the screen is open
        val stateWatcher = object : EversenseWatcher {
            override fun onStateChanged(state: EversenseState) {
                mainHandler.post {
                    val ready = state.calibrationReadiness == CalibrationReadiness.READY
                    submitButton.isEnabled = ready
                    bgInput.isEnabled = ready
                    statusText.text = readinessMessage(state.calibrationReadiness)
                    EversenseLogger.info(TAG, "State updated - readiness: ${state.calibrationReadiness}")
                }
            }

            override fun onTransmitterReady() {}
            override fun onConnectionChanged(connected: Boolean) {}
            override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {}
            override fun onAlarmReceived(alarm: ActiveAlarm) {}
            override fun onTransmitterNotPlaced() {}
        }
        EversenseCGMPlugin.instance.addWatcher(stateWatcher)
        connectionWatcher = stateWatcher

        submitButton.setOnClickListener {
            val rawInput = bgInput.text.toString()
            EversenseLogger.info(TAG, "Submit pressed - raw input: '$rawInput', units: ${profileUtil.units.asText}")
            val bgValue = rawInput.toDoubleOrNull()
            if (bgValue == null || bgValue <= 0) {
                EversenseLogger.warning(TAG, "Invalid calibration value - bgValue: $bgValue")
                Toast.makeText(this, getString(R.string.eversense_calibration_invalid_value), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // roundToInt, not toInt: truncating would turn the displayed low bound of 2.2 mmol/L
            // (39.6 mg/dL) into 39 and reject it. Rounding also keeps every other mmol entry as
            // close as possible to what the user typed.
            val bgMgDl = profileUtil.convertToMgdl(bgValue, profileUtil.units).roundToInt()
            if (bgMgDl < EversenseCalibrationSource.MIN_CALIBRATION_MGDL || bgMgDl > EversenseCalibrationSource.MAX_CALIBRATION_MGDL) {
                EversenseLogger.warning(TAG, "Calibration value out of range - bgMgDl: $bgMgDl")
                Toast.makeText(
                    this,
                    getString(
                        CoreUiR.string.eversense_calibration_value_out_of_range,
                        profileUtil.fromMgdlToStringWithUnits(EversenseCalibrationSource.MIN_CALIBRATION_MGDL.toDouble()),
                        profileUtil.fromMgdlToStringWithUnits(EversenseCalibrationSource.MAX_CALIBRATION_MGDL.toDouble())
                    ),
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            EversenseLogger.info(TAG, "Calibration submitting - bgValue: $bgValue, bgMgDl: $bgMgDl")

            submitButton.isEnabled = false
            statusText.text = getString(R.string.eversense_calibration_sending)

            if (EversenseCGMPlugin.instance.isConnected()) {
                sendCalibration(bgMgDl, submitButton, statusText)
            } else {
                EversenseLogger.info(TAG, "Not connected - triggering reconnect before calibration")
                statusText.text = getString(R.string.eversense_reconnecting)
                reconnectThenCalibrate(bgMgDl, submitButton, statusText)
            }
        }
    }

    private fun reconnectThenCalibrate(bgMgDl: Int, submitButton: Button, statusText: TextView) {
        var reconnected = false

        val watcher = object : EversenseWatcher {
            override fun onTransmitterReady() {
                if (!reconnected) {
                    reconnected = true
                    EversenseLogger.info(TAG, "Transmitter ready after full sync - proceeding with calibration")
                    mainHandler.post {
                        statusText.text = getString(R.string.eversense_calibration_sending)
                    }
                    sendCalibration(bgMgDl, submitButton, statusText)
                }
            }

            override fun onConnectionChanged(connected: Boolean) {}
            override fun onStateChanged(state: EversenseState) {}
            override fun onCGMRead(type: EversenseType, readings: List<EversenseCGMResult>) {}
            override fun onAlarmReceived(alarm: ActiveAlarm) {}
            override fun onTransmitterNotPlaced() {}
        }

        connectionWatcher = watcher
        EversenseCGMPlugin.instance.addWatcher(watcher)
        EversenseCGMPlugin.instance.connect(null)

        // Give up when the transmitter is not ready in time
        mainHandler.postDelayed({
            if (!reconnected) {
                EversenseLogger.warning(TAG, "Transmitter ready timed out after ${RECONNECT_TIMEOUT_MS / 1000}s - calibration aborted")
                EversenseCGMPlugin.instance.removeWatcher(watcher)
                connectionWatcher = null
                mainHandler.post {
                    submitButton.isEnabled = true
                    statusText.text = getString(R.string.eversense_calibration_ready)
                    Toast.makeText(this, getString(R.string.eversense_calibration_failed), Toast.LENGTH_LONG).show()
                }
            }
        }, RECONNECT_TIMEOUT_MS)
    }

    private fun sendCalibration(bgMgDl: Int, submitButton: Button, statusText: TextView) {
        Thread {
            connectionWatcher?.let {
                EversenseCGMPlugin.instance.removeWatcher(it)
                connectionWatcher = null
            }
            EversenseLogger.info(TAG, "Calibration thread started - sending $bgMgDl mg/dL to transmitter")
            val success = EversenseCGMPlugin.instance.sendCalibration(bgMgDl)
            EversenseLogger.info(TAG, "Calibration result - success: $success")
            if (success) {
                EversenseLogger.info(TAG, "Triggering fullSync after successful calibration")
                EversenseCGMPlugin.instance.triggerFullSync(force = true)
            }
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, getString(R.string.eversense_calibration_success), Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    submitButton.isEnabled = true
                    statusText.text = getString(R.string.eversense_calibration_ready)
                    Toast.makeText(this, getString(R.string.eversense_calibration_failed), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun readinessMessage(readiness: CalibrationReadiness): String = getString(
        when (readiness) {
            CalibrationReadiness.READY                    -> R.string.eversense_calibration_ready
            CalibrationReadiness.NOT_ENOUGH_DATA          -> R.string.eversense_readiness_not_enough_data
            CalibrationReadiness.GLUCOSE_TOO_HIGH         -> R.string.eversense_readiness_glucose_too_high
            CalibrationReadiness.TOO_SOON                 -> R.string.eversense_readiness_too_soon
            CalibrationReadiness.DROPOUT_PHASE            -> R.string.eversense_readiness_dropout_phase
            CalibrationReadiness.SENSOR_EOL               -> R.string.eversense_readiness_sensor_eol
            CalibrationReadiness.NO_SENSOR_LINKED         -> R.string.eversense_readiness_no_sensor_linked
            CalibrationReadiness.UNSUPPORTED_MODE         -> R.string.eversense_readiness_unsupported_mode
            CalibrationReadiness.WAITING_POST_CALIBRATION -> R.string.eversense_readiness_waiting_post_calibration
            CalibrationReadiness.LED_DISCONNECT_DETECTED  -> R.string.eversense_readiness_led_disconnect
            CalibrationReadiness.TRANSMITTER_EOL          -> R.string.eversense_readiness_transmitter_eol
            CalibrationReadiness.UNKNOWN                  -> R.string.eversense_readiness_unknown
        }
    )

    override fun onDestroy() {
        super.onDestroy()
        connectionWatcher?.let {
            EversenseCGMPlugin.instance.removeWatcher(it)
            connectionWatcher = null
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private companion object {

        const val TAG = "EversenseCalibration"
        const val RECONNECT_TIMEOUT_MS = 30_000L
    }
}
