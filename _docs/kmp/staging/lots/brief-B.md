# Task B — Peel JSON T1 AIMI files into commonMain

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
File list (exclusive): `_docs/kmp/staging/lots/lot-B.txt` (46 files)
Source: `_docs/kmp/staging/openAPSAIMI-android-wip/<rel>`
Destination: `plugins/aps/src/commonMain/kotlin/app/aaps/plugins/aps/openAPSAIMI/<rel>`

## Do

1. For each file in `lot-B.txt`, if destination already exists, skip.
2. **Skip** `aimiNeuralNetwork.kt` (keep TFLite path; do not put NN core in commonMain). Note it in the report.
3. Replace `org.json` with `app.aaps.core.data.json.OrgJsonCompat` accessors on `kotlinx.serialization.json.JsonObject` (`optStringCompat`, `optLongCompat`, `optBooleanCompat`, `optJsonObjectCompat`, `optJsonArrayCompat`, `optDoubleCompat`, `optIntCompat`, `hasCompat`). Read `core/data/src/commonMain/kotlin/app/aaps/core/data/json/OrgJsonCompat.kt` first.
4. `javax.inject.Inject` → Metro `dev.zacsweers.metro.Inject`. No `java.util.Locale` / `String.format` — use `app.aaps.plugins.aps.openAPSAIMI.aimiFmt1` / `aimiFmt2` or `NumberFormat` + `SEPARATOR_DOT`.
5. `System.currentTimeMillis()` → `aimiWallClockMs()`. No `android.*` in commonMain. If a file still needs Android/files/HTTP after rewrite, **do not copy it**; leave it in staging and list it.

## Do not

- Do not write to `androidMain`.
- Do not run Gradle, commit, or push.
- Do not edit lot-A or lot-C files.
- Do not add module dependencies.

## Report

Write `_docs/kmp/staging/lots/report-B.md`: copied to commonMain, skipped, rewrite notes.
Return: status DONE | DONE_WITH_CONCERNS | BLOCKED, one-line summary.
