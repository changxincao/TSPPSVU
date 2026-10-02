"""Aggregate an explicit expected case/query manifest; never select cases by performance."""
import argparse
import csv
import math
from collections import defaultdict
from pathlib import Path
from statistics import mean


def aggregate(manifest, lambdas):
    with manifest.open(encoding="utf-8-sig", newline="") as stream:
        tasks = list(csv.DictReader(stream))
    if not tasks:
        raise ValueError("Manifest has no expected case/query rows")
    identities = [(task["case_id"], task["query_id"]) for task in tasks]
    if len(set(identities)) != len(identities):
        raise ValueError("Duplicate expected case/query")
    by_lambda = {value: {} for value in lambdas}
    by_case = defaultdict(list)
    for task, identity in zip(tasks, identities):
        by_case[identity[0]].append(identity)
        path = Path(task["comparison_csv"])
        if not path.is_absolute():
            path = manifest.parent / path
        if not path.is_file():
            continue
        with path.open(encoding="utf-8-sig", newline="") as stream:
            for row in csv.DictReader(stream):
                if (row["case_id"], row["query_id"]) != identity:
                    raise ValueError(f"Wrong query identity in {path}")
                value = float(row["lambda"])
                if value not in by_lambda:
                    raise ValueError(f"Unexpected lambda {value} in {path}")
                if identity in by_lambda[value]:
                    raise ValueError(f"Duplicate lambda/query in {path}")
                by_lambda[value][identity] = row
    summaries = []
    for value, indexed in by_lambda.items():
        rows = list(indexed.values())
        paired = [r for r in rows if r["same_decision"] in ("true", "false")]
        certified = [r for r in paired if r["both_certified"] == "true"]
        dro_certified = [r for r in rows if r["chi_certified"] == "true"]
        checked = [r for r in dro_certified if r["certificate_status"] in ("HOLDS", "FAILS")]
        same = sum(r["same_decision"] == "true" for r in certified)
        holds = sum(r["certificate_status"] == "HOLDS" for r in checked)
        summary = dict(lambda_value=value, expected_queries=len(tasks), recorded_queries=len(rows),
                       paired_incumbents=len(paired), paired_certified=len(certified),
                       same_decision_count=same,
                       decision_agreement_percent=100 * same / len(certified) if certified else math.nan,
                       dro_certified=len(dro_certified), certificate_checked=len(checked),
                       certificate_holds_count=holds,
                       condition_holds_percent=100 * holds / len(checked) if checked else math.nan,
                       unpaired_queries=len(tasks) - len(paired),
                       not_both_certified_queries=len(tasks) - len(certified),
                       unresolved_certificate_queries=len(tasks) - len(checked))
        # OOS levels: first average queries within each complete market, then average markets.
        # Risk columns are conditional-query risk averages, not pooled distribution risk.
        metrics = [f"{model}_oos_{metric}" for model in ("chi", "rcsaa")
                   for metric in ("mean", "sd", "q95", "cvar95", "max")]
        complete_markets = []
        for identities_in_case in by_case.values():
            case_rows = [indexed.get(key) for key in identities_in_case]
            if all(r is not None and all(math.isfinite(float(r[m])) for m in metrics) for r in case_rows):
                complete_markets.append({m: mean(float(r[m]) for r in case_rows) for m in metrics})
        summary["complete_oos_markets"] = len(complete_markets)
        summary["expected_markets"] = len(by_case)
        for metric in metrics:
            summary[metric] = mean(r[metric] for r in complete_markets) if complete_markets else math.nan
        summary["oos_mean_delta_percent_rcsaa_vs_chi"] = (
            mean(100 * (r["rcsaa_oos_mean"] - r["chi_oos_mean"]) / r["chi_oos_mean"]
                 for r in complete_markets) if complete_markets and all(r["chi_oos_mean"] > 0 for r in complete_markets)
            else math.nan)
        summaries.append(summary)
    return summaries


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="CSV: case_id,query_id,comparison_csv (all planned queries)")
    parser.add_argument("output", type=Path)
    parser.add_argument("--lambdas", required=True, help="The frozen shared grid")
    args = parser.parse_args()
    lambdas = [float(value) for value in args.lambdas.split(",")]
    if not lambdas or len(set(lambdas)) != len(lambdas) or any(not math.isfinite(v) or v <= 0 for v in lambdas):
        parser.error("Grid must contain unique finite positive lambdas")
    rows = aggregate(args.manifest.resolve(), lambdas)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
