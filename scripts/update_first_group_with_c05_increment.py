import csv
import math
from pathlib import Path


def read_csv(path):
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def write_csv(path, rows, fieldnames):
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def q(vals, p):
    if not vals:
        return float("nan")
    xs = sorted(vals)
    if len(xs) == 1:
        return xs[0]
    idx = p * (len(xs) - 1)
    lo = int(math.floor(idx))
    hi = int(math.ceil(idx))
    if lo == hi:
        return xs[lo]
    w = idx - lo
    return xs[lo] * (1 - w) + xs[hi] * w


def mean(xs):
    return sum(xs) / len(xs) if xs else float("nan")


def stdev(xs):
    if len(xs) <= 1:
        return 0.0
    m = mean(xs)
    return math.sqrt(sum((x - m) ** 2 for x in xs) / (len(xs) - 1))


def fmt_sel(v):
    if v is None or v == "":
        return ""
    x = float(v)
    if abs(x - round(x)) <= 1e-9:
        return str(int(round(x)))
    s = f"{x:.10f}".rstrip("0").rstrip(".")
    return s


def build_replacement_row(old_row, raw_row, sel, method_name):
    row = dict(old_row)
    row["final_method_name"] = method_name
    row["param_strategy"] = "trial-wise best (k, C_h)" if method_name == "CSAA" else "trial-wise best (k, C_h, lambda)"
    row["trialId"] = str(sel["trialId"])
    row["actual_test_period"] = str(int(raw_row["测试索引"]) + int(raw_row["k1"]))
    row["selected_k"] = fmt_sel(sel["bestK"])
    row["selected_C_h"] = fmt_sel(sel["bestC_h"])
    row["selected_lambda"] = "" if method_name == "CSAA" else fmt_sel(sel["bestLambda"])
    row["stage1_val_mean"] = sel["stage1ValMean"]
    row["stage2_val_mean"] = sel["stage2ValMean"]

    passthrough = [
        "k1", "source_dir", "method_name", "param", "标签", "试验ID", "测试索引", "求解模式",
        "fillMissingDates", "standardizeTheta", "k1Lag", "kernelType", "bandwidthH",
        "C_h", "lambda", "训练集大小", "期望目标值", "实现目标值", "求解时间秒",
        "选中供应商数量", "样本外运输成本", "样本外现货成本", "样本外罚金成本",
        "权重和", "权重平方和", "有效样本量", "最大权重", "前5权重和", "最大权重相对平均值",
        "Theta距离均值", "Theta距离中位数", "Theta距离最小值", "Theta距离最大值",
        "需求距离均值", "需求距离中位数", "需求距离最小值", "需求距离最大值",
        "权重Theta距离相关系数", "权重需求距离相关系数", "Theta需求距离相关系数",
        "y二进制向量", "选中供应商集合"
    ]
    for key in passthrough:
        row[key] = raw_row[key]
    return row


def summarize(rows):
    groups = {}
    for row in rows:
        groups.setdefault(row["final_method_name"], []).append(row)
    out = []
    for method in ["Mean", "SAA", "CSAA", "RCSAA"]:
        rs = groups[method]
        exp = [float(r["期望目标值"]) for r in rs]
        rea = [float(r["实现目标值"]) for r in rs]
        transport = [float(r["样本外运输成本"]) for r in rs]
        spot = [float(r["样本外现货成本"]) for r in rs]
        penalty = [float(r["样本外罚金成本"]) for r in rs]
        solve_t = [float(r["求解时间秒"]) for r in rs]
        selected = [float(r["选中供应商数量"]) for r in rs]
        ess = [float(r["有效样本量"]) for r in rs]
        out.append({
            "final_method_name": method,
            "param_strategy": rs[0]["param_strategy"],
            "trial_count": str(len(rs)),
            "mean_expected_obj": mean(exp),
            "std_expected_obj": stdev(exp),
            "min_expected_obj": min(exp),
            "p20_expected_obj": q(exp, 0.2),
            "p50_expected_obj": q(exp, 0.5),
            "p75_expected_obj": q(exp, 0.75),
            "p80_expected_obj": q(exp, 0.8),
            "p95_expected_obj": q(exp, 0.95),
            "max_expected_obj": max(exp),
            "mean_realized_obj": mean(rea),
            "std_realized_obj": stdev(rea),
            "min_realized_obj": min(rea),
            "p20_realized_obj": q(rea, 0.2),
            "p50_realized_obj": q(rea, 0.5),
            "p75_realized_obj": q(rea, 0.75),
            "p80_realized_obj": q(rea, 0.8),
            "p95_realized_obj": q(rea, 0.95),
            "max_realized_obj": max(rea),
            "mean_transport_cost": mean(transport),
            "mean_spot_cost": mean(spot),
            "mean_penalty_cost": mean(penalty),
            "mean_solve_time_sec": mean(solve_t),
            "mean_selected_count": mean(selected),
            "mean_ess": mean(ess),
        })
    return out


def write_xlsx(path, trials, summary):
    try:
        from openpyxl import Workbook
    except Exception:
        return
    wb = Workbook()
    ws1 = wb.active
    ws1.title = "final_trials_4methods"
    ws1.append(list(trials[0].keys()))
    for row in trials:
        ws1.append([row[k] for k in trials[0].keys()])
    ws2 = wb.create_sheet("final_summary_4methods")
    ws2.append(list(summary[0].keys()))
    for row in summary:
        ws2.append([row[k] for k in summary[0].keys()])
    wb.save(path)


def main():
    base = Path("analysis/巴西数据分析/新版_purchase时间")
    increment_root = base / "第一组_C_h0.5增量补充"
    old_final_root = base / "第一组_最优参数提取结果_4方法"
    out_root = base / "第一组_最优参数提取结果_4方法_含C0.5增量"
    out_root.mkdir(parents=True, exist_ok=True)

    old_trials = read_csv(old_final_root / "final_trials_4methods.csv")
    updated_sel_rows = read_csv(increment_root / "cv_selected_params_含C0.5增量.csv")
    changed_raw_rows = read_csv(increment_root / "changed_trials_actual_results.csv")

    updated_sel = {int(r["trialId"]): r for r in updated_sel_rows}
    changed_raw = {(int(r["试验ID"]), r["method_name"]): r for r in changed_raw_rows}
    changed_trial_ids = {tid for tid, r in updated_sel.items() if r["isUpdatedByC0.5"].lower() == "true"}

    out_trials = []
    for row in old_trials:
        trial_id = int(row["trialId"])
        if trial_id in changed_trial_ids and row["final_method_name"] in {"CSAA", "RCSAA"}:
            method = row["final_method_name"]
            raw_row = changed_raw[(trial_id, method)]
            sel = updated_sel[trial_id]
            out_trials.append(build_replacement_row(row, raw_row, sel, method))
        else:
            out_trials.append(row)

    summary = summarize(out_trials)
    write_csv(out_root / "final_trials_4methods_含C0.5增量.csv", out_trials, list(out_trials[0].keys()))
    write_csv(out_root / "final_summary_4methods_含C0.5增量.csv", summary, list(summary[0].keys()))
    write_xlsx(out_root / "24line_10供应商_滚动CV选参提取结果_4methods_含C0.5增量.xlsx", out_trials, summary)

    with (out_root / "changed_trials.txt").open("w", encoding="utf-8") as f:
        for trial_id in sorted(changed_trial_ids):
            f.write(f"{trial_id}\n")


if __name__ == "__main__":
    main()
