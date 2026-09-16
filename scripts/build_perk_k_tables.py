import csv
import math
from pathlib import Path
from statistics import mean, pstdev


ROOT = Path(__file__).resolve().parents[1]
ANALYSIS = ROOT / "analysis"
PURCHASE_ROOT = ANALYSIS / "巴西数据分析" / "新版_purchase时间"
PERK_ROOT = PURCHASE_ROOT / "第二组_按k选参原始输出"
MERGED_TRIALS = PURCHASE_ROOT / "按purchase时间_raw网格实验_10供应商" / "all_trials_merged_dedup_purchase_raw_加参数求解.csv"
OUT_DIR = PURCHASE_ROOT / "第二组_按k最优参数提取结果"
SUPPLEMENTAL_RCSAA = OUT_DIR / "missing_selected_rcsaa_trials.csv"
EXISTING_TRIALS = OUT_DIR / "所有trial_所有k_最终提取记录.csv"
BASELINE_K = 3


def norm_num(value):
    if value is None:
        return ""
    text = str(value).strip()
    if text == "" or text.lower() == "nan":
        return ""
    try:
        x = float(text)
    except ValueError:
        return text
    if math.isnan(x):
        return ""
    return f"{x:.10f}"


def parse_float(value):
    text = str(value).strip()
    if text == "" or text.lower() == "nan":
        return math.nan
    return float(text)


def percentile(sorted_vals, p):
    if not sorted_vals:
        return math.nan
    if len(sorted_vals) == 1:
        return sorted_vals[0]
    pos = (len(sorted_vals) - 1) * p
    lo = math.floor(pos)
    hi = math.ceil(pos)
    if lo == hi:
        return sorted_vals[lo]
    frac = pos - lo
    return sorted_vals[lo] * (1 - frac) + sorted_vals[hi] * frac


def compute_stats(rows, field):
    vals = [parse_float(r[field]) for r in rows if not math.isnan(parse_float(r[field]))]
    vals.sort()
    if not vals:
        return {f"mean_{field}": math.nan}
    return {
        f"mean_{field}": mean(vals),
        f"std_{field}": pstdev(vals) if len(vals) > 1 else 0.0,
        f"min_{field}": vals[0],
        f"p20_{field}": percentile(vals, 0.20),
        f"p50_{field}": percentile(vals, 0.50),
        f"p75_{field}": percentile(vals, 0.75),
        f"p80_{field}": percentile(vals, 0.80),
        f"p95_{field}": percentile(vals, 0.95),
        f"max_{field}": vals[-1],
    }


def load_perk_selected():
    rows = []
    for csv_path in PERK_ROOT.rglob("perk_selected.csv"):
        with csv_path.open("r", encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            for row in reader:
                row["_source_file"] = str(csv_path)
                rows.append(row)
    dedup = {}
    for row in rows:
        key = (int(row["trialId"]), int(row["k"]))
        dedup[key] = row
    if len(dedup) != 153:
        raise RuntimeError(f"Expected 153 trial-k rows, got {len(dedup)}")
    return dedup


def load_merged_trials():
    rows = []
    with MERGED_TRIALS.open("r", encoding="utf-8-sig", newline="") as f:
        rows.extend(list(csv.DictReader(f)))
    if SUPPLEMENTAL_RCSAA.exists():
        with SUPPLEMENTAL_RCSAA.open("r", encoding="utf-8-sig", newline="") as f:
            rows.extend(list(csv.DictReader(f)))
    elif EXISTING_TRIALS.exists():
        with EXISTING_TRIALS.open("r", encoding="utf-8-sig", newline="") as f:
            for row in csv.DictReader(f):
                if row.get("base_method") == "RCSAA":
                    rows.append(row)
    return rows


def build_index(merged_rows):
    index = {
        "Mean": {},
        "SAA": {},
        "CSAA": {},
        "RCSAA": {},
    }
    for row in merged_rows:
        trial = int(row["试验ID"])
        k = int(row["k1"])
        mode = row["求解模式"]
        c_h = norm_num(row["C_h"])
        lam = norm_num(row["lambda"])
        if mode == "MeanDeterministic":
            index["Mean"][(trial, k)] = row
        elif mode == "SAA":
            index["SAA"][(trial, k)] = row
        elif mode == "CSAA":
            index["CSAA"][(trial, k, c_h)] = row
        elif mode == "RCSAA":
            index["RCSAA"][(trial, k, c_h, lam)] = row
    return index


def build_final_rows(perk_rows, merged_index):
    final_rows = []
    for k in (1, 2, 3):
        for trial in range(51):
            chosen = perk_rows[(trial, k)]
            c_h = norm_num(chosen["bestC_h"])
            lam = norm_num(chosen["bestLambda"])
            stage1 = chosen["stage1ValMean"]
            stage2 = chosen["stage2ValMean"]

            bundle = [
                (
                    f"Mean_k{k}",
                    f"fixed baseline (k={BASELINE_K} rows reused)",
                    merged_index["Mean"][(trial, BASELINE_K)],
                    "",
                    "",
                    "",
                    "",
                ),
                (
                    f"SAA_k{k}",
                    f"fixed baseline (k={BASELINE_K} rows reused)",
                    merged_index["SAA"][(trial, BASELINE_K)],
                    "",
                    "",
                    "",
                    "",
                ),
                (
                    f"CSAA_k{k}",
                    f"trial-wise best C_h under k={k}",
                    merged_index["CSAA"][(trial, k, c_h)],
                    str(k),
                    c_h,
                    "",
                    stage1,
                ),
                (
                    f"RCSAA_k{k}",
                    f"trial-wise best (C_h, lambda) under k={k}",
                    merged_index["RCSAA"][(trial, k, c_h, lam)],
                    str(k),
                    c_h,
                    lam,
                    stage2,
                ),
            ]

            for method_name, strategy, source_row, selected_k, selected_c, selected_lam, val_metric in bundle:
                out_row = {
                    "final_method_name": method_name,
                    "base_method": method_name.split("_", 1)[0],
                    "group_k": k,
                    "param_strategy": strategy,
                    "trialId": trial,
                    "actual_test_period": int(source_row["测试索引"]) + int(source_row["k1"]),
                    "selected_k": selected_k,
                    "selected_C_h": selected_c,
                    "selected_lambda": selected_lam,
                    "stage1_val_mean": stage1 if selected_k else "",
                    "stage2_val_mean": val_metric if selected_lam or method_name.startswith("RCSAA") else "",
                }
                out_row.update(source_row)
                final_rows.append(out_row)
    return final_rows


def build_summary(final_rows):
    groups = {}
    for row in final_rows:
        key = (row["final_method_name"], row["base_method"], row["group_k"], row["param_strategy"])
        groups.setdefault(key, []).append(row)

    summary_rows = []
    for key in sorted(groups.keys(), key=lambda x: (int(x[2]), x[1])):
        method_name, base_method, group_k, strategy = key
        rows = groups[key]
        summary = {
            "final_method_name": method_name,
            "base_method": base_method,
            "group_k": group_k,
            "param_strategy": strategy,
            "trial_count": len(rows),
        }
        summary.update(compute_stats(rows, "期望目标值"))
        summary.update(compute_stats(rows, "实现目标值"))
        for field, alias in [
            ("样本外运输成本", "mean_transport_cost"),
            ("样本外现货成本", "mean_spot_cost"),
            ("样本外罚金成本", "mean_penalty_cost"),
            ("求解时间秒", "mean_solve_time_sec"),
            ("选中供应商数量", "mean_selected_count"),
            ("有效样本量", "mean_ess"),
        ]:
            vals = [parse_float(r[field]) for r in rows if not math.isnan(parse_float(r[field]))]
            summary[alias] = mean(vals) if vals else math.nan
        summary_rows.append(summary)
    return summary_rows


def write_csv(path, rows):
    if not rows:
        raise RuntimeError(f"No rows to write: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    fieldnames = list(rows[0].keys())
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def write_xlsx(summary_rows, trial_rows, out_path):
    try:
        from openpyxl import Workbook
    except Exception:
        return False

    wb = Workbook()
    ws1 = wb.active
    ws1.title = "summary"
    ws1.append(list(summary_rows[0].keys()))
    for row in summary_rows:
        ws1.append([row[k] for k in summary_rows[0].keys()])

    ws2 = wb.create_sheet("trials")
    ws2.append(list(trial_rows[0].keys()))
    for row in trial_rows:
        ws2.append([row[k] for k in trial_rows[0].keys()])

    wb.save(out_path)
    return True


def main():
    perk_rows = load_perk_selected()
    merged_rows = load_merged_trials()
    merged_index = build_index(merged_rows)
    final_rows = build_final_rows(perk_rows, merged_index)
    if len(final_rows) != 51 * 3 * 4:
        raise RuntimeError(f"Expected 612 extracted rows, got {len(final_rows)}")

    summary_rows = build_summary(final_rows)

    trials_csv = OUT_DIR / "所有trial_所有k_最终提取记录.csv"
    summary_csv = OUT_DIR / "汇总统计.csv"
    xlsx_path = OUT_DIR / "24line_10供应商_按k最优参数汇报.xlsx"

    write_csv(trials_csv, final_rows)
    write_csv(summary_csv, summary_rows)
    xlsx_ok = write_xlsx(summary_rows, final_rows, xlsx_path)

    print(f"wrote trials: {trials_csv}")
    print(f"wrote summary: {summary_csv}")
    print(f"trial_rows={len(final_rows)} summary_rows={len(summary_rows)}")
    print(f"xlsx={'yes' if xlsx_ok else 'no'} path={xlsx_path}")


if __name__ == "__main__":
    main()
