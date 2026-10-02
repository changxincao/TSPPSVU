import csv
import tempfile
import unittest
from pathlib import Path

from summarize_lambda_comparison import aggregate


class SummaryTest(unittest.TestCase):
    def test_missing_and_market_aggregation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            tasks = []
            for case, query, chi, exact, same, condition in [
                ("r0", "q0", 10, 9, "true", "HOLDS"),
                ("r0", "q1", 30, 33, "false", "FAILS"),
            ]:
                file = root / f"{case}_{query}.csv"
                row = dict(case_id=case, query_id=query, lambda_value=0.5,
                           same_decision=same, both_certified="true", chi_certified="true",
                           certificate_status=condition)
                row["lambda"] = row.pop("lambda_value")
                for model, value in (("chi", chi), ("rcsaa", exact)):
                    for metric in ("mean", "sd", "q95", "cvar95", "max"):
                        row[f"{model}_oos_{metric}"] = value
                with file.open("w", newline="") as stream:
                    writer = csv.DictWriter(stream, fieldnames=list(row))
                    writer.writeheader()
                    writer.writerow(row)
                tasks.append(dict(case_id=case, query_id=query, comparison_csv=file.name))
            tasks.append(dict(case_id="r1", query_id="q0", comparison_csv="not_finished.csv"))
            manifest = root / "manifest.csv"
            with manifest.open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=list(tasks[0]))
                writer.writeheader()
                writer.writerows(tasks)
            result = aggregate(manifest, [0.5])[0]
            self.assertEqual(result["expected_queries"], 3)
            self.assertEqual(result["paired_certified"], 2)
            self.assertEqual(result["decision_agreement_percent"], 50)
            self.assertEqual(result["condition_holds_percent"], 50)
            self.assertEqual(result["unresolved_certificate_queries"], 1)
            self.assertEqual(result["complete_oos_markets"], 1)
            self.assertEqual(result["chi_oos_mean"], 20)
            self.assertEqual(result["rcsaa_oos_mean"], 21)
            self.assertEqual(result["oos_mean_delta_percent_rcsaa_vs_chi"], 5)
            # The same physical file cannot be used under a different query identity.
            tasks[0]["query_id"] = "wrong"
            with manifest.open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=list(tasks[0]))
                writer.writeheader()
                writer.writerows(tasks)
            with self.assertRaises(ValueError):
                aggregate(manifest, [0.5])


if __name__ == "__main__":
    unittest.main()
