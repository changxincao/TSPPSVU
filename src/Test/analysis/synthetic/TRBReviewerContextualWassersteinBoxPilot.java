package Test.analysis.synthetic;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Model.ContextualWassersteinBoxCcgSolver;
import Model.SAAModel;
import Model.SecondStageEvaluator;
import Model.Solution;
import Model.WassersteinBoxInput;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSolveBridge.PreparedInput;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Small, paired effect-and-runtime gate for the bounded-box contextual
 * Wasserstein extension requested in Reviewer 3, Comment 1.
 *
 * <p>The pilot freezes the R7 demand/market seed, equality demand balance,
 * h=min(r), kernel weights, OOS draws and all procurement parameters. It only
 * changes the support box and Wasserstein radius. Radius multipliers are fixed
 * before any OOS evaluation and are applied to a training-only normalized
 * demand-dispersion statistic.</p>
 */
public final class TRBReviewerContextualWassersteinBoxPilot {
    private static final int I = 10;
    private static final int J = 23;
    private static final int OOS_DRAWS = 200;
    private static final double[] RADIUS_MULTIPLIERS = configuredRadiusMultipliers();
    private static final List<String> BOX_RULES = configuredBoxRules();
    private static final long TRAINING_SEED = Long.getLong("trb.wasserstein.trainingSeed", 1_000_101L);
    private static final long QUERY_SEED = Long.getLong("trb.wasserstein.querySeed", 2_101_001L);

    private TRBReviewerContextualWassersteinBoxPilot() {
    }

    /** Usage: {@code <empty-output-dir> [threads] [time-limit-sec]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: <empty-output-dir> [threads] [time-limit-sec]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int timeLimitSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 600;
        requireEmpty(root);
        Files.createDirectories(root);

        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                J, 2300.0, 0.65, 20260809L);
        int[] groups = TRBReviewerGroupSpecializedProcurementFactory.balancedLaneGroups(
                J, 3, 20260810L);
        Config procurementConfig = new Config();
        procurementConfig.seed = 0;
        ProcurementParams fullMarket = InstanceGenerator.generate(
                I, baseline, new InstanceGenerator.GenConfig(), procurementConfig);
        ProcurementParams params = TRBReviewerR7CoverageMqcGridExperiment.withNestedCoverage(
                fullMarket, baseline, 1.0, 1.0, "min", false);

        ReplicationData replication = TRBReviewerDecisionRelevantDemandGenerator.generate(
                TRBReviewerR7CoverageMqcGridExperiment.r7Settings(OOS_DRAWS),
                baseline, groups, TRAINING_SEED, QUERY_SEED);
        log1pContexts(replication);

        Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                Method.CSAA, 3, 1.0, 1.0, threads, timeLimitSeconds);
        config.maxBendersIter = 500;
        config.tol = Math.min(config.tol, 1e-6);
        PreparedInput prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                replication, params, config);
        Data data = prepared.solveData;
        double[] scale = trainingScale(data.samples, baseline);
        double dispersion = weightedDispersion(data.samples, scale);

        List<Row> rows = new ArrayList<>();
        long csaaStart = System.nanoTime();
        Solution csaa = new SAAModel().solve(data, config, null);
        double csaaSeconds = (System.nanoTime() - csaaStart) / 1.0e9;
        rows.add(row("CSAA", "NONE", 0.0, 0.0, Double.NaN,
                csaa, csaaSeconds, params, prepared.oosSamples));

        for (String boxRule : BOX_RULES) {
            double[] upper = boxUpper(boxRule, data.samples, J);
            for (double multiplier : RADIUS_MULTIPLIERS) {
                double radius = multiplier * dispersion;
                WassersteinBoxInput input = WassersteinBoxInput.fromData(
                        data, upper, scale, radius);
                if (radius == 0.0) {
                    rows.add(row("WASSERSTEIN", boxRule, multiplier, radius,
                            input.lipschitzBound(), csaa, csaaSeconds,
                            params, prepared.oosSamples));
                } else {
                    ContextualWassersteinBoxCcgSolver.Result solved =
                            new ContextualWassersteinBoxCcgSolver().solve(input, config);
                    requireCertified(boxRule, multiplier, solved.solution());
                    rows.add(row("WASSERSTEIN", boxRule, multiplier, radius,
                            solved.eta(), solved.solution(), solved.solution().solveTimeSec,
                            params, prepared.oosSamples));
                }
            }
        }

        validateZeroRadius(rows, csaa);
        writeSummary(root.resolve("summary.csv"), rows, dispersion);
        writeInputs(root.resolve("configuration.txt"), data, baseline, scale,
                dispersion, config);
    }

    private static void requireCertified(String boxRule,
                                         double radiusMultiplier,
                                         Solution solution) {
        if (solution.certifiedOptimal) return;
        throw new IllegalStateException(String.format(Locale.US,
                "Uncertified Wasserstein result for box=%s,radiusMultiplier=%.8f: "
                        + "status=%s,objective=%.12f,bestBound=%.12f,gap=%.12g",
                boxRule, radiusMultiplier, solution.solverStatus,
                solution.objValue, solution.bestBound, solution.relativeGap));
    }

    private static Row row(String method,
                           String boxRule,
                           double radiusMultiplier,
                           double radius,
                           double eta,
                           Solution solution,
                           double seconds,
                           ProcurementParams params,
                           List<Sample> oos) throws Exception {
        double[] total = new double[oos.size()];
        double transport = 0.0;
        double spot = 0.0;
        double penalty = 0.0;
        double shortfall = 0.0;
        for (int draw = 0; draw < oos.size(); draw++) {
            SecondStageEvaluator.Result value = SecondStageEvaluator.evaluate(
                    params, solution.y, oos.get(draw).demand(), true);
            total[draw] = value.objective;
            transport += value.transportCost;
            spot += value.spotCost;
            penalty += value.penaltyCost;
            shortfall += value.mqcShortfallQuantity;
        }
        Arrays.sort(total);
        double mean = Arrays.stream(total).average().orElseThrow();
        double variance = 0.0;
        for (double value : total) variance += (value - mean) * (value - mean);
        double q95 = quantile(total, 0.95);
        double cvar95 = Arrays.stream(total).filter(value -> value >= q95)
                .average().orElseThrow();
        int selected = 0;
        for (double value : solution.y) if (value > 0.5) selected++;
        return new Row(method, boxRule, radiusMultiplier, radius, eta,
                solution.objValue, selected, binary(solution.y), mean,
                Math.sqrt(variance / total.length), q95, cvar95,
                transport / total.length, spot / total.length,
                penalty / total.length, shortfall / total.length,
                seconds, solution.iterationCount, solution.cutCount,
                solution.candidateCount, solution.solverStatus,
                solution.certifiedOptimal, solution.relativeGap);
    }

    private static double[] boxUpper(String rule,
                                     List<Sample> training,
                                     int laneCount) {
        double[] maximum = new double[laneCount];
        for (Sample sample : training) {
            for (int j = 0; j < maximum.length; j++) {
                maximum[j] = Math.max(maximum[j], sample.demand()[j]);
            }
        }
        double[] upper = new double[laneCount];
        for (int j = 0; j < upper.length; j++) {
            upper[j] = switch (rule) {
                case "TRAIN_MAX_100" -> maximum[j];
                default -> throw new IllegalArgumentException("Unknown box rule " + rule);
            };
        }
        return upper;
    }

    private static double[] trainingScale(List<Sample> samples, double[] baseline) {
        int J = baseline.length;
        double[] mean = new double[J];
        for (Sample sample : samples) {
            for (int j = 0; j < J; j++) mean[j] += sample.demand()[j];
        }
        for (int j = 0; j < J; j++) mean[j] /= samples.size();
        double[] scale = new double[J];
        for (Sample sample : samples) {
            for (int j = 0; j < J; j++) {
                double difference = sample.demand()[j] - mean[j];
                scale[j] += difference * difference;
            }
        }
        for (int j = 0; j < J; j++) {
            scale[j] = Math.sqrt(scale[j] / samples.size());
            scale[j] = Math.max(scale[j], Math.max(0.05 * mean[j], 0.01 * baseline[j]));
        }
        return scale;
    }

    private static double weightedDispersion(List<Sample> samples, double[] scale) {
        int J = scale.length;
        double sumWeight = samples.stream().mapToDouble(sample -> sample.weight).sum();
        double[] mean = new double[J];
        for (Sample sample : samples) {
            double weight = sample.weight / sumWeight;
            for (int j = 0; j < J; j++) mean[j] += weight * sample.demand()[j];
        }
        double dispersion = 0.0;
        for (Sample sample : samples) {
            double distance = 0.0;
            for (int j = 0; j < J; j++) {
                distance += Math.abs(sample.demand()[j] - mean[j]) / scale[j];
            }
            dispersion += sample.weight / sumWeight * distance;
        }
        return dispersion;
    }

    private static void validateZeroRadius(List<Row> rows, Solution csaa) {
        for (Row row : rows) {
            if (!row.method.equals("WASSERSTEIN") || row.radiusMultiplier != 0.0) continue;
            double objectiveTolerance = 1e-5 * Math.max(1.0, Math.abs(csaa.objValue));
            if (Math.abs(row.modelObjective - csaa.objValue) > objectiveTolerance
                    || !row.yBinary.equals(binary(csaa.y))) {
                throw new IllegalStateException(
                        "epsilon=0 does not reproduce CSAA for box=" + row.boxRule
                                + ": objective=" + row.modelObjective
                                + " vs " + csaa.objValue + ", y=" + row.yBinary
                                + " vs " + binary(csaa.y));
            }
        }
    }

    private static void writeSummary(Path path, List<Row> rows, double dispersion) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("method,boxRule,radiusMultiplier,trainingDispersion,radius,eta,modelObjective,"
                    + "selectedCount,yBinary,mean,sd,q95,cvar95,transport,spot,penalty,"
                    + "mqcShortfall,solveTimeSec,iterations,cuts,separationSolves,status,certified,gap");
            out.newLine();
            for (Row row : rows) {
                out.write(String.format(Locale.US,
                        "%s,%s,%.8f,%.12f,%.12f,%.12f,%.12f,%d,%s,"
                                + "%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,"
                                + "%.6f,%d,%d,%d,%s,%s,%.12g%n",
                        row.method, row.boxRule, row.radiusMultiplier, dispersion,
                        row.radius, row.eta, row.modelObjective, row.selectedCount,
                        row.yBinary, row.mean, row.sd, row.q95, row.cvar95,
                        row.transport, row.spot, row.penalty, row.mqcShortfall,
                        row.solveTime, row.iterations, row.cuts, row.separationSolves,
                        row.status, row.certified, row.gap));
            }
        }
    }

    private static void writeInputs(Path path,
                                    Data data,
                                    double[] baseline,
                                    double[] scale,
                                    double dispersion,
                                    Config config) throws Exception {
        List<String> lines = List.of(
                "Baseline=TRB-MQC-COVERAGE-BASELINE-20260813",
                "DGP=R7 independent training and conditional OOS draws",
                "I=10,J=23,S=" + data.samples.size() + ",OOS=" + OOS_DRAWS,
                "procurementSeed=0,trainingSeed=" + TRAINING_SEED + ",querySeed=" + QUERY_SEED,
                "coverage=1.0,mqcScale=1.0,h=min eligible r,demandBalance=equality",
                "kernel=exponential,C_h=1.0,standardizeTheta=true,lag=3",
                "boxes=" + BOX_RULES,
                "radiusMultipliers=" + Arrays.toString(RADIUS_MULTIPLIERS),
                "trainingDispersion=" + dispersion,
                "threads=" + config.threads + ",timeLimitSec=" + config.timeLimitSeconds,
                "bendersTolerance=" + config.tol
                        + ",maxBendersIterations=" + config.maxBendersIter,
                "baseline=" + Arrays.toString(baseline),
                "demandScale=" + Arrays.toString(scale));
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static void log1pContexts(ReplicationData demand) {
        for (Sample sample : demand.trainingSamples) log1p(sample.theta.values());
        log1p(demand.thetaNow.values());
        for (Sample sample : demand.oosSamples) log1p(sample.theta.values());
    }

    private static double[] configuredRadiusMultipliers() {
        String raw = System.getProperty("trb.wasserstein.radiusMultipliers", "0,0.025");
        String[] tokens = raw.split(",");
        double[] values = new double[tokens.length];
        for (int index = 0; index < tokens.length; index++) {
            values[index] = Double.parseDouble(tokens[index].trim());
            if (!Double.isFinite(values[index]) || values[index] < 0.0) {
                throw new IllegalArgumentException("Invalid radius multiplier " + values[index]);
            }
        }
        return values;
    }

    private static List<String> configuredBoxRules() {
        String raw = System.getProperty(
                "trb.wasserstein.boxRules", "TRAIN_MAX_100");
        List<String> rules = Arrays.stream(raw.split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (rules.isEmpty()) throw new IllegalArgumentException("At least one box rule is required.");
        for (String rule : rules) {
            if (!rule.equals("TRAIN_MAX_100")) {
                throw new IllegalArgumentException("Unknown box rule " + rule);
            }
        }
        return rules;
    }

    private static void log1p(double[] values) {
        for (int index = 0; index < values.length; index++) values[index] = Math.log1p(values[index]);
    }

    private static double quantile(double[] sorted, double probability) {
        double position = probability * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double fraction = position - lower;
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction;
    }

    private static String binary(double[] y) {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < y.length; i++) {
            if (i > 0) value.append(';');
            value.append(y[i] > 0.5 ? '1' : '0');
        }
        return value.append(']').toString();
    }

    private static void requireEmpty(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output directory must be empty: " + root);
            }
        }
    }

    private record Row(String method, String boxRule, double radiusMultiplier,
                       double radius, double eta, double modelObjective,
                       int selectedCount, String yBinary, double mean, double sd,
                       double q95, double cvar95, double transport, double spot,
                       double penalty, double mqcShortfall, double solveTime,
                       int iterations, int cuts, long separationSolves,
                       String status, boolean certified, double gap) {
    }
}
