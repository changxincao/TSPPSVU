package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Mode;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Random;

/**
 * R7 mechanism grid for Reviewer 4, M6: lane coverage versus MQC quantity.
 *
 * <p>The demand DGP, full-coverage rates, capacities, spot rates, procurement
 * seed, and all solver parameters are frozen.  The 100/75/50 percent masks are
 * nested and outcome independent.  For every retained market,
 * {@code p_i = u_i * sum(eligible baseline demand) * quantityScale}, where the
 * original full-coverage generator draw {@code u_i in [0.1,0.2]} is reused.
 * The MQC shortfall rate is the configured minimum or maximum retained
 * eligible contract rate. Thus coverage never changes the random number
 * stream for r, q, or e.</p>
 *
 * <p>FAST runs D/SAA/CSAA on the complete grid.  ALL additionally runs DRO and
 * exact enumerated RCSAA and is intended for a small cell filter.  Every method
 * receives the same training path, query context, and conditional OOS draws.</p>
 */
public final class TRBReviewerR7CoverageMqcGridExperiment {
    private static final int I = configuredCarrierCount();
    private static final int J = 23;
    private static final int S = configuredTrainingSampleCount();
    private static final int CONTINUOUS_HORIZON = configuredContinuousHorizon();
    private static final long BASELINE_SEED = 20260809L;
    private static final long GROUP_SEED = 20260810L;
    private static final long MASK_SEED = 20260811L;
    private static final double C_H = configuredDouble(
            "trb.synthetic.cH", 1.0, 0.0, Double.POSITIVE_INFINITY);
    private static final double[] COVERAGES = {1.00, 0.75, 0.50};
    private static final double[] MQC_SCALES = {0.50, 0.75, 1.00, 1.25};
    private static final String PENALTY_RULE = configuredPenaltyRule();
    private static final String DGP_PROFILE = configuredDgpProfile();
    private static final String TRAINING_PROTOCOL = configuredTrainingProtocol();
    private static final double INNOVATION_CV = configuredDouble(
            "trb.synthetic.innovationCv", 0.345, 0.0, Double.POSITIVE_INFINITY);
    private static final double SHARE_REGIME_PERSISTENCE = configuredDouble(
            "trb.synthetic.sharePersistence", 0.90, 0.0, 1.0);
    private static final double ACTIVE_GROUP_MULTIPLIER = configuredDouble(
            "trb.synthetic.activeGroupMultiplier", 2.00, 1.0,
            Double.POSITIVE_INFINITY);
    private static final double TOTAL_REGIME_MULTIPLIER = configuredDouble(
            "trb.synthetic.totalRegimeMultiplier", 1.30, 1.0, Double.POSITIVE_INFINITY);
    private static final double TOTAL_REGIME_PERSISTENCE = configuredDouble(
            "trb.synthetic.totalPersistence", 0.95, 0.0, 1.0);
    private static final double BETA_RATIO = configuredBetaRatio();
    private static final boolean NORMALIZE_COVERAGE_CAPACITY =
            Boolean.getBoolean("trb.coverage.normalizeCapacity");

    private TRBReviewerR7CoverageMqcGridExperiment() {
    }

    /**
     * Usage: {@code <output-dir> [cell-regex] [training-reps] [queries]
     * [oos-draws] [FAST|DRO_ONLY|RCSAA_ONLY|R4M30|R4M30_LBBD|ALL]
     * [lambda] [threads] [procurement-seed]
     * [seed-offset] [time-limit-sec]}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 11) {
            throw new IllegalArgumentException(
                    "Usage: <output-dir> [cell-regex] [training-reps] [queries] "
                            + "[oos-draws] [FAST|DRO_ONLY|RCSAA_ONLY|R4M30|R4M30_LBBD|ALL] "
                            + "[lambda] [threads] "
                            + "[procurement-seed] [seed-offset] [time-limit-sec]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        String cellRegex = args.length > 1 && !"ALL".equalsIgnoreCase(args[1])
                ? args[1] : ".*";
        int trainingReplications = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        int queries = args.length > 3 ? Integer.parseInt(args[3]) : 5;
        int oosDraws = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        String profile = args.length > 5 ? args[5].toUpperCase(Locale.ROOT) : "FAST";
        double lambda = args.length > 6 ? Double.parseDouble(args[6]) : 1.0;
        int threads = args.length > 7 ? Integer.parseInt(args[7]) : 1;
        int procurementSeed = args.length > 8 ? Integer.parseInt(args[8]) : 0;
        int seedOffset = args.length > 9 ? Integer.parseInt(args[9]) : 100;
        int timeLimitSeconds = args.length > 10 ? Integer.parseInt(args[10]) : 600;
        if (trainingReplications <= 0 || queries <= 0 || oosDraws <= 0
                || threads <= 0 || timeLimitSeconds <= 0 || seedOffset < 0
                || !(lambda > 0.0) || !Double.isFinite(lambda)) {
            throw new IllegalArgumentException("Invalid counts or solver parameters.");
        }
        if (!TRAINING_PROTOCOL.equals("independent") && queries != 1) {
            throw new IllegalArgumentException(
                    "Paired/continuous protocols require exactly one terminal query per path.");
        }
        Method[] methods = methods(profile);
        requireEmptyDirectory(root);
        Files.createDirectories(root);

        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                J, 2300.0, 0.65, BASELINE_SEED);
        int[] laneGroups = TRBReviewerGroupSpecializedProcurementFactory.balancedLaneGroups(
                J, 3, GROUP_SEED);
        Config procurementConfig = new Config();
        procurementConfig.seed = procurementSeed;
        InstanceGenerator.GenConfig marketConfig = new InstanceGenerator.GenConfig();
        marketConfig.betaRatio = BETA_RATIO;
        ProcurementParams fullMarket = InstanceGenerator.generate(
                I, baseline, marketConfig, procurementConfig);
        if (marketSizeScale() != 1.0) {
            fullMarket = withMarketSizeScale(fullMarket, marketSizeScale());
        }

        writeExperimentProperties(root.resolve("experiment.properties"), profile,
                trainingReplications, queries, oosDraws, lambda, threads,
                procurementSeed, seedOffset, timeLimitSeconds);

        List<QueryRow> rows = new ArrayList<>();
        Map<String, PooledAccumulator> pooled = new LinkedHashMap<>();
        int matchedCells = 0;
        for (double coverage : COVERAGES) {
            for (double quantityScale : MQC_SCALES) {
                String cell = cellName(coverage, quantityScale);
                if (!cell.matches(cellRegex)) continue;
                matchedCells++;
                ProcurementParams params = withNestedCoverage(
                        fullMarket, baseline, coverage, quantityScale,
                        PENALTY_RULE, NORMALIZE_COVERAGE_CAPACITY);
                CoverageStats coverageStats = coverageStats(params);
                writeCellProperties(root.resolve(cell).resolve("cell.properties"), cell,
                        coverage, quantityScale, params, coverageStats, baseline);

                for (int replication = 1; replication <= trainingReplications; replication++) {
                    long trainingSeed = 1_000_000L + seedOffset + replication;
                    for (int query = 1; query <= queries; query++) {
                        long querySeed = 2_000_000L
                                + 1000L * (seedOffset + replication) + query;
                        ReplicationData demand = switch (TRAINING_PROTOCOL) {
                            case "continuous" ->
                                    TRBReviewerDecisionRelevantDemandGenerator.generateContinuousPath(
                                            r7Settings(oosDraws), baseline, laneGroups,
                                            trainingSeed, querySeed, CONTINUOUS_HORIZON);
                            case "independent_paired_query" ->
                                    TRBReviewerDecisionRelevantDemandGenerator
                                            .generateIndependentTrainingAtContinuousQuery(
                                                    r7Settings(oosDraws), baseline, laneGroups,
                                                    trainingSeed, querySeed);
                            default -> TRBReviewerDecisionRelevantDemandGenerator.generate(
                                    r7Settings(oosDraws), baseline, laneGroups,
                                    trainingSeed, querySeed);
                        };
                        log1pContexts(demand);
                        DemandStats demandStats = demandStats(demand.oosSamples);

                        Integer rcsaaSelectedCount = null;
                        for (Method method : methods) {
                            ProcurementParams methodParams = params;
                            if (method == Method.CSAA_MATCHED_RCSAA_COUNT) {
                                if (rcsaaSelectedCount == null) {
                                    throw new IllegalStateException(
                                            "Matched-cardinality CSAA requires RCSAA first.");
                                }
                                methodParams = withSelectionBounds(
                                        params, rcsaaSelectedCount, rcsaaSelectedCount);
                            }
                            Config solveConfig =
                                    TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                                            method, 3, C_H, lambda, threads,
                                            timeLimitSeconds);
                            Path output = root.resolve(cell).resolve(String.format(Locale.US,
                                    "train_%02d/query_%03d/%s", replication, query, method));
                            TRBReviewerR3M3SyntheticMainSolve.Result result =
                                    TRBReviewerR3M3SyntheticMainSolve.run(method.name(),
                                            demand, methodParams, solveConfig, output);
                            if (method == Method.RCSAA_ENUMERATE
                                    || method == Method.RCSAA_LBBD
                                    || method == Method.RCSAA_LBBD_SEARCH) {
                                rcsaaSelectedCount = result.selectedCount;
                            }
                            DecisionStats decision = decisionStats(
                                    result.yBinary, params, demand.oosSamples);
                            rows.add(new QueryRow(cell, coverage, quantityScale,
                                    coverageStats, replication, query, trainingSeed,
                                    querySeed, demandStats, decision, result));
                            pooled.computeIfAbsent(cell + "|" + result.methodLabel,
                                    ignored -> new PooledAccumulator(cell, coverage,
                                            quantityScale, result.methodLabel))
                                    .add(result, decision, demandStats,
                                            output.resolve("oos_costs.csv"));
                        }
                    }
                }
            }
        }
        if (matchedCells == 0) {
            throw new IllegalArgumentException("No grid cell matches " + cellRegex);
        }
        writeQueryRows(root.resolve("query_results.csv"), rows);
        writePooledRows(root.resolve("pooled_method_summary.csv"), pooled);
    }

    private static Method[] methods(String profile) {
        return switch (profile) {
            case "FAST" -> new Method[]{Method.D, Method.SAA, Method.CSAA};
            case "CSAA_ONLY" -> new Method[]{Method.CSAA};
            case "DRO_ONLY" -> new Method[]{Method.DRO};
            case "RCSAA_ONLY" -> new Method[]{Method.RCSAA_ENUMERATE};
            case "RCSAA_SEARCH_ONLY" -> new Method[]{Method.RCSAA_LBBD_SEARCH};
            case "R4M30" -> new Method[]{Method.RCSAA_ENUMERATE,
                    Method.CSAA_MATCHED_RCSAA_COUNT};
            case "R4M30_LBBD" -> new Method[]{Method.RCSAA_LBBD,
                    Method.CSAA_MATCHED_RCSAA_COUNT};
            case "ALL" -> new Method[]{Method.D, Method.SAA, Method.CSAA,
                    Method.DRO, Method.RCSAA_ENUMERATE};
            default -> throw new IllegalArgumentException("Unknown profile: " + profile);
        };
    }

    static Settings r7Settings(int oosDraws) {
        Settings settings = new Settings();
        if (DGP_PROFILE.equals("lane_ar80_calibrated")) {
            settings.mode = Mode.LINEAR_GROUP_AR;
            settings.trainingSampleCount = S;
            settings.observedLagPeriods = 3;
            settings.warmupPeriods = 60;
            settings.oosSampleCount = oosDraws;
            settings.innovationCv = INNOVATION_CV;
            settings.latentCrossLaneCorrelation = 0.30;
            settings.longRunWeight = 0.20;
            settings.globalHistoryWeight = 0.0;
            settings.groupHistoryWeight = 0.0;
            settings.laneHistoryWeight = 0.80;
            return settings;
        }
        settings.mode = Mode.MARKOV_GROUP_SHARE;
        settings.trainingSampleCount = S;
        settings.observedLagPeriods = 3;
        settings.warmupPeriods = 60;
        settings.oosSampleCount = oosDraws;
        settings.innovationCv = INNOVATION_CV;
        settings.latentCrossLaneCorrelation = 0.30;
        settings.longRunWeight = 0.20;
        settings.globalHistoryWeight = 0.30;
        settings.groupHistoryWeight = 0.35;
        settings.laneHistoryWeight = 0.15;
        settings.regimePersistence = SHARE_REGIME_PERSISTENCE;
        settings.activeGroupMultiplier = ACTIVE_GROUP_MULTIPLIER;
        settings.inactiveGroupMultiplier = 1.00;
        settings.regimeShareWeight = 1.00;
        settings.markovTotalHistoryWeight = 0.80;
        settings.totalRegimeLogStep = Math.log(TOTAL_REGIME_MULTIPLIER);
        settings.totalRegimePersistence = TOTAL_REGIME_PERSISTENCE;
        settings.orderedTotalRegimeTransitions = true;
        settings.coupledTotalAndShareRegime = true;
        return settings;
    }

    /** Builds nested masks and applies the paper's eligible-demand MQC rule. */
    static ProcurementParams withNestedCoverage(ProcurementParams source,
                                                 double[] baseline,
                                                 double coverage,
                                                 double quantityScale) {
        return withNestedCoverage(source, baseline, coverage, quantityScale, PENALTY_RULE);
    }

    static ProcurementParams withNestedCoverage(ProcurementParams source,
                                                 double[] baseline,
                                                 double coverage,
                                                 double quantityScale,
                                                 String penaltyRule) {
        return withNestedCoverage(source, baseline, coverage, quantityScale,
                penaltyRule, false);
    }

    static ProcurementParams withNestedCoverage(ProcurementParams source,
                                                 double[] baseline,
                                                 double coverage,
                                                 double quantityScale,
                                                 String penaltyRule,
                                                 boolean normalizeCapacity) {
        int lanesPerCarrier = Math.max(1,
                Math.min(source.J, (int) Math.round(coverage * source.J)));
        double capacityScale = normalizeCapacity
                ? (double) source.J / lanesPerCarrier : 1.0;
        List<Integer> laneOrder = new ArrayList<>(source.J);
        for (int j = 0; j < source.J; j++) laneOrder.add(j);
        Collections.shuffle(laneOrder, new Random(MASK_SEED));

        double fullBaseline = Arrays.stream(baseline).sum();
        double[][] q = new double[source.I][source.J];
        double[][] r = new double[source.I][source.J];
        boolean[][] eligible = new boolean[source.I][source.J];
        double[] p = new double[source.I];
        double[] h = new double[source.I];

        for (int i = 0; i < source.I; i++) {
            int start = (int) Math.floor((double) i * source.J / source.I);
            double eligibleBaseline = 0.0;
            double minimumRate = Double.POSITIVE_INFINITY;
            double maximumRate = Double.NEGATIVE_INFINITY;
            for (int offset = 0; offset < lanesPerCarrier; offset++) {
                int j = laneOrder.get((start + offset) % source.J);
                eligible[i][j] = true;
                q[i][j] = source.q[i][j] * capacityScale;
                r[i][j] = source.r[i][j];
                eligibleBaseline += baseline[j];
                minimumRate = Math.min(minimumRate, r[i][j]);
                maximumRate = Math.max(maximumRate, r[i][j]);
            }
            double originalMqcFraction = source.p[i] / fullBaseline;
            p[i] = originalMqcFraction * eligibleBaseline * quantityScale;
            h[i] = "max".equals(penaltyRule) ? maximumRate : minimumRate;
        }
        for (int j = 0; j < source.J; j++) {
            boolean covered = false;
            for (int i = 0; i < source.I; i++) covered |= eligible[i][j];
            if (!covered) {
                throw new IllegalStateException("Nested mask leaves lane uncovered: " + j);
            }
        }
        return new ProcurementParams(source.carriers, source.J, source.e.clone(),
                p, h, q, r, eligible, source.alpha, source.beta);
    }

    static ProcurementParams withMarketSizeScale(ProcurementParams source,
                                                  double scale) {
        double[] p = source.p.clone();
        double[][] q = clone(source.q);
        for (int i = 0; i < source.I; i++) {
            p[i] *= scale;
            for (int j = 0; j < source.J; j++) q[i][j] *= scale;
        }
        return new ProcurementParams(source.carriers, source.J, source.e.clone(),
                p, source.h.clone(), q, clone(source.r),
                clone(source.eligible), source.alpha, source.beta);
    }

    private static ProcurementParams withSelectionBounds(ProcurementParams source,
                                                          int alpha,
                                                          int beta) {
        return new ProcurementParams(source.carriers, source.J, source.e,
                source.p, source.h, source.q, source.r, source.eligible, alpha, beta);
    }

    private static double[][] clone(double[][] matrix) {
        double[][] copy = new double[matrix.length][];
        for (int row = 0; row < matrix.length; row++) copy[row] = matrix[row].clone();
        return copy;
    }

    private static boolean[][] clone(boolean[][] matrix) {
        boolean[][] copy = new boolean[matrix.length][];
        for (int row = 0; row < matrix.length; row++) copy[row] = matrix[row].clone();
        return copy;
    }

    private static int configuredCarrierCount() {
        int carriers = Integer.getInteger("trb.synthetic.carriers", 10);
        if (carriers <= 0) {
            throw new IllegalArgumentException(
                    "trb.synthetic.carriers must be positive: " + carriers);
        }
        return carriers;
    }

    private static int configuredTrainingSampleCount() {
        int samples = Integer.getInteger("trb.synthetic.trainingSamples", 50);
        if (samples <= 0) {
            throw new IllegalArgumentException(
                    "trb.synthetic.trainingSamples must be positive: " + samples);
        }
        return samples;
    }

    private static int configuredContinuousHorizon() {
        int horizon = Integer.getInteger("trb.synthetic.continuousHorizon", S);
        if (horizon < S) {
            throw new IllegalArgumentException(
                    "trb.synthetic.continuousHorizon must be at least trainingSamples: "
                            + horizon + " < " + S);
        }
        return horizon;
    }

    private static String configuredPenaltyRule() {
        String rule = System.getProperty("trb.coverage.penalty", "min")
                .toLowerCase(Locale.ROOT);
        if (!rule.equals("min") && !rule.equals("max")) {
            throw new IllegalArgumentException(
                    "trb.coverage.penalty must be min or max: " + rule);
        }
        return rule;
    }

    private static String configuredDgpProfile() {
        String profile = System.getProperty("trb.synthetic.dgp", "r7")
                .toLowerCase(Locale.ROOT);
        if (!profile.equals("r7") && !profile.equals("lane_ar80_calibrated")) {
            throw new IllegalArgumentException(
                    "trb.synthetic.dgp must be r7 or lane_ar80_calibrated: " + profile);
        }
        return profile;
    }

    private static String configuredTrainingProtocol() {
        String protocol = System.getProperty("trb.synthetic.trainingProtocol", "independent")
                .toLowerCase(Locale.ROOT);
        if (!protocol.equals("independent") && !protocol.equals("continuous")
                && !protocol.equals("independent_paired_query")) {
            throw new IllegalArgumentException(
                    "trb.synthetic.trainingProtocol must be independent, "
                            + "independent_paired_query, or continuous: "
                            + protocol);
        }
        return protocol;
    }

    private static double configuredDouble(String property, double defaultValue,
                                           double lowerExclusive,
                                           double upperExclusive) {
        double value = Double.parseDouble(System.getProperty(
                property, Double.toString(defaultValue)));
        if (!(value > lowerExclusive && value < upperExclusive)
                || !Double.isFinite(value)) {
            throw new IllegalArgumentException(property + " out of range: " + value);
        }
        return value;
    }

    private static double marketSizeScale() {
        return 10.0 / I;
    }

    private static double configuredBetaRatio() {
        double ratio = Double.parseDouble(
                System.getProperty("trb.synthetic.betaRatio", "0.7"));
        if (!(ratio > 0.0 && ratio <= 1.0) || !Double.isFinite(ratio)) {
            throw new IllegalArgumentException(
                    "trb.synthetic.betaRatio must be in (0,1]: " + ratio);
        }
        return ratio;
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

    private static DemandStats demandStats(List<Sample> draws) {
        double sum = 0.0;
        double inverseSum = 0.0;
        for (Sample draw : draws) {
            double total = Arrays.stream(draw.demand()).sum();
            sum += total;
            inverseSum += 1.0 / total;
        }
        return new DemandStats(sum / draws.size(), inverseSum / draws.size());
    }

    private static DecisionStats decisionStats(String yBinary,
                                                ProcurementParams params,
                                                List<Sample> draws) {
        double[] y = parseBinary(yBinary);
        double selectedMqc = 0.0;
        for (int i = 0; i < y.length; i++) if (y[i] > 0.5) selectedMqc += params.p[i];
        double ratio = 0.0;
        double excess = 0.0;
        for (Sample draw : draws) {
            double demand = Arrays.stream(draw.demand()).sum();
            ratio += selectedMqc / demand;
            excess += Math.max(0.0, selectedMqc - demand);
        }
        return new DecisionStats(selectedMqc, ratio / draws.size(),
                excess / draws.size());
    }

    private static void log1pContexts(ReplicationData demand) {
        for (Sample sample : demand.trainingSamples) log1p(sample.theta.values());
        log1p(demand.thetaNow.values());
        for (Sample sample : demand.oosSamples) log1p(sample.theta.values());
    }

    private static void log1p(double[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] < 0.0 || !Double.isFinite(values[index])) {
                throw new IllegalArgumentException("LOG1P requires finite nonnegative contexts.");
            }
            values[index] = Math.log1p(values[index]);
        }
    }

    private static void writeQueryRows(Path path, List<QueryRow> rows) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("cell,targetCoverage,quantityScale,actualCoverageMin,actualCoverageMean,"
                    + "actualCoverageMax,trainingReplication,query,trainingSeed,querySeed,method,"
                    + "selectedCount,yBinary,meanOosCost,sdOosCost,q95OosCost,cvar95OosCost,"
                    + "meanTransportCost,meanSpotCost,meanMqcPenalty,selectedAggregateMqc,"
                    + "meanOosDemand,selectedMqcOverMeanDemand,meanSelectedMqcToDemandRatio,"
                    + "meanStructuralExcessMqc,certifiedOptimal,relativeGap,optimizerTimeSec");
            out.newLine();
            for (QueryRow row : rows) {
                var result = row.result;
                out.write(String.format(Locale.US,
                        "%s,%.2f,%.2f,%.12f,%.12f,%.12f,%d,%d,%d,%d,%s,%d,%s,"
                                + "%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%.12f,%.12f,%.12f,%s,%.12g,%.9f%n",
                        row.cell, row.coverage, row.quantityScale,
                        row.coverageStats.min, row.coverageStats.mean,
                        row.coverageStats.max, row.trainingReplication, row.query,
                        row.trainingSeed, row.querySeed, result.methodLabel,
                        result.selectedCount, csv(result.yBinary), result.meanOosCost,
                        result.sdOosCost, result.q95OosCost, result.cvar95OosCost,
                        result.meanTransportCost, result.meanSpotCost,
                        result.meanPenaltyCost, row.decision.selectedMqc,
                        row.demand.meanDemand,
                        row.decision.selectedMqc / row.demand.meanDemand,
                        row.decision.meanMqcToDemandRatio,
                        row.decision.meanStructuralExcessMqc,
                        result.certifiedOptimal, result.relativeGap,
                        result.optimizerTimeSeconds));
            }
        }
    }

    private static List<PooledRow> writePooledRows(
            Path path, Map<String, PooledAccumulator> accumulators) throws Exception {
        List<PooledRow> rows = new ArrayList<>();
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("cell,targetCoverage,quantityScale,method,queries,nDraws,pooledMean,"
                    + "pooledPopulationSd,pooledQ95,pooledCvar95,meanTransportCost,meanSpotCost,"
                    + "meanMqcPenalty,meanSelectedCount,meanSelectedAggregateMqc,meanOosDemand,"
                    + "selectedMqcOverMeanDemand,meanSelectedMqcToDemandRatio,"
                    + "meanStructuralExcessMqc,certifiedCount");
            out.newLine();
            for (PooledAccumulator accumulator : accumulators.values()) {
                PooledRow row = accumulator.finish();
                rows.add(row);
                out.write(String.format(Locale.US,
                        "%s,%.2f,%.2f,%s,%d,%d,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%d%n",
                        row.cell, row.coverage, row.quantityScale, row.method,
                        row.queries, row.draws, row.mean, row.sd, row.q95, row.cvar95,
                        row.transport, row.spot, row.penalty, row.selectedCount,
                        row.selectedMqc, row.meanDemand,
                        row.selectedMqc / row.meanDemand, row.meanMqcToDemandRatio,
                        row.meanStructuralExcessMqc, row.certifiedCount));
            }
        }
        return rows;
    }

    private static void writeExperimentProperties(Path path, String profile,
                                                  int replications, int queries,
                                                  int draws, double lambda, int threads,
                                                  int procurementSeed, int seedOffset,
                                                  int timeLimitSeconds) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("dgp", DGP_PROFILE);
        properties.setProperty("trainingProtocol", TRAINING_PROTOCOL);
        properties.setProperty("innovationCv", Double.toString(INNOVATION_CV));
        properties.setProperty("shareRegimePersistence",
                Double.toString(SHARE_REGIME_PERSISTENCE));
        properties.setProperty("activeGroupMultiplier",
                Double.toString(ACTIVE_GROUP_MULTIPLIER));
        properties.setProperty("totalRegimeMultiplier",
                Double.toString(TOTAL_REGIME_MULTIPLIER));
        properties.setProperty("totalRegimePersistence",
                Double.toString(TOTAL_REGIME_PERSISTENCE));
        properties.setProperty("carrierCount", Integer.toString(I));
        properties.setProperty("betaRatio", Double.toString(BETA_RATIO));
        properties.setProperty("marketSizeNormalization", Double.toString(marketSizeScale()));
        properties.setProperty("mqcPenalty", PENALTY_RULE + "imum retained eligible r_ij");
        properties.setProperty("mqcQuantityRule",
                "original U[0.1,0.2] draw times retained eligible baseline demand times quantityScale");
        properties.setProperty("coverageMask", "nested_balanced_seed_" + MASK_SEED);
        properties.setProperty("coverageCapacityNormalization",
                Boolean.toString(NORMALIZE_COVERAGE_CAPACITY));
        properties.setProperty("formalPairing",
                "disabled in solve runner; use TRBReviewerR7CoverageMqcGridPostprocess");
        properties.setProperty("profile", profile);
        properties.setProperty("trainingReplications", Integer.toString(replications));
        properties.setProperty("trainingSamples", Integer.toString(S));
        properties.setProperty("continuousHorizon", Integer.toString(CONTINUOUS_HORIZON));
        properties.setProperty("queriesPerTraining", Integer.toString(queries));
        properties.setProperty("oosDraws", Integer.toString(draws));
        properties.setProperty("C_h", Double.toString(C_H));
        properties.setProperty("lambda", Double.toString(lambda));
        properties.setProperty("threads", Integer.toString(threads));
        properties.setProperty("procurementSeed", Integer.toString(procurementSeed));
        properties.setProperty("seedOffset", Integer.toString(seedOffset));
        properties.setProperty("timeLimitSeconds", Integer.toString(timeLimitSeconds));
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(out, "R7 coverage and MQC quantity grid");
        }
    }

    private static void writeCellProperties(Path path, String cell,
                                            double targetCoverage, double quantityScale,
                                            ProcurementParams params,
                                            CoverageStats coverage,
                                            double[] baseline) throws Exception {
        Files.createDirectories(path.getParent());
        Properties properties = new Properties();
        properties.setProperty("cell", cell);
        properties.setProperty("targetCoverage", Double.toString(targetCoverage));
        properties.setProperty("quantityScale", Double.toString(quantityScale));
        properties.setProperty("actualCoverageMin", Double.toString(coverage.min));
        properties.setProperty("actualCoverageMean", Double.toString(coverage.mean));
        properties.setProperty("actualCoverageMax", Double.toString(coverage.max));
        properties.setProperty("totalMqcAllCarriers", Double.toString(Arrays.stream(params.p).sum()));
        properties.setProperty("baselineTotal", Double.toString(Arrays.stream(baseline).sum()));
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(out, "Frozen R7 grid cell");
        }
    }

    private static String cellName(double coverage, double quantityScale) {
        return String.format(Locale.US, "coverage_%03d_mqc_%03d",
                Math.round(100 * coverage), Math.round(100 * quantityScale));
    }

    private static double[] parseBinary(String binary) {
        String[] values = binary.substring(1, binary.length() - 1).split(",");
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = Double.parseDouble(values[i]);
        return result;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void requireEmptyDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output directory must be empty: " + directory);
            }
        }
    }

    private record CoverageStats(double min, double mean, double max) {
    }

    private record DemandStats(double meanDemand, double meanInverseDemand) {
    }

    private record DecisionStats(double selectedMqc, double meanMqcToDemandRatio,
                                 double meanStructuralExcessMqc) {
    }

    private record QueryRow(String cell, double coverage, double quantityScale,
                            CoverageStats coverageStats, int trainingReplication,
                            int query, long trainingSeed, long querySeed,
                            DemandStats demand, DecisionStats decision,
                            TRBReviewerR3M3SyntheticMainSolve.Result result) {
    }

    private record PooledRow(String cell, double coverage, double quantityScale,
                             String method, int queries, int draws, double mean,
                             double sd, double q95, double cvar95, double transport,
                             double spot, double penalty, double selectedCount,
                             double selectedMqc, double meanDemand,
                             double meanMqcToDemandRatio,
                             double meanStructuralExcessMqc, int certifiedCount) {
    }

    private static final class PooledAccumulator {
        final String cell;
        final double coverage;
        final double quantityScale;
        final String method;
        final List<Double> totalCosts = new ArrayList<>();
        double transport;
        double spot;
        double penalty;
        double selectedCount;
        double selectedMqc;
        double meanDemand;
        double meanMqcToDemandRatio;
        double meanStructuralExcessMqc;
        int queries;
        int certifiedCount;

        PooledAccumulator(String cell, double coverage,
                          double quantityScale, String method) {
            this.cell = cell;
            this.coverage = coverage;
            this.quantityScale = quantityScale;
            this.method = method;
        }

        void add(TRBReviewerR3M3SyntheticMainSolve.Result result,
                 DecisionStats decision, DemandStats demand, Path oosCosts) throws Exception {
            queries++;
            if (result.certifiedOptimal) certifiedCount++;
            selectedCount += result.selectedCount;
            selectedMqc += decision.selectedMqc;
            meanDemand += demand.meanDemand;
            meanMqcToDemandRatio += decision.meanMqcToDemandRatio;
            meanStructuralExcessMqc += decision.meanStructuralExcessMqc;
            try (BufferedReader reader = Files.newBufferedReader(oosCosts, StandardCharsets.UTF_8)) {
                reader.readLine();
                for (String line; (line = reader.readLine()) != null;) {
                    String[] fields = line.split(",");
                    if (fields.length != 6 && fields.length != 7) {
                        throw new IllegalStateException("Malformed OOS row: " + oosCosts);
                    }
                    totalCosts.add(Double.parseDouble(fields[2]));
                    transport += Double.parseDouble(fields[3]);
                    spot += Double.parseDouble(fields[4]);
                    penalty += Double.parseDouble(fields[5]);
                }
            }
        }

        PooledRow finish() {
            double[] values = totalCosts.stream().mapToDouble(Double::doubleValue).toArray();
            Arrays.sort(values);
            double mean = Arrays.stream(values).average().orElseThrow();
            double sumSquares = 0.0;
            for (double value : values) sumSquares += (value - mean) * (value - mean);
            double q95 = quantile(values, 0.95);
            double tail = Arrays.stream(values).filter(value -> value >= q95)
                    .average().orElseThrow();
            return new PooledRow(cell, coverage, quantityScale, method, queries,
                    values.length, mean, Math.sqrt(sumSquares / values.length),
                    q95, tail, transport / values.length, spot / values.length,
                    penalty / values.length, selectedCount / queries,
                    selectedMqc / queries, meanDemand / queries,
                    meanMqcToDemandRatio / queries,
                    meanStructuralExcessMqc / queries, certifiedCount);
        }

        private static double quantile(double[] values, double probability) {
            double position = probability * (values.length - 1);
            int lower = (int) Math.floor(position);
            int upper = (int) Math.ceil(position);
            if (lower == upper) return values[lower];
            double fraction = position - lower;
            return values[lower] * (1.0 - fraction) + values[upper] * fraction;
        }
    }
}
