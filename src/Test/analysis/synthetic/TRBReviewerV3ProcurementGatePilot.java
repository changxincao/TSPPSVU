package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceIO.StoredInstance;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerSyntheticProcurementFactoryV3.Generated;
import Test.analysis.synthetic.TRBReviewerSyntheticProcurementFactoryV3.Regime;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Runs the v3 parameter gate and the fast D/SAA/CSAA discrimination gate. */
public final class TRBReviewerV3ProcurementGatePilot {

    private static final long[] PROCUREMENT_SEEDS = {101L, 102L, 103L, 104L, 105L};
    private static final Method[] FAST_METHODS = {Method.D, Method.SAA, Method.CSAA};
    private static final double COVERAGE = 0.75;

    private TRBReviewerV3ProcurementGatePilot() {
    }

    /** Usage: {@code <saved-demand-instance-dir> <output-dir> [threads] [regime-filter] [demand-cv] [training-samples] [demand-seed] [procurement-seed] [dynamics-profile] [method-profile] [innovation-distribution] [lane-history-weight] [context-transform] [lambda]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 14) {
            throw new IllegalArgumentException(
                    "Usage: <saved-demand-instance-dir> <output-dir> [threads] [regime-filter] [demand-cv] "
                            + "[training-samples] [demand-seed] [procurement-seed] "
                            + "[ORIGINAL|COMMON_FACTOR|COMMON_FACTOR_CORRELATED] "
                            + "[FAST|ROBUST|DRO_ONLY|RCSAA_ENUMERATE_ONLY] [LOGNORMAL|UNIFORM] "
                            + "[lane-history-weight] [RAW|LOG1P] [lambda]");
        }
        Path instanceDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(args[1]).toAbsolutePath().normalize();
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        String regimeFilter = args.length > 3 ? args[3] : ".*";
        requireEmptyDirectory(outputDirectory);

        StoredInstance stored = TRBReviewerR3M3SyntheticInstanceIO.load(instanceDirectory);
        Settings demandSettings = stored.settings.copy();
        ReplicationData demandData = stored.demandData;
        if (args.length > 4) {
            demandSettings.innovationCv = Double.parseDouble(args[4]);
        }
        if (args.length > 5) demandSettings.trainingSampleCount = Integer.parseInt(args[5]);
        if (args.length > 6) demandSettings.replicationSeed = Long.parseLong(args[6]);
        if (args.length > 8) applyDynamicsProfile(demandSettings, args[8]);
        if (args.length > 10) {
            demandSettings.innovationDistribution = InnovationDistribution.valueOf(
                    args[10].toUpperCase(Locale.ROOT));
        }
        if (args.length > 11) {
            double laneWeight = Double.parseDouble(args[11]);
            if (laneWeight < 0.0 || laneWeight > 1.0 - demandSettings.longRunWeight) {
                throw new IllegalArgumentException("lane-history-weight must be in [0, 1-longRunWeight].");
            }
            demandSettings.laneHistoryWeight = laneWeight;
            demandSettings.globalHistoryWeight = 1.0 - demandSettings.longRunWeight - laneWeight;
        }
        if (args.length > 4) {
            demandData = TRBReviewerR3M3IndependentPathDemandGenerator.generate(demandSettings);
        }
        if (args.length > 12 && "LOG1P".equalsIgnoreCase(args[12])) {
            log1pContextsInPlace(demandData);
        } else if (args.length > 12 && !"RAW".equalsIgnoreCase(args[12])) {
            throw new IllegalArgumentException("Unknown context transform: " + args[12]);
        }
        double lambda = args.length > 13 ? Double.parseDouble(args[13]) : 1.0;
        long[] procurementSeeds = args.length > 7
                ? new long[]{Long.parseLong(args[7])}
                : PROCUREMENT_SEEDS;
        String methodProfile = args.length > 9 ? args[9].toUpperCase(Locale.ROOT) : "FAST";
        boolean fastProfile = "FAST".equals(methodProfile);
        Method[] methods = switch (methodProfile) {
            case "FAST" -> FAST_METHODS;
            case "ROBUST" -> new Method[]{Method.DRO, Method.RCSAA_LBBD};
            case "DRO_ONLY" -> new Method[]{Method.DRO};
            case "RCSAA_ENUMERATE_ONLY" -> new Method[]{Method.RCSAA_ENUMERATE};
            default -> throw new IllegalArgumentException("Unknown method profile: " + methodProfile);
        };
        writeDemandDiagnostics(outputDirectory.resolve("demand_diagnostics.csv"), demandData, demandSettings, threads);
        List<PilotRow> rows = new ArrayList<>();
        List<Regime> regimes = java.util.Arrays.stream(Regime.values())
                .filter(regime -> regime.name().matches(regimeFilter)).toList();
        if (regimes.isEmpty()) throw new IllegalArgumentException("No regime matches " + regimeFilter);

        try (BufferedWriter parameterOut = writer(outputDirectory.resolve("parameter_gate.csv"));
             BufferedWriter resultOut = writer(outputDirectory.resolve("fast_method_results.csv"))) {
            parameterOut.write("regime,procurementSeed,targetCoverage,actualCoverage,penaltyRateRatio,"
                    + "marketCapacityRatio,mqcUtilizationMin,mqcUtilizationMean,mqcUtilizationMax,"
                    + "laneCarrierMin,laneCarrierMax,meanSpotToMedianRate,meanWithinLaneRateCv");
            parameterOut.newLine();
            resultOut.write("regime,procurementSeed,method,beta,selectedCount,yBinary,meanOosCost,"
                    + "q95OosCost,cvar95OosCost,meanTransportCost,meanSpotCost,meanMqcPenalty,"
                    + "spotShare,penaltyShare,certifiedOptimal,relativeGap,optimizerTimeSec");
            resultOut.newLine();

            for (Regime regime : regimes) {
                for (long seed : procurementSeeds) {
                    Generated generated = TRBReviewerSyntheticProcurementFactoryV3.generate(
                            demandData.baselineDemand, 10, COVERAGE, regime, seed);
                    ProcurementParams params = generated.params();
                    writeParameterRow(parameterOut, generated, demandData.baselineDemand);

                    for (Method method : methods) {
                        Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                                method, demandSettings.observedLagPeriods,
                                1.0, lambda, threads, 600);
                        Path methodOutput = outputDirectory.resolve(regime.name())
                                .resolve("seed_" + seed).resolve(method.name());
                        TRBReviewerR3M3SyntheticMainSolve.Result result =
                                TRBReviewerR3M3SyntheticMainSolve.run(
                                        method.name(), demandData, params, config, methodOutput);
                        PilotRow row = new PilotRow(regime, seed, params.beta, result);
                        rows.add(row);
                        writeResultRow(resultOut, row);
                    }
                }
            }
        }
        if (fastProfile) {
            writeGateSummary(outputDirectory.resolve("gate_summary.csv"), rows, regimes, procurementSeeds);
        }
    }

    private static void log1pContextsInPlace(ReplicationData demandData) {
        for (Sample sample : demandData.trainingSamples) log1pInPlace(sample.theta.values());
        log1pInPlace(demandData.thetaNow.values());
    }

    private static void log1pInPlace(double[] values) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] < 0.0 || !Double.isFinite(values[index])) {
                throw new IllegalArgumentException(
                        "LOG1P context requires finite nonnegative values; index=" + index);
            }
            values[index] = Math.log1p(values[index]);
        }
    }

    private static void applyDynamicsProfile(Settings settings, String profile) {
        if ("ORIGINAL".equalsIgnoreCase(profile)) return;
        if ("COMMON_FACTOR".equalsIgnoreCase(profile)
                || "COMMON_FACTOR_CORRELATED".equalsIgnoreCase(profile)) {
            settings.longRunWeight = 0.20;
            settings.globalHistoryWeight = 0.60;
            settings.laneHistoryWeight = 0.20;
            if ("COMMON_FACTOR_CORRELATED".equalsIgnoreCase(profile)) {
                settings.latentCrossLaneCorrelation = 0.30;
            }
            return;
        }
        throw new IllegalArgumentException("Unknown dynamics profile: " + profile);
    }

    private static void writeDemandDiagnostics(Path path,
                                               ReplicationData demandData,
                                               Settings settings,
                                               int threads) throws Exception {
        Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                Method.CSAA, settings.observedLagPeriods, 1.0, 1.0, threads, 600);
        var prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                demandData,
                TRBReviewerSyntheticProcurementFactoryV3.generate(
                        demandData.baselineDemand, 10, COVERAGE, Regime.MQC_VERY_TIGHT, 101L).params(),
                config);

        double[] saaMean = new double[demandData.baselineDemand.length];
        double[] csaaMean = new double[demandData.baselineDemand.length];
        double sumWeightSquares = 0.0;
        double maxWeight = 0.0;
        for (Sample sample : prepared.solveData.samples) {
            sumWeightSquares += sample.weight * sample.weight;
            maxWeight = Math.max(maxWeight, sample.weight);
            for (int j = 0; j < saaMean.length; j++) {
                saaMean[j] += sample.demand()[j] / prepared.solveData.samples.size();
                csaaMean[j] += sample.weight * sample.demand()[j];
            }
        }

        try (BufferedWriter out = writer(path)) {
            out.write("trainingSamples,thetaDimension,innovationDistribution,innovationCv,"
                    + "longRunWeight,globalHistoryWeight,"
                    + "laneHistoryWeight,trueMeanVsBaselineL1,"
                    + "saaMeanErrorL1,csaaMeanErrorL1,aess,maxWeight,bandwidthH");
            out.newLine();
            out.write(String.format(Locale.US,
                    "%d,%d,%s,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f%n",
                    settings.trainingSampleCount, demandData.thetaNow.dim(), settings.innovationDistribution,
                    settings.innovationCv,
                    settings.longRunWeight, settings.globalHistoryWeight, settings.laneHistoryWeight,
                    normalizedL1(demandData.conditionalMean, demandData.baselineDemand),
                    normalizedL1(saaMean, demandData.conditionalMean),
                    normalizedL1(csaaMean, demandData.conditionalMean),
                    1.0 / sumWeightSquares, maxWeight, config.bandwidthH));
        }
    }

    private static double normalizedL1(double[] estimate, double[] target) {
        double absoluteError = 0.0;
        double targetTotal = 0.0;
        for (int j = 0; j < target.length; j++) {
            absoluteError += Math.abs(estimate[j] - target[j]);
            targetTotal += Math.abs(target[j]);
        }
        return absoluteError / targetTotal;
    }

    private static void writeParameterRow(BufferedWriter out,
                                          Generated generated,
                                          double[] baselineDemand) throws Exception {
        ProcurementParams p = generated.params();
        double baseTotal = sum(baselineDemand);
        double capacityTotal = sum(p.M);
        double coverageSum = 0.0;
        double etaMin = Double.POSITIVE_INFINITY;
        double etaMax = 0.0;
        double etaSum = 0.0;
        for (int i = 0; i < p.I; i++) {
            int eligibleCount = 0;
            for (int j = 0; j < p.J; j++) if (p.eligible[i][j]) eligibleCount++;
            coverageSum += (double) eligibleCount / p.J;
            double eta = p.p[i] / p.M[i];
            etaMin = Math.min(etaMin, eta);
            etaMax = Math.max(etaMax, eta);
            etaSum += eta;
        }

        int laneCarrierMin = p.I;
        int laneCarrierMax = 0;
        double spotRatioSum = 0.0;
        double rateCvSum = 0.0;
        for (int j = 0; j < p.J; j++) {
            List<Double> rates = new ArrayList<>();
            for (int i = 0; i < p.I; i++) if (p.eligible[i][j]) rates.add(p.r[i][j]);
            laneCarrierMin = Math.min(laneCarrierMin, rates.size());
            laneCarrierMax = Math.max(laneCarrierMax, rates.size());
            rates.sort(Double::compareTo);
            double median = rates.size() % 2 == 1
                    ? rates.get(rates.size() / 2)
                    : 0.5 * (rates.get(rates.size() / 2 - 1) + rates.get(rates.size() / 2));
            spotRatioSum += p.e[j] / median;
            double mean = rates.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
            double sumSquares = 0.0;
            for (double rate : rates) sumSquares += (rate - mean) * (rate - mean);
            rateCvSum += Math.sqrt(sumSquares / Math.max(1, rates.size() - 1)) / mean;
        }

        out.write(String.format(Locale.US,
                "%s,%d,%.6f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%d,%d,%.9f,%.9f%n",
                generated.regime(), generated.seed(), generated.targetCoverage(),
                coverageSum / p.I, generated.regime().penaltyRateRatio, capacityTotal / baseTotal,
                etaMin, etaSum / p.I, etaMax,
                laneCarrierMin, laneCarrierMax,
                spotRatioSum / p.J, rateCvSum / p.J));
        out.flush();
    }

    private static void writeResultRow(BufferedWriter out, PilotRow row) throws Exception {
        TRBReviewerR3M3SyntheticMainSolve.Result r = row.result;
        double spotShare = r.meanSpotCost / r.meanOosCost;
        double penaltyShare = r.meanPenaltyCost / r.meanOosCost;
        out.write(String.format(Locale.US,
                "%s,%d,%s,%d,%d,\"%s\",%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                        + "%.9f,%.9f,%s,%.17g,%.9f%n",
                row.regime, row.seed, r.methodLabel, row.beta, r.selectedCount, r.yBinary,
                r.meanOosCost, r.q95OosCost, r.cvar95OosCost,
                r.meanTransportCost, r.meanSpotCost, r.meanPenaltyCost,
                spotShare, penaltyShare, r.certifiedOptimal,
                r.relativeGap, r.optimizerTimeSeconds));
        out.flush();
    }

    private static void writeGateSummary(Path path,
                                         List<PilotRow> rows,
                                         List<Regime> regimes,
                                         long[] procurementSeeds) throws Exception {
        try (BufferedWriter out = writer(path)) {
            out.write("regime,seeds,distinctDecisionSeeds,csaaDiffFromSaaSeeds,allMethodsAtBetaSeeds,"
                    + "meanSpotShare,meanPenaltyShare,passFastGate");
            out.newLine();
            for (Regime regime : regimes) {
                int distinct = 0;
                int csaaDiffFromSaa = 0;
                int allBeta = 0;
                double spotShare = 0.0;
                double penaltyShare = 0.0;
                int runCount = 0;
                for (long seed : procurementSeeds) {
                    List<PilotRow> seedRows = rows.stream()
                            .filter(r -> r.regime == regime && r.seed == seed).toList();
                    Set<String> decisions = new HashSet<>();
                    boolean seedAllBeta = true;
                    for (PilotRow row : seedRows) {
                        decisions.add(row.result.yBinary);
                        seedAllBeta &= row.result.selectedCount == row.beta;
                        spotShare += row.result.meanSpotCost / row.result.meanOosCost;
                        penaltyShare += row.result.meanPenaltyCost / row.result.meanOosCost;
                        runCount++;
                    }
                    if (decisions.size() >= 2) distinct++;
                    String saaDecision = decisionOf(seedRows, "SAA");
                    String csaaDecision = decisionOf(seedRows, "CSAA");
                    if (!saaDecision.equals(csaaDecision)) csaaDiffFromSaa++;
                    if (seedAllBeta) allBeta++;
                }
                double meanSpotShare = spotShare / runCount;
                double meanPenaltyShare = penaltyShare / runCount;
                int distinctRequired = (int) Math.ceil(0.60 * procurementSeeds.length);
                int csaaDifferenceRequired = (int) Math.ceil(0.40 * procurementSeeds.length);
                boolean pass = distinct >= distinctRequired && csaaDiffFromSaa >= csaaDifferenceRequired
                        && allBeta < procurementSeeds.length
                        && meanSpotShare + meanPenaltyShare >= 0.005;
                out.write(String.format(Locale.US, "%s,%d,%d,%d,%d,%.9f,%.9f,%s%n",
                        regime, procurementSeeds.length, distinct, csaaDiffFromSaa, allBeta,
                        meanSpotShare, meanPenaltyShare, pass));
            }
        }
    }

    private static String decisionOf(List<PilotRow> rows, String methodLabel) {
        return rows.stream()
                .filter(row -> methodLabel.equals(row.result.methodLabel))
                .map(row -> row.result.yBinary)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Missing " + methodLabel + " pilot row"));
    }

    private static BufferedWriter writer(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    }

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum;
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

    private record PilotRow(Regime regime,
                            long seed,
                            int beta,
                            TRBReviewerR3M3SyntheticMainSolve.Result result) {
    }
}
