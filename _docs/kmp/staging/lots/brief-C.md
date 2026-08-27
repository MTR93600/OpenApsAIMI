# Task C — Place remaining AIMI algorithm files in androidMain

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
File list (exclusive): `_docs/kmp/staging/lots/lot-C.txt` (196 files, includes `DetermineBasalAIMI2.kt` and `OpenAPSAIMIPlugin.kt`)
Source: `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`
Destination: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`

## Do

1. Copy **only** `lot-C.txt`. Skip if destination exists. Never overwrite `commonMain`.
2. Mechanical Metro only:
   - `javax.inject.Inject` → `dev.zacsweers.metro.Inject`
   - `dagger.Reusable` / `@Reusable` → `@SingleIn(AppScope::class)`
   - Leave `org.json` as-is (androidMain can use it once a json dep exists; do not rewrite JSON in this task).
3. If `OpenAPSAIMIPlugin.kt` imports `app.aaps.plugins.aps.afrezza.AfrezzaMaxBasalConstraints` and that type is missing on this branch, comment that call with `// TODO(kmp): AfrezzaMaxBasalConstraints not on kmp yet` and make the site compile (no-op / skip). Do **not** invent `AfrezzaMaxBasalState` in `:core:data`.
4. Plugin class: Metro `@Inject` on the class if a single constructor (AGP warning otherwise). Do not add `@IntKey` here (Task A / controller registers).

## Do not

- Do not run Gradle, commit, or push.
- Do not edit `build.gradle.kts`, lot-A files, lot-B files, or commonMain.
- Do not extract the 18k tick into `aimi-engine`. Placement only.
- ⚠️ ASYNC IMPACT: do not add `suspend` to evaluate/tick APIs.

## Report

Write `_docs/kmp/staging/lots/report-C.md`: copied count, plugin/Afrezza note, skipped.
Return: status DONE | DONE_WITH_CONCERNS | BLOCKED, one-line summary.
