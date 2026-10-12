---
name: aps-commonmain-has-no-vico
description: Vico charting is unreachable from :plugins:aps commonMain because :core:graph is declared only in its androidMain - proven by compile probe, and the reason AimiCognitiveOrefCoachCards cannot move
metadata:
  type: reference
---

`:core:graph` is a KMP module with an `iosArm64()` target and it exposes Vico with
`api(libs.com.patrykandpatrick.vico.compose)` from its **commonMain**. So Vico itself is fine for
iOS. The block is on the consumer side: `plugins/aps/build.gradle.kts` declares
`implementation(project(":core:graph"))` in **androidMain only**, and `:core:ui` does not pull Vico
in either. Any `:plugins:aps` file that draws a chart is therefore pinned to androidMain.

**Proven, not grepped.** A one-import throwaway file in `plugins/aps/src/commonMain` importing
`com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer` fails
`:plugins:aps:compileKotlinIosArm64` with `Unresolved reference 'com'` - the package root does not
exist for that source set. A grep of the file's imports says nothing about this, because the import
is neither `android.*` nor `androidx.*`.

Clearing it means adding `implementation(project(":core:graph"))` to `:plugins:aps` commonMain. That
is a build change and a new module edge for the common/iOS source set, so it is the user's call, not
a moving agent's. Do not instead delete the chart to make the file compile.

Known victim: `openAPSAIMI/advisor/compose/AimiCognitiveOrefCoachCards.kt` (its private
`OrefCgmRangeChart`). Its other apparent blocker, `rh: ResourceHelper`, is **not** one -
`ResourceHelper : TextResolver`, so the parameter just widens to `TextResolver`.

Related: [[aimi-control-center-move]], [[cmp-what-crosses-unchanged]]
