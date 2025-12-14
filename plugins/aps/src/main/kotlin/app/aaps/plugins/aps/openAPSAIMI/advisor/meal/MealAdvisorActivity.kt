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
    
    private lateinit var recognitionService: FoodRecognitionService
    private val REQUEST_IMAGE_CAPTURE = 1

    private lateinit var resultText: TextView
    private lateinit var reasoningText: TextView
    private lateinit var confirmButton: Button
    private lateinit var imageView: ImageView

    private var currentEstimate: FoodRecognitionService.EstimationResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        recognitionService = FoodRecognitionService(this, preferences)
        title = "AIMI Meal Advisor"

        // UI Setup (Code Layout)
        val bgColor = Color.parseColor("#10141C")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(bgColor)
            gravity = Gravity.CENTER_HORIZONTAL
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
            // Apply a simple white text style for spinner items if possible or use default
            // For programmatic spinner with dark theme, we might need a custom adapter or accept default.
            // Using default for now.
            background.setColorFilter(Color.WHITE, android.graphics.PorterDuff.Mode.SRC_ATOP)
        }
        
        val providers = arrayOf("OpenAI (GPT-4o)", "Gemini (1.5 Flash)")
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, providers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // Set initial selection
        val currentProvider = preferences.get(app.aaps.core.keys.StringKey.AimiAdvisorProvider)
        if (currentProvider == "GEMINI") {
            spinner.setSelection(1)
        } else {
            spinner.setSelection(0)
        }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
             override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                 val selected = if (position == 1) "GEMINI" else "OPENAI"
                 preferences.put(app.aaps.core.keys.StringKey.AimiAdvisorProvider, selected)
                 // Ensure text color is readable if default adapter is used
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
            setImageResource(android.R.drawable.ic_menu_camera) // Placeholder
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

        // 3. Result Card
        resultText = TextView(this).apply {
            text = "No food analyzed yet."
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
        }
        layout.addView(resultText)

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
        layout.addView(reasoningText)

        // 4. Confirm Button (Hidden initially)
        confirmButton = Button(this).apply {
            text = "✅ Confirm & Inject to FCL"
            setBackgroundColor(Color.parseColor("#10B981")) // Green
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 48 }
            visibility = android.view.View.GONE
            setOnClickListener { confirmEstimate() }
        }
        layout.addView(confirmButton)

        setContentView(layout)
    }

    private fun dispatchTakePictureIntent() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.CAMERA), 100)
        } else {
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
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

                // Update UI
                resultText.text = "${result.carbsGrams.toInt()}g Carbs\n${result.description}"
                reasoningText.text = result.reasoning
                confirmButton.visibility = android.view.View.VISIBLE
                confirmButton.text = "✅ Confirm ${result.carbsGrams.toInt()}g"

            } catch (e: Exception) {
                resultText.text = "Error: ${e.message}"
            }
        }
    }

    private fun confirmEstimate() {
        val estimate = currentEstimate ?: return
        
        // Inject into Preferences for FCL
        // We need to define these keys in DoubleKey/LongKey.
        // Assuming keys will be added: OApsAIMILastEstimatedCarbs, OApsAIMILastEstimatedCarbTime
        
        // Using safe fallback if keys not yet compiled, but plan requires adding them first.
        // I will trust the keys will be added in the next step.
        // Writing code knowing keys are:
        
        preferences.put(DoubleKey.OApsAIMILastEstimatedCarbs, estimate.carbsGrams)
        preferences.put(DoubleKey.OApsAIMILastEstimatedCarbTime, System.currentTimeMillis().toDouble())
        
        Toast.makeText(this, "Injected! FCL will now target this rise.", Toast.LENGTH_LONG).show()
        finish()
    }
}
