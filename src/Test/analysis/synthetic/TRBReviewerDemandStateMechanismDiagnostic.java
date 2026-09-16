package Test.analysis.synthetic;

import Basic.Sample;
import Helper.basicHelper.Config;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSolveBridge.PreparedInput;
import Test.analysis.synthetic.TRBReviewerSyntheticProcurementFactoryV3.Regime;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

/** Data-only diagnosis of the demand states behind the v3 holdout decisions. */
public final class TRBReviewerDemandStateMechanismDiagnostic {

    private TRBReviewerDemandStateMechanismDiagnostic() {
    }

    /** Usage: {@code <output-csv> [start-seed] [end-seed] [C_h] [distribution] [CV] [S] [k] [context-transform]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 9) {
            throw new IllegalArgumentException(
                    "Usage: <output-csv> [start-seed] [end-seed] [C_h] "
                            + "[LOGNORMAL|UNIFORM] [CV] [S] [k] [RAW|LOG1P]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        long startSeed = args.length >= 2 ? Long.parseLong(args[1]) : 21L;
        long endSeed = args.length >= 3 ? Long.parseLong(args[2]) : 40L;
        double cH = args.length >= 4 ? Double.parseDouble(args[3]) : 1.0;
        InnovationDistribution distribution = args.length >= 5
                ? InnovationDistribution.valueOf(args[4].toUpperCase(Locale.ROOT))
                : InnovationDistribution.LOGNORMAL;
        double cv = args.length >= 6 ? Double.parseDouble(args[5]) : 0.30;
        int sampleCount = args.length >= 7 ? Integer.parseInt(args[6]) : 50;
        int observedLagPeriods = args.length >= 8 ? Integer.parseInt(args[7]) : 3;
        ContextTransform contextTransform = args.length >= 9
                ? ContextTransform.valueOf(args[8].toUpperCase(Locale.ROOT))
                : ContextTransform.RAW;
        if (startSeed > endSeed) throw new IllegalArgumentException("start-seed exceeds end-seed");
        if (!Double.isFinite(cH) || cH <= 0.0) throw new IllegalArgumentException("C_h must be positive");
        if (!Double.isFinite(cv) || cv <= 0.0) throw new IllegalArgumentException("CV must be positive");
        if (sampleCount <= 0) throw new IllegalArgumentException("S must be positive");
        if (observedLagPeriods < 1 || observedLagPeriods > 3) {
            throw new IllegalArgumentException("k must be 1, 2, or 3");
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());

        try (BufferedWriter out = Files.newBufferedWriter(
                output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            out.write("demandSeed,baselineTotal,trainingMeanRatio,csaaMeanRatio,trueMeanRatio,"
                    + "csaaToTrueRatio,lag1Ratio,lag2Ratio,lag3Ratio,aess,maxWeight,"
                    + "saaMeanErrorL1,csaaMeanErrorL1,csaaAboveSaaLanes,trueAboveSaaLanes");
            out.newLine();

            for (long seed = startSeed; seed <= endSeed; seed++) {
                Settings settings = settings(
                        seed, distribution, cv, sampleCount, observedLagPeriods);
                ReplicationData demand =
                        TRBReviewerR3M3IndependentPathDemandGenerator.generate(settings);
                transformContextsInPlace(demand, contextTransform);
                Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                        Method.CSAA, observedLagPeriods, cH, 1.0, 2, 600);
                PreparedInput prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                        demand,
                        TRBReviewerSyntheticProcurementFactoryV3.generate(
                                demand.baselineDemand, 10, 0.75,
                                Regime.MQC_VERY_TIGHT, 100L + seed).params(),
                        config);

                double[] saaMean = mean(demand.trainingSamples, demand.baselineDemand.length, false);
                double[] csaaMean = mean(prepared.solveData.samples,
                        demand.baselineDemand.length, true);
                double sumWeightSquares = 0.0;
                double maxWeight = 0.0;
                for (Sample sample : prepared.solveData.samples) {
                    sumWeightSquares += sample.weight * sample.weight;
                    maxWeight = Math.max(maxWeight, sample.weight);
                }

                double baselineTotal = sum(demand.baselineDemand);
                out.write(String.format(Locale.US,
                        "%d,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,%.9f,"
                                + "%.9f,%.9f,%d,%d%n",
                        seed, baselineTotal,
                        sum(saaMean) / baselineTotal,
                        sum(csaaMean) / baselineTotal,
                        sum(demand.conditionalMean) / baselineTotal,
                        sum(csaaMean) / sum(demand.conditionalMean),
                        sum(demand.queryHistoryLatestFirst[0]) / baselineTotal,
                        sum(demand.queryHistoryLatestFirst[1]) / baselineTotal,
                        sum(demand.queryHistoryLatestFirst[2]) / baselineTotal,
                        1.0 / sumWeightSquares, maxWeight,
                        normalizedL1(saaMean, demand.conditionalMean),
                        normalizedL1(csaaMean, demand.conditionalMean),
                        countAbove(csaaMean, saaMean),
                        countAbove(demand.conditionalMean, saaMean)));
            }
        }
        System.out.println("DEMAND_STATE_DIAGNOSTIC_OK " + output);
    }

    private enum ContextTransform {
        RAW,
        LOG1P
    }

    private static void transformContextsInPlace(ReplicationData demand,
                                                 ContextTransform transform) {
        if (transform == ContextTransform.RAW) return;
        for (Sample sample : demand.trainingSamples) log1pInPlace(sample.theta.values());
        log1pInPlace(demand.thetaNow.values());
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

    private static Settings settings(long seed,
                                     InnovationDistribution distribution,
                                     double cv,
                                     int sampleCount,
                                     int observedLagPeriods) {
        Settings settings = new Settings();
        settings.trainingSampleCount = sampleCount;
        settings.observedLagPeriods = observedLagPeriods;
        settings.warmupPeriods = 50;
        settings.oosSampleCount = 200;
        settings.innovationCv = cv;
        settings.latentCrossLaneCorrelation = 0.30;
        settings.longRunWeight = 0.20;
        settings.globalHistoryWeight = 0.60;
        settings.laneHistoryWeight = 0.20;
        settings.innovationDistribution = distribution;
        settings.baselineSeed = 20260808L;
        settings.replicationSeed = seed;
        return settings;
    }

    private static double[] mean(List<Sample> samples, int laneCount, boolean useWeights) {
        double[] mean = new double[laneCount];
        for (Sample sample : samples) {
            double weight = useWeights ? sample.weight : 1.0 / samples.size();
            for (int j = 0; j < laneCount; j++) mean[j] += weight * sample.demand()[j];
        }
        return mean;
    }

    private static int countAbove(double[] left, double[] right) {
        int count = 0;
        for (int j = 0; j < left.length; j++) if (left[j] > right[j]) count++;
        return count;
    }

    private static double normalizedL1(double[] estimate, double[] target) {
        double error = 0.0;
        double total = 0.0;
        for (int j = 0; j < target.length; j++) {
            error += Math.abs(estimate[j] - target[j]);
            total += Math.abs(target[j]);
        }
        return error / total;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }
}
