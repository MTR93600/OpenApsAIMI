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
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.R
import androidx.preference.PreferenceManager

class AimiModeSettingsActivity : TranslatedDaggerAppCompatActivity() {

    // Removed Inject to avoid Dagger graph issues with new Activity
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private var selectedMode = ModeType.LUNCH

    private lateinit var lunchButton: TextView
    private lateinit var dinnerButton: TextView
    
    private lateinit var inputPrebolus1: EditText
    private lateinit var inputPrebolus2: EditText
    private lateinit var inputReactivity: EditText
    private lateinit var inputInterval: EditText

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

        // Save Button
        val saveBtn = Button(this).apply {
            text = "SAVE SETTINGS"
            textSize = 16f
            setTextColor(Color.WHITE)
            setBackgroundColor(accentColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 120).apply {
                topMargin = 64
            }
            setOnClickListener { saveValues() }
        }
        container.addView(saveBtn)

        setContentView(mainScroll)

        // Functionality
        lunchButton.setOnClickListener { switchMode(ModeType.LUNCH) }
        dinnerButton.setOnClickListener { switchMode(ModeType.DINNER) }

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

    private fun saveValues() {
        val p1 = inputPrebolus1.text.toString()
        val p2 = inputPrebolus2.text.toString()
        val react = inputReactivity.text.toString()
        val interv = inputInterval.text.toString()

        val editor = prefs.edit()

        if (selectedMode == ModeType.LUNCH) {
            editor.putString(DoubleKey.OApsAIMILunchPrebolus.key, p1)
            editor.putString(DoubleKey.OApsAIMILunchPrebolus2.key, p2)
            editor.putString(DoubleKey.OApsAIMILunchFactor.key, react)
            editor.putString(IntKey.OApsAIMILunchinterval.key, interv)
        } else {
            editor.putString(DoubleKey.OApsAIMIDinnerPrebolus.key, p1)
            editor.putString(DoubleKey.OApsAIMIDinnerPrebolus2.key, p2)
            editor.putString(DoubleKey.OApsAIMIDinnerFactor.key, react)
            editor.putString(IntKey.OApsAIMIDinnerinterval.key, interv)
        }
        editor.apply()
        
        finish()
    }

    private fun getStringPref(key: DoubleKey): String {
        return try {
            prefs.getString(key.key, key.defaultValue.toString()) ?: key.defaultValue.toString()
        } catch (e: Exception) {
            key.defaultValue.toString()
        }
    }

    private fun getStringPref(key: IntKey): String {
         return try {
            prefs.getString(key.key, key.defaultValue.toString()) ?: key.defaultValue.toString()
        } catch (e: Exception) {
            key.defaultValue.toString()
        }
    }
}
