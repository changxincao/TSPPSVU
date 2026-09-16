package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.SolveMode;
import Test.analysis.synthetic.TRBReviewerDecisionRelevantDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Forecast-support diagnostic for paired independent versus continuous training rows. */
public final class TRBReviewerContinuousPathProtocolDiagnostic {
    private TRBReviewerContinuousPathProtocolDiagnostic() {
    }

    public static void main(String[] args) {
        int firstSeed = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        int seedCount = args.length > 1 ? Integer.parseInt(args[1]) : 10;
        if (firstSeed < 0 || seedCount <= 0) throw new IllegalArgumentException("Invalid seed range.");

        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                23, 2300.0, 0.65, 20260809L);
        int[] groups = TRBReviewerGroupSpecializedProcurementFactory.balancedLaneGroups(
                23, 3, 20260810L);
        System.out.println("protocol,seed,saaError,csaaError,ess,maxWeight,minDistance,lag1Total");
        for (int seed = firstSeed; seed < firstSeed + seedCount; seed++) {
            long pathSeed = 1_000_000L + 400L + seed + 1L;
            long oosSeed = 2_000_000L + 1000L * (400L + seed + 1L) + 1L;
            Settings settings = TRBReviewerR7CoverageMqcGridExperiment.r7Settings(200);
            ReplicationData continuous =
                    TRBReviewerDecisionRelevantDemandGenerator.generateContinuousPath(
                            settings, baseline, groups, pathSeed, oosSeed);
            ReplicationData independent =
                    TRBReviewerDecisionRelevantDemandGenerator
                            .generateIndependentTrainingAtContinuousQuery(
                                    settings, baseline, groups, pathSeed, oosSeed);
            requirePairedQueryAndOos(independent, continuous);
            print("independent_paired_query", seed, diagnose(independent));
            print("continuous", seed, diagnose(continuous));
        }
    }

    private static Diagnostic diagnose(ReplicationData data) {
        List<Sample> samples = copyAndLogContexts(data.trainingSamples);
        double[] nowRaw = data.thetaNow.values().clone();
        log1p(nowRaw);
        StandardScaler scaler = new StandardScaler();
        scaler.fit(samples, nowRaw.length);
        for (Sample sample : samples) {
            sample.theta = new CovariateVector(scaler.transform(sample.theta.values()));
        }
        CovariateVector now = new CovariateVector(scaler.transform(nowRaw));

        Config config = new Config();
        config.solveMode = SolveMode.CSAA;
        config.C_h = 1.0;
        WeightCalculator calculator = new WeightCalculator(
                WeightCalculator.buildKernel(config), new EuclideanDistance());
        calculator.computeKernelWeights(samples, now, config);

        double[] saa = new double[data.conditionalMean.length];
        double[] csaa = new double[data.conditionalMean.length];
        double sumSquares = 0.0;
        double maxWeight = 0.0;
        double minDistance = Double.POSITIVE_INFINITY;
        EuclideanDistance distance = new EuclideanDistance();
        for (Sample sample : samples) {
            for (int j = 0; j < saa.length; j++) {
                saa[j] += sample.demand()[j] / samples.size();
                csaa[j] += sample.weight * sample.demand()[j];
            }
            sumSquares += sample.weight * sample.weight;
            maxWeight = Math.max(maxWeight, sample.weight);
            minDistance = Math.min(minDistance,
                    distance.distance(now.values(), sample.theta.values()));
        }
        return new Diagnostic(normalizedL1(saa, data.conditionalMean),
                normalizedL1(csaa, data.conditionalMean), 1.0 / sumSquares,
                maxWeight, minDistance, lag1TotalCorrelation(data.trainingSamples));
    }

    private static List<Sample> copyAndLogContexts(List<Sample> source) {
        List<Sample> out = new ArrayList<>(source.size());
        for (Sample sample : source) {
            double[] theta = sample.theta.values().clone();
            log1p(theta);
            out.add(new Sample(sample.id, sample.period,
                    new CovariateVector(theta), sample.weight));
        }
        return out;
    }

    private static void log1p(double[] values) {
        for (int index = 0; index < values.length; index++) values[index] = Math.log1p(values[index]);
    }

    private static double normalizedL1(double[] estimate, double[] target) {
        double absolute = 0.0;
        double scale = 0.0;
        for (int j = 0; j < estimate.length; j++) {
            absolute += Math.abs(estimate[j] - target[j]);
            scale += target[j];
        }
        return absolute / scale;
    }

    private static double lag1TotalCorrelation(List<Sample> samples) {
        double[] values = new double[samples.size()];
        for (int index = 0; index < values.length; index++) {
            for (double demand : samples.get(index).demand()) values[index] += demand;
        }
        double leftMean = 0.0;
        double rightMean = 0.0;
        for (int index = 1; index < values.length; index++) {
            leftMean += values[index - 1];
            rightMean += values[index];
        }
        leftMean /= values.length - 1;
        rightMean /= values.length - 1;
        double covariance = 0.0;
        double leftVariance = 0.0;
        double rightVariance = 0.0;
        for (int index = 1; index < values.length; index++) {
            double left = values[index - 1] - leftMean;
            double right = values[index] - rightMean;
            covariance += left * right;
            leftVariance += left * left;
            rightVariance += right * right;
        }
        return covariance / Math.sqrt(leftVariance * rightVariance);
    }

    private static void print(String protocol, int seed, Diagnostic value) {
        System.out.printf(Locale.US, "%s,%d,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f%n",
                protocol, seed, value.saaError, value.csaaError, value.ess,
                value.maxWeight, value.minDistance, value.lag1Total);
    }

    private static void requirePairedQueryAndOos(ReplicationData left,
                                                 ReplicationData right) {
        if (!Arrays.equals(left.thetaNow.values(), right.thetaNow.values())
                || left.oosSamples.size() != right.oosSamples.size()) {
            throw new AssertionError("Protocols do not share query/OOS dimensions.");
        }
        for (int index = 0; index < left.oosSamples.size(); index++) {
            if (!Arrays.equals(left.oosSamples.get(index).demand(),
                    right.oosSamples.get(index).demand())) {
                throw new AssertionError("Protocols do not share OOS draw " + index + ".");
            }
        }
    }

    private record Diagnostic(double saaError, double csaaError, double ess,
                              double maxWeight, double minDistance, double lag1Total) {
    }
}
