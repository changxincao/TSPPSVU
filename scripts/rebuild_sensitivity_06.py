from __future__ import annotations

import csv
import math
from pathlib import Path
from statistics import mean, pstdev

from openpyxl import Workbook


ROOT = Path(__file__).resolve().parents[1]
SENS_ROOT = ROOT / "analysis" / "巴西数据分析" / "新版_purchase时间" / "输出" / "06_补充分析_C_h灵敏度"
MERGED_TRIALS = (
    ROOT
    / "analysis"
    / "巴西数据分析"
    / "新版_purchase时间"
    / "输出"
    / "03_10供应商基础网格与提取"
    / "按purchase时间_raw网格实验_10供应商"
    / "已弃用_旧汇总文件"
    / "all_trials_merged_dedup_purchase_raw_加参数求解.csv"
)

OUT_FULL_DIR = SENS_ROOT / "01_正式汇总表" / "01_CSAA_C_h完整汇总"
OUT_FOCUS_DIR = SENS_ROOT / "01_正式汇总表" / "02_CSAA_C_h=0.1和0.5重点汇总"
OUT_EXTRACT_05_DIR = SENS_ROOT / "02_提取结果汇总" / "01_C_h=0.5提取结果"
OUT_EXTRACT_01_DIR = SENS_ROOT / "02_提取结果汇总" / "02_C_h=0.1提取结果汇总"

RAW_01_DIR = SENS_ROOT / "04_逐参数求解目录" / "01_C_h=0.1逐参数求解目录"
EXTRACT_05_TRIAL = OUT_EXTRACT_05_DIR / "C_h=0.5逐trial样本外结果.csv"

W = 50
K_MAX = 3
MIN_ALIGNED_PERIOD = W + K_MAX
MAX_ALIGNED_PERIOD = 103
K_GRID = (1, 2, 3)
FULL_C_GRID = (0.1, 0.5, 1.0, 2.0, 3.0, 5.0, 10.0, 30.0, 50.0, 100.0)
LEGACY_C_GRID = (1.0, 2.0, 3.0, 5.0, 10.0, 30.0, 50.0, 100.0)


def parse_float(value: object) -> float:
    text = "" if value is None else str(value).strip()
    if text == "" or text.lower() == "nan":
        return math.nan
    return float(text)


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


def numeric_stats(values: list[float], prefix: str) -> dict[str, float]:
    vals = sorted(v for v in values if not math.isnan(v))
    if not vals:
        return {
            f"mean_{prefix}": math.nan,
            f"std_{prefix}": math.nan,
            f"min_{prefix}": math.nan,
            f"p20_{prefix}": math.nan,
            f"p50_{prefix}": math.nan,
            f"p75_{prefix}": math.nan,
            f"p80_{prefix}": math.nan,
            f"p95_{prefix}": math.nan,
            f"max_{prefix}": math.nan,
        }
    return {
        f"mean_{prefix}": mean(vals),
        f"std_{prefix}": pstdev(vals) if len(vals) > 1 else 0.0,
        f"min_{prefix}": vals[0],
        f"p20_{prefix}": percentile(vals, 0.20),
        f"p50_{prefix}": percentile(vals, 0.50),
        f"p75_{prefix}": percentile(vals, 0.75),
        f"p80_{prefix}": percentile(vals, 0.80),
        f"p95_{prefix}": percentile(vals, 0.95),
        f"max_{prefix}": vals[-1],
    }


def load_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def align_period(test_idx: int, k: int) -> int:
    return test_idx + k


def normalize_merged_row(row: dict[str, str]) -> dict[str, object]:
    k = int(row["k1"])
    test_idx = int(row["测试索引"])
    return {
        "method": row["method_name"],
        "k": k,
        "c_h": parse_float(row.get("C_h")),
        "lambda": parse_float(row.get("lambda")),
        "trial_id": int(row["试验ID"]),
        "test_idx": test_idx,
        "actual_test_period": align_period(test_idx, k),
        "expected_obj": parse_float(row["期望目标值"]),
        "realized_obj": parse_float(row["实现目标值"]),
        "solve_time_sec": parse_float(row["求解时间秒"]),
        "selected_count": parse_float(row["选中供应商数量"]),
        "ess": parse_float(row["有效样本量"]),
        "source_dir": row.get("source_dir", ""),
        "tag": row.get("标签", ""),
    }


def normalize_raw_row(row: dict[str, str], source_dir: str) -> dict[str, object]:
    k = int(row["k1Lag"])
    test_idx = int(row["testIdx"])
    return {
        "method": row["solveMode"],
        "k": k,
        "c_h": parse_float(row.get("C_h")),
        "lambda": parse_float(row.get("lambda")),
        "trial_id": int(row["trialId"]),
        "test_idx": test_idx,
        "actual_test_period": align_period(test_idx, k),
        "expected_obj": parse_float(row["expectedObj"]),
        "realized_obj": parse_float(row["realizedObj"]),
        "solve_time_sec": parse_float(row["solveTimeSec"]),
        "selected_count": parse_float(row["selectedCount"]),
        "ess": parse_float(row["ESS"]),
        "source_dir": source_dir,
        "tag": row.get("tag", ""),
    }


def aligned(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    return [
        row
        for row in rows
        if MIN_ALIGNED_PERIOD <= int(row["actual_test_period"]) <= MAX_ALIGNED_PERIOD
    ]


def aggregate_rows(rows: list[dict[str, object]]) -> dict[str, float]:
    return {
        "trial_count": len(rows),
        "mean_solve_time_sec": mean(float(r["solve_time_sec"]) for r in rows),
        "mean_selected_count": mean(float(r["selected_count"]) for r in rows),
        "mean_ess": mean(float(r["ess"]) for r in rows),
        **numeric_stats([float(r["expected_obj"]) for r in rows], "expected_obj"),
        **numeric_stats([float(r["realized_obj"]) for r in rows], "realized_obj"),
    }


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def write_xlsx(path: Path, rows: list[dict[str, object]]) -> None:
    wb = Workbook()
    ws = wb.active
    ws.title = "summary"
    headers = list(rows[0].keys())
    ws.append(headers)
    for row in rows:
        ws.append([row.get(k, "") for k in headers])
    wb.save(path)


def rebuild_saa_and_legacy_csaa() -> list[dict[str, object]]:
    merged_rows = aligned([normalize_merged_row(r) for r in load_csv(MERGED_TRIALS)])
    out: list[dict[str, object]] = []

    saa_groups: dict[int, list[dict[str, object]]] = {}
    for row in merged_rows:
        if row["method"] == "SAA":
            saa_groups.setdefault(int(row["k"]), []).append(row)
    best_k = min(
        saa_groups,
        key=lambda k: (
            mean(float(r["realized_obj"]) for r in saa_groups[k]),
            pstdev([float(r["realized_obj"]) for r in saa_groups[k]]),
            k,
        ),
    )
    saa_stats = aggregate_rows(saa_groups[best_k])
    out.append(
        {
            "base_method": "SAA",
            "k": best_k,
            "C_h": "",
            "selection_rule": "baseline; choose best k by realized mean, then std",
            "source_dir": saa_groups[best_k][0]["source_dir"],
            **saa_stats,
        }
    )

    for k in K_GRID:
        for c_h in LEGACY_C_GRID:
            group = [
                r
                for r in merged_rows
                if r["method"] == "CSAA"
                and int(r["k"]) == k
                and math.isclose(float(r["c_h"]), c_h, abs_tol=1e-12)
            ]
            if not group:
                continue
            out.append(
                {
                    "base_method": "CSAA",
                    "k": k,
                    "C_h": f"{c_h:.2f}",
                    "selection_rule": "fixed parameter; direct solve on aligned 51 tests",
                    "source_dir": group[0]["source_dir"],
                    **aggregate_rows(group),
                }
            )
    return out


def rebuild_01_summary() -> list[dict[str, object]]:
    out = rebuild_saa_and_legacy_csaa()

    for dir_path in sorted(p for p in RAW_01_DIR.iterdir() if p.is_dir()):
        rows = aligned(
            [
                normalize_raw_row(
                    r,
                    source_dir=dir_path.name.replace("_最终结果", "").replace("k=", "CSAA_W50_k1=").replace("_C_h=", "_raw_C"),
                )
                for r in load_csv(dir_path / "逐trial样本外结果.csv")
            ]
        )
        k = int(rows[0]["k"])
        out.append(
            {
                "base_method": "CSAA",
                "k": k,
                "C_h": "0.10",
                "selection_rule": "fixed parameter; direct solve on aligned 51 tests",
                "source_dir": rows[0]["source_dir"],
                **aggregate_rows(rows),
            }
        )

    rows05 = aligned(
        [
            normalize_raw_row(r, source_dir=str(r.get("source_folder", "")))
            for r in load_csv(EXTRACT_05_TRIAL)
            if r.get("solveMode") == "CSAA"
        ]
    )
    for k in K_GRID:
        group = [r for r in rows05 if int(r["k"]) == k]
        out.append(
            {
                "base_method": "CSAA",
                "k": k,
                "C_h": "0.50",
                "selection_rule": "fixed parameter; direct solve on aligned 51 tests",
                "source_dir": group[0]["source_dir"],
                **aggregate_rows(group),
            }
        )

    out.sort(key=lambda r: (r["base_method"] != "SAA", float(r["k"]), float(r["C_h"] or 0)))
    return out


def rebuild_05_extract_summary() -> list[dict[str, object]]:
    rows = aligned([normalize_raw_row(r, source_dir=str(r.get("source_folder", ""))) for r in load_csv(EXTRACT_05_TRIAL)])
    out: list[dict[str, object]] = []
    grouped: dict[tuple[str, int, float], list[dict[str, object]]] = {}
    for row in rows:
        lam = float(row["lambda"]) if not math.isnan(float(row["lambda"])) else math.nan
        key = (str(row["method"]), int(row["k"]), lam)
        grouped.setdefault(key, []).append(row)
    for (method, k, lam), group in sorted(grouped.items(), key=lambda x: (x[0][0], x[0][1], x[0][2] if not math.isnan(x[0][2]) else -1)):
        agg = aggregate_rows(group)
        out.append(
            {
                "method_name": method,
                "k1": k,
                "C_h": "0.50",
                "lambda": "" if math.isnan(lam) else f"{lam:.2f}",
                "trial_count": agg["trial_count"],
                "mean_realized_obj": agg["mean_realized_obj"],
                "std_realized_obj": agg["std_realized_obj"],
                "mean_expected_obj": agg["mean_expected_obj"],
                "std_expected_obj": agg["std_expected_obj"],
                "time_mean": agg["mean_solve_time_sec"],
                "source_folder": group[0]["source_dir"],
            }
        )
    return out


def rebuild_01_extract_summary() -> list[dict[str, object]]:
    out: list[dict[str, object]] = []
    for dir_path in sorted(p for p in RAW_01_DIR.iterdir() if p.is_dir()):
        rows = aligned([normalize_raw_row(r, source_dir=dir_path.name) for r in load_csv(dir_path / "逐trial样本外结果.csv")])
        agg = aggregate_rows(rows)
        out.append(
            {
                "base_method": "CSAA",
                "k": rows[0]["k"],
                "C_h": "0.10",
                "selection_rule": "fixed parameter; direct solve on aligned 51 tests",
                "trial_count": agg["trial_count"],
                "source_dir": dir_path.name,
                "mean_solve_time_sec": agg["mean_solve_time_sec"],
                "mean_selected_count": agg["mean_selected_count"],
                "mean_ess": agg["mean_ess"],
                "mean_expected_obj": agg["mean_expected_obj"],
                "std_expected_obj": agg["std_expected_obj"],
                "min_expected_obj": agg["min_expected_obj"],
                "p20_expected_obj": agg["p20_expected_obj"],
                "p50_expected_obj": agg["p50_expected_obj"],
                "p75_expected_obj": agg["p75_expected_obj"],
                "p80_expected_obj": agg["p80_expected_obj"],
                "p95_expected_obj": agg["p95_expected_obj"],
                "max_expected_obj": agg["max_expected_obj"],
                "mean_realized_obj": agg["mean_realized_obj"],
                "std_realized_obj": agg["std_realized_obj"],
                "min_realized_obj": agg["min_realized_obj"],
                "p20_realized_obj": agg["p20_realized_obj"],
                "p50_realized_obj": agg["p50_realized_obj"],
                "p75_realized_obj": agg["p75_realized_obj"],
                "p80_realized_obj": agg["p80_realized_obj"],
                "p95_realized_obj": agg["p95_realized_obj"],
                "max_realized_obj": agg["max_realized_obj"],
            }
        )
    out.sort(key=lambda r: int(r["k"]))
    return out


def rebuild_focus_summary(full_rows: list[dict[str, object]]) -> list[dict[str, object]]:
    return [
        row
        for row in full_rows
        if row["base_method"] == "SAA" or str(row["C_h"]) in {"0.10", "0.50"}
    ]


def remove_stale_files() -> None:
    for path in [
        OUT_EXTRACT_05_DIR / "C_h=0.5汇总统计结果.csv",
        OUT_EXTRACT_01_DIR / "CSAA_C_h=0.1_按k汇总.csv",
        OUT_FULL_DIR / "CSAA_C_h灵敏度_完整汇总统计.csv",
        OUT_FOCUS_DIR / "CSAA_C_h=0.1和0.5_汇总统计.csv",
    ]:
        if path.exists():
            path.unlink()


def main() -> None:
    full_rows = rebuild_01_summary()
    focus_rows = rebuild_focus_summary(full_rows)
    extract05_rows = rebuild_05_extract_summary()
    extract01_rows = rebuild_01_extract_summary()

    remove_stale_files()

    write_csv(OUT_FULL_DIR / "CSAA_C_h灵敏度_完整汇总统计.csv", full_rows)
    write_xlsx(OUT_FULL_DIR / "24line_10供应商_CSAA_C_h灵敏度_完整汇总.xlsx", full_rows)

    write_csv(OUT_FOCUS_DIR / "CSAA_C_h=0.1和0.5_汇总统计.csv", focus_rows)
    write_xlsx(OUT_FOCUS_DIR / "24line_10供应商_CSAA_C_h=0.1和0.5_汇总.xlsx", focus_rows)
    write_csv(OUT_FOCUS_DIR / "CSAA_C_h=0.1_按k汇总.csv", extract01_rows)
    write_xlsx(OUT_FOCUS_DIR / "CSAA_C_h=0.1_按k汇总.xlsx", extract01_rows)
    write_csv(OUT_FOCUS_DIR / "CSAA_C_h=0.5_按k汇总.csv", [r for r in full_rows if r["base_method"] == "CSAA" and r["C_h"] == "0.50"])

    write_csv(OUT_EXTRACT_05_DIR / "C_h=0.5汇总统计结果.csv", extract05_rows)
    write_csv(OUT_EXTRACT_05_DIR / "C_h=0.5汇总查看.csv", extract05_rows)
    write_csv(OUT_EXTRACT_05_DIR / "CSAA_C_h=0.5_按k汇总.csv", [r for r in full_rows if r["base_method"] == "CSAA" and r["C_h"] == "0.50"])
    write_csv(OUT_EXTRACT_01_DIR / "CSAA_C_h=0.1_按k汇总.csv", extract01_rows)
    write_xlsx(OUT_EXTRACT_01_DIR / "24line_10供应商_CSAA_C_h=0.1_按k汇总.xlsx", extract01_rows)
    write_xlsx(OUT_EXTRACT_05_DIR / "24line_10供应商_CSAA_C_h=0.5_按k汇总.xlsx", [r for r in full_rows if r["base_method"] == "CSAA" and r["C_h"] == "0.50"])

    print("rebuild done")


if __name__ == "__main__":
    main()
