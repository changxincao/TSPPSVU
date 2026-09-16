package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Model.SecondStageEvaluator;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceIO.StoredInstance;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Reviewer 4, M6 mechanism experiment on one frozen synthetic instance.
 *
 * <p>Coverage conditions retain the original rates and capacities on eligible
 * carrier-lane pairs.  A fixed, outcome-independent balanced mask gives every
 * carrier the nearest feasible number of lanes to 100%, 75%, or 50% coverage.
 * MQC quantity and penalty are then recomputed by the same economic rules as
 * {@code InstanceGenerator}: p_i is proportional to eligible baseline demand
 * and h_i is the maximum retained contract rate.  The separate MQC conditions
 * scale only p_i, so the two mechanisms are not conflated.</p>
 *
 * <p>The demand-shift diagnostic fixes each baseline decision and multiplies
 * every conditional OOS draw by 1.0, 1.1, or 1.2.  For a lognormal instance
 * this remains lognormal and changes its conditional mean; it is not described
 * as merely "switching to lognormal" because the formal instance is already
 * lognormal.</p>
 */
public final class TRBReviewerR4M6CoverageMqcMechanismExperiment {

    private static final long COVERAGE_MASK_SEED = 20260809L;
    private static final double[] DEMAND_SHIFTS = {1.0, 1.1, 1.2};
    private static final Method[] METHODS = {
            Method.D, Method.SAA, Method.CSAA, Method.RCSAA_LBBD
    };

    private TRBReviewerR4M6CoverageMqcMechanismExperiment() {
    }

    /** Usage: {@code <instance-dir> <output-dir> [threads] [time-limit-sec] [condition-filter]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Usage: <instance-dir> <output-dir> [threads] [time-limit-sec] [condition-filter]");
        }
        Path instanceDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        int timeLimitSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 3600;
        String conditionFilter = args.length > 4 ? args[4] : "ALL";
        requireEmptyDirectory(outputDirectory);

        StoredInstance stored = TRBReviewerR3M3SyntheticInstanceIO.load(instanceDirectory);
        if (!stored.hasProcurementParams()) {
            throw new IllegalArgumentException("Saved instance contains no procurement parameters.");
        }
        if (stored.settings.innovationDistribution != InnovationDistribution.LOGNORMAL) {
            throw new IllegalArgumentException(
                    "Demand-shift diagnostic expects a LOGNORMAL saved instance.");
        }

        List<Condition> conditions = List.of(
                new Condition("coverage_100", "coverage", 1.00, stored.procurementParams),
                new Condition("coverage_75", "coverage", 0.75,
                        withCoverage(stored.procurementParams, stored.demandData.baselineDemand, 0.75, false)),
                new Condition("coverage_50", "coverage", 0.50,
                        withCoverage(stored.procurementParams, stored.demandData.baselineDemand, 0.50, false)),
                new Condition("coverage_75_capacity_normalized", "coverage_capacity_normalized", 0.75,
                        withCoverage(stored.procurementParams, stored.demandData.baselineDemand, 0.75, true)),
                new Condition("coverage_50_capacity_normalized", "coverage_capacity_normalized", 0.50,
                        withCoverage(stored.procurementParams, stored.demandData.baselineDemand, 0.50, true)),
                new Condition("mqc_quantity_99", "mqc_quantity_scale", 0.99,
                        withMqcQuantityScale(stored.procurementParams, 0.99)),
                new Condition("mqc_quantity_98", "mqc_quantity_scale", 0.98,
                        withMqcQuantityScale(stored.procurementParams, 0.98)),
                new Condition("mqc_quantity_97", "mqc_quantity_scale", 0.97,
                        withMqcQuantityScale(stored.procurementParams, 0.97)),
                new Condition("mqc_quantity_95", "mqc_quantity_scale", 0.95,
                        withMqcQuantityScale(stored.procurementParams, 0.95)),
                new Condition("mqc_quantity_90", "mqc_quantity_scale", 0.90,
                        withMqcQuantityScale(stored.procurementParams, 0.90)),
                new Condition("mqc_quantity_85", "mqc_quantity_scale", 0.85,
                        withMqcQuantityScale(stored.procurementParams, 0.85)),
                new Condition("mqc_quantity_75", "mqc_quantity_scale", 0.75,
                        withMqcQuantityScale(stored.procurementParams, 0.75)),
                new Condition("mqc_quantity_50", "mqc_quantity_scale", 0.50,
                        withMqcQuantityScale(stored.procurementParams, 0.50)),
                new Condition("mqc_quantity_105", "mqc_quantity_scale", 1.05,
                        withMqcQuantityScale(stored.procurementParams, 1.05)),
                new Condition("mqc_quantity_110", "mqc_quantity_scale", 1.10,
                        withMqcQuantityScale(stored.procurementParams, 1.10)),
                new Condition("mqc_penalty_75", "mqc_penalty_scale", 0.75,
                        withMqcPenaltyScale(stored.procurementParams, 0.75)),
                new Condition("mqc_penalty_50", "mqc_penalty_scale", 0.50,
                        withMqcPenaltyScale(stored.procurementParams, 0.50)),
                new Condition("mqc_penalty_125", "mqc_penalty_scale", 1.25,
                        withMqcPenaltyScale(stored.procurementParams, 1.25)),
                new Condition("mqc_penalty_150", "mqc_penalty_scale", 1.50,
                        withMqcPenaltyScale(stored.procurementParams, 1.50)),
                new Condition("mqc_penalty_min_rate", "mqc_penalty_min_rate", Double.NaN,
                        withMqcMinimumRatePenalty(stored.procurementParams)));

        try (BufferedWriter summary = Files.newBufferedWriter(
                outputDirectory.resolve("mechanism_summary.csv"), StandardCharsets.UTF_8)) {
            summary.write("condition,mechanism,target,actualCoverageMin,actualCoverageMean,"
                    + "actualCoverageMax,method,selectedCount,meanOosCost,q95OosCost,cvar95OosCost,"
                    + "meanTransportCost,meanSpotCost,meanMqcPenalty,certifiedOptimal,relativeGap,solveTimeSec");
            summary.newLine();

            for (Condition condition : conditions) {
                if (!conditionFilter.equalsIgnoreCase("ALL")
                        && !condition.name.matches(conditionFilter)) continue;
                CoverageStats coverage = coverageStats(condition.params);
                for (Method method : METHODS) {
                    Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                            method, stored.settings.observedLagPeriods,
                            1.0, 1.0, threads, timeLimitSeconds);
                    Path methodOutput = outputDirectory.resolve(condition.name).resolve(method.name());
                    TRBReviewerR3M3SyntheticMainSolve.Result result =
                            TRBReviewerR3M3SyntheticMainSolve.run(
                                    method.name(), stored.demandData, condition.params,
                                    config, methodOutput);
                    writeMechanismRow(summary, condition, coverage, result);

                    if (condition.name.equals("coverage_100")) {
                        evaluateDemandShifts(outputDirectory, method, result.yBinary,
                                stored.procurementParams, stored.demandData.oosSamples);
                    }
                }
            }
        }
    }

    private static ProcurementParams withCoverage(ProcurementParams source,
                                                   double[] baselineDemand,
                                                   double targetCoverage,
                                                   boolean normalizeCapacity) {
        int lanesPerCarrier = Math.max(1,
                Math.min(source.J, (int) Math.round(targetCoverage * source.J)));
        List<Integer> laneOrder = new ArrayList<>(source.J);
        for (int j = 0; j < source.J; j++) laneOrder.add(j);
        Collections.shuffle(laneOrder, new Random(COVERAGE_MASK_SEED));

        double totalBaseline = Arrays.stream(baselineDemand).sum();
        double[][] q = new double[source.I][source.J];
        double[][] r = new double[source.I][source.J];
        boolean[][] eligible = new boolean[source.I][source.J];
        double[] p = new double[source.I];
        double[] h = new double[source.I];

        for (int i = 0; i < source.I; i++) {
            int start = (int) Math.floor((double) i * source.J / source.I);
            double eligibleBaseline = 0.0;
            for (int offset = 0; offset < lanesPerCarrier; offset++) {
                int j = laneOrder.get((start + offset) % source.J);
                eligible[i][j] = true;
                q[i][j] = source.q[i][j] * (normalizeCapacity ? 1.0 / targetCoverage : 1.0);
                r[i][j] = source.r[i][j];
                eligibleBaseline += baselineDemand[j];
                h[i] = Math.max(h[i], r[i][j]);
            }
            p[i] = totalBaseline == 0.0
                    ? 0.0 : source.p[i] * eligibleBaseline / totalBaseline;
        }
        for (int j = 0; j < source.J; j++) {
            boolean covered = false;
            for (int i = 0; i < source.I; i++) covered |= eligible[i][j];
            if (!covered) {
                throw new IllegalArgumentException(
                        "Coverage mask leaves lane " + j + " without a contract carrier.");
            }
        }
        return new ProcurementParams(source.carriers, source.J, source.e.clone(),
                p, h, q, r, eligible, source.alpha, source.beta);
    }

    private static ProcurementParams withMqcQuantityScale(ProcurementParams source,
                                                           double factor) {
        double[] p = source.p.clone();
        for (int i = 0; i < p.length; i++) p[i] *= factor;
        return new ProcurementParams(source.carriers, source.J, source.e.clone(),
                p, source.h.clone(), deepCopy(source.q), deepCopy(source.r),
                deepCopy(source.eligible), source.alpha, source.beta);
    }

    private static ProcurementParams withMqcPenaltyScale(ProcurementParams source,
                                                          double factor) {
        double[] h = source.h.clone();
        for (int i = 0; i < h.length; i++) h[i] *= factor;
        return new ProcurementParams(source.carriers, source.J, source.e.clone(),
                source.p.clone(), h, deepCopy(source.q), deepCopy(source.r),
                deepCopy(source.eligible), source.alpha, source.beta);
    }

    private static ProcurementParams withMqcMinimumRatePenalty(ProcurementParams source) {
        double[] h = new double[source.I];
        for (int i = 0; i < source.I; i++) {
            h[i] = Double.POSITIVE_INFINITY;
            for (int j = 0; j < source.J; j++) {
                if (source.eligible[i][j]) h[i] = Math.min(h[i], source.r[i][j]);
            }
            if (!Double.isFinite(h[i])) {
                throw new IllegalArgumentException("Carrier " + i + " has no eligible lane.");
            }
        }
        return new ProcurementParams(source.carriers, source.J, source.e.clone(),
                source.p.clone(), h, deepCopy(source.q), deepCopy(source.r),
                deepCopy(source.eligible), source.alpha, source.beta);
    }

    private static void evaluateDemandShifts(Path outputDirectory,
                                             Method method,
                                             String yBinary,
                                             ProcurementParams params,
                                             List<Sample> oosSamples) throws Exception {
        double[] y = parseBinary(yBinary);
        Path methodDirectory = outputDirectory.resolve("demand_mean_shift").resolve(method.name());
        Files.createDirectories(methodDirectory);
        try (BufferedWriter rows = Files.newBufferedWriter(
                     methodDirectory.resolve("oos_costs.csv"), StandardCharsets.UTF_8);
             BufferedWriter summary = Files.newBufferedWriter(
                     methodDirectory.resolve("summary.csv"), StandardCharsets.UTF_8)) {
            rows.write("method,demandMeanScale,drawId,totalCost,transportCost,spotCost,mqcPenalty");
            rows.newLine();
            summary.write("method,demandMeanScale,selectedCount,meanOosCost,q95OosCost,cvar95OosCost,"
                    + "meanTransportCost,meanSpotCost,meanMqcPenalty");
            summary.newLine();
            for (double scale : DEMAND_SHIFTS) {
                List<Cost> costs = new ArrayList<>(oosSamples.size());
                for (int draw = 0; draw < oosSamples.size(); draw++) {
                    double[] demand = oosSamples.get(draw).demand().clone();
                    for (int j = 0; j < demand.length; j++) demand[j] *= scale;
                    SecondStageEvaluator.Result result = SecondStageEvaluator.evaluate(
                            params, y, demand, true);
                    Cost cost = new Cost(result.objective, result.transportCost,
                            result.spotCost, result.penaltyCost);
                    costs.add(cost);
                    rows.write(String.format(Locale.US, "%s,%.2f,%d,%.17g,%.17g,%.17g,%.17g%n",
                            method, scale, draw, cost.total, cost.transport,
                            cost.spot, cost.penalty));
                }
                writeShiftSummary(summary, method, scale, y, costs);
            }
        }
    }

    private static void writeMechanismRow(BufferedWriter out,
                                          Condition condition,
                                          CoverageStats coverage,
                                          TRBReviewerR3M3SyntheticMainSolve.Result result) throws Exception {
        out.write(String.format(Locale.US,
                "%s,%s,%.2f,%.9f,%.9f,%.9f,%s,%d,%.17g,%.17g,%.17g,"
                        + "%.17g,%.17g,%.17g,%s,%.17g,%.9f%n",
                condition.name, condition.mechanism, condition.target,
                coverage.min, coverage.mean, coverage.max,
                result.methodLabel, result.selectedCount,
                result.meanOosCost, result.q95OosCost, result.cvar95OosCost,
                result.meanTransportCost, result.meanSpotCost, result.meanPenaltyCost,
                result.certifiedOptimal, result.relativeGap, result.optimizerTimeSeconds));
        out.flush();
    }

    private static void writeShiftSummary(BufferedWriter out,
                                          Method method,
                                          double scale,
                                          double[] y,
                                          List<Cost> costs) throws Exception {
        double[] totals = costs.stream().mapToDouble(c -> c.total).sorted().toArray();
        double q95 = quantile(totals, 0.95);
        double tail = Arrays.stream(totals).filter(v -> v >= q95).average().orElse(Double.NaN);
        double transport = costs.stream().mapToDouble(c -> c.transport).average().orElse(Double.NaN);
        double spot = costs.stream().mapToDouble(c -> c.spot).average().orElse(Double.NaN);
        double penalty = costs.stream().mapToDouble(c -> c.penalty).average().orElse(Double.NaN);
        long selected = Arrays.stream(y).filter(v -> v > 0.5).count();
        out.write(String.format(Locale.US, "%s,%.2f,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                method, scale, selected, Arrays.stream(totals).average().orElse(Double.NaN),
                q95, tail, transport, spot, penalty));
        out.flush();
    }

    private static CoverageStats coverageStats(ProcurementParams params) {
        double min = 1.0;
        double max = 0.0;
        double sum = 0.0;
        for (int i = 0; i < params.I; i++) {
            int count = 0;
            for (int j = 0; j < params.J; j++) if (params.eligible[i][j]) count++;
            double coverage = (double) count / params.J;
            min = Math.min(min, coverage);
            max = Math.max(max, coverage);
            sum += coverage;
        }
        return new CoverageStats(min, sum / params.I, max);
    }

    private static double[] parseBinary(String binary) {
        String body = binary.substring(1, binary.length() - 1);
        String[] tokens = body.split(",");
        double[] y = new double[tokens.length];
        for (int i = 0; i < tokens.length; i++) y[i] = Double.parseDouble(tokens[i]);
        return y;
    }

    private static double quantile(double[] sortedValues, double probability) {
        double position = probability * (sortedValues.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sortedValues[lower];
        double fraction = position - lower;
        return sortedValues[lower] * (1.0 - fraction) + sortedValues[upper] * fraction;
    }

    private static double[][] deepCopy(double[][] source) {
        double[][] copy = new double[source.length][];
        for (int i = 0; i < source.length; i++) copy[i] = source[i].clone();
        return copy;
    }

    private static boolean[][] deepCopy(boolean[][] source) {
        boolean[][] copy = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) copy[i] = source[i].clone();
        return copy;
    }

    private static void requireEmptyDirectory(Path directory) throws Exception {
        if (Files.exists(directory)) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Output directory must be empty: " + directory);
                }
            }
        } else {
            Files.createDirectories(directory);
        }
    }

    private record Condition(String name, String mechanism, double target,
                             ProcurementParams params) {
    }

    private record CoverageStats(double min, double mean, double max) {
    }

    private record Cost(double total, double transport, double spot, double penalty) {
    }
}
