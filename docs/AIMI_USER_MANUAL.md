# 📘 AIMI User Manual - Complete Guide
## Advanced Intelligent Mathematical Insulin (AIMI)

**Version**: 3.4.0  
**Last Updated**: May 2026  
**Languages**: 🌍 Multi-language (see Settings → Language)

---

## 📋 Table of Contents

1. [Quick Start](#quick-start)
2. [Essential Settings](#essential-settings) ⚠️ MUST READ
3. [Meal Modes](#meal-modes) 
4. [AIMI Advisor](#aimi-advisor) 🤖
5. [AIMI Meal Advisor](#aimi-meal-advisor) 📸
6. [AIMI Context](#aimi-context) 🎯
7. [Safety Features](#safety-features) 🛡️
   - [AIMI Auditor](#aimi-auditor)
   - [AIMI Trajectory](#aimi-trajectory)
   - [PKPD](#pkpd-pharmacokineticpharmacodynamic-model)
8. [Eversense CGM (E3 / 365)](#eversense-cgm-e3--365) 📡
9. [Troubleshooting](#troubleshooting)
10. [Recommended Settings](#recommended-settings-by-user-type)

---

## 🚀 Quick Start

### Step 1: Enable AIMI

1. Go to **Config Builder** → **APS** tab
2. Select **"OpenAPS AIMI"**
3. Click **Plugin Preferences** (gear icon)

### Step 2: Essential Settings ⚠️

**CRITICAL: These settings MUST be configured correctly:**

#### ✅ Disable ApsSensitivityRaisesTarget

**Path**: Config Builder → Sensitivity → Advanced Settings

```
❌ ApsSensitivityRaisesTarget = OFF (MUST be disabled)
```

**Why?** AIMI uses its own dynamic ISF system. Having this enabled causes conflicts and over-correction.

#### ✅ Set Max IOB Appropriately  

**Path**: OpenAPS AIMI → Safety tab

```
Max IOB: Start conservatively
- Adults: 15-25U
- Teens: 10-15U  
- Children: 5-10U
```

**Tip**: Start low and increase gradually based on results.

---

## 🍽️ Meal Modes

AIMI includes **8 specialized meal modes** that automatically adjust insulin delivery for different meal types.

### Available Modes

| Icon | Mode | When to Use | Typical Carbs | Prebolus |
|------|------|-------------|---------------|----------|
| 🌅 | **Breakfast** | Morning meal | 30-60g | 15 min before |
| 🍱 | **Lunch** | Midday meal | 40-80g | 10 min before |
| 🍽️ | **Dinner** | Evening meal | 50-100g | 15 min before |
| 🍕 | **High Carb** | Pizza, pasta | 80-150g | 20 min before |
| 🍪 | **Snack** | Small meal | 10-30g | 5 min before |
| 🍴 | **Meal (Generic)** | Any meal | Variable | 10 min before |
| 😴 | **Sleep** | Before bed | 0-20g | Optional |

### How to Create Meal Mode Buttons

#### Method: Using Automation + Careportal

1. Go to **Automation** tab
2. Create **New Rule**
3. **Name the rule** (e.g., "AIMI Breakfast")
4. **Trigger**: Check **"User action"**
5. In **Action**: Select **"Careportal"**
6. **Mode name**: Enter the desired mode code:
   - `bfast`: Breakfast
   - `lunch`: Lunch
   - `dinner`: Dinner
   - `highcarb`: High Carb (pizza, pasta)
   - `snack`: Snack
   - `meal`: Generic meal
   - `sport`: Sport mode
   - `stop`: Stop current mode
   - `sleep`: Sleep mode
7. **Duration**: 
   - **60 or 90 minutes** for normal meal modes
   - **5 minutes REQUIRED** for `stop` mode (cancels current mode)
8. **Save** the rule
9. Repeat for each desired mode

⚠️ **Important**:
- **Refresh the loop** (pull-to-refresh on main screen) can activate the mode faster
- **Without active CGM readings**, prebolus will **NOT be sent** because the loop doesn't refresh without CGM data

### Meal Mode Preferences

**Path**: OpenAPS AIMI Preferences → Meal Modes

Each mode has customizable parameters:

| Parameter | Description | Typical Range |
|-----------|-------------|---------------|
| **Prebolus Amount** | How much insulin to give before meal | 30-100% |
| **Prebolus Timer** | Minutes before meal | 5-30 min |
| **Factor** | Aggressiveness multiplier | 0.8-1.5 |
| **Max Basal** | Maximum basal rate during mode | 3-10 U/h |

**Example Configuration:**

```yaml
Breakfast Mode:
  Prebolus: 60% of estimated
  Timer: 15 minutes
  Factor: 1.2 (more aggressive)
  Max Basal: 5.0 U/h
```

---

## 🤖 AIMI Advisor

**AI-powered profile and settings advisor using GPT-5.2, Gemini 2.5, or Claude.**

### What It Does

- Analyzes your **last 7-14 days** of glycemic and insulin data
- Identifies patterns (hypos, hypers, variability)
- Evaluates overall profile performance
- Suggests **specific and precise adjustments** to:
  - Basal rates (by time slot)
  - ISF (Insulin Sensitivity Factor)
  - CR (Carb Ratio)
  - DIA (Duration of Insulin Action)
  - Target BG
  - Max IOB
  - AIMI parameters (reactivity, meal modes)

### AIMI Advisor Actions

The Advisor generates a **detailed report** containing:

1. **Performance Analysis**:
   - Time In Range (TIR)
   - Frequency and severity of hypos/hypers
   - Glycemic variability (CV - Coefficient of Variation)
   - Analysis by period (night, morning, afternoon, evening)

2. **Specific Recommendations**:
   - Suggested changes with precise percentages
   - Data-based justification
   - Prioritized adjustments (critical → optional)

3. **Safety Validation**:
   - Each recommendation is **automatically audited** by AI Auditor
   - Dangerous suggestions are blocked or adjusted
   - Respects physiological limits

### How to Use

1. Go to **OpenAPS AIMI Preferences**
2. Scroll to **"🤖 Assistant AI"** section
3. Tap **"AIMI Profile Advisor"**
4. Select **AI Provider**:
   - **ChatGPT (GPT-5.2)**: Most advanced reasoning
   - **Gemini (2.5 Flash)**: Best cost/performance ratio ✅ Recommended
   - **DeepSeek (Chat)**: Most economical
   - **Claude (3.5 Sonnet)**: Alternative high-quality
5. Enter your **API Key** (get from provider's website)
6. Tap **"Analyze Profile"**
7. Wait 30-60 seconds
8. **Review recommendations** carefully
9. Apply changes **one at a time** and monitor results

### API Key Setup

**OpenAI (GPT-5.2)**:
- Go to https://platform.openai.com/api-keys
- Create new key
- Copy and paste into AAPS

**Google Gemini (2.5 Flash)** ✅ Recommended:
- Go to https://makersuite.google.com/app/apikey
- Create API key
- Copy and paste into AAPS
- **Cost**: ~30x cheaper than GPT

**DeepSeek**:
- Go to https://platform.deepseek.com
- Register and get key

**Claude**:
- Go to https://console.anthropic.com
- Create API key

### Safety Features

✅ **AI Auditor**: Every recommendation is automatically reviewed for safety  
✅ **Range Limits**: Suggestions stay within safe physiological ranges  
✅ **Human Approval**: You must manually apply each change  

---

## 📸 AIMI Meal Advisor

**Take a photo of your food, get instant carb estimates.**

### Supported AI Models

| Model | Use Case | Accuracy | Cost |
|-------|----------|----------|------|
| **GPT-4o Vision** | High accuracy needed | ⭐⭐⭐⭐⭐ | $$$ |
| **Gemini (2.5 Flash)** | Best balance | ⭐⭐⭐⭐ | $ ✅ |
| **DeepSeek (Chat)** | Budget option | ⭐⭐⭐ | ¢ |
| **Claude (3.5 Sonnet)** | Alternative | ⭐⭐⭐⭐ | $$$ |

### How to Use

1. Open **AIMI Meal Advisor** from menu
2. Select **AI Model** (dropdown at top)
3. Tap **"📷 Take Food Photo"**
4. Take clear photo of your meal
5. Wait 5-10 seconds for analysis
6. Review estimate:
   - **Total Effective**: Carbs + FPU equivalent
   - **Carbs**: Direct carbohydrates
   - **FPU**: Fat/Protein Units (converted to g)
7. Tap **"✅ Confirm"** to inject into AIMI
8. AIMI will automatically adjust insulin delivery

### Tips for Best Results

✅ **Good Photo**:
- Well-lit
- Entire meal visible
- Normal angle (not too close)

❌ **Avoid**:
- Dark/shadowy photos
- Partial plates
- Extreme close-ups

### What Happens After Confirmation

1. Carb estimate sent to **FoodCarbLoad (FCL)** system
2. AIMI calculates optimal bolus
3. **Prebolus** delivered if configured
4. **Basal adjustments** for fat/protein
5. **Extended bolus** handling for slow absorption

---

## 🎯 AIMI Context

**Tell AIMI about your activities, stress, illness, etc. for better insulin dosing.**

### What is Context?

AIMI Context allows you to **inform the algorithm** about factors that affect insulin needs:

- 🏃 **Exercise** (cardio, strength, yoga, sports)
- 🤒 **Illness** (fever, infection, stress)
- 😰 **Stress** (emotional, work, exams)
- 🍷 **Alcohol** consumption
- ✈️ **Travel** (timezone changes)
- 🔄 **Menstrual cycle** phase
- 🍕 **Unannounced meal risk**

### How to Use

#### Method 1: Natural Language (LLM)

1. Open **AIMI Context** from menu
2. Enable **"Use AI Parsing"** toggle
3. Type in **plain English**:
   ```
   "heavy cardio session 1 hour"
   "sick with flu, feeling resistant"
   "2 beers just had"
   "stressful work deadline today"
   ```
4. AI converts your text to structured intent
5. Tap **"Add Intent"**

#### Method 2: Preset Buttons

1. Open **AIMI Context**
2. Tap a **preset button**:
   - 🏃 Light Exercise
   - 🏃‍♂️ Moderate Exercise
   - 🏃‍♀️ Intense Exercise
   - 🤒 Illness
   - 😰 Stress
3. Adjust **duration** and **intensity** if needed
4. Tap **"Confirm"**

### Active Intents Management

**View Active**:
- See all currently active intents
- Shows time remaining for each

**Extend Duration**:
- Tap intent → **"Extend"** → Add more time

**Remove Intent**:
- Tap intent → **"Remove"**

### How Context Affects Insulin

| Context Type | Effect on Insulin | Typical Duration |
|--------------|-------------------|------------------|
| 🏃 **Exercise (Cardio)** | ⬇️ -30to -60% basal/SMB | 2-4 hours |
| 💪 **Exercise (Strength)** | ⬇️ -15 to -30% | 1-2 hours |
| 🧘 **Yoga** | ⬇️ -10 to -20% | 1-2 hours |
| 🤒 **Illness** | ⬆️ +20 to +50% | 12-48 hours |
| 😰 **Stress** | ⬆️ +10 to +30% | 4-8 hours |
| 🍷 **Alcohol** | ⬇️⬆️ Complex (initial drop, then rise) | 4-12 hours |
| 🔄 **Luteal Phase** | ⬆️ +10 to +20% | 14 days |

### Current AIMI Understanding (body state card)

At the top of **AIMI Context**, AIMI shows how it reads your body **before** insulin posture:

- **Mode headline** — e.g. Dawn Endogenous, Fast Meal, Stress Resistance
- **Narrative** — plain-language summary of therapeutic posture
- **Live body signals** — steps/15 min, heart rate, activity, sleep debt (Health Connect)
- **Thermal rhythm** — skin temperature evolution vs your personal baseline (Garmin or Oura via Health Connect)
- **Signal gauges** — Meal, Endogenous, Resistance, **Thermal**, Sensor

#### Thermal rhythm (Garmin / Oura)

AIMI does **not** dose from absolute body temperature. It tracks **how skin temperature moves** relative to your own nightly baseline:

| Reading | Meaning |
|---------|---------|
| *Stable around baseline* | Normal rhythm — no thermal stress signal |
| *Progressive warming* | Possible inflammatory or illness stress (combined with HR, sleep, context) |
| *Cooling + sleep debt* | Recovery burden — conservative posture |
| *Luteal BBT rise* | Cycle physiology (women) — read before insulin, not treated as infection |
| *Pending* | Enable **Skin Temperature** and **Basal Body Temperature** in Health Connect for Garmin/Oura |

**Setup:**

1. Garmin Connect or Oura → sync to **Health Connect**
2. AIMI → grant **Skin Temperature** + **Basal Body Temperature** permissions
3. Open Context — **Thermal rhythm** updates between loop ticks when wearable data changes

**Noise handling:** variations smaller than **0.03 °C** are ignored so normal wearable jitter does not trigger false alerts.

**Effect on insulin:** thermal signals influence AIMI’s **understanding** (resistance/recovery probabilities, patient mode, recursive belief authority). They do **not** add a fixed “+X% for fever” bolus. Dosing still flows through the belief tree and safety gates.

---

## 🛡️ Safety Features

### AIMI Auditor

**Real-time safety system that audits every insulin decision before execution.**

#### What It Does

The AI Auditor is an **independent second brain** that verifies all AIMI decisions:

**Checks Performed**:
- ✅ **Hypoglycemia risk assessment**:
  - Analysis of current BG and trends
  - Calculation of total IOB (Insulin On Board)
  - Prediction of future BG (30-120 minutes)
  
- ✅ **IOB saturation**:
  - Checks if too much insulin is already active
  - Detects dangerous insulin stacking
  - Respects configured Max IOB limits
  
- ✅ **Delta trend analysis**:
  - Evaluates glycemic change velocity
  - Detects rapid drops (hypo risk)
  - Identifies rapid rises (adjustment needed)
  
- ✅ **Prediction consistency**:
  - Compares AIMI predictions with safety models
  - Blocks dangerous contradictions
  - Validates proposed doses are proportional

**Verdict Types**:
- ✅ **APPROVED**: Dose is safe, immediate execution
- ⚠️ **APPROVED_WITH_REDUCTION**: Dose reduced for safety (e.g., -30%)
- ❌ **REJECTED**: Dose blocked, too dangerous

#### When Auditor Intervenes

The Auditor verifies:
- **All SMB** (Super Micro Bolus)
- **All preboluses** from meal modes
- **All temporary basal adjustments**
- **All recommendations** from AIMI Advisor

**Protection Example**:
```
Scenario: BG = 85 mg/dL, Delta = -5 mg/dL/5min, IOB = 3U
AIMI proposes: 0.5U SMB
Auditor: ❌ REJECTED - High hypo risk, rapid downward trend
Result: No insulin delivered
```

### Low BG Guards

**Multiple layers of protection**:

1. **Reactivity Clamp**: Limits aggressiveness below 120 mg/dL
2. **SMB Cap**: Reduces max SMB by 80% below 120 mg/dL
3. **LGS (Low Glucose Suspend)**: Stops all insulin below threshold
4. **Hypo Prediction**: Blocks insulin if hypo predicted within 30 min

### AIMI Trajectory

**Advanced prediction system that anticipates your future blood glucose.**

#### What It Does

- **Calculates glycemic trajectory** over 30-180 minutes
- **Integrates all active factors**:
  - IOB (Insulin On Board) with PKPD model
  - COB (Carbs On Board) with dynamic absorption
  - Current Delta trends
  - Active temporary basal
  - Context (exercise, stress, etc.)
  
- **Adjusts decisions in real-time**:
  - Anticipated prebolus if rise predicted
  - Reduction/stop if hypo predicted
  - Optimal insulin timing

**Display**:
You can see predicted trajectory in:
- AIMI logs (OpenAPS tab)
- Prediction curve on main graph
- Decision details (tap notification)

### PKPD (Pharmacokinetic/Pharmacodynamic) Model

**Advanced model of insulin absorption and action.**

#### What is PKPD?

Instead of using a fixed DIA curve, PKPD models insulin **dynamically**:

**Pharmacokinetic (PK)** - How insulin is absorbed:
- Variable absorption rate based on:
  - Insulin type (Fiasp, NovoRapid, Humalog)
  - Injection site (abdomen, arm, thigh)
  - Body temperature (exercise = faster absorption)
  - Local blood flow

**Pharmacodynamic (PD)** - How insulin acts:
- Variable effect on BG based on:
  - Current sensitivity (dynamic ISF)
  - Receptor saturation (high IOB = reduced effect)
  - Temporary resistance (stress, illness)

#### PKPD Advantages

✅ **More accurate predictions**: Realistic model of insulin action  
✅ **Adapts to situations**: Detects saturation and adjusts  
✅ **Better meal management**: Optimal bolus timing  
✅ **Less stacking**: Detects "hidden" insulin still active  

**Configurable Parameters**:
- Insulin type (ultra-rapid vs rapid)
- Peak action (25-75 minutes)
- Effective DIA (3-7 hours)
- Saturation factor

### Max SMB/IOB Enforcement

**CRITICAL**: User preferences are **ALWAYS respected**.

```
✅ If you set max_smb_size = 0.5U → it will NEVER exceed 0.5U
✅ If you set max_iob = 10U → it will NEVER exceed 10U
```

---

## ⚙️ Advanced Features

### Dynamic ISF

**What**: Insulin Sensitivity Factor that adapts in real-time based on BG trends.

**Path**: OpenAPS AIMI → Advanced → Dynamic ISF

**Key Settings**:
- `dynISF Factor`: Lower = more conservative (100-400)
- `dynISF Adjustment Factor`: Fine-tuning (0.8-1.2)

### Trajectory Guard

**What**: Predicts BG trajectory and prevents dangerous patterns.

**Types Detected**:
- 🌀 **ORBIT**: Stable control
- 📈 **DIVERGENT**: Losing control (intervention needed)
- 📉 **CONVERGENT**: Improving
- ⚠️ **DRIFT**: Slow degradation

**Path**: OpenAPS AIMI → Trajectory Guard

### UnifiedReactivity Learner

**What**: Machine learning system that adapts to your insulin sensitivity.

**How It Works**:
1. Analyzes **hypos**, **hypers**, **variability**
2. Adjusts **react** factor (0.4-2.5)
3. Updates every **24-48 hours**

**View Current React**:
- Check logs: `globalFactor=X.XX`
- Lower react (0.4-0.7) = more conservative
- Higher react (1.3-2.0) = more aggressive

---

## 📡 Eversense CGM (E3 / 365)

OpenApsAIMI includes the **native Eversense CGM plugin** (CAPTCG patch series). It works with the AIMI loop like any other glucose source. This plugin is **experimental** — always verify readings with a fingerstick before treatment decisions.

Upstream reference: [CAPTCG/AndroidAPS-Eversense-](https://github.com/CAPTCG/AndroidAPS-Eversense-)

### E3 vs 365

| Transmitter | Sensor life | Official Eversense app | Notes |
|-------------|-------------|------------------------|-------|
| **Eversense E3** (180-day) | 180 days | **Required** for insertion/warm-up, then **disconnect** for AAPS BLE | **EU** DMS endpoints |
| **Eversense 365** (1-year) | 365 days | **Not required** — AAPS standalone | **US** DMS endpoints |

### Before using AIMI

1. **Insertion** by a Senseonics-trained clinician (warm-up shown in the official app).
2. **Do not calibrate** during warm-up or initialization.
3. In the official app: **Connections → your transmitter → Disconnect** (frees BLE for AAPS).
4. In **Config Builder → BG Source**: select **Eversense**.
5. Open **Eversense plugin settings** and enter **DMS credentials** (Eversense portal username/password) — required for cloud auth and the security certificate on every new connection.
6. **Scan** and **pair** the transmitter over Bluetooth.

Without active CGM data, **AIMI cannot run the loop** (meal-mode prebolus, SMB, etc.).

### Plugin settings (summary)

| Setting | Purpose |
|---------|---------|
| **DMS credentials** | Eversense cloud login (E3 EU / 365 US) |
| **Calibration** | Phase, last/next calibration, **Calibrate** button when `READY` |
| **Placement signal** | Excellent ≥75 · Good 48–74 · Low 30–47 · Very poor 1–24 |
| **Use ESEL smoothing** | Optional glucose smoothing (fork preference) |
| **Enable Eversense cloud upload** | Upload to DMS portal for care team |
| **Show cloud upload result** | Optional upload result toast |

**Poor placement**: 3 consecutive low readings → urgent notification and placement guide.

### Calibration

- Transmitter must be **CalibrationReadiness = READY** (shown in the plugin).
- Tap **Calibrate**, enter fingerstick BG; value is sent over **Bluetooth** (E3 uses corrected **0x3C** dual-timestamp packet).
- **Rejected** during warm-up, initialization, or when not ready.
- After success, last calibration date is updated locally (some E3 firmware versions do not return it reliably from flash).

### Daily use with AIMI

1. Apply a **fresh adhesive patch** each morning; wear the transmitter on the upper arm.
2. **Charge the transmitter daily** — no glucose data while charging.
3. Check BG and trend arrows in AAPS; AIMI uses these values like any CGM source.
4. **Calibrate** when due; if symptoms do not match CGM, **always fingerstick**.
5. For **firmware updates** via the official app: remove the CGM source in AAPS, reconnect in Senseonics, then set up Eversense in AAPS again.

### DMS portal sync (care team)

If **cloud upload** is enabled:

- **E3**: `PutCurrentValues` + `PutDeviceEvents` to **EU** servers.
- **365**: diagnostic upload + **US** portal (standard 365 behavior).

Readings can update the portal (glucose, trend, signal, battery, history).

### Eversense troubleshooting

| Issue | Checks |
|-------|--------|
| No BLE connection | Official app disconnected? Bluetooth / location permissions? |
| Calibration refused | Warm-up / init phase? Wait for `READY` |
| Absurd values after calibration | Update to latest AIMI build (0x3C packet fix) |
| Cloud upload failed | DMS credentials, Internet; E3 = EU account |
| AIMI loop inactive | Active BG source and recent readings |

### AIMI compatibility

- Eversense as **SourceSensor** `EVERSENSE_E3` or `EVERSENSE_365` — works with OpenAPS AIMI, adaptive smoothing, and standard exports.
- Same requirement as Dexcom/xDrip: **fresh CGM data** for SMB, meal modes, and Auditor.

---

## 🔧 Troubleshooting

### "Getting too many hypos"

**Steps**:
1. **Lower Max SMB**:
   - Settings → Max SMB > 120: 0.5U
   - Max SMB < 120: 0.2U
2. **Increase Target BG**:
   - Consider 110-120 mg/dL instead of 100
3. **Check React**:
   - Should auto-adapt down after hypos
   - Check logs: `globalFactor` should decrease
4. **Disable Aggressive Features**:
   - Lower `dynISF Adjustment Factor` to 0.9
   - Increase SMB interval

### "Not enough insulin for meals"

**Steps**:
1. **Use Meal Modes**:
   - Don't rely on auto-bolus alone
   - Activate appropriate mode 15 min before eating
2. **Increase Meal Mode Prebolus**:
   - Settings → Meal Modes → Prebolus: 80-100%
3. **Check Carb Ratio**:
   - May need adjustment via Profile
4. **Use Meal Advisor**:
   - More accurate carb counting

### "AI Advisor API errors"

**Solutions**:
- **HTTP 400**: Check API key is valid
- **HTTP 429**: Rate limit exceeded, wait 1 min
- **Timeout**: Try Gemini (faster than GPT)
- **Invalid JSON**: Model incompatibility, switch provider

---

## 📊 Recommended Settings by User Type

### Conservative (Hypo-Prone)

```yaml
Max SMB > 120: 0.5 U
Max SMB < 120: 0.2 U
Max IOB: 8 U
dynISF Factor: 100
Target BG: 110-120 mg/dL
Autodrive Prebolus: 0.1
```

### Balanced (Standard)

```yaml
Max SMB > 120: 1.0 U
Max SMB < 120: 0.5 U
Max IOB: 15 U
dynISF Factor: 200
Target BG: 100-110 mg/dL
Autodrive Prebolus: 0.5
```

### Aggressive (Tight Control)

```yaml
Max SMB > 120: 1.5 U
Max SMB < 120: 0.8 U
Max IOB: 25 U
dynISF Factor: 300
Target BG: 90-100 mg/dL
Autodrive Prebolus: 1.0
```

---

## 🌍 Language Support

This manual is available in the following languages:

🇬🇧 English | 🇫🇷 Français | 🇩🇪 Deutsch | 🇪🇸 Español | 🇮🇹 Italiano  
🇵🇹 Português | 🇷🇺 Русский | 🇵🇱 Polski | 🇨🇿 Čeština | 🇳🇱 Nederlands  
🇸🇪 Svenska | 🇩🇰 Dansk | 🇳🇴 Norsk | 🇫🇮 Suomi | 🇬🇷 Ελληνικά  
🇹🇷 Türkçe | 🇮🇱 עברית | 🇰🇷 한국어 | 🇨🇳 中文 | 🇯🇵 日本語

**To change language**: Settings → Language → Select your preferred language

---

## 📞 Support & Community

- **GitHub Issues**: https://github.com/YourRepo/OpenApsAIMI/issues
- **Discord**: https://discord.gg/aaps-aimi
- **Documentation**: https://aimi.docs.com
- **Facebook Group**: [AAPS AIMI Users]

---

## ⚖️ Disclaimer

**AIMI is experimental software for research purposes.**

- ⚠️ Always supervise automated insulin delivery
- ⚠️ Verify all recommendations before applying
- ⚠️ Consult your healthcare provider
- ⚠️ You are responsible for your diabetes management

**Do not rely solely on automation. Stay vigilant.**

---

**Last Updated**: May 2026  
**Manual Version**: 2.1  
**AIMI Version**: 3.4.0

