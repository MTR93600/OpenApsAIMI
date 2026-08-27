# W8 go / no-go (AIMI KMP shells)

> Date: 2026-08-27  
> Branch: `kmp-aimi-migration-study`  
> Freeze: `aimi-baseline-2026-08-26` (`1ae418e106`)

## Product call used for this note

CGM hardware smoke is **deferred**. One+ and Libre 3 Android plugins stay as landed in W3/W4.
Libre 3 NFC Android is an **explicit later flag** (W7 allowed this). Test sensors come after
an extracted AIMI engine, not before.

## GO checks

| Check | Status |
|---|---|
| Freeze tag + ADR G0 | yes (docs) |
| `SourceSensor` native values in `commonMain` | yes (W1) |
| One+ Metro `@IntKey(446)` in the APK plugin map | yes (W3). Not proven on a body. |
| VirtualPump on `full` flavor | yes (`:pump:virtual` in `:app`) |
| `aimi-contracts` JVM + iOS simulator | W5 compiled; re-checked with W7 types |
| Empty AIMI KMP modules compile JVM + iOS | **yes** — JVM tests + `compileKotlinIosSimulatorArm64` + `aimi-engine` `compileKotlinIosArm64` (2026-08-27, this Mac) |
| Metro recipe, no Hilt in `:app` for AIMI leaves | yes for One+/Libre 3 |
| Host still Trio; first CGM still One+/G7 | ADR G0 unchanged |
| `evaluate()` is **not** in `aimi-contracts` | yes; it lives in `:plugins:aimi-engine` |
| Engine import ban (Android/Room/prefs/files/coroutines) | `checkAimiDomainImports` on engine + learning |

## NO-GO (not claimed)

- AIMI **tick** (`DetermineBasalAIMI2` / `DetermineBasalaimiSMB2`) is not extracted.
- T0 helpers may exist in `openAPSAIMI/` commonMain. That is not therapy.
- `HoldAimiEngine` always returns `Hold("ENGINE_NOT_EXTRACTED")`. That is not therapy.
- No Android dual-write / `engine-replay-v1`. M1 replay gate is **open**.
- No Tree / Harmonia / RBT / UAM in commonMain. Do not say “AIMI runs on iOS”.
- One+ / Libre 3 **body** test: not run. User will test CGMs after AIMI extract.

## What “AIMI port” means next (after this GO)

1. Keep the freeze tick on the tag. Do **not** rebase `dev_OAPSAIMI`.
2. M1: Android shell that fills `AimiInputSnapshot` + records expected Hold/SMB **from freeze**, then replay.
3. M3: one Native slice (Tree → safety Hold) against that corpus.
4. M4: move stages into `HoldAimiEngine` → a real `AimiEngine` **without** pump I/O.

Until M1 replays, iOS dosing work stays stopped.
