package app.aaps.plugins.aps.openAPSAIMI.advisor.meal

import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Space
import androidx.lifecycle.lifecycleScope
import app.aaps.core.ui.activities.TranslatedDaggerAppCompatActivity
import app.aaps.plugins.aps.R
import app.aaps.core.keys.interfaces.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import javax.inject.Inject
import app.aaps.core.keys.DoubleKey

/**
 * Meal Advisor UI: "Snap & Go"
 * Allows user to take a photo, estimates carbs, and injects into AIMI.
 */
class MealAdvisorActivity : TranslatedDaggerAppCompatActivity() {

    @Inject lateinit var preferences: Preferences
    @Inject lateinit var persistenceLayer: app.aaps.core.interfaces.db.PersistenceLayer
    @Inject lateinit var profileFunction: app.aaps.core.interfaces.profile.ProfileFunction
    @Inject lateinit var dateUtil: app.aaps.core.interfaces.utils.DateUtil
    
    private lateinit var recognitionService: FoodRecognitionService
    private val REQUEST_IMAGE_CAPTURE = 1

    private lateinit var resultText: TextView
    private lateinit var reasoningText: TextView
    private lateinit var confirmButton: Button
    private lateinit var imageView: ImageView
    private lateinit var carbsInput: android.widget.EditText
    private lateinit var descriptionInput: android.widget.EditText
    private lateinit var detailsLayout: LinearLayout
    
    // V2 Enhanced UI Views
    private lateinit var confidenceBadge: TextView
    private lateinit var macroSummaryText: TextView
    private lateinit var visibleItemsList: TextView
    private lateinit var recReasonText: TextView
    private lateinit var riskWarningText: TextView

    private var currentEstimate: EstimationResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        recognitionService = FoodRecognitionService(this, preferences)
        title = "AIMI Meal Advisor"

        val bgColor = Color.parseColor("#0F172A") // Deep Slate
        val cardColor = Color.parseColor("#1E293B")
        val accentColor = Color.parseColor("#3B82F6") // Blue

        val scrollView = android.widget.ScrollView(this).apply {
             layoutParams = android.view.ViewGroup.LayoutParams(-1, -1)
             isFillViewport = true
             setBackgroundColor(bgColor)
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // --- Model Selector ---
        val providerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(32, 16, 32, 16)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = 24f
            }
        }
        
        providerLayout.addView(TextView(this).apply {
            text = "Model: "
            setTextColor(Color.GRAY)
            textSize = 12f
        })

        val spinner = android.widget.Spinner(this).apply {
            val providers = arrayOf("OpenAI GPT-4o", "Gemini 3.0", "DeepSeek Chat", "Claude 3.5")
            adapter = android.widget.ArrayAdapter(this@MealAdvisorActivity, android.R.layout.simple_spinner_item, providers).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            background.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
        
        val initialProvider = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorProvider)
        spinner.setSelection(when (initialProvider.uppercase()) {
            "OPENAI" -> 0; "GEMINI" -> 1; "DEEPSEEK" -> 2; "CLAUDE" -> 3; else -> 0
        })

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                val p = when (pos) { 0 -> "OPENAI"; 1 -> "GEMINI"; 2 -> "DEEPSEEK"; 3 -> "CLAUDE"; else -> "OPENAI" }
                preferences.put(app.aaps.core.keys.StringKey.AimiAdvisorProvider, p)
                (v as? TextView)?.setTextColor(Color.WHITE)
            }
            override fun onNothingSelected(p0: android.widget.AdapterView<*>?) {}
        }
        providerLayout.addView(spinner)
        mainLayout.addView(providerLayout)

        // --- Image Area ---
        imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(700, 700).apply { topMargin = 48 }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = 32f
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_camera)
            setPadding(20, 20, 20, 20)
        }
        mainLayout.addView(imageView)

        descriptionInput = android.widget.EditText(this).apply {
            hint = "Optional context (e.g. 'Whole wheat pasta')"
            setHintTextColor(Color.DKGRAY)
            setTextColor(Color.WHITE)
            textSize = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = 16f
            }
            setPadding(32, 24, 32, 24)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 32 }
        }
        mainLayout.addView(descriptionInput)

        val snapButton = Button(this).apply {
            text = "📷 ANALYZE MEAL"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(accentColor)
                cornerRadius = 24f
            }
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, 140).apply { topMargin = 32 }
            setOnClickListener { dispatchTakePictureIntent() }
        }
        mainLayout.addView(snapButton)

        // --- Results Section ---
        detailsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 48 }
        }

        confidenceBadge = TextView(this).apply {
            setPadding(24, 8, 24, 8)
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER }
        }
        detailsLayout.addView(confidenceBadge)

        resultText = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 8)
        }
        detailsLayout.addView(resultText)

        macroSummaryText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }
        detailsLayout.addView(macroSummaryText)

        // Divider
        detailsLayout.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 2).apply { setMargins(0, 16, 0, 32) }
            setBackgroundColor(Color.parseColor("#334155"))
        })

        // Inputs
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        inputRow.addView(TextView(this).apply { text = "Carbs: "; setTextColor(Color.GRAY) })
        carbsInput = android.widget.EditText(this).apply {
            inputType = 8194
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = null
            setEms(3)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { recalculateProposal() }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
        inputRow.addView(carbsInput)
        inputRow.addView(TextView(this).apply { text = " g"; setTextColor(Color.GRAY) })
        detailsLayout.addView(inputRow)

        recReasonText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(32, 8, 32, 24)
        }
        detailsLayout.addView(recReasonText)

        riskWarningText = TextView(this).apply {
            setTextColor(Color.parseColor("#F87171"))
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            visibility = android.view.View.GONE
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#451A1A"))
                cornerRadius = 12f
            }
        }
        detailsLayout.addView(riskWarningText)

        visibleItemsList = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            setPadding(32, 24, 32, 24)
        }
        detailsLayout.addView(visibleItemsList)

        reasoningText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(32, 0, 32, 32)
        }
        detailsLayout.addView(reasoningText)

        confirmButton = Button(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = 24f
            }
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(-1, 160).apply { topMargin = 32 }
            setOnClickListener { confirmInjection() }
        }
        detailsLayout.addView(confirmButton)

        mainLayout.addView(detailsLayout)
        scrollView.addView(mainLayout)
        setContentView(scrollView)
    }

    private fun dispatchTakePictureIntent() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != 0) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 100)
        } else {
            startActivityForResult(Intent(this, MealAdvisorCameraActivity::class.java), REQUEST_IMAGE_CAPTURE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.openInputStream(uri)?.use { 
                    android.graphics.BitmapFactory.decodeStream(it)?.let { bmp ->
                        imageView.setImageBitmap(bmp)
                        simulateAnalysis(bmp)
                    }
                }
            } ?: (data?.extras?.get("data") as? Bitmap)?.let { bmp ->
                imageView.setImageBitmap(bmp)
                simulateAnalysis(bmp)
            }
        }
    }

    private fun simulateAnalysis(bitmap: Bitmap) {
        val userDesc = descriptionInput.text.toString()
        Toast.makeText(this, "AI Analysis in progress...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val result = recognitionService.estimateCarbsFromImage(bitmap, userDesc)
                currentEstimate = result
                updateUIWithResult(result)
            } catch (e: Exception) {
                Toast.makeText(this@MealAdvisorActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUIWithResult(result: EstimationResult) {
        resultText.text = result.description
        carbsInput.setText(result.recommendedCarbsForDose.toInt().toString())
        
        val macroStr = "P: ${result.protein.estimate.toInt()}g | F: ${result.fat.estimate.toInt()}g | FPU: ${result.fpuEquivalent}"
        macroSummaryText.text = macroStr
        
        recReasonText.text = result.recommendedCarbsReason
        reasoningText.text = "Rationale: ${result.reasoning}"
        
        val items = result.visibleItems.joinToString("\n") { "• ${it.name} (${it.amountInfo})" }
        visibleItemsList.text = "Identified:\n$items"

        // Confidence Badge
        val (color, text) = when(result.confidence.uppercase()) {
            "HIGH" -> Color.parseColor("#065F46") to "HIGH CONFIDENCE"
            "MEDIUM" -> Color.parseColor("#92400E") to "MEDIUM CONFIDENCE"
            else -> Color.parseColor("#991B1B") to "LOW CONFIDENCE"
        }
        confidenceBadge.text = text
        confidenceBadge.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = 12f
        }

        // Risks
        if (result.hiddenCarbRisk == "HIGH" || result.needsManualConfirmation) {
            riskWarningText.visibility = android.view.View.VISIBLE
            riskWarningText.text = "⚠️ HIGH RISK: ${result.insulinRelevantNotes.joinToString(", ")}"
        } else {
            riskWarningText.visibility = android.view.View.GONE
        }

        detailsLayout.visibility = android.view.View.VISIBLE
        recalculateProposal()
    }

    private fun recalculateProposal() {
        try {
            val carbs = carbsInput.text.toString().toDoubleOrNull() ?: 0.0
            val profile = profileFunction.getProfile()
            val cr = if (profile != null && profile.getIc() > 0.1) profile.getIc() else 10.0
            val insulin = carbs / cr
            confirmButton.text = "INJECT ${carbs.toInt()}g CARBS\nConsolidated Dose: %.1f U".format(insulin)
        } catch (e: Exception) {
            confirmButton.text = "CONFIRM INJECTION"
        }
    }

    private fun confirmInjection() {
        val valCarbs = carbsInput.text.toString().toDoubleOrNull() ?: return
        if (valCarbs <= 0.0) return

        lifecycleScope.launch(Dispatchers.IO) {
            val ca = app.aaps.core.data.model.CA(
                timestamp = System.currentTimeMillis(),
                isValid = true,
                duration = 0,
                amount = valCarbs,
                notes = "AIMI V2: ${currentEstimate?.description ?: ""}",
                ids = app.aaps.core.data.model.IDs()
            )
            persistenceLayer.insertOrUpdateCarbs(ca, app.aaps.core.data.ue.Action.TREATMENT, app.aaps.core.data.ue.Sources.CarbDialog, ca.notes).blockingGet()
            
            preferences.put(app.aaps.core.keys.BooleanKey.OApsAIMIMealAdvisorTrigger, true)
            preferences.put(DoubleKey.OApsAIMILastEstimatedCarbs, valCarbs)
            
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MealAdvisorActivity, "Carbs recorded. SMB active.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
