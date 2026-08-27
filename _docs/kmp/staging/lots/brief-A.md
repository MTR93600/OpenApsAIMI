# Task A — AIMI androidMain host (T2/T3, Metro, deps)

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
File list (exclusive): `_docs/kmp/staging/lots/lot-A.txt` (90 files)
Source copies: `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`
Destination: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`

## Do

1. Copy **only** files in `lot-A.txt`. Do not overwrite a file that already exists at the destination.
2. Metro, not Hilt:
   - `javax.inject.Inject` → `dev.zacsweers.metro.Inject`
   - `dagger.Reusable` / `@Reusable` → `@SingleIn(AppScope::class)` + imports
   - `@HiltWorker` + `dagger.assisted` → same pattern as `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/loop/runningMode/RunningModeExpiryWorker.kt` (`Metro AssistedInject`, `MetroWorkerCreator`)
   - `di/WCycleModule.kt` and `di/AIMIStepsProviderModuleMTR.kt`: Hilt `@Module`/`@InstallIn` → Metro `@ContributesTo(AppScope::class)` + `@BindingContainer` + `@Provides` like `ApsPluginRegistrations.kt`. Drop empty Hilt scan-only module if it has no `@Provides`.
3. `plugins/aps/build.gradle.kts` **androidMain only** (do not add to commonMain):
   - `org.tensorflow:tensorflow-lite:2.4.0`
   - `androidx.health.connect:connect-client:1.1.0`
   - `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`
   Same versions as freeze `aimi-baseline-2026-08-26`.
4. Copy freeze English layouts if missing:
   `git show aimi-baseline-2026-08-26:plugins/aps/src/main/res/layout/<name>` → `plugins/aps/src/androidMain/res/layout/`
   Files: `activity_aimi_hc_permission.xml`, `activity_aimi_sos_permission.xml`, `activity_context.xml`, `item_active_intent.xml`, `item_patient_signal_gauge.xml`.
5. Register plugin in `ApsPluginRegistrations.kt`: `@IntKey(225)` `OpenAPSAIMIPlugin` **only if that class exists at destination after copy**. If the plugin file is not in lot-A (it is in lot-C), skip registration and say so.

## Do not

- Do not run Gradle (controller compiles after all agents).
- Do not commit or push.
- Do not edit `commonMain`, ledger, lot-B/lot-C files, or `DetermineBasalAIMI2.kt`.
- Do not install the app. School English. Explicit imports.

## Report

Write `_docs/kmp/staging/lots/report-A.md`: files copied, Metro edits, skipped, blockers.
Return: status DONE | DONE_WITH_CONCERNS | BLOCKED, one-line summary.
