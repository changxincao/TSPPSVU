from __future__ import annotations

import csv
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from matplotlib.lines import Line2D


ROOT = Path(__file__).resolve().parents[1]
OUT_ROOT = ROOT / "analysis" / "巴西数据分析" / "新版_purchase时间" / "输出"
PLOT07 = OUT_ROOT / "07_lambda灵敏度分析_k=1_代表性C_h"
INPUT_CSV = PLOT07 / "02_k=1_代表性C_h提取结果" / "k=1_lambda灵敏度作图数据.csv"
BASELINE_SUMMARY = (
    OUT_ROOT
    / "03_10供应商基础网格与提取"
    / "按purchase时间_raw网格实验_10供应商"
    / "全部实验配置_4方法_对齐51次样本外结果"
    / "全部实验配置_对齐后51次样本外_summary.csv"
)
OUT_DIR = PLOT07 / "03_lambda灵敏度图"

LAMBDA_ORDER = ["0", "0.01", "0.05", "0.1", "1", "5", "10", "50", "100"]
CH_ORDER = ["0.1", "0.5", "1", "100"]

COLORS = {
    "0.1": "#7A1E6C",
    "0.5": "#C75B12",
    "1": "#1F6E8C",
    "100": "#6B8E23",
}
LINESTYLES = {
    "0.1": "-",
    "0.5": "--",
    "1": "-.",
    "100": ":",
}
MARKERS = {
    "0.1": "o",
    "0.5": "s",
    "1": "^",
    "100": "D",
}


def legend_handles() -> tuple[list[Line2D], list[str]]:
    handles: list[Line2D] = []
    labels: list[str] = []
    for c_h in CH_ORDER:
        handles.append(
            Line2D(
                [0],
                [0],
                color=COLORS[c_h],
                linestyle=LINESTYLES[c_h],
                linewidth=1.9,
                marker=MARKERS[c_h],
                markersize=6.0,
                markerfacecolor=COLORS[c_h],
                markeredgecolor="white",
                markeredgewidth=1.1,
                alpha=0.95,
            )
        )
        labels.append(f"B = {c_h}")
    return handles, labels


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def parse_float(value: str) -> float:
    text = str(value).strip()
    if text == "" or text.lower() == "nan":
        return float("nan")
    return float(text)


def ch_label(ch: str) -> str:
    val = float(ch)
    return str(int(val)) if val.is_integer() else f"{val:g}"


def lambda_label(x: str) -> str:
    val = float(x)
    return str(int(val)) if val.is_integer() else f"{val:g}"


def build_baseline() -> dict[str, float]:
    rows = read_csv(BASELINE_SUMMARY)
    baseline_row = next(r for r in rows if r["method_name"] == "Mean" and r["k1"] == "1")
    return {
        "mean_cost": parse_float(baseline_row["mean_实现目标值"]),
        "std_cost": parse_float(baseline_row["std_实现目标值"]),
        "max_cost": parse_float(baseline_row["max_实现目标值"]),
    }


def build_plot_rows() -> list[dict[str, object]]:
    baseline = build_baseline()
    rows = read_csv(INPUT_CSV)
    out: list[dict[str, object]] = []
    for row in rows:
        c_h = ch_label(row["C_h"])
        lam = "0" if row["method_name"] == "CSAA" else lambda_label(row["lambda"])
        mean_cost = parse_float(row["mean_realized_obj"])
        std_cost = parse_float(row["std_realized_obj"])
        max_cost = parse_float(row["max_realized_obj"])
        out.append(
            {
                "method_name": row["method_name"],
                "C_h": c_h,
                "lambda": lam,
                "imp_mean": (baseline["mean_cost"] - mean_cost) / baseline["mean_cost"] * 100.0,
                "imp_std": (baseline["std_cost"] - std_cost) / baseline["std_cost"] * 100.0,
                "imp_max": (baseline["max_cost"] - max_cost) / baseline["max_cost"] * 100.0,
                "avg_selected": parse_float(row["mean_selected_count"]),
                "mean_realized_obj": mean_cost,
                "std_realized_obj": std_cost,
                "max_realized_obj": max_cost,
            }
        )
    return out


def write_processed_csv(rows: list[dict[str, object]]) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out_csv = OUT_DIR / "k=1_lambda灵敏度作图数据_含相对D改进.csv"
    with out_csv.open("w", encoding="utf-8-sig", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def values_by_ch(rows: list[dict[str, object]], metric: str) -> dict[str, np.ndarray]:
    idx_map = {lam: i for i, lam in enumerate(LAMBDA_ORDER)}
    out: dict[str, np.ndarray] = {}
    for c_h in CH_ORDER:
        arr = np.full(len(LAMBDA_ORDER), np.nan)
        for row in rows:
            if row["C_h"] == c_h and row["lambda"] in idx_map:
                arr[idx_map[row["lambda"]]] = float(row[metric])
        out[c_h] = arr
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


def plot_single(metric: str, ylabel: str, out_name: str, rows: list[dict[str, object]]) -> None:
    metric_map = values_by_ch(rows, metric)
    x = np.arange(len(LAMBDA_ORDER))
    fig, ax = plt.subplots(figsize=(4.9, 3.6))
    for c_h in CH_ORDER:
        ax.plot(
            x,
            metric_map[c_h],
            color=COLORS[c_h],
            linestyle=LINESTYLES[c_h],
            marker=MARKERS[c_h],
            linewidth=1.9,
            markersize=6.0,
            markerfacecolor=COLORS[c_h],
            markeredgecolor="white",
            markeredgewidth=1.1,
            alpha=0.95,
            label=f"B = {c_h}",
        )
    ax.set_xlabel(r"$\lambda$")
    ax.set_ylabel(ylabel)
    ax.set_xticks(x)
    ax.set_xticklabels(LAMBDA_ORDER)
    ax.grid(True, linestyle="--", alpha=0.35)
    handles, labels = legend_handles()
    ax.legend(handles, labels, frameon=False, loc="lower right", handlelength=2.6)
    fig.tight_layout()
    fig.savefig(OUT_DIR / f"{out_name}.pdf", bbox_inches="tight")
    fig.savefig(OUT_DIR / f"{out_name}.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def plot_combined(rows: list[dict[str, object]]) -> None:
    metrics = [
        ("imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)"),
        ("imp_std", r"$\Delta_{\mathrm{std}}$ (\%)"),
        ("imp_max", r"$\Delta_{\max}$ (\%)"),
        ("avg_selected", "ANC"),
    ]
    x = np.arange(len(LAMBDA_ORDER))
    fig, axes = plt.subplots(2, 2, figsize=(10.6, 7.6))
    axes = axes.flatten()
    for ax, (metric, ylabel) in zip(axes, metrics):
        metric_map = values_by_ch(rows, metric)
        for c_h in CH_ORDER:
            ax.plot(
                x,
                metric_map[c_h],
                color=COLORS[c_h],
                linestyle=LINESTYLES[c_h],
                marker=MARKERS[c_h],
                linewidth=1.9,
                markersize=5.8,
                markerfacecolor=COLORS[c_h],
                markeredgecolor="white",
                markeredgewidth=1.0,
                alpha=0.95,
                label=f"B = {c_h}",
            )
        ax.set_xlabel(r"$\lambda$")
        ax.set_ylabel(ylabel)
        ax.set_xticks(x)
        ax.set_xticklabels(LAMBDA_ORDER)
        ax.grid(True, linestyle="--", alpha=0.35)
    handles, labels = legend_handles()
    fig.legend(handles, labels, loc="upper center", ncol=4, frameon=False, handlelength=2.6)
    fig.tight_layout(rect=[0, 0, 1, 0.94])
    fig.savefig(OUT_DIR / "sens_lambda_combined.pdf", bbox_inches="tight")
    fig.savefig(OUT_DIR / "sens_lambda_combined.png", dpi=600, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    set_style()
    rows = build_plot_rows()
    write_processed_csv(rows)
    plot_single("imp_mean", r"$\Delta_{\mathrm{cost}}$ (\%)", "sens_lambda_mean", rows)
    plot_single("imp_std", r"$\Delta_{\mathrm{std}}$ (\%)", "sens_lambda_std", rows)
    plot_single("imp_max", r"$\Delta_{\max}$ (\%)", "sens_lambda_max", rows)
    plot_single("avg_selected", "ANC", "sens_lambda_selected", rows)
    plot_combined(rows)
    print(f"saved figures to {OUT_DIR}")


if __name__ == "__main__":
    main()
