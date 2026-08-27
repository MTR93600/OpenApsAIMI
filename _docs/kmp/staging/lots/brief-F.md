# Task F — Remaining unresolved API catalog + `LTag.AIMI`

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
Branch: `kmp-aimi-migration-study`
Freeze: `aimi-baseline-2026-08-26`

Dump (read only): `_docs/kmp/staging/openAPSAIMI-android-wip/`
Tick: `DetermineBasalAIMI2.kt` (~18k LOC). Plugin: `OpenAPSAIMIPlugin.kt`.

## Do (1) LTag

Freeze `LTag` has **no** `AIMI`. Dump currently logs with `LTag.APS`.
Add `AIMI("AIMI")` to kmp `core/interfaces/src/commonMain/kotlin/app/aaps/core/interfaces/logging/LTag.kt` anyway, so later host code can use it without a second pass.
Place after `APS`. Do not change other tags.

## Do (2) Catalog only

Scan dump kotlin (especially tick + plugin) for symbols that will not resolve on this kmp branch **after** keys (lot D) and PersistenceLayer carb helpers (lot E).

Write `_docs/kmp/staging/lots/report-F.md` grouped by owner module:

1. Missing types / functions in `:core:interfaces` (not keys, not the 3 carb helpers)
2. Missing types in `:core:data` / `:core:objects`
3. Missing Android host APIs (Context, WorkManager, Health Connect) — list, do not port
4. `AfrezzaMaxBasalConstraints` / `AfrezzaMaxBasalRate` — confirm still absent
5. `TirCalculator` / `TIR` — already restored; say if dump still calls something else
6. `R.string.*` still missing from `plugins/aps/src/androidMain/res/values/aimi_strings.xml`
7. Anything that imports `dagger` / Hilt / `android.util.Log` still in dump

Be concrete: symbol name + example file:line. Cap at ~80 rows. Prioritize tick + plugin.

Do **not** restore dump to androidMain. Do **not** edit dump sources except if you must grep.

## Do not

- Do not edit `:core:keys`
- Do not edit PersistenceLayer carb helpers (lot E owns that)
- Do not commit or push
- Do not run a full app assemble

Optional compile after LTag only:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :core:interfaces:compileKotlinIosSimulatorArm64
```

Skip if lot E is compiling the same module at the same time (file conflict on PersistenceLayer). Prefer **not** compiling interfaces if you only add LTag — LTag is a one-line enum add; controller will compile.

## Report

`_docs/kmp/staging/lots/report-F.md`
Return: DONE | DONE_WITH_CONCERNS | BLOCKED + one-line summary.
