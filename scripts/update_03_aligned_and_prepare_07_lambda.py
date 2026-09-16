from __future__ import annotations

import csv
import math
import shutil
from pathlib import Path
from statistics import mean, pstdev


ROOT = Path(__file__).resolve().parents[1]
OUT_ROOT = ROOT / "analysis" / "巴西数据分析" / "新版_purchase时间" / "输出"
RAW03 = OUT_ROOT / "03_10供应商基础网格与提取" / "按purchase时间_raw网格实验_10供应商"
ALIGNED03 = RAW03 / "全部实验配置_4方法_对齐51次样本外结果"
TRIAL03 = ALIGNED03 / "全部实验配置_对齐后51次样本外_trial.csv"
SUMMARY03 = ALIGNED03 / "全部实验配置_对齐后51次样本外_summary.csv"

SENS06 = OUT_ROOT / "06_补充分析_C_h灵敏度"
CSAA01_TRIAL = (
    SENS06
    / "04_逐参数求解目录"
    / "01_C_h=0.1逐参数求解目录"
    / "k=1_C_h=0.1_最终结果"
    / "逐trial样本外结果.csv"
)

OUT07 = OUT_ROOT / "07_lambda灵敏度分析_k=1_代表性C_h"
COPY07 = OUT07 / "01_复制的03总表"
EXTRACT07 = OUT07 / "02_k=1_代表性C_h提取结果"

TARGET_RCSAA_DIRS = {
    "RCSAA_W50_k1=1_raw_C0.10_lambda0.01",
    "RCSAA_W50_k1=1_raw_C0.10_lambda0.05",
    "RCSAA_W50_k1=1_raw_C0.10_lambda0.10",
    "RCSAA_W50_k1=1_raw_C0.10_lambda1.00",
    "RCSAA_W50_k1=1_raw_C0.10_lambda5.00",
    "RCSAA_W50_k1=1_raw_C0.10_lambda10.00",
    "RCSAA_W50_k1=1_raw_C0.10_lambda50.00",
    "RCSAA_W50_k1=1_raw_C0.10_lambda100.00",
    "RCSAA_W50_k1=1_raw_C0.50_lambda0.01",
    "RCSAA_W50_k1=1_raw_C0.50_lambda0.05",
    "RCSAA_W50_k1=1_raw_C0.50_lambda0.10",
    "RCSAA_W50_k1=1_raw_C0.50_lambda1.00",
    "RCSAA_W50_k1=1_raw_C0.50_lambda5.00",
    "RCSAA_W50_k1=1_raw_C0.50_lambda10.00",
    "RCSAA_W50_k1=1_raw_C0.50_lambda50.00",
    "RCSAA_W50_k1=1_raw_C0.50_lambda100.00",
    "RCSAA_W50_k1=1_raw_C100.00_lambda0.05",
    "RCSAA_W50_k1=1_raw_C100.00_lambda0.10",
    "RCSAA_W50_k1=1_raw_C100.00_lambda1.00",
    "RCSAA_W50_k1=1_raw_C100.00_lambda5.00",
    "RCSAA_W50_k1=1_raw_C100.00_lambda10.00",
    "RCSAA_W50_k1=1_raw_C100.00_lambda50.00",
    "RCSAA_W50_k1=1_raw_C100.00_lambda100.00",
}
REPRESENTATIVE_CH = (0.1, 0.5, 1.0, 100.0)
MIN_PERIOD = 53
MAX_PERIOD = 103


def read_csv(path: Path) -> tuple[list[dict[str, str]], list[str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
        return rows, list(reader.fieldnames or [])


def write_csv(path: Path, rows: list[dict[str, object]], fieldnames: list[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def parse_float(value: object) -> float:
    text = "" if value is None else str(value).strip()
    if text == "" or text.lower() == "nan":
        return math.nan
    return float(text)


def fmt_num(x: float, digits: int = 6) -> str:
    if math.isnan(x):
        return "NaN"
    return f"{x:.{digits}f}"


def fmt_ch(x: float) -> str:
    if math.isnan(x):
        return "NaN"
    if abs(x - round(x)) < 1e-12:
        return str(int(round(x)))
    return ("%g" % x)


def fmt_lambda(x: float) -> str:
    if math.isnan(x):
        return "NaN"
    if x >= 1 and abs(x - round(x)) < 1e-12:
        return str(int(round(x)))
    return ("%g" % x)


def percentile(sorted_vals: list[float], p: float) -> float:
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


def build_summary_row(rows: list[dict[str, str]]) -> dict[str, object]:
    realized = sorted(float(r["实现目标值"]) for r in rows)
    expected = [float(r["期望目标值"]) for r in rows]
    solve = [float(r["求解时间秒"]) for r in rows]
    selected = [float(r["选中供应商数量"]) for r in rows]
    periods = [int(r["actual_test_period"]) for r in rows]
    first = rows[0]
    return {
        "method_name": first["method_name"],
        "source_dir": first["source_dir"],
        "k1": first["k1"],
        "C_h": first["C_h"],
        "lambda": first["lambda"],
        "param": first["param"],
        "标签": first["标签"],
        "trial_count": len(rows),
        "mean_实现目标值": mean(realized),
        "std_实现目标值": pstdev(realized) if len(realized) > 1 else 0.0,
        "p20_实现目标值": percentile(realized, 0.20),
        "p50_实现目标值": percentile(realized, 0.50),
        "p75_实现目标值": percentile(realized, 0.75),
        "p95_实现目标值": percentile(realized, 0.95),
        "max_实现目标值": realized[-1],
        "mean_期望目标值": mean(expected),
        "std_期望目标值": pstdev(expected) if len(expected) > 1 else 0.0,
        "mean_求解时间秒": mean(solve),
        "mean_选中供应商数量": mean(selected),
        "actual_test_period_range": f"{min(periods)}-{max(periods)}",
    }


def convert_raw_trial_row(raw: dict[str, str], source_dir: str) -> dict[str, str]:
    c_h = parse_float(raw["C_h"])
    lam = parse_float(raw["lambda"])
    actual_period = int(raw["testIdx"]) + int(raw["k1Lag"])
    return {
        "k1": raw["k1Lag"],
        "source_dir": source_dir,
        "method_name": raw["solveMode"],
        "param": f"C_h={fmt_ch(c_h)}, lambda={fmt_lambda(lam)}",
        "标签": raw["tag"],
        "试验ID": raw["trialId"],
        "测试索引": raw["testIdx"],
        "求解模式": raw["solveMode"],
        "fillMissingDates": raw["fillMissingDates"],
        "standardizeTheta": raw["standardizeTheta"],
        "k1Lag": raw["k1Lag"],
        "kernelType": raw["kernelType"],
        "bandwidthH": raw["bandwidthH"],
        "C_h": raw["C_h"],
        "lambda": raw["lambda"],
        "训练集大小": raw["trainSize"],
        "期望目标值": raw["expectedObj"],
        "实现目标值": raw["realizedObj"],
        "求解时间秒": raw["solveTimeSec"],
        "选中供应商数量": raw["selectedCount"],
        "样本外运输成本": raw["oosTransportCost"],
        "样本外现货成本": raw["oosSpotCost"],
        "样本外罚金成本": raw["oosPenaltyCost"],
        "权重和": raw["sumW"],
        "权重平方和": raw["sumW2"],
        "有效样本量": raw["ESS"],
        "最大权重": raw["top1W"],
        "前5权重和": raw["top5Wsum"],
        "最大权重相对平均值": raw["maxW_over_meanW"],
        "Theta距离均值": raw["thetaDist_mean"],
        "Theta距离中位数": raw["thetaDist_median"],
        "Theta距离最小值": raw["thetaDist_min"],
        "Theta距离最大值": raw["thetaDist_max"],
        "需求距离均值": raw["demandDist_mean"],
        "需求距离中位数": raw["demandDist_median"],
        "需求距离最小值": raw["demandDist_min"],
        "需求距离最大值": raw["demandDist_max"],
        "权重Theta距离相关系数": raw["corrW_thetaDist"],
        "权重需求距离相关系数": raw["corrW_demandDist"],
        "Theta需求距离相关系数": raw["corrTheta_demandDist"],
        "y二进制向量": raw["yBinary"],
        "选中供应商集合": raw["selectedCarriers"],
        "actual_test_period": str(actual_period),
    }


def load_aligned_rows_from_global_trials(dir_path: Path) -> list[dict[str, str]]:
    rows, _ = read_csv(dir_path / "global_trials.csv")
    dedup: dict[tuple[str, str], dict[str, str]] = {}
    for row in rows:
        dedup[(row["trialId"], row["testIdx"])] = row
    out: list[dict[str, str]] = []
    for row in dedup.values():
        converted = convert_raw_trial_row(row, dir_path.name)
        period = int(converted["actual_test_period"])
        if MIN_PERIOD <= period <= MAX_PERIOD:
            out.append(converted)
    out.sort(key=lambda r: int(r["试验ID"]))
    return out


def target_rcsaa_dirs() -> list[Path]:
    out: list[Path] = []
    for dir_path in RAW03.iterdir():
        if not dir_path.is_dir():
            continue
        if dir_path.name in TARGET_RCSAA_DIRS and (dir_path / "global_summary.csv").exists():
            out.append(dir_path)
    return sorted(out, key=lambda p: p.name)


def update_03_tables() -> tuple[list[dict[str, str]], list[dict[str, str]]]:
    trial_rows, trial_fields = read_csv(TRIAL03)
    summary_rows, summary_fields = read_csv(SUMMARY03)

    update_dirs = target_rcsaa_dirs()
    update_names = {p.name for p in update_dirs}

    trial_rows = [r for r in trial_rows if r["source_dir"] not in update_names]
    summary_rows = [r for r in summary_rows if r["source_dir"] not in update_names]

    new_trial_rows: list[dict[str, str]] = []
    new_summary_rows: list[dict[str, str]] = []
    for dir_path in update_dirs:
        aligned_rows = load_aligned_rows_from_global_trials(dir_path)
        if len(aligned_rows) != 51:
            raise RuntimeError(f"{dir_path.name} aligned row count expected 51, got {len(aligned_rows)}")
        new_trial_rows.extend(aligned_rows)
        new_summary_rows.append(build_summary_row(aligned_rows))

    trial_rows.extend(new_trial_rows)
    trial_rows.sort(
        key=lambda r: (
            int(r["k1"]),
            r["method_name"],
            parse_float(r["C_h"]),
            parse_float(r["lambda"]),
            int(r["试验ID"]),
        )
    )

    summary_rows.extend(new_summary_rows)
    summary_rows.sort(
        key=lambda r: (
            r["method_name"],
            int(r["k1"]),
            parse_float(r["C_h"]),
            parse_float(r["lambda"]),
            r["source_dir"],
        )
    )

    write_csv(TRIAL03, trial_rows, trial_fields)
    write_csv(SUMMARY03, summary_rows, summary_fields)
    return trial_rows, summary_rows


def build_csaa01_rows_for_07() -> tuple[list[dict[str, str]], dict[str, object]]:
    raw_rows, _ = read_csv(CSAA01_TRIAL)
    dedup: dict[tuple[str, str], dict[str, str]] = {}
    for row in raw_rows:
        dedup[(row["trialId"], row["testIdx"])] = row
    trial_rows = []
    for row in dedup.values():
        converted = convert_raw_trial_row(row, "CSAA_W50_k1=1_raw_C0.10")
        converted["param"] = "C_h=0.1"
        converted["lambda"] = "NaN"
        period = int(converted["actual_test_period"])
        if MIN_PERIOD <= period <= MAX_PERIOD:
            trial_rows.append(converted)
    trial_rows.sort(key=lambda r: int(r["试验ID"]))
    if len(trial_rows) != 51:
        raise RuntimeError(f"CSAA C_h=0.1 aligned row count expected 51, got {len(trial_rows)}")
    summary_row = build_summary_row(trial_rows)
    summary_row["source_dir"] = "CSAA_W50_k1=1_raw_C0.10"
    summary_row["lambda"] = "NaN"
    summary_row["param"] = "C_h=0.1"
    return trial_rows, summary_row


def build_csaa_rows_from_03(dir_name: str) -> tuple[list[dict[str, str]], dict[str, object]]:
    dir_path = RAW03 / dir_name
    trial_rows = load_aligned_rows_from_global_trials(dir_path)
    for row in trial_rows:
        row["method_name"] = "CSAA"
        row["求解模式"] = "CSAA"
        row["lambda"] = "NaN"
        row["param"] = f"C_h={fmt_ch(parse_float(row['C_h']))}"
    summary_row = build_summary_row(trial_rows)
    summary_row["method_name"] = "CSAA"
    summary_row["lambda"] = "NaN"
    summary_row["param"] = f"C_h={fmt_ch(parse_float(summary_row['C_h']))}"
    return trial_rows, summary_row


def prepare_07(trial_rows_03: list[dict[str, str]], summary_rows_03: list[dict[str, str]]) -> None:
    COPY07.mkdir(parents=True, exist_ok=True)
    EXTRACT07.mkdir(parents=True, exist_ok=True)

    shutil.copy2(TRIAL03, COPY07 / TRIAL03.name)
    shutil.copy2(SUMMARY03, COPY07 / SUMMARY03.name)

    rep_ch_set = {f"{v:.6f}" for v in REPRESENTATIVE_CH}
    subset_trial: list[dict[str, str]] = []
    subset_summary: list[dict[str, str]] = []

    for csaa_dir in ("CSAA_W50_k1=1_raw_C0.50", "CSAA_W50_k1=1_raw_C1.00", "CSAA_W50_k1=1_raw_C100.00"):
        rows, summary = build_csaa_rows_from_03(csaa_dir)
        subset_trial.extend(rows)
        subset_summary.append(summary)

    csaa01_trial_rows, csaa01_summary_row = build_csaa01_rows_for_07()
    subset_trial.extend(csaa01_trial_rows)
    subset_summary.append(csaa01_summary_row)

    subset_trial.extend(
        [
            r
            for r in trial_rows_03
            if int(r["k1"]) == 1 and r["method_name"] == "RCSAA" and r["C_h"] in rep_ch_set
        ]
    )
    subset_summary.extend(
        [
            r
            for r in summary_rows_03
            if int(r["k1"]) == 1 and r["method_name"] == "RCSAA" and r["C_h"] in rep_ch_set
        ]
    )

    subset_trial.sort(
        key=lambda r: (
            0 if r["method_name"] == "CSAA" else 1,
            parse_float(r["C_h"]),
            parse_float(r["lambda"]),
            int(r["试验ID"]),
        )
    )
    subset_summary.sort(
        key=lambda r: (
            0 if r["method_name"] == "CSAA" else 1,
            parse_float(r["C_h"]),
            parse_float(r["lambda"]),
        )
    )

    trial_fields = list(subset_trial[0].keys())
    summary_fields = list(subset_summary[0].keys())
    write_csv(
        EXTRACT07 / "k=1_代表性C_h下_CSAA与RCSAA_lambda灵敏度_trial.csv",
        subset_trial,
        trial_fields,
    )
    write_csv(
        EXTRACT07 / "k=1_代表性C_h下_CSAA与RCSAA_lambda灵敏度_summary.csv",
        subset_summary,
        summary_fields,
    )

    plot_rows = []
    for row in subset_summary:
        plot_rows.append(
            {
                "method_name": row["method_name"],
                "k1": row["k1"],
                "C_h": row["C_h"],
                "lambda": row["lambda"],
                "source_dir": row["source_dir"],
                "param": row["param"],
                "trial_count": row["trial_count"],
                "mean_realized_obj": row["mean_实现目标值"],
                "std_realized_obj": row["std_实现目标值"],
                "max_realized_obj": row["max_实现目标值"],
                "mean_selected_count": row["mean_选中供应商数量"],
                "mean_expected_obj": row["mean_期望目标值"],
                "mean_solve_time_sec": row["mean_求解时间秒"],
            }
        )
    write_csv(
        EXTRACT07 / "k=1_lambda灵敏度作图数据.csv",
        plot_rows,
        list(plot_rows[0].keys()),
    )

    note = (
        "本目录用于 k=1 下正则化参数 lambda 的灵敏度分析。\n"
        "01_复制的03总表 为更新后的 03 总 trial / summary 原样复制。\n"
        "02_k=1_代表性C_h提取结果 提取了 C_h=0.1, 0.5, 1, 100 四组代表点。\n"
        "其中 CSAA 的 C_h=0.1 来自 06；其余 CSAA/RCSAA 来自更新后的 03。\n"
        "k=1_lambda灵敏度作图数据.csv 是后续画 mean/std/max/selected-count 折线图的直接输入。\n"
    )
    (OUT07 / "说明.txt").write_text(note, encoding="utf-8")


def main() -> None:
    trial_rows_03, summary_rows_03 = update_03_tables()
    prepare_07(trial_rows_03, summary_rows_03)
    print("updated_03_trial", TRIAL03)
    print("updated_03_summary", SUMMARY03)
    print("prepared_07", OUT07)


if __name__ == "__main__":
    main()
