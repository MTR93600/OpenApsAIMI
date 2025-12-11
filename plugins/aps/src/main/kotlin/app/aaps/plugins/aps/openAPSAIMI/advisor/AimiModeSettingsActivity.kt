package app.aaps.plugins.aps.openAPSAIMI.advisor

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import android.content.Context
import androidx.preference.PreferenceManager
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.core.interfaces.automation.Automation
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.ui.dialogs.OKDialog
import javax.inject.Inject
import android.os.Handler
import android.os.Looper
import android.widget.Switch


class AimiModeSettingsActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var automation: Automation
    @Inject lateinit var rh: ResourceHelper

    // Removed Inject to avoid Dagger graph issues with new Activity - REVERTED: Now we use Dagger
    // private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    @Inject lateinit var preferences: Preferences
    private val sp by lazy { androidx.preference.PreferenceManager.getDefaultSharedPreferences(this) }

    private var selectedMode = ModeType.LUNCH

    private lateinit var lunchButton: TextView
    private lateinit var dinnerButton: TextView
    
    private lateinit var inputPrebolus1: EditText
    private lateinit var inputPrebolus2: EditText
    private lateinit var inputReactivity: EditText
    private lateinit var inputInterval: EditText

    // AI Settings Inputs
    private lateinit var inputOpenAiKey: EditText
    private lateinit var inputGeminiKey: EditText
    private lateinit var switchProvider: Switch

    private val darkNavy = Color.parseColor("#0F172A")
    private val cardDark = Color.parseColor("#1E293B")
    private val textPrimary = Color.parseColor("#E2E8F0")
    private val textSecondary = Color.parseColor("#94A3B8")
    private val accentColor = Color.parseColor("#6366F1") // Indigo
    private val activeTabColor = Color.parseColor("#334155")

    enum class ModeType { LUNCH, DINNER }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = darkNavy
        
        val mainScroll = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(darkNavy)
            isFillViewport = true
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        mainScroll.addView(container)

        // Title
        val title = TextView(this).apply {
            text = "⚙️ Mode Settings"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 48
            }
        }
        container.addView(title)

        // Mode Toggles (Tabs)
        val toggleContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            // Custom simplified background logic
            setBackgroundColor(cardDark)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 48
            }
        }

        lunchButton = createTabButton("LUNCH 🥗", true)
        dinnerButton = createTabButton("DINNER 🍽️", false)

        toggleContainer.addView(lunchButton)
        toggleContainer.addView(Space(this), LinearLayout.LayoutParams(32, 1))
        toggleContainer.addView(dinnerButton)
        container.addView(toggleContainer)

        // Inputs Card
        val formCard = CardView(this).apply {
            setCardBackgroundColor(cardDark)
            radius = 24f
            cardElevation = 8f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        
        val formLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        inputPrebolus1 = createLabeledInput(formLayout, "Prebolus 1 (U)", "2.5")
        inputPrebolus2 = createLabeledInput(formLayout, "Prebolus 2 (U)", "2.0")
        inputReactivity = createLabeledInput(formLayout, "Reactivity (%)", "100")
        inputInterval = createLabeledInput(formLayout, "Interval (min)", "5")
        (inputInterval.layoutParams as LinearLayout.LayoutParams).bottomMargin = 0

        formCard.addView(formLayout)
        container.addView(formCard)


        // --- Buttons ---
        val buttonPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 2f
            // Custom simplified background logic
            setBackgroundColor(cardDark)
            setPadding(16, 16, 16, 16)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 64
            }
        }

        // Save Button
        val saveBtn = Button(this).apply {
            text = "SAVE SETTINGS"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(accentColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120).apply {
                topMargin = 64
            }
            setOnClickListener { saveValues(true) }
        }
        container.addView(saveBtn)

        // Activate Button
        val activateBtn = Button(this).apply {
            text = "⚡ ACTIVATE ${if (selectedMode == ModeType.LUNCH) "LUNCH" else "DINNER"}" // Initial text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#4ADE80")) // Green text
            // Outlined style simulation
            background = android.graphics.drawable.GradientDrawable().apply {
                 setStroke(4, Color.parseColor("#4ADE80"))
                 cornerRadius = 12f
                 setColor(Color.TRANSPARENT)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120).apply {
                topMargin = 32
                bottomMargin = 64
            }
            setOnClickListener { activateMode() }
        }
        container.addView(activateBtn)
        
        // Helper to update button text on switch
        lunchButton.setOnClickListener { 
            switchMode(ModeType.LUNCH) 
            activateBtn.text = "⚡ ACTIVATE LUNCH"
        }
        dinnerButton.setOnClickListener { 
            switchMode(ModeType.DINNER) 
            activateBtn.text = "⚡ ACTIVATE DINNER"
        }

        setContentView(mainScroll)

        // Functionality
        lunchButton.setOnClickListener { 
            switchMode(ModeType.LUNCH) 
            activateBtn.text = "⚡ ACTIVATE LUNCH"
        }
        dinnerButton.setOnClickListener { 
            switchMode(ModeType.DINNER) 
            activateBtn.text = "⚡ ACTIVATE DINNER"
        }

        // Initial Load
        loadValues(ModeType.LUNCH)
    }

    private fun createTabButton(label: String, active: Boolean): TextView {
        return TextView(this).apply {
            text = label
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (active) Color.WHITE else textSecondary)
            setBackgroundColor(if (active) activeTabColor else Color.TRANSPARENT)
            setPadding(0, 32, 0, 32)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
    }

    private fun createLabeledInput(parent: LinearLayout, labelText: String, hintText: String): EditText {
        val label = TextView(this).apply {
            text = labelText
            textSize = 14f
            setTextColor(textSecondary)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 16
            }
        }
        parent.addView(label)

        val input = EditText(this).apply {
            hint = hintText
            setHintTextColor(Color.parseColor("#475569"))
            setTextColor(Color.WHITE)
            textSize = 18f
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            background = null // Remove underline
            setBackgroundColor(Color.parseColor("#334155")) // Slightly lighter input bg
            setPadding(32, 32, 32, 32)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = 48
            }
        }
        parent.addView(input)
        return input
    }

    private fun getInputBackground(): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12f
            setColor(Color.parseColor("#334155")) // Slightly lighter input bg
        }
    }

    private fun switchMode(mode: ModeType) {
        if (selectedMode == mode) return
        selectedMode = mode
        
        // Update Tabs
        if (mode == ModeType.LUNCH) {
            lunchButton.setBackgroundColor(activeTabColor)
            lunchButton.setTextColor(Color.WHITE)
            dinnerButton.setBackgroundColor(Color.TRANSPARENT)
            dinnerButton.setTextColor(textSecondary)
        } else {
            dinnerButton.setBackgroundColor(activeTabColor)
            dinnerButton.setTextColor(Color.WHITE)
            lunchButton.setBackgroundColor(Color.TRANSPARENT)
            lunchButton.setTextColor(textSecondary)
        }

        loadValues(mode)
    }

    private fun loadValues(mode: ModeType) {
        if (mode == ModeType.LUNCH) {
            inputPrebolus1.setText(getStringPref(DoubleKey.OApsAIMILunchPrebolus))
            inputPrebolus2.setText(getStringPref(DoubleKey.OApsAIMILunchPrebolus2))
            inputReactivity.setText(getStringPref(DoubleKey.OApsAIMILunchFactor))
            inputInterval.setText(getStringPref(IntKey.OApsAIMILunchinterval))
        } else {
            inputPrebolus1.setText(getStringPref(DoubleKey.OApsAIMIDinnerPrebolus))
            inputPrebolus2.setText(getStringPref(DoubleKey.OApsAIMIDinnerPrebolus2))
            inputReactivity.setText(getStringPref(DoubleKey.OApsAIMIDinnerFactor))
            inputInterval.setText(getStringPref(IntKey.OApsAIMIDinnerinterval))
        }
    }

    private fun saveValues(finishAfterSave: Boolean) {
        // 1. Save Mode Settings
        val p1 = inputPrebolus1.text.toString().toDoubleOrNull() ?: 0.0
        val p2 = inputPrebolus2.text.toString().toDoubleOrNull() ?: 0.0
        val react = inputReactivity.text.toString().toDoubleOrNull() ?: 100.0
        val interv = inputInterval.text.toString().toIntOrNull() ?: 5

        if (selectedMode == ModeType.LUNCH) {
            preferences.put(DoubleKey.OApsAIMILunchPrebolus, p1)
            preferences.put(DoubleKey.OApsAIMILunchPrebolus2, p2)
            preferences.put(DoubleKey.OApsAIMILunchFactor, react)
            preferences.put(IntKey.OApsAIMILunchinterval, interv)
        } else {
            preferences.put(DoubleKey.OApsAIMIDinnerPrebolus, p1)
            preferences.put(DoubleKey.OApsAIMIDinnerPrebolus2, p2)
            preferences.put(DoubleKey.OApsAIMIDinnerFactor, react)
            preferences.put(IntKey.OApsAIMIDinnerinterval, interv)
        }

        if (finishAfterSave) {
            finish()
        }
    }

    private fun activateMode() {
        saveValues(false) // Save without closing logic merged.

        // Find the automation event
        val eventTitle = if (selectedMode == ModeType.LUNCH) "Lunch" else "Dinner"
        val events = automation.userEvents()
        val event = events.find { it.title.trim().equals(eventTitle, ignoreCase = true) }
        
        if (event != null) {
            OKDialog.showConfirmation(
                this,
                "Activate $eventTitle mode?"
            ) {
                 Handler(Looper.getMainLooper()).post { 
                     automation.processEvent(event) 
                     finish()
                 }
            }
        } else {
            // Debug info for user
            val available = events.joinToString(", ") { it.title }
            val msg = if (available.isEmpty()) "No automations found." else "Available: $available"
            OKDialog.show(this, "Error: '$eventTitle' not found", msg)
        }
    }

    private fun getStringPref(key: DoubleKey): String {
        return try {
            preferences.get(key).toString()
        } catch (e: Exception) {
            key.defaultValue.toString()
        }
    }

    private fun getStringPref(key: IntKey): String {
         return try {
            preferences.get(key).toString()
        } catch (e: Exception) {
            key.defaultValue.toString()
        }
    }
}
