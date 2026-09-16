package Test;

import Basic.CovariateVector;
import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator.GenConfig;
import Helper.basicHelper.OutputManager;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.SAAModel;
import Model.Solution;
import Model.SolveMode;
import Test.BatchRunner.RecourseEvaluator;
import Test.BatchRunner.TrialDiag;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 浠呯敤浜庤窇 SAA / CSAA 鐨勬壒閲忚瘯楠?Runner锛堜笌 DROBatchRunner / DeterministicBatchRunner 鍙ｅ緞瀵归綈锛夈€? * 杩欐槸澧為噺鏂板锛屼笉鏀瑰姩鐜版湁 BatchRunner锛堢洰鍓嶅叾涓眰瑙ｉ儴鍒嗚娉ㄩ噴锛夈€? */
public class SAACSAAPlainBatchRunner {

    public static void run(String tag,
                           List<String> lanes,
                           ProcurementParams params,
                           Config cfg,
                           ExperimentBatches batches,
                           Path outDir,
                           GlobalSummaryCollector collector,
                           GlobalTrialCollector trialCollector,
                           GenConfig genCfg) throws Exception {

        if (batches == null || batches.size() == 0) throw new IllegalArgumentException("Empty experiment batches.");
        if (cfg.solveMode != SolveMode.SAA && cfg.solveMode != SolveMode.CSAA) {
            throw new IllegalArgumentException("SAACSAAPlainBatchRunner requires solveMode in {SAA, CSAA}.");
        }

        Files.createDirectories(outDir);

        Path trialsCsv = outDir.resolve("trials_" + tag + ".csv");

        try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv)) {
            bw.write(String.join(",",
                    "tag", "solveMode", "fillMissingDates", "standardizeTheta", "k1Lag", "kernelType", "bandwidthH",
                    "C_h", "lambda",
                    "trialId", "testIdx", "trainSize",
                    "expectedObj", "realizedObj", "solveTimeSec", "selectedCount",
                    "oosTransportCost", "oosSpotCost", "oosPenaltyCost",
                    "sumW", "sumW2", "ESS", "top1W", "top5Wsum", "maxW_over_meanW",
                    "thetaDist_mean", "thetaDist_median", "thetaDist_min", "thetaDist_max",
                    "demandDist_mean", "demandDist_median", "demandDist_min", "demandDist_max",
                    "corrW_thetaDist", "corrW_demandDist", "corrTheta_demandDist",
                    "yBinary", "selectedCarriers", "sampleWeights"));
            bw.newLine();
        }

        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        EuclideanDistance distMetric = new EuclideanDistance();
        SAAModel model = new SAAModel();

        // lists across trials (for global_summary.csv)
        List<Double> expectedList = new ArrayList<>();
        List<Double> realizedList = new ArrayList<>();
        List<Double> timeList = new ArrayList<>();
        List<Double> essList = new ArrayList<>();
        List<Double> corrTDList = new ArrayList<>();
        List<Double> transportList = new ArrayList<>();
        List<Double> spotList = new ArrayList<>();
        List<Double> penaltyList = new ArrayList<>();

        for (int t = 0; t < batches.size(); t++) {
            OutputManager out = new OutputManager(tag, t, cfg, genCfg);

            List<Sample> trainRaw = batches.trainSets.get(t);
            CovariateVector thetaNowRaw = batches.thetaNowList.get(t);
            Sample test = batches.testSamples.get(t);
            int testIdx = batches.testIndex.get(t);

            List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
            CovariateVector thetaNow = new CovariateVector(thetaNowRaw.values().clone());
            int thetaDim = (train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length);

            if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
                StandardScaler scaler = new StandardScaler();
                scaler.fit(train, thetaDim);
                for (Sample s : train) {
                    s.theta = new CovariateVector(scaler.transform(s.theta.values()));
                }
                thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
            }

            if (cfg.solveMode == SolveMode.SAA) {
                double eq = 1.0 / Math.max(1, train.size());
                for (Sample s : train) s.weight = eq;
            } else {
                wc.computeKernelWeights(train, thetaNow, cfg);
            }

            double[] dTest = test.demand().clone();
            TrialDiag diag = TrialDiag.compute(train, thetaNow, dTest, distMetric);

            Data data = new Data(lanes, train, thetaNow, params);

            long st = System.nanoTime();
            Solution sol = model.solve(data, cfg, out);
            long ed = System.nanoTime();
            double solveTimeSec = (ed - st) / 1e9;

            RecourseEvaluator.RecourseEval rec = RecourseEvaluator.evaluate(
                    params, sol.y, dTest, cfg.enforceDemandEquality);
            double realized = rec.objValue;
            double expected = sol.objValue;
            int selectedCount = countSelected(sol.y);

            String yBinary = yBinary(sol.y);
            String selectedCarriers = selectedCarriers(sol.y);
            String sampleWeights = buildSampleWeightsString(train);
            String yBinaryQ = "\"" + yBinary.replace("\"", "\"\"") + "\"";
            String selectedCarriersQ = "\"" + selectedCarriers.replace("\"", "\"\"") + "\"";
            String sampleWeightsQ = "\"" + sampleWeights.replace("\"", "\"\"") + "\"";

            double lambdaVal = (cfg.solveMode == SolveMode.RCSAA ? cfg.lambda : Double.NaN);

            expectedList.add(expected);
            realizedList.add(realized);
            timeList.add(solveTimeSec);
            essList.add(diag.ess);
            corrTDList.add(diag.corrThetaDemand);
            transportList.add(rec.transportTotalCost);
            spotList.add(rec.spotTotalCost);
            penaltyList.add(rec.penaltyTotalCost);

            try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv, java.nio.file.StandardOpenOption.APPEND)) {
                bw.write(String.format(Locale.US,
                        "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%.6f," +
                                "%d,%d,%d," +
                                "%.10f,%.10f,%.6f,%d," +
                                "%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f," +
                                "%s,%s,%s%n",
                        safe(tag),
                        (cfg.solveMode == null ? "" : cfg.solveMode.name()),
                        String.valueOf(cfg.fillMissingDates),
                        String.valueOf(cfg.standardizeTheta),
                        cfg.k1LagPeriods,
                        (cfg.kernelType == null ? "" : cfg.kernelType.name()),
                        cfg.bandwidthH,
                        cfg.C_h,
                        lambdaVal,

                        t, testIdx, train.size(),

                        expected, realized, solveTimeSec, selectedCount,
                        rec.transportTotalCost, rec.spotTotalCost, rec.penaltyTotalCost,

                        diag.sumW, diag.sumW2, diag.ess, diag.top1W, diag.top5Wsum, diag.maxOverMean,
                        diag.thetaMean, diag.thetaMedian, diag.thetaMin, diag.thetaMax,
                        diag.demMean, diag.demMedian, diag.demMin, diag.demMax,
                        diag.corrWTheta, diag.corrWDemand, diag.corrThetaDemand,

                        yBinaryQ, selectedCarriersQ, sampleWeightsQ));
            }

            if (trialCollector != null) {
                trialCollector.append(
                        tag,
                        cfg.solveMode,
                        cfg.fillMissingDates,
                        cfg.standardizeTheta,
                        cfg.k1LagPeriods,
                        cfg.kernelType,
                        cfg.bandwidthH,
                        cfg.C_h,
                        lambdaVal,
                        t,
                        testIdx,
                        train.size(),
                        expected,
                        expected,
                        realized,
                        solveTimeSec,
                        selectedCount,
                        rec.transportTotalCost,
                        rec.spotTotalCost,
                        rec.penaltyTotalCost,
                        diag.sumW,
                        diag.sumW2,
                        diag.ess,
                        diag.top1W,
                        diag.top5Wsum,
                        diag.maxOverMean,
                        diag.thetaMean,
                        diag.thetaMedian,
                        diag.thetaMin,
                        diag.thetaMax,
                        diag.demMean,
                        diag.demMedian,
                        diag.demMin,
                        diag.demMax,
                        diag.corrWTheta,
                        diag.corrWDemand,
                        diag.corrThetaDemand,
                        yBinary,
                        selectedCarriers
                );
            }
        }

        if (collector != null && !expectedList.isEmpty()) {
            SummaryStats exp = SummaryStats.of(expectedList);
            SummaryStats rea = SummaryStats.of(realizedList);
            SummaryStats ess = SummaryStats.of(essList);
            SummaryStats corrTD = SummaryStats.of(corrTDList);

            collector.append(
                    tag,
                    cfg.solveMode,
                    cfg.fillMissingDates,
                    cfg.standardizeTheta,
                    cfg.k1LagPeriods,
                    cfg.kernelType,
                    cfg.bandwidthH,
                    cfg.C_h,
                    (cfg.solveMode == SolveMode.RCSAA ? cfg.lambda : Double.NaN),
                     expectedList.size(),
                     new GlobalSummaryCollector.Stats(exp.n, exp.mean, exp.std, exp.min, exp.max, exp.p20, exp.p50, exp.p80, exp.p95),
                     new GlobalSummaryCollector.Stats(exp.n, exp.mean, exp.std, exp.min, exp.max, exp.p20, exp.p50, exp.p80, exp.p95),
                     new GlobalSummaryCollector.Stats(rea.n, rea.mean, rea.std, rea.min, rea.max, rea.p20, rea.p50, rea.p80, rea.p95),
                    new GlobalSummaryCollector.Stats(ess.n, ess.mean, ess.std, ess.min, ess.max, ess.p20, ess.p50, ess.p80, ess.p95),
                    new GlobalSummaryCollector.Stats(corrTD.n, corrTD.mean, corrTD.std, corrTD.min, corrTD.max, corrTD.p20, corrTD.p50, corrTD.p80, corrTD.p95),
                    mean(transportList),
                    mean(spotList),
                    mean(penaltyList),
                    mean(timeList)
            );
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace(",", "_");
    }

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y) if (v > 0.5) c++;
        return c;
    }

    private static String yBinary(double[] y) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < y.length; i++) {
            sb.append(y[i] > 0.5 ? 1 : 0);
            if (i < y.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private static String selectedCarriers(double[] y) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (int i = 0; i < y.length; i++) {
            if (y[i] > 0.5) {
                if (!first) sb.append(",");
                sb.append(i);
                first = false;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static double mean(List<Double> xs) {
        if (xs == null || xs.isEmpty()) return Double.NaN;
        double s = 0.0;
        int c = 0;
        for (Double v : xs) {
            if (v == null) continue;
            double x = v;
            if (!Double.isFinite(x)) continue;
            s += x;
            c++;
        }
        return c == 0 ? Double.NaN : s / c;
    }

    private static String buildSampleWeightsString(List<Sample> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "%.10f", samples.get(i).weight));
        }
        sb.append("]");
        return sb.toString();
    }

    private static class SummaryStats {
        final int n;
        final double mean, std, min, max, p20, p50, p80, p95;

        private SummaryStats(int n, double mean, double std, double min, double max,
                             double p20, double p50, double p80, double p95) {
            this.n = n;
            this.mean = mean;
            this.std = std;
            this.min = min;
            this.max = max;
            this.p20 = p20;
            this.p50 = p50;
            this.p80 = p80;
            this.p95 = p95;
        }

        static SummaryStats of(List<Double> xs) {
            if (xs == null) return new SummaryStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            double[] a = xs.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue)
                    .filter(Double::isFinite).toArray();
            if (a.length == 0) return new SummaryStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            Arrays.sort(a);
            int n = a.length;
            double mean = 0.0;
            for (double v : a) mean += v;
            mean /= n;
            double var = 0.0;
            for (double v : a) {
                double d = v - mean;
                var += d * d;
            }
            var /= n;
            double std = Math.sqrt(var);
            return new SummaryStats(
                    n,
                    mean,
                    std,
                    a[0],
                    a[n - 1],
                    q(a, 0.2),
                    q(a, 0.5),
                    q(a, 0.8),
                    q(a, 0.95)
            );
        }

        private static double q(double[] a, double p) {
            if (a.length == 0) return Double.NaN;
            double idx = p * (a.length - 1);
            int i = (int) Math.floor(idx);
            int j = (int) Math.ceil(idx);
            if (i == j) return a[i];
            double w = idx - i;
            return a[i] * (1 - w) + a[j] * w;
        }
    }
}
