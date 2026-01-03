# 🎯 CONTEXT MODULE - IMPLEMENTATION COMPLETE PLAN
## **UI-First Approach with Dedicated Provider**

**Date** : 2026-01-02 22:47  
**Strategy** : UI → Backend → Integration  
**Provider** : Dedicated for Context (flexibility)

---

## ✅ **WHAT'S DONE**

### **1. Core Classes (5 files, ~1900 lines)**

| File | Status | Lines |
|------|--------|-------|
| ContextIntent.kt | ✅ DONE | 290 |
| ContextLLMClient.kt | ✅ DONE +ENHANCED | 460 |
| ContextParser.kt | ✅ DONE | 380 |
| ContextManager.kt | ✅ DONE | 280 |
| ContextInfluenceEngine.kt | ✅ DONE | 340 |

### **2. Enhanced LLM Prompt** ✅

**Medical Context Included** :
- 🩸 BG + Delta + Trend
- 💉 IOB + TBR active + DIA + Peak
- 🍽️ COB
- 🌀 Trajectory type + score
- 🔄 WCycle phase (if enabled)
- 🕐 Time of day

**Example Prompt Sent to LLM** :
```
You are an expert diabetes context analyzer...

═══════════════════════════════════════════════════════════════
CURRENT MEDICAL CONTEXT:
═══════════════════════════════════════════════════════════════

🩸 GLUCOSE:
  • Current BG: 185 mg/dL
  • Delta: +3.2 mg/dL/5min ↗
  • Recent trend: 170 → 175 → 180 → 185 mg/dL

💉 INSULIN:
  • IOB: 0.8U
  • DIA: 5.5h
  • Peak time: 75min

🍽️ CARBS:
  • COB: 15g

🌀 TRAJECTORY:
  • Type: DIVERGENT
  • Score: 0.68

🔄 HORMONAL CYCLE:
  • Phase: LUTEAL
    (may affect insulin sensitivity)

🕐 TIME:
  • Evening

═══════════════════════════════════════════════════════════════

**Use this context to:**
1. Distinguish illness vs meals
2. Assess urgency
3. Infer activity timing
4. Detect patterns
5. Adjust intensity/duration

USER MESSAGE: "feeling tired today"
```

**LLM Response** (smart interpretation) :
```json
[{
  "type": "Illness",
  "intensity": "MEDIUM",
  "durationMinutes": 720,
  "confidence": 0.85,
  "metadata": {
    "symptomType": "GENERAL",
    "reasoning": "High BG with low IOB + luteal phase suggests insulin resistance consistent with fatigue"
  }
}]
```

---

## 🔨 **TO DO - PHASE 1: PREFERENCES & PROVIDER**

### **File 1: StringKey.kt**

**Add keys** :
```kotlin
// Context Module
ContextEnabled("aimi_context_enabled"),
ContextMode("aimi_context_mode"), // "CONSERVATIVE", "BALANCED", "AGGRESSIVE"

// Context LLM (dedicated provider)
ContextLLMEnabled("aimi_context_llm_enabled"),
ContextLLMProvider("aimi_context_llm_provider"), // "OPENAI", "GEMINI", "DEEPSEEK", "CLAUDE"
ContextLLMOpenAIKey("aimi_context_llm_openai_key"),
ContextLLMGeminiKey("aimi_context_llm_gemini_key"),
ContextLLMDeepSeekKey("aimi_context_llm_deepseek_key"),
ContextLLMClaudeKey("aimi_context_llm_claude_key"),
```

**Location** : Add after existing AI/Advisor keys

---

### **File2: ContextLLMClient.kt Enhancement**

**Modify to use dedicated provider** :

```kotlin
fun parseWithLLM(...): List<ContextIntent> {
    // Get Context-specific provider (not global Advisor provider)
    val provider = getContextProvider()
    val apiKey = getContextApiKey(provider)
    
    // Use provider
    val response = when (provider) {
        Provider.OPENAI -> aiCoachingService.callOpenAI(apiKey, prompt)
        Provider.GEMINI -> aiCoachingService.callGemini(apiKey, prompt)
        Provider.DEEPSEEK -> aiCoachingService.callDeepSeek(apiKey, prompt)
        Provider.CLAUDE -> aiCoachingService.callClaude(apiKey, prompt)
    }
}

private fun getContextProvider(): Provider {
    val providerStr = sp.getString(StringKey.ContextLLMProvider.key, "OPENAI")
    return Provider.valueOf(providerStr)
}

private fun getContextApiKey(provider: Provider): String {
    return when (provider) {
        Provider.OPENAI -> sp.getString(StringKey.ContextLLMOpenAIKey.key, "")
        Provider.GEMINI -> sp.getString(StringKey.ContextLLMGeminiKey.key, "")
        Provider.DEEPSEEK -> sp.getString(StringKey.ContextLLMDeepSeekKey.key, "")
        Provider.CLAUDE -> sp.getString(StringKey.ContextLLMClaudeKey.key, "")
    }
}
```

---

## 🎨 **TO DO - PHASE 2: UI (PRIORITY)**

### **File 3: ContextActivity.kt** (~400 lines)

**Structure** :
```kotlin
class ContextActivity : DaggerAppCompatActivityWithResult() {
    
    @Inject lateinit var contextManager: ContextManager
    @Inject lateinit var sp: SP
    
    private val viewModel: ContextViewModel by viewModels()
    
    override fun onCreate(...) {
        // Setup UI
        setupPresetButtons()
        setupChatInput()
        setupActiveIntentsList()
        setupSettings()
    }
    
    private fun setupPresetButtons() {
        // 10 chips: Cardio, Strength, Yoga, Sport, Walking, Sick, Stress, Meal, Alcohol, Travel
        binding.chipCardio.setOnClickListener {
            showPresetDialog(ContextPreset.ALL_PRESETS[0])
        }
        // ...
    }
    
    private fun setupChatInput() {
        binding.btnSendChat.setOnClickListener {
            val text = binding.editChatInput.text.toString()
            viewModel.parseAndAddIntent(text)
        }
    }
    
    private fun setupActiveIntentsList() {
        viewModel.activeIntents.observe(this) { intents ->
            // Update RecyclerView
            adapter.submitList(intents)
        }
    }
}
```

---

### **File 4: ContextViewModel.kt** (~200 lines)

```kotlin
@HiltViewModel
class ContextViewModel @Inject constructor(
    private val contextManager: ContextManager,
    private val contextLLMClient: ContextLLMClient,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val iobCobCalculator: IobCobCalculator,
    private val trajectoryGuard: TrajectoryGuard,
    private val sp: SP
) : ViewModel() {
    
    private val _activeIntents = MutableLiveData<List<Pair<String, ContextIntent>>>()
    val activeIntents: LiveData<List<Pair<String, ContextIntent>>> = _activeIntents
    
    private val _parseStatus = MutableLiveData<ParseStatus>()
    val parseStatus: LiveData<ParseStatus> = _parseStatus
    
    fun parseAndAddIntent(userText: String) {
        viewModelScope.launch {
            _parseStatus.value = ParseStatus.Parsing
            
            try {
                // Build medical context
                val medicalContext = buildMedicalContext()
                
                // Parse with LLM (if enabled) or offline
                val ids = if (sp.getBoolean(StringKey.ContextLLMEnabled.key, false)) {
                    contextManager.addIntentWithLLM(userText, medicalContext)
                } else {
                    contextManager.addIntent(userText)
                }
                
                _parseStatus.value = ParseStatus.Success(ids.size)
                refreshActiveIntents()
                
            } catch (e: Exception) {
                _parseStatus.value = ParseStatus.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    private fun buildMedicalContext(): ContextLLMClient.MedicalContext {
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData ?: return MedicalContext(...)
        val iobCob = iobCobCalculator.calculateIobFromBolus().round()
        
        // Get trajectory if available
        val trajectoryResult = try {
            trajectoryGuard.getLastAnalysis()
        } catch (e: Exception) { null }
        
        // Get wcycle phase if enabled
        val wcyclePhase = if (sp.getBoolean(StringKey.WCycleLearnerEnabled.key, false)) {
            // Get current phase from WCycleLearner
            wcycleLearner.getCurrentPhase()?.toString()
        } else null
        
        // Determine time of day
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val timeOfDay = when (hour) {
            in 6..11 -> "Morning"
            in 12..17 -> "Afternoon"
            in 18..22 -> "Evening"
            else -> "Night"
        }
        
        return ContextLLMClient.MedicalContext(
            currentBG = glucoseStatus.glucose,
            iob = iobCob.iob,
            cob = iobCob.cob,
            currentTBR = getCurrentTBR(),
            tbrDuration = getTBRDuration(),
            bgTrend = getBGHistory(),
            delta = glucoseStatus.delta,
            shortAvgDelta = glucoseStatus.shortAvgDelta,
            trajectoryType = trajectoryResult?.type?.toString(),
            trajectoryScore = trajectoryResult?.score,
            dia = profile.dia,
            peakTime = calculatePeakTime(),
            wcyclePhase = wcyclePhase,
            timeOfDay = timeOfDay
        )
    }
    
    fun refreshActiveIntents() {
        val allIntents = contextManager.getAllIntents()
        _activeIntents.value = allIntents.toList()
    }
    
    fun removeIntent(id: String) {
        contextManager.removeIntent(id)
        refreshActiveIntents()
    }
    
    fun extendIntent(id: String, additionalMinutes: Int) {
        contextManager.extendDuration(id, additionalMinutes.minutes)
        refreshActiveIntents()
    }
}

sealed class ParseStatus {
    object Parsing : ParseStatus()
    data class Success(val intentCount: Int) : ParseStatus()
    data class Error(val message: String) : ParseStatus()
}
```

---

### **File 5: activity_context.xml** (~300 lines)

**Material Design 3 Layout** :

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="?android:colorBackground">
    
    <!-- AppBar -->
    <com.google.android.material.appbar.MaterialToolbar
        android:id="@+id/toolbar"
        android:layout_width="match_parent"
        android:layout_height="?attr/actionBarSize"
        android:background="?attr/colorPrimary"
        app:title="@string/context_title"
        app:navigationIcon="@drawable/ic_arrow_back"
        app:layout_constraintTop_toTopOf="parent"/>
    
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        app:layout_constraintTop_toBottomOf="@id/toolbar"
        app:layout_constraintBottom_toBottomOf="parent">
        
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="16dp">
            
            <!-- Chat Input Section -->
            <com.google.android.material.card.MaterialCardView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="16dp"
                app:cardElevation="2dp"
                app:cardCornerRadius="8dp">
                
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:orientation="vertical"
                    android:padding="16dp">
                    
                    <TextView
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="💬 Tell me your situation"
                        android:textAppearance="?attr/textAppearanceHeadline6"
                        android:layout_marginBottom="8dp"/>
                    
                    <com.google.android.material.textfield.TextInputLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:hint="Type here..."
                        app:boxBackgroundMode="outline">
                        
                        <com.google.android.material.textfield.TextInputEditText
                            android:id="@+id/editChatInput"
                            android:layout_width="match_parent"
                            android:layout_height="wrap_content"
                            android:minLines="3"
                            android:maxLines="5"
                            android:inputType="textMultiLine|textCapSentences"/>
                    </com.google.android.material.textfield.TextInputLayout>
                    
                    <LinearLayout
                        android:layout_width="match_parent"
                        android:layout_height="wrap_content"
                        android:orientation="horizontal"
                        android:layout_marginTop="8dp">
                        
                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnSendChat"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:text="🤖 AI Parse"
                            app:icon="@drawable/ic_send"/>
                        
                        <Space android:layout_width="8dp" android:layout_height="1dp"/>
                        
                        <com.google.android.material.button.MaterialButton
                            android:id="@+id/btnManualParse"
                            style="@style/Widget.Material3.Button.OutlinedButton"
                            android:layout_width="0dp"
                            android:layout_height="wrap_content"
                            android:layout_weight="1"
                            android:text="📝 Manual"/>
                    </LinearLayout>
                </LinearLayout>
            </com.google.android.material.card.MaterialCardView>
            
            <!-- Presets Section -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Quick Presets"
                android:textAppearance="?attr/textAppearanceHeadline6"
                android:layout_marginBottom="8dp"/>
            
            <com.google.android.material.chip.ChipGroup
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginBottom="16dp"
                app:singleSelection="false">
                
                <com.google.android.material.chip.Chip
                    android:id="@+id/chipCardio"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="🏃 Cardio"
                    app:chipIcon="@drawable/ic_run"/>
                
                <com.google.android.material.chip.Chip
                    android:id="@+id/chipStrength"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="💪 Strength"/>
                
                <!-- More chips... -->
            </com.google.android.material.chip.ChipGroup>
            
            <!-- Active Intents Section -->
            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="📋 Active Contexts"
                android:textAppearance="?attr/textAppearanceHeadline6"
                android:layout_marginBottom="8dp"/>
            
            <androidx.recyclerview.widget.RecyclerView
                android:id="@+id/recyclerActiveIntents"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                app:layoutManager="androidx.recyclerview.widget.LinearLayoutManager"/>
            
        </LinearLayout>
    </ScrollView>
</androidx.constraintlayout.widget.ConstraintLayout>
```

---

## 📋 **IMPLEMENTATION CHECKLIST**

### **Phase 1: Preferences** (30 min)
- [ ] Add StringKeys (Context + dedicated provider keys)
- [ ] Update ContextLLMClient to use dedicated provider
- [ ] Test provider selection logic

### **Phase 2: UI Core** (2h)
- [ ] Create ContextActivity.kt
- [ ] Create ContextViewModel.kt
- [ ] Create activity_context.xml layout
- [ ] Create item_active_intent.xml (RecyclerView item)
- [ ] Create ContextIntentAdapter.kt

### **Phase 3: UI Integration** (1h)
- [ ] Add menu item in MainActivity (access Context screen)
- [ ] Add settings section in OpenAPSAIMIPlugin
- [ ] Wire up navigation

### **Phase 4: Medical Context Integration** (30 min)
- [ ] Implement buildMedicalContext() in ViewModel
- [ ] Get trajectory data
- [ ] Get wcycle data (if enabled)
- [ ] Test medical context prompt

### **Phase 5: Testing** (1h)
- [ ] Test UI flow (add preset)
- [ ] Test chat input + LLM parsing
- [ ] Test offline fallback
- [ ] Test active intents list (remove/extend)
- [ ] Test medical context enrichment

---

## 🎯 **DELIVERABLES**

1. **Preferences** : Dedicated provider + mode selection
2. **UI** : Full Context Activity with chat + presets
3. **Medical Context** : Enriched prompt with all params
4. **Offline Fallback** : Works without LLM
5. **Documentation** : User guide + screenshots

---

## 🚀 **NEXT STEPS**

**Immediate** :
1. Create StringKey additions
2. Create UI files (Activity + ViewModel + Layout)
3. Test UI flow

**Then** :
4. Integration with DetermineBasalAIMI2
5. End-to-end testing

---

**Ready to implement UI ?** 🎨

**Time estimate** : 3-4 hours for complete UI + provider

---
