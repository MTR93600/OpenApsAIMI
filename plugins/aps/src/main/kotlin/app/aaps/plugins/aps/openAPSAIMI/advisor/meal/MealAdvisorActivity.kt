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
    private lateinit var detailsLayout: LinearLayout

    private var currentEstimate: EstimationResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        recognitionService = FoodRecognitionService(this, preferences)
        title = "AIMI Meal Advisor"

        // UI Setup (Code Layout)
        val bgColor = Color.parseColor("#10141C")

        // 1. Create the ScrollView (Parent)
        val scrollView = android.widget.ScrollView(this).apply {
             layoutParams = android.view.ViewGroup.LayoutParams(
                 android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                 android.view.ViewGroup.LayoutParams.MATCH_PARENT
             )
             isFillViewport = true
             setBackgroundColor(bgColor)
        }

        // 2. Create the Content Layout (Child of ScrollView)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor)
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusableInTouchMode = true // Clear focus on touch
        }

        // 0. Provider Selector
        val providerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }
        }

        val providerLabel = TextView(this).apply {
            text = "AI Model: "
            setTextColor(Color.WHITE)
        }
        providerLayout.addView(providerLabel)

        val spinner = android.widget.Spinner(this).apply {
            background.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
        
        // All supported vision providers
        val providers = arrayOf(
            "OpenAI (GPT-4o Vision)", 
            "Gemini (2.0 Flash)", 
            "DeepSeek (Chat)",
            "Claude (3.5 Sonnet)"
        )
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Set initial selection based on saved preference
        val currentProvider = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorProvider)
        val initialPosition = when (currentProvider.uppercase()) {
            "OPENAI" -> 0
            "GEMINI" -> 1
            "DEEPSEEK" -> 2
            "CLAUDE" -> 3
            else -> 0
        }
        spinner.setSelection(initialPosition)

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
             override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                 val selected = when (position) {
                     0 -> "OPENAI"
                     1 -> "GEMINI"
                     2 -> "DEEPSEEK"
                     3 -> "CLAUDE"
                     else -> "OPENAI"
                 }
                 preferences.put(app.aaps.core.keys.StringKey.AimiAdvisorProvider, selected)
                 (view as? TextView)?.setTextColor(Color.WHITE)
             }
             override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        providerLayout.addView(spinner)
        layout.addView(providerLayout)

        // 1. Photo Area
        imageView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(600, 600)
            setBackgroundColor(Color.parseColor("#1E293B"))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_camera)
        }
        layout.addView(imageView)

        // 2. Action Button
        val snapButton = Button(this).apply {
            text = "📷 Take Food Photo"
            setBackgroundColor(Color.parseColor("#3B82F6"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 32 }
            setOnClickListener { dispatchTakePictureIntent() }
        }
        layout.addView(snapButton)
        
        // --- Details Section (Hidden until result) ---
        detailsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
        }

        // 3. Result Display
        resultText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        detailsLayout.addView(resultText)

        // 4. Carbs Editor
        val carbEditLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                 LinearLayout.LayoutParams.MATCH_PARENT,
                 LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }
        }
        
        val carbLabel = TextView(this).apply { 
            text = "Carbs (g): "
            setTextColor(Color.LTGRAY)
        }
        
        carbsInput = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setTextColor(Color.WHITE)
            setEms(4)
            gravity = Gravity.CENTER
            setText("0")
            addTextChangedListener(object : android.text.TextWatcher {
                 override fun afterTextChanged(s: android.text.Editable?) { recalculateProposal() }
                 override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                 override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
        }
        
        carbEditLayout.addView(carbLabel)
        carbEditLayout.addView(carbsInput)
        detailsLayout.addView(carbEditLayout)

        reasoningText = TextView(this).apply {
            text = ""
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        detailsLayout.addView(reasoningText)

        // 5. Confirm Button
        confirmButton = Button(this).apply {
            text = "✅ Confirm"
            setBackgroundColor(Color.parseColor("#10B981")) // Green
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
            setOnClickListener { confirmInjection() }
        }
        detailsLayout.addView(confirmButton)
        
        layout.addView(detailsLayout)

        // Add content to ScrollView
        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun dispatchTakePictureIntent() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 100)
        } else {
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            // Force Back Camera (Try multiple extras as manufacturers differ)
            takePictureIntent.putExtra("android.intent.extras.CAMERA_FACING", 0)
            takePictureIntent.putExtra("android.intent.extras.LENS_FACING_FRONT", 0)
            takePictureIntent.putExtra("android.intent.extra.USE_FRONT_CAMERA", false)

            if (takePictureIntent.resolveActivity(packageManager) != null) {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE)
            } else {
                Toast.makeText(this, "No Camera App found", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                dispatchTakePictureIntent()
            } else {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            val imageBitmap = data?.extras?.get("data") as Bitmap
            imageView.setImageBitmap(imageBitmap)
            simulateAnalysis(imageBitmap)
        }
    }

    // Mock simulation for prototype consistency without camera hardware
    private fun simulateAnalysis(bitmap: Bitmap) {
        Toast.makeText(this, "Analyzing image (AI Vision)...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                // Call Service
                val result = recognitionService.estimateCarbsFromImage(bitmap)
                currentEstimate = result

                // Calculate Total Effective (Carbs + FPU)
                val totalEffective = result.carbsGrams + result.fpuEquivalent

                // Update UI
                resultText.text = result.description
                carbsInput.setText(totalEffective.toInt().toString())
                reasoningText.text = result.reasoning
                
                detailsLayout.visibility = android.view.View.VISIBLE
                recalculateProposal() // Update button text immediately

            } catch (e: Exception) {
                resultText.text = "Error: ${e.message}"
                detailsLayout.visibility = android.view.View.VISIBLE
            }
        }
    }
    
    // 🔧 Recalculate suggested insulin dynamically
    private fun recalculateProposal() {
        try {
            val carbs = carbsInput.text.toString().toDoubleOrNull() ?: 0.0
            val profile = profileFunction.getProfile()
            
            // Get CR (Carb Ratio) - handling variants
            val cr = if (profile != null) {
                 val ratio = profile.getIc()
                 if (ratio > 0.1) ratio else 10.0 // Fail-safe
            } else 10.0
            
            val insulin = carbs / cr
            
            confirmButton.text = "✅ Inject ${carbs.toInt()}g Carbs\n(Prop: %.1f U @ CR %.1f)".format(insulin, cr)
        } catch (e: Exception) {
            confirmButton.text = "✅ Confirm"
        }
    }

    private fun confirmInjection() {
        val carbsVal = carbsInput.text.toString().toDoubleOrNull() ?: return
        
        if (carbsVal <= 0.0) {
             Toast.makeText(this, "Please enter valid carbs", Toast.LENGTH_SHORT).show()
             return
        }

        // 1. Insert Real Carbs into DB
        val now = System.currentTimeMillis()
        val ca = app.aaps.core.data.model.CA(
             timestamp = now, // Valid now
             isValid = true,
             amount = carbsVal,
             duration = 0, // 0 = Let Profile Decide / Calculator
             notes = "AIMI Advisor: Snap & Go",
             ids = app.aaps.core.data.model.IDs()
        )
        
        // Blocking Get for safety in this synchronous UI flow
        try {
            persistenceLayer.insertOrUpdateCarbs(
                 ca, 
                 app.aaps.core.data.ue.Action.TREATMENT, 
                 app.aaps.core.data.ue.Sources.CarbDialog, 
                 ca.notes
            ).blockingGet()
        } catch (e: Exception) {
            Toast.makeText(this, "DB Error: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Set Trigger Preferences for DetermineBasalAIMI2
        preferences.put(app.aaps.core.keys.BooleanKey.OApsAIMIMealAdvisorTrigger, true)
        
        // Also update legacy prefs for redundancy/display
        preferences.put(DoubleKey.OApsAIMILastEstimatedCarbs, carbsVal)
        preferences.put(DoubleKey.OApsAIMILastEstimatedCarbTime, now.toDouble())
        
        Toast.makeText(this, "Injected ${carbsVal.toInt()}g + Triggered SMB/TBR Logic", Toast.LENGTH_LONG).show()
        finish()
    }
}
