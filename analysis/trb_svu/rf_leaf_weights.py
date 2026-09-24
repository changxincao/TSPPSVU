"""Experiment 1 RF-CSAA: multi-output forest, normalized same-leaf weights.

Input has n,k,J on line 1; n lines of k contexts then J demands; one query line.
All RF settings are explicit and fixed; no OOS-based hyperparameter tuning.
"""

import csv
import sys

import numpy as np
from sklearn.ensemble import RandomForestRegressor


def main():
    input_path, output_path, seed, tree_count, min_samples_leaf = sys.argv[1:6]
    min_samples_leaf = int(min_samples_leaf)
    if min_samples_leaf < 1:
        raise ValueError("min_samples_leaf must be positive")
    with open(input_path, newline="", encoding="utf-8") as stream:
        reader = csv.reader(stream)
        n, dimension, lanes = map(int, next(reader))
        rows = [list(map(float, next(reader))) for _ in range(n)]
        query = list(map(float, next(reader)))
    values = np.asarray(rows, dtype=float)
    x = values[:, :dimension]
    y = values[:, dimension:]
    if x.shape != (n, dimension) or y.shape != (n, lanes) or len(query) != dimension:
        raise ValueError("RF input dimension mismatch")
    forest = RandomForestRegressor(
        n_estimators=int(tree_count),
        criterion="squared_error",
        max_depth=None,
        min_samples_split=2,
        min_samples_leaf=min_samples_leaf,
        max_features=1.0,
        bootstrap=True,
        random_state=int(seed),
        n_jobs=1,
    )
    forest.fit(x, y)
    training_leaves = forest.apply(x)
    query_leaves = forest.apply(np.asarray(query, dtype=float).reshape(1, -1))[0]
    weights = np.zeros(n)
    for tree in range(int(tree_count)):
        matches = training_leaves[:, tree] == query_leaves[tree]
        count = int(matches.sum())
        if count == 0:
            raise AssertionError("Query leaf has no training observation")
        weights[matches] += 1.0 / (int(tree_count) * count)
    weights /= weights.sum()
    with open(output_path, "w", encoding="utf-8") as stream:
        stream.write(",".join(format(float(value), ".17g") for value in weights))


if __name__ == "__main__":
    main()
