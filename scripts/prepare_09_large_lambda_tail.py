from __future__ import annotations

import csv
import math
from pathlib import Path
from statistics import mean, pstdev

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.lines import Line2D


ROOT = Path(__file__).resolve().parents[1]
OUT_ROOT = ROOT / "analysis" / "巴西数据分析" / "新版_purchase时间" / "输出"
TEST_ROOT = OUT_ROOT / "09_lambda超大值延伸分析_k=1"
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

LAMBDA_ORDER = ["0", "0.01", "0.05", "0.1", "1", "5", "10", "50", "100", "300", "500", "1000", "10000", "50000", "100000"]
CH_ORDER = ["0.1", "0.5", "1", "100"]
COLORS = {"0.1": "#7A1E6C", "0.5": "#C75B12", "1": "#1F6E8C", "100": "#6B8E23"}
LINESTYLES = {"0.1": "-", "0.5": "--", "1": "-.", "100": ":"}
MARKERS = {"0.1": "o", "0.5": "s", "1": "^", "100": "D"}
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


def build_baseline() -> dict[str, float]:
    rows = read_csv(BASELINE_SUMMARY)
    baseline_row = next(r for r in rows if r["method_name"] == "Mean" and r["k1"] == "1")
    return {
        "mean_cost": parse_float(baseline_row["mean_实现目标值"]),
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
    parts = dir_path.name.split("_")
    b = parts[4].replace("C", "")
    lam = dir_path.name.split("lambda", 1)[1]
    return {
        "method_name": "RCSAA",
        "B": str(float(b)).rstrip("0").rstrip("."),
        "lambda": str(int(round(float(lam)))) if float(lam).is_integer() else str(float(lam)),
        "trial_count": len(aligned),
        "mean_realized_obj": mean(realized),
        "std_realized_obj": std_value,
        "max_realized_obj": realized[-1],
        "mean_selected_count": mean(parse_float(r["selectedCount"]) for r in aligned),
        "source": dir_path.name,
    }


def current_rows() -> list[dict[str, object]]:
    rows = read_csv(CURRENT_PLOT_CSV)
    out: list[dict[str, object]] = []
    for row in rows:
        b = row["C_h"]
        if b not in CH_ORDER:
            continue
        lam = "0" if row["method_name"] == "CSAA" else row["lambda"]
        out.append(
            {
                "method_name": row["method_name"],
                "B": b,
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
    for dir_path in sorted(RAW_ROOT.glob("RCSAA_W50_k1=1_raw_C*_lambda*")):
        if not (dir_path / "global_summary.csv").exists():
            continue
        rows.append(summarize_global_trials(dir_path))
    return sorted(rows, key=lambda r: (CH_ORDER.index(str(r["B"])), float(str(r["lambda"]))))


def add_improvements(rows: list[dict[str, object]]) -> list[dict[str, object]]:
    baseline = build_baseline()
    out = []
    for row in rows:
        row = dict(row)
        row["imp_mean"] = (baseline["mean_cost"] - float(row["mean_realized_obj"])) / baseline["mean_cost"] * 100.0
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


def legend_handles() -> tuple[list[Line2D], list[str]]:
    handles: list[Line2D] = []
    labels: list[str] = []
    for b in CH_ORDER:
        handles.append(
            Line2D(
                [0],
                [0],
                color=COLORS[b],
                linestyle=LINESTYLES[b],
                linewidth=1.9,
                marker=MARKERS[b],
                markersize=6.0,
                markerfacecolor=COLORS[b],
                markeredgecolor="white",
                markeredgewidth=1.1,
                alpha=0.95,
            )
        )
        labels.append(f"B = {b}")
    return handles, labels


def values_by_b(rows: list[dict[str, object]]) -> dict[str, np.ndarray]:
    idx_map = {lam: i for i, lam in enumerate(LAMBDA_ORDER)}
    out: dict[str, np.ndarray] = {}
    for b in CH_ORDER:
        arr = np.full(len(LAMBDA_ORDER), np.nan)
        for row in rows:
            if row["B"] == b and row["lambda"] in idx_map:
                arr[idx_map[str(row["lambda"])]] = float(row["imp_mean"])
        out[b] = arr
    return out


def plot_mean(rows: list[dict[str, object]]) -> None:
    x = np.arange(len(LAMBDA_ORDER))
    metric_map = values_by_b(rows)
    fig, ax = plt.subplots(figsize=(8.8, 5.6))
    for b in CH_ORDER:
        ax.plot(
            x,
            metric_map[b],
            color=COLORS[b],
            linestyle=LINESTYLES[b],
            marker=MARKERS[b],
            linewidth=1.9,
            markersize=6.0,
            markerfacecolor=COLORS[b],
            markeredgecolor="white",
            markeredgewidth=1.0,
            alpha=0.95,
            label=f"B = {b}",
        )
    ax.set_xlabel(r"$\lambda$")
    ax.set_ylabel(r"$\Delta_{\mathrm{cost}}$ (\%)")
    ax.set_xticks(x)
    ax.set_xticklabels(LAMBDA_ORDER, rotation=0)
    ax.grid(True, linestyle="--", alpha=0.35)
    handles, labels = legend_handles()
    ax.legend(handles, labels, frameon=False, loc="lower right", handlelength=2.6)
    fig.tight_layout()
    fig.savefig(FIG_DIR / "tail_lambda_mean.pdf", bbox_inches="tight")
    fig.savefig(FIG_DIR / "tail_lambda_mean.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    EXTRACT_DIR.mkdir(parents=True, exist_ok=True)
    FIG_DIR.mkdir(parents=True, exist_ok=True)
    set_style()
    rows = add_improvements(build_rows())
    fieldnames = sorted({key for row in rows for key in row.keys()})
    write_csv(EXTRACT_DIR / "k=1_lambda到100000_mean_cost_summary.csv", rows, fieldnames)
    plot_mean(rows)
    print(f"prepared test9 outputs under {TEST_ROOT}")


if __name__ == "__main__":
    main()
