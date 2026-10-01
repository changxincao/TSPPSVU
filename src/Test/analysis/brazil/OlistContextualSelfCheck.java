package Test.analysis.brazil;

import Basic.CovariateVector;
import Basic.Data;
import Helper.basicHelper.Config;
import Model.SAAModel;
import Test.analysis.synthetic.TRBSVUForestWeights;
import Test.analysis.synthetic.TRBSVUProcurementGenerator;
import Test.analysis.synthetic.TRBSVUScenarioWeights;

import java.util.Arrays;
import java.util.List;

/** Checks actual Olist data/windows and max/RF/Exp weights, with an optional tiny native smoke test. */
public final class OlistContextualSelfCheck {
    public static void main(String[] args) throws Exception {
        OlistContextualData data = new OlistContextualData(OlistContextualData.DEFAULT_INPUT, 20261020);
        OlistContextualData repeated = new OlistContextualData(OlistContextualData.DEFAULT_INPUT, 20261020);
        require(data.weekly.periods.size() == 104 && data.market.J == 23, "Olist dimensions");
        require(data.market.I == 15 && data.market.alpha == 2 && data.market.beta == 12, "Market dimensions");
        require(Arrays.deepEquals(data.market.q, repeated.market.q)
                && Arrays.deepEquals(data.market.r, repeated.market.r), "Seed reproducibility");
        for (int i = 0; i < 15; i++) {
            int count = 0;
            double min = Double.POSITIVE_INFINITY, demand = 0;
            for (int j = 0; j < 23; j++) if (data.market.eligible[i][j]) {
                count++;
                min = Math.min(min, data.market.r[i][j]);
                demand += data.baselineDemand[j];
                require(data.market.q[i][j] >= .3 * data.baselineDemand[j]
                        && data.market.q[i][j] <= .5 * data.baselineDemand[j], "Capacity scale");
            }
            require(count == 12 && min == data.market.h[i], "Coverage/min penalty");
            require(data.market.p[i] >= .15 * demand && data.market.p[i] <= .35 * demand, "MQC scale");
        }
        int checks = 0;
        for (int test = 53; test < 104; test++) for (int lag = 1; lag <= 3; lag++) {
            var full = data.window(test, lag, 50);
            require(full.startWeek() == test - 50 && full.endWeek() == test - 1, "Final window");
            for (int origin = test - 15; origin < test; origin++) {
                var window = data.window(origin, lag, 35);
                require(window.training().size() == 35 && window.endWeek() < origin, "Rolling origin");
                require(window.training().get(0).period.tIndex == origin - 35, "Rolling start");
                for (int k = 0; k < lag; k++) for (int j = 0; j < 23; j++)
                    require(window.target().theta.values()[23 * k + j]
                            == data.weekly.periods.get(origin - 1 - k).demandSum[j], "Past-only context");
                var scaled = OlistContextualData.scale(window);
                for (int k = 0; k < scaled.maxima().length; k++) {
                    double max = 0;
                    for (var sample : window.training()) max = Math.max(max, sample.theta.values()[k]);
                    require(scaled.maxima()[k] == (max < 1e-12 ? 1 : max), "Training-only maximum");
                    require(scaled.query().values()[k] == window.target().theta.values()[k] / scaled.maxima()[k], "No query clipping");
                }
                var weights = TRBSVUScenarioWeights.kernel(scaled.training(), scaled.query(),
                        TRBSVUScenarioWeights.Kernel.EXPONENTIAL, 1);
                require(Math.abs(weights.stream().mapToDouble(s -> s.weight).sum() - 1) < 1e-12, "Exp normalization");
                require(window.training().get(0).theta.values()[0] == scaled.training().get(0).theta.values()[0]
                        * scaled.maxima()[0] || Math.abs(window.training().get(0).theta.values()[0]
                        - scaled.training().get(0).theta.values()[0] * scaled.maxima()[0]) < 1e-9, "Source unchanged");
                checks++;
            }
        }
        var scaled = OlistContextualData.scale(data.window(53, 2, 35));
        TRBSVUForestWeights forest = new TRBSVUForestWeights(OlistContextualRunner.PYTHON.toString(),
                OlistContextualRunner.RF_SCRIPT);
        for (int leaf : new int[]{1, 2, 5, 10}) {
            var weights = forest.weights(scaled.training(), scaled.query(), 20261020, leaf);
            require(Math.abs(weights.stream().mapToDouble(s -> s.weight).sum() - 1) < 1e-10, "RF normalization");
            require(weights.size() == 35, "RF scenario count");
        }
        if (args.length > 0 && args[0].equals("solver-smoke")) {
            var market = TRBSVUProcurementGenerator.generate(15, new double[]{20, 30}, 20261020);
            var period = new Basic.PeriodData(0, java.time.LocalDate.of(2020, 1, 1),
                    java.time.LocalDate.of(2020, 1, 7), new double[]{25, 35}, 0, 0, 0, 0);
            var sample = new Basic.Sample(0, period, new CovariateVector(new double[]{1}), 1);
            Config cfg = new Config();
            cfg.enforceDemandEquality = true;
            cfg.threads = 4;
            cfg.timeLimitSeconds = 30;
            var solution = new SAAModel().solve(new Data(List.of("A", "B"), List.of(sample), sample.theta, market), cfg, null);
            var recourse = Test.BatchRunner.RecourseEvaluator.evaluate(market, solution.y, sample.demand(), true);
            require(solution.y != null && Math.abs(solution.objValue - recourse.objValue) < 1e-5, "Nominal/equality OOS identity");
            System.out.println("CPLEX_TINY_SMOKE_PASS");
        }
        if (args.length > 0 && args[0].equals("pipeline-smoke")) {
            java.nio.file.Path root = java.nio.file.Path.of(args.length > 1 ? args[1] : "tmp/olist_pipeline_selfcheck");
            java.nio.file.Files.createDirectories(root);
            StringBuilder fixture = new StringBuilder("weekIndex,A,B\n");
            for (int week = 0; week < 54; week++) fixture.append(week).append(",20,30\n");
            OlistContextualRunner.atomic(root.resolve("fixture.csv"), fixture.toString());
            OlistContextualRunner.INPUT = root.resolve("fixture.csv");
            OlistContextualRunner.OUTPUT = root.resolve("outputs");
            OlistContextualRunner.BANDWIDTH = new double[]{1};
            OlistContextualRunner.LEAF = new int[]{2};
            OlistContextualRunner.TRIAL_COUNT = 1;
            OlistContextualRunner.LIMIT_SECONDS = 30;
            OlistContextualRunner.main(new String[]{"run"});
            for (String method : List.of("EXP", "RF")) {
                var task = root.resolve("outputs/trial_000/" + method);
                require(java.nio.file.Files.exists(task.resolve("complete.txt")), "End-to-end completion");
                var fixtureData = OlistContextualData.loadSnapshot(root.resolve("outputs/input_snapshot.tsv"));
                require(OlistContextualRunner.taskComplete(task, 53,
                        OlistContextualRunner.Method.valueOf(method), fixtureData), "Complete output audit");
                var weightsFile = task.resolve("final/weights.tsv");
                String savedWeights = java.nio.file.Files.readString(weightsFile);
                try {
                    OlistContextualRunner.atomic(weightsFile, "sample_id\tweek\tweight\n");
                    require(!OlistContextualRunner.taskComplete(task, 53,
                            OlistContextualRunner.Method.valueOf(method), fixtureData), "Marker cannot hide missing weights");
                } finally { OlistContextualRunner.atomic(weightsFile, savedWeights); }
                var selectionFile = task.resolve("selection.tsv");
                String savedSelection = java.nio.file.Files.readString(selectionFile);
                try {
                    String[] selected = savedSelection.lines().toList().get(1).split("\t");
                    selected[2] = Double.toString(Double.parseDouble(selected[2]) + 100);
                    OlistContextualRunner.atomic(selectionFile, savedSelection.lines().toList().get(0)
                            + "\n" + String.join("\t", selected) + "\n");
                    require(!OlistContextualRunner.taskComplete(task, 53,
                            OlistContextualRunner.Method.valueOf(method), fixtureData), "Validation mean audit");
                } finally { OlistContextualRunner.atomic(selectionFile, savedSelection); }
                require(java.nio.file.Files.readAllLines(task.resolve("final/result.tsv")).get(1).split("\t", -1).length == 29,
                        "Checkpoint schema");
                require(java.nio.file.Files.readAllLines(task.resolve("final/weights.tsv")).size() == 51, "Final 50 weights");
                var before = java.nio.file.Files.getLastModifiedTime(task.resolve("final/logs/cplex.log"));
                OlistContextualRunner.METHODS = new OlistContextualRunner.Method[]{OlistContextualRunner.Method.valueOf(method)};
                OlistContextualRunner.main(new String[]{"run"});
                require(before.equals(java.nio.file.Files.getLastModifiedTime(task.resolve("final/logs/cplex.log"))), "Resume does not resolve");
            }
            // A settings change must not silently reuse old checkpoints.
            OlistContextualRunner.LIMIT_SECONDS = 31;
            boolean rejected = false;
            try { OlistContextualRunner.main(new String[]{"prepare"}); }
            catch (IllegalStateException expected) { rejected = expected.getMessage().contains("Protocol changed"); }
            require(rejected, "Protocol mismatch rejects resume");
            System.out.println("OLIST_PIPELINE_RESUME_PASS");
        }
        System.out.println("OLIST_SELF_CHECK_PASS windows=" + checks + " RF500_four_leaves=PASS");
    }
    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
