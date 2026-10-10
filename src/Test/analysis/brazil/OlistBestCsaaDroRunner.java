package Test.analysis.brazil;

import Basic.Data;
import Basic.Sample;
import Helper.basicHelper.Config;
import Model.DROModel;
import Model.Solution;
import Model.SolveMode;
import Test.BatchRunner;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Separate diagnostic stage: one globally OOS-selected CSAA family, historical-only lambda CV. */
public final class OlistBestCsaaDroRunner {
    private static final boolean FIXED_RF = Boolean.getBoolean("olist.fixedRf");
    private static final double[] LAMBDA = parseGrid(System.getProperty("olist.lambdaGrid", "0.1,0.25,0.5,1"));
    private static Path outputRoot, reuseRoot;
    private static boolean requireReuse;
    private static final String HEADER = "target_week\ttrain_start\ttrain_end\ttraining_size\tlag\tmethod"
            + "\tparameter\tstatus\tcertified_optimal\tobjective\tbest_bound\trelative_gap"
            + "\tmodel_build_solve_sec\toptimizer_sec\tselected_count\ty_binary\tess\tpositive_samples"
            + "\trealized_cost\ttransport_cost\tspot_cost\tmqc_cost\ttotal_demand\tcontracted_quantity"
            + "\tspot_quantity\tmqc_shortfall\tspot_share\tcapacity_utilization\tlane_capacity_utilization\tscenario_count\tlambda\n";
    private record Candidate(double lambda, double mean, double sd) { }
    private record Result(double cost, String row) { }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("selfcheck")) {
            // Different per-market winners must still yield one family from aggregate costs.
            Map<String, Double> aggregate = Map.of("EXP", (90.0 + 1000.0) / 2, "RF", (100.0 + 1.0) / 2);
            if (!best(aggregate).equals("RF") || !best(Map.of("EXP", 90.0, "RF", 100.0)).equals("EXP")
                    || !best(Map.of("RF", 1.0, "EXP", 1.0)).equals("EXP")) throw new AssertionError("Global selection/tie rule");
            Set<Integer> before = new HashSet<>(), after = new HashSet<>();
            for (int origin = 38; origin < 53; origin++) before.add(origin);
            for (int origin = 39; origin < 54; origin++) after.add(origin);
            after.retainAll(before);
            if (after.size() != 14 || Arrays.stream(LAMBDA).anyMatch(x -> x <= 0)
                    || parseGrid("0.01,0.05,0.1,0.25,0.5,1,2,5,10").length != 9)
                throw new AssertionError("Rolling reuse/lambda grid");
            for (String bad : List.of("", "0,1", "1,0.5", "1,1", "NaN", "Infinity")) {
                try { parseGrid(bad); throw new AssertionError("Accepted invalid grid: " + bad); }
                catch (IllegalArgumentException expected) { }
            }
            System.out.println("GLOBAL_SELECTION_AND_ROLLING_SELF_CHECK_PASS"); return;
        }
        if (args.length < 3) throw new IllegalArgumentException("select|run|audit|smoke baseRoot outputRoot [market method]");
        Path base = Path.of(args[1]), out = Path.of(args[2]);
        requireReuse = args[0].equals("reuse-smoke");
        outputRoot = out.toAbsolutePath().normalize();
        String previous = System.getProperty("olist.reuseRoot", "");
        reuseRoot = previous.isBlank() ? null : Path.of(previous).toAbsolutePath().normalize();
        if (outputRoot.equals(reuseRoot)) throw new IllegalArgumentException("Reuse source must be a separate directory");
        if (args[0].equals("smoke")) out = out.resolve("preflight");
        if (args[0].equals("select")) { select(base, out); return; }
        if (args.length != 5) throw new IllegalArgumentException("market and EXP|RF required");
        String market = args[3], method = args[4];
        if (!Set.of("EXP", "RF").contains(method)) throw new IllegalArgumentException("Not a CSAA method");
        if (!args[0].equals("smoke")) {
            String frozen = Files.readAllLines(out.resolve("global_selection.tsv")).get(1).split("\t")[0];
            if (!method.equals(frozen)) throw new IllegalStateException("Worker differs from globally selected method");
        }
        OlistContextualData data = OlistContextualData.loadSnapshot(base.resolve("inputs/" + market + "/instance.tsv"));
        Path baseline = base.resolve("results/" + market), target = out.resolve("results/" + market);
        prepareProtocol(base, out, market, method);
        if (args[0].equals("reusecheck")) {
            if (reuseRoot == null) throw new IllegalArgumentException("No reuse source");
            System.out.println("REUSE_PROTOCOL_RUNTIME_CHECK_PASS " + market); return;
        }
        if (requireReuse) {
            String[] selected = selection(baseline, 0, method);
            int lag = Integer.parseInt(selected[0]); double parameter = Double.parseDouble(selected[1]);
            String key = method + "/k" + lag + "_p" + parameter;
            solve(data, data.window(38, lag, 35), method, lag, parameter, 0.1,
                    baseline.resolve("validation_pool/" + key + "/week_038"),
                    target.resolve("validation_pool/" + key + "/lambda_0.1/week_038"));
            System.out.println("REUSE_SAVED_VALIDATION_PASS no_optimizer_called"); return;
        }
        if (args[0].equals("smoke")) {
            String[] selected = selection(baseline, 0, method);
            solve(data, data.window(53, Integer.parseInt(selected[0]), 50), method,
                    Integer.parseInt(selected[0]), Double.parseDouble(selected[1]), 0.1,
                    baseline.resolve("trial_000/" + method + "/final"), target.resolve("smoke"));
            System.out.println("DRO_SMOKE_PASS"); return;
        }
        if (args[0].equals("audit")) {
            int complete = 0;
            for (int trial = 0; trial < 51; trial++) if (complete(target, baseline, trial, method, data)) complete++;
            System.out.println("DRO_AUDIT " + market + " " + complete + "/51");
            if (complete != 51) System.exit(2);
            return;
        }
        if (!args[0].equals("run")) throw new IllegalArgumentException("Unknown mode");
        int failures = 0;
        for (int trial = 0; trial < 51; trial++) {
            if (complete(target, baseline, trial, method, data)) continue;
            Path dir = target.resolve(String.format("trial_%03d", trial));
            Files.createDirectories(dir);
            Files.deleteIfExists(dir.resolve("complete.txt"));
            Files.deleteIfExists(dir.resolve("failure.txt"));
            try {
                String[] selected = selection(baseline, trial, method);
                int lag = Integer.parseInt(selected[0]), week = 53 + trial;
                double parameter = Double.parseDouble(selected[1]);
                List<Candidate> candidates = new ArrayList<>();
                StringBuilder table = new StringBuilder("lambda\tcompleted_origins\tmean_cost\tsample_sd\n");
                for (double lambda : LAMBDA) {
                    List<Double> costs = new ArrayList<>();
                    StringBuilder origins = new StringBuilder(HEADER);
                    for (int origin = week - 15; origin < week; origin++) {
                        String key = method + "/k" + lag + "_p" + parameter + "/lambda_" + lambda
                                + String.format("/week_%03d", origin);
                        Path source = baseline.resolve("validation_pool/" + method + "/k" + lag + "_p" + parameter)
                                .resolve(String.format("week_%03d", origin));
                        Result result = solve(data, data.window(origin, lag, 35), method, lag, parameter,
                                lambda, source, target.resolve("validation_pool/" + key));
                        costs.add(result.cost()); origins.append(result.row());
                        OlistContextualRunner.atomic(dir.resolve("validation/lambda_" + lambda + "/origins.tsv"), origins.toString());
                    }
                    double mean = costs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                    double sd = sd(costs, mean);
                    candidates.add(new Candidate(lambda, mean, sd));
                    table.append(lambda).append("\t15\t").append(mean).append('\t').append(sd).append('\n');
                    OlistContextualRunner.atomic(dir.resolve("candidates.tsv"), table.toString());
                }
                candidates.sort(Comparator.comparingDouble(Candidate::mean).thenComparingDouble(Candidate::sd)
                        .thenComparingDouble(Candidate::lambda));
                Candidate best = candidates.get(0);
                OlistContextualRunner.atomic(dir.resolve("selection.tsv"),
                        "method\tlag\tcontext_parameter\tlambda\tvalidation_mean\tvalidation_sd\n"
                                + method + "\t" + lag + "\t" + parameter + "\t" + best.lambda() + "\t" + best.mean() + "\t" + best.sd() + "\n");
                Result result = solve(data, data.window(week, lag, 50), method, lag, parameter, best.lambda(),
                        baseline.resolve(String.format("trial_%03d/%s/final", trial, method)), dir.resolve("final"));
                OlistContextualRunner.atomic(dir.resolve("final_result.tsv"), HEADER + result.row());
                OlistContextualRunner.atomic(dir.resolve("complete.txt"), "finished=" + java.time.Instant.now() + "\n");
                if (!complete(target, baseline, trial, method, data)) throw new IllegalStateException("Final audit failed");
                System.out.println("DRO_WEEK_DONE market=" + market + " trial=" + trial + " method=" + method
                        + " lambda=" + best.lambda() + " realized=" + result.cost());
            } catch (Exception error) {
                failures++; Files.deleteIfExists(dir.resolve("complete.txt"));
                OlistContextualRunner.atomic(dir.resolve("failure.txt"), error.toString() + "\n");
                System.err.println("DRO_WEEK_FAILED " + market + " " + trial + " " + error);
            }
        }
        if (failures > 0) throw new IllegalStateException(failures + " weeks failed; completed results retained");
    }

    private static void select(Path base, Path out) throws Exception {
        OlistContextualBatchMain.main(new String[]{"check", base.toString()});
        if (FIXED_RF) {
            // Family is fixed in advance. Audit RF availability per market in the scheduler;
            // one failed market must not block other markets or trigger OOS family selection.
            String result = "method\tselection\tformal_training_only\tmean_realized_cost\tvalidation_winner\n"
                    + "RF\tUSER_FIXED_RF\ttrue\tNaN\tRF\n";
            Path file = out.resolve("global_selection.tsv");
            if (Files.exists(file) && !Files.readString(file).equals(result))
                throw new IllegalStateException("Refuse to change a frozen fixed-family selection");
            OlistContextualRunner.atomic(file, result);
            System.out.println("USER_FIXED_METHOD=RF; baseline audit is per market");
            return;
        }
        List<String> markets = Files.readAllLines(base.resolve("inputs/markets.tsv")).stream()
                .skip(1).map(row -> row.split("\t")[0]).toList();
        Map<String, Double> oos = new TreeMap<>(), validation = new TreeMap<>();
        StringBuilder table = new StringBuilder("market\tmethod\tweeks\tmean_realized_cost\tmean_validation_cost\n");
        for (String method : FIXED_RF ? List.of("RF") : List.of("EXP", "RF")) {
            double total = 0, totalValidation = 0;
            for (String market : markets) {
                var data = OlistContextualData.loadSnapshot(base.resolve("inputs/" + market + "/instance.tsv"));
                double sum = 0, validationSum = 0;
                for (int trial = 0; trial < 51; trial++) {
                    Path dir = base.resolve(String.format("results/%s/trial_%03d/%s", market, trial, method));
                    if (!OlistContextualRunner.taskComplete(dir, 53 + trial, OlistContextualRunner.Method.valueOf(method), data))
                        throw new IllegalStateException("Baseline incomplete: " + dir);
                    sum += Double.parseDouble(Files.readAllLines(dir.resolve("final_result.tsv")).get(1).split("\t")[18]);
                    validationSum += Double.parseDouble(Files.readAllLines(dir.resolve("selection.tsv")).get(1).split("\t")[2]);
                }
                total += sum / 51; totalValidation += validationSum / 51;
                table.append(market).append('\t').append(method).append("\t51\t").append(sum / 51)
                        .append('\t').append(validationSum / 51).append('\n');
            }
            oos.put(method, total / markets.size()); validation.put(method, totalValidation / markets.size());
        }
        String winner = best(oos), validationWinner = best(validation);
        String result = "method\tselection\tformal_training_only\tmean_realized_cost\tvalidation_winner\n"
                + winner + "\t" + (FIXED_RF ? "USER_FIXED_RF\ttrue" : "GLOBAL_" + markets.size() + "_MARKETS_51_WEEKS_OOS_MEAN\tfalse")
                + "\t" + oos.get(winner) + "\t" + validationWinner + "\n";
        Path selection = out.resolve("global_selection.tsv");
        if (Files.exists(selection) && !Files.readString(selection).equals(result))
            throw new IllegalStateException("Refuse to change a frozen global method");
        OlistContextualRunner.atomic(out.resolve("method_comparison.tsv"), table.toString());
        OlistContextualRunner.atomic(selection, result);
        System.out.println((FIXED_RF ? "USER_FIXED_METHOD=" : "GLOBAL_OOS_WINNER=") + winner + " validationWinner=" + validationWinner + " means=" + oos);
    }

    static String best(Map<String, Double> means) {
        return means.entrySet().stream().min(Map.Entry.<String, Double>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey())).orElseThrow().getKey();
    }

    private static double[] parseGrid(String text) {
        double[] grid = Arrays.stream(text.split(",", -1)).map(String::trim).mapToDouble(Double::parseDouble).toArray();
        for (int i = 0; i < grid.length; i++)
            if (!Double.isFinite(grid[i]) || grid[i] <= 0 || (i > 0 && grid[i] <= grid[i - 1]))
                throw new IllegalArgumentException("Lambda grid must be finite, positive and strictly increasing");
        return grid;
    }

    private static String[] selection(Path baseline, int trial, String method) throws Exception {
        return Files.readAllLines(baseline.resolve(String.format("trial_%03d/%s/selection.tsv", trial, method)))
                .get(1).split("\t");
    }

    private static void prepareProtocol(Path base, Path out, String market, String method) throws Exception {
        if (FIXED_RF && !method.equals("RF")) throw new IllegalArgumentException("Fixed RF route requires RF");
        String protocol = "OLIST_GLOBAL_OOS_CSAA_CHI2_V1\nmethod=" + method + "\nformalTrainingOnly=" + FIXED_RF + "\n"
                + (OlistContextualData.FIXED_TREND_104 ? "trendFeature=FIXED_ONE_BASED_WEEK_DIV_104_NO_WINDOW_SCALING\n" : "")
                + "includeTrend=" + Boolean.getBoolean("olist.includeTrend") + "\n"
                + "scaling=LANE_SHARED_MAX_OF_TRAINING_DEMAND_NO_CLIPPING\n"
                + "lambdaGrid=" + Arrays.toString(LAMBDA) + "\nthreads=4\nlimitSec=14400\nvalidation=15x35\nfinal=50\n"
                + "inputSha256=" + sha(base.resolve("inputs/" + market + "/instance.tsv")) + "\n"
                + "baselineProtocolSha256=" + sha(base.resolve("results/" + market + "/protocol.txt")) + "\n";
        // Hash staged runtime, including nested classes and solvers, before permitting checkpoint reuse.
        Path classes = Path.of(OlistBestCsaaDroRunner.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var paths = Files.walk(classes)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                digest.update(classes.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                digest.update(Files.readAllBytes(file));
            }
        }
        protocol += "runtimeSha256=" + HexFormat.of().formatHex(digest.digest()) + "\n";
        if (reuseRoot != null) {
            Path oldProtocol = reuseRoot.resolve("results/" + market + "/protocol.txt");
            List<String> expected = protocol.lines().filter(OlistBestCsaaDroRunner::reuseInvariant).toList();
            List<String> previousLines = new ArrayList<>(Files.readAllLines(oldProtocol));
            // The original no-trend deployment predates this explicit protocol field.
            if (previousLines.stream().noneMatch(line -> line.startsWith("includeTrend="))) {
                if (Boolean.getBoolean("olist.includeTrend")) throw new IllegalStateException("Old runtime has no trend support");
                previousLines.add(previousLines.indexOf("formalTrainingOnly=false") + 1, "includeTrend=false");
            }
            if (!previousLines.stream().filter(OlistBestCsaaDroRunner::reuseInvariant).toList().equals(expected))
                throw new IllegalStateException("Reuse input/baseline/protocol mismatch: " + oldProtocol);
            // Only this orchestration class may change. Solvers, data, evaluation and all dependencies must match.
            Path oldClasses = reuseRoot.resolve("runtime/classes");
            MessageDigest oldDigest = MessageDigest.getInstance("SHA-256");
            try (var paths = Files.walk(oldClasses)) {
                for (Path old : paths.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    oldDigest.update(oldClasses.relativize(old).toString().getBytes(StandardCharsets.UTF_8));
                    oldDigest.update(Files.readAllBytes(old));
                }
            }
            if (!Files.readAllLines(oldProtocol).contains("runtimeSha256=" + HexFormat.of().formatHex(oldDigest.digest())))
                throw new IllegalStateException("Reuse source runtime no longer matches its saved protocol");
            if (!runtimeFiles(classes).equals(runtimeFiles(oldClasses)))
                throw new IllegalStateException("Reuse runtime file set mismatch");
            for (String relative : runtimeFiles(classes))
                if (!sha(classes.resolve(relative)).equals(sha(oldClasses.resolve(relative))))
                    throw new IllegalStateException("Reuse runtime differs: " + relative);
            protocol += "reuseRoot=" + reuseRoot + "\nreuseProtocolSha256=" + sha(oldProtocol) + "\n";
        }
        Path file = out.resolve("results/" + market + "/protocol.txt");
        if (Files.exists(file) && !Files.readString(file).equals(protocol)) throw new IllegalStateException("DRO protocol mismatch");
        OlistContextualRunner.atomic(file, protocol);
    }

    private static Result solve(OlistContextualData data, OlistContextualData.Window window, String method,
                                int lag, double parameter, double lambda, Path source, Path dir) throws Exception {
        List<String> rows = Files.readAllLines(source.resolve("weights.tsv"));
        if (rows.size() != window.training().size() + 1) throw new IllegalStateException("Weight/window count mismatch");
        String fingerprint = sha(source.resolve("weights.tsv")) + "\n" + sha(source.resolve("result.tsv")) + "\n"
                + sha(source.resolve("model_scenarios.tsv")) + "\n" + sha(source.resolve("max_scaling.tsv")) + "\n"
                + window.target().period.tIndex + "\n"
                + window.startWeek() + "\n" + lag + "\n" + parameter + "\n" + lambda + "\n";
        Files.createDirectories(dir);
        Path savedFingerprint = dir.resolve("source_fingerprint.txt");
        if (Files.exists(savedFingerprint) && !Files.readString(savedFingerprint).equals(fingerprint))
            throw new IllegalStateException("Checkpoint source differs: " + dir);
        OlistContextualRunner.atomic(savedFingerprint, fingerprint);
        var scaled = OlistContextualData.scale(window);
        double mass = 0, squares = 0; int positive = 0;
        for (int s = 0; s < scaled.training().size(); s++) {
            Sample sample = scaled.training().get(s); String[] row = rows.get(s + 1).split("\t");
            if (sample.id != Integer.parseInt(row[0]) || sample.period.tIndex != Integer.parseInt(row[1]))
                throw new IllegalStateException("Unpaired source sample");
            sample.weight = Double.parseDouble(row[2]);
            if (!Double.isFinite(sample.weight) || sample.weight < 0) throw new IllegalStateException("Invalid weight");
            mass += sample.weight; squares += sample.weight * sample.weight; if (sample.weight > 0) positive++;
        }
        if (Math.abs(mass - 1) > 1e-10) throw new IllegalStateException("Weights not normalized");
        if (reuseRoot != null && !Files.exists(dir.resolve("incumbent.tsv"))) {
            Path old = reuseRoot.resolve(outputRoot.relativize(dir.toAbsolutePath().normalize()));
            if (Files.exists(old.resolve("source_fingerprint.txt"))
                    && Files.readString(old.resolve("source_fingerprint.txt")).equals(fingerprint)
                    && solveFilesComplete(old, data, scaled.training().size())) {
                for (String name : List.of("result.tsv", "incumbent.tsv", "weights.tsv", "model_scenarios.tsv",
                        "max_scaling.tsv", "lane_oos.tsv", "carrier_oos.tsv", "mosek.log"))
                    Files.copy(old.resolve(name), dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                OlistContextualRunner.atomic(dir.resolve("reused_from.txt"), old + "\n");
                System.out.println("DRO_REUSE_SOLVE " + old);
            }
        }
        if (solveFilesComplete(dir, data, scaled.training().size())) {
            String row = Files.readAllLines(dir.resolve("result.tsv")).get(1);
            return new Result(Double.parseDouble(row.split("\t")[18]), row + "\n");
        }
        if (requireReuse) throw new IllegalStateException("Reuse smoke must not invoke an optimizer");
        Files.copy(source.resolve("weights.tsv"), dir.resolve("weights.tsv"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source.resolve("model_scenarios.tsv"), dir.resolve("model_scenarios.tsv"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(source.resolve("max_scaling.tsv"), dir.resolve("max_scaling.tsv"), StandardCopyOption.REPLACE_EXISTING);
        Config cfg = new Config(); cfg.enforceDemandEquality = true; cfg.threads = 4;
        cfg.timeLimitSeconds = 14400; cfg.tol = 1e-4; cfg.lambda = lambda; cfg.solveMode = SolveMode.CSAA;
        cfg.writeSolverLogToConsole = true;
        Solution solution; double elapsed;
        Path incumbent = dir.resolve("incumbent.tsv");
        if (Files.exists(incumbent)) {
            String[] f = Files.readAllLines(incumbent).get(1).split("\t");
            solution = new Solution(); solution.solverStatus = f[0]; solution.certifiedOptimal = Boolean.parseBoolean(f[1]);
            solution.objValue = Double.parseDouble(f[2]); solution.bestBound = Double.parseDouble(f[3]);
            solution.relativeGap = Double.parseDouble(f[4]); solution.y = new double[data.market.I];
            if (!f[5].matches("[01]{" + data.market.I + "}")) throw new IllegalStateException("Invalid saved y");
            for (int i = 0; i < solution.y.length; i++) solution.y[i] = f[5].charAt(i) - '0';
            elapsed = Double.parseDouble(f[6]); solution.optimizerTimeSec = Double.parseDouble(f[7]);
        } else {
            PrintStream console = System.out; long started = System.nanoTime();
            try (PrintStream log = new PrintStream(Files.newOutputStream(dir.resolve("mosek.log")), true, StandardCharsets.UTF_8)) {
                System.setOut(log);
                try { solution = new DROModel().solve(new Data(data.weekly.laneNames, scaled.training(), scaled.query(), data.market), cfg); }
                finally { System.setOut(console); }
            }
            elapsed = (System.nanoTime() - started) / 1e9;
        }
        if (solution.y == null || !Double.isFinite(solution.objValue)) throw new IllegalStateException("No finite feasible decision");
        OlistContextualRunner.atomic(incumbent, "status\tcertified\tobjective\tbound\tgap\ty\twall_sec\toptimizer_sec\n"
                + solution.solverStatus + "\t" + solution.certifiedOptimal + "\t" + solution.objValue + "\t" + solution.bestBound
                + "\t" + solution.relativeGap + "\t" + encode(solution.y) + "\t" + elapsed + "\t" + solution.optimizerTimeSec + "\n");
        var recourse = BatchRunner.RecourseEvaluator.evaluate(data.market, solution.y, window.target().demand(), true);
        if (!Double.isFinite(recourse.objValue)) throw new IllegalStateException("No finite OOS recourse");
        double demand = Arrays.stream(window.target().demand()).sum(), contracted = Arrays.stream(recourse.carrierAssignedQty).sum();
        double spot = Arrays.stream(recourse.laneSpotQty).sum(), shortfall = Arrays.stream(recourse.carrierPenaltyQty).sum();
        double capacity = 0, laneUtilization = 0; int active = 0;
        for (int i = 0; i < data.market.I; i++) if (solution.y[i] > .5) capacity += data.market.M[i];
        StringBuilder lanes = new StringBuilder("lane\tdemand\tcontracted\tspot\tcontract_cost\tspot_cost\n");
        for (int j = 0; j < data.market.J; j++) {
            double available = 0; for (int i = 0; i < data.market.I; i++) if (solution.y[i] > .5) available += data.market.q[i][j];
            if (available > 0) { laneUtilization += recourse.laneTransportQty[j] / available; active++; }
            lanes.append(j).append('\t').append(window.target().demand()[j]).append('\t').append(recourse.laneTransportQty[j])
                    .append('\t').append(recourse.laneSpotQty[j]).append('\t').append(recourse.laneTransportCost[j])
                    .append('\t').append(recourse.laneSpotCost[j]).append('\n');
        }
        OlistContextualRunner.atomic(dir.resolve("lane_oos.tsv"), lanes.toString());
        StringBuilder carriers = new StringBuilder("carrier\ty\tassigned\tmqc_shortfall\tmqc_cost\n");
        for (int i = 0; i < data.market.I; i++) carriers.append(i).append('\t').append(solution.y[i]).append('\t')
                .append(recourse.carrierAssignedQty[i]).append('\t').append(recourse.carrierPenaltyQty[i])
                .append('\t').append(recourse.carrierPenaltyCost[i]).append('\n');
        OlistContextualRunner.atomic(dir.resolve("carrier_oos.tsv"), carriers.toString());
        String row = String.join("\t", "" + window.target().period.tIndex, "" + window.startWeek(), "" + window.endWeek(),
                "" + scaled.training().size(), "" + lag, method, "" + parameter, solution.solverStatus,
                "" + solution.certifiedOptimal, "" + solution.objValue, "" + solution.bestBound, "" + solution.relativeGap,
                "" + elapsed, "" + solution.optimizerTimeSec, "" + Arrays.stream(solution.y).filter(x -> x > .5).count(),
                encode(solution.y), "" + (1 / squares), "" + positive, "" + recourse.objValue, "" + recourse.transportTotalCost,
                "" + recourse.spotTotalCost, "" + recourse.penaltyTotalCost, "" + demand, "" + contracted, "" + spot,
                "" + shortfall, "" + (demand > 0 ? spot / demand : 0), "" + (capacity > 0 ? contracted / capacity : 0),
                "" + (active > 0 ? laneUtilization / active : 0), "" + positive, "" + lambda) + "\n";
        OlistContextualRunner.atomic(dir.resolve("result.tsv"), HEADER + row);
        return new Result(recourse.objValue, row);
    }

    private static boolean complete(Path target, Path baseline, int trial, String method, OlistContextualData data) throws Exception {
        try {
            return completeChecked(target, baseline, trial, method, data);
        } catch (java.io.IOException | IllegalArgumentException | IndexOutOfBoundsException ex) {
            System.err.println("Incomplete/corrupt Olist recovery artifacts at "
                    + target + "/trial_" + trial + ": " + ex);
            return false;
        }
    }

    private static boolean completeChecked(Path target, Path baseline, int trial, String method,
                                           OlistContextualData data) throws Exception {
        Path dir = target.resolve(String.format("trial_%03d", trial));
        if (!Files.exists(dir.resolve("complete.txt")) || Files.exists(dir.resolve("failure.txt"))
                || !table(dir.resolve("selection.tsv"), 2) || !table(dir.resolve("candidates.tsv"), LAMBDA.length + 1)
                || !table(dir.resolve("final_result.tsv"), 2)
                || !solveFilesComplete(dir.resolve("final"), data, 50)) return false;
        String[] chosen = Files.readAllLines(dir.resolve("selection.tsv")).get(1).split("\t");
        String[] nominal = selection(baseline, trial, method);
        if (!chosen[0].equals(method) || !chosen[1].equals(nominal[0]) || !chosen[2].equals(nominal[1])) return false;
        List<Candidate> candidates = new ArrayList<>();
        for (int n = 0; n < LAMBDA.length; n++) {
            double lambda = LAMBDA[n]; List<Double> costs = new ArrayList<>();
            Path origins = dir.resolve("validation/lambda_" + lambda + "/origins.tsv");
            if (!table(origins, 16)) return false;
            List<String> rows = Files.readAllLines(origins);
            for (int v = 0; v < 15; v++) {
                String[] f = rows.get(v + 1).split("\t");
                int origin = 53 + trial - 15 + v;
                if (f.length != 31 || Integer.parseInt(f[0]) != origin || Integer.parseInt(f[3]) != 35
                        || Integer.parseInt(f[1]) != origin - 35 || Integer.parseInt(f[2]) != origin - 1
                        || !f[4].equals(chosen[1]) || !f[5].equals(method) || !f[6].equals(chosen[2])
                        || Double.parseDouble(f[30]) != lambda || !Double.isFinite(Double.parseDouble(f[18]))) return false;
                costs.add(Double.parseDouble(f[18]));
            }
            double mean = costs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            candidates.add(new Candidate(lambda, mean, sd(costs, mean)));
            String[] c = Files.readAllLines(dir.resolve("candidates.tsv")).get(n + 1).split("\t");
            if (Double.parseDouble(c[0]) != lambda || !c[1].equals("15") || Double.parseDouble(c[2]) != mean
                    || Double.parseDouble(c[3]) != sd(costs, mean)) return false;
        }
        candidates.sort(Comparator.comparingDouble(Candidate::mean).thenComparingDouble(Candidate::sd).thenComparingDouble(Candidate::lambda));
        Candidate best = candidates.get(0);
        String[] result = Files.readAllLines(dir.resolve("final/result.tsv")).get(1).split("\t");
        return Double.parseDouble(chosen[3]) == best.lambda() && Double.parseDouble(chosen[4]) == best.mean()
                && Double.parseDouble(chosen[5]) == best.sd() && Integer.parseInt(result[0]) == 53 + trial
                && Integer.parseInt(result[1]) == 3 + trial && Integer.parseInt(result[2]) == 52 + trial
                && Integer.parseInt(result[3]) == 50
                && result[4].equals(chosen[1]) && result[5].equals(method) && result[6].equals(chosen[2])
                && Double.parseDouble(result[30]) == best.lambda()
                && Files.readAllLines(dir.resolve("final/result.tsv")).equals(Files.readAllLines(dir.resolve("final_result.tsv")));
    }

    private static boolean solveFilesComplete(Path dir, OlistContextualData data, int samples) throws Exception {
        try {
            return solveFilesCompleteChecked(dir, data, samples);
        } catch (java.io.IOException | IllegalArgumentException | IndexOutOfBoundsException ex) {
            System.err.println("Incomplete/corrupt Olist solve artifacts at " + dir + ": " + ex);
            return false;
        }
    }

    private static boolean solveFilesCompleteChecked(Path dir, OlistContextualData data, int samples) throws Exception {
        if (!table(dir.resolve("result.tsv"), 2) || !table(dir.resolve("incumbent.tsv"), 2)
                || !table(dir.resolve("weights.tsv"), samples + 1) || !table(dir.resolve("model_scenarios.tsv"), samples + 1)
                || !table(dir.resolve("lane_oos.tsv"), data.market.J + 1) || !table(dir.resolve("carrier_oos.tsv"), data.market.I + 1)
                || !Files.exists(dir.resolve("max_scaling.tsv")) || !Files.exists(dir.resolve("source_fingerprint.txt"))
                || !Files.exists(dir.resolve("mosek.log"))) return false;
        String[] f = Files.readAllLines(dir.resolve("result.tsv")).get(1).split("\t");
        return f.length == 31 && f[15].matches("[01]{" + data.market.I + "}") && Double.isFinite(Double.parseDouble(f[18]));
    }
    private static boolean table(Path path, int rows) throws Exception { return Files.exists(path) && Files.readAllLines(path).size() == rows; }
    private static boolean reuseInvariant(String line) {
        return !line.startsWith("lambdaGrid=") && !line.startsWith("runtimeSha256=");
    }
    private static Set<String> runtimeFiles(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            Set<String> names = new TreeSet<>();
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals("OlistBestCsaaDroRunner.class") || name.startsWith("OlistBestCsaaDroRunner$")) continue;
                names.add(root.relativize(file).toString());
            }
            return names;
        }
    }
    private static double sd(List<Double> costs, double mean) { return Math.sqrt(costs.stream().mapToDouble(c -> (c - mean) * (c - mean)).sum() / (costs.size() - 1)); }
    private static String encode(double[] y) { StringBuilder s = new StringBuilder(); for (double v : y) s.append(v > .5 ? '1' : '0'); return s.toString(); }
    private static String sha(Path path) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
}
