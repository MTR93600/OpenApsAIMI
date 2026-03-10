# 🎯 PLAN D'IMPLÉMENTATION EXPERT - Hybrid Circle-Top Dashboard
## Conservation Licorne + Badges + Nouveau Cercle + Treatments Graph

**Date**: 2026-01-09  
**Réalisé par**: Lyra - Senior++ Kotlin & UI/UX Expert  
**Objectif**: Fusionner le meilleur de `feature/circle-top` avec le dashboard MTR actuel

---

## 📋 **EXECUTIVE SUMMARY**

**Ce qu'on va créer** :

```
┌────────────────────────────────────────────────────────┐
│ [🎓] [🔍]                  Closed Loop [🟢]            │
│ context auditor                                         │
│                                                         │
│                          ╭─────────────╮                │
│        🦄                │     130     │      ➡  -3    │
│     (VERT)               │   4m ago    │                │
│   dynamique              │    Δ -3     │                │
│     70×70                ╰─────────────╯                │
│                        Cercle + Nose Pointer           │
│                       (GlucoseRingView)                │
│                                                         │
│ ┌──────────────────┐      ┌──────────────────┐         │
│ │ 🧪 65.90 IE      │      │ 🕐 23:16         │         │
│ │ 💉 2d 8h         │      │ 📊 0%            │         │
│ │ 🔋 5h 50m        │      │ 🔄 0.00 U/h      │         │
│ │ 💧 5d 1h         │      │ ⚙️  2.02 IE      │         │
│ └──────────────────┘      └──────────────────┘         │
│                                                         │
│ [Advisor] [Adjust] [Prefs] [Stats]                     │
└────────────────────────────────────────────────────────┘

    Graph avec treatments overlays :
┌────────────────────────────────────────────────────────┐
│ 280                                       Upd. 23:35   │
│ 240                                                     │
│ 200        ●●●●                                        │
│ 160    ●●●●    ●●●●                                   │
│ 120 ●●●            ●●●●●                              │
│  80 ▲  ▲  ▲  ▲  ▲                                     │
│  40 ║  ║  ║                                            │
│   0 ──────────────────────────────────────────────     │
└────────────────────────────────────────────────────────┘
     ▲ = Bolus (blue)   ║ = Carbs (orange)   ● = BG line
```

**Innovations fusionnées** :
1. ✅ **Licorne dynamique** (MTR existante) - conservée à gauche
2. ✅ **Badges AIMI** (context + auditor) - conservés en top-left
3. ✅ **GlucoseRingView** (feature/circle-top) - ajouté au centre avec "nose pointer"
4. ✅ **Métriques 2 colonnes** (feature/circle-top) - ajoutées en bas
5. ✅ **Action chips** (feature/circle-top) - ajoutés en bottom row
6. ✅ **Treatments overlays** (feature/circle-top) - bolus/SMB/carbs sur graphique

---

## 🎨 **ARCHITECTURE HYBRIDE**

### **Top Card Hybride** :

**Composants** :

| Élément | Position | Source | Conservation |
|---------|----------|--------|--------------|
| **Context Badge** 🎓 | Top-left | MTR existant | ✅ CONSERVÉ |
| **Auditor Badge** 🔍 | Top-left (after context) | MTR existant | ✅ CONSERVÉ |
| **Loop Indicator** 🟢 | Top-right | MTR existant | ✅ CONSERVÉ |
| **Unicorn** 🦄 | Left of circle | MTR existant | ✅ CONSERVÉ (dynamique) |
| **GlucoseRingView** ⭕ | Center | feature/circle-top NOUVEAU | ✅ AJOUTÉ (avec nose pointer) |
| **Trend Arrow** ➡ | Right of circle | MTR existant | ✅ CONSERVÉ |
| **Delta** -3 | Right of arrow | MTR existant | ✅ CONSERVÉ |
| **Left Column** 🧪💉🔋💧 | Below circle, left | feature/circle-top NOUVEAU | ✅ AJOUTÉ |
| **Right Column** 🕐📊🔄⚙️ | Below circle, right | feature/circle-top NOUVEAU | ✅ AJOUTÉ |
| **Action Chips** | Bottom row | feature/circle-top NOUVEAU | ✅ AJOUTÉ |

---

## 📂 **FICHIERS À CRÉER/MODIFIER**

### **1. Custom View - GlucoseRingView** ✅ DÉJÀ CRÉÉ

**Fichier** : `/Users/mtr/StudioProjects/OpenApsAIMI/core/ui/src/main/kotlin/app/aaps/core/ui/elements/GlucoseRingView.kt`

**Status** : ✅ **DÉJÀ CRÉÉ** (211 lignes)

**Modifications nécessaires** : AUCUNE (ton GlucoseCircleView.kt existant est différent, on va créer GlucoseRingView en parallèle)

**Source** : Code récupéré du GitHub `feature/circle-top`

**Fonctionnalités** :
- ✅ Arc coloré selon BG range (vert/jaune/orange/rouge)
- ✅ **Nose pointer** (triangle qui pointe selon delta : -90° à +90°)
- ✅ BG value au centre (bold, large)
- ✅ 2 subtexts en bas : time ago + delta
- ✅ Animations smooth

---

### **2. Layout Hybride - component_circle_top_status_hybrid.xml** ⏳ À CRÉER

**Fichier** : `/Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/res/layout/component_circle_top_status_hybrid.xml`

**Structure** :

```xml
<?xml version="1.0" encoding="utf-8"?>
<merge xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:background="@drawable/dashboard_card_surface"
        android:orientation="vertical"
        android:padding="16dp">

        <!-- ═══════════════════════════════════════════════════ -->
        <!-- TOP ROW: Badges + Loop Indicator                    -->
        <!-- ═══════════════════════════════════════════════════ -->
        <RelativeLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginBottom="8dp">

            <!-- Left: Badges (Context + Auditor) - CONSERVÉS -->
            <LinearLayout
                android:id="@+id/badges_container"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_alignParentStart="true"
                android:orientation="horizontal">

                <ImageView
                    android:id="@+id/aimi_context_indicator"
                    android:layout_width="28dp"
                    android:layout_height="28dp"
                    android:layout_marginEnd="4dp"
                    android:contentDescription="AIMI Context"
                    android:elevation="20dp"
                    android:visibility="gone"
                    app:srcCompat="@drawable/ic_graduation"
                    app:tint="?android:attr/textColorPrimary" />

                <FrameLayout
                    android:id="@+id/aimi_auditor_indicator_container"
                    android:layout_width="28dp"
                    android:layout_height="28dp"
                    android:elevation="20dp" />
            </LinearLayout>

            <!-- Right: Loop Indicator - CONSERVÉ -->
            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_alignParentEnd="true"
                android:orientation="horizontal"
                android:gravity="center_vertical">

                <TextView
                    android:id="@+id/loop_status"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginEnd="6dp"
                    android:alpha="0.7"
                    android:text="Closed Loop"
                    android:textColor="@color/dashboard_on_surface"
                    android:textSize="12sp" />

                <View
                    android:id="@+id/loop_indicator"
                    android:layout_width="12dp"
                    android:layout_height="12dp"
                    android:background="@drawable/dashboard_loop_indicator" />
            </LinearLayout>
        </RelativeLayout>

        <!-- ═══════════════════════════════════════════════════ -->
        <!-- MIDDLE ROW: Unicorn + GlucoseRingView + Trend      -->
        <!-- ═══════════════════════════════════════════════════ -->
        <androidx.constraintlayout.widget.ConstraintLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:layout_marginBottom="16dp">

            <!-- Unicorn (Left) - CONSERVÉ -->
            <ImageView
                android:id="@+id/unicorn_icon"
                android:layout_width="70dp"
                android:layout_height="70dp"
                android:layout_marginEnd="12dp"
                android:contentDescription="Unicorn Status"
                android:src="@drawable/unicorn"
                app:layout_constraintBottom_toBottomOf="@id/glucose_ring"
                app:layout_constraintEnd_toStartOf="@id/glucose_ring"
                app:layout_constraintStart_toStartOf="parent"
                app:layout_constraintTop_toTopOf="@id/glucose_ring"
                app:layout_constraintHorizontal_chainStyle="packed" />

            <!-- GlucoseRingView (Center) - NOUVEAU -->
            <app.aaps.core.ui.views.GlucoseRingView
                android:id="@+id/glucose_ring"
                style="@style/GlucoseRingViewStepped"
                android:layout_width="149dp"
                android:layout_height="149dp"
                android:elevation="3dp"
                app:ringStrokeWidth="5dp"
                app:layout_constraintStart_toEndOf="@id/unicorn_icon"
                app:layout_constraintEnd_toStartOf="@id/trend_arrow"
                app:layout_constraintTop_toTopOf="parent"
                app:layout_constraintBottom_toBottomOf="parent" />

            <!-- Trend Arrow (Right) - CONSERVÉ -->
            <ImageView
                android:id="@+id/trend_arrow"
                android:layout_width="40dp"
                android:layout_height="40dp"
                android:layout_marginStart="12dp"
                android:contentDescription="Trend"
                app:layout_constraintBottom_toBottomOf="@id/glucose_ring"
                app:layout_constraintStart_toEndOf="@id/glucose_ring"
                app:layout_constraintTop_toTopOf="@id/glucose_ring"
                app:layout_constraintEnd_toStartOf="@id/delta_value" />

            <!-- Delta (Right) - CONSERVÉ -->
            <TextView
                android:id="@+id/delta_value"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginStart="6dp"
                android:alpha="0.8"
                android:text="-3"
                android:textAppearance="@style/TextAppearance.MaterialComponents.Headline6"
                android:textColor="@color/dashboard_on_surface"
                app:layout_constraintBottom_toBottomOf="@id/trend_arrow"
                app:layout_constraintStart_toEndOf="@id/trend_arrow"
                app:layout_constraintTop_toTopOf="@id/trend_arrow"
                app:layout_constraintEnd_toEndOf="parent" />
        </androidx.constraintlayout.widget.ConstraintLayout>

        <!-- ═══════════════════════════════════════════════════ -->
        <!-- METRICS ROW: Left Column + Right Column            -->
        <!-- ═══════════════════════════════════════════════════ -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="8dp"
            android:layout_marginBottom="12dp">

            <!-- LEFT COLUMN - NOUVEAU -->
            <LinearLayout
                android:id="@+id/left_col"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical"
                android:layout_marginEnd="8dp">

                <!-- Reservoir -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:layout_marginBottom="6dp">

                    <ImageView
                        android:layout_width="18dp"
                        android:layout_height="18dp"
                        android:layout_marginEnd="6dp"
                        app:srcCompat="@drawable/ic_cp_age_insulin"
                        app:tint="@color/dashboard_on_surface" />

                    <TextView
                        android:id="@+id/reservoir_chip"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="65.90 IE"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                        android:textColor="@color/dashboard_on_surface" />
                </LinearLayout>

                <!-- Infusion Age -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:layout_marginBottom="6dp">

                    <ImageView
                        android:layout_width="18dp"
                        android:layout_height="18dp"
                        android:layout_marginEnd="6dp"
                        app:srcCompat="@drawable/ic_cp_age_cannula"
                        app:tint="@color/dashboard_on_surface" />

                    <TextView
                        android:id="@+id/infusion_age_text"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="2d 8h"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                        android:textColor="@color/dashboard_on_surface" />
                </LinearLayout>

                <!-- Pump Battery -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:layout_marginBottom="6dp">

                    <ImageView
                        android:layout_width="18dp"
                        android:layout_height="18dp"
                        android:layout_marginEnd="6dp"
                        app:srcCompat="@drawable/ic_dashboard_battery"
                        app:tint="@color/dashboard_on_surface" />

                    <TextView
                        android:id="@+id/pump_battery_text"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="5h 50m"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                        android:textColor="@color/dashboard_on_surface" />
                </LinearLayout>

                <!-- Sensor Age -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical">

                    <ImageView
                        android:layout_width="18dp"
                        android:layout_height="18dp"
                        android:layout_marginEnd="6dp"
                        app:srcCompat="@drawable/ic_cp_age_sensor"
                        app:tint="@color/dashboard_on_surface" />

                    <TextView
                        android:id="@+id/sensor_age_text"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="5d 1h"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                        android:textColor="@color/dashboard_on_surface" />
                </LinearLayout>
            </LinearLayout>

            <!-- RIGHT COLUMN - NOUVEAU -->
            <LinearLayout
                android:id="@+id/right_col"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical"
                android:gravity="end"
                android:layout_marginStart="8dp">

                <!-- Last Update -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:layout_marginBottom="6dp">

                    <ImageView
                        android:layout_width="18dp"
                        android:layout_height="18dp"
                        android:layout_marginEnd="6dp"
                        app:srcCompat="@drawable/ic_time"
                        app:tint="@color/dashboard_on_surface" />

                    <TextView
                        android:id="@+id/last_update_text"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="23:16"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                        android:textColor="@color/dashboard_on_surface" />
                </LinearLayout>

                <!-- Activity % -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:layout_marginBottom="6dp">

                    <ImageView
                        android:layout_width="18dp"
                        android:layout_height="18dp"
                        android:layout_marginEnd="6dp"
                        app:srcCompat="@drawable/ic_activity"
                        app:tint="@color/dashboard_on_surface" />

                    <TextView
                        android:id="@+id/activity_text"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="0%"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                        android:textColor="@color/dashboard_on_surface" />
                </LinearLayout>

                <!-- TBR Rate -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical"
                    android:layout_marginBottom="6dp">

                    <ImageView
                        android:layout_width="18dp"
                        android:layout_height="18dp"
                        android:layout_marginEnd="6dp"
                        app:srcCompat="@drawable/ic_sensor_reading"
                        app:tint="@color/dashboard_on_surface" />

                    <TextView
                        android:id="@+id/tbr_rate_text"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="0.00 U/h"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                        android:textColor="@color/dashboard_on_surface" />
                </LinearLayout>

                <!-- Basal -->
                <LinearLayout
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:orientation="horizontal"
                    android:gravity="center_vertical">

                    <ImageView
                        android:layout_width="18dp"
                        android:layout_height="18dp"
                        android:layout_marginEnd="6dp"
                        app:srcCompat="@drawable/ic_cp_basal"
                        app:tint="@color/dashboard_on_surface" />

                    <TextView
                        android:id="@+id/basal_text"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"
                        android:text="2.02 IE"
                        android:textAppearance="@style/TextAppearance.MaterialComponents.Caption"
                        android:textColor="@color/dashboard_on_surface" />
                </LinearLayout>
            </LinearLayout>
        </LinearLayout>

        <!-- ═══════════════════════════════════════════════════ -->
        <!-- BOTTOM ROW: Action Chips                           -->
        <!-- ═══════════════════════════════════════════════════ -->
        <HorizontalScrollView
            android:id="@+id/bottom_action_row"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:scrollbars="none">

            <com.google.android.material.chip.ChipGroup
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="center"
                app:singleSelection="false">

                <com.google.android.material.chip.Chip
                    android:id="@+id/chip_aimi_advisor"
                    style="@style/Widget.MaterialComponents.Chip.Action"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Advisor" />

                <com.google.android.material.chip.Chip
                    android:id="@+id/chip_adjust"
                    style="@style/Widget.MaterialComponents.Chip.Action"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Adjust" />

                <com.google.android.material.chip.Chip
                    android:id="@+id/chip_aimi_pref"
                    style="@style/Widget.MaterialComponents.Chip.Action"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Prefs" />

                <com.google.android.material.chip.Chip
                    android:id="@+id/chip_stat"
                    style="@style/Widget.MaterialComponents.Chip.Action"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Stats" />
            </com.google.android.material.chip.ChipGroup>
        </HorizontalScrollView>
    </LinearLayout>
</merge>
```

---

### **3. Kotlin View Class - CircleTopStatusHybridView.kt** ⏳ À CRÉER

**Fichier** : `/Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/views/CircleTopStatusHybridView.kt`

**Code** :

```kotlin
package app.aaps.plugins.main.general.dashboard.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import app.aaps.plugins.main.databinding.ComponentCircleTopStatusHybridBinding
import app.aaps.plugins.main.general.dashboard.viewmodel.OverviewViewModel

class CircleTopStatusHybridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val binding = ComponentCircleTopStatusHybridBinding.inflate(
        LayoutInflater.from(context),
        this,
        true
    )

    fun update(state: OverviewViewModel.StatusCardState) {
        // Update GlucoseRingView
        binding.glucoseRing.update(
            bgMgdl = state.glucoseMgdl,
            mainText = state.glucoseText ?: "--",
            subLeftText = state.timeAgo ?: "",
            subRightText = state.deltaText ?: "",
            noseAngleDeg = state.noseAngleDeg
        )

        // Update Left Column
        binding.reservoirChip.text = state.reservoirText ?: "--"
        binding.infusionAgeText.text = state.infusionAgeText ?: "--"
        binding.pumpBatteryText.text = state.pumpBatteryText ?: "--"
        binding.sensorAgeText.text = state.sensorAgeText ?: "--"

        // Update Right Column
        binding.lastUpdateText.text = state.lastUpdateText ?: "--"
        binding.activityText.text = state.activityPctText ?: "0%"
        binding.tbrRateText.text = "0.00 U/h" // TODO: Add to StatusCardState
        binding.basalText.text = state.basalText ?: "--"

        // Delta large (right of trend arrow)
        binding.deltaValue.text = state.deltaText ?: "--"
    }

    fun setActionListener(listener: CircleTopActionListener) {
        binding.chipAimiAdvisor.setOnClickListener { listener.onAimiAdvisorClicked() }
        binding.chipAdjust.setOnClickListener { listener.onAdjustClicked() }
        binding.chipAimiPref.setOnClickListener { listener.onAimiPreferencesClicked() }
        binding.chipStat.setOnClickListener { listener.onStatsClicked() }
    }
}

interface CircleTopActionListener {
    fun onAimiAdvisorClicked()
    fun onAdjustClicked()
    fun onAimiPreferencesClicked()
    fun onStatsClicked()
}
```

---

### **4. ViewModel Extension - StatusCardState** ⏳ À MODIFIER

**Fichier** : `/Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/viewmodel/OverviewViewModel.kt`

**Modifications** :

```kotlin
// Line ~610 : Extend StatusCardState
data class StatusCardState(
    // ... existing fields ...
    
    // NOUVEAUX CHAMPS (feature/circle-top) :
    val reservoirText: String? = null,
    val infusionAgeText: String? = null,
    val sensorAgeText: String? = null,
    val basalText: String? = null,
    val glucoseMgdl: Int? = null,
    val noseAngleDeg: Float? = null,
    val lastUpdateText: String? = null,
    val activityPctText: String? = null,
    val pumpBatteryText: String? = null
)
```

**Population dans `updateStatus()`** :

```kotlin
private fun updateStatus() {
    // ... existing code ...
    
    // Calculate nose angle from delta
    val delta = glucoseStatusProvider.glucoseStatusData?.delta ?: 0.0
    val noseAngleDeg = when {
        delta > 10 -> 45f   // Rapidly rising
        delta > 5 -> 20f    // Rising
        delta < -10 -> -45f // Rapidly falling
        delta < -5 -> -20f  // Falling
        else -> 0f          // Stable
    }
    
    // Calculate extended fields
    val reservoirText = activePlugin.activePump.pumpDescription.reservoirLevel?.let { 
        decimalFormatter.to2Decimal(it) + " IE" 
    }
    
    // TODO: Calculate infusionAge, sensorAge, etc.
    
    val state = StatusCardState(
        // ... existing fields ...
        glucoseMgdl = lastBg?.recalculated?.toInt(),
        noseAngleDeg = noseAngleDeg,
        reservoirText = reservoirText,
        // ... other new fields ...
    )
}
```

---

### **5. Fragment Integration - OverviewFragment.kt** ⏳ À MODIFIER

**Fichier** : `/Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/OverviewFragment.kt`

**Modifications** :

```kotlin
// Dans onViewCreated() :
binding.statusCard.setActionListener(object : CircleTopActionListener {
    override fun onAimiAdvisorClicked() {
        startActivity(Intent(requireContext(), AimiProfileAdvisorActivity::class.java))
    }
    override fun onAdjustClicked() {
        uiInteraction.runLoopDialog(childFragmentManager, 1)
    }
    override fun onAimiPreferencesClicked() {
        startActivity(Intent(requireContext(), uiInteraction.preferencesActivity)
            .putExtra(UiInteraction.PLUGIN_NAME, "AIMI"))
    }
    override fun onStatsClicked() {
        // TODO: Show stats dialog
    }
})

// Observer StatusCardState :
overviewViewModel.statusCardState.observe(viewLifecycleOwner) { state ->
    binding.statusCard.update(state)
    
    // Update unicorn color (CONSERVÉ)
    binding.statusCard.findViewById<ImageView>(R.id.unicorn_icon)?.setColorFilter(
        when {
            state.glucoseMgdl == null -> Color.GRAY
            state.glucoseMgdl < 54 -> ContextCompat.getColor(requireContext(), R.color.critical_low)
            state.glucoseMgdl < 70 -> ContextCompat.getColor(requireContext(), R.color.low)
            state.glucoseMgdl <= 180 -> ContextCompat.getColor(requireContext(), R.color.inRange)
            state.glucoseMgdl <= 250 -> ContextCompat.getColor(requireContext(), R.color.high)
            else -> ContextCompat.getColor(requireContext(), R.color.critical_high)
        },
        PorterDuff.Mode.SRC_ATOP
    )
}
```

---

## 📈 **GRAPH TREATMENTS OVERLAY**

### **GraphData.kt Modifications** ⏳ À FAIRE

**Fichier** : `/Users/mtr/StudioProjects/OpenApsAIMI/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/graphData/GraphData.kt`

**Ajout de la méthode `addTreatments()`** :

```kotlin
fun addTreatments(context: Context?) {
    // Fetch treatments from persistence layer
    val boluses = persistenceLayer.getBolusesDataFromTime(overviewData.fromTime, overviewData.endTime, false)
    val carbs = persistenceLayer.getCarbsDataFromTimeToTime(overviewData.fromTime, overviewData.endTime, false)
    
    // Render bolus markers (blue triangles ▲)
    val bolusPaint = Paint().apply {
        color = Color.parseColor("#3F51B5")  // Blue
        style = Paint.Style.FILL
    }
    for (bolus in boluses) {
        if (bolus.type == Bolus.Type.NORMAL) {
            // Draw triangle at bottom of graph at bolus timestamp
            val x = graphView.getXCoordinate(bolus.timestamp)
            val y = graphView.graphContentBottom
            drawTriangle(x, y, bolusPaint)
        }
    }
    
    // Render SMB markers (cyan triangles ▲)
    val smbPaint = Paint().apply {
        color = Color.parseColor("#00BCD4")  // Cyan
        style = Paint.Style.FILL
    }
    for (bolus in boluses) {
        if (bolus.type == Bolus.Type.SMB) {
            val x = graphView.getXCoordinate(bolus.timestamp)
            val y = graphView.graphContentBottom
            drawTriangle(x, y, smbPaint)
        }
    }
    
    // Render carb bars (orange vertical bars)
    val carbPaint = Paint().apply {
        color = Color.parseColor("#FF9800")  // Orange
        style = Paint.Style.FILL
    }
    for (carb in carbs) {
        val x = graphView.getXCoordinate(carb.timestamp)
        val height = (carb.amount * 2).toFloat()  // Scale carbs to pixel height
        drawVerticalBar(x, height, carbPaint)
    }
}

private fun drawTriangle(x: Float, y: Float, paint: Paint) {
    val path = Path().apply {
        moveTo(x, y)
        lineTo(x - 5f, y + 10f)
        lineTo(x + 5f, y + 10f)
        close()
    }
    graphView.canvas.drawPath(path, paint)
}

private fun drawVerticalBar(x: Float, height: Float, paint: Paint) {
    graphView.canvas.drawRect(x - 3f, 0f, x + 3f, height, paint)
}
```

---

## 🎨 **RESOURCES NÉCESSAIRES**

### **Drawables** ⏳ À AJOUTER

**Fichiers à copier du GitHub** :

| Drawable | Source | Target | Notes |
|----------|--------|--------|-------|
| `ic_cp_age_insulin.xml` | feature/circle-top | `plugins/main/src/main/res/drawable/` | Icône reservoir |
| `ic_cp_age_cannula.xml` | feature/circle-top | `plugins/main/src/main/res/drawable/` | Icône infusion age |
| `ic_cp_age_sensor.xml` | feature/circle-top | `plugins/main/src/main/res/drawable/` | Icône sensor age |
| `ic_dashboard_battery.xml` | feature/circle-top | `plugins/main/src/main/res/drawable/` | Icône pump battery |
| `ic_time.xml` | feature/circle-top | `plugins/main/src/main/res/drawable/` | Icône last update |
| `ic_activity.xml` | feature/circle-top | `plugins/main/src/main/res/drawable/` | Icône activity % |
| `ic_cp_basal.xml` | feature/circle-top | `plugins/main/src/main/res/drawable/` | Icône basal |
| `ic_sensor_reading.xml` | feature/circle-top | `plugins/main/src/main/res/drawable/` | Icône TBR rate |

**Note** : Si conflits de noms, préfixer avec `circle_top_`

---

### **Strings** ⏳ À AJOUTER

**Fichier** : `plugins/main/src/main/res/values/strings.xml`

```xml
<string name="reservoir_short">Reservoir</string>
<string name="infusion_age">Infusion Age</string>
<string name="sensor_age">Sensor Age</string>
<string name="pump_battery">Pump Battery</string>
<string name="last_update">Last Update</string>
<string name="activity_pct">Activity %</string>
<string name="basal">Basal</string>
<string name="advisor_button">Advisor</string>
<string name="adjust_button">Adjust</string>
<string name="prefs_button">Prefs</string>
<string name="stats_button">Stats</string>
```

---

### **Styles** ⏳ À AJOUTER

**Fichier** : `core/ui/src/main/res/values/styles.xml`

```xml
<style name="GlucoseRingViewStepped">
    <item name="ringStrokeWidth">5dp</item>
    <item name="ringInRangeColor">@color/glucose_in_range</item>
    <item name="ringHighColor">@color/glucose_high</item>
    <item name="ringCriticalHighColor">@color/glucose_critical_high</item>
    <item name="ringLowColor">@color/glucose_low</item>
</style>
```

---

## 🔧 **ÉTAPES D'IMPLÉMENTATION**

### **Phase 1 : Core Custom View** ✅ FAIT

- [x] Créer `GlucoseRingView.kt` (avec code du GitHub)
- [x] Ajouter styles
- [x] Ajouter colors

### **Phase 2 : Layout Hybride** ⏳ EN COURS

- [ ] Créer `component_circle_top_status_hybrid.xml`
- [ ] Vérifier tous les IDs
- [ ] Tester inflation layout

### **Phase 3 : Kotlin View Class** ⏳ EN COURS

- [ ] Créer `CircleTopStatusHybridView.kt`
- [ ] Implémenter `update()` method
- [ ] Implémenter `setActionListener()`

### **Phase 4 : ViewModel** ⏳ TO DO

- [ ] Étendre `StatusCardState` avec nouveaux champs
- [ ] Calculer `noseAngleDeg` depuis delta
- [ ] Calculer reservoir, ages, etc.

### **Phase 5 : Fragment Integration** ⏳ TO DO

- [ ] Modifier `OverviewFragment.kt`
- [ ] Bind `CircleTopStatusHybridView`
- [ ] Setup action listeners
- [ ] Conserver unicorn color logic

### **Phase 6 : Graph Overlays** ⏳ TO DO

- [ ] Modifier `GraphData.kt`
- [ ] Implémenter `addTreatments()`
- [ ] Dessiner bolus/SMB/carbs markers

### **Phase 7 : Testing** ⏳ TO DO

- [ ] Build APK
- [ ] Test visual layout
- [ ] Test unicorn color changes
- [ ] Test badges (context + auditor)
- [ ] Test nose pointer rotation
- [ ] Test graph overlays

---

## 🎯 **RÉSULTATS ATTENDUS**

**Ce que tu auras à la fin** :

1. ✅ **Licorne dynamique** (ta feature) - conservée
2. ✅ **Badges AIMI** (context + auditor) - conservés
3. ✅ **Cercle GlucoseRingView** avec "nose pointer" - ajouté
4. ✅ **Métriques 2 colonnes** (8 infos) - ajoutées
5. ✅ **Action chips** (4 boutons) - ajoutés
6. ✅ **Graph avec treatments** (bolus/SMB/carbs overlays) - ajouté

**Dashboard le plus informatif et esthétique de tous les APS au monde !** 🚀

---

## ⏭️ **NEXT STEPS**

MTR, je vais maintenant :

1. ✅ Créer le fichier `GlucoseRingView.kt` (code du GitHub)
2. ✅ Créer le layout `component_circle_top_status_hybrid.xml`
3. ✅ Créer la classe `CircleTopStatusHybridView.kt`

**Prêt à commencer ?** 💪
