# Task C report — remaining AIMI algorithm files in androidMain

**Status:** DONE_WITH_CONCERNS

## Copied

- **196 / 196** files from `_docs/kmp/staging/lots/lot-C.txt`
- Source: `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`
- Destination: `plugins/aps/src/androidMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`
- Destination directory did not exist before this task; all 196 paths were new.
- Extra files at destination: 0
- Overlap with lot-A / lot-B: 0

## Metro

- No `javax.inject.Inject` or `dagger.Reusable` / `@Reusable` in lot-C sources.
- WIP copies already use `dev.zacsweers.metro.Inject` (38 files). No transform applied.
- `org.json` left as-is in `context/ContextManager.kt`.

## Plugin / Afrezza

- `OpenAPSAIMIPlugin.kt` and `DetermineBasalAIMI2.kt` are **not** in `lot-C.txt`. They are in `lot-A.txt`. Task C did not copy them (exclusive list).
- Brief-C says lot-C includes those two files; the exclusive list does not. Followed the exclusive list.
- `AfrezzaMaxBasalConstraints` is missing on this branch. No plugin file was placed here, so the Afrezza call was not commented. Task A owns that file.
- No `AfrezzaMaxBasalState` was invented.
- No `@IntKey` registration in this task.

## Skipped

- Destination already exists: **0**
- Missing source: **0**
- commonMain overwrite: **none** (no lot-C relative path exists under commonMain)
- `build.gradle.kts`: not edited
- `DetermineBasalAIMI2.kt` not extracted to `aimi-engine`
- Gradle / commit / push: not run

## Not tested

- Compile not run (brief: do not run Gradle).
