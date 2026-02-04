package app.aaps.plugins.sync.nsShared

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import app.aaps.core.data.model.TE
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginFragment
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventNSClientNewLog
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.plugins.sync.R
import app.aaps.plugins.sync.databinding.FragmentRemoteControlBinding
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import javax.inject.Inject

/**
 * Remote Control Fragment for NSClient Parent App.
 * Allows parents to send secure AIMI commands to child's AAPS via TherapyEvents.
 */
class RemoteControlFragment : DaggerFragment(), PluginFragment {

    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var pinManager: NSClientPinManager

    override var plugin: PluginBase? = null

    private var _binding: FragmentRemoteControlBinding? = null
    private val binding get() = _binding!!
    private val disposable = CompositeDisposable()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRemoteControlBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check if PIN needs configuration
        if (pinManager.needsConfiguration()) {
            showPinSetupDialog()
        }

        setupQuickActions()
        setupAdvancedCommand()
        setupActiveContextsList()
        setupPinInput()
    }

    private fun showPinSetupDialog() {
        context?.let { ctx ->
            OKDialog.show(
                ctx,
                rh.gs(R.string.remote_control_title),
                rh.gs(R.string.remote_pin_setup_required)
            )
            // Show setup card inline
            binding.pinSetupCard.visibility = View.VISIBLE
        }
    }

    private fun setupPinInput() {
        // Initialize button states on startup
        // If PIN is already configured, prompt user to enter it for security
        val pinConfigured = !pinManager.needsConfiguration()
        if (pinConfigured) {
            // PIN exists but user must enter it each session for security
            binding.pinInput.hint = rh.gs(R.string.enter_your_pin)
            updateButtonStates(false) // Buttons disabled until PIN is entered
        } else {
            // PIN not configured, buttons won't work until setup complete
            updateButtonStates(false)
        }

        binding.pinInput.addTextChangedListener {
            // Enable/disable buttons based on PIN entry (not validation yet)
            // Validation happens on command send for security
            val hasPin = !it.isNullOrEmpty() && it.length >= 4
            updateButtonStates(hasPin)
        }

        // PIN Setup Card (if visible)
        binding.btnSavePin.setOnClickListener {
            val newPin = binding.newPinInput.text.toString()
            val confirmPin = binding.confirmPinInput.text.toString()

            when {
                newPin.isEmpty() -> showError(rh.gs(R.string.pin_cannot_be_empty))
                newPin.length < 4 -> showError(rh.gs(R.string.pin_too_short))
                newPin != confirmPin -> showError(rh.gs(R.string.pins_do_not_match))
                else -> {
                    pinManager.savePin(newPin)
                    showSuccess(rh.gs(R.string.pin_saved_successfully))
                    binding.pinSetupCard.visibility = View.GONE
                    // After saving PIN, user should enter it in the main field
                    binding.pinInput.hint = rh.gs(R.string.enter_your_pin)
                    updateButtonStates(false) // Still disabled until user enters PIN
                }
            }
        }
    }

    private fun updateButtonStates(enabled: Boolean) {
        binding.btnSport.isEnabled = enabled
        binding.btnMeal.isEnabled = enabled
        binding.btnStress.isEnabled = enabled
        binding.btnCancelAll.isEnabled = enabled
        binding.btnSendCustom.isEnabled = enabled
    }

    private fun setupQuickActions() {
        binding.btnSport.setOnClickListener {
            val duration = binding.sportDuration.text.toString().toIntOrNull() ?: 60
            sendRemoteCommand("CONTEXT SPORT $duration")
        }

        binding.btnMeal.setOnClickListener {
            val duration = binding.mealDuration.text.toString().toIntOrNull() ?: 30
            sendRemoteCommand("CONTEXT MEAL $duration")
        }

        binding.btnStress.setOnClickListener {
            sendRemoteCommand("CONTEXT STRESS HIGH")
        }

        binding.btnCancelAll.setOnClickListener {
            OKDialog.showConfirmation(
                requireActivity(),
                rh.gs(R.string.confirm_cancel_all_contexts),
                { sendRemoteCommand("CANCEL") }
            )
        }
    }

    private fun setupAdvancedCommand() {
        binding.btnSendCustom.setOnClickListener {
            val customCmd = binding.customCommand.text.toString()
            if (customCmd.isBlank()) {
                showError(rh.gs(R.string.command_cannot_be_empty))
                return@setOnClickListener
            }
            sendRemoteCommand(customCmd)
        }
    }

    private fun setupActiveContextsList() {
        binding.activeContextsList.layoutManager = LinearLayoutManager(context)
        // TODO: Implement adapter to display active contexts from AAPS
        // This would require listening to NSClient data updates
    }

    private fun sendRemoteCommand(command: String) {
        val pin = binding.pinInput.text.toString()

        // Validate PIN
        if (!pinManager.validatePin(pin)) {
            showError(rh.gs(R.string.invalid_pin))
            return
        }

        // Construct full AIMI command
        val fullCommand = "AIMI:$pin $command"

        // Create TherapyEvent (Note type) to trigger remote command
        val note = TE(
            timestamp = dateUtil.now(),
            type = TE.Type.NOTE,
            glucoseUnit = GlucoseUnit.MGDL,
            note = fullCommand
        )

        // Insert into database (will be synced to AAPS via NSClient)
        disposable += persistenceLayer.insertOrUpdateTherapyEvent(note)
            .subscribeOn(aapsSchedulers.io)
            .observeOn(aapsSchedulers.main)
            .subscribe(
                { result ->
                    showSuccess(rh.gs(R.string.command_sent_successfully, command))
                    logCommandSent(fullCommand)
                    // Clear custom command field
                    binding.customCommand.setText("")
                },
                { throwable: Throwable ->
                    aapsLogger.error(LTag.NSCLIENT, "Failed to send remote command", throwable)
                    showError(rh.gs(R.string.command_error, throwable.message ?: "Unknown"))
                }
            )
    }

    private fun logCommandSent(command: String) {
        rxBus.send(EventNSClientNewLog("► REMOTE", "Sent: $command"))
    }

    private fun showSuccess(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private fun showError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        disposable.clear()
    }
}
