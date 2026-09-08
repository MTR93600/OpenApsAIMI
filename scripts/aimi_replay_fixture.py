#!/usr/bin/env python3
"""Project an AIMI support package into a replay fixture.

A full ``AIMI_Decisions_Last24h.jsonl`` is 8-13 MB per day, dominated by blocks the replay harness
never reads (``recursive_belief``, ``physiological_tree``, the free-text narrative). This script
keeps only the harness input contract and writes one flat JSON object per line, which brings a day
down to roughly 150 KB.

It also drops everything identifying: the narrative (which carries local file paths), and every
block outside the allow-list below. The ``Diagnostic_Report.txt`` of a package is never read.

Usage:
    python3 scripts/aimi_replay_fixture.py <package>/AIMI_Decisions_Last24h.jsonl <out.jsonl>
    python3 scripts/aimi_replay_fixture.py --barrier [--max N] <package>/...jsonl <out.jsonl>

The default (day) mode keeps a whole day and keeps the absolute timestamp, because a day fixture is
already the maintainer's own contributed data and the harness sorts on it.

``--barrier`` writes the *barrier* fixture instead. It keeps only the ticks that carry an
``adjustments.control_barrier`` block (about a third of a day), keeps **only numeric and boolean
fields**, and replaces the absolute timestamp by an offset from the first kept tick. No date, no
time of day, no event id, no free text. That fixture is meant to be read by the barrier replay,
which never needs to know when a tick happened.

``--max N`` subsamples to about N ticks, round-robin over the strata that matter for the barrier
(gamma branch, full suspension, whether the barrier intervened at all), so a small fixture still
contains suspended ticks, passing ticks, accelerated ticks and relaxed ticks.

Bundled fixtures live in ``plugins/aps/src/test/resources/replay/``. A larger private corpus can be
kept outside the repository and pointed at with the ``AIMI_REPLAY_CORPUS`` environment variable;
see ``docs/adr/0001-replay-harness.md``.
"""
import json,os,sys
def g(d,*path):
    cur=d
    for p in path:
        if not isinstance(cur,dict): return None
        cur=cur.get(p)
    return cur
def flat(r):
    b=r["baseline_state"]; a=r.get("adjustments",{}) or {}; o=r.get("outcome") or {}
    st=a.get("smb_binding_trace") or {}; rq=a.get("replay_quality") or {}
    bt=a.get("basal_terminal") or {}; sr=a.get("safety_risk") or {}
    phd=a.get("post_hypo_delivery") or {}
    d={
      "t":r["timestamp"],"trig":r.get("trigger"),
      "bg":b.get("current_bg_mgdl"),"iob":b.get("iob_u"),"cob":b.get("cob_g"),
      "pisf":b.get("profile_isf_mgdl"),"pbasal":b.get("profile_basal_uph"),
      "sisf":b.get("profile_isf_static_mgdl"),"cisf":b.get("command_isf_mgdl"),
      "isrc":b.get("isf_source"),"iage":b.get("isf_age_ms"),
      "ikey":b.get("isf_cache_key"),"iglu":b.get("isf_cache_glucose_mgdl"),
      "ikal":b.get("isf_kalman_fast_mgdl"),"iadj":b.get("isf_adj_engine_mgdl"),
      "islow":b.get("isf_fused_slow_mgdl"),"itrust":b.get("isf_trust_fast"),
      "idyn":b.get("isf_dynamic_factor"),"itraj":b.get("isf_trajectory_multiplier"),
      "ra":b.get("estimated_ra_mgdl_per_min"),
      "phf":b.get("physio_isf_factor"),
      "shadow":b.get("isf_profile_relative_shadow_mgdl"),"shadowhit":b.get("isf_profile_relative_bound_hit"),
      "ratioR":b.get("sensitivity_ratio_r"),"shadowS":b.get("isf_shadow_s_mgdl"),
      "nobs":b.get("sensitivity_observations"),
      "htrfloor":b.get("htr_ra_floor_mgdl_per_min"),
      "raused":b.get("estimated_ra_used_mgdl_per_min"),
      "raadv":b.get("ra_estimator_advances"),"rarep":b.get("ra_estimator_replayed_calls"),
      "rashadow":b.get("ra_aligned_tau_shadow_mgdl_per_min"),
      "cbfc":b.get("cbf_coefficient_used"),"cbfcunf":b.get("cbf_coefficient_unfloored"),
      "cbfu":b.get("cbf_permitted_u"),"cbfuunf":b.get("cbf_permitted_unfloored_u"),
      "cbfisf":b.get("cbf_profile_isf_mgdl"),
      "sealref":b.get("smb_seal_refused_count"),"sealrefu":b.get("smb_seal_refused_total_u"),
      "sealok":b.get("smb_seal_allowed_raise_count"),
      "dec":o.get("decision"),"amt":o.get("amount"),"basal":o.get("target_basal_rate_uph"),
      "owner":st.get("origin_owner"),"fowner":st.get("final_owner"),
      "maxsmb":st.get("max_smb_u"),"iobhead":st.get("iob_headroom_u"),
      "tier":rq.get("correction_aggression_tier"),"safety":rq.get("safety_source"),
      "phguard":rq.get("post_hypo_guard_state"),
      "pmode":g(a,"patient_mode","mode"),
      "tgt":bt.get("target_bg_mgdl"),"mealmode":bt.get("meal_mode_active"),
      "posthypo":bt.get("post_hypo_active"),
      "sgate":sr.get("safety_gate"),"halt":sr.get("halt_remaining_pipeline"),
      "uam":g(a,"uam_hypotheses","dominant"),
      "absorb":g(a,"meal_absorption_phase","phase"),
      "phase":g(a,"physiological_phase","phase"),
      "disf":g(a,"dynamic_isf","final_value_mgdl"),
      "ev":g(a,"dose_terminal_snapshot","eventual_mgdl"),
      "minpred":g(a,"dose_terminal_snapshot","min_pred_mgdl"),
      "phd_active":phd.get("active"),"phd_reason":phd.get("reason_tag"),
      "phd_before":phd.get("smb_before_cap_u"),"phd_after":phd.get("smb_after_cap_u"),
    }
    d.update(barrier(r))
    return {k:v for k,v in d.items() if v is not None}

def barrier(r):
    """The fields ``ControlBarrierShield.enforce`` needs to be replayed on this tick.

    Absent on the two thirds of ticks where the barrier did not run: the whole ``control_barrier``
    block is missing then, and every key below is dropped by the caller. A missing key must read as
    "unknown" on the Kotlin side, never as zero.
    """
    b=r["baseline_state"]; a=r.get("adjustments") or {}
    cb=a.get("control_barrier")
    bt=a.get("basal_terminal") or {}; sr=a.get("safety_risk") or {}
    env=g(a,"harmonia_simulation","environment") or {}
    ios=a.get("iob_surveillance") or {}
    delta5=bt.get("delta_mgdl_5m")
    if delta5 is None: delta5=ios.get("delta_mgdl_5m")
    d={
      # Baseline witnesses of the barrier. Present whenever the engine ran, block or not.
      "cbfc":b.get("cbf_coefficient_used"),"cbfcunf":b.get("cbf_coefficient_unfloored"),
      "cbfu":b.get("cbf_permitted_u"),"cbfuunf":b.get("cbf_permitted_unfloored_u"),
      "cbfisf":b.get("cbf_profile_isf_mgdl"),
      # Inputs the barrier itself does not export but the replay needs.
      "maxiob":env.get("max_iob_u") if env.get("max_iob_u") is not None else ios.get("max_iob_u"),
      "vel":(delta5/5.0) if isinstance(delta5,(int,float)) else None,
      "lgs":sr.get("hypo_threshold_mgdl"),
    }
    if isinstance(cb,dict):
        d.update({
          "cb_h":cb.get("h_mgdl"),"cb_lfh":cb.get("lfh_mgdl_per_min"),
          "cb_lgh":cb.get("lgh_mgdl_per_u_per_min"),"cb_ins":cb.get("insulin_term_mgdl_per_min"),
          "cb_gam":cb.get("active_gamma"),"cb_bnd":cb.get("safety_boundary"),
          "cb_evo":cb.get("system_evolution"),"cb_si":cb.get("si_metabolic"),
          "cb_safeu":cb.get("safe_u"),"cb_susp":cb.get("fully_suspended"),
          "cb_anch":cb.get("anchor_is_dynamic_isf"),
          "cb_rsmb":cb.get("mpc_raw_smb_u"),"cb_rtbr":cb.get("mpc_raw_tbr_uph"),
        })
    return d

# Keys the barrier fixture is allowed to carry. Numbers and booleans only: no timestamp, no event
# id, no trigger name, no decision text. Everything here is either a physiological quantity or a
# term the barrier computed from one.
BARRIER_KEYS=(
  "cb_h","cb_lfh","cb_lgh","cb_ins","cb_gam","cb_bnd","cb_evo","cb_si","cb_safeu","cb_susp",
  "cb_anch","cb_rsmb","cb_rtbr",
  "cbfc","cbfcunf","cbfu","cbfuunf","cbfisf","maxiob","vel","lgs",
  "bg","iob","cob","pbasal","pisf","sisf","cisf",
)

def barrier_row(r,first_ts):
    d={k:v for k,v in barrier(r).items() if v is not None}
    b=r["baseline_state"]
    for key,src_key in (("bg","current_bg_mgdl"),("iob","iob_u"),("cob","cob_g"),
                        ("pbasal","profile_basal_uph"),("pisf","profile_isf_mgdl"),
                        ("sisf","profile_isf_static_mgdl"),("cisf","command_isf_mgdl")):
        v=b.get(src_key)
        if v is not None: d[key]=v
    offset_ms=r["timestamp"]-first_ts
    out={"t":offset_ms,"tmin":round(offset_ms/60000.0,3)}
    for k in BARRIER_KEYS:
        if k in d: out[k]=d[k]
    return out

def strata(r):
    """The axes a barrier fixture must cover to be worth replaying.

    ``requested`` matters as much as the rest: a tick where the controller asked for nothing is
    suspended by arithmetic, not by the barrier, and a fixture made only of those cannot show what
    a wider barrier would have let through.
    """
    cb=(r.get("adjustments") or {}).get("control_barrier") or {}
    requested=(cb.get("mpc_raw_smb_u") or 0.0)+(cb.get("mpc_raw_tbr_uph") or 0.0)/12.0
    return (round(cb.get("active_gamma") or 0.0,4),bool(cb.get("fully_suspended")),
            cb.get("safe_u") is None,requested>0.0)

def subsample(recs,limit):
    """Round-robin over strata, keeping order inside each one."""
    if limit is None or len(recs)<=limit: return recs
    groups={}
    for r in recs: groups.setdefault(strata(r),[]).append(r)
    order=sorted(groups)
    picked=[]; i=0
    while len(picked)<limit and any(groups[k] for k in order):
        k=order[i%len(order)]
        if groups[k]: picked.append(groups[k].pop(0))
        i+=1
    picked.sort(key=lambda x:x["timestamp"])
    return picked

args=[a for a in sys.argv[1:]]
barrier_mode="--barrier" in args
if barrier_mode: args.remove("--barrier")
limit=None
if "--max" in args:
    i=args.index("--max"); limit=int(args[i+1]); del args[i:i+2]
src,dst=args[0],args[1]
recs=[json.loads(l) for l in open(src,encoding="utf-8",errors="replace") if l.strip()]
recs=[x for x in recs if "baseline_state" in x]
recs.sort(key=lambda x:x["timestamp"])
if barrier_mode:
    recs=[x for x in recs if isinstance((x.get("adjustments") or {}).get("control_barrier"),dict)]
    recs=subsample(recs,limit)
    first_ts=recs[0]["timestamp"] if recs else 0
    rows=[barrier_row(r,first_ts) for r in recs]
else:
    rows=[flat(r) for r in recs]
with open(dst,"w",encoding="utf-8") as fh:
    for row in rows: fh.write(json.dumps(row,separators=(",",":"),sort_keys=True)+"\n")
print(f"{os.path.basename(dst)}: {len(rows)} ticks, {os.path.getsize(dst)/1024:.0f} Ko")
