package app.aaps.plugins.aps.openAPSAIMI.context.ui

import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.ui.compose.MetroAppCompatActivity
import app.aaps.core.ui.locale.LocaleHelper
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.databinding.ActivityContextBinding
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.plugins.aps.openAPSAIMI.context.ContextPreset
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilder
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/**
 * Context Activity - UI for Context Module.
 * 
 * Simplified version without ViewModel for quick implementation.
 */
class ContextActivity : MetroAppCompatActivity() {
    
    @Inject lateinit var contextManager: ContextManager
    @Inject lateinit var sp: SP
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var healthContextRepository: HealthContextRepository
    
    private lateinit var binding: ActivityContextBinding
    private lateinit var adapter: ContextIntentAdapter
    
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
    private var patientStateRefreshJob: Job? = null

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityContextBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Toolbar
        title = rh.gs(R.string.context_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        
        setupUI()
        setupPresets()
        refreshUI()
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
        refreshPhysioSnapshot()
        startPatientStateRefresh()
    }

    override fun onPause() {
        patientStateRefreshJob?.cancel()
        patientStateRefreshJob = null
        super.onPause()
    }

    override fun onDestroy() {
        patientStateRefreshJob?.cancel()
        activityScope.cancel()
        super.onDestroy()
    }
    
    private fun setupUI() {
        // RecyclerView
        adapter = ContextIntentAdapter(
            onRemove = { id -> 
                contextManager.removeIntent(id)
                refreshUI()
            },
            onExtend = { id -> showExtendDialog(id) },
            getTimeRemaining = { intent -> getTimeRemaining(intent) },
            getDisplayString = { intent -> getDisplayString(intent) }
        )
        
        binding.recyclerActiveIntents.layoutManager = LinearLayoutManager(this)
        binding.recyclerActiveIntents.adapter = adapter
        
        // Send button
        binding.btnSendChat.setOnClickListener {
            val text = binding.editChatInput.text.toString()
            if (text.isNotBlank()) {
                activityScope.launch {
                    binding.progressParsing.visibility = View.VISIBLE
                    binding.btnSendChat.isEnabled = false
                    
                    try {
                        val ids = contextManager.addIntent(text)
                        
                        if (ids.isNotEmpty()) {
                            binding.editChatInput.text?.clear()
                            Toast.makeText(this@ContextActivity, "${ids.size} contexte(s) ajouté(s)", Toast.LENGTH_SHORT).show()
                        } else {
                            // Feedback detailed on failure
                            val isLLMEnabled = sp.getBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextLLMEnabled.key, false)
                            val provider = sp.getString(app.aaps.core.keys.StringKey.AimiAdvisorProvider.key, "OPENAI")
                            
                            val msg = if (isLLMEnabled) {
                                "Aucun contexte détecté via IA ($provider).\n\nCauses possibles :\n1. Clé API manquante ou invalide (Préférences AIMI > Advisor)\n2. Timeout réseau\n3. Description trop vague\n\nFallback : Essayez des mots-clés simples (ex: 'Sport 1h', 'Malade')."
                            } else {
                                "Aucun contexte détecté via mots-clés.\nEssayez des commandes simples :\n- 'Cardio 1h'\n- 'Malade'\n- 'Stress'\n- 'Repas surprise'"
                            }
                            
                            MaterialAlertDialogBuilder(this@ContextActivity)
                                .setTitle("Analyse échouée")
                                .setMessage(msg)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        }
                        
                        refreshUI()
                    } catch (e: Exception) {
                        Toast.makeText(this@ContextActivity, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
                    } finally {
                        binding.progressParsing.visibility = View.GONE
                        binding.btnSendChat.isEnabled = true
                    }
                }
            }
        }
        
        // Clear button
        binding.btnClearInput.setOnClickListener {
            binding.editChatInput.text?.clear()
        }
        
        // Clear all button
        binding.btnClearAll.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle("Supprimer tous les contextes ?")
                .setPositiveButton("Supprimer") { _, _ ->
                    contextManager.clearAll()
                    refreshUI()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
        
        // Module toggle
        binding.switchContextEnabled.setOnCheckedChangeListener { _, isChecked ->
            sp.putBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled.key, isChecked)
        }
        
        // LLM toggle
        binding.switchLLMEnabled.setOnCheckedChangeListener { _, isChecked ->
            sp.putBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextLLMEnabled.key, isChecked)
        }
    }
    
    private fun setupPresets() {
        if (ContextPreset.ALL_PRESETS.size >= 10) {
            binding.chipCardio.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[0]) }
            binding.chipStrength.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[1]) }
            binding.chipYoga.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[2]) }
            binding.chipSport.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[3]) }
            binding.chipWalking.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[4]) }
            binding.chipSick.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[5]) }
            binding.chipStress.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[6]) }
            binding.chipMealRisk.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[7]) }
            binding.chipAlcohol.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[8]) }
            binding.chipTravel.setOnClickListener { addPreset(ContextPreset.ALL_PRESETS[9]) }
        }
    }
    
    private fun refreshUI() {
        // Get intents
        val intents = contextManager.getAllIntents().toList()
        adapter.submitList(intents)
        
        // Show/hide empty state
        if (intents.isEmpty()) {
            binding.recyclerActiveIntents.visibility = View.GONE
            binding.textEmptyState.visibility = View.VISIBLE
        } else {
            binding.recyclerActiveIntents.visibility = View.VISIBLE
            binding.textEmptyState.visibility = View.GONE
        }
        
        // Settings
        binding.switchContextEnabled.isChecked = sp.getBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextEnabled.key, false)
        binding.switchLLMEnabled.isChecked = sp.getBoolean(app.aaps.core.keys.BooleanKey.OApsAIMIContextLLMEnabled.key, false)
        refreshPatientRuntimeUi()
    }

    private fun refreshPhysioSnapshot() {
        activityScope.launch(Dispatchers.IO) {
            try {
                healthContextRepository.fetchSnapshot()
            } catch (e: Exception) {
                aapsLogger.error(LTag.APS, "ContextActivity physio snapshot refresh failed", e)
            }
        }
    }

    private fun startPatientStateRefresh() {
        patientStateRefreshJob?.cancel()
        patientStateRefreshJob = activityScope.launch {
            launch {
                PatientStateRuntimeRepository.updates.collectLatest {
                    refreshPatientRuntimeUi()
                }
            }
            while (isActive) {
                refreshPatientRuntimeUi()
                delay(60_000L)
            }
        }
    }

    private fun refreshPatientRuntimeUi() {
        val runtimeSnapshot = PatientStateRuntimeRepository.getLatest()
        val presentation = runtimeSnapshot?.let {
            PatientStatePresentationBuilder.build(
                snapshot = it,
                nowMs = System.currentTimeMillis(),
            )
        }
        if (presentation == null) {
            binding.layoutPatientStateDetails.visibility = View.GONE
            binding.textPatientStateEmpty.visibility = View.VISIBLE
            binding.textPatientStateEmpty.text = rh.gs(R.string.context_patient_state_empty)
            return
        }

        binding.layoutPatientStateDetails.visibility = View.VISIBLE
        binding.textPatientStateEmpty.visibility = View.GONE
        binding.textPatientStateUpdated.text = presentation.updatedSummary
        binding.textPatientStateMode.text = presentation.modeHeadline
        binding.textPatientStateNarrative.text = presentation.narrative
        binding.textPatientStateLiveBody.text = presentation.physioLiveSummary
        binding.textPatientStateThermal.text = presentation.thermalSummary
        binding.textPatientStatePhaseValue.text = presentation.physiologySummary
        binding.textPatientStateIntentValue.text = presentation.intentSummary
        binding.textPatientStateSignalsValue.text = presentation.signalSummary
        PatientSignalGaugeBinder.bindAll(
            mealContainer = binding.layoutPatientSignalGaugeMeal.root,
            endogenousContainer = binding.layoutPatientSignalGaugeEndogenous.root,
            resistanceContainer = binding.layoutPatientSignalGaugeResistance.root,
            thermalContainer = binding.layoutPatientSignalGaugeThermal.root,
            sensorContainer = binding.layoutPatientSignalGaugeSensor.root,
            gauges = presentation.signalGauges,
        )
        binding.textPatientStateDeliveryValue.text = presentation.deliverySummary
        binding.textPatientStateReasonsValue.text = presentation.reasonSummary
    }
    
    private fun addPreset(preset: ContextPreset) {
        activityScope.launch {
            try {
                contextManager.addPreset(preset)
                Toast.makeText(this@ContextActivity, "Contexte ajouté", Toast.LENGTH_SHORT).show()
                refreshUI()
            } catch (e: Exception) {
                Toast.makeText(this@ContextActivity, "Erreur: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun showExtendDialog(intentId: String) {
        val options = arrayOf("15 min", "30 min", "1 heure", "2 heures")
        val durations = arrayOf(15, 30, 60, 120)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Prolonger le contexte")
            .setItems(options) { _, which ->
                contextManager.extendDuration(intentId, durations[which].minutes)
                refreshUI()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
    
    private fun getTimeRemaining(intent: app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent): String {
        val now = System.currentTimeMillis()
        val remaining = (intent.endTimeMs - now) / 1000 / 60 // minutes
        
        return when {
            remaining <= 0 -> "Expiré"
            remaining < 60 -> "${remaining}min restantes"
            else -> "${remaining / 60}h ${remaining % 60}min"
        }
    }
    
    private fun getDisplayString(intent: app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent): String {
        return when (intent) {
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.Activity -> 
                "🏃 Activity: ${intent.activityType.name} ${intent.intensity.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.Illness -> 
                "🤒 Illness: ${intent.symptomType.name} ${intent.intensity.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.Stress -> 
                "😰 Stress: ${intent.stressType.name} ${intent.intensity.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.UnannouncedMealRisk -> 
                "🍕 Meal Risk: ${intent.intensity.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.Alcohol -> 
                "🍷 Alcohol: ${intent.units}U ${intent.intensity.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.Travel -> 
                "✈️ Travel: ${intent.intensity.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.MenstrualCycle ->
                "🔄 Cycle: ${intent.phase.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.SlowCarbMeal ->
                "🍕 Repas lent: ${intent.intensity.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.HypoRecovery ->
                "🍬 Hypo récup: ${intent.intensity.name}"
            is app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent.Custom ->
                "📝 ${intent.description}"
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
