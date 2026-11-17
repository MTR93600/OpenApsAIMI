# Skin Essential + TPO Boost Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A third dashboard skin "Essential" (living glucose shape, scrub chart, gestures) on Android and iOS, and a manual, capped TPO Boost started by pulling the shape up.

**Architecture:** The skin is Compose Multiplatform in `ui/commonMain`, fed by the same builders as Glass. It reaches the Boost only through a new platform-neutral port `AimiBoostControl` in `core/interfaces`, implemented in `plugins/aps` on top of the existing TPO session manager. All guards live in the plugin and are checked again at start.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (`Canvas`, `pointerInput`, `animateFloatAsState`, `rememberInfiniteTransition`), Metro DI, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-16-skin-essential-design.md`

## Global Constraints

- No commit and no push unless the user asks (project rule). Work stays in the `feature/skin-essential` worktree.
- Simple English in code, comments and strings. English strings only, in the module's `values/strings.xml`.
- Explicit imports, no fully qualified names inline. Theme values, no Android attrs in Compose.
- Boost keys and cap, exactly: `OApsAIMIHighBGMaxSMB`, `ApsMaxBasal`, `autodriveMaxBasal`; each `min(v × 1.20, key max[, HardLimits.maxBasal()])`, rounded down to 0.01. Duration 60 min. Priority 0.
- Low/high thresholds always from user settings (`chartConfigFlow`), never hard-coded.
- No COB anywhere in the skin.
- Test names without commas (Kotlin/Native).
- Gradle: `./gradlew` (macOS), `--no-daemon`, redirect to a log and grep; never two builds at once.

---

### Task 1: The Boost port

**Files:**
- Create: `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/aps/AimiBoostControl.kt`

**Interfaces — Produces:**
```kotlin
interface AimiBoostControl {
    val state: StateFlow<AimiBoostState>
    suspend fun start(): AimiBoostStartResult
    fun cancel(): Boolean
}
data class AimiBoostState(val available: Boolean = false, val unavailableReason: AimiBoostUnavailableReason? = null, val active: Boolean = false, val remainingMinutes: Int = 0)
enum class AimiBoostUnavailableReason { NOT_AIMI, LOOP_NOT_CLOSED, NO_RECENT_TICK, BELOW_TARGET, FALLING, RECENT_LOW, REBOUND_GUARD, OTHER_SESSION, ALREADY_ACTIVE }
sealed interface AimiBoostStartResult { data class Started(val remainingMinutes: Int) : AimiBoostStartResult; data class Refused(val reason: AimiBoostUnavailableReason) : AimiBoostStartResult }
```

- [ ] Write the file. Compile `:core:interfaces:compileKotlinIosSimulatorArm64`.

### Task 2: Boost plan and guards (pure, tested)

**Files:**
- Modify: `plugins/aps/.../tpo/TpoModels.kt` (`MANUAL_BOOST(priority = 0)`), `TpoPreferenceKeys.kt` (3 keys), `TpoUiSupport.kt` (pack title), `TpoSessionManager.kt` (`startSession(..., ttlMs: Long = TTL_MS)`), any exhaustive `when` on `TpoPackId`.
- Create: `plugins/aps/.../tpo/TpoBoostPlan.kt`, `TpoBoostEligibility.kt`
- Test: `plugins/aps/src/commonTest/.../tpo/TpoBoostPlanTest.kt`, `TpoBoostEligibilityTest.kt`

**Produces:**
```kotlin
internal object TpoBoostPlan {
    const val FACTOR = 1.20
    const val TTL_MS = 60L * 60L * 1000L
    fun boosted(value: Double, keyMax: Double, hardMax: Double = Double.MAX_VALUE): Double // floor to 0.01, never below value
    fun build(preferences: Preferences, hardMaxBasal: Double): TpoApplyPlan // only keys whose boosted value > current
}
internal data class TpoBoostTick(val nowMs: Long, val bgMgdl: Double, val deltaMgdl5m: Double, val minBgLookback75m: Double, val reboundGuardActive: Boolean)
internal object TpoBoostEligibility {
    const val MAX_TICK_AGE_MS = 10L * 60L * 1000L
    fun check(isAimi: Boolean, loopClosed: Boolean, tick: TpoBoostTick?, nowMs: Long, targetMgdl: Double, activeSession: TpoSessionDocument?): AimiBoostUnavailableReason?
}
```
Guard order: NOT_AIMI, LOOP_NOT_CLOSED, ALREADY_ACTIVE (active session is MANUAL_BOOST), OTHER_SESSION, NO_RECENT_TICK, RECENT_LOW (`minBg75 < 70`), REBOUND_GUARD, BELOW_TARGET (`bg < target` or `bg < 80`), FALLING (`delta <= -2`). Null = allowed.

- [ ] Tests first: cap 20 %, key max, hard max, floor rounding, untouched when at cap; each guard alone blocks; all good = null.
- [ ] Implement; run `:plugins:aps:jvmTest --tests '*TpoBoost*'`.

### Task 3: Boost controller

**Files:**
- Modify: `plugins/aps/.../tpo/TpoOrchestrator.kt` — keep `lastBoostTick` from `onPatientStateReady`; add `internal fun startManualBoost(plan, nowMs)`, `internal fun boostTick()`, use `revertNow()` for cancel.
- Create: `plugins/aps/.../tpo/TpoBoostController.kt` — `@ContributesBinding(AppScope) @SingleIn @Inject class TpoBoostController(tpo: TpoOrchestrator, activePlugin: ActivePlugin, loop: Loop, profileFunction: ProfileFunction, hardLimits: HardLimits, preferences: Preferences, dateUtil: DateUtil, aapsLogger: AAPSLogger) : AimiBoostControl`. `state` refreshed by a 30 s ticker plus after start/cancel.
- Test: `plugins/aps/src/commonTest/.../tpo/TpoBoostLifecycleTest.kt` — start writes 3 keys and session MANUAL_BOOST with 60 min; revert restores; trigger engine lets a protective pack supersede a running Boost.

- [ ] Tests first (use `InMemoryAimiStorage` and a map-backed `Preferences` fake as other TPO tests do).
- [ ] Implement; run aps jvmTest + iosSimulatorArm64Test for the new tests.

### Task 4: Pure skin logic (tested)

**Files:**
- Create: `ui/src/commonMain/.../dashboard/essential/EssentialShapeState.kt`, `EssentialSeries.kt`
- Test: `ui/src/commonTest/.../dashboard/essential/EssentialShapeStateTest.kt`, `EssentialSeriesTest.kt`

**Produces:**
```kotlin
enum class EssentialMood { STABLE, RISING, FALLING, HYPER, HYPO, TRAJECTORY_HYPER, TRAJECTORY_HYPO, NO_DATA }
data class EssentialInputs(val bgMgdl: Double?, val stale: Boolean, val trend: TrendArrow?, val prediction60Mgdl: Double?, val lowMgdl: Double, val highMgdl: Double)
object EssentialShapeState { const val MARGIN_MGDL = 5.0; fun classify(inputs: EssentialInputs, previous: EssentialMood?): EssentialMood }
data class ShapeParams(val stretchY: Float, val tilt: Float, val angularity: Float, val scale: Float, val breathPeriodMs: Int, val breathAmplitude: Float, val pulses: Boolean)
fun EssentialMood.shapeParams(): ShapeParams
fun EssentialMood.offersBoost(): Boolean // TRAJECTORY_HYPER, RISING, HYPER
object EssentialSeries {
    fun prediction60(predictions: List<BgDataPoint>, nowMs: Long): BgDataPoint? // UAM, then IOB, then ZT; within ±5 min of now+60
    fun basalAt(actualBasal: List<GraphDataPoint>, timeMs: Long): Double?
    fun smbAt(boluses: List<BolusGraphPoint>, timeMs: Long, windowMs: Long = 150_000L): Double?
}
```
Hysteresis: from HYPO stay until `bg > low + MARGIN`; from HYPER stay until `bg < high - MARGIN`; enter HYPO at `bg < low`, HYPER at `bg > high`. Trend up = SINGLE_UP, DOUBLE_UP, TRIPLE_UP, FORTY_FIVE_UP; down mirrored. Trajectory only with a prediction.

- [ ] Tests first, then implement; run `:ui:jvmTest` (or the module's test task) and iOS simulator.

### Task 5: Registration

**Files:**
- Modify: `ui/.../dashboard/DashboardHomeSelection.kt` (`ESSENTIAL("essential")`), `core/keys/.../StringKey.kt` (entriesRefs), `core/keys/src/androidMain/res/values/strings.xml` (`pref_dashboard_home_essential` = "Essential"), `ui/.../main/OverviewScreen.kt` (new branch).

- [ ] Add, compile, check `prefs-schema.txt` golden test still passes (entries may be listed there; update it if so).

### Task 6: Composables

**Files (all `ui/src/commonMain/.../dashboard/essential/`):**
- `LivingShape.kt` — Canvas blob from `ShapeParams` (8 control points on a circle, radius modulated by stretch/tilt/angularity, smooth cubic closing), BG text, trend + delta; animated params; breath/pulse via infinite transition; vertical drag with 56 dp threshold, gauge progress callback, haptic tick at threshold, `onPullUp`, `onPullDown`, `onTap`.
- `EssentialChart.kt` — Canvas chart (zone-coloured BG line, target band, dotted prediction, basal steps, SMB dots sized by dose, hour labels, low/high marks), range selector 6/12/18/24, horizontal scrub with readout; release animates back.
- `EssentialDetailSheet.kt` — ModalBottomSheet: recent decisions, IOB activity (reuse Glass `IobChartCard` if its signature allows, else a small Canvas), detailed ages, Boost buttons and reason.
- `EssentialViewModel.kt` — `@ContributesIntoMap(ViewModel) @ViewModelKey`, exposes `boost: StateFlow<AimiBoostState>`, `startBoost()`, `cancelBoost()` (feedback event flow for snackbar text).
- `EssentialDashboardHome.kt` — assembly: collects the same flows as Glass, builds `GlassUiState` and `GlassChartState`, mood with remembered previous, info line, status line, TIR, boost banner.
- Strings in `ui/src/androidMain/res/values/strings.xml` (or the module's string file used by `UiStrings`).

- [ ] Build Android `:ui:compileAndroidMain` and iOS `:ui:compileKotlinIosSimulatorArm64`.

### Task 7: Verification

- [ ] `:app:compileFullDebugKotlin`, `:ios:shell:compileKotlinIosSimulatorArm64`, `:desktop:shell:compileKotlin` (exclude the controller there if it cannot be built, as Tidepool).
- [ ] Tests on JVM / Android host / iOS simulator for `:plugins:aps` and `:ui`.
- [ ] Full CI suite with `--continue`; report results honestly.
