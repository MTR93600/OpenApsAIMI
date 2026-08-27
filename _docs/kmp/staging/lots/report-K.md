# Task K report — Missing freeze AIMI strings into aimi_strings.xml

Status: **DONE_WITH_CONCERNS**

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`  
Branch: `kmp-aimi-migration-study`  
Freeze: `aimi-baseline-2026-08-26`  
Catalog: report-F section 6 (**198** dump `R.string` names missing from kmp English)

Gradle: **not run** (brief: other agents compile). No layouts restored. No `ApsIntentKey.kt`. No `:core:keys`. No commit, no push.

Owned file: `plugins/aps/src/androidMain/res/values/aimi_strings.xml` only.

## Source

Freeze `plugins/aps/src/main/res/values/` tried in order:

| File | Used |
|---|---|
| `strings.xml` | **198** copied (all catalog names) |
| `aimi_strings.xml` | does not exist on freeze |
| `wcycle_strings.xml` | 0 of the 198 |
| `styles_context_patient_state.xml` | no `<string>` entries |

English copied as-is. No translations. No name already in `aimi_strings.xml` or `plugins/aps/.../strings.xml`.

## Counts

| Result | Count |
|---|---:|
| Copied from freeze English | **198** |
| Placeholders (dump name not in freeze English) | **0** |
| Catalog leftover (still missing after copy) | **0** |
| Duplicates skipped | **0** |

`aimi_strings.xml` string names: 935 → **1133**.

## Leftover dump `R.string` (not in the 198)

Dump still uses **17** names that already exist in `core.ui.R` / `core.interfaces.R`. They were **not** copied (would duplicate English already in the repo; report-F already called these out). Dump binds `app.aaps.plugins.aps.R` unless the call site uses the other `R` (plugin already does this for `advanced_settings_title`).

- `advanced_settings_title`
- `back`
- `cancel`
- `current_basal_value`
- `format_insulin_units`
- `limitingbasalratio`
- `no_profile_set`
- `ok`
- `profile_carbs_ratio_value`
- `profile_dia`
- `profile_high_target`
- `profile_low_target`
- `profile_max_daily_basal_value`
- `profile_sensitivity_value`
- `temp_target_high_target`
- `temp_target_low_target`
- `temp_target_value`

## Concerns

1. **17 core-module names** above still fail on `plugins.aps.R` until call sites switch `R` (or a later lot copies them). Not part of the 198.
2. Freeze `user_preferences` English is **Preferences user** (awkward). Copied as-is.
3. **Not AAPT-tested.** Gradle not run.

## Status

`DONE_WITH_CONCERNS` — copied **198**, placeholders **0**, catalog leftover **0**.
