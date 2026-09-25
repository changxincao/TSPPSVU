"""Solve one PCM-DRO procurement model with RSOME/MOSEK.

The ambiguity set fixes lane means and bounds lane variances.  The aggregate
total-demand variance bound is optional.  Recourse uses RSOME's lifted affine
decision rules; this is not unrestricted fully adaptive two-stage recourse.
"""

from __future__ import annotations

import argparse
import csv
import json
import time
from pathlib import Path

import numpy as np
from rsome import E, dro, square
import msk_feasible_solver as msk


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def read_matrix(path: Path) -> np.ndarray:
    with path.open(encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream))
    return np.asarray([[float(value) for value in row] for row in rows], dtype=float)


def solve(root: Path) -> dict[str, object]:
    meta = read_json(root / "meta.json")
    lanes = read_json(root / "lanes.json")
    carriers = read_json(root / "carriers.json")
    rate = read_matrix(root / "rate.csv")
    lane_capacity = read_matrix(root / "lane_capacity.csv")
    eligibility_values = read_matrix(root / "eligibility.csv")

    i_count = int(meta["carriers"])
    j_count = int(meta["lanes"])
    expected_shape = (i_count, j_count)
    if (rate.shape != expected_shape or lane_capacity.shape != expected_shape
            or eligibility_values.shape != expected_shape):
        raise ValueError("Carrier-lane matrix dimension mismatch")
    if np.any((eligibility_values != 0.0) & (eligibility_values != 1.0)):
        raise ValueError("Eligibility matrix must contain only zero or one")
    eligible = eligibility_values.astype(bool)
    pair_carrier, pair_lane = np.nonzero(eligible)
    pair_count = pair_carrier.size
    if pair_count == 0:
        raise ValueError("PCM instance has no eligible carrier-lane pair")
    if np.any(lane_capacity[~eligible] != 0.0) or np.any(rate[~eligible] != 0.0):
        raise ValueError("Ineligible PCM pairs must have zero capacity and rate")

    mean = np.asarray(lanes["mean"], dtype=float)
    variance = np.asarray(lanes["variance_bound"], dtype=float)
    upper = np.asarray(lanes["support_upper"], dtype=float)
    spot_cost = np.asarray(lanes["spot_cost"], dtype=float)
    include_total_variance = bool(meta.get("include_total_variance", True))
    total_variance = float(meta["total_variance_bound"])
    total_capacity = np.asarray(carriers["total_capacity"], dtype=float)
    mqc = np.asarray(carriers["mqc"], dtype=float)
    penalty = np.asarray(carriers["penalty"], dtype=float)

    if any(vector.size != j_count for vector in (mean, variance, upper, spot_cost)):
        raise ValueError("Lane-vector dimension mismatch")
    if any(vector.size != i_count for vector in (total_capacity, mqc, penalty)):
        raise ValueError("Carrier-vector dimension mismatch")
    if np.any(mean < 0) or np.any(variance < 0) or np.any(upper < mean):
        raise ValueError("Invalid PCM lane moments or support")
    if total_variance < 0:
        raise ValueError("Negative total-demand variance bound")

    # Comparable reported time: RSOME model construction/reformulation plus MOSEK solve.
    # Input-file parsing above and Java/Python process overhead are deliberately excluded.
    started = time.perf_counter()
    model = dro.Model()
    policy = str(meta.get("policy", "demand_and_second_moment_lift_affine"))
    if policy not in {"demand_affine", "demand_and_second_moment_lift_affine"}:
        raise ValueError(f"Unsupported PCM policy: {policy}")
    selected = model.dvar(i_count, vtype="B", name="carrier_selected")
    demand = model.rvar(j_count, name="demand")
    lift_dimension = j_count + (1 if include_total_variance else 0)
    lift = model.rvar(lift_dimension, name="second_moment_lift")
    # Create policies only for eligible carrier-lane pairs.  The previous
    # dense I-by-J array was mathematically equivalent, but every ineligible
    # zero flow still generated a full set of lifted-affine coefficients and
    # robust-dual constraints.
    flow = model.dvar(pair_count, name="eligible_flow")
    spot = model.dvar(j_count, name="spot")
    shortfall = model.dvar(i_count, name="shortfall")

    for decision in (flow, spot, shortfall):
        decision.adapt(demand)
        if policy == "demand_and_second_moment_lift_affine":
            decision.adapt(lift)

    ambiguity = model.ambiguity()
    support_constraints = [
        demand >= 0,
        demand <= upper,
        square(demand - mean) <= lift[:j_count],
    ]
    moment_bounds = variance
    if include_total_variance:
        support_constraints.append(square((demand - mean).sum()) <= lift[j_count])
        moment_bounds = np.r_[variance, total_variance]
    ambiguity.suppset(*support_constraints)
    ambiguity.exptset(
        E(demand) == mean,
        E(lift) <= moment_bounds,
    )

    lane_incidence = np.zeros((j_count, pair_count))
    lane_incidence[pair_lane, np.arange(pair_count)] = 1.0
    carrier_incidence = np.zeros((i_count, pair_count))
    carrier_incidence[pair_carrier, np.arange(pair_count)] = 1.0
    pair_rate = rate[pair_carrier, pair_lane]
    pair_capacity = lane_capacity[pair_carrier, pair_lane]

    cost = pair_rate @ flow + spot_cost @ spot + penalty @ shortfall
    model.minsup(E(cost), ambiguity)
    carrier_flow = carrier_incidence @ flow
    model.st(lane_incidence @ flow + spot == demand)
    model.st(carrier_flow <= total_capacity * selected)
    model.st(carrier_flow >= mqc * selected - shortfall)
    model.st(flow <= pair_capacity * selected[pair_carrier])
    model.st(flow >= 0, spot >= 0, shortfall >= 0)
    model.st(selected.sum() >= int(meta["alpha"]), selected.sum() <= int(meta["beta"]))

    reformulation = model.do_math()
    scalar_variable_count = int(reformulation.linear.shape[1])
    scalar_constraint_count = int(reformulation.linear.shape[0])
    cone_count = int(len(reformulation.qmat) + len(reformulation.xmat) + len(reformulation.lmi))
    print("PCM_REFORMULATION_SIZE "
          f"scalarVariables={scalar_variable_count} "
          f"scalarLinearConstraints={scalar_constraint_count} coneBlocks={cone_count}",
          flush=True)

    model.solve(
        msk,
        display=True,
        log=True,
        params={
            "mioMaxTime": float(meta["time_limit_seconds"]),
            "numThreads": int(meta["threads"]),
        },
    )
    model_and_solve_seconds = time.perf_counter() - started
    status = str(model.solution.status)
    if status not in {"Optimal", "Feasible"}:
        raise RuntimeError(f"PCM solve returned no usable incumbent: {status}")
    y = np.asarray(selected.get(), dtype=float)
    integrality_error = float(np.max(np.abs(y - np.rint(y))))
    if integrality_error > 1e-5:
        raise RuntimeError(f"PCM binary integrality error: {integrality_error}")
    result = {
        "status": ("OPTIMAL_PCM_LIFTED_AFFINE_APPROXIMATION"
                   if status == "Optimal"
                   else "TIME_LIMIT_FEASIBLE_PCM_LIFTED_AFFINE_APPROXIMATION"),
        "objective": float(model.get()),
        "best_bound": float(model.solution.best_bound),
        "relative_gap": float(model.solution.relative_gap),
        "certified_optimal": status == "Optimal",
        "solver_seconds": float(model.solution.time),
        "model_and_solve_seconds": model_and_solve_seconds,
        "selected": np.rint(y).astype(int).tolist(),
        "selected_count": int(np.rint(y).sum()),
        "eligible_pair_count": int(pair_count),
        "scalar_variable_count": scalar_variable_count,
        "scalar_constraint_count": scalar_constraint_count,
        "cone_count": cone_count,
        "integrality_error": integrality_error,
        "policy": policy,
        "include_total_variance": include_total_variance,
        "exactness": ("optimal_for_lifted_affine_approximation_not_unrestricted_recourse"
                      if policy == "demand_and_second_moment_lift_affine"
                      else "optimal_for_demand_affine_approximation_not_unrestricted_recourse"),
    }
    (root / "solution.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_directory", type=Path)
    args = parser.parse_args()
    print(json.dumps(solve(args.input_directory.resolve())))


if __name__ == "__main__":
    main()
