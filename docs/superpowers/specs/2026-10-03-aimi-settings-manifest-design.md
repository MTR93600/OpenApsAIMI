# AIMI settings: one manifest, three levels, and a test that keeps every setting reachable

Design agreed with the maintainer on 2026-10-03. Branch `dev_OAPSAIMI`, base `3dd0ca6477`.

## Why

A real defect started this. `aimi_isf_fusion_max_change_per_tick` was stored at `0.4` on the
maintainer's device while the algorithm that reads it declares `0.03` as its own default
(`pkpd/IsfFusion.kt:9`). Measured effect: the dose-facing sensitivity swung more than 1.5x between two
consecutive loop ticks on 11 % of ticks, and at equal glucose the computed dose changed by a factor of
two. The slider that would have fixed it exists, in `PkpdExpertSettingsContent`. **That function is
called from nowhere**: `AimiPkpdSettingsScreen.kt:169` routes both `ADVANCED` and `EXPERT` to the
advanced content. The setting was unreachable, so the stale value could never be corrected, and nothing
in the build complained.

AIMI has **230 preference keys** (79 Boolean, 106 Double, 21 Int, 12 String, plus the AIMI-local
`AimiStringKey` and `AimiLongKey`). The goal is not fewer settings. It is that the user only has to move
the ones that belong to them, and that no setting can ever become unreachable again.

## What we are building

Three things, and a fourth that keeps them honest.

### 1. The manifest — `AimiSettingsManifest.kt`

One entry per AIMI key, beside the existing `AimiBehaviorFamilyRegistry`, which it extends rather than
replaces. A key is identified by its string key, as the registry already does.

Each entry carries exactly three facts:

- **level**: `SIMPLE`, `ADVANCED` or `EXPERT`. Exactly one.
  - `SIMPLE` — the person's own therapy: profile-shaped values and safety limits. Max SMB, max IOB,
    max basal, targets. If a clinician would recognise it, it belongs here.
  - `ADVANCED` — intent. One knob that shapes a family's behaviour, the way the PKPD sliders already
    do. The user says "more cautious about meals", not "min factor 0.75".
  - `EXPERT` — everything else, with the screen saying plainly that nobody checks behind you.
- **family**: one of the existing `AimiBehaviorFamilyId` values — `Protection`, `MealCapture`,
  `Stability`, `Physio`, `Autonomy`.
- **writtenBy**: who may write the value. `USER`, `SLIDER`, `PRESET`, or `LOOP`.

`writtenBy = LOOP` is not decoration. The transient preference overlay rewrites `OApsAIMIMaxSMB`,
`OApsAIMIHighBGMaxSMB` and the priority max-IOB keys from inside a loop tick. Without this field the
audit screen below would accuse the user of changes they never made, and it would be noise within a
week. With it, the screen can say "changed by the loop" instead.

The manifest holds **no values**: no defaults, no bounds. Those live on the keys, which are their only
legitimate source.

### 2. The screen declarations — `AimiSettingsScreens.kt`

For a test to prove a setting is reachable, the list of displayed keys must be data, not layout code.

Each section — "Stress ISF floor", "ISF fusion", "Protection" — is declared with its level and its keys.
The existing Compose screens keep their hand-written layout and read their key list from the
declaration instead of holding it inline. The PKPD simplified sliders keep their own ergonomics; they
declare the keys they drive.

### 3. The audit screen

Lists every AIMI setting whose stored value differs from the shipped default, with:

- the stored value and the default side by side,
- **who changed it**, from `writtenBy` plus the change timestamp where the platform has one,
- a one-tap return to the default.

The maintainer chose visibility over silent migration: a changed default does NOT reach an existing
install on its own. The audit screen is what makes the gap findable instead of invisible. On the first
build after this lands, it will show `aimi_isf_fusion_max_change_per_tick` at 0.4 against a default of
0.03 for every existing user — which is exactly the point.

### 4. The tests that keep it true

Four, all failing the build:

1. **Completeness** — every AIMI key in `BooleanKey`, `DoubleKey`, `IntKey`, `StringKey`, `AimiStringKey`
   and `AimiLongKey` appears in the manifest exactly once. A new key breaks the build on the day it is
   written.
2. **Reachability** — every manifest key belongs to a section, and that section's level is one the
   navigation really routes. This is the test that would have caught the defect above.
3. **Consistency** — a key's manifest level equals the level of the section holding it.
4. **The exemption list only shrinks** — see below.

## Bootstrapping 230 keys

A test that fails on day one for 230 reasons gets disabled within a week. So the manifest ships with an
explicit `UNCLASSIFIED` set holding the keys not yet placed, and test 4 asserts its size never grows.
Keys leave it in batches; the day it is empty it is deleted and tests 1-3 stand alone.

The maintainer asked for the full classification in one pass, so the intended end state of this change
is `UNCLASSIFIED` empty. It stays in the design because the mechanism is what prevents the next
regression, not because we plan to need it.

## What this change does NOT do

- It does not change any default, any bound, or any behaviour. Not one line of dosing code.
- It does not remove or rename a key. Stored values are untouched.
- It does not migrate anything. The audit screen shows; the user decides.
- It does not route `EXPERT` to its own content as a side effect — that is part of the work, and it is
  the one visible behaviour change: the expert section becomes reachable.

## Testing

Beyond the four structural tests: a unit test that the audit screen's "differs from default" logic is
right for each key type, including a `LOOP`-written key (it must be reported as loop-written, not as a
user change), and a test that `PkpdExpertSettingsContent`'s keys are reachable — the regression test for
the defect that started this.
