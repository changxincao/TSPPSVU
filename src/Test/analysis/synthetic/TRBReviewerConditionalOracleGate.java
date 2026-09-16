package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Estimates the attainable value of the true conditional distribution without
 * changing the demand DGP or procurement market.
 *
 * <p>For each terminal state, the first {@code evaluationDraws} conditional
 * draws are retained as the paired OOS set used by the existing experiment.
 * A disjoint suffix of conditional draws is used as a large oracle-SAA
 * training set. The oracle therefore knows the correct conditional
 * distribution but never trains on its evaluation draws.</p>
 */
public final class TRBReviewerConditionalOracleGate {
    private static final int I = 30;
    private static final int J = 23;
    private static final int CONTINUOUS_HORIZON = 100;
    private static final long BASELINE_SEED = 20260809L;
    private static final long GROUP_SEED = 20260810L;

    private TRBReviewerConditionalOracleGate() {
    }

    /**
     * Usage: {@code <output-root> [demand-start] [demand-count]
     * [oracle-samples] [evaluation-draws] [procurement-seed]}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 6) {
            throw new IllegalArgumentException(
                    "Usage: <output-root> [demand-start] [demand-count] "
                            + "[oracle-samples] [evaluation-draws] [procurement-seed]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        int demandStart = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int demandCount = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        int oracleSamples = args.length > 3 ? Integer.parseInt(args[3]) : 2000;
        int evaluationDraws = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        int procurementSeed = args.length > 5 ? Integer.parseInt(args[5]) : 0;
        if (demandStart < 0 || demandCount <= 0 || oracleSamples <= 0
                || evaluationDraws <= 0) {
            throw new IllegalArgumentException("Invalid demand range or sample count.");
        }
        if (Files.exists(root)) {
            try (var entries = Files.list(root)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Output root must be empty: " + root);
                }
            }
        }
        Files.createDirectories(root);

        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                J, 2300.0, 0.65, BASELINE_SEED);
        int[] laneGroups = TRBReviewerGroupSpecializedProcurementFactory.balancedLaneGroups(
                J, 3, GROUP_SEED);
        Config procurementConfig = new Config();
        procurementConfig.seed = procurementSeed;
        InstanceGenerator.GenConfig marketConfig = new InstanceGenerator.GenConfig();
        marketConfig.betaRatio = 0.70;
        ProcurementParams fullMarket = InstanceGenerator.generate(
                I, baseline, marketConfig, procurementConfig);
        fullMarket = TRBReviewerR7CoverageMqcGridExperiment.withMarketSizeScale(
                fullMarket, 10.0 / I);
        ProcurementParams params = TRBReviewerR7CoverageMqcGridExperiment.withNestedCoverage(
                fullMarket, baseline, 0.50, 1.00, "min", true);

        List<Row> rows = new ArrayList<>();
        for (int demandIndex = demandStart;
             demandIndex < demandStart + demandCount;
             demandIndex++) {
            int seedOffset = 800 + demandIndex;
            long trainingSeed = 1_000_000L + seedOffset + 1L;
            long querySeed = 2_000_000L + 1000L * (seedOffset + 1L) + 1L;
            ReplicationData generated =
                    TRBReviewerDecisionRelevantDemandGenerator.generateContinuousPath(
                            TRBReviewerR7CoverageMqcGridExperiment.r7Settings(
                                    evaluationDraws + oracleSamples),
                            baseline, laneGroups, trainingSeed, querySeed,
                            CONTINUOUS_HORIZON);
            ReplicationData oracle = oracleReplication(
                    generated, evaluationDraws, oracleSamples);
            Config solveConfig =
                    TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                            Method.SAA, 3, 1.0, 1.0, 1, 600);
            Path output = root.resolve(String.format(Locale.US,
                    "demand_%03d/ORACLE_TRUE_CONDITIONAL", demandIndex));
            TRBReviewerR3M3SyntheticMainSolve.Result result =
                    TRBReviewerR3M3SyntheticMainSolve.run(
                            "ORACLE_TRUE_CONDITIONAL", oracle, params,
                            solveConfig, output);
            rows.add(new Row(demandIndex, trainingSeed, querySeed, result));
        }
        writeSummary(root.resolve("oracle_query_results.csv"), rows,
                oracleSamples, evaluationDraws, procurementSeed);
    }

    private static ReplicationData oracleReplication(ReplicationData generated,
                                                     int evaluationDraws,
                                                     int oracleSamples) {
        if (generated.oosSamples.size() != evaluationDraws + oracleSamples) {
            throw new IllegalArgumentException("Unexpected generated OOS count.");
        }
        List<Sample> evaluation = new ArrayList<>(evaluationDraws);
        for (int index = 0; index < evaluationDraws; index++) {
            Sample source = generated.oosSamples.get(index);
            evaluation.add(new Sample(index, source.period, source.theta.copy(),
                    1.0 / evaluationDraws));
        }
        List<Sample> oracleTraining = new ArrayList<>(oracleSamples);
        for (int index = 0; index < oracleSamples; index++) {
            Sample source = generated.oosSamples.get(evaluationDraws + index);
            oracleTraining.add(new Sample(index, source.period, source.theta.copy(),
                    1.0 / oracleSamples));
        }
        return new ReplicationData(generated.laneNames, generated.baselineDemand,
                oracleTraining, generated.thetaNow, evaluation,
                generated.conditionalMean, generated.queryHistoryLatestFirst);
    }

    private static void writeSummary(Path path,
                                     List<Row> rows,
                                     int oracleSamples,
                                     int evaluationDraws,
                                     int procurementSeed) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("demandIndex,trainingSeed,querySeed,oracleSamples,evaluationDraws,"
                    + "procurementSeed,selectedCount,yBinary,meanOosCost,sdOosCost,"
                    + "q95OosCost,cvar95OosCost,meanTransportCost,meanSpotCost,"
                    + "meanPenaltyCost,certifiedOptimal,relativeGap,optimizerTimeSec");
            out.newLine();
            for (Row row : rows) {
                var result = row.result;
                out.write(String.format(Locale.US,
                        "%d,%d,%d,%d,%d,%d,%d,%s,%.17g,%.17g,%.17g,%.17g,"
                                + "%.17g,%.17g,%.17g,%s,%.17g,%.9f%n",
                        row.demandIndex, row.trainingSeed, row.querySeed,
                        oracleSamples, evaluationDraws, procurementSeed,
                        result.selectedCount, csv(result.yBinary), result.meanOosCost,
                        result.sdOosCost, result.q95OosCost, result.cvar95OosCost,
                        result.meanTransportCost, result.meanSpotCost,
                        result.meanPenaltyCost, result.certifiedOptimal,
                        result.relativeGap, result.optimizerTimeSeconds));
            }
        }
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record Row(int demandIndex, long trainingSeed, long querySeed,
                       TRBReviewerR3M3SyntheticMainSolve.Result result) {
    }
}
