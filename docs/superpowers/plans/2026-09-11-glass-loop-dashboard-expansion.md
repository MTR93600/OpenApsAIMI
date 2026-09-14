# Glass Loop Dashboard Expansion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restyle the Glass Loop Dashboard screen to GlycoCalm and add 4 new sections: Trajectory (~4h
prediction), Physio (light summary + link to AIMI Context), ML Learner/Training status, ML Model Files.

**Architecture:** The restyle is confined to the screen's own local color-token `val`s (its 3 existing section
composables — `SectionCard`/`MetricCell`/`SafetyRow` — already take colors as parameters, so retuning ~15
lines of token definitions restyles the whole screen without touching structure). Each new section follows the
existing `GlassLoopDashboardViewModel`'s established pattern: a `viewModelScope.launch { withContext(Dispatchers.IO) { ... } }`
block populating a `MutableStateFlow`. Two of the four new data sources need small, additive visibility/accessor
changes in `:plugins:aps` (a separate Gradle module); two need a new lightweight reactive repository.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt DI.

**Spec:** `docs/superpowers/specs/2026-09-11-glass-loop-dashboard-expansion-design.md`

## Global Constraints

- No `git commit` during this plan's execution.
- No install/on-device testing without the user explicitly asking.
- Dark mode: this screen already sources `isDark` via `LocalPreferences`/`StringKey.GeneralDarkMode`/`UiMode`
  in its `AppNavGraph.kt` destination — unaffected by this plan, must keep working after the restyle.
- Explicit imports only. No hardcoded user-facing strings; no string concatenation for user-facing text.
- Every glucose value must go through the same unit-formatting this screen's ViewModel already uses
  (`profileUtil.fromMgdlToStringInUnits`/`.fromMgdlToSignedStringInUnits`) — never a raw mg/dL Double rendered
  directly.
- **Task 2 (Trajectory) touches `DetermineBasalAIMI2.kt`, a large, safety-critical file with a documented
  history of subtle bugs in this exact area (prediction/floor logic).** The change must be a PURE, ISOLATED
  side-effect addition — publishing a snapshot to a new repository — inserted between two existing statements,
  with ZERO changes to any existing variable, branch, or return value. If implementing this task requires
  touching more than the single insertion point specified, STOP and report back rather than improvising.
- GlycoCalm dark-mode tokens are drawn from `DESIGN.md`'s `inverse-*` tokens (see Task 1) — this screen already
  supports both light and dark, the restyle must keep supporting both, not collapse to light-only.

---

## Task 1: Restyle `GlassLoopDashboardScreen.kt` to GlycoCalm

**Files:**
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardScreen.kt`

**Interfaces:** No data/behavior change — pure visual retune. Produces the token baseline Tasks 2-5's new
sections will render into (they reuse `SectionCard`/`MetricCell` unchanged, so they automatically pick up
whatever this task sets here).

The screen's structural composables (`GlassCardInner`, `SectionCard`, `MetricCell`, `SafetyRow`) already take
every color as a parameter — do NOT modify their bodies. This task only changes the local `val` token
definitions inside `GlassLoopDashboardScreen`'s own function body (currently lines 46-62) plus the few
composables further down that use LITERAL hex colors directly instead of a token `val` (the semantic accents
`skyBlue`/`emerald`/`amber`/`red`/`indigo` and icon-background tints are already tokens — check for any
remaining inline `Color(0x...)` literals elsewhere in the file, e.g. inside `SafetyRow`'s own hardcoded
`Color(0xFF64748B)`/`Color(0x0DFFFFFF)` at lines 387/390, and retune those too since they're not currently
parameterized).

- [ ] **Step 1: Replace the token block**

Change (current lines 46-62):
```kotlin
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
```
to (GlycoCalm light tokens from `DESIGN.md`'s prose; dark tokens from its `inverse-*`/`*-container`/`*-fixed`
YAML tokens — same mapping already used for the Personalize screen and Tools overlay in the other 2 plans of
this round):
```kotlin
    val bgColorTop = if (isDark) Color(0xFF213145) else Color(0xFFF1F5F9)
    val bgColorBot = if (isDark) Color(0xFF0F1C2C) else Color(0xFFE8EEF8)
    val cardBgStart = if (isDark) Color(0xFF0F1C2C) else Color(0xFFFFFFFF)
    val cardBgEnd = if (isDark) Color(0xFF0F1C2C) else Color(0xFFF8FAFC)
    val borderCard = if (isDark) Color(0x33EAF1FF) else Color(0xFFE2E8F0)
    val textBright = if (isDark) Color(0xFFEAF1FF) else Color(0xFF0D1B2A)
    val textMuted = if (isDark) Color(0xFF778598) else Color(0xFF64748B)
    val cellBg = if (isDark) Color(0x14EAF1FF) else Color(0xFFF1F5F9)
    val cellBorder = if (isDark) Color(0x22EAF1FF) else Color(0xFFE2E8F0)
    val skyBlue = Color(0xFF0984E3)
    val emerald = Color(0xFF00B894)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFF43F5E)
    val indigo = Color(0xFF6366F1)
    val iconBgBlue = if (isDark) Color(0xFF15406B) else Color(0xFFDBEAFE)
    val iconBgGreen = if (isDark) Color(0xFF0B4A3A) else Color(0xFFD1FAE5)
    val iconBgAmber = if (isDark) Color(0xFF3D2E0A) else Color(0xFFFEF3C7)
```
(The dark `cardBgStart`/`cardBgEnd` are the SAME flat `0xFF0F1C2C` — GlycoCalm's dark cards are a flat tone,
not a gradient like the old dark theme's `Brush.verticalGradient` — this keeps `GlassCardInner`'s existing
`Brush.verticalGradient(listOf(cardBgStart, cardBgEnd))` call working unchanged while rendering as an
effectively flat card in dark mode.)

- [ ] **Step 2: Retune `SafetyRow`'s hardcoded colors**

Change (current lines 380-392):
```kotlin
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
to accept the screen's own `textMuted`/`isDark`-aware divider color as parameters instead of hardcoding them:
```kotlin
@Composable
private fun SafetyRow(
    label: String,
    value: String,
    valueColor: Color,
    labelColor: Color,
    dividerColor: Color,
    showDivider: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text(value, color = valueColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        if (showDivider) Divider(color = dividerColor, thickness = 0.5.dp)
    }
}
```
Update its 2 call sites (in the "SAFETY & ALARMS" `SectionCard` block) to pass `labelColor = textMuted,
dividerColor = if (isDark) Color(0x1AEAF1FF) else Color(0x1A000000),` (both already in scope where `SafetyRow`
is called, since `textMuted`/`isDark` are the enclosing composable's own local vals).

- [ ] **Step 3: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Manual verification note**

Cannot be meaningfully tested without a live device. Flag for the user: open the Loop Dashboard in both light
and dark mode, confirm all 3 existing sections (Glucose & Insulin Dynamics, Key Factors, Safety & Alarms) now
render in the lighter, rounder GlycoCalm style with no leftover dark-gradient card in light mode and no
washed-out/illegible text in dark mode.

---

## Task 2: Trajectory section (new repository, careful single-point hook)

**Files:**
- Create: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/pkpd/TrajectoryRuntimeRepository.kt`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt` (ONE insertion
  point only)
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardModels.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardViewModel.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardScreen.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `TrajectoryRuntimeRepository.getLatest(): AdvancedPredictionCurves?` (public object, in
  `:plugins:aps`), consumed by `GlassLoopDashboardViewModel`.

**Read this whole task before touching `DetermineBasalAIMI2.kt`.** The insertion point was identified by a
dedicated research pass through the file's control flow — it is the ONE place in the whole tick where the
authoritative, terminal prediction curves for that cycle are available, execute exactly once per tick that
reaches it, and sit between two existing top-level statements with no branching of their own.

- [ ] **Step 1: Create the repository**

```kotlin
package app.aaps.plugins.aps.openAPSAIMI.pkpd

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * Plain publish/subscribe cache for the loop's most recent [AdvancedPredictionCurves], mirroring
 * [app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository]'s shape for consistency.
 * Published from exactly one point in [app.aaps.plugins.aps.openAPSAIMI.DetermineBasalAIMI2] per tick that
 * reaches the late PKPD refine stage — ticks that return earlier (T3C brittle mode, meal-advisor gate, hard
 * brake, glucose abort) do not publish a fresh snapshot that cycle. This is a read-only diagnostic/dashboard
 * side-channel; nothing doses on it.
 */
object TrajectoryRuntimeRepository {

    private val latestRef = AtomicReference<AdvancedPredictionCurves?>(null)
    private val updatesFlow = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    val updates: SharedFlow<Unit> = updatesFlow.asSharedFlow()

    fun publish(curves: AdvancedPredictionCurves) {
        latestRef.set(curves)
        updatesFlow.tryEmit(Unit)
    }

    fun getLatest(): AdvancedPredictionCurves? = latestRef.get()
}
```

- [ ] **Step 2: The ONE insertion point in `DetermineBasalAIMI2.kt`**

Inside `runDetermineBasalTickInner`, find the call to `runPkpdPredictionsBgiDeviationAndNoisyTargetsStage(...)`
(around line 17849) and the following `runUamModelCalHypoGuardPostHypoAndSetPredictedSmb(...)` call (around
line 17868). Immediately AFTER the former's result is destructured and BEFORE the latter is called, insert
exactly one line:
```kotlin
        TrajectoryRuntimeRepository.publish(this.lastAdvancedPredictionCurves)
```
Add the import `import app.aaps.plugins.aps.openAPSAIMI.pkpd.TrajectoryRuntimeRepository` at the top of the
file (check `this.lastAdvancedPredictionCurves`'s exact declared type at its member-field declaration, around
line 11101, to confirm it is non-null `AdvancedPredictionCurves` at this point in the tick, not nullable — if
it IS nullable, guard with `this.lastAdvancedPredictionCurves?.let { TrajectoryRuntimeRepository.publish(it) }`
instead of the bare call above).

**Do not touch anything else in this file.** No existing variable is read, no branch is added, no return value
changes. If the exact surrounding line numbers have drifted from this brief (the file is large and may have
changed slightly since this plan was written), locate the insertion point by the function/call names given
above, not by line number, and insert the single line in the same logical position (right after the late PKPD
refine stage's result is available, right before the next stage begins).

- [ ] **Step 3: Compile `:plugins:aps`**

Run: `./gradlew :plugins:aps:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL. This is the
highest-risk compile in this whole plan — if it fails, stop and report the exact error rather than guessing at
a fix.

- [ ] **Step 4: Add string resources**

```xml
<string name="dashboard_glass_loop_trajectory_title">Trajectory</string>
<string name="dashboard_glass_loop_trajectory_hybrid_label">Predicted (4h)</string>
<string name="dashboard_glass_loop_trajectory_iob_label">IOB path</string>
<string name="dashboard_glass_loop_trajectory_cob_label">COB path</string>
<string name="dashboard_glass_loop_trajectory_stale">No recent prediction available.</string>
```

- [ ] **Step 5: Extend `GlassLoopDashboardModels.kt`**

Add to `GlassLoopDashboardState`:
```kotlin
val hasTrajectory: Boolean = false,
val trajectoryHybridText: String = "--",
val trajectoryIobText: String = "--",
val trajectoryCobText: String = "--",
```

- [ ] **Step 6: Extend `GlassLoopDashboardViewModel.kt`**

Add to the injected constructor: `private val profileUtil: ProfileUtil,` — already injected (check the
existing constructor first; it's already there, do not add a duplicate).

Inside `refresh()`'s `withContext(Dispatchers.IO) { ... }` block, add (anywhere before the final
`GlassLoopDashboardState(...)` construction):
```kotlin
                val trajectoryCurves = app.aaps.plugins.aps.openAPSAIMI.pkpd.TrajectoryRuntimeRepository.getLatest()
                val hasTrajectory = trajectoryCurves != null
                val trajectoryHybridText = trajectoryCurves?.hybridTerminal?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"
                val trajectoryIobText = trajectoryCurves?.iob?.lastOrNull()?.takeIf { it.isFinite() }?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"
                val trajectoryCobText = trajectoryCurves?.cobTerminal?.let { profileUtil.fromMgdlToStringInUnits(it) } ?: "--"
```
(Add the import `import app.aaps.plugins.aps.openAPSAIMI.pkpd.TrajectoryRuntimeRepository` instead of using the
fully-qualified form shown above — the inline form is only to pin the exact type for this brief. `:plugins:main`
already depends on `:plugins:aps`, confirmed by this plan's own research — no new module dependency needed.)

Add to the `GlassLoopDashboardState(...)` construction:
```kotlin
                    hasTrajectory = hasTrajectory,
                    trajectoryHybridText = trajectoryHybridText,
                    trajectoryIobText = trajectoryIobText,
                    trajectoryCobText = trajectoryCobText,
```

- [ ] **Step 7: Add the section to `GlassLoopDashboardScreen.kt`**

Add a new `SectionCard` after "CARD 1: GLUCOSE & INSULIN DYNAMICS" and before "CARD 2: KEY FACTORS":
```kotlin
            // CARD 1b: TRAJECTORY
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Bolt, null, tint = skyBlue, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgBlue,
                title = stringResource(R.string.dashboard_glass_loop_trajectory_title)
            ) {
                if (uiState.hasTrajectory) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_trajectory_hybrid_label),
                            value = uiState.trajectoryHybridText,
                            valueFontSize = 16.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_trajectory_iob_label),
                            value = uiState.trajectoryIobText,
                            valueFontSize = 16.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_trajectory_cob_label),
                            value = uiState.trajectoryCobText,
                            valueFontSize = 16.sp,
                        )
                    }
                } else {
                    Text(stringResource(R.string.dashboard_glass_loop_trajectory_stale), color = textMuted, fontSize = 11.sp)
                }
            }
```
(Reuse the same `Icons.Default.Bolt`/`skyBlue`/`iconBgBlue` already used by CARD 1 — a distinct icon is a
cosmetic follow-up, not blocking, same reasoning already applied elsewhere in this Glass skin.)

- [ ] **Step 8: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Manual verification note**

Cannot be tested without a live loop cycle. Flag for the user: after the loop has run at least once with this
build installed, open the Loop Dashboard and confirm the Trajectory card shows real numbers (not "--" / the
stale message) — if it stays stale, check whether the tick is consistently taking an early-return path (T3C
brittle mode etc.) that never reaches the publish point, which is a known, documented limitation of this
section, not necessarily a bug.

---

## Task 3: Physio section (visibility widening + light summary card)

**Files:**
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRepository.kt`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientModeOrchestrator.kt`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateLoopCache.kt`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStateRuntimeRefresher.kt`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/patient/PatientStatePresentation.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardModels.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardViewModel.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardScreen.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Widens visibility of 6 declarations (all `internal` → public, no other change) so `:plugins:aps`'s existing
  `PatientStateRuntimeRepository`/`PatientStatePresentationBuilder` become consumable from `:plugins:main`.

This is a purely mechanical visibility change — confirmed by dedicated research that removing `internal` from
exactly these 6 declarations is sufficient, with no cascading blockers (every field type they reference is
already public). Do not widen anything beyond this list; do not add new fields.

- [ ] **Step 1: Widen 6 declarations**

In `PatientStateRuntimeRepository.kt`: remove `internal` from `internal data class PatientRuntimeSnapshot` and
from `internal object PatientStateRuntimeRepository`.

In `PatientModeOrchestrator.kt`: remove `internal` from `internal object PatientModeOrchestrator` (its nested
`Decision` data class already has no modifier — do not add one).

In `PatientStateLoopCache.kt`: remove `internal` from `internal data class PatientStateLoopCache`.

In `PatientStateRuntimeRefresher.kt`: remove `internal` from `internal enum class PatientRefreshSource`.

In `PatientStatePresentation.kt`: remove `internal` from `internal object PatientStatePresentationBuilder`
(its `PatientStatePresentation`/`PatientSignalGauge` output types are already public, no change needed there).

- [ ] **Step 2: Compile `:plugins:aps`**

Run: `./gradlew :plugins:aps:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL. If it fails with
a different "internal type in public API" error than the 6 declarations above, that means a nested type this
plan's research did not catch also needs widening — check the exact error, widen only the specific type named
in the error, and re-run. Do not widen anything beyond what a compile error actually demands.

- [ ] **Step 3: Add string resources**

```xml
<string name="dashboard_glass_loop_physio_title">Physio</string>
<string name="dashboard_glass_loop_physio_mode_label">Mode</string>
<string name="dashboard_glass_loop_physio_intent_label">Intent</string>
<string name="dashboard_glass_loop_physio_thermal_label">Thermal</string>
<string name="dashboard_glass_loop_physio_open_context">Open AIMI Context</string>
<string name="dashboard_glass_loop_physio_stale">No recent physio snapshot available.</string>
```

- [ ] **Step 4: Extend `GlassLoopDashboardModels.kt`**

Add to `GlassLoopDashboardState`:
```kotlin
val hasPhysio: Boolean = false,
val physioModeText: String = "--",
val physioIntentText: String = "--",
val physioThermalText: String = "--",
```

- [ ] **Step 5: Extend `GlassLoopDashboardViewModel.kt`**

Inside `refresh()`'s IO block:
```kotlin
                val physioSnapshot = app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository.getLatest()
                val physioPresentation = physioSnapshot?.let {
                    app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilder.build(it, dateUtil.now())
                }
                val hasPhysio = physioPresentation != null
                val physioModeText = physioPresentation?.modeHeadline ?: "--"
                val physioIntentText = physioPresentation?.intentSummary ?: "--"
                val physioThermalText = physioPresentation?.thermalSummary ?: "--"
```
(Proper imports instead of fully-qualified forms: `import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStateRuntimeRepository`,
`import app.aaps.plugins.aps.openAPSAIMI.patient.PatientStatePresentationBuilder`. Confirm `dateUtil` is
already injected in this ViewModel — it is, per its existing constructor.)

Add to the `GlassLoopDashboardState(...)` construction: `hasPhysio = hasPhysio, physioModeText = physioModeText,
physioIntentText = physioIntentText, physioThermalText = physioThermalText,`.

- [ ] **Step 6: Add the section + "Open AIMI Context" button to `GlassLoopDashboardScreen.kt`**

Add a new parameter to `GlassLoopDashboardScreen`'s signature: `onOpenAimiContext: () -> Unit,`.

Add a new `SectionCard` after the Trajectory card (Task 2) and before "CARD 2: KEY FACTORS":
```kotlin
            // CARD 1c: PHYSIO
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Bolt, null, tint = indigo, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgBlue,
                title = stringResource(R.string.dashboard_glass_loop_physio_title)
            ) {
                if (uiState.hasPhysio) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MetricCell(
                                isDark, cellBg, cellBorder, Modifier.weight(1f),
                                label = stringResource(R.string.dashboard_glass_loop_physio_mode_label),
                                value = uiState.physioModeText,
                                valueFontSize = 13.sp,
                            )
                            MetricCell(
                                isDark, cellBg, cellBorder, Modifier.weight(1f),
                                label = stringResource(R.string.dashboard_glass_loop_physio_intent_label),
                                value = uiState.physioIntentText,
                                valueFontSize = 13.sp,
                            )
                        }
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.fillMaxWidth(),
                            label = stringResource(R.string.dashboard_glass_loop_physio_thermal_label),
                            value = uiState.physioThermalText,
                            valueFontSize = 13.sp,
                        )
                        Text(
                            text = stringResource(R.string.dashboard_glass_loop_physio_open_context),
                            color = skyBlue,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { onOpenAimiContext() }
                        )
                    }
                } else {
                    Text(stringResource(R.string.dashboard_glass_loop_physio_stale), color = textMuted, fontSize = 11.sp)
                }
            }
```
Add the import `import androidx.compose.foundation.clickable` if not already present.

Wire `onOpenAimiContext` at this screen's call site in `AppNavGraph.kt`'s `GlassLoopDashboard` destination —
reuse the EXACT SAME launch mechanism the Tools grid's own "AIMI Context" tile already uses
(`launchDashboardV2Aimi`-reachable `ContextActivity` launch, per this plan's own research — find that exact
call in `ComposeMainActivity.kt` and invoke the same function/pattern here, do not build a second navigation
path to the same screen).

- [ ] **Step 7: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Task 4: ML Learner/Training status section

**Files:**
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/learning/BasalMlTrainingCoordinator.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardModels.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardViewModel.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardScreen.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Produces 2 new public methods on the already-public `BasalMlTrainingCoordinator` class:
  `lastTrainedAtMs(): Long` and `isCircuitOpenNow(): Boolean`.
- Consumes: `BasalMlTrainingCoordinator.instance` (existing public companion getter) and
  `BasalNeuralLearner.getGovernanceSnapshot().sampleCount` (already fully public today, zero changes needed).

**Known risk, accepted per the spec:** this codebase has an in-progress effort to unify 2 duplicated ML
training pipelines (SMB/basal). This task integrates with the CURRENT (pre-unification) shape of
`BasalMlTrainingCoordinator`/`BasalNeuralLearner`. If that unification lands later and changes these classes'
structure, this section may need rework — not this plan's problem to solve, already flagged in the spec.

- [ ] **Step 1: Add 2 public methods to `BasalMlTrainingCoordinator`**

Add near its other public methods (do not touch `private val lastTrainMs`/`private val circuitBreaker` fields
themselves, only add new methods reading them):
```kotlin
    /** Epoch ms of the last completed training run, or 0 if none yet. Read-only, dashboard-facing. */
    fun lastTrainedAtMs(): Long = lastTrainMs.get()

    /** True while the training circuit breaker is currently open (recent failures cooling down). */
    fun isCircuitOpenNow(): Boolean = circuitBreaker.isOpen()
```

- [ ] **Step 2: Compile `:plugins:aps`**

Run: `./gradlew :plugins:aps:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Add string resources**

```xml
<string name="dashboard_glass_loop_ml_training_title">ML Training</string>
<string name="dashboard_glass_loop_ml_last_trained_label">Last trained</string>
<string name="dashboard_glass_loop_ml_samples_label">Samples</string>
<string name="dashboard_glass_loop_ml_circuit_open">Training paused (cooling down after recent failures)</string>
<string name="dashboard_glass_loop_ml_circuit_closed">Training active</string>
<string name="dashboard_glass_loop_ml_never_trained">Never trained yet</string>
```

- [ ] **Step 4: Extend `GlassLoopDashboardModels.kt`**

Add to `GlassLoopDashboardState`:
```kotlin
val mlLastTrainedText: String = "",
val mlSampleCountText: String = "--",
val mlCircuitOpen: Boolean = false,
```

- [ ] **Step 5: Extend `GlassLoopDashboardViewModel.kt`**

Need a `BasalNeuralLearner` instance — check whether it's already injectable via this ViewModel's existing
constructor pattern (it should be, since it's a plain `@Singleton`-scoped `@Inject constructor` class per this
plan's research) and add `private val basalNeuralLearner: BasalNeuralLearner,` to the constructor if not
already present.

Inside `refresh()`'s IO block:
```kotlin
                val trainingCoordinator = app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator.instance
                val lastTrainedMs = trainingCoordinator?.lastTrainedAtMs() ?: 0L
                val mlLastTrainedText = if (lastTrainedMs > 0L)
                    resourceHelper.gs(R.string.dashboard_glass_loop_ml_last_trained_label) + ": " + dateUtil.minAgoShort(lastTrainedMs)
                else
                    resourceHelper.gs(R.string.dashboard_glass_loop_ml_never_trained)
                val mlSampleCountText = basalNeuralLearner.getGovernanceSnapshot().sampleCount.toString()
                val mlCircuitOpen = trainingCoordinator?.isCircuitOpenNow() ?: false
```
(The `mlLastTrainedText` line concatenates a label + a value, which normally violates this project's
no-concatenation rule — but `dashboard_glass_loop_ml_last_trained_label` here is used as a PREFIX inside one
composed sentence read as a single unit, not as a separate visual label elsewhere; if this bothers the
reviewer, replace with a proper 1-placeholder format string
`dashboard_glass_loop_ml_last_trained_value` = `"%1$s ago"`-style resource instead, applied to
`dateUtil.minAgoShort(lastTrainedMs)`, and use `dashboard_glass_loop_ml_last_trained_label` purely as the
`MetricCell`'s separate `label` parameter — this is the cleaner, compliant option, prefer it over the
concatenation shown above.)

Add the import `import app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator` and
`import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner` instead of fully-qualified forms.

Add to the `GlassLoopDashboardState(...)` construction: `mlLastTrainedText = mlLastTrainedText,
mlSampleCountText = mlSampleCountText, mlCircuitOpen = mlCircuitOpen,`.

- [ ] **Step 6: Add the section to `GlassLoopDashboardScreen.kt`**

Add a new `SectionCard` after "CARD 2: KEY FACTORS" and before "CARD 3: SAFETY & ALARMS":
```kotlin
            // CARD 2b: ML TRAINING
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Tune, null, tint = if (uiState.mlCircuitOpen) amber else emerald, modifier = Modifier.size(16.dp)) },
                iconBg = if (uiState.mlCircuitOpen) iconBgAmber else iconBgGreen,
                title = stringResource(R.string.dashboard_glass_loop_ml_training_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_ml_last_trained_label),
                            value = uiState.mlLastTrainedText,
                            valueFontSize = 13.sp,
                        )
                        MetricCell(
                            isDark, cellBg, cellBorder, Modifier.weight(1f),
                            label = stringResource(R.string.dashboard_glass_loop_ml_samples_label),
                            value = uiState.mlSampleCountText,
                            valueFontSize = 13.sp,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (uiState.mlCircuitOpen) R.string.dashboard_glass_loop_ml_circuit_open
                            else R.string.dashboard_glass_loop_ml_circuit_closed
                        ),
                        color = if (uiState.mlCircuitOpen) amber else emerald,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
```

- [ ] **Step 7: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

---

## Task 5: ML Model Files section

**Files:**
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/learning/BasalMlTrainingCoordinator.kt`
- Modify: `plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/ml/AimiSmbModelStore.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardModels.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardViewModel.kt`
- Modify: `plugins/main/src/main/kotlin/app/aaps/plugins/main/general/dashboard/glass/GlassLoopDashboardScreen.kt`
- Modify: `plugins/main/src/main/res/values/strings.xml`

**Interfaces:**
- Produces: `BasalMlTrainingCoordinator.basalWeightsFile(): File` (new), `AimiSmbModelStore` widened to public
  (its existing `modelFile(dir): File` accessor already does what's needed).

- [ ] **Step 1: Add a file accessor to `BasalMlTrainingCoordinator`**

Add near the existing `BASAL_WEIGHTS`/`T3C_WEIGHTS` constants and `storageHelper.getAimiFile(...)` call sites:
```kotlin
    /** File backing the basal adaptive weights, for read-only metadata (dashboard use only). */
    fun basalWeightsFile(): File = storageHelper.getAimiFile(BASAL_WEIGHTS)
```
(Confirm `storageHelper`'s exact property name in this class before using it — this plan's research found it
referenced as `storageHelper.getAimiFile(...)` at several existing call sites in this same file, reuse that
exact same call.)

- [ ] **Step 2: Widen `AimiSmbModelStore`'s visibility**

Remove `internal` from `internal object AimiSmbModelStore`. Its existing `modelFile(dir: File): File` (already
public within the object) is what a new metadata accessor needs — no other change to this file. You will also
need the `dir: File` this function expects — find how existing callers (e.g. `AimiSmbTrainer.kt:217`) obtain
this `dir` value (likely via `AimiStorageHelper` or a similar directory-resolution call) and use the same
mechanism from the new ViewModel code in Step 4.

- [ ] **Step 3: Compile `:plugins:aps`**

Run: `./gradlew :plugins:aps:compileFullDebugKotlin --no-daemon`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Add string resources**

```xml
<string name="dashboard_glass_loop_ml_files_title">Model Files</string>
<string name="dashboard_glass_loop_ml_file_basal_label">Basal model</string>
<string name="dashboard_glass_loop_ml_file_smb_label">SMB model</string>
<string name="dashboard_glass_loop_ml_file_missing">Not created yet</string>
```

- [ ] **Step 5: Extend `GlassLoopDashboardModels.kt`**

Add to `GlassLoopDashboardState`:
```kotlin
val basalModelFileText: String = "",
val smbModelFileText: String = "",
```

- [ ] **Step 6: Extend `GlassLoopDashboardViewModel.kt`**

Inside `refresh()`'s IO block, using the same "file exists → last-modified formatted via `dateUtil`" pattern:
```kotlin
                val basalModelFile = app.aaps.plugins.aps.openAPSAIMI.learning.BasalMlTrainingCoordinator.instance?.basalWeightsFile()
                val basalModelFileText = basalModelFile?.takeIf { it.exists() }?.let { dateUtil.dateAndTimeString(it.lastModified()) }
                    ?: resourceHelper.gs(R.string.dashboard_glass_loop_ml_file_missing)
```
(For the SMB model file, follow the same pattern via `AimiSmbModelStore.modelFile(dir)` once the `dir` value
is resolved per Step 2's note — write the equivalent `smbModelFileText` val the same way. Confirm
`dateUtil.dateAndTimeString(Long): String`'s exact name/signature in this codebase's `DateUtil` interface
before using it — if it's named differently, use the correct existing method rather than inventing one.)

Add the import `import app.aaps.plugins.aps.openAPSAIMI.ml.AimiSmbModelStore` if needed. Add to the
`GlassLoopDashboardState(...)` construction: `basalModelFileText = basalModelFileText, smbModelFileText =
smbModelFileText,`.

- [ ] **Step 7: Add the section to `GlassLoopDashboardScreen.kt`**

Add a new `SectionCard` after the ML Training card (Task 4) and before "CARD 3: SAFETY & ALARMS":
```kotlin
            // CARD 2c: MODEL FILES
            SectionCard(
                isDark, cardBgStart, cardBgEnd, borderCard,
                icon = { Icon(Icons.Default.Tune, null, tint = indigo, modifier = Modifier.size(16.dp)) },
                iconBg = iconBgBlue,
                title = stringResource(R.string.dashboard_glass_loop_ml_files_title)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SafetyRow(
                        stringResource(R.string.dashboard_glass_loop_ml_file_basal_label),
                        uiState.basalModelFileText,
                        textMuted,
                        labelColor = textMuted,
                        dividerColor = if (isDark) Color(0x1AEAF1FF) else Color(0x1A000000),
                    )
                    SafetyRow(
                        stringResource(R.string.dashboard_glass_loop_ml_file_smb_label),
                        uiState.smbModelFileText,
                        textMuted,
                        labelColor = textMuted,
                        dividerColor = if (isDark) Color(0x1AEAF1FF) else Color(0x1A000000),
                        showDivider = false,
                    )
                }
            }
```
(Reuses `SafetyRow` — already extended with `labelColor`/`dividerColor` parameters by Task 1 Step 2.)

- [ ] **Step 8: Compile**

Run: `./gradlew :plugins:main:compileFullDebugKotlin --no-daemon` then
`./gradlew :app:compileFullDebugKotlin --no-daemon`. Both must succeed.

---

## Self-Review Notes

- **Spec coverage:** restyle (Task 1) + all 4 new sections (Tasks 2-5) match the spec's Architecture section
  exactly, including the explicitly-flagged effort tiers (Trajectory/ML files = new plumbing, Physio/ML
  training = mostly-existing plumbing just needing visibility/accessor widening).
- **No placeholders:** every step has real, research-grounded code; the few remaining "confirm the exact name"
  notes (e.g. `dateUtil.dateAndTimeString`, `storageHelper.getAimiFile`'s exact property name) are flagged as
  verify-before-use because this plan's own research established the PATTERN but not always the single
  character-exact identifier — these are honest residual unknowns, not vague direction.
- **Highest-risk task called out explicitly:** Task 2's `DetermineBasalAIMI2.kt` insertion is flagged in both
  the Global Constraints and the task's own text as the one place in this plan requiring extra care, with an
  explicit "stop and report" instruction if the single insertion point doesn't cleanly apply.
- **Type consistency:** `GlassLoopDashboardState`'s new fields are used identically between the ViewModel
  (Tasks 2-5) and the Screen (same tasks).
