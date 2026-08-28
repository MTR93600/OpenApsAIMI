# Lot Z — senior architecture + Kotlin/KMP review

Reviewer: code-reviewer subagent  
Branch: `kmp-aimi-migration-study`  
HEAD: `08bc621dae` (Lot Y)  
Files reviewed: 2 new dest files (working-tree, not committed)

---

## Summary

Both files are a clean, mechanical peel of PKPD settings math that was previously grouped with
Compose UI in the dump's `compose/` folder. Neither file contains any Compose runtime, Android
API, or KMP-banned symbol. Therapy math is unchanged. The one required omission
(`pkpdPrefsSnapshotFrom`) was performed correctly. All imports are explicit. Compile log confirms
`BUILD SUCCESSFUL` for both `:plugins:aps:compileKotlinIosSimulatorArm64` and
`:plugins:aps:compileAndroidMain`.

---

## Checklist results

### Copy list — exact 2, no extras

| dest file | exists before lot | notes |
|---|---|---|
| `compose/PkpdPresetProfiles.kt` | no — `compose/` folder did not exist | ✅ created |
| `compose/PkpdSettingsSupport.kt` | no | ✅ created |

No Compose screens copied. `PkpdPresetProfiles` landed first (required by brief — `detectPkpdInsulinPreset` returns `PkpdInsulinPreset`). ✅

### `pkpdPrefsSnapshotFrom` omission

`PkpdSettingsSupport.kt` does not contain `pkpdPrefsSnapshotFrom`, `PkpdPrefsSnapshot`, or any
`BooleanKey` import. File KDoc at lines 13–18 states the reason. ✅

### Package

Both files: `package app.aaps.plugins.aps.openAPSAIMI.compose` — not `androidx.compose`. ✅

### No `@Composable`

Grep across both files: zero matches. ✅

### KMP bans

| symbol class | result |
|---|---|
| `android.*` | absent ✅ |
| `File` | absent ✅ |
| `org.json` | absent ✅ |
| `R.string` | absent ✅ |
| `@Composable` | absent ✅ |
| `androidx.compose` | absent ✅ |
| `System.currentTimeMillis()` | absent ✅ |
| `String.format` / `java.util.Locale` | absent ✅ |
| `@Inject` / Hilt / `javax.inject` | absent ✅ |
| `@IntKey` / plugin registration | absent ✅ |
| `@Volatile` | absent ✅ |

### Therapy math

Preset clamp values (ULTRA_FAST / RAPID / STANDARD), `reclampPkpdLearnedStateToBounds`,
`resetPkpdLearnedStateToInitial`, `PkpdCorrectionPrudence` interpolation, `PkpdTailPrudence`
delegation to `PkpdSmbTailDamping`, `PkpdLearningPace` thresholds, and `detectPkpdInsulinPreset`
field selection — all match the dump source. No new math introduced. ✅

### Explicit imports — no FQ names at use site

- `PkpdSettingsSupport.kt`: imports `app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping`
  and `app.aaps.plugins.aps.openAPSAIMI.model.AimiAction` explicitly. `kotlin.math.abs` imported.
  Same-package `PkpdInsulinPreset` — no FQ needed. ✅
- `PkpdPresetProfiles.kt`: imports `DoubleKey` and `Preferences` only. ✅

### KDoc link resolution

| link | file | resolves |
|---|---|---|
| `[DoubleKey]` | PkpdPresetProfiles | imported ✅ |
| `[PkpdLearningPace]` | PkpdPresetProfiles | same-package (lands this lot) ✅ |
| `[PkpdCorrectionPrudence]` | PkpdPresetProfiles | same-package ✅ |
| `[PkpdTailPrudence]` | PkpdPresetProfiles | same-package ✅ |
| `[reclampPkpdLearnedStateToBounds]` | PkpdPresetProfiles | same file ✅ |
| `[PkpdSmbTailDamping]` | PkpdSettingsSupport | imported ✅ |
| `[PkpdCorrectionPrudence]` | PkpdSettingsSupport | same-package ✅ |
| `pkpdPrefsSnapshotFrom` / `PkpdPrefsSnapshot` / `AdvisorModels` | PkpdSettingsSupport | backtick prose, not `[link]` ✅ |

### Compile

Log: `/tmp/aimi-lot-Z.log`

```
> Task :plugins:aps:compileKotlinIosSimulatorArm64
> Task :plugins:aps:compileAndroidMain
BUILD SUCCESSFUL in 1m 1s
```

Both tasks ran (not UP-TO-DATE). EXIT 0. ✅

---

## Critical Issues 🔴

None.

---

## Important Issues 🟡

None.

---

## Suggestions 🟢

1. **`PkpdPresetProfiles.kt` line 83 — double coerceIn in `reclampPkpdLearnedStateToBounds`.**
   `dia` is first clamped to `[bDiaLo, bDiaHi]` inline, then `putClamped` clamps again to
   `[key.min, key.max]`. Harmless — the user bounds are expected to be inside the key limits —
   but a reader might expect the outer clamp to be the authoritative one. No change required;
   noting for awareness.

2. **`PkpdSettingsSupport.kt` line 103 — redundant outer `coerceIn` in `applyUiLevel`.**
   `(1.0 - uiLevel.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)`: the inner `coerceIn` already
   confines `uiLevel` to `[0, 1]`, so the subtraction result is always in `[0, 1]`, making the
   outer `coerceIn` a no-op. Harmless. Matches dump.

3. **`syncPkpdLearnedStateToBounds` name vs behaviour.**
   The deprecated alias is named "sync…ToBounds" but delegates to `resetPkpdLearnedStateToInitial`
   (a wipe, not a reclamp). The `@Deprecated` message makes the distinction clear. No action
   needed; the function is deprecated with a `ReplaceWith`.

---

## What Looks Good ✅

- Clean separation: pure `Preferences` + `DoubleKey` math, zero UI coupling.
- `putClamped` extension declared `private` to the file — not leaked as public API.
- `applyPkpdPreferenceUpdate` correctly handles all four preference key types with safe casts and
  returns `false` for unrecognised types; `else -> false` covers null since `newValue: Any`.
- `maxOf(maxStored, minStored)` in `applyLevel` prevents min > max storage — good defensive guard.
- File KDoc on `PkpdSettingsSupport` is accurate and actionable for the next reviewer.
- No `@IntKey(225)`, no plugin registration, no tick, no enact.
- `AdvisorModels` not split. ✅

---

APPROVE
