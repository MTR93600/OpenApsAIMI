# AIMI Assistant: handoff notes

Read this first in a new chat. Branch: `feature/smb-quality-threshold` (repo joojik5-spec/OpenApsAIMI, base `dev_OAPSAIMI`).
The user speaks Russian. Main working language of the app is English. Keep replies short. Use simple English in code and commits.
Do not mention the AI tool in commits or files. Do not commit or push unless asked. Do not install the app on a device unless asked.

## User and setup
- Type 1 diabetes. Android APS fork with AIMI. Fully closed loop (FCL): no carbs are entered. Medtrum patch pump. DynamicISF must stay on.
- Problems: overshoots and lows (down to 2.5 to 2.8 mmol/L) and sometimes a late first SMB when glucose goes above 8 mmol/L.
- The user installs builds from his own fork, not from the upstream MTR repo.
- GitHub Actions build "Android Build (Signed)" #154 on `dev_OAPSAIMI` failed. Branch CI could not be checked. He builds locally.

## What is done (all in the branch)
1. SMB model training fix (commits a3d7de6, c2a457d, 99be4b1): the strict row filter dropped almost every real SMB row (dual_delivery flags, quality limit 0.52, validation set with zeros only).
   Now: harmless dual delivery flags are ignored, limit 0.40, block split. Two user settings: `OApsAIMISmbTrainingMinQuality` (0.40) and `OApsAIMISmbTrainingRelaxedFilter` (true).
   The user said: leave training as in his current build and only let him change the values in the app. Both are in the top card of the assistant screen.
2. New screen `advisor/assistant/AimiAssistantActivity.kt` (package `app.aaps.plugins.aps.openAPSAIMI.advisor.assistant`):
   - `AssistantAnalyzer` reads `AIMI_Decisions.jsonl` (streams the file, 5 days), finds low episodes (< 3.5 mmol/L) and late first SMB episodes. Doses are not used: the log holds proposals, not delivered insulin.
   - `AssistantProposals` has priority rules. `AssistantCatalog` has 10 settings with English names, default, min, max, step and a note about related settings.
   - `AssistantHistory` saves each change (old value, new value, time, stats before). The "!" button shows the standard value, all changes and before/after stats, and "Restore standard".
   - Rules: one change at a time, then wait 1 day. The first proposal in the priority list is shown, the rest are listed as the queue.
3. Small green "AI" button on the home screen: `app/.../compose/dashboard/DashboardOverviewHost.kt` (top end, 64 dp from the top). The original Advisor button is unchanged.
4. Strings are in `plugins/aps/src/main/res/values/strings.xml` and `values-ru-rRU/strings.xml` (keys `aimi_as_*`, `aimi_adv_train_*`). Russian text is hand written.
5. CI: JetBrains Runtime 21 install step added to `.github/workflows/aaps-ci.yml`. This is a guess, not confirmed.

## Not verified
- The code was never compiled. Expect compile errors on the first build. Fix them from the log.
- Rules were made from code reading and the user's logs, not from tests on the device.
- Open: the order of the late SMB gates, the AAPS side of "max minutes of basal", and 37 uncertain rows in the catalog.

## Findings about AIMI settings (details in `docs/AIMI_SETTINGS_CATALOG.md`, 313 settings)
- Max minutes of basal for SMB (`ApsMaxMinutesOfBasalToLimitSmb`, UAM version): AIMI does not use them. Do not suggest them.
- Max SMB and High BG Max SMB are not one hard cap. In meal and rise situations the High BG value is the real limit. Red Carpet can restore a cut dose (needs priority context for values above 0.60). Priority MaxIOB raises the IOB limit (10 -> 12 U with 10 / 1.2 / 2.0).
- SMB interval comes from per-mode interval keys and `High BG interval`. Delta above 15 forces 1 minute. `ApsMaxSmbFrequency` is not the SMB interval.
- TPO can overwrite 15 keys for a while (MaxSMB, HighBGMaxSMB, Priority MaxIOB factor and extra, RedCarpet, damping keys, and others).
- Dead keys (never read): 33. Mode Factor 50 halves SMB only in meal modes, not in FCL without modes.
- Model refine can change an SMB by at most min(0.05 U, 25 percent): the model alone does not cause large overdoses.
- Log facts from 22 to 30 Oct 2026: TIR 92 percent, 2.1 percent below 3.9. Lows at 25.10 17:38 (3.0), 27.10 01:19 (2.7), 30.10 04:22 (2.6): each came after a series of SMB with IOB near zero.
  `AIMI_Decisions.jsonl` lines are about 40 KB each. The csv `oapsaimi2_records.csv` has real `smbGiven` but no lows.

## Next steps
1. Build on the user's PC (`C:\Users\ThinkPad\AndroidStudioProjects\OpenApsAIMI`, script `build_aimi.bat` there, logs `build_aimi.log` and `gradle_out.log`). Fix compile errors, push, build again.
2. Emulator on real logs: use `smb_binding_trace` and `dose_terminal_snapshot` inside the decision log to see which cap acted in each episode.
3. Test the assistant on the phone (the user installs the APK himself). Check the Russian texts and the button place.
4. Extend rules for late reaction in FCL, add more settings after checking them in the catalog.
