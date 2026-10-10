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
        String snapshot = System.getProperty("olist.instance");
        OlistContextualData data = snapshot == null ? new OlistContextualData(OlistContextualData.DEFAULT_INPUT, 20261020)
                : OlistContextualData.loadSnapshot(java.nio.file.Path.of(snapshot));
        OlistContextualData repeated = snapshot == null ? new OlistContextualData(OlistContextualData.DEFAULT_INPUT, 20261020)
                : OlistContextualData.loadSnapshot(java.nio.file.Path.of(snapshot));
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
        if (args.length > 0 && args[0].equals("weight-grid")) {
            double[] grid = {.1, .25, .5, .8, .9, 1, 2, 3, 5, 10, 30, 50, 100};
            System.out.println("B\twindows\tess_min\tess_median\ttv_uniform_median\ttv_uniform_max");
            for (double b : grid) {
                List<Double> ess = new java.util.ArrayList<>(), tv = new java.util.ArrayList<>();
                // All 65 distinct validation origins, not duplicated across outer test weeks.
                for (int origin = 38; origin <= 102; origin++) for (int lag = 1; lag <= 3; lag++) {
                    var scaledWindow = OlistContextualData.scale(data.window(origin, lag, 35));
                    var weights = TRBSVUScenarioWeights.kernel(scaledWindow.training(), scaledWindow.query(),
                            TRBSVUScenarioWeights.Kernel.EXPONENTIAL, b);
                    double total = 0, squares = 0, deviation = 0;
                    for (var sample : weights) {
                        if (sample.weight > 0) sample.weight = Math.max(1e-8, sample.weight);
                        total += sample.weight;
                    }
                    for (var sample : weights) {
                        double p = sample.weight / total;
                        squares += p * p;
                        deviation += Math.abs(p - 1.0 / weights.size());
                    }
                    ess.add(1 / squares);
                    tv.add(deviation / 2);
                }
                ess.sort(Double::compare); tv.sort(Double::compare);
                System.out.printf(java.util.Locale.ROOT, "%g\t%d\t%.6f\t%.6f\t%.6f\t%.6f%n",
                        b, ess.size(), ess.get(0), ess.get(ess.size() / 2),
                        tv.get(tv.size() / 2), tv.get(tv.size() - 1));
            }
            return; // Diagnostic only: no RF training, MIP or holdout-cost evaluation.
        }
        int checks = 0;
        sharedLaneScalingFixture();
        for (int test = 53; test < 104; test++) for (int lag = 1; lag <= 3; lag++) {
            var full = data.window(test, lag, 50);
            require(full.startWeek() == test - 50 && full.endWeek() == test - 1, "Final window");
            require(full.target().theta.dim() == lag * data.market.J + (OlistContextualData.INCLUDE_TREND ? 1 : 0),
                    "Explicit context dimension");
            var finalLaneScaled = OlistContextualData.scale(full);
            for (int j = 0; j < data.market.J; j++) {
                double max = 0;
                for (var sample : full.training()) max = Math.max(max, Math.abs(sample.demand()[j]));
                for (int position = 0; position < lag; position++)
                    require(finalLaneScaled.maxima()[position * data.market.J + j] == (max < 1e-12 ? 1 : max),
                            "Final50 shared lane divisor");
            }
            if (OlistContextualData.INCLUDE_TREND) {
                int trend = full.target().theta.dim() - 1;
                require(full.target().theta.values()[trend] == OlistContextualData.trendValue(test), "Target chronological trend");
                for (var sample : full.training()) require(sample.theta.values()[trend] == OlistContextualData.trendValue(sample.period.tIndex),
                        "Training chronological trend");
                var previous = data.window(test - 1, lag, 35);
                // The same physical historical week must have the same trend in overlapping windows.
                require(previous.target().theta.values()[trend] == full.training().get(49).theta.values()[trend],
                        "Trend does not reset between windows");
                if (OlistContextualData.FIXED_TREND_104) {
                    var finalScaled = OlistContextualData.scale(full);
                    require(finalScaled.query().values()[trend] == (test + 1.0) / 104, "Final query fixed trend");
                    require(finalScaled.maxima()[trend] == 1.0, "No final trend max scaling");
                    for (int s = 0; s < 50; s++)
                        require(finalScaled.training().get(s).theta.values()[trend] == full.training().get(s).theta.values()[trend], "Final training fixed trend");
                }
            }
            for (int origin = test - 15; origin < test; origin++) {
                var window = data.window(origin, lag, 35);
                require(window.training().size() == 35 && window.endWeek() < origin, "Rolling origin");
                require(window.training().get(0).period.tIndex == origin - 35, "Rolling start");
                for (int k = 0; k < lag; k++) for (int j = 0; j < 23; j++)
                    require(window.target().theta.values()[23 * k + j]
                            == data.weekly.periods.get(origin - 1 - k).demandSum[j], "Past-only context");
                var scaled = OlistContextualData.scale(window);
                if (OlistContextualData.INCLUDE_TREND) {
                    int trend = scaled.query().dim() - 1;
                    require(scaled.maxima()[trend] == (OlistContextualData.FIXED_TREND_104 ? 1 : origin), "Trend scale policy");
                    require(Math.abs(scaled.query().values()[trend] - (OlistContextualData.FIXED_TREND_104
                            ? (origin + 1.0) / 104 : (origin + 1.0) / origin)) < 1e-12,
                            "Chronological query beyond training maximum is not clipped");
                    for (var sample : scaled.training()) require(sample.theta.values()[trend] <= 1.0,
                            "Historical trend scaled to at most one");
                }
                for (int k = 0; k < scaled.maxima().length; k++) {
                    if (OlistContextualData.INCLUDE_TREND && OlistContextualData.FIXED_TREND_104 && k == scaled.maxima().length - 1) {
                        require(scaled.query().values()[k] == window.target().theta.values()[k], "Query fixed trend unchanged");
                        for (int s = 0; s < window.training().size(); s++)
                            require(scaled.training().get(s).theta.values()[k] == window.training().get(s).theta.values()[k], "Training fixed trend unchanged");
                        continue;
                    }
                    double max = 0;
                    int demandDimensions = lag * data.market.J;
                    for (var sample : window.training()) max = Math.max(max,
                            k < demandDimensions ? Math.abs(sample.demand()[k % data.market.J])
                                    : Math.abs(sample.theta.values()[k]));
                    require(scaled.maxima()[k] == (max < 1e-12 ? 1 : max), "Training-only shared lane maximum");
                    require(scaled.query().values()[k] == window.target().theta.values()[k] / scaled.maxima()[k], "No query clipping");
                    for (int s = 0; s < window.training().size(); s++) {
                        require(scaled.training().get(s).theta.values()[k]
                                == window.training().get(s).theta.values()[k] / scaled.maxima()[k], "Same training/query divisor");
                        require(Arrays.equals(scaled.training().get(s).demand(), window.training().get(s).demand()), "Response demand unscaled");
                    }
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
        if (args.length > 0 && args[0].equals("scaling-only")) {
            System.out.println("OLIST_LANE_SHARED_MAX_PASS windows=" + checks
                    + " includeTrend=" + OlistContextualData.INCLUDE_TREND
                    + " fixedTrend104=" + OlistContextualData.FIXED_TREND_104);
            return;
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
            for (int week = 0; week < 54; week++) fixture.append(week).append(',')
                    .append(20 + week % 5).append(',').append(30 + week % 7).append('\n');
            OlistContextualRunner.atomic(root.resolve("fixture.csv"), fixture.toString());
            OlistContextualRunner.INPUT = root.resolve("fixture.csv");
            OlistContextualRunner.OUTPUT = root.resolve("outputs");
            OlistContextualRunner.BANDWIDTH = new double[]{1};
            OlistContextualRunner.LEAF = new int[]{2};
            OlistContextualRunner.TRIAL_COUNT = 1;
            OlistContextualRunner.LIMIT_SECONDS = 30;
            OlistContextualRunner.METHODS = OlistContextualRunner.Method.values();
            OlistContextualRunner.main(new String[]{"run"});
            for (String method : List.of("D", "SAA", "EXP", "RF")) {
                boolean baseline = method.equals("D") || method.equals("SAA");
                int scenarioCount = method.equals("D") ? 1 : 50;
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
                    selected[2] = baseline ? "0.0" : Double.toString(Double.parseDouble(selected[2]) + 100);
                    OlistContextualRunner.atomic(selectionFile, savedSelection.lines().toList().get(0)
                            + "\n" + String.join("\t", selected) + "\n");
                    require(!OlistContextualRunner.taskComplete(task, 53,
                            OlistContextualRunner.Method.valueOf(method), fixtureData), "Validation mean audit");
                } finally { OlistContextualRunner.atomic(selectionFile, savedSelection); }
                if (!baseline) {
                    var candidateFile = task.resolve("candidates.tsv");
                    String savedCandidates = java.nio.file.Files.readString(candidateFile);
                    try {
                        // Keep the marker and final result: an omitted or misranked candidate still invalidates completion.
                        var candidateRows = savedCandidates.lines().toList();
                        OlistContextualRunner.atomic(candidateFile,
                                String.join("\n", candidateRows.subList(0, candidateRows.size() - 1)) + "\n");
                        require(!OlistContextualRunner.taskComplete(task, 53,
                                OlistContextualRunner.Method.valueOf(method), fixtureData), "Missing grid candidate audit");
                        String[] other = candidateRows.get(candidateRows.size() - 1).split("\t");
                        other[4] = "0.0";
                        OlistContextualRunner.atomic(candidateFile,
                                String.join("\n", candidateRows.subList(0, candidateRows.size() - 1))
                                        + "\n" + String.join("\t", other) + "\n");
                        require(!OlistContextualRunner.taskComplete(task, 53,
                                OlistContextualRunner.Method.valueOf(method), fixtureData), "Selection must match grid ranking");
                    } finally { OlistContextualRunner.atomic(candidateFile, savedCandidates); }
                } else {
                    require(java.nio.file.Files.readAllLines(task.resolve("candidates.tsv")).size() == 1,
                            "Baselines have no validation grid");
                    require(!java.nio.file.Files.exists(root.resolve("outputs/validation_pool/" + method)),
                            "Baselines must not run origin solves");
                }
                String[] result = java.nio.file.Files.readAllLines(task.resolve("final/result.tsv")).get(1).split("\t", -1);
                require(result.length == 30,
                        "Checkpoint schema");
                require(Integer.parseInt(result[3]) == 50 && Integer.parseInt(result[29]) == scenarioCount,
                        "Historical observations versus model scenarios");
                require(java.nio.file.Files.readAllLines(task.resolve("final/weights.tsv")).size() == scenarioCount + 1,
                        "Final scenario weights");
                var scenariosFile = task.resolve("final/model_scenarios.tsv");
                var scenarioRows = java.nio.file.Files.readAllLines(scenariosFile);
                require(scenarioRows.size() == scenarioCount + 1, "Saved model inputs");
                var history = fixtureData.window(53, 1, 50).training();
                if (method.equals("D")) {
                    String[] meanRow = scenarioRows.get(1).split("\t");
                    for (int j = 0; j < 2; j++) {
                        final int lane = j;
                        double mean = history.stream().mapToDouble(s -> s.demand()[lane] / 50).sum();
                        require(Math.abs(Double.parseDouble(meanRow[2 + j]) - mean) < 1e-10, "D uses past50 lane mean");
                    }
                } else if (method.equals("SAA")) {
                    for (int s = 0; s < 50; s++) {
                        String[] row = scenarioRows.get(s + 1).split("\t");
                        require(Math.abs(Double.parseDouble(row[1]) - .02) < 1e-12, "SAA equal weight");
                        for (int j = 0; j < 2; j++) require(Double.parseDouble(row[2 + j]) == history.get(s).demand()[j],
                                "SAA uses every past50 scenario");
                    }
                }
                if (baseline) {
                    double[] y = new double[fixtureData.market.I];
                    for (int i = 0; i < y.length; i++) y[i] = result[15].charAt(i) - '0';
                    double expected = 0;
                    for (String scenario : scenarioRows.subList(1, scenarioRows.size())) {
                        String[] fields = scenario.split("\t");
                        double[] demand = {Double.parseDouble(fields[2]), Double.parseDouble(fields[3])};
                        expected += Double.parseDouble(fields[1]) * Test.BatchRunner.RecourseEvaluator
                                .evaluate(fixtureData.market, y, demand, true).objValue;
                    }
                    require(Math.abs(Double.parseDouble(result[9]) - expected) < 1e-7 * Math.max(1, expected),
                            "Baseline objective equals weighted fixed-y recourse");
                }
                String savedScenarios = java.nio.file.Files.readString(scenariosFile);
                try {
                    OlistContextualRunner.atomic(scenariosFile, scenarioRows.get(0) + "\n");
                    require(!OlistContextualRunner.taskComplete(task, 53,
                            OlistContextualRunner.Method.valueOf(method), fixtureData), "Missing model inputs invalidate marker");
                } finally { OlistContextualRunner.atomic(scenariosFile, savedScenarios); }
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
    private static void sharedLaneScalingFixture() {
        int dim = 6 + (OlistContextualData.INCLUDE_TREND ? 1 : 0);
        double[][] contexts = {{500, 4, 40, 8, 20, 12}, {30, 2, 100, 6, 50, 10}, {300, 20, 200, 30, 100, 40}};
        double[][] demands = {{10, 0}, {100, 0}, {1e9, 1e9}};
        List<Basic.Sample> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            double[] context = Arrays.copyOf(contexts[i], dim);
            if (OlistContextualData.INCLUDE_TREND) context[dim - 1] = OlistContextualData.trendValue(i);
            var date = java.time.LocalDate.of(2020, 1, 1).plusWeeks(i);
            var period = new Basic.PeriodData(i, date, date.plusDays(6), demands[i], 0, 0, 0, 0);
            samples.add(new Basic.Sample(i, period, new CovariateVector(context), .5));
        }
        var window = new OlistContextualData.Window(samples.subList(0, 2), samples.get(2), 0, 1);
        var scaled = OlistContextualData.scale(window);
        for (int k = 0; k < 6; k++) {
            require(scaled.maxima()[k] == (k % 2 == 0 ? 100 : 1), "Shared divisor and zero-lane fallback");
            require(scaled.query().values()[k] == contexts[2][k] / scaled.maxima()[k], "Target excluded and un-clipped");
            for (int s = 0; s < 2; s++)
                require(scaled.training().get(s).theta.values()[k] == contexts[s][k] / scaled.maxima()[k], "Lag positions share divisor");
        }
        require(scaled.training().get(0).theta.values()[0] == 5, "Earlier lag value above training-demand max not clipped");
        require(samples.get(0).theta.values()[0] == 500 && samples.get(2).theta.values()[0] == 300, "Source contexts untouched");
        for (int s = 0; s < 2; s++) require(Arrays.equals(scaled.training().get(s).demand(), demands[s]), "Fixture demands untouched");
    }
    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
