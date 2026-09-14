# Glass Loop Dashboard (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a live "Glass Loop Dashboard" screen (Glucose & Insulin Dynamics + Key Factors) reachable from the
Glass main screen's Loop pill, using only data this codebase actually has as real typed fields — no invented
or approximated values, no regex-scraping of formatted text.

**Architecture:** A new Hilt `ViewModel` (`GlassLoopDashboardViewModel`) reads live algorithm/status data from
`ActivePlugin.activeAPS.lastAPSResult`, `GlucoseStatusProvider`, `TddCalculator`, `TirCalculator`,
`PersistenceLayer`, `GlucoseStatusCalculatorAimi`, and `Config`, and exposes a single pre-formatted
`GlassLoopDashboardState` (all display-unit conversion and string formatting done in the ViewModel, matching
this codebase's established `StatusCardState`/`GlassUiState` pattern — never raw numbers formatted in the
Composable). A new Compose screen (`GlassLoopDashboardScreen`) renders it, reached via a real app-navigation
route (`AppRoute`, registered in `:app`'s nav graph) rather than an in-place overlay, matching how
`onOpenStats`/`onOpenTreatments` already route from the Glass screen. The Loop pill on `StatusAgoraCard` gets a
short-tap → Loop Dashboard / long-press → running-mode-dialog split gesture, using the same
`combinedClickable` pattern already used elsewhere in this codebase (e.g. `TempBasalScreen.kt`).

**Tech Stack:** Android, Kotlin, Jetpack Compose (Material3), Hilt, Kotlin coroutines, Jetpack Navigation
Compose.

**Spec:** `docs/superpowers/specs/2026-09-10-glass-dashboard-port-design.md` (Phase 2). This plan also resolves
that spec's two "open questions for implementation time" via decisions made with the user before this plan was
written (see Global Constraints below).

## Background: why this plan differs from the reference

The reference project's `GlassLoopDashboardViewModel.kt`
(`~/Downloads/OpenApsAIMI-Tarciso-test-v242/plugins/main/src/main/kotlin/app/aaps/plugins/main/general/overview/glass/`)
gets most of its "Key Factors" data by taking `activePlugin.activeAPS.lastAPSResult.toSpanned().toString()` (the
algorithm's human-readable reason/explanation text) and REGEX-SCRAPING that string for numbers next to labels
like "React Factor", "Hourly Factor", "TDD Adjust:", "Low Glucose Alarms:". A dedicated research pass (grepping
this entire repo, not the reference) confirmed that:

- Several of those concepts (React Factor / Hourly Factor as a profile-vs-adjusted pair, TDD Adjust status
  string, BG Adjust status string, Low Glucose Alarms counter, the target-protection ON/OFF flag) **do not
  exist anywhere in this codebase** — not just untyped, genuinely removed/replaced by different subsystems as
  this fork's algorithm evolved.
- Some values (dynamic, per-cycle MaxIOB/MaxSMB after physio/ladder adjustments) exist ONLY as `private var`s
  inside the 19,000-line `DetermineBasalAIMI2.kt` algorithm file, never written back to any externally-visible
  object. Exposing them would mean modifying safety-critical dosing logic just to feed a UI screen — out of
  scope for a UI port.
- The rest of the reference's fields DO have real, typed, already-injectable sources in this codebase, listed
  field-by-field in the Task briefs below — traced directly from source, not assumed.

**Decision (made with the user before this plan was written):** the ported screen drops every card/row whose
underlying data does not exist as a real value in this codebase, rather than inventing, approximating, or
re-scraping text for it. MaxIOB/MaxSMB are shown as a single "profile" value (the constraint-checked ceiling),
not the reference's profile→dynamic arrow comparison. No git commit until the user explicitly asks (standing
rule this session).

## Global Constraints

- No hardcoded user-facing strings — every label/unit/template is a string resource. No string concatenation
  for user-facing text — use positional format-string resources (existing ones where they already fit:
  `format_insulin_units` = `"%1$.2f U"`, `format_mins` = `"%1$d min"`, `format_percent` = `"%1$d%%"`,
  `pump_base_basal_rate` = `"%1$.2f U/h"`, `profile_isf_units_mgdl`/`profile_isf_units_mmol` = `"mg/dL/U"`/
  `"mmol/L/U"` — all four already exist in `core:ui`'s `strings.xml`, do not recreate them).
- All glucose/ISF/target values must be converted from mg/dL to the user's display unit before being shown —
  use `ProfileUtil.fromMgdlToStringInUnits(Double?)` for plain BG values, `ProfileUtil.fromMgdlToSignedStringInUnits(Double)`
  for deltas, and `ProfileUtil.fromMgdlToUnits(Double)` (the raw numeric converter) for ISF, paired with the
  `profile_isf_units_mgdl`/`profile_isf_units_mmol` resource picked by `profileFunction.getUnits() == GlucoseUnit.MGDL`.
  This exact bug class (a value shown in the wrong unit, or a hardcoded "mg/dL") was a Critical finding twice
  already in this feature's earlier plans — do not repeat it.
- `GlassLoopDashboardState` carries only pre-formatted `String`/`Boolean`/`Int` fields the Composable renders
  directly — no raw `Double` glucose/insulin/ISF values cross into the Composable. This mirrors
  `StatusCardState`'s and `GlassUiState`'s existing pattern and removes the entire class of unit-conversion
  bugs from the Composable layer by construction.
- No `git commit` during this plan's execution (standing user rule).
- No new inter-module Gradle dependencies — `plugins:main` already depends on `plugins:aps` (see
  `plugins/main/build.gradle.kts:31`, `implementation(project(":plugins:aps"))`), so `GlucoseStatusCalculatorAimi`
  is already reachable; no new dependency is needed for the `:app`-module navigation wiring either (it's an
  existing module already containing `AppRoute`/`AppNavGraph`/`ComposeMainActivity`).
- Explicit imports only — no fully-qualified names inline (project-wide rule).
- Use `stringResource()` in Composables, `ResourceHelper`/`rh.gs(...)` in the ViewModel (project-wide rule).

## Decisions already made with the user (do not re-litigate)

1. **Loop Dashboard entry point:** short tap on the Loop pill (`StatusAgoraCard.kt`, the `GlassPill` labeled
   with `R.string.dashboard_glass_loop_label`) opens the new Loop Dashboard screen. Long-press on the same pill
   keeps the EXISTING behavior (`onOpenLoop` → running-mode dialog). The separate `onOpenLoop` binding on the
   big glucose number (`StatusAgoraCard.kt` line ~133) is untouched — that is a different existing gesture, not
   in scope.
2. **Fields with no real equivalent in this codebase are DROPPED from the ported screen**, not approximated:
   `predictedBg`/`protection` ON/OFF flag, `reactFactorProfile`/`reactFactorAdjusted`,
   `hourlyFactorProfile`/`hourlyFactorAdjusted`, `tddStatus`, `tirStatus`, `lowGlucoseAlarms1h`/`lowGlucoseAlarms24h`,
   the "Auto Mode: ON/OFF" top banner (no single clean equivalent toggle found), the reference's
   `aimiVersion`/"Chg. Ver. 240" hardcoded string, and the "GlycoCalm Intelligent Automated Insulin Delivery
   System" tagline (names a different product, not ours).
3. **MaxIOB/MaxSMB show only the "profile" (constraint-checked ceiling) value**, as a plain `MetricCell`, not
   the reference's `ArrowMetricCell` profile→dynamic comparison — the dynamic value only exists as a private
   algorithm variable and exposing it is out of scope for this plan.

## Field-by-field data sourcing (verified against this repo's source, not assumed)

| `GlassLoopDashboardState` field | Real typed source in this repo |
|---|---|
| `lastRunTime` | `activePlugin.activeAPS?.lastAPSResult?.date` (`Long`, from `app.aaps.core.interfaces.aps.APSResult`) → `dateUtil.timeString(date)` if `date > 0`, else `""` |
| `requestedSmbText` | `lastAPSResult?.smb ?: 0.0` (`APSResult.smb: Double`) → `resourceHelper.gs(CoreUiR.string.format_insulin_units, smb)` |
| `glucoseText` | `glucoseStatusProvider.glucoseStatusData?.glucose ?: 0.0` (mg/dL, `app.aaps.core.interfaces.aps.GlucoseStatus.glucose`) → `profileUtil.fromMgdlToStringInUnits(glucoseMgdl)` |
| `delta5mText` + `delta5mIsPositive` | `glucoseStatusData?.delta ?: 0.0` (mg/dL, `GlucoseStatus.delta`) → `profileUtil.fromMgdlToSignedStringInUnits(deltaMgdl)`; `delta5mIsPositive = deltaMgdl >= 0.0` |
| `shortAvgDeltaText` + `shortAvgDeltaIsPositive` | `glucoseStatusData?.shortAvgDelta ?: 0.0` (mg/dL) → same signed formatter |
| `longAvgDeltaText` + `longAvgDeltaIsPositive` | `glucoseStatusData?.longAvgDelta ?: 0.0` (mg/dL) → same signed formatter |
| `iobText` | `lastAPSResult?.iob` (`APSResult.iob: IobTotal? get() = iobData?.get(0)`) → `(it.iob + it.basaliob)` if non-null else `0.0`, formatted via `format_insulin_units` |
| `targetBgText` | `lastAPSResult?.targetBG ?: 0.0` (mg/dL, `APSResult.targetBG: Double`) → `profileUtil.fromMgdlToStringInUnits(targetBgMgdl)` |
| `maxIobProfileText` | `lastAPSResult?.oapsProfileAimi?.max_iob ?: 0.0` (`OapsProfileAimi.max_iob: Double`, already constraint-checked — set from `constraintsChecker.getMaxIOBAllowed().value()`) → `format_insulin_units` |
| `maxSmbProfileText` | `preferences.get(DoubleKey.OApsAIMIMaxSMB)` (raw preference, `core/keys/.../DoubleKey.kt:310`, default 1.0) → `format_insulin_units` |
| `isfText` | `lastAPSResult?.variableSens ?: 0.0` (mg/dL/U, `APSResult.variableSens: Double?`) → `profileUtil.fromMgdlToUnits(isfMgdl)` formatted with `"%.0f"` + a space + `resourceHelper.gs(if (profileFunction.getUnits() == GlucoseUnit.MGDL) CoreUiR.string.profile_isf_units_mgdl else CoreUiR.string.profile_isf_units_mmol)` |
| `stableMinutesText` | `glucoseStatusCalculatorAimi.getAimiFeatures(allowOldData = true)?.stable5pctMinutes ?: 0.0` (`AimiBgFeatures.stable5pctMinutes: Double`, `plugins/aps/.../openAPSAIMI/GlucoseStatusCalculatorAimi.kt`, non-suspend) → `resourceHelper.gs(CoreUiR.string.format_mins, minutes.roundToInt())` |
| `tdd7DaysPerHourText` | `tddCalculator.calculateDaily(-7, 0)?.totalAmount?.div(24.0) ?: 0.0` (`TddCalculator.calculateDaily(startHours: Long, endHours: Long): TDD?`, `TDD.totalAmount: Double`) → `resourceHelper.gs(CoreUiR.string.pump_base_basal_rate, tddPerHour)` (reuses the existing "%1$.2f U/h" template — this is a display convenience, not a real basal rate, but the unit and format match exactly) |
| `tirLow1hText` | `tirCalculator.averageTIR(tirCalculator.calculateHour(70.0, 180.0)).belowPct() ?: 0.0` (standard 70/180 mg/dL thresholds — `TirCalculator`'s own KDoc names these as the "common threshold values") → `resourceHelper.gs(CoreUiR.string.format_percent, pct.roundToInt())` |
| `tirLow24hText` | `tirCalculator.averageTIR(tirCalculator.calculateDaily(70.0, 180.0)).belowPct() ?: 0.0` → same formatter |
| `steps5mText` | `persistenceLayer.getLastStepsCountFromTimeToTime(nowEpochMs - 24 * 3_600_000L, nowEpochMs)?.steps5min ?: 0` (`SC.steps5min: Int`, `core/data/.../model/SC.kt`) → `resourceHelper.gs(app.aaps.plugins.main.R.string.dashboard_glass_loop_steps_5m, steps)` (new template, see Task 1) |
| `hourOfDay` | `java.util.Calendar.getInstance().get(Calendar.HOUR_OF_DAY)` — computed fresh, not read from the algorithm (confirmed not exposed anywhere reachable) |
| `isWeekend` | `java.util.Calendar.getInstance().get(Calendar.DAY_OF_WEEK)` is `Calendar.SATURDAY` or `Calendar.SUNDAY` |
| `buildVersionText` | `config.VERSION_NAME` (`app.aaps.core.interfaces.configuration.Config.VERSION_NAME: String`) |
| `isLoading` | `true` while a refresh coroutine is in flight, `false` once done — same pattern as the reference |

## Task 1: String resources + `GlassLoopDashboardModels.kt`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardModels.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `GlassLoopDashboardState` data class, consumed by Task 2 (built by the ViewModel) and Task 3
  (rendered by the screen).

- [ ] **Step 1: Add string resources**

Add these entries to `plugins/main/src/main/res/values/strings.xml` (anywhere alongside the existing
`dashboard_glass_*` entries added by the prior Glass plans):

```xml
<string name="dashboard_glass_loop_dashboard_title">Loop Dashboard</string>
<string name="dashboard_glass_loop_dashboard_last_run" comment="%1$s=time, e.g. 14:32">Last run: today at %1$s</string>
<string name="dashboard_glass_loop_dashboard_pump_label">PUMP</string>
<string name="dashboard_glass_loop_dashboard_requested" comment="%1$s=insulin amount with unit, e.g. 0.50 U">%1$s requested</string>
<string name="dashboard_glass_loop_dynamics_title">Glucose &amp; Insulin Dynamics</string>
<string name="dashboard_glass_loop_glucose_label">GLUCOSE</string>
<string name="dashboard_glass_loop_delta_5m_label" comment="e.g. -1.20 5m delta">5m delta</string>
<string name="dashboard_glass_loop_delta_avg_label">DELTA (SHORT / LONG)</string>
<string name="dashboard_glass_loop_delta_avg_hint">5m / 15m avg</string>
<string name="dashboard_glass_loop_iob_label">IOB</string>
<string name="dashboard_glass_loop_iob_hint">Active Insulin</string>
<string name="dashboard_glass_loop_target_label">TARGET</string>
<string name="dashboard_glass_loop_tdd_label">TDD 7 Days</string>
<string name="dashboard_glass_loop_key_factors_title">Key Factors</string>
<string name="dashboard_glass_loop_max_iob_label">MAX IOB</string>
<string name="dashboard_glass_loop_max_smb_label">MAX SMB</string>
<string name="dashboard_glass_loop_isf_label">ISF SENSITIVITY</string>
<string name="dashboard_glass_loop_stable_bg_label">BG STABLE</string>
<string name="dashboard_glass_loop_weekday">Weekday</string>
<string name="dashboard_glass_loop_weekend">Weekend</string>
<string name="dashboard_glass_loop_hour_badge" comment="%1$s=2-digit hour e.g. 14, %2$s=Weekday or Weekend">%1$sh • %2$s</string>
<string name="dashboard_glass_loop_safety_title">Safety &amp; Alarms</string>
<string name="dashboard_glass_loop_tir_low_label">Low TIR (1h / 24h)</string>
<string name="dashboard_glass_loop_tir_low_value" comment="%1$s=percent e.g. 5%%, %2$s=percent e.g. 8%%">%1$s / %2$s</string>
<string name="dashboard_glass_loop_steps_label">Recent Activity / Steps</string>
<string name="dashboard_glass_loop_steps_5m" comment="%1$d=step count">%1$d steps (5m)</string>
<string name="dashboard_glass_loop_footer" comment="%1$s=insulin amount with unit e.g. 0.50 U, %2$s=app version e.g. 3.5.0">Requested %1$s to pump • %2$s</string>
```

- [ ] **Step 2: Create `GlassLoopDashboardModels.kt`**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

/**
 * UI state for the ported Glass Loop Dashboard screen. Every glucose/insulin/ISF value is already
 * converted to the user's display unit and formatted as a String by the ViewModel — this screen never
 * sees a raw mg/dL Double, so it cannot reintroduce a unit-conversion bug.
 */
data class GlassLoopDashboardState(
    val isLoading: Boolean = false,
    val lastRunTime: String = "",
    val requestedSmbText: String = "--",
    val glucoseText: String = "--",
    val delta5mText: String = "--",
    val delta5mIsPositive: Boolean = true,
    val shortAvgDeltaText: String = "--",
    val shortAvgDeltaIsPositive: Boolean = true,
    val longAvgDeltaText: String = "--",
    val longAvgDeltaIsPositive: Boolean = true,
    val iobText: String = "--",
    val targetBgText: String = "--",
    val maxIobProfileText: String = "--",
    val maxSmbProfileText: String = "--",
    val isfText: String = "--",
    val stableMinutesText: String = "--",
    val tdd7DaysPerHourText: String = "--",
    val tirLow1hText: String = "--",
    val tirLow24hText: String = "--",
    val steps5mText: String = "--",
    val hourOfDay: Int = 0,
    val isWeekend: Boolean = false,
    val buildVersionText: String = "",
)
```

- [ ] **Step 3: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL (this task adds
only a data class with no logic and resource strings — nothing to unit-test on its own).

---

## Task 2: `GlassLoopDashboardViewModel.kt`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardViewModel.kt`
- Test: `plugins/main/src/test/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardViewModelTest.kt`

**Interfaces:**
- Consumes: `GlassLoopDashboardState` (Task 1).
- Produces: `GlassLoopDashboardViewModel` with `val uiState: StateFlow<GlassLoopDashboardState>` and
  `fun refresh()`, consumed by Task 3 (screen) and Task 4 (nav graph, via `hiltViewModel()`).

Unlike the reference's plain `ViewModel()` with a manual `.init(...)` call, this is a real Hilt
`@HiltViewModel` with constructor injection — matching this codebase's `TreatmentsViewModel`/`StatsViewModel`
pattern, not the reference's Fragment-glue idiom.

- [ ] **Step 1: Write the ViewModel**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.interfaces.aps.GlucoseStatusProvider
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.R as CoreUiR
import app.aaps.plugins.aps.openAPSAIMI.GlucoseStatusCalculatorAimi
import app.aaps.plugins.main.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class GlassLoopDashboardViewModel @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val glucoseStatusProvider: GlucoseStatusProvider,
    private val tddCalculator: TddCalculator,
    private val tirCalculator: TirCalculator,
    private val glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi,
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    private val profileUtil: ProfileUtil,
    private val preferences: Preferences,
    private val resourceHelper: ResourceHelper,
    private val dateUtil: DateUtil,
    private val config: Config,
) : ViewModel() {

    private val _uiState = MutableStateFlow(GlassLoopDashboardState())
    val uiState: StateFlow<GlassLoopDashboardState> = _uiState.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val newState = withContext(Dispatchers.IO) {
                val result = activePlugin.activeAPS?.lastAPSResult

                val smbMgdl = result?.smb ?: 0.0
                val requestedSmbText = resourceHelper.gs(CoreUiR.string.format_insulin_units, smbMgdl)

                val glucoseStatus = glucoseStatusProvider.glucoseStatusData
                val glucoseMgdl = glucoseStatus?.glucose ?: 0.0
                val delta5mMgdl = glucoseStatus?.delta ?: 0.0
                val shortAvgDeltaMgdl = glucoseStatus?.shortAvgDelta ?: 0.0
                val longAvgDeltaMgdl = glucoseStatus?.longAvgDelta ?: 0.0

                val iob = result?.iob
                val iobTotal = (iob?.iob ?: 0.0) + (iob?.basaliob ?: 0.0)

                val targetBgMgdl = result?.targetBG ?: 0.0
                val maxIobProfile = result?.oapsProfileAimi?.max_iob ?: 0.0
                val maxSmbProfile = preferences.get(DoubleKey.OApsAIMIMaxSMB)
                val isfMgdl = result?.variableSens ?: 0.0
                val isfDisplay = profileUtil.fromMgdlToUnits(isfMgdl)
                val isfUnitRes = if (profileFunction.getUnits() == GlucoseUnit.MGDL)
                    CoreUiR.string.profile_isf_units_mgdl
                else
                    CoreUiR.string.profile_isf_units_mmol

                val stableMinutes = glucoseStatusCalculatorAimi.getAimiFeatures(allowOldData = true)?.stable5pctMinutes ?: 0.0

                val tdd7 = tddCalculator.calculateDaily(-7, 0)
                val tdd7PerHour = (tdd7?.totalAmount ?: 0.0) / 24.0

                val tirLow1h = tirCalculator.averageTIR(tirCalculator.calculateHour(70.0, 180.0)).belowPct() ?: 0.0
                val tirLow24h = tirCalculator.averageTIR(tirCalculator.calculateDaily(70.0, 180.0)).belowPct() ?: 0.0

                val nowEpochMs = dateUtil.now()
                val steps5m = persistenceLayer.getLastStepsCountFromTimeToTime(
                    nowEpochMs - 24 * 3_600_000L,
                    nowEpochMs
                )?.steps5min ?: 0

                val calendar = Calendar.getInstance()
                val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY

                val lastRunTime = (result?.date ?: 0L).let { date -> if (date > 0L) dateUtil.timeString(date) else "" }

                GlassLoopDashboardState(
                    isLoading = false,
                    lastRunTime = lastRunTime,
                    requestedSmbText = requestedSmbText,
                    glucoseText = profileUtil.fromMgdlToStringInUnits(glucoseMgdl),
                    delta5mText = profileUtil.fromMgdlToSignedStringInUnits(delta5mMgdl),
                    delta5mIsPositive = delta5mMgdl >= 0.0,
                    shortAvgDeltaText = profileUtil.fromMgdlToSignedStringInUnits(shortAvgDeltaMgdl),
                    shortAvgDeltaIsPositive = shortAvgDeltaMgdl >= 0.0,
                    longAvgDeltaText = profileUtil.fromMgdlToSignedStringInUnits(longAvgDeltaMgdl),
                    longAvgDeltaIsPositive = longAvgDeltaMgdl >= 0.0,
                    iobText = resourceHelper.gs(CoreUiR.string.format_insulin_units, iobTotal),
                    targetBgText = profileUtil.fromMgdlToStringInUnits(targetBgMgdl),
                    maxIobProfileText = resourceHelper.gs(CoreUiR.string.format_insulin_units, maxIobProfile),
                    maxSmbProfileText = resourceHelper.gs(CoreUiR.string.format_insulin_units, maxSmbProfile),
                    isfText = "${isfDisplay.roundToInt()} ${resourceHelper.gs(isfUnitRes)}",
                    stableMinutesText = resourceHelper.gs(CoreUiR.string.format_mins, stableMinutes.roundToInt()),
                    tdd7DaysPerHourText = resourceHelper.gs(CoreUiR.string.pump_base_basal_rate, tdd7PerHour),
                    tirLow1hText = resourceHelper.gs(CoreUiR.string.format_percent, tirLow1h.roundToInt()),
                    tirLow24hText = resourceHelper.gs(CoreUiR.string.format_percent, tirLow24h.roundToInt()),
                    steps5mText = resourceHelper.gs(R.string.dashboard_glass_loop_steps_5m, steps5m),
                    hourOfDay = hourOfDay,
                    isWeekend = isWeekend,
                    buildVersionText = config.VERSION_NAME,
                )
            }

            _uiState.update { newState }
        }
    }
}
```

`DateUtil.now(): Long` (plain `System.currentTimeMillis()` wrapper) is already used the same way across this
codebase — confirm the exact method name is `now()` when writing this file (it is the standard AAPS
`DateUtil` method for "current time in ms"; if it differs, use `System.currentTimeMillis()` directly, which is
equally correct here since no test-clock injection is needed for a live status screen).

- [ ] **Step 2: Write the test**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.GlucoseStatus
import app.aaps.core.interfaces.aps.GlucoseStatusProvider
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.GlucoseUnit
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.stats.TDD
import app.aaps.core.interfaces.stats.TIR
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.GlucoseStatusCalculatorAimi
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class GlassLoopDashboardViewModelTest {

    private val activePlugin: ActivePlugin = mock()
    private val glucoseStatusProvider: GlucoseStatusProvider = mock()
    private val tddCalculator: TddCalculator = mock()
    private val tirCalculator: TirCalculator = mock()
    private val glucoseStatusCalculatorAimi: GlucoseStatusCalculatorAimi = mock()
    private val persistenceLayer: PersistenceLayer = mock()
    private val profileFunction: ProfileFunction = mock()
    private val profileUtil: ProfileUtil = mock()
    private val preferences: Preferences = mock()
    private val resourceHelper: ResourceHelper = mock()
    private val dateUtil: DateUtil = mock()
    private val config: Config = mock()

    private lateinit var viewModel: GlassLoopDashboardViewModel

    @BeforeEach
    fun setup() {
        kotlinx.coroutines.test.Dispatchers.setMain(StandardTestDispatcher())
        whenever(resourceHelper.gs(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn("formatted")
        whenever(resourceHelper.gs(org.mockito.kotlin.any())).thenReturn("unit")
        whenever(profileUtil.fromMgdlToStringInUnits(org.mockito.kotlin.any())).thenReturn("120")
        whenever(profileUtil.fromMgdlToSignedStringInUnits(org.mockito.kotlin.any())).thenReturn("+1.0")
        whenever(profileUtil.fromMgdlToUnits(org.mockito.kotlin.any())).thenReturn(40.0)
        whenever(profileFunction.getUnits()).thenReturn(GlucoseUnit.MGDL)
        whenever(config.VERSION_NAME).thenReturn("3.5.0")
        whenever(dateUtil.now()).thenReturn(1_000_000_000L)
        whenever(dateUtil.timeString(org.mockito.kotlin.any())).thenReturn("14:32")

        viewModel = GlassLoopDashboardViewModel(
            activePlugin, glucoseStatusProvider, tddCalculator, tirCalculator, glucoseStatusCalculatorAimi,
            persistenceLayer, profileFunction, profileUtil, preferences, resourceHelper, dateUtil, config,
        )
    }

    @AfterEach
    fun tearDown() {
        kotlinx.coroutines.test.Dispatchers.resetMain()
    }

    @Test
    fun `a null lastAPSResult produces a state with defaulted zero-based text, no crash`() = runTest {
        whenever(activePlugin.activeAPS).thenReturn(null)
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(null)
        whenever(glucoseStatusCalculatorAimi.getAimiFeatures(true)).thenReturn(null)
        whenever(tddCalculator.calculateDaily(-7, 0)).thenReturn(null)
        val emptyTir: TIR = mock()
        whenever(emptyTir.belowPct()).thenReturn(null)
        whenever(tirCalculator.calculateHour(70.0, 180.0)).thenReturn(mock())
        whenever(tirCalculator.calculateDaily(70.0, 180.0)).thenReturn(mock())
        whenever(tirCalculator.averageTIR(org.mockito.kotlin.any())).thenReturn(emptyTir)
        whenever(persistenceLayer.getLastStepsCountFromTimeToTime(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(null)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.isLoading).isFalse()
        assertThat(state.lastRunTime).isEmpty()
        assertThat(state.buildVersionText).isEqualTo("3.5.0")
    }

    @Test
    fun `a real lastAPSResult with date greater than zero produces a non-empty lastRunTime`() = runTest {
        val apsResult: APSResult = mock()
        whenever(apsResult.date).thenReturn(1_000_000L)
        whenever(apsResult.smb).thenReturn(0.5)
        whenever(apsResult.targetBG).thenReturn(100.0)
        whenever(apsResult.variableSens).thenReturn(40.0)
        val iobTotal = IobTotal(time = 0L, iob = 0.3, basaliob = 0.2)
        whenever(apsResult.iob).thenReturn(iobTotal)
        val oapsProfile: OapsProfileAimi = mock()
        whenever(oapsProfile.max_iob).thenReturn(3.5)
        whenever(apsResult.oapsProfileAimi).thenReturn(oapsProfile)
        whenever(activePlugin.activeAPS?.lastAPSResult).thenReturn(apsResult)

        val glucoseStatus: GlucoseStatus = mock()
        whenever(glucoseStatus.glucose).thenReturn(120.0)
        whenever(glucoseStatus.delta).thenReturn(2.0)
        whenever(glucoseStatus.shortAvgDelta).thenReturn(1.5)
        whenever(glucoseStatus.longAvgDelta).thenReturn(1.0)
        whenever(glucoseStatusProvider.glucoseStatusData).thenReturn(glucoseStatus)

        whenever(glucoseStatusCalculatorAimi.getAimiFeatures(true)).thenReturn(null)
        whenever(tddCalculator.calculateDaily(-7, 0)).thenReturn(null)
        val emptyTir: TIR = mock()
        whenever(emptyTir.belowPct()).thenReturn(5.0)
        whenever(tirCalculator.averageTIR(org.mockito.kotlin.any())).thenReturn(emptyTir)
        whenever(persistenceLayer.getLastStepsCountFromTimeToTime(org.mockito.kotlin.any(), org.mockito.kotlin.any())).thenReturn(null)

        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertThat(state.lastRunTime).isEqualTo("14:32")
    }
}
```

Adjust mock setup as needed once the real `ActivePlugin.activeAPS` (nullable `APS?`) chaining is written — the
key behaviors to prove are: (a) a null `lastAPSResult` never crashes and produces defaulted state, (b) a real
result with `date > 0` produces a non-empty `lastRunTime`, and (c) `buildVersionText` always comes from
`Config.VERSION_NAME`, never a hardcoded literal. If mocking `activePlugin.activeAPS?.lastAPSResult` as a
chained nullable property proves awkward with Mockito, mock `activePlugin.activeAPS` to return a mock `APS`
whose `lastAPSResult` is stubbed instead of stubbing the chain directly — whichever compiles cleanly is fine,
the test's job is proving the two behaviors above, not a specific mocking style.

- [ ] **Step 3: Run the test**

Run: `./gradlew :plugins:main:testFullDebugUnitTest --tests "*GlassLoopDashboardViewModelTest*" --no-daemon`.
Expected: BUILD SUCCESSFUL, both tests passing.

- [ ] **Step 4: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 3: `GlassLoopDashboardScreen.kt`

**Files:**
- Create: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardScreen.kt`

**Interfaces:**
- Consumes: `GlassLoopDashboardState` (Task 1).
- Produces: `@Composable fun GlassLoopDashboardScreen(uiState: GlassLoopDashboardState, onBack: () -> Unit, isDark: Boolean)`, consumed by Task 4 (nav graph destination).

Ported from the reference's `GlassLoopDashboardScreen.kt`, keeping its visual language (colors, card shapes,
the `GlassCardInner`/`SectionCard`/`MetricCell` helper composables — all private to this file, a self-contained
port, not reusing `GlassOverviewComponents.kt`'s `GlassContainer`/`GlassPill` which use a visually different
26dp-shadow-box style built for the main dashboard's pills, not this screen's simpler 16dp bordered cards),
with every dropped field (see Global Constraints / Decisions section) removed rather than left as a dead
parameter, and every literal string replaced by `stringResource(...)`.

- [ ] **Step 1: Write the screen**

```kotlin
package app.aaps.plugins.main.general.dashboard.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aaps.plugins.main.R

@Composable
internal fun GlassLoopDashboardScreen(
    uiState: GlassLoopDashboardState,
    onBack: () -> Unit,
    isDark: Boolean,
) {
    val bgColorTop = if (isDark) Color(0xFF070E1B) else Color(0xFFF1F5F9)
    val bgColorBot = if (isDark) Color(0xFF0B1424) else Color(0xFFE8EEF8)
    val cardBgStart = if (isDark) Color(0x24FFFFFF) else Color(0xF5FFFFFF)
    val cardBgEnd = if (isDark) Color(0x0EFFFFFF) else Color(0xE0EEF2FA)
    val borderCard = if (isDark) Color(0x26FFFFFF) else Color(0xB8CBD5E1)
    val textBright = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textMuted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val cellBg = if (isDark) Color(0x12FFFFFF) else Color(0xFFF1F5F9)
    val cellBorder = if (isDark) Color(0x1AFFFFFF) else Color(0xFFE2E8F0)
    val skyBlue = Color(0xFF38BDF8)
    val emerald = Color(0xFF10B981)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444)
    val indigo = Color(0xFF6366F1)
    val iconBgBlue = if (isDark) Color(0xFF1E3A5F) else Color(0xFFDBEAFE)
    val iconBgGreen = if (isDark) Color(0xFF0D3320) else Color(0xFFD1FAE5)
    val iconBgAmber = if (isDark) Color(0xFF3D2E0A) else Color(0xFFFEF3C7)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgColorTop, bgColorBot, bgColorTop)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, tint = textBright)
                }
                Text(
                    text = stringResource(R.string.dashboard_glass_loop_dashboard_title),
                    color = textBright,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // HEADER CARD
            GlassCardInner(isDark, cardBgStart, cardBgEnd, borderCard) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        if (uiState.lastRunTime.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.dashboard_glass_loop_dashboard_last_run, uiState.lastRunTime),
                                color = textMuted,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = stringResource(R.string.dashboard_glass_loop_dashboard_pump_label),
                            color = textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Box(
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(emerald.copy(alpha = 0.12f))
                                .border(1.dp, emerald.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.dashboard_glass_loop_dashboard_requested, uiState.requestedSmbText),
                                color = emerald,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // CARD 1: GLUCOSE & INSULIN DYNAMICS
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Bolt, null, tint = skyBlue, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgBlue,
                title = stringResource(R.string.dashboard_glass_loop_dynamics_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_glucose_label),
                            value = uiState.glucoseText,
                            subContent = {
                                val deltaColor = if (uiState.delta5mIsPositive) emerald else red
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(uiState.delta5mText, color = deltaColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(R.string.dashboard_glass_loop_delta_5m_label),
                                        color = textMuted,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_delta_avg_label),
                            value = "${uiState.shortAvgDeltaText} / ${uiState.longAvgDeltaText}",
                            valueColor = when {
                                uiState.shortAvgDeltaIsPositive && uiState.longAvgDeltaIsPositive -> emerald
                                !uiState.shortAvgDeltaIsPositive && !uiState.longAvgDeltaIsPositive -> red
                                else -> amber
                            },
                            valueFontSize = 13.sp,
                            subContent = {
                                Text(stringResource(R.string.dashboard_glass_loop_delta_avg_hint), color = textMuted, fontSize = 9.sp)
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_iob_label),
                            labelColor = indigo,
                            value = uiState.iobText,
                            valueColor = indigo,
                            subContent = {
                                Text(stringResource(R.string.dashboard_glass_loop_iob_hint), color = textMuted, fontSize = 9.sp)
                            }
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_target_label),
                            value = uiState.targetBgText,
                        )
                    }
                    GlassCardInner(isDark, cellBg, cellBg, cellBorder) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.dashboard_glass_loop_tdd_label), color = textMuted, fontSize = 11.sp)
                            Text(uiState.tdd7DaysPerHourText, color = textBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // CARD 2: KEY FACTORS
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Tune, null, tint = emerald, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgGreen,
                title = stringResource(R.string.dashboard_glass_loop_key_factors_title),
                badge = {
                    val dayLabel = if (uiState.isWeekend)
                        stringResource(R.string.dashboard_glass_loop_weekend)
                    else
                        stringResource(R.string.dashboard_glass_loop_weekday)
                    val hourLabel = "%02d".format(uiState.hourOfDay)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9999.dp))
                            .background(if (isDark) Color(0x1AFFFFFF) else Color(0xFFE2E8F0))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            stringResource(R.string.dashboard_glass_loop_hour_badge, hourLabel, dayLabel),
                            color = textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_max_iob_label),
                            value = uiState.maxIobProfileText,
                            valueFontSize = 13.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_max_smb_label),
                            value = uiState.maxSmbProfileText,
                            valueFontSize = 13.sp,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_isf_label),
                            value = uiState.isfText,
                            valueFontSize = 13.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_stable_bg_label),
                            value = uiState.stableMinutesText,
                            valueFontSize = 13.sp,
                        )
                    }
                }
            }

            // CARD 3: SAFETY & ALARMS
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Shield, null, tint = amber, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgAmber,
                title = stringResource(R.string.dashboard_glass_loop_safety_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    SafetyRow(
                        stringResource(R.string.dashboard_glass_loop_tir_low_label),
                        stringResource(R.string.dashboard_glass_loop_tir_low_value, uiState.tirLow1hText, uiState.tirLow24hText),
                        emerald
                    )
                    SafetyRow(
                        stringResource(R.string.dashboard_glass_loop_steps_label),
                        uiState.steps5mText,
                        textMuted,
                        showDivider = false
                    )
                }
            }

            // FOOTER
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.dashboard_glass_loop_footer, uiState.requestedSmbText, uiState.buildVersionText),
                    color = textMuted,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
internal fun GlassCardInner(
    isDark: Boolean,
    cardBgStart: Color,
    cardBgEnd: Color,
    borderCard: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(cardBgStart, cardBgEnd)))
            .border(1.dp, borderCard, RoundedCornerShape(16.dp)),
        content = content
    )
}

@Composable
private fun SectionCard(
    isDark: Boolean,
    cardBgStart: Color,
    cardBgEnd: Color,
    borderCard: Color,
    icon: @Composable () -> Unit,
    iconBg: Color,
    title: String,
    badge: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val textMuted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    GlassCardInner(isDark, cardBgStart, cardBgEnd, borderCard) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(iconBg), contentAlignment = Alignment.Center) {
                        icon()
                    }
                    Text(title.uppercase(), color = textMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                }
                badge?.invoke()
            }
            Divider(color = if (isDark) Color(0x1AFFFFFF) else Color(0xFFE2E8F0), thickness = 0.5.dp)
            content()
        }
    }
}

@Composable
private fun MetricCell(
    isDark: Boolean,
    cellBg: Color,
    cellBorder: Color,
    modifier: Modifier,
    label: String,
    labelColor: Color = Color.Unspecified,
    value: String,
    valueColor: Color = Color.Unspecified,
    valueFontSize: TextUnit = 20.sp,
    subContent: @Composable (() -> Unit)? = null
) {
    val textBright = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
    val textMuted = if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
    val finalValueColor = if (valueColor != Color.Unspecified) valueColor else textBright

    GlassCardInner(isDark, cellBg, cellBg, cellBorder, modifier) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = if (labelColor != Color.Unspecified) labelColor else textMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
            Text(value, color = finalValueColor, fontSize = valueFontSize, fontWeight = FontWeight.Bold)
            subContent?.invoke()
        }
    }
}

@Composable
private fun SafetyRow(label: String, value: String, valueColor: Color, showDivider: Boolean = true) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color(0xFF64748B), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (showDivider) Divider(color = Color(0x0DFFFFFF), thickness = 0.5.dp)
    }
}
```

Note: `"%02d".format(uiState.hourOfDay)` uses the default locale for a purely numeric zero-padded integer (no
letters, no locale-sensitive punctuation) — this is the same category of formatting already accepted elsewhere
in this codebase for numeric-only padding (not a translatable-string concern, since digits render the same in
every locale AAPS supports); if the reviewer prefers, `String.format(java.util.Locale.US, "%02d", ...)` is
equally correct and matches the style already used in `GlassChartComponents.kt`.

- [ ] **Step 2: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL. This screen has
no ViewModel/business logic of its own (pure rendering of an already-formatted state), so no new unit test is
needed beyond Task 2's ViewModel test — this matches the precedent set by `StatusAgoraCard`/`BgChartCard`/
`IobChartCard`, none of which have their own tests either.

---

## Task 4: `:app` module navigation wiring

**Files:**
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt`
- Modify: `app/src/main/kotlin/app/aaps/compose/navigation/AppNavGraph.kt`
- Modify: `app/src/main/kotlin/app/aaps/ComposeMainActivity.kt`

**Interfaces:**
- Consumes: `GlassLoopDashboardScreen` (Task 3), `GlassLoopDashboardViewModel` (Task 2).
- Produces: route string `"glass_loop_dashboard"`, consumed by Task 5's `openLoopDashboard()` implementation
  (which lives in `plugins:main` and cannot import `AppRoute` — same cross-module-literal pattern already used
  for `openStatsScreen()`/`openTreatmentsScreen()` in `DashboardShellController.kt`).

- [ ] **Step 1: Add the route**

In `app/src/main/kotlin/app/aaps/compose/navigation/AppRoute.kt`, add a new route object next to
`AppRoute.Stats`/`AppRoute.Treatments` (read the file first to match the exact surrounding style — `data
object X : AppRoute("route_string")`):

```kotlin
data object GlassLoopDashboard : AppRoute("glass_loop_dashboard")
```

- [ ] **Step 2: Add the ViewModel and pass it into the nav graph call**

In `app/src/main/kotlin/app/aaps/ComposeMainActivity.kt`, next to the existing
`private val treatmentsViewModel: TreatmentsViewModel by viewModels()` (around line 260), add:

```kotlin
private val glassLoopDashboardViewModel: GlassLoopDashboardViewModel by viewModels()
```

with the matching import:

```kotlin
import app.aaps.plugins.main.general.dashboard.glass.GlassLoopDashboardViewModel
```

Then, in the same file's `appNavGraph(...)` call (around line 901), add a new named argument:

```kotlin
glassLoopDashboardViewModel = glassLoopDashboardViewModel,
```

- [ ] **Step 3: Add the parameter and the destination**

In `app/src/main/kotlin/app/aaps/compose/navigation/AppNavGraph.kt`, add a new parameter to
`fun NavGraphBuilder.appNavGraph(...)` (around line 147, next to `statsViewModel: StatsViewModel,`):

```kotlin
glassLoopDashboardViewModel: app.aaps.plugins.main.general.dashboard.glass.GlassLoopDashboardViewModel,
```

(Use the explicit import form matching this file's existing import block — add
`import app.aaps.plugins.main.general.dashboard.glass.GlassLoopDashboardViewModel` at the top and use the
short name `GlassLoopDashboardViewModel` in the parameter list, per the project's explicit-imports rule; the
fully-qualified form above is only to pin the exact type for this brief.)

Then add a new destination next to the existing `composable(AppRoute.Stats.route) { ... }` block (around line
514):

```kotlin
composable(AppRoute.GlassLoopDashboard.route) {
    val uiState by glassLoopDashboardViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { glassLoopDashboardViewModel.refresh() }
    GlassLoopDashboardScreen(
        uiState = uiState,
        onBack = { navController.safePopBackStack() },
        isDark = isSystemInDarkTheme(),
    )
}
```

with imports (check which are already present in this file before adding — `composable`/`collectAsStateWithLifecycle`/`LaunchedEffect` are almost certainly already imported given the file's size and existing usage; add any missing ones):

```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.plugins.main.general.dashboard.glass.GlassLoopDashboardScreen
```

`isSystemInDarkTheme()` here is a placeholder for "match the app's dark-mode setting", not literally the OS
theme — this is a KNOWN, DELIBERATE deviation from the more correct pattern `GlassOverviewComposeEmbedded.kt`
already uses (reading `StringKey.GeneralDarkMode` via `LocalPreferences.current` and resolving through
`UiMode.fromString(...)`, exactly the fix applied for Plan 1's Critical finding C3). **Do not leave this as
`isSystemInDarkTheme()`** — instead, wrap the destination content the same way
`GlassOverviewComposeEmbedded.kt` does:

```kotlin
composable(AppRoute.GlassLoopDashboard.route) {
    val uiState by glassLoopDashboardViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { glassLoopDashboardViewModel.refresh() }
    val preferences = app.aaps.core.ui.compose.LocalPreferences.current
    val darkModeValue by preferences.observe(app.aaps.core.keys.StringKey.GeneralDarkMode).collectAsState()
    val isDark = when (app.aaps.core.ui.UiMode.fromString(darkModeValue)) {
        app.aaps.core.ui.UiMode.LIGHT -> false
        app.aaps.core.ui.UiMode.DARK -> true
        app.aaps.core.ui.UiMode.SYSTEM -> isSystemInDarkTheme()
    }
    GlassLoopDashboardScreen(
        uiState = uiState,
        onBack = { navController.safePopBackStack() },
        isDark = isDark,
    )
}
```

with the corresponding explicit imports at the top of the file (replacing the fully-qualified names above with
short names + imports, matching this file's existing style):

```kotlin
import androidx.compose.runtime.collectAsState
import app.aaps.core.keys.StringKey
import app.aaps.core.ui.UiMode
import app.aaps.core.ui.compose.LocalPreferences
```

- [ ] **Step 4: Compile**

Run: `./gradlew :app:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 5: `GlassHeroCommands.openLoopDashboard()` + Loop pill gesture split

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassHeroCommands.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/DashboardShellController.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/StatusAgoraCard.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassOverviewComposeEmbedded.kt`

**Interfaces:**
- Consumes: route string `"glass_loop_dashboard"` (Task 4).
- Produces: `GlassHeroCommands.openLoopDashboard()`, wired end-to-end from the Loop pill's short-tap gesture.

- [ ] **Step 1: Add the command to the interface**

In `GlassHeroCommands.kt`, add `fun openLoopDashboard()` to the interface and its `Noop` implementation:

```kotlin
interface GlassHeroCommands {
    fun openLoop()
    fun openLoopDashboard()
    fun openTarget()
    // ... (rest unchanged)
}

object NoopGlassHeroCommands : GlassHeroCommands {
    override fun openLoop() {}
    override fun openLoopDashboard() {}
    override fun openTarget() {}
    // ... (rest unchanged)
}
```

- [ ] **Step 2: Implement it in `DashboardShellController.kt`**

Next to the existing `openStatsScreen()`/`openTreatmentsScreen()` (which already use this exact
inline-literal-route pattern, with the comment explaining why `plugins:main` cannot import `AppRoute`), add:

```kotlin
override fun openLoopDashboard() =
    uiInteraction.openComposeMainAtRoute(host.context, "glass_loop_dashboard")
```

- [ ] **Step 3: Split the Loop pill's gesture in `StatusAgoraCard.kt`**

Add a new parameter to `StatusAgoraCard`'s signature, next to `onOpenLoop: () -> Unit,`:

```kotlin
onOpenLoopDashboard: () -> Unit,
```

Add the opt-in and import at the top of the file:

```kotlin
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
```

and mark the composable:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatusAgoraCard(
```

Then change ONLY the Loop `GlassPill`'s modifier (the one with `label = stringResource(R.string.dashboard_glass_loop_label)`,
currently `.width(100.dp).clickable { onOpenLoop() }`) to:

```kotlin
modifier = Modifier.width(100.dp).combinedClickable(
    onClick = onOpenLoopDashboard,
    onLongClick = onOpenLoop
),
```

Do NOT touch the OTHER `onOpenLoop()` binding earlier in the same file (the one on the big glucose number,
`Modifier.clickable { onOpenLoop() }`) — that is a separate, pre-existing gesture, out of scope for this task.

- [ ] **Step 4: Thread the new command through the call site**

In `GlassOverviewComposeEmbedded.kt`, the `StatusAgoraCard(...)` call currently passes
`onOpenLoop = commands::openLoop,` — add, right after it:

```kotlin
onOpenLoopDashboard = commands::openLoopDashboard,
```

- [ ] **Step 5: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed — this task's interface change
(`GlassHeroCommands.openLoopDashboard()`) affects both modules since `NoopGlassHeroCommands` and the real
`DashboardShellController` implementation both live in `plugins:main`, but the route it opens was added to
`:app` in Task 4.

- [ ] **Step 6: Manual verification note for the user**

This task cannot be unit-tested meaningfully (it's a gesture-wiring change across Composables with no
ViewModel logic of its own) — flag for the user to manually verify after this plan's tasks are all done: short
tap on the Loop pill opens the new Loop Dashboard screen, long-press on the Loop pill still opens the
running-mode dialog exactly as before, and the glucose-number's own tap-to-loop-dialog gesture is unchanged.

---

## Self-Review Notes

- **Spec coverage:** Phase 2 of the spec (`GlassLoopDashboardScreen`, reachable from a new entry point) is
  fully covered: Task 1-3 build the screen and its data, Task 4 wires real app navigation to it, Task 5 wires
  the entry point the user chose (Loop pill, short-tap/long-press split). The spec's open questions for this
  phase are both resolved in the "Decisions already made with the user" section above.
- **No placeholders:** every dropped reference field is named explicitly with the reason it was dropped, not
  left as a TODO. Every task has complete code, not a "similar to X" reference.
- **Type consistency:** `GlassLoopDashboardState` (Task 1) field names match exactly what Task 2's ViewModel
  constructs and what Task 3's screen reads — checked field-by-field against the table above.
- **Unit-space correctness:** re-verified against this plan's own Global Constraints — every mg/dL value is
  converted via `ProfileUtil` before it ever becomes a `String` in `GlassLoopDashboardState`; the Composable in
  Task 3 never receives or formats a raw `Double` glucose/ISF/target value.
- **Known deviation flagged inline, not silently introduced:** Task 4's first draft of the nav destination used
  `isSystemInDarkTheme()` directly, which would be the same class of bug already fixed once in Plan 1 (C3 —
  dark mode from the OS instead of the app's own preference). The task's final instructions replace it with the
  correct `LocalPreferences`/`UiMode` pattern before dispatch, so no implementer ever sees the wrong version as
  a live instruction — it is shown once, explicitly labeled wrong, immediately followed by the corrected code.
