# Task K — Missing freeze AIMI strings into aimi_strings.xml

Work from: `/Users/mtr/StudioProjects/OpenApsAIMI`
Freeze: `aimi-baseline-2026-08-26`

Catalog: `_docs/kmp/staging/lots/report-F.md` section 6 (~198 `R.string` names used by dump, missing from kmp).

## Do

Copy **English** `<string name="...">` from freeze `plugins/aps` resources into:

`plugins/aps/src/androidMain/res/values/aimi_strings.xml`

Sources on freeze (try in order):

- `plugins/aps/src/main/res/values/strings.xml`
- `plugins/aps/src/main/res/values/aimi_strings.xml` if it exists
- other `values/*.xml` under freeze `plugins/aps/src/main/res/`

Only add names that are **missing**. Do not duplicate. Ignore translations. School English: if freeze English is already school English, copy as-is.

If a name is used by dump but **not** in freeze English, add a short placeholder title equal to the name with underscores → spaces, and list those in the report.

Do **not** restore layouts. Layouts stay in `_docs/kmp/staging/res-layout-wip/`.

## Verify

Do **not** run `testAndroidHostTest` (layouts were the AAPT break; they are parked). Optional:

```
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
./gradlew --no-daemon :plugins:aps:compileAndroidMain :plugins:aps:compileKotlinIosSimulatorArm64
```

No commit.

## Report

`_docs/kmp/staging/lots/report-K.md`: how many copied, how many placeholders, leftover names.
Return DONE | DONE_WITH_CONCERNS | BLOCKED.
