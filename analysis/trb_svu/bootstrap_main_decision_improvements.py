"""Paired decision-level percentile CIs; read existing solves, never run a solver.

The remote collector is this same file streamed to Python stdin over SSH. It uses
only the standard library and writes nothing on the remote host.
"""
import argparse
import csv
import hashlib
import json
import math
import statistics
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

METRICS = ("mean", "sd", "q95", "maximum")
METHODS = ("RF-CSAA", "RCSAA", "C-Chi2", "W1-L1", "W1-Linf")
LABELS = ("RF-CSAA", "RCSAA", "chi2-DRO", "W1-L1", "W1-Linf")


def read_rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        return list(csv.DictReader(stream))


def write_rows(path, rows):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def load_query(folder, method, robust):
    prefix = "experiment2_" if robust else ""
    summary_path = folder / f"oos/{prefix}summary.csv"
    solve_path = folder / ("solve/experiment2_final_solves.csv" if robust
                           else "solve/final_solve.csv")
    summary, solve = read_rows(summary_path), read_rows(solve_path)
    if len(summary) != 1 or len(solve) != 1:
        raise ValueError(f"Missing or multiple final records: {folder}")
    expected_method = "C-W1" if method == "W1-L1" else method
    if summary[0]["method"] != expected_method or solve[0]["method"] != expected_method:
        raise ValueError(f"Unexpected source method: {folder}")
    for name in ("failure.txt", "failed.txt"):
        if (folder / name).exists():
            raise ValueError(f"Failure marker present: {folder / name}")
    draws_path = folder / f"oos/{prefix}draws.csv"
    draws = read_rows(draws_path)
    if len(draws) != 1000 or len({r["sample_id"] for r in draws}) != 1000:
        raise ValueError(f"Not 1000 distinct OOS draws: {folder}")
    if not all(math.isfinite(float(r[k])) for r in draws
               for k in ("total_cost", "total_demand")):
        raise ValueError(f"Non-finite OOS: {folder}")
    # Only check the frozen definitions; estimates below read saved summaries.
    costs = sorted(float(r["total_cost"]) for r in draws)
    expected = (statistics.mean(costs), statistics.stdev(costs),
                0.95 * costs[949] + 0.05 * costs[950], costs[-1])
    values = {k: float(summary[0][k]) for k in METRICS}
    for k, check in zip(METRICS, expected):
        if not math.isfinite(values[k]) or not math.isclose(
                values[k], check, rel_tol=1e-9, abs_tol=1e-6):
            raise ValueError(f"Saved metric disagrees with frozen definition: {folder}, {k}")
    metadata_path = folder / "query_metadata.txt"
    metadata = dict(line.split("=", 1) for line in
                    metadata_path.read_text(encoding="utf-8-sig").splitlines()
                    if "=" in line)
    query_hash = metadata.get("querySha256", metadata.get("instanceSha256"))
    if not query_hash:
        raise ValueError(f"Missing query/instance hash: {folder}")
    pairs = [[r["sample_id"], float(r["total_demand"]).hex()] for r in draws]
    pair_hash = hashlib.sha256(json.dumps(pairs, separators=(",", ":")).encode()).hexdigest()
    return dict(method=method, source=str(folder), query_sha256=query_hash,
                oos_pair_sha256=pair_hash, oos_count=len(draws),
                summary_sha256=hashlib.sha256(summary_path.read_bytes()).hexdigest(),
                draws_sha256=hashlib.sha256(draws_path.read_bytes()).hexdigest(),
                status=solve[0]["status"], certified_optimal=solve[0]["certified_optimal"],
                gap=solve[0]["gap"], decision_vector=solve[0]["decision_vector"],
                selected_parameter=solve[0].get("selected_parameter", ""), **values)


def collect_remote():
    root = Path(r"E:\ccx_work\TSPP_SVU_Contextual")
    main = root / "main_grid_completion_20261004"
    base = root / "paired_cv_best_csaa_20260930/cv030050"
    data = []
    for rep in range(1, 6):
        name = f"rep_{rep:03}"
        folders = {
            "SAA-All": base / "experiment1" / name / "SAA-All",
            "RF-CSAA": main / "experiment1" / name / "RF-CSAA",
            "RCSAA": main / "experiment2_20261005/results" / name / "RCSAA",
            "C-Chi2": base / "experiment2_fixed_csaa/RF-CSAA/primary/C-Chi2" / name,
        }
        for method, directory in folders.items():
            if not (directory / "complete.txt").is_file():
                raise ValueError(f"Incomplete method task: {directory}")
            for q in range(40):
                item = load_query(directory / "queries" / f"query_{q:03}", method,
                                  method in ("RCSAA", "C-Chi2"))
                data.append(dict(rep=rep, query=q, **item))
    print(json.dumps(dict(captured_utc=datetime.now(timezone.utc).isoformat(), rows=data)))


def paired_improvements(data):
    """Require one shared, complete 200-decision panel, with no exclusions."""
    import numpy as np

    index = {}
    for row in data:
        key = (row["rep"], row["query"], row["method"])
        if key in index:
            raise ValueError(f"Duplicate record: {key}")
        index[key] = row
    keys = [(rep, q) for rep in range(1, 6) for q in range(40)]
    expected = {(r, q, m) for r, q in keys for m in ("SAA-All",) + METHODS}
    if set(index) != expected:
        raise ValueError(f"Incomplete/unexpected panel: missing={len(expected-set(index))}, extra={len(set(index)-expected)}")
    result = np.empty((200, len(METHODS), len(METRICS)))
    for n, (rep, q) in enumerate(keys):
        baseline = index[rep, q, "SAA-All"]
        for i, method in enumerate(METHODS):
            row = index[rep, q, method]
            for field in ("query_sha256", "oos_pair_sha256", "oos_count"):
                if row[field] != baseline[field]:
                    raise ValueError(f"Unpaired {field}: {rep}, {q}, {method}")
            for j, metric in enumerate(METRICS):
                b, m = baseline[metric], row[metric]
                if not (math.isfinite(b) and math.isfinite(m) and b > 0):
                    raise ValueError(f"Invalid metric/denominator: {rep}, {q}, {method}, {metric}")
                result[n, i, j] = 100 * (b - m) / b
    return keys, result


def bootstrap(values, seed=20261011, repeats=10000):
    import numpy as np

    rng = np.random.default_rng(seed)
    indices = rng.integers(0, len(values), size=(repeats, len(values)))
    means = np.empty((repeats,) + values.shape[1:])
    for start in range(0, repeats, 250):
        means[start:start+250] = values[indices[start:start+250]].mean(axis=1)
    ci = np.quantile(means, [0.025, 0.975], axis=0, method="linear")
    return indices, means, ci


def main(output, refresh):
    import numpy as np

    output.mkdir(parents=True, exist_ok=True)
    cache = output / "remote_source_capture.json"
    if refresh or not cache.exists():
        command = [r"C:\Windows\System32\OpenSSH\ssh.exe", "-i",
                   r"C:\Users\Changxin\.ssh\codex_solver_auto_ed25519",
                   "-o", "BatchMode=yes", "-o", "ConnectTimeout=15",
                   "codex-runner@100.71.236.93",
                   r"E:\ccx_work\TSPP_SVU_Contextual\deployment_20260930\runtime\python\python.exe",
                   "-", "--remote-collect"]
        captured = subprocess.run(command, input=Path(__file__).read_text(encoding="utf-8"),
                                  text=True, encoding="utf-8", capture_output=True, check=True,
                                  timeout=300)
        payload = json.loads(captured.stdout)
        cache.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    payload = json.loads(cache.read_text(encoding="utf-8"))
    data = payload["rows"]
    runs = Path(__file__).resolve().parents[2] / "analysis_runs"
    local_sources = {
        "W1-L1": runs / "main_w1_full_cv_local_20261006/results/primary/C-W1",
        "W1-Linf": runs / "w1_linf_monotone_probe_20261007/cv_upto01/results",
    }
    for method, root in local_sources.items():
        for rep in range(1, 6):
            for q in range(40):
                item = load_query(root / f"rep_{rep:03}/queries/query_{q:03}", method,
                                  method == "W1-L1")
                data.append(dict(rep=rep, query=q, **item))
    keys, values = paired_improvements(data)
    indices, means, ci = bootstrap(values)
    np.save(output / "bootstrap_indices.npy", indices)
    point_rows, interval_rows = [], []
    for i, method in enumerate(METHODS):
        point_rows.append(dict(method=LABELS[i], **dict(zip(METRICS, values[:, i].mean(axis=0)))))
        interval_rows.append(dict(method=LABELS[i], **{
            k: f"[{ci[0,i,j]:.3f}, {ci[1,i,j]:.3f}]" for j, k in enumerate(METRICS)}))
    columns = [f"{m}_{k}" for m in METHODS for k in METRICS]
    write_rows(output / "source_metrics_and_audit.csv", data)
    write_rows(output / "decision_improvements_pct.csv", [dict(rep=r, query=q,
               **dict(zip(columns, values[n].ravel()))) for n, (r, q) in enumerate(keys)])
    write_rows(output / "bootstrap_means_pct.csv", [dict(iteration=n+1,
               **dict(zip(columns, means[n].ravel()))) for n in range(len(means))])
    write_rows(output / "point_estimates_pct.csv", point_rows)
    write_rows(output / "appendix_ci95_pct.csv", interval_rows)
    detailed = [dict(method=LABELS[i], metric=k, point=float(values[:,i,j].mean()),
                     lower=float(ci[0,i,j]), upper=float(ci[1,i,j]))
                for i in range(len(METHODS)) for j, k in enumerate(METRICS)]
    write_rows(output / "ci95_full_precision.csv", detailed)
    report = dict(captured_utc=payload["captured_utc"], computed_utc=datetime.now(timezone.utc).isoformat(),
                  seed=20261011, repeats=10000, decisions=200, markets=[1,2,3,4,5],
                  queries_per_market=40, oos_per_decision=1000, exclusions=0,
                  numpy_version=np.__version__, rng="PCG64", ci_method="percentile",
                  ci_quantile="linear interpolation", resampling_unit="procurement decision",
                  shared_indices_sha256=hashlib.sha256(indices.astype("<i8").tobytes()).hexdigest(),
                  aggregation="mean of 200 decision-level relative improvements over SAA-All",
                  interpretation="decision variation conditional on five fixed markets; not market-level population inference",
                  w1_scope="L1 completed small-radius CV; Linf completed CV through radius 0.1",
                  rcsaa_dro_metrics_identical=bool(np.array_equal(values[:,1], values[:,2])),
                  uncertified_counts={m: sum(x["certified_optimal"].lower() != "true" for x in data
                                               if x["method"] == m) for m in ("SAA-All",)+METHODS},
                  results=detailed)
    (output / "manifest.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(dict(point_estimates=point_rows, appendix_ci95=interval_rows,
                         audit={k:v for k,v in report.items() if k != "results"}), indent=2))


if __name__ == "__main__":
    if "--remote-collect" in sys.argv:
        collect_remote()
    else:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("output", type=Path)
        parser.add_argument("--refresh-remote", action="store_true")
        args = parser.parse_args()
        main(args.output, args.refresh_remote)
