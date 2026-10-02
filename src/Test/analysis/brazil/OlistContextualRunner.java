package Test.analysis.brazil;

import Basic.Data;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.OutputManager;
import Helper.calculateHelper.WeightCalculator;
import Model.SAAModel;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUForestWeights;
import Test.analysis.synthetic.TRBSVUScenarioWeights;
import Test.analysis.synthetic.TRBSVUSolveMethods;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** IDE entry point: default only prepares/audits inputs; pass 'run' to solve. */
public final class OlistContextualRunner {
    public enum Method { D, SAA, EXP, RF }
    public static Path INPUT = Path.of(System.getProperty("olist.input", OlistContextualData.DEFAULT_INPUT.toString()));
    public static Path SNAPSHOT = System.getProperty("olist.instance") == null ? null : Path.of(System.getProperty("olist.instance"));
    public static Path OUTPUT = Path.of("analysis_runs/olist_exp_rf_max_20261001");
    public static Path PYTHON = Path.of(System.getProperty("olist.python", ".venv-rsome/Scripts/python.exe")).toAbsolutePath();
    public static Path RF_SCRIPT = Path.of(System.getProperty("olist.rfScript", "analysis/trb_svu/rf_leaf_weights.py")).toAbsolutePath();
    public static long MARKET_SEED = Long.getLong("olist.marketSeed", 20261020), RF_SEED = Long.getLong("olist.rfSeed", 20261020);
    public static int THREADS = Integer.getInteger("olist.threads", 4), LIMIT_SECONDS = Integer.getInteger("olist.limit", 14400);
    public static int START_TRIAL = 0, TRIAL_COUNT = 51;
    public static boolean PREPARE_ONLY = true;
    public static Method[] METHODS = Arrays.stream(System.getProperty("olist.methods", "D,SAA,EXP,RF").split(","))
            .map(String::trim).map(Method::valueOf).toArray(Method[]::new);
    // Pilot grid: concentrated through nearly uniform weights; expand only after reviewing validation.
    public static double[] BANDWIDTH = {0.1, 0.25, 0.5, 1, 2, 5};
    public static int[] LEAF = {1, 2, 5, 10};
    private static final String HEADER = "target_week\ttrain_start\ttrain_end\ttraining_size\tlag\tmethod"
            + "\tparameter\tstatus\tcertified_optimal\tobjective\tbest_bound\trelative_gap"
            + "\tmodel_build_solve_sec\toptimizer_sec\tselected_count\ty_binary\tess\tpositive_samples"
            + "\trealized_cost\ttransport_cost\tspot_cost\tmqc_cost\ttotal_demand\tcontracted_quantity"
            + "\tspot_quantity\tmqc_shortfall\tspot_share\tcapacity_utilization\tlane_capacity_utilization\tscenario_count\n";

    private OlistContextualRunner() { }

    public static void main(String[] args) throws Exception {
        boolean solve = args.length == 0 ? !PREPARE_ONLY : args[0].equals("run");
        if (args.length > 0 && !args[0].equals("run") && !args[0].equals("prepare"))
            throw new IllegalArgumentException("Usage: [prepare|run] [outputDir] [startTrial] [trialCount]");
        if (args.length > 1) OUTPUT = Path.of(args[1]);
        if (args.length > 2) START_TRIAL = Integer.parseInt(args[2]);
        if (args.length > 3) TRIAL_COUNT = Integer.parseInt(args[3]);
        OlistContextualData data = SNAPSHOT == null ? new OlistContextualData(INPUT, MARKET_SEED)
                : OlistContextualData.loadSnapshot(SNAPSHOT);
        if (data.marketSeed != MARKET_SEED) throw new IllegalArgumentException("Snapshot/market seed mismatch.");
        if (START_TRIAL < 0 || TRIAL_COUNT < 1 || THREADS < 1 || LIMIT_SECONDS < 1)
            throw new IllegalArgumentException("Invalid execution settings.");
        for (double b : BANDWIDTH) if (!Double.isFinite(b) || b <= 0) throw new IllegalArgumentException("Invalid B.");
        for (int leaf : LEAF) if (leaf < 1) throw new IllegalArgumentException("Invalid RF leaf.");
        int available = data.weekly.periods.size() - OlistContextualData.FIRST_TEST;
        int end = Math.min(available, Math.addExact(START_TRIAL, TRIAL_COUNT));
        if (START_TRIAL >= end) throw new IllegalArgumentException("No selected trials.");
        String environment = pythonEnvironment(solve && Arrays.asList(METHODS).contains(Method.RF));
        prepare(data, environment);
        if (!solve) {
            System.out.printf("PREPARED weeks=%d lanes=%d carriers=%d selection=%d..%d trials=%d; no solves%n",
                    data.weekly.periods.size(), data.market.J, data.market.I, data.market.alpha, data.market.beta, available);
            return;
        }
        TRBSVUForestWeights forest = new TRBSVUForestWeights(PYTHON.toString(), RF_SCRIPT);
        int failed = 0;
        for (int trial = START_TRIAL; trial < end; trial++) {
            int test = OlistContextualData.FIRST_TEST + trial;
            for (Method method : METHODS) {
                Path directory = OUTPUT.resolve(String.format("trial_%03d/%s", trial, method));
                try { runMethod(data, forest, test, method, directory); }
                catch (Exception error) {
                    failed++;
                    atomic(directory.resolve("failure.txt"), error.toString() + "\n");
                    System.err.println("TASK_FAILED " + directory + " " + error);
                    // One failed method/week must not stop later tasks.
                }
            }
        }
        if (failed > 0) throw new IllegalStateException(failed + " method/week tasks failed; saved successes remain reusable.");
    }

    private record Candidate(int lag, double parameter, double mean, double sd) { }
    private record Result(double cost, String row) { }

    private static void runMethod(OlistContextualData data, TRBSVUForestWeights forest,
                                  int test, Method method, Path directory) throws Exception {
        Files.createDirectories(directory);
        Files.deleteIfExists(directory.resolve("complete.txt"));
        Files.deleteIfExists(directory.resolve("failure.txt"));
        StringBuilder candidates = new StringBuilder("lag\tparameter\tcompleted_origins\tvalid\tmean_cost\tsample_sd\n");
        if (isBaseline(method)) {
            // k=1 is only a metadata/window carrier; neither baseline uses context or tunes k.
            atomic(directory.resolve("candidates.tsv"), candidates.toString());
            atomic(directory.resolve("selection.tsv"), "lag\tparameter\tvalidation_mean\tvalidation_sd\n1\t0.0\tNaN\tNaN\n");
            finishTask(data, forest, test, method, 1, 0, directory);
            return;
        }
        List<Candidate> valid = new ArrayList<>();
        double[] grid = method == Method.EXP ? BANDWIDTH : Arrays.stream(LEAF).asDoubleStream().toArray();
        for (int lag = 1; lag <= 3; lag++) for (double parameter : grid) {
            List<Double> costs = new ArrayList<>();
            Path candidate = directory.resolve("validation/k" + lag + "_p" + parameter);
            StringBuilder originRows = new StringBuilder(HEADER);
            for (int origin = test - 15; origin < test; origin++) {
                // A fixed-size rolling origin has identical training/query data for all
                // outer weeks that include it. Reuse its solve; never refit/resolve it.
                Path step = OUTPUT.resolve("validation_pool/" + method + "/k" + lag + "_p" + parameter)
                        .resolve(String.format("week_%03d", origin));
                try {
                    Result result = solveOne(data, forest, data.window(origin, lag, 35), method,
                            lag, parameter, step);
                    costs.add(result.cost());
                    originRows.append(result.row());
                    atomic(candidate.resolve("origins.tsv"), originRows.toString());
                    Files.deleteIfExists(step.resolve("failure.txt"));
                } catch (Exception error) {
                    atomic(step.resolve("failure.txt"), error.toString() + "\n");
                    System.err.println("ORIGIN_FAILED " + step + " " + error);
                    // Output failure is not evidence that a bandwidth is statistically invalid.
                    if (error instanceof java.io.IOException) throw error;
                    break;
                }
            }
            double mean = costs.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
            double sd = sampleSd(costs, mean);
            boolean complete = costs.size() == 15;
            if (complete) valid.add(new Candidate(lag, parameter, mean, sd));
            candidates.append(lag).append('\t').append(parameter).append('\t').append(costs.size())
                    .append('\t').append(complete).append('\t').append(mean).append('\t').append(sd).append('\n');
            atomic(directory.resolve("candidates.tsv"), candidates.toString());
        }
        // Identical 15 origins for every candidate; never average a partial candidate.
        valid.sort(Comparator.comparingDouble(Candidate::mean).thenComparingDouble(Candidate::sd)
                .thenComparingDouble(Candidate::parameter).thenComparingInt(Candidate::lag));
        if (valid.isEmpty()) throw new IllegalStateException("No complete validation candidate.");
        Candidate best = valid.get(0);
        atomic(directory.resolve("selection.tsv"), "lag\tparameter\tvalidation_mean\tvalidation_sd\n"
                + best.lag() + "\t" + best.parameter() + "\t" + best.mean() + "\t" + best.sd() + "\n");
        finishTask(data, forest, test, method, best.lag(), best.parameter(), directory);
    }

    private static boolean isBaseline(Method method) { return method == Method.D || method == Method.SAA; }

    private static void finishTask(OlistContextualData data, TRBSVUForestWeights forest, int test,
                                   Method method, int lag, double parameter, Path directory) throws Exception {
        Result result = solveOne(data, forest, data.window(test, lag, 50), method,
                lag, parameter, directory.resolve("final"));
        atomic(directory.resolve("final_result.tsv"), HEADER + result.row());
        atomic(directory.resolve("complete.txt"), "finished=" + java.time.Instant.now() + "\n");
        System.out.printf(Locale.ROOT, "TASK_DONE week=%d method=%s lag=%d parameter=%g realized=%.10f%n",
                test, method, lag, parameter, result.cost());
    }

    private static Result solveOne(OlistContextualData data, TRBSVUForestWeights forest,
                                    OlistContextualData.Window window, Method method,
                                    int lag, double parameter, Path directory) throws Exception {
        Files.createDirectories(directory);
        int scenarios = method == Method.D ? 1 : window.training().size();
        Path checkpoint = directory.resolve("result.tsv");
        if (Files.exists(checkpoint) && tableComplete(directory.resolve("weights.tsv"), scenarios + 1)
                && tableComplete(directory.resolve("model_scenarios.tsv"), scenarios + 1)
                && tableComplete(directory.resolve("max_scaling.tsv"), window.target().theta.dim() + 1)
                && tableComplete(directory.resolve("lane_oos.tsv"), data.market.J + 1)
                && tableComplete(directory.resolve("carrier_oos.tsv"), data.market.I + 1)
                && tableComplete(directory.resolve("incumbent.tsv"), 2)) {
            List<String> saved = Files.readAllLines(checkpoint);
            if (saved.size() == 2 && (saved.get(0) + "\n").equals(HEADER)) {
                String[] values = saved.get(1).split("\t", -1);
                if (values.length == 30 && Integer.parseInt(values[0]) == window.target().period.tIndex
                        && Integer.parseInt(values[1]) == window.startWeek()
                        && Integer.parseInt(values[2]) == window.endWeek()
                        && Integer.parseInt(values[3]) == window.training().size() && Integer.parseInt(values[29]) == scenarios
                        && Integer.parseInt(values[4]) == lag && values[5].equals(method.name())
                        && Double.parseDouble(values[6]) == parameter && Double.isFinite(Double.parseDouble(values[18])))
                    return new Result(Double.parseDouble(values[18]), saved.get(1) + "\n");
            }
            throw new IllegalStateException("Incomplete or inconsistent checkpoint: " + checkpoint);
        }
        OlistContextualData.Scaled scaled = OlistContextualData.scale(window);
        List<Sample> weighted = switch (method) {
            case D -> TRBSVUScenarioWeights.arithmeticMean(scaled.training());
            case SAA -> TRBSVUScenarioWeights.equal(scaled.training());
            case RF -> forest.weights(scaled.training(), scaled.query(), RF_SEED, (int) parameter);
            case EXP -> TRBSVUScenarioWeights.kernel(scaled.training(), scaled.query(),
                    TRBSVUScenarioWeights.Kernel.EXPONENTIAL, parameter);
        };
        double sum = 0;
        for (Sample sample : weighted) {
            // Preserve exact zeros; apply the previously frozen positive-weight floor.
            if (sample.weight > 0) sample.weight = Math.max(WeightCalculator.FORMAL_WEIGHT_FLOOR, sample.weight);
            sum += sample.weight;
        }
        for (Sample sample : weighted) sample.weight /= sum;
        StringBuilder weights = new StringBuilder("sample_id\tweek\tweight\n");
        double squares = 0;
        int positive = 0;
        for (Sample sample : weighted) {
            weights.append(sample.id).append('\t').append(sample.period.tIndex).append('\t').append(sample.weight).append('\n');
            squares += sample.weight * sample.weight;
            if (sample.weight > 0) positive++;
        }
        atomic(directory.resolve("weights.tsv"), weights.toString());
        StringBuilder demands = new StringBuilder("sample_id\tweight");
        for (int j = 0; j < data.market.J; j++) demands.append("\tlane_").append(j);
        demands.append('\n');
        for (Sample sample : weighted) {
            demands.append(sample.id).append('\t').append(sample.weight);
            for (double demand : sample.demand()) demands.append('\t').append(demand);
            demands.append('\n');
        }
        atomic(directory.resolve("model_scenarios.tsv"), demands.toString());
        StringBuilder scale = new StringBuilder("feature\t"
                + (OlistContextualData.FIXED_TREND_104 ? "effective_divisor" : "training_max") + "\tquery_raw\tquery_scaled\n");
        for (int k = 0; k < scaled.maxima().length; k++)
            scale.append(k).append('\t').append(scaled.maxima()[k]).append('\t')
                    .append(window.target().theta.values()[k]).append('\t').append(scaled.query().values()[k]).append('\n');
        atomic(directory.resolve("max_scaling.tsv"), scale.toString());
        Config config = new Config();
        config.enforceDemandEquality = true;
        config.threads = THREADS;
        config.timeLimitSeconds = LIMIT_SECONDS;
        config.tol = 1e-4;
        config.writeCplexLogToFile = true;
        long started = System.nanoTime();
        Solution solution;
        double elapsed;
        Path incumbent = directory.resolve("incumbent.tsv");
        if (Files.exists(incumbent)) {
            String[] fields = Files.readAllLines(incumbent).get(1).split("\t");
            solution = new Solution();
            solution.solverStatus = fields[0];
            solution.certifiedOptimal = Boolean.parseBoolean(fields[1]);
            solution.objValue = Double.parseDouble(fields[2]);
            solution.bestBound = Double.parseDouble(fields[3]);
            solution.relativeGap = Double.parseDouble(fields[4]);
            if (fields[5].length() != data.market.I || !fields[5].matches("[01]+"))
                throw new IllegalStateException("Invalid saved decision: " + incumbent);
            solution.y = new double[data.market.I];
            for (int i = 0; i < solution.y.length; i++) solution.y[i] = fields[5].charAt(i) - '0';
            elapsed = Double.parseDouble(fields[6]);
            solution.optimizerTimeSec = Double.parseDouble(fields[7]);
        } else {
            solution = new SAAModel().solve(new Data(data.weekly.laneNames, weighted,
                    scaled.query(), data.market), config, OutputManager.atDirectory(directory));
            elapsed = (System.nanoTime() - started) / 1e9;
        }
        if (solution.y == null || !Double.isFinite(solution.objValue))
            throw new IllegalStateException("No finite feasible decision.");
        // Record incumbent immediately, before OOS evaluation can fail.
        atomic(incumbent, "status\tcertified\tobjective\tbound\tgap\ty\twall_sec\toptimizer_sec\n"
                + solution.solverStatus + "\t" + solution.certifiedOptimal + "\t" + solution.objValue + "\t"
                + solution.bestBound + "\t" + solution.relativeGap + "\t" + encode(solution.y)
                + "\t" + elapsed + "\t" + solution.optimizerTimeSec + "\n");
        // Single observed week is the holdout; no fabricated 1000 conditional OOS draws.
        var recourse = Test.BatchRunner.RecourseEvaluator.evaluate(data.market, solution.y, window.target().demand(), true);
        if (!Double.isFinite(recourse.objValue))
            throw new IllegalStateException("Realized-demand recourse has no finite solution; incumbent retained.");
        double totalDemand = Arrays.stream(window.target().demand()).sum();
        double contracted = Arrays.stream(recourse.carrierAssignedQty).sum();
        double spot = Arrays.stream(recourse.laneSpotQty).sum();
        double shortfall = Arrays.stream(recourse.carrierPenaltyQty).sum();
        double selectedCapacity = 0, laneUtilization = 0;
        int activeLanes = 0;
        for (int i = 0; i < data.market.I; i++) if (solution.y[i] > 0.5) selectedCapacity += data.market.M[i];
        for (int j = 0; j < data.market.J; j++) {
            double capacity = 0;
            for (int i = 0; i < data.market.I; i++) if (solution.y[i] > 0.5) capacity += data.market.q[i][j];
            if (capacity > 0) { laneUtilization += recourse.laneTransportQty[j] / capacity; activeLanes++; }
        }
        StringBuilder lanes = new StringBuilder("lane\tdemand\tcontracted\tspot\tcontract_cost\tspot_cost\n");
        for (int j = 0; j < data.market.J; j++)
            lanes.append(j).append('\t').append(window.target().demand()[j]).append('\t')
                    .append(recourse.laneTransportQty[j]).append('\t').append(recourse.laneSpotQty[j]).append('\t')
                    .append(recourse.laneTransportCost[j]).append('\t').append(recourse.laneSpotCost[j]).append('\n');
        atomic(directory.resolve("lane_oos.tsv"), lanes.toString());
        StringBuilder carriers = new StringBuilder("carrier\ty\tassigned\tmqc_shortfall\tmqc_cost\n");
        for (int i = 0; i < data.market.I; i++) carriers.append(i).append('\t').append(solution.y[i]).append('\t')
                .append(recourse.carrierAssignedQty[i]).append('\t').append(recourse.carrierPenaltyQty[i])
                .append('\t').append(recourse.carrierPenaltyCost[i]).append('\n');
        atomic(directory.resolve("carrier_oos.tsv"), carriers.toString());
        long count = Arrays.stream(solution.y).filter(x -> x > 0.5).count();
        String row = String.join("\t", Integer.toString(window.target().period.tIndex),
                Integer.toString(window.startWeek()), Integer.toString(window.endWeek()),
                Integer.toString(window.training().size()), Integer.toString(lag), method.name(), Double.toString(parameter),
                solution.solverStatus, Boolean.toString(solution.certifiedOptimal), Double.toString(solution.objValue),
                Double.toString(solution.bestBound), Double.toString(solution.relativeGap), Double.toString(elapsed),
                Double.toString(solution.optimizerTimeSec), Long.toString(count), encode(solution.y),
                Double.toString(1 / squares), Integer.toString(positive), Double.toString(recourse.objValue),
                Double.toString(recourse.transportTotalCost), Double.toString(recourse.spotTotalCost), Double.toString(recourse.penaltyTotalCost),
                Double.toString(totalDemand), Double.toString(contracted), Double.toString(spot), Double.toString(shortfall),
                Double.toString(totalDemand > 0 ? spot / totalDemand : 0),
                Double.toString(selectedCapacity > 0 ? contracted / selectedCapacity : 0),
                Double.toString(activeLanes > 0 ? laneUtilization / activeLanes : 0), Integer.toString(weighted.size())) + "\n";
        atomic(checkpoint, HEADER + row);
        return new Result(recourse.objValue, row);
    }

    private static void prepare(OlistContextualData data, String environment) throws Exception {
        Files.createDirectories(OUTPUT);
        String protocol = "OLIST_BASELINES_EXP_RF_MAX_ROLLING15_V3\ninputSha256=" + hash(Files.readAllBytes(SNAPSHOT == null ? INPUT : SNAPSHOT))
                + "\ncodeSha256=" + codeHash() + "\nrfScriptSha256=" + hash(Files.readAllBytes(RF_SCRIPT))
                + "\npython=" + PYTHON + "\npythonEnvironment=" + environment
                + "\nmarketSeed=" + MARKET_SEED + "\nrfSeed=" + RF_SEED + "\nthreads=" + THREADS
                + "\nlimit=" + LIMIT_SECONDS + "\ngap=1e-4\nrfTrees=500\nselection=" + data.market.alpha + ".." + data.market.beta + "\n"
                + "market=" + System.getProperty("olist.marketLabel", "current_factory_50pct_coverage_mqc015035_spot23_minH") + "\n"
                + "procurementCalibration=ALL_WEEKS_RETROSPECTIVE\nvalidation=35_rolling_15\nfinalHistory=50\n"
                + "includeTrend=" + OlistContextualData.INCLUDE_TREND + "\ntrendFeature="
                + (OlistContextualData.FIXED_TREND_104 ? "FIXED_ONE_BASED_WEEK_DIV_104_NO_WINDOW_SCALING" : "DETERMINISTIC_ONE_BASED_WEEK_TRAINING_MAX") + "\n"
                + "baselineD=MEAN_OF_50\nbaselineSAA=ALL_50_EQUAL\nbaselineValidation=NONE\n"
                + "scaling=" + (OlistContextualData.FIXED_TREND_104 ? "DEMAND_TRAINING_MAX_FIXED_TREND_NO_CLIPPING" : "TRAINING_MAX_NO_CLIPPING")
                + "\nB=" + Arrays.toString(BANDWIDTH) + "\nleaf=" + Arrays.toString(LEAF) + "\n";
        Path file = OUTPUT.resolve("protocol.txt");
        if (Files.exists(file)) {
            if (!Files.readString(file).equals(protocol))
                throw new IllegalStateException("Protocol changed; use a new output directory. " + file);
            if (Files.exists(OUTPUT.resolve("rolling_plan.tsv")) && Files.exists(OUTPUT.resolve("input_snapshot.tsv"))) {
                Path expected = Files.createTempFile(OUTPUT, "snapshot_verify_", ".tsv");
                try {
                    data.saveSnapshot(expected);
                    if (!Arrays.equals(Files.readAllBytes(expected), Files.readAllBytes(OUTPUT.resolve("input_snapshot.tsv"))))
                        throw new IllegalStateException("Prepared numerical snapshot differs from solve input.");
                } finally { Files.deleteIfExists(expected); }
                return;
            }
        }
        atomic(file, protocol);
        StringBuilder plan = new StringBuilder("trial\ttest_week\tfinal_start\tfinal_end\tvalidation_start\tvalidation_end\n");
        for (int test = OlistContextualData.FIRST_TEST; test < data.weekly.periods.size(); test++)
            plan.append(test - OlistContextualData.FIRST_TEST).append('\t').append(test).append('\t')
                    .append(test - 50).append('\t').append(test - 1).append('\t').append(test - 15)
                    .append('\t').append(test - 1).append('\n');
        atomic(OUTPUT.resolve("rolling_plan.tsv"), plan.toString());
        data.saveSnapshot(OUTPUT.resolve("input_snapshot.tsv"));
    }

    /** Check actual final outputs, not just an old complete marker. */
    public static boolean taskComplete(Path directory, int week, Method method, OlistContextualData data) throws Exception {
        if (!Files.exists(directory.resolve("complete.txt")) || Files.exists(directory.resolve("failure.txt"))
                || !tableComplete(directory.resolve("selection.tsv"), 2)
                || !tableComplete(directory.resolve("final_result.tsv"), 2)) return false;
        String[] selection = Files.readAllLines(directory.resolve("selection.tsv")).get(1).split("\t");
        if (selection.length != 4) return false;
        int lag = Integer.parseInt(selection[0]);
        double parameter = Double.parseDouble(selection[1]);
        if (lag < 1 || lag > 3) return false;
        if (!selectionComplete(directory, method, selection)) return false;
        int scenarios = method == Method.D ? 1 : 50;
        Path finalDir = directory.resolve("final");
        if (!tableComplete(finalDir.resolve("result.tsv"), 2)
                || !tableComplete(finalDir.resolve("weights.tsv"), scenarios + 1)
                || !tableComplete(finalDir.resolve("model_scenarios.tsv"), scenarios + 1)
                || !tableComplete(finalDir.resolve("max_scaling.tsv"), lag * data.market.J + (OlistContextualData.INCLUDE_TREND ? 1 : 0) + 1)
                || !tableComplete(finalDir.resolve("lane_oos.tsv"), data.market.J + 1)
                || !tableComplete(finalDir.resolve("carrier_oos.tsv"), data.market.I + 1)
                || !tableComplete(finalDir.resolve("incumbent.tsv"), 2)
                || !Files.exists(finalDir.resolve("logs/cplex.log"))) return false;
        List<String> rows = Files.readAllLines(finalDir.resolve("result.tsv"));
        if (!rows.equals(Files.readAllLines(directory.resolve("final_result.tsv")))) return false;
        String[] f = rows.get(1).split("\t", -1);
        if (f.length != 30 || Integer.parseInt(f[0]) != week || !f[5].equals(method.name())
                || Integer.parseInt(f[4]) != lag || Double.parseDouble(f[6]) != parameter
                || Integer.parseInt(f[1]) != week - 50 || Integer.parseInt(f[2]) != week - 1
                || Integer.parseInt(f[3]) != 50 || Integer.parseInt(f[29]) != scenarios || !f[15].matches("[01]{" + data.market.I + "}")
                || !Double.isFinite(Double.parseDouble(f[18]))) return false;
        if (isBaseline(method)) return true; // No nonexistent validation origins are required for D/SAA.
        Path origins = directory.resolve("validation/k" + lag + "_p" + parameter + "/origins.tsv");
        if (!tableComplete(origins, 16)) return false;
        List<String> originRows = Files.readAllLines(origins);
        List<Double> costs = new ArrayList<>();
        for (int v = 0; v < 15; v++) {
            String[] origin = originRows.get(v + 1).split("\t");
            if (origin.length != 30 || Integer.parseInt(origin[0]) != week - 15 + v
                    || Integer.parseInt(origin[1]) != week - 15 + v - 35
                    || Integer.parseInt(origin[2]) != week - 15 + v - 1
                    || Integer.parseInt(origin[3]) != 35 || Integer.parseInt(origin[29]) != 35 || Integer.parseInt(origin[4]) != lag
                    || !origin[5].equals(method.name()) || Double.parseDouble(origin[6]) != parameter
                    || !Double.isFinite(Double.parseDouble(origin[18]))) return false;
            costs.add(Double.parseDouble(origin[18]));
        }
        double mean = costs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        return Math.abs(mean - Double.parseDouble(selection[2])) <= 1e-10 * Math.max(1, mean)
                && Math.abs(sampleSd(costs, mean) - Double.parseDouble(selection[3])) <= 1e-10 * Math.max(1, mean);
    }

    private static boolean selectionComplete(Path directory, Method method, String[] selection) throws Exception {
        int lag = Integer.parseInt(selection[0]);
        double parameter = Double.parseDouble(selection[1]);
        Path candidateFile = directory.resolve("candidates.tsv");
        if (isBaseline(method)) return lag == 1 && parameter == 0
                && Double.isNaN(Double.parseDouble(selection[2])) && Double.isNaN(Double.parseDouble(selection[3]))
                && tableComplete(candidateFile, 1);
        // A final result alone cannot certify that the whole current grid was evaluated.
        double[] grid = method == Method.EXP ? BANDWIDTH : Arrays.stream(LEAF).asDoubleStream().toArray();
        if (!tableComplete(candidateFile, 3 * grid.length + 1)) return false;
        List<Candidate> valid = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String row : Files.readAllLines(candidateFile).subList(1, 3 * grid.length + 1)) {
            String[] fields = row.split("\t", -1);
            if (fields.length != 6) return false;
            int candidateLag = Integer.parseInt(fields[0]), count = Integer.parseInt(fields[2]);
            double candidateParameter = Double.parseDouble(fields[1]);
            if (candidateLag < 1 || candidateLag > 3 || count < 0 || count > 15
                    || Arrays.stream(grid).noneMatch(p -> p == candidateParameter)
                    || !seen.add(candidateLag + ":" + candidateParameter)
                    || !(fields[3].equals("true") || fields[3].equals("false"))
                    || Boolean.parseBoolean(fields[3]) != (count == 15)) return false;
            if (count == 15) {
                double mean = Double.parseDouble(fields[4]), sd = Double.parseDouble(fields[5]);
                if (!Double.isFinite(mean) || !Double.isFinite(sd) || sd < 0) return false;
                valid.add(new Candidate(candidateLag, candidateParameter, mean, sd));
            }
        }
        valid.sort(Comparator.comparingDouble(Candidate::mean).thenComparingDouble(Candidate::sd)
                .thenComparingDouble(Candidate::parameter).thenComparingInt(Candidate::lag));
        if (valid.isEmpty()) return false;
        Candidate best = valid.get(0);
        return best.lag() == lag && best.parameter() == parameter
                && best.mean() == Double.parseDouble(selection[2]) && best.sd() == Double.parseDouble(selection[3]);
    }

    private static String pythonEnvironment(boolean required) throws Exception {
        if (!Files.exists(PYTHON)) {
            if (required) throw new IllegalStateException("RF Python executable missing: " + PYTHON);
            return "UNAVAILABLE";
        }
        Process process = new ProcessBuilder(PYTHON.toString(), "-c",
                "import sys,numpy,sklearn;print(sys.version.split()[0]+'|numpy='+numpy.__version__+'|sklearn='+sklearn.__version__)")
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0) throw new IllegalStateException("RF environment error: " + output);
        return output;
    }

    private static String codeHash() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<String, Class<?>> classes = new TreeMap<>();
        for (Class<?> type : List.of(OlistContextualRunner.class, OlistContextualData.class,
                Test.analysis.synthetic.TRBSVUProcurementGenerator.class, TRBSVUForestWeights.class,
                TRBSVUScenarioWeights.class, TRBSVUSolveMethods.class, SAAModel.class,
                Test.BatchRunner.class, Helper.calculateHelper.StandardScaler.class,
                Helper.basicHelper.SampleBuilder.class, Helper.basicHelper.Config.class,
                Basic.Data.class, Basic.PeriodData.class, Solution.class,
                Basic.ProcurementParams.class, Basic.Sample.class, Basic.CovariateVector.class,
                Helper.calculateHelper.WeightCalculator.class, OutputManager.class,
                Helper.basicHelper.WeeklyWideLoader.class)) collectClasses(type, classes);
        for (Class<?> type : classes.values()) {
            try (InputStream stream = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
                if (stream == null) throw new IllegalStateException("Class unavailable: " + type);
                digest.update(stream.readAllBytes());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void collectClasses(Class<?> type, Map<String, Class<?>> classes) {
        if (classes.putIfAbsent(type.getName(), type) == null)
            for (Class<?> nested : type.getDeclaredClasses()) collectClasses(nested, classes);
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String encode(double[] y) {
        StringBuilder out = new StringBuilder();
        for (double x : y) out.append(x > 0.5 ? '1' : '0');
        return out.toString();
    }
    private static double sampleSd(List<Double> costs, double mean) {
        double sum = 0;
        for (double value : costs) sum += (value - mean) * (value - mean);
        return costs.size() > 1 ? Math.sqrt(sum / (costs.size() - 1)) : Double.NaN;
    }
    private static boolean tableComplete(Path file, int rows) throws Exception {
        return Files.exists(file) && Files.size(file) > 0 && Files.readAllLines(file).size() == rows;
    }
    static void atomic(Path file, String text) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        for (int attempt = 0; ; attempt++) {
            try {
                Files.writeString(temporary, text, StandardCharsets.UTF_8);
                try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (AtomicMoveNotSupportedException error) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
                return;
            } catch (AccessDeniedException error) {
                // Windows scanners/readers may briefly hold the destination; never swallow a persistent error.
                if (attempt >= 3) throw error;
                Thread.sleep(100L * (attempt + 1));
            }
        }
    }
}
