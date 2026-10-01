"""Fixed experiment plans and observed client-configuration checks."""
import re

EXPERIMENTS = ("pool-abba", "preallocation-abba")


def experiment_plan(name="pool-abba"):
    if name not in EXPERIMENTS:
        raise ValueError("Unknown profiling experiment")
    pool_axis = name == "pool-abba"
    pools = (2, 16, 16, 2) if pool_axis else (16, 16, 16, 16)
    initial = (40, 40, 40, 40) if pool_axis else (40, 80, 80, 40)
    variants = [{"label": f"{i:02d}-" + (f"pool{pool}" if pool_axis else f"prealloc{vus}"),
                 "pool": pool, "preallocatedVUs": vus, "maxVUs": 80}
                for i, (pool, vus) in enumerate(zip(pools, initial), 1)]
    return {"name": name, "axis": "pool" if pool_axis else "preallocatedVUs",
            "control": 2 if pool_axis else 40, "intervention": 16 if pool_axis else 80,
            "variants": variants}


def expected_configuration(rate, duration, label, preallocated=40):
    if preallocated not in (40, 80):
        raise ValueError("Preallocation must be one of the fixed experiment levels")
    return {"authMode": "local-demo", "seed": 42, "ratePerSecond": rate,
            "durationSeconds": duration, "preAllocatedVUs": preallocated, "maxVUs": 80,
            "mix": "deterministic seeded approximately 75% browse / 25% bid",
            "comparisonLabel": label, "dataset": "one newly created auction"}


def configuration_check(summary, expected):
    observed = summary.get("configuration", {})
    if not isinstance(observed, dict):
        observed = {}
    errors = [key for key, value in expected.items()
              if type(observed.get(key)) is not type(value) or observed.get(key) != value]
    if summary.get("nominalScheduledTarget") != expected["ratePerSecond"] * expected["durationSeconds"]:
        errors.append("nominalScheduledTarget")
    return {"verified": not errors, "mismatchedFields": errors,
            "expected": expected, "observed": {key: observed.get(key) for key in expected}}


def allocation_progress(text, preallocated, maximum, dropped):
    # CLI progress is a sparse observation, not a per-drop event timeline. Global
    # elapsed time includes setup; the scenario clock below does not.
    pattern = re.compile(r"running \((\d+)m([\d.]+)s\), (\d+)/(\d+) VUs, (\d+) complete[^\r\n]*[\r\n]+auction\s+\[[^\]]*\] [^\r\n]*?([\d.]+)s/([\d.]+)s")
    samples = []
    errors = []
    for match in pattern.finditer(text):
        minute, second, active, allocated, complete, elapsed, duration = match.groups()
        row = {"globalElapsedSeconds": int(minute) * 60 + float(second),
               "scenarioElapsedSeconds": float(elapsed), "scenarioDurationSeconds": float(duration),
               "activeVUs": int(active), "allocatedVUs": int(allocated), "completed": int(complete)}
        if not 0 <= row["activeVUs"] <= row["allocatedVUs"] <= maximum or row["allocatedVUs"] < preallocated:
            errors.append("VU counts outside requested bounds")
        if samples and any(row[key] < samples[-1][key] for key in ("globalElapsedSeconds", "scenarioElapsedSeconds", "allocatedVUs", "completed")):
            errors.append("Progress counters moved backwards")
        samples.append(row)
    peak = max((row["allocatedVUs"] for row in samples), default=None)
    return {"available": bool(samples), "valid": bool(samples) and not errors,
            "errors": sorted(set(errors)), "samples": samples, "sampleCount": len(samples),
            "maxObservedActiveVUs": max((row["activeVUs"] for row in samples), default=None),
            "maxObservedAllocatedVUs": peak,
            "additionalObservedVUs": None if peak is None else peak - preallocated,
            "additionalObservedVUsEqualDropped": None if peak is None else peak - preallocated == dropped,
            "interpretation": "Sparse terminal snapshots; setup is included in global elapsed time. Equal allocation growth and drops is descriptive, not proof of the cause of unavailable VUs. Absence of snapshots is not a zero count."}


def campaign_design_errors(runs, plan):
    errors = []
    if len(runs) > len(plan["variants"]):
        errors.append("More runs than the fixed plan")
    for actual, expected in zip(runs, plan["variants"]):
        for key, value in expected.items():
            if type(actual.get(key)) is not type(value) or actual.get(key) != value:
                errors.append(f"{expected['label']}: unexpected {key}")
        if actual.get("experiment") != plan["name"]:
            errors.append(f"{expected['label']}: unexpected experiment")
        for phase, rate, duration, initial in (("warmup", 50, 15, 40), ("measurement", 100, 45, expected["preallocatedVUs"])):
            suffix = "warmup" if phase == "warmup" else "measured"
            requested = expected_configuration(rate, duration, expected["label"] + "-" + suffix, initial)
            check = actual.get(phase, {}).get("configuration", {})
            if check.get("verified") is not True or check.get("expected") != requested or check.get("observed") != requested:
                errors.append(f"{expected['label']}: unverified {phase} configuration")
    hashes = [item.get("appSHA256", "") for item in runs]
    if any(not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest) for digest in hashes) or len(set(hashes)) > 1:
        errors.append("Application artifact missing or changed within campaign")
    return errors
