from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd


PROJECT = Path(__file__).resolve().parents[1]
RESULTS = PROJECT / "analysis" / "TRB_reviewer_revision"
ROOTS = [
    "350_olist_v2_hmax_lambda_figure_pilot_market0_20260819",
    "353_olist_v2_hmax_lambda_figure_pilot_market1_retry_20260819",
    "352_olist_v2_hmax_lambda_figure_pilot_market2_20260819",
]
OUTPUT = RESULTS / "354_olist_v2_hmax_lambda_figure_pilot_summary_v2_20260819"
LAMBDA_ORDER = [
    0.0, 0.01, 0.05, 0.1, 1.0, 5.0, 10.0,
    50.0, 100.0, 300.0, 1000.0, 10000.0, 50000.0, 100000.0,
]
B_ORDER = [0.1, 0.5, 1.0, 100.0]


def label_number(value: float) -> str:
    return str(int(value)) if value >= 1 and value.is_integer() else f"{value:g}"


def main() -> None:
    if OUTPUT.exists() and any(OUTPUT.iterdir()):
        raise RuntimeError(f"Output directory must be empty: {OUTPUT}")
    OUTPUT.mkdir(parents=True, exist_ok=True)

    frames = []
    for market, root in enumerate(ROOTS):
        frame = pd.read_csv(RESULTS / root / "lambda_figure_results.csv")
        frame.insert(0, "market", market)
        frames.append(frame)
    raw = pd.concat(frames, ignore_index=True)

    expected = pd.MultiIndex.from_product(
        [range(3), B_ORDER, LAMBDA_ORDER], names=["market", "B", "lambda"]
    )
    actual = pd.MultiIndex.from_frame(raw[["market", "B", "lambda"]])
    if len(raw) != 168 or actual.has_duplicates or set(actual) != set(expected):
        raise RuntimeError("Incomplete or duplicate market/B/lambda grid")
    if not raw["certifiedOptimal"].all() or raw["relativeGap"].max() > 1.0e-4:
        raise RuntimeError("Uncertified solve or relative gap above tolerance")

    summary = (
        raw.groupby(["B", "lambda"], as_index=False)
        .agg(
            markets=("market", "nunique"),
            averageSelectedCarriers=("selectedCount", "mean"),
            meanImprovementPct=("meanImprovementPct", "mean"),
            sdImprovementPct=("sdImprovementPct", "mean"),
            maxImprovementPct=("maxImprovementPct", "mean"),
            meanOosCost=("meanOosCost", "mean"),
            sdOosCost=("sdOosCost", "mean"),
            maxOosCost=("maxOosCost", "mean"),
            meanOptimizerTimeSec=("optimizerTimeSec", "mean"),
            maxRelativeGap=("relativeGap", "max"),
        )
        .sort_values(["B", "lambda"])
    )
    raw.to_csv(OUTPUT / "individual_market_results.csv", index=False)
    summary.to_csv(OUTPUT / "three_market_summary.csv", index=False)

    x = list(range(len(LAMBDA_ORDER)))
    labels = [label_number(value) for value in LAMBDA_ORDER]
    colors = ["#1f77b4", "#ff7f0e", "#2ca02c", "#9467bd"]
    metrics = [
        ("meanImprovementPct", "Relative improvement in mean cost (%)"),
        ("sdImprovementPct", "Relative improvement in standard deviation (%)"),
        ("maxImprovementPct", "Relative improvement in worst-case cost (%)"),
    ]
    fig, axes = plt.subplots(1, 3, figsize=(16, 4.6), sharex=True)
    for axis, (column, title) in zip(axes, metrics):
        for b, color in zip(B_ORDER, colors):
            part = summary[summary["B"] == b].set_index("lambda").loc[LAMBDA_ORDER]
            axis.plot(x, part[column], marker="o", linewidth=1.7,
                      markersize=4, label=f"B={label_number(b)}", color=color)
        axis.axhline(0.0, color="black", linewidth=0.7)
        axis.set_title(title)
        axis.set_xticks(x)
        axis.set_xticklabels(labels, rotation=55, ha="right")
        axis.set_xlabel("Regularization parameter lambda")
        axis.grid(alpha=0.25)
    axes[0].legend(frameon=False)
    fig.tight_layout()
    fig.savefig(OUTPUT / "lambda_sensitivity_performance.png", dpi=220)
    plt.close(fig)

    fig, axis = plt.subplots(figsize=(9.2, 4.8))
    for b, color in zip(B_ORDER, colors):
        part = summary[summary["B"] == b].set_index("lambda").loc[LAMBDA_ORDER]
        axis.plot(x, part["averageSelectedCarriers"], marker="o",
                  linewidth=1.8, markersize=4.5,
                  label=f"B={label_number(b)}", color=color)
    axis.axhline(21.0, color="black", linestyle="--", linewidth=0.9,
                 label="Carrier upper bound (21)")
    axis.set_xticks(x)
    axis.set_xticklabels(labels, rotation=55, ha="right")
    axis.set_xlabel("Regularization parameter lambda")
    axis.set_ylabel("Average number of selected carriers")
    axis.grid(alpha=0.25)
    axis.legend(frameon=False, ncol=2)
    fig.tight_layout()
    fig.savefig(OUTPUT / "lambda_sensitivity_anc.png", dpi=220)
    plt.close(fig)

    monotonic = []
    for b in B_ORDER:
        values = summary[summary["B"] == b].set_index("lambda").loc[
            LAMBDA_ORDER, "averageSelectedCarriers"
        ].to_numpy()
        monotonic.append(f"B={label_number(b)} ANC monotone={bool((values[1:] >= values[:-1]).all())}")
    validation = [
        "PASSED",
        "markets=3",
        "grid_rows=168",
        "summary_rows=56",
        f"uncertified={int((~raw['certifiedOptimal']).sum())}",
        f"max_relative_gap={raw['relativeGap'].max():.17g}",
        *monotonic,
    ]
    (OUTPUT / "validation.txt").write_text("\n".join(validation) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
