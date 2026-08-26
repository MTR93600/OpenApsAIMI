#!/usr/bin/env python3
"""Build the W6 M1 read-registry from the AIMI freeze tag.

Does not change medical code. Re-run:

  python3 _docs/kmp/tools/generate_m1_read_registry.py

Reads DetermineBasalAIMI2.kt from git tag aimi-baseline-2026-08-26.
Writes CSV files under _docs/kmp/generated/.
"""

from __future__ import annotations

import csv
import json
import re
import subprocess
from collections import Counter
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
TAG = "aimi-baseline-2026-08-26"
TICK = "plugins/aps/src/main/kotlin/app/aaps/plugins/aps/openAPSAIMI/DetermineBasalAIMI2.kt"
OUT = REPO / "_docs/kmp/generated"

# Annex 8 names that must stay ENGINE_STATE unless a later ADR says otherwise.
ENGINE_STATE_ANNEX8 = {
    "lastHypoBlockAt",
    "hypoClearCandidateSince",
    "lastBgRiseFastNightMs",
    "highBgOverrideUsed",
    "hyperDwellAboveHighBgSinceMs",
    "lastRiseFloorContributionMs",
    "riseFloorSpentU",
    "lastEffortMemory",
    "lastHypoBelow70At",
}

# Cross-tick memory that annex 8 did not list by name, but the freeze writes
# it to prefs or keeps it between ticks.
ENGINE_STATE_PERSIST = {
    "internalLastSmbMillis",
    "internalLastLegacyPrebolusMillis",
    "pendingLegacyPrebolusUnit",
    "pendingLegacyPrebolusExpiry",
    "lastSmbTimestampMem",
    "lastLegacyPrebolusTimestampMem",
    "pendingLegacyPrebolusUnitMem",
    "pendingLegacyPrebolusExpiryMem",
    "lastCarryRetryFireMillis",
    "zeroBasalAccumulatedMinutes",
    "slowCarbBudgetWindowMs",
    "slowCarbBudgetDeliveredU",
    "lastAutodriveState",
    "lastPostHypoOrdinal",
    "lastPhysioLatentState",
    "lastUamHypothesisState",
    "lastWCycleBelief",
    "lateFatRiseFlag",
}

# Annex 8: adaptiveMult is learner or computed. Do not pick for the reviewer.
LEARNER_OR_ENGINE = {
    "adaptiveMult": "Annex 8: learner state or computed value. Do not merge yet.",
}

WORKING_THIS_TICK = {
    "mealModeSmbReason",
    "consoleError",
    "consoleLog",
    "bgacc",
    "predictedSMB",
    "variableSensitivity",
    "eventualBG",
    "now",
    "iob",
    "cob",
    "predictedBg",
    "futureCarbs",
    "lastCarbAgeMin",
    "tags0to60minAgo",
    "tags60to120minAgo",
    "tags120to180minAgo",
    "tags180to240minAgo",
    "recentNotes",
    "bg",
    "delta",
    "shortAvgDelta",
    "longAvgDelta",
    "lastsmbtime",
    "acceleratingUp",
    "decceleratingUp",
    "acceleratingDown",
    "decceleratingDown",
    "stable",
    "hourOfDay",
    "weekend",
    "basalaimi",
    "endoSmbMult",
    "activityProtectionMode",
    "activityStateIntense",
    "aimilimit",
    "ci",
    "sleepTime",
    "sportTime",
    "aimiContextActivityActive",
    "exerciseInsulinLockoutActive",
    "exerciseHyperBasalOverrideActive",
    "tickCombinedDelta",
    "snackTime",
    "lowCarbTime",
    "highCarbTime",
    "mealTime",
    "bfastTime",
    "lunchTime",
    "dinnerTime",
    "fastingTime",
    "stopTime",
    "iscalibration",
    "mealruntime",
    "bfastruntime",
    "lunchruntime",
    "dinnerruntime",
    "highCarbrunTime",
    "snackrunTime",
    "intervalsmb",
    "peakintermediaire",
    "latestAdjustedDia",
    "insulinPeakTime",
    "iobActivityNow",
    "lastBolusAgeMinutes",
    "tickCobGrams",
    "correctionAggressionDecision",
    "smbTerminalSealed",
    "smbSealRefusedCount",
    "smbSealRefusedTotalU",
    "smbSealAllowedRaiseCount",
    "raNetCombinedDelta",
    "raNetShortAvgDeltaAdj",
    "mealAdvisorOneShotThisTick",
    "tickInsulinActionState",
    "tickEffectiveDiaHours",
    "tickEffectivePeakMinutes",
    "lastDecisionSource",
    "lastSafetySource",
    "lastPredictionAvailable",
    "lastPredictionSize",
    "lastEventualBgSnapshot",
    "lastSmbProposed",
    "lastSmbBindingTraceDraft",
    "lastSmbCapped",
    "lastSmbFinal",
    "duraISFminutes",
    "duraISFaverage",
    "pkpdThrottleIntervalAdd",
    "pkpdPreferTbrBoost",
    "currentThyroidEffects",
    "wCycleInfoForRun",
    "wCycleReasonLogged",
    "rbtResolvedThisTick",
    "tubeAppliedFromDoseSnapshotThisTick",
    "lastT3cHistoricalBypassNeutralizedThisTick",
    "pkpdAbsorptionGuardAppliedThisTick",
    "isConfirmedHighRiseThisTick",
    "criticalSafetyZeroedThisTick",
    "aimiDecisionExportedThisTick",
    "pendingDecisionCtxForExport",
    "currentTickDecisionEventId",
    "tickIobEffectiveU",
    "normalBgThreshold",
    "maxIob",
    "maxSMB",
    "maxSMBHB",
    "lastBolusSMBUnit",
}

# Member targetBg is AIMI targetBg, not schedule target_bg.
INPUT_COPIES = {
    "targetBg": "Member targetBg. Do not merge with schedule target_bg.",
    "averageBeatsPerMinute": "HR window copy. Missing must stay Missing, not 0.",
    "averageBeatsPerMinute10": "HR window copy.",
    "averageBeatsPerMinute60": "HR window copy.",
    "averageBeatsPerMinute180": "HR window copy.",
    "recentSteps5Minutes": "Steps window copy. Missing must stay Missing, not 0.",
    "recentSteps10Minutes": "Steps window copy.",
    "recentSteps15Minutes": "Steps window copy.",
    "recentSteps30Minutes": "Steps window copy.",
    "recentSteps60Minutes": "Steps window copy.",
    "recentSteps180Minutes": "Steps window copy.",
    "lastLoopCgmNoise": "CGM noise copy from this tick input.",
    "lastPatientSourceSensor": "CGM source id for this tick.",
    "lastProfile": "Effective profile copy. Also a cache if reused across ticks.",
    "tdd7DaysPerHour": "TDD derived input. Do not treat missing as 0 silently.",
    "tdd2DaysPerHour": "TDD derived input.",
    "tddPerHour": "TDD derived input.",
    "tdd24HrsPerHour": "TDD derived input.",
    "tir1DAYabove": "TIR derived input.",
    "currentTIRLow": "TIR derived input.",
    "currentTIRRange": "TIR derived input.",
    "currentTIRAbove": "TIR derived input.",
    "lastHourTIRLow": "TIR derived input.",
    "lastHourTIRLow100": "TIR derived input.",
    "lastHourTIRabove170": "TIR derived input.",
    "lastHourTIRabove120": "TIR derived input.",
}

TELEMETRY = {
    "csvPrimaryStorageDeniedLogged",
    "lastSafetyRiskExport",
    "lastScenarioProjection",
    "lastPredDivergenceExport",
    "lastIntelligenceSnapshot",
    "lastDoseTerminalSnapshot",
    "lastPkpdSoftFloorTelemetry",
    "lastBasalTerminalTelemetry",
    "lastAdaptiveBasalTrace",
    "lastAuditorTickDisposition",
    "lastAuditorLoopSnapshot",
    "lastAuditorAuditStartedAtMs",
    "lastIobSurveillanceExport",
    "lastIobReleaseExport",
    "lastTubeAdvisorTrace",
    "lastMaxSmbLadderBranch",
}

EFFECT = {
    "lastCycleNotificationDay": "UX anti-spam. Annex 8: platform state, not engine.",
}

CACHE = {
    "cachedPkpdRuntime",
    "cachedRiskEnvelopeEarly",
    "cachedRiskEnvelopeDecision",
    "cachedActivityContext",
    "cachedBasalFirstActive",
    "cachedIsFragileBg",
    "cachedPumpAgeDays",
    "cachedLastSmb",
    "cachedEffectiveProfile",
}

EFFECT.update(
    {
        "lastBasalLearnerHypoNotifyMs": "Learner notification anti-spam. Platform, not engine.",
        "lastBasalLearnerHyperNotifyMs": "Learner notification anti-spam. Platform, not engine.",
    }
)

# last* snapshots that are almost certainly this-tick working copies.
# Reviewer must still prove they are not read on the next tick before write.
WORKING_LAST_SNAPSHOT = {
    "lastDecisionPredictionAuthority",
    "lastPredictionAuthorityApplyResult",
    "lastAdvancedPredictionCurves",
    "lastSafetyTerminalsForRbt",
    "lastHyperTrajectoryRelease",
    "lastRecursiveBeliefSnapshot",
    "lastRecursiveAuthorityGateDecision",
    "lastT3cRuntimeOwnership",
    "lastRbtChaosEvaluation",
    "lastRbtAppliedHints",
    "lastRbtLiveCommitResult",
    "lastLoadGovernorMultiplierG",
    "lastPhysiologicalPhaseOutput",
    "lastPhysiologicalPatternSnapshot",
    "lastMealAbsorptionOutput",
    "lastInsulinStackingEvaluation",
    "lastBasePhysioMultipliers",
    "lastFusedPhysioMultipliers",
    "lastScenarioBestCappedForPhysio",
    "pendingTrajSpiralBasal",
    "tubeDoseBaseline",
    "lastPostHypoDeliveryAuthority",
    "lastHtrRaFloorMgdlPerMin",
    "lastSlopeFromMinDeviation",
    "lastPostHypoSmbBeforeCapU",
    "lastPostHypoSmbAfterCapU",
    "lastEffortSmbFactorRaw",
    "lastEffortSmbFactorApplied",
    "lastEffortSmbBeforeU",
    "lastEffortSmbAfterU",
    "lastTubeAdvisorSmbCapScale",
    "lastInflammationResult",
    "lastEffortAssessment",
    "lastContextSmbCeilingU",
    "lastContextSuppressSmb",
    "lastContextSnapshot",
    "lastPatientState",
    "lastPatientModeDecision",
    "lastPhysiologicalTreeSnapshot",
    "lastNgrBasalMultiplier",
    "lastHarmoniaDecision",
    "lastMealCertainty",
    "lastHarmonizerOutcome",
    "lastHarmoniaProductionDecision",
    "mealAbsorptionDeltaPrevForTick",
    "mealAbsorptionDeltaPrevLatched",
    "raEstimatorRunCountAtTickStart",
    "basalChannelGuardBlockedT3cCount",
    "basalChannelGuardBlockedHarmoniaCount",
    "iobNet",
}

STATEFUL_VAL_HINTS = (
    "Atomic",
    "mutable",
    "lazy",
    "CoroutineScope",
    "HashMap",
    "PatternCapHold",
    "DetermineBasalInvocationCaches",
    "NightGrowthResistanceLearner",
    "RealTimeInsulinObserver",
    "EndometriosisAdjuster",
    "InflammationAdjuster",
    "ThyroidPreferences",
    "AimiHormonitorStudyExporter",
    "PkPdIntegration",
)


def git_show(rel: str) -> str:
    return subprocess.check_output(["git", "-C", str(REPO), "show", f"{TAG}:{rel}"], text=True)


def git_rev() -> str:
    return subprocess.check_output(
        ["git", "-C", str(REPO), "rev-parse", "--verify", f"{TAG}^{{commit}}"],
        text=True,
    ).strip()


def classify_var(name: str, decl: str) -> tuple[str, str, str, str]:
    """Return class, confidence, persist_policy, note."""
    if name in LEARNER_OR_ENGINE:
        return "LEARNER_STATE", "ANNEX8_OPEN", "unknown", LEARNER_OR_ENGINE[name]
    if name in ENGINE_STATE_ANNEX8:
        return "ENGINE_STATE", "ANNEX8", "unknown", "Annex 8 cross-tick memory."
    if name in ENGINE_STATE_PERSIST:
        persist = "persist" if name in {
            "internalLastSmbMillis",
            "internalLastLegacyPrebolusMillis",
            "pendingLegacyPrebolusUnit",
            "pendingLegacyPrebolusExpiry",
        } else "unknown"
        return "ENGINE_STATE", "HEURISTIC", persist, "Cross-tick memory on freeze. CORE must confirm."
    if name in EFFECT:
        return "EFFECT", "ANNEX8", "reset", EFFECT[name]
    if name in TELEMETRY:
        return "TELEMETRY", "HEURISTIC", "n/a", "Trace / export of this tick."
    if name in CACHE:
        return "CACHE", "HEURISTIC", "rebuild", "Acquisition or tick cache. Do not move into the KMP engine."
    if name in INPUT_COPIES:
        return "INPUT", "HEURISTIC", "n/a", INPUT_COPIES[name]
    if "ThisTick" in name:
        return "WORKING", "HEURISTIC", "reset", "Name says this tick only."
    if name in WORKING_THIS_TICK:
        return "WORKING", "HEURISTIC", "reset", "Annex 8 scratch examples or this-tick copy."
    if name in WORKING_LAST_SNAPSHOT:
        return "WORKING", "NEEDS_REVIEW", "reset", (
            "Looks like this-tick scratch (last* snapshot). "
            "CORE must prove it is not read on the next tick before write."
        )
    if "targetBg" == name:
        return "INPUT", "ANNEX8", "n/a", "Member targetBg. Not schedule target_bg."
    return "UNASSIGNED", "NEEDS_REVIEW", "unknown", "No first-pass class. Do not guess."


def parse_private_vars(src: str) -> list[dict]:
    rows = []
    for i, line in enumerate(src.splitlines(), 1):
        m = re.search(r"^(\s*)(?:@Volatile\s+)?private var\s+([A-Za-z_][A-Za-z0-9_]*)", line)
        if not m:
            continue
        name = m.group(2)
        klass, conf, persist, note = classify_var(name, line)
        rows.append(
            {
                "line": i,
                "indent": len(m.group(1)),
                "name": name,
                "declaration": line.strip(),
                "class": klass,
                "confidence": conf,
                "persist_policy": persist,
                "note": note,
            }
        )
    return rows


def parse_stateful_vals(src: str) -> list[dict]:
    rows = []
    pending_inject = False
    for i, line in enumerate(src.splitlines(), 1):
        stripped = line.strip()
        if stripped == "@Inject":
            pending_inject = True
            continue
        m = re.search(r"^(\s*)(?:@Inject\s+)?(?:lateinit\s+)?(?:private\s+)?val\s+([A-Za-z_][A-Za-z0-9_]*)", line)
        if not m:
            pending_inject = False
            continue
        if not stripped.startswith("private val") and "@Inject" not in stripped and not pending_inject:
            pending_inject = False
            continue
        if "private val" not in stripped and not pending_inject and "@Inject" not in stripped:
            pending_inject = False
            continue
        if "private val" not in stripped:
            pending_inject = False
            continue
        name = m.group(2)
        hint = any(h in stripped for h in STATEFUL_VAL_HINTS)
        klass = "UNASSIGNED"
        note = ""
        if "Atomic" in stripped or "InvocationCaches" in stripped or "SnapshotRef" in name or name.endswith("Ref"):
            klass = "CACHE"
            note = "Acquisition cache. Snapshot the value; do not port the Atomic into KMP."
        elif "PatternCapHold" in stripped or name == "patternCapHold":
            klass = "ENGINE_STATE"
            note = "Annex 8 PatternCapHold."
        elif "lazy" in stripped and any(x in stripped.lower() for x in ("csv", "dir", "file", "exporter")):
            klass = "TELEMETRY"
            note = "File / export handle."
        elif "Learner" in stripped or "Observer" in stripped:
            klass = "LEARNER_STATE"
            note = "Learner / observer owned by the class."
        elif hint:
            klass = "CACHE" if "Cache" in stripped or "Atomic" in stripped else "UNASSIGNED"
            note = "Looks stateful. CORE must assign class."
        rows.append(
            {
                "line": i,
                "name": name,
                "declaration": stripped[:240],
                "stateful_hint": "yes" if hint else "no",
                "class": klass,
                "note": note,
            }
        )
        pending_inject = False
    return rows


def parse_config_keys(src: str) -> list[dict]:
    refs = re.findall(
        r"\b((?:Aimi)?(?:Boolean|Int|Long|Double|UnitDouble|String|StringNotSensitive)Key)\.([A-Za-z0-9_]+)",
        src,
    )
    counts = Counter(f"{a}.{b}" for a, b in refs)
    rows = []
    for key, n in sorted(counts.items()):
        kind = key.split(".", 1)[0]
        name = key.split(".", 1)[1]
        persist = "persist" if kind in {"AimiLongKey"} or name in {
            "OApsAIMILastEstimatedCarbs",
            "OApsAIMILastEstimatedCarbTime",
            "OApsAIMIMealAdvisorTrigger",
        } else "config"
        note = "Tick config key. Must appear in AimiConfigSnapshot later (M1.7)."
        if persist == "persist":
            note = "Written during the tick. This is engine/effect state stored in prefs, not only config."
        rows.append({"key": key, "kind": kind, "name": name, "ref_count": n, "role": persist, "note": note})
    return rows


def parse_injects(src: str) -> list[dict]:
    rows = []
    lines = src.splitlines()
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if stripped == "@Inject" and i + 1 < len(lines) and "lateinit var" in lines[i + 1]:
            nxt = lines[i + 1].strip()
            m = re.search(r"lateinit var\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+)$", nxt)
            if m:
                rows.append({"line": i + 2, "name": m.group(1), "type": m.group(2).rstrip(","), "source": "field_inject"})
            i += 2
            continue
        m = re.search(r"@Inject\s+lateinit var\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+)$", stripped)
        if m:
            rows.append({"line": i + 1, "name": m.group(1), "type": m.group(2).rstrip(","), "source": "field_inject"})
        m = re.search(r"private val\s+([A-Za-z_][A-Za-z0-9_]*)\s*:\s*(.+),?\s*$", stripped)
        if m and 1277 <= i + 1 <= 1292:
            rows.append({"line": i + 1, "name": m.group(1), "type": m.group(2).rstrip(","), "source": "constructor"})
        i += 1
    return rows


SERVICE_TOKENS = [
    "persistenceLayer",
    "tddCalculator",
    "tirCalculator",
    "physioAdapter",
    "iobCobCalculator",
    "profileFunction",
    "activePlugin",
    "storageHelper",
    "dateUtil",
    "aapsLogger",
    "aimiLogger",
    "activityManager",
    "glucoseStatusCalculatorAimi",
    "autodriveEngine",
    "autodriveGater",
    "auditorOrchestrator",
    "tpoOrchestrator",
    "contextManager",
    "contextInfluenceEngine",
    "basalLearner",
    "unifiedReactivityLearner",
    "basalNeuralLearner",
    "basalMlTrainingCoordinator",
    "basalDecisionEngine",
    "trajectoryGuard",
    "trajectoryHistoryProvider",
    "straightLineTubeAdvisor",
    "sensitivityRatioEstimator",
    "continuousStateEstimator",
    "notificationManager",
    "uiInteraction",
    "wCycleFacade",
    "wCycleLearner",
    "wCyclePreferences",
    "gestationalAutopilot",
    "pumpCapabilityValidator",
    "dynamicBasalController",
    "insulinObserver",
    "pkpdIntegration",
    "determineIoScope",
    "hormonitorStudyExporter",
    "comparator",
]


def parse_service_reads(src: str) -> list[dict]:
    rows = []
    lines = src.splitlines()
    for tok in SERVICE_TOKENS:
        hits = [i for i, line in enumerate(lines, 1) if re.search(rf"\b{tok}\b", line)]
        sample = "; ".join(f"L{n}" for n in hits[:12])
        if len(hits) > 12:
            sample += f"; +{len(hits) - 12} more"
        dest = "AimiInputSnapshot or cache"
        if tok in {"aapsLogger", "aimiLogger", "hormonitorStudyExporter"}:
            dest = "AimiDecisionTrace"
        elif tok in {"notificationManager", "uiInteraction"}:
            dest = "EFFECT events"
        elif tok in {
            "basalLearner",
            "unifiedReactivityLearner",
            "basalNeuralLearner",
            "basalMlTrainingCoordinator",
            "wCycleLearner",
        }:
            dest = "AimiModelBundle / learner stores"
        elif tok in {"auditorOrchestrator", "tpoOrchestrator"}:
            dest = "next-tick advice, not mutate tick N"
        elif tok == "determineIoScope":
            dest = "forbidden inside evaluate(); capture before tick"
        elif tok == "storageHelper":
            dest = "TELEMETRY / files. Engine must not open files."
        rows.append(
            {
                "token": tok,
                "hits": len(hits),
                "destination": dest,
                "sample_lines": sample,
            }
        )
    # persistenceLayer methods
    methods = []
    for i, line in enumerate(lines, 1):
        m = re.search(r"persistenceLayer\.([A-Za-z_][A-Za-z0-9_]*)", line)
        if m:
            methods.append((i, m.group(1), line.strip()[:160]))
    for line_no, method, snippet in methods:
        rows.append(
            {
                "token": f"persistenceLayer.{method}",
                "hits": 1,
                "destination": "AimiInputSnapshot / history",
                "sample_lines": f"L{line_no}: {snippet}",
            }
        )
    return rows


def write_csv(path: Path, rows: list[dict], fieldnames: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        w.writeheader()
        for row in rows:
            w.writerow(row)


def main() -> None:
    src = git_show(TICK)
    sha = git_rev()
    vars_rows = parse_private_vars(src)
    val_rows = parse_stateful_vals(src)
    keys = parse_config_keys(src)
    injects = parse_injects(src)
    services = parse_service_reads(src)

    class_counts = Counter(r["class"] for r in vars_rows)
    conf_counts = Counter(r["confidence"] for r in vars_rows)

    write_csv(
        OUT / "m1-private-var-registry.csv",
        vars_rows,
        ["line", "indent", "name", "class", "confidence", "persist_policy", "note", "declaration"],
    )
    write_csv(
        OUT / "m1-private-val-stateful.csv",
        val_rows,
        ["line", "name", "class", "stateful_hint", "note", "declaration"],
    )
    write_csv(
        OUT / "m1-config-keys.csv",
        keys,
        ["key", "kind", "name", "ref_count", "role", "note"],
    )
    write_csv(
        OUT / "m1-injected-services.csv",
        injects,
        ["line", "source", "name", "type"],
    )
    write_csv(
        OUT / "m1-service-reads.csv",
        services,
        ["token", "hits", "destination", "sample_lines"],
    )

    meta = {
        "freeze_tag": TAG,
        "freeze_sha": sha,
        "source": TICK,
        "private_var_count": len(vars_rows),
        "annex8_private_var_count": 239,
        "unique_config_keys": len(keys),
        "private_var_class_counts": dict(class_counts),
        "private_var_confidence_counts": dict(conf_counts),
        "token_counts": {
            "preferences": len(re.findall(r"\bpreferences\b", src)),
            "persistenceLayer": len(re.findall(r"\bpersistenceLayer\b", src)),
            "tddCalculator": len(re.findall(r"\btddCalculator\b", src)),
            "tirCalculator": len(re.findall(r"\btirCalculator\b", src)),
            "physioAdapter": len(re.findall(r"\bphysioAdapter\b", src)),
        },
        "unassigned_private_var": [r["name"] for r in vars_rows if r["class"] == "UNASSIGNED"],
        "needs_review_private_var": [r["name"] for r in vars_rows if r["confidence"] == "NEEDS_REVIEW"],
    }
    (OUT / "m1-registry-meta.json").write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: meta[k] for k in (
        "freeze_sha", "private_var_count", "unique_config_keys",
        "private_var_class_counts", "private_var_confidence_counts",
    )}, indent=2))


if __name__ == "__main__":
    main()
