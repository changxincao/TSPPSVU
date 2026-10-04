"""Audit old/new paired main-grid outputs; retain all five markets, no result selection."""
import csv
import json
import math
import statistics
import sys
from datetime import datetime, timezone
from pathlib import Path

METRICS = ("mean", "sd", "q95", "cvar95", "maximum")
CSAA = ("CSAA-Exp", "CSAA-Gau", "CSAA-Epa", "CSAA-Tri", "RF-CSAA")


def rows(path):
    with path.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def metadata(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8-sig").splitlines() if "=" in line)


def write(path, data):
    with path.open("w", encoding="utf-8", newline="") as f:
        out = csv.DictWriter(f, fieldnames=list(data[0]))
        out.writeheader()
        out.writerows(data)


def main(baseline, output):
    target = output / "comparison"
    target.mkdir(exist_ok=True)
    results, changes, audit = [], [], []
    for rep in range(1, 6):
        name = f"rep_{rep:03}"
        saa = baseline / "experiment1" / name / "SAA-All"
        reference = []
        for q in range(40):
            root = saa / "queries" / f"query_{q:03}"
            draws = rows(root / "oos/draws.csv")
            if len(draws) != 1000:
                raise ValueError(f"Incomplete SAA OOS: {root}")
            reference.append((metadata(root / "query_metadata.txt")["querySha256"],
                              [(r["sample_id"], r["total_demand"]) for r in draws]))
        for method in ("D", "SAA-All") + CSAA + ("C-Chi2",):
            directory = (baseline / "experiment1" / name / method if method in ("D", "SAA-All")
                         else output / "experiment2_rf" / name if method == "C-Chi2"
                         else output / "experiment1" / name / method)
            if not (directory / "complete.txt").is_file():
                raise ValueError(f"Incomplete task excluded, not counted as zero: {directory}")
            summaries = []
            for q in range(40):
                root = directory / "queries" / f"query_{q:03}"
                robust = method == "C-Chi2"
                summary = rows(root / ("oos/experiment2_summary.csv" if robust else "oos/summary.csv"))
                if len(summary) != 1 or summary[0]["method"] != method:
                    raise ValueError(f"Unexpected method/summary: {root}")
                draws = rows(root / ("oos/experiment2_draws.csv" if robust else "oos/draws.csv"))
                paired = (metadata(root / "query_metadata.txt")["querySha256"],
                          [(r["sample_id"], r["total_demand"]) for r in draws])
                if paired != reference[q]:
                    raise ValueError(f"Unpaired query or OOS: {root}")
                if not all(math.isfinite(float(summary[0][k])) for k in METRICS):
                    raise ValueError(f"Non-finite metric: {root}")
                summaries.append(summary[0])
                audit.append(dict(rep=rep, method=method, query=q,
                                  status=summary[0]["solve_status"],
                                  certified_optimal=summary[0]["certified_optimal"],
                                  gap=summary[0]["solve_gap"], paired=True, oos_count=len(draws)))
            result = dict(rep=rep, method=method, **{k: statistics.mean(float(r[k]) for r in summaries) for k in METRICS})
            results.append(result)
            if method in ("CSAA-Exp", "CSAA-Tri", "RF-CSAA"):
                old_dir = baseline / "experiment1" / name / method
                old = rows(old_dir / "queries/query_000/validation/context_candidate.csv")[0]
                new = rows(directory / "queries/query_000/validation/context_candidate.csv")[0]
                parameter = "validation_selected_min_leaf" if method == "RF-CSAA" else "validation_selected_B"
                old_mean = statistics.mean(float(rows(old_dir / "queries" / f"query_{q:03}" / "oos/summary.csv")[0]["mean"]) for q in range(40))
                changes.append(dict(rep=rep, method=method, old_parameter=old[parameter],
                                    new_parameter=new[parameter], changed=float(old[parameter]) != float(new[parameter]),
                                    old_mean=old_mean, new_mean=result["mean"],
                                    improvement_pct=100 * (old_mean - result["mean"]) / old_mean))
    for result in results:
        base = next(r for r in results if r["rep"] == result["rep"] and r["method"] == "SAA-All")
        for k in METRICS:
            result[k + "_vs_saa_pct"] = 100 * (base[k] - result[k]) / base[k]
    overall = [dict(method=method, **{k + "_vs_saa_pct": statistics.mean(r[k + "_vs_saa_pct"] for r in results if r["method"] == method) for k in METRICS})
               for method in ("D", "SAA-All") + CSAA + ("C-Chi2",)]
    rankings = [dict(rep=rep, mean_ranking=";".join(r["method"] for r in sorted(
                    (r for r in results if r["rep"] == rep and r["method"] in CSAA), key=lambda r: r["mean"]))) for rep in range(1, 6)]
    write(target / "per_market.csv", results)
    write(target / "overall.csv", overall)
    write(target / "parameter_changes.csv", changes)
    write(target / "rankings.csv", rankings)
    write(target / "query_audit.csv", audit)
    (target / "audit_complete.json").write_text(json.dumps(dict(
        captured=datetime.now(timezone.utc).isoformat(), baseline=str(baseline), output=str(output),
        markets=5, queries_per_market=40, oos_per_query=1000,
        aggregation="Mean each metric over 40 queries within market, then mean market-relative improvements",
        risk_metrics="average conditional query metrics, not pooled risk", selection="no market filtering"), indent=2), encoding="utf-8")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
