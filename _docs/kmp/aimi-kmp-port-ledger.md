# AIMI KMP port ledger (Milos pattern)

Destination: `:plugins:aps` `commonMain` (`openAPSAIMI/`), types in `:core:interfaces` `commonMain`.
Same shape as OpenAPS SMB: algorithm in commonMain, Metro `@Inject`, no Hilt, no `android.util.Log`.

Freeze: `aimi-baseline-2026-08-26` (`dev_OAPSAIMI` `1ae418e106`). 441 main files.

## Lot status

| Folder | commonMain | androidMain (WIP, uncommitted until it compiles) | Notes |
|---|---|---|---|
| `core/interfaces` AIMI DTOs | done | — | `GlucoseStatusAIMI`, `OapsProfileAimi`, adaptation status |
| `model/` | done | — | `aimiWallClockMs()`; `processDecision` does not enact |
| `ports/` `extensions/` `carbs/` `decision/` `validation/` | done | — | Metro on validator |
| `keys/` | partial | `AimiStringKey` | needs `R` + steps provider |
| `CircadianMath` `TimestampedBgSample` `AimiFmt` `AimiWallClock` | done | — | |
| `ISF/` | partial | tuners + JSON | T0 blender/policy/engine in common |
| `prediction/` | partial | rest | T0 clamp/curve/sanity in common |
| `safety/` | mostly | governor/stance/export | T0 helpers in common |
| `pkpd/` `physio/` `recursive/` `scenario/` `smb/` `patient/` … | T0 subset | graph-blocked T0 + T1/T2/T3 | iOS fixpoint: 111 files stay commonMain |
| `KalmanFilter` `DetermineBasalAIMI2` `OpenAPSAIMIPlugin` | not yet | dumped, does not compile | TFLite/HC/ONNX deps missing on this branch |
| Advisor UI, SOS, Compose screens, HC, TFLite, ONNX | never commonMain | dumped | T2/T3 hosts |

## Counts (2026-08-27)

- Freeze main: 441 kt
- `commonMain` AIMI: **111** kt — `compileKotlinIosSimulatorArm64` **BUILD SUCCESSFUL**
- `androidMain` AIMI dump: **332** kt — **not compiling** (Metro `@Reusable`, TFLite, Health Connect, ONNX, Hilt workers). Kept in the working tree, not in the iOS-green commit.
- English AIMI strings: `plugins/aps/src/androidMain/res/values/aimi_strings.xml` (924 entries from freeze). No translations copied.

## Next (same day)

1. Metro instead of `dagger.Reusable` / Hilt modules / `@HiltWorker`.
2. Add freeze Android deps (TFLite, Health Connect, ONNX) on `androidMain` only.
3. Compile `compileAndroidMain`. Register plugin `@IntKey` like SMB 220.
4. Peel JSON T1 files from androidMain back to commonMain via `OrgJsonCompat`.
5. Tick (`DetermineBasalaimiSMB2`) last, `Context` → `TextResolver`.

Do not say AIMI runs on iOS. Hold engine is still Hold. Tick is not extracted into `aimi-engine`.
