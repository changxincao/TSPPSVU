"""Small deterministic checks; no optimization or remote access."""
import csv
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import numpy as np

import bootstrap_main_decision_improvements as ci
import compare_main_grid_completion as main_table


def panel():
    return [dict(rep=r, query=q, method=m, query_sha256=f"{r}-{q}",
                 oos_pair_sha256=f"draws-{r}-{q}", oos_count=1000,
                 **{k: 100.0 if m == "SAA-All" else 90.0 for k in ci.METRICS})
            for r in range(1, 6) for q in range(40) for m in ("SAA-All",) + ci.METHODS]


class BootstrapChecks(unittest.TestCase):
    def test_pairing_missing_duplicate_and_denominator(self):
        data = panel()
        keys, improvements = ci.paired_improvements(data)
        self.assertEqual(len(keys), 200)
        np.testing.assert_array_equal(improvements, 10.0)
        for invalid in (data[:-1], data + [data[0]],
                        [dict(x, oos_pair_sha256="wrong") if n == 1 else x for n, x in enumerate(data)],
                        [dict(x, mean=0) if n == 0 else x for n, x in enumerate(data)]):
            with self.assertRaises(ValueError):
                ci.paired_improvements(invalid)

    def test_shared_indices_reproducible_and_percentile(self):
        values = np.arange(200.0)[:, None, None] * np.ones((1, 5, 4))
        indices, means, intervals = ci.bootstrap(values, repeats=1000)
        second = ci.bootstrap(values, repeats=1000)
        for a, b in zip((indices, means, intervals), second):
            np.testing.assert_array_equal(a, b)
        self.assertEqual(indices.shape, (1000, 200))
        np.testing.assert_allclose(means[:, 0, 0], values[:,0,0][indices].mean(axis=1))
        np.testing.assert_array_equal(means[:, 0, 0], means[:, 4, 3])
        np.testing.assert_array_equal(intervals, np.quantile(means, [.025, .975], axis=0))

    def test_main_table_uses_mean_improvement_not_ratio_of_means(self):
        draws = [dict(sample_id=str(n), total_demand="100") for n in range(1000)]

        def fake_rows(path):
            if path.name == "draws.csv":
                return draws
            if path.name == "context_candidate.csv":
                return [dict(validation_selected_min_leaf="1", validation_selected_B="1", validation_cost="1")]
            method = next(p for p in path.parts if p in ("D", "SAA-All") + main_table.CSAA)
            query = int(next(p for p in path.parts if p.startswith("query_"))[6:])
            value = 100.0 if query % 2 == 0 else 1000.0
            if method != "SAA-All" and query % 2 == 0:
                value *= .9
            return [dict(method=method, solve_status="Optimal", certified_optimal="true", solve_gap="0",
                         **{k: str(value) for k in main_table.METRICS})]

        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "output"
            output.mkdir()
            with patch.object(main_table, "rows", side_effect=fake_rows), \
                    patch.object(main_table, "metadata", return_value={"querySha256": "same"}), \
                    patch.object(Path, "is_file", return_value=True):
                main_table.main(Path(temp) / "baseline", output, csaa_only=True)
            with (output / "comparison/overall.csv").open(newline="") as stream:
                overall = list(csv.DictReader(stream))
            rf = next(r for r in overall if r["method"] == "RF-CSAA")
            self.assertEqual(float(rf["mean_vs_saa_pct"]), 5.0)
            self.assertNotAlmostEqual(float(rf["mean_vs_saa_pct"]), 100*(550-545)/550)


if __name__ == "__main__":
    unittest.main()
