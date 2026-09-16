package Model;

import Basic.CovariateVector;
import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Helper.basicHelper.WeeklyWideLoader;
import Test.BatchRunner;
import Test.ExperimentBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** One-period realized-OOS comparison for the trial21 alternative ambiguity pilot. */
public final class OlistAlternativeOosComparison {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        Path w1Log = Path.of(args[1]);
        Path chi2Result = Path.of(args[2]);
        Path output = Path.of(args[3]);
        int period = Integer.parseInt(args[4]);
        int lag = Integer.parseInt(args[5]);
        double bandwidth = Double.parseDouble(args[6]);
        int sampleCount = Integer.parseInt(args[7]);
        int carrierCount = Integer.parseInt(args[8]);

        Class<?> historical = Class.forName("Test.analysis.brazil.BrazilOlistSingleRCSAAVariantRunner");
        var configMethod = historical.getDeclaredMethod("buildConfig", int.class, double.class, double.class);
        configMethod.setAccessible(true);
        Config cfg = (Config) configMethod.invoke(null, lag, bandwidth, 5.0);
        cfg.enforceDemandEquality = true;

        var loader = WeeklyWideLoader.load(input);
        var built = SampleBuilder.buildFromPeriods(loader.periods, loader.laneNames, cfg);
        var baselineMethod = historical.getDeclaredMethod("buildBaselineDemand", SampleBuilder.BuildResult.class);
        baselineMethod.setAccessible(true);
        ProcurementParams params = InstanceGenerator.generate(carrierCount,
                (double[]) baselineMethod.invoke(null, built), new InstanceGenerator.GenConfig(), cfg);
        var rolling = ExperimentBuilder.buildRolling(built.samples, sampleCount);
        int trial = period - lag - sampleCount;
        if (trial < 0 || trial >= rolling.size()) throw new IllegalArgumentException("Insufficient history");

        // Repeat the production preprocessing as an input-consistency check. OOS demand is never scaled.
        var train = BatchRunner.deepCopySamples(rolling.trainSets.get(trial));
        CovariateVector theta = new CovariateVector(rolling.thetaNowList.get(trial).values().clone());
        StandardScaler scaler = new StandardScaler();
        scaler.fit(train, theta.values().length);
        for (var sample : train) sample.theta = new CovariateVector(scaler.transform(sample.theta.values()));
        theta = new CovariateVector(scaler.transform(theta.values()));
        new WeightCalculator(WeightCalculator.buildKernel(cfg), new EuclideanDistance())
                .computeKernelWeights(train, theta, cfg);

        double[] w1Y = parseLastVector(Files.readAllLines(w1Log), "W1-CCG incumbentUpdate");
        double[] chi2Y = parseLastVector(Files.readAllLines(chi2Result), "chi2,");
        if (w1Y.length != carrierCount || chi2Y.length != carrierCount) {
            throw new IllegalStateException("Decision length mismatch");
        }
        double[] demand = rolling.testSamples.get(trial).demand().clone();
        SecondStageEvaluator.Result w1 = SecondStageEvaluator.evaluate(params, w1Y, demand, true);
        SecondStageEvaluator.Result chi2 = SecondStageEvaluator.evaluate(params, chi2Y, demand, true);

        Files.createDirectories(output.getParent());
        String header = "method,testPeriod,totalDemand,selected,oosTotal,transport,spot,mqcPenalty,mqcShortfall,y\n";
        String rows = row("w1", period, demand, w1Y, w1)
                + row("chi2", period, demand, chi2Y, chi2Y.length == 0 ? null : chi2);
        Files.writeString(output, header + rows);
        System.out.printf(Locale.ROOT,
                "W1_vs_chi2 OOS difference=%.6f (%.4f%%), agreement=%.4f, jaccard=%.4f%n",
                w1.objective - chi2.objective, 100.0 * (w1.objective / chi2.objective - 1.0),
                agreement(w1Y, chi2Y), jaccard(w1Y, chi2Y));
    }

    private static String row(String method, int period, double[] demand, double[] y,
                              SecondStageEvaluator.Result result) {
        return String.format(Locale.ROOT, "%s,%d,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%.6f,\"%s\"%n",
                method, period, Arrays.stream(demand).sum(), selected(y), result.objective,
                result.transportCost, result.spotCost, result.penaltyCost,
                result.mqcShortfallQuantity, Arrays.toString(y));
    }

    private static double[] parseLastVector(List<String> lines, String marker) {
        String match = null;
        for (String line : lines) if (line.contains(marker) && line.contains("[")) match = line;
        if (match == null) throw new IllegalArgumentException("No decision vector for " + marker);
        int left = match.lastIndexOf('['), right = match.indexOf(']', left);
        String body = match.substring(left + 1, right).trim();
        if (body.isEmpty()) return new double[0];
        String[] tokens = body.split(",\\s*");
        double[] out = new double[tokens.length];
        for (int i = 0; i < tokens.length; i++) out[i] = Double.parseDouble(tokens[i]);
        return out;
    }

    private static int selected(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static double agreement(double[] a, double[] b) {
        int same = 0;
        for (int i = 0; i < a.length; i++) if ((a[i] > 0.5) == (b[i] > 0.5)) same++;
        return (double) same / a.length;
    }

    private static double jaccard(double[] a, double[] b) {
        int intersection = 0, union = 0;
        for (int i = 0; i < a.length; i++) {
            boolean aa = a[i] > 0.5, bb = b[i] > 0.5;
            if (aa || bb) union++;
            if (aa && bb) intersection++;
        }
        return union == 0 ? 1.0 : (double) intersection / union;
    }
}
