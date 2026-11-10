#!/usr/bin/env python3
"""
Harnais de rejeu (lot 5) — rejoue un paquet AIMI_Decisions*.jsonl et estime l'effet des lots 1 et 2
sur la TBR finale, tick par tick.

⚠️  Outil d'analyse hors produit. Il réimplémente les formules Kotlin ; il ne remplace PAS les tests
    unitaires, qui restent la source de vérité. Les formules sont volontairement écrites à l'identique :

      lot 1 (DynamicBasalController) — legacy   : mult = 1 + 0,05·(bg − cible) + 1,8·v
                                       projeté  : mult = min(1 + 0,05·((bg + v·(H/5)) − cible), legacy)
                                                  H = 60 ; le min() garantit qu'on ne freine jamais moins
                                                  que l'ancienne formulation en descente.
      lot 2 (BasalTerminalInvariants)          : plafond au basal profil si
                                                 (bg < cible et eventual < cible) | post-hypo | (iob<0 et delta<=0)

    Limite majeure : le JSONL ne contient pas le taux d'entrée du contrôleur, seulement la TBR finale.
    On ne peut donc pas rejouer la chaîne complète. Le lot 1 est estimé par le **ratio** de
    multiplicateurs appliqué à la TBR observée, et n'est calculé que sur les ticks dont la narration
    porte `PI-Fallback:` (les seuls où la formule du contrôleur est réellement celle qui a produit le
    taux). Le lot 2, lui, est exact : il ne dépend que d'états présents dans le tick.

Usage :
    python3 replay_harness.py <AIMI_Decisions_Last24h.jsonl>
"""

import json
import re
import sys
from collections import Counter

HORIZON_MIN = 60.0
P_WEIGHT = 0.05
D_WEIGHT = 0.15

PI_RE = re.compile(r"PI-Fallback: P=([-\d.,]+) D=([-\d.,]+) Mult=([\d.,]+)x")


def _f(x):
    return float(str(x).replace(",", "."))


def legacy_mult(p_err, velocity):
    return max(0.0, min(10.0, 1.0 + p_err * P_WEIGHT + velocity * 12.0 * D_WEIGHT))


def projected_mult(bg, target, velocity, p_err):
    projected = bg + velocity * (HORIZON_MIN / 5.0)
    raw = min(1.0 + (projected - target) * P_WEIGHT, legacy_raw(p_err, velocity))
    return max(0.0, min(10.0, raw))


def legacy_raw(p_err, velocity):
    return 1.0 + p_err * P_WEIGHT + velocity * 12.0 * D_WEIGHT


def terminal_cap(rate, profile_basal, bg, target, eventual, delta, iob, meal_mode, post_hypo):
    """Retourne (taux_borné, invariant_liant|None). Réduction seule."""
    if meal_mode or rate <= 0 or profile_basal <= 0:
        return rate, None
    caps = []
    if eventual is not None and bg < target and eventual < target:
        caps.append(("below_target", profile_basal))
    if post_hypo:
        caps.append(("post_hypo", profile_basal))
    if iob < 0 and delta <= 0:
        caps.append(("negative_iob", profile_basal))
    if not caps:
        return rate, None
    name, cap = min(caps, key=lambda c: c[1])
    out = min(rate, cap)
    return (out, name) if out < rate else (rate, None)


def main(path):
    ticks = [json.loads(l) for l in open(path) if '"baseline_state"' in l]
    ticks.sort(key=lambda d: d.get("timestamp", 0))

    n = len(ticks)
    lot1_rows, bound = [], Counter()
    delivered_before = delivered_after = 0.0

    for d in ticks:
        b, o = d["baseline_state"], d["outcome"]
        adj = d.get("adjustments", {})
        dts = adj.get("dose_terminal_snapshot") or {}
        rate = o.get("target_basal_rate_uph") or 0.0
        bg = b.get("current_bg_mgdl")
        profile_basal = b.get("profile_basal_uph") or 0.0
        iob = b.get("iob_u") or 0.0
        eventual = dts.get("eventual_mgdl")
        nar = o.get("narrative") or ""

        m = PI_RE.search(nar)
        target = None
        rate_after_lot1 = rate
        if m:
            p_err, velocity, logged = _f(m.group(1)), _f(m.group(2)), _f(m.group(3))
            target = bg - p_err
            lm = legacy_mult(p_err, velocity)
            pm = projected_mult(bg, target, velocity, p_err)
            if lm > 0:
                rate_after_lot1 = rate * (pm / lm)
            lot1_rows.append((d["timestamp"], bg, target, p_err, velocity, logged, lm, pm, rate, rate_after_lot1))

        if target is None:
            # cible non déductible sans ligne PI : on ne peut pas évaluer below_target
            out, name = terminal_cap(rate_after_lot1, profile_basal, bg, bg, None, 0.0, iob, False, False)
        else:
            out, name = terminal_cap(
                rate_after_lot1, profile_basal, bg, target, eventual,
                0.0, iob, False, False,
            )
        if name:
            bound[name] += 1
        delivered_before += rate
        delivered_after += out

    print(f"=== REJEU {path.split('/')[-1]} — {n} ticks ===\n")

    print(f"--- LOT 1 : erreur projetée (H={HORIZON_MIN:.0f} min) ---")
    print(f"  ticks avec une décision PI traçable : {len(lot1_rows)} / {n}")
    if lot1_rows:
        worse = [r for r in lot1_rows if r[7] > r[6] + 1e-9]
        print(f"  ticks où le projeté est PLUS agressif que l'ancien : {len(worse)} (attendu : 0 en montée)")
        big = sorted(lot1_rows, key=lambda r: r[8] - r[9], reverse=True)[:8]
        print("  plus fortes réductions de TBR :")
        for ts, bg, tgt, p, v, logged, lm, pm, r0, r1 in big:
            if r0 - r1 < 0.01:
                continue
            print(f"    BG={bg:6.1f} cible={tgt:5.1f} v={v:+5.2f} | mult {lm:5.2f}x → {pm:5.2f}x"
                  f" | TBR {r0:5.2f} → {r1:5.2f} U/h")

    print(f"\n--- LOT 2 : invariants terminaux ---")
    if bound:
        for k, v in bound.most_common():
            print(f"  {k:<14} : {v} ticks bornés")
    else:
        print("  aucun tick borné")
    print(f"  (below_target n'est évalué que sur les ticks à cible déductible)")

    print(f"\n--- BILAN (approximation, cf. limites en tête de fichier) ---")
    print(f"  somme des TBR demandées : {delivered_before:8.1f} U/h·tick")
    print(f"  après lots 1+2          : {delivered_after:8.1f} U/h·tick")
    if delivered_before > 0:
        print(f"  réduction relative      : {100 * (1 - delivered_after / delivered_before):5.1f} %")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1])
