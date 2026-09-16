from __future__ import annotations

import csv
import math
from pathlib import Path
from statistics import mean, pstdev

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np


ROOT = Path(__file__).resolve().parents[1]
OUT_ROOT = ROOT / "analysis" / "巴西数据分析" / "新版_purchase时间" / "输出"
TEST_ROOT = OUT_ROOT / "08_额外测试_极小权重替代与大lambda扩展" / "01_极小权重替代为1e-6并重标化_仅测试"
RAW_ROOT = TEST_ROOT / "01_原始求解目录"
EXTRACT_DIR = TEST_ROOT / "02_整理结果"
FIG_DIR = TEST_ROOT / "03_图"
BASELINE_SUMMARY = (
    OUT_ROOT
    / "03_10供应商基础网格与提取"
    / "按purchase时间_raw网格实验_10供应商"
    / "全部实验配置_4方法_对齐51次样本外结果"
    / "全部实验配置_对齐后51次样本外_summary.csv"
)
CURRENT_PLOT_CSV = (
    OUT_ROOT
    / "07_lambda灵敏度分析_k=1_代表性C_h"
    / "03_lambda灵敏度图"
    / "k=1_lambda灵敏度作图数据_含相对D改进.csv"
)

LAMBDA_ORDER = ["0", "0.01", "0.05", "0.1", "1", "5", "10", "50", "100"]
SERIES_ORDER = ["current", "tiny-floor test"]
COLORS = {"current": "#7A1E6C", "tiny-floor test": "#0F766E"}
LINESTYLES = {"current": "-", "tiny-floor test": "--"}
MARKERS = {"current": "o", "tiny-floor test": "s"}
MIN_PERIOD = 53
MAX_PERIOD = 103


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


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


def build_baseline() -> dict[str, float]:
    rows = read_csv(BASELINE_SUMMARY)
    baseline_row = next(r for r in rows if r["method_name"] == "Mean" and r["k1"] == "1")
    return {
        "mean_cost": parse_float(baseline_row["mean_实现目标值"]),
        "std_cost": parse_float(baseline_row["std_实现目标值"]),
        "max_cost": parse_float(baseline_row["max_实现目标值"]),
    }


def summarize_global_trials(dir_path: Path) -> dict[str, object]:
    rows = read_csv(dir_path / "global_trials.csv")
    dedup: dict[tuple[str, str], dict[str, str]] = {}
    for row in rows:
        dedup[(row["trialId"], row["testIdx"])] = row

    aligned = []
    for row in dedup.values():
        actual_period = int(row["testIdx"]) + int(row["k1Lag"])
        if MIN_PERIOD <= actual_period <= MAX_PERIOD:
            aligned.append(row)

    if len(aligned) != 51:
        raise RuntimeError(f"{dir_path.name} aligned row count expected 51, got {len(aligned)}")

    realized = sorted(parse_float(r["realizedObj"]) for r in aligned)
    std_value = pstdev(realized) if len(realized) > 1 else 0.0
    return {
        "series": "tiny-floor test",
        "method_name": "RCSAA",
        "B": "0.1",
        "lambda": dir_path.name.split("lambda", 1)[1].split("_", 1)[0],
        "trial_count": len(aligned),
        "mean_realized_obj": mean(realized),
        "std_realized_obj": std_value,
        "max_realized_obj": realized[-1],
        "mean_selected_count": mean(parse_float(r["selectedCount"]) for r in aligned),
        "p20_realized_obj": percentile(realized, 0.2),
        "p95_realized_obj": percentile(realized, 0.95),
        "source": dir_path.name,
    }


def current_rows() -> list[dict[str, object]]:
    rows = read_csv(CURRENT_PLOT_CSV)
    out: list[dict[str, object]] = []
    for row in rows:
        if row["C_h"] != "0.1":
            continue
        lam = "0" if row["method_name"] == "CSAA" else row["lambda"]
        out.append(
            {
                "series": "current",
                "method_name": row["method_name"],
                "B": "0.1",
                "lambda": lam,
                "trial_count": 51,
                "mean_realized_obj": parse_float(row["mean_realized_obj"]),
                "std_realized_obj": parse_float(row["std_realized_obj"]),
                "max_realized_obj": parse_float(row["max_realized_obj"]),
                "mean_selected_count": parse_float(row["avg_selected"]),
                "source": "07_current",
            }
        )
    return out


def build_rows() -> list[dict[str, object]]:
    rows = current_rows()
    for dir_path in sorted(RAW_ROOT.glob("RCSAA_W50_k1=1_raw_C0.10_lambda*_tinyFloor1e-6_renorm")):
        if not (dir_path / "global_summary.csv").exists():
            continue
        rows.append(summarize_global_trials(dir_path))
    return sorted(rows, key=lambda r: (SERIES_ORDER.index(str(r["series"])), float(str(r["lambda"]))))


def add_improvements(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    baseline = build_baseline()
    out = []
    for row in rows:
        row = dict(row)
        row["imp_mean"] = (baseline["mean_cost"] - float(row["mean_realized_obj"])) / baseline["mean_cost"] * 100.0
        row["imp_std"] = (baseline["std_cost"] - float(row["std_realized_obj"])) / baseline["std_cost"] * 100.0
        row["imp_max"] = (baseline["max_cost"] - float(row["max_realized_obj"])) / baseline["max_cost"] * 100.0
        out.append(row)
    return out


def set_style() -> None:
    plt.rcParams.update(
        {
            "font.family": "Times New Roman",
            "mathtext.fontset": "stix",
            "font.size": 11,
            "axes.labelsize": 11,
            "legend.fontsize": 10,
            "xtick.labelsize": 10,
            "ytick.labelsize": 10,
            "axes.spines.top": False,
            "axes.spines.right": False,
            "axes.linewidth": 0.8,
            "grid.linewidth": 0.5,
            "pdf.fonttype": 42,
            "ps.fonttype": 42,
        }
    )


def series_values(rows: list[dict[str, object]], metric: str) -> dict[str, np.ndarray]:
    idx_map = {lam: i for i, lam in enumerate(LAMBDA_ORDER)}
    out: dict[str, np.ndarray] = {}
    for series in SERIES_ORDER:
        arr = np.full(len(LAMBDA_ORDER), np.nan)
        for row in rows:
            if row["series"] == series and row["lambda"] in idx_map:
                arr[idx_map[str(row["lambda"])] ] = float(row[metric])
        out[series] = arr
    return out


def plot_single(rows: list[dict[str, object]], metric: str, ylabel: str, out_name: str) -> None:
    x = np.arange(len(LAMBDA_ORDER))
    metric_map = series_values(rows, metric)
    fig, ax = plt.subplots(figsize=(4.9, 3.6))
    for series in SERIES_ORDER:
        ax.plot(
            x,
            metric_map[series],
            color=COLORS[series],
            linestyle=LINESTYLES[series],
            marker=MARKERS[series],
            linewidth=1.9,
            markersize=6.0,
            markerfacecolor=COLORS[series],
            markeredgecolor="white",
            markeredgewidth=1.0,
            alpha=0.95,
            label=series,
        )
    ax.set_xlabel(r"$\lambda$")
    ax.set_ylabel(ylabel)
    ax.set_xticks(x)
    ax.set_xticklabels(LAMBDA_ORDER)
    ax.grid(True, linestyle="--", alpha=0.35)
    ax.legend(frameon=False, loc="lower right", handlelength=2.6)
    fig.tight_layout()
    fig.savefig(FIG_DIR / f"{out_name}.pdf", bbox_inches="tight")
    fig.savefig(FIG_DIR / f"{out_name}.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def plot_combined(rows: list[dict[str, object]]) -> None:
    specs = [
        ("imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)"),
        ("imp_std", r"$\Delta_{\mathrm{std}}$ (\%)"),
        ("imp_max", r"$\Delta_{\max}$ (\%)"),
        ("mean_selected_count", "ANC"),
    ]
    x = np.arange(len(LAMBDA_ORDER))
    fig, axes = plt.subplots(2, 2, figsize=(10.2, 7.2))
    axes = axes.flatten()
    for ax, (metric, ylabel) in zip(axes, specs):
        metric_map = series_values(rows, metric)
        for series in SERIES_ORDER:
            ax.plot(
                x,
                metric_map[series],
                color=COLORS[series],
                linestyle=LINESTYLES[series],
                marker=MARKERS[series],
                linewidth=1.9,
                markersize=6.0,
                markerfacecolor=COLORS[series],
                markeredgecolor="white",
                markeredgewidth=1.0,
                alpha=0.95,
                label=series,
            )
        ax.set_xlabel(r"$\lambda$")
        ax.set_ylabel(ylabel)
        ax.set_xticks(x)
        ax.set_xticklabels(LAMBDA_ORDER)
        ax.grid(True, linestyle="--", alpha=0.35)
        ax.legend(frameon=False, loc="lower right", handlelength=2.6)
    fig.tight_layout()
    fig.savefig(FIG_DIR / "test1_combined.pdf", bbox_inches="tight")
    fig.savefig(FIG_DIR / "test1_combined.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    EXTRACT_DIR.mkdir(parents=True, exist_ok=True)
    FIG_DIR.mkdir(parents=True, exist_ok=True)
    set_style()
    rows = add_improvements(build_rows())
    fieldnames = sorted({key for row in rows for key in row.keys()})
    write_csv(EXTRACT_DIR / "B=0.1_当前结果_vs_极小权重替代测试_summary.csv", rows, fieldnames)
    plot_single(rows, "imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)", "test1_mean")
    plot_single(rows, "imp_std", r"$\Delta_{\mathrm{std}}$ (\%)", "test1_std")
    plot_single(rows, "imp_max", r"$\Delta_{\max}$ (\%)", "test1_max")
    plot_single(rows, "mean_selected_count", "ANC", "test1_selected")
    plot_combined(rows)
    print(f"prepared test1 outputs under {TEST_ROOT}")


if __name__ == "__main__":
    main()
