# AIMI KMP lot pipeline (orchestrator)

Parent agent is the chef d'orchestre. Auto-prompt: do not stop between lots unless BLOCKED.

Per lot:

1. **Merge/docs** — only when kmp/iOS practices changed (or first lot after a merge).
2. **Scope** — write `brief-N.md`: exact files, compile tasks, do-not list.
3. **Code** — implement only that brief. Metro, explicit imports, school English, `TextResolver` not `ResourceHelper` in commonMain, no `android.util.Log` in commonMain, no Hilt.
4. **Review** — senior architecture + senior Kotlin. Spec + quality. Critical/Important must be fixed before commit.
5. **Commit** — one commit per lot. No push. Never commit a dump that breaks `:plugins:aps:compileAndroidMain`.

Frozen:

- Engine: `evaluate(snapshot, state, models) → result`. No pump mid-tick.
- T2/T3 stay androidMain (Advisor UI, SOS SMS, Camera, Health Connect, TFLite, ONNX).
- Tick last. Do not say AIMI runs on iOS. `HoldAimiEngine` is Hold until evaluate is real.
- Strategy S2. Metro. Do not rebase freeze onto kmp.

Verify after code:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileKotlinIosSimulatorArm64 :plugins:aps:compileAndroidMain
```

macOS: `./gradlew`, never `cd &&`.

---

## Milos merge 2026-08-28 — MUST-copy import rules

Merge: `903d725489` (`kmp` into `kmp-aimi-migration-study`). Freeze stays `aimi-baseline-2026-08-26`.
Teacher: OpenAPS SMB in `:plugins:aps` `commonMain`. Full examples: [`report-merge-kmp.md`](report-merge-kmp.md).

Copy these. Do not invent a second style.

1. **Source sets.** Algorithm → `src/commonMain`. Android host (plugin map, workers, TFLite, Health Connect, layouts) → `src/androidMain`. Tests → `src/androidHostTest`. `:plugins:aps` has iOS *targets* and an empty `iosMain` — do not add AIMI iOS shells, and do not say AIMI runs on iOS.
2. **Gradle.** Keep `GenerateKeyStringsTask` (`ApsStrings` / `ApsStringIds`). Point `resDir` at `src/androidMain/res`. The task reads **every** xml under `values/` (including `aimi_strings.xml`). Do not add new `implementation(project(":…"))` edges.
3. **DI.** `import dev.zacsweers.metro.Inject` (and `AppScope` / `SingleIn`). No Hilt. No `dagger.hilt`. Android-only modules may still use `javax.inject.Inject` *only* when that module has `metro { interop { includeDagger() } }` — OpenAPS SMB in `commonMain` does **not**; AIMI `commonMain` must match SMB.
4. **Strings in commonMain.** Constructor `rh: TextResolver`, not `ResourceHelper`. Plugin names: `ApsStrings.foo`, not `R.string.foo`. Keys in `:core:keys`: `title = KeysStrings.pref_title_…` (`TextRef`), not `titleResId`. Never hand-write `TextRef.Named("…")`. `androidMain` may still use `ResourceHelper` (Autotune does).
5. **Prefs.** Read `BooleanKey` / `DoubleKey` / … through `Preferences`. `EventPreferenceChange(key: String)` and `isChanged(preferenceKey: String)` — pass `SomeKey.key`. `KeyValueStore` is the string-key store in commonMain. `SP.getString(@StringRes …)` is Android only.
6. **Time.** `aimiWallClockMs()` or `Clock.System.now().toEpochMilliseconds()` with `import kotlin.time.Clock`. Not `System.currentTimeMillis()`. Injected `dateUtil.now()` is fine when the class already has `DateUtil` (SMB plugin).
7. **Log.** `aapsLogger.debug(LTag.AIMI, …)` (or `LTag.APS` only if you are in OpenAPS SMB). Not `android.util.Log`.
8. **JSON.** Writes: `kotlinx.serialization.json` builders. Reads: `OrgJsonCompat.opt*Compat`. Not `org.json`.
9. **NotificationId.** Append at the **end** of the enum. Never insert. AIMI ids are already last.
10. **One+ / Libre 3.** Already on this branch as Android Metro plugins (`@IntKey(446)` / `@IntKey(447)`). Do not re-port them inside AIMI lots. Do not put GATT in `commonMain`. Do not copy `:plugins:source`’s current gradle (Hilt + `com.android.library` vs `androidMain` folders) — that is a merge leftover; kmp’s file was KMP.

Lot size: a few dozen T1 files, not the 324-file dump. Tick last.
