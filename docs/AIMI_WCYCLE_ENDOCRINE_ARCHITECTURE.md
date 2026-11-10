# AIMI WCycle Endocrine Architecture

Status: **Lots A–D production** (governor owns dose amps; no shadow decision path).

## Authority (production)

| Layer | Role |
|-------|------|
| `WCycleAdjuster` | Phase/day/base multipliers only (estimator) — **no dawn**, no dose apply |
| `EndocrineAmplitudeGovernor` | **Sole** soft hormonal amp owner: dawn + hypo dampen + hard unity |
| `PhysiologicalTree.hormonalResistance` | Belief for Harmonia posture |
| `HarmoniaDecisionEngine` | BASAL_FIRST: `endocrineBasalAmp` null→1.18 posture; APPLIED amp (incl. 1.0 unity) honored — no 1.18 fallback; PROTECTIVE if hypoRisk ≥ 0.30 |
| `setTempBasal` / SMB / IC | Apply `productionAmp(belief)` once; skip basal amp if Harmonia already owns rate |
| RBT `WCYCLE_VS_HYPO` / `WCYCLE_VS_STABLE` | Force `ReleaseAuthority.NONE` |
| Safety / LGS / Harmonizer↓ | Absolute veto |

## Production rules (no shadow decisions)

1. Dose path reads **governor effective amps** only (`EndocrineAmplitudeGovernor.productionAmp`).
2. User WCycle shadow/confirm prefs → `applicationMode ≠ APPLIED` → production amp = **1.0** (opt-out, not a decision shadow).
3. Hard unity: `hypoLoad ≥ 0.45` or (`HYPO_GUARD` and `hypoLoad ≥ 0.25`) → effective amps = 1.0.
4. Luteal dawn (+10% 04–07h) lives in the governor and is hypo-dampened (closes dawn yoyo under hypo load).
5. No parallel `× wCycle.basalMultiplier` on the pump path.
6. RBT leaf mults fed from governor effective amps (not raw adjuster).

## Adaptive basal

- Default `OApsAIMIT3cAdaptiveBasalEnabled = true`.
- One-shot upgrade re-enable if Stability/HYPO_GUARD writeback had forced OFF.
- Stability family levels no longer force adaptive basal OFF.

## JSONL

- `adjustments.endocrine_belief` — full belief + `dose_path_owner`
- `physio_context.cycle_phase` refreshed after WCycle resolve
- Tree `hormonal_resistance` reasons include phase / effective / legacy adjuster baseline

## Invariants

1. At most one soft endocrine scale per dose axis per tick.
2. Safety zeroes SMB before endocrine SMB amp.
3. `bgNow ≤ hypoGuard` → no basal endocrine uplift in `setTempBasal`.
4. `WCYCLE_VS_HYPO` → no SMB release authority.
5. Tree never owns insulin; governor + Harmonia production + legacy cascade share named channels only.
