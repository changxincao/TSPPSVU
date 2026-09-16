package Test.analysis.covariate;

import Basic.CovariateVector;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.SolveMode;
import Test.BatchRunner;
import Test.ExperimentBatches;
import Test.ExperimentBuilder;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 在不求解模型的前提下，扫描不同 `C_h` 下的权重分布。
 *
 * 目的：
 * - 固定同一份需求数据和 rolling 划分；
 * - 只计算 `CSAA` 权重，不求解；
 * - 观察 `ESS / top1W / top5Wsum / maxW_over_meanW` 是否显著偏离 SAA；
 * - 同时比较 `thetaShare` 与 `thetaDemand` 两种协变量口径。
 */
public class ThetaWeightScan {
    private static final Locale US = Locale.US;
    private static final double[] C_H_GRID = new double[] {0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 20.0, 50.0};

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = Paths.get(args.length > 0 ? args[0] : "analysis/合成需求_成本感知_周度_仅前90%需求线路.csv");
        Path outSummary = Paths.get(args.length > 1 ? args[1] : "analysis/权重扫描_成本感知需求_k1=2_汇总.csv");
        Path outTrial = Paths.get(args.length > 2 ? args[2] : "analysis/权重扫描_成本感知需求_k1=2_逐trial.csv");
        Path outNote = Paths.get(args.length > 3 ? args[3] : "analysis/权重扫描_成本感知需求_k1=2_说明.txt");
        int W = (args.length > 4 ? Integer.parseInt(args[4]) : 24);
        int k1 = (args.length > 5 ? Integer.parseInt(args[5]) : 2);

        Files.createDirectories(outSummary.toAbsolutePath().getParent());
        Files.createDirectories(outTrial.toAbsolutePath().getParent());
        Files.createDirectories(outNote.toAbsolutePath().getParent());

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);

        try (BufferedWriter bw = Files.newBufferedWriter(outTrial)) {
            bw.write("theta模式,C_h,bandwidthH,trialId,trainSize,thetaDim,ESS,top1W,top5Wsum,maxW_over_meanW,低于20的ESS,低于15的ESS,低于10的ESS,低于5的ESS");
            bw.newLine();
        }

        List<SummaryRow> rows = new ArrayList<>();
        scanOneThetaMode("thetaShare", true, w, W, k1, rows, outTrial);
        scanOneThetaMode("thetaDemand", false, w, W, k1, rows, outTrial);

        writeSummary(outSummary, rows);
        writeNote(outNote, weeklyCsv, outSummary, outTrial, W, k1, rows);

        System.out.println("[DONE] " + outSummary.toAbsolutePath());
        System.out.println("[DONE] " + outTrial.toAbsolutePath());
        System.out.println("[DONE] " + outNote.toAbsolutePath());
    }

    private static void scanOneThetaMode(String thetaMode,
                                         boolean lagDemandAsShare,
                                         WeeklyWideLoader.Result w,
                                         int W,
                                         int k1,
                                         List<SummaryRow> outRows,
                                         Path outTrial) throws Exception {
        Config cfgBase = new Config();
        cfgBase.fillMissingDates = false;
        cfgBase.aggregationDays = 7;
        cfgBase.k1LagPeriods = k1;
        cfgBase.demandAgg = false;
        cfgBase.lagDemandAsShare = lagDemandAsShare;
        cfgBase.featureFlags.includeLagDemand = true;
        cfgBase.featureFlags.includeHolidayCount = false;
        cfgBase.featureFlags.includeFreightIndex = false;
        cfgBase.featureFlags.includeConsumptionIndex = false;
        cfgBase.featureFlags.includeWEIIndex = false;
        cfgBase.standardizeTheta = true;
        cfgBase.kernelType = Helper.calculateHelper.KernelType.EXPONENTIAL;
        cfgBase.solveMode = SolveMode.CSAA;

        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, w.laneNames, cfgBase);
        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
        if (rolling.size() == 0) throw new IllegalStateException("No rolling trials.");

        for (double cH : C_H_GRID) {
            List<TrialWeightDiag> diags = new ArrayList<>();
            for (int t = 0; t < rolling.size(); t++) {
                Config cfg = copyBase(cfgBase);
                cfg.C_h = cH;

                List<Sample> train = BatchRunner.deepCopySamples(rolling.trainSets.get(t));
                CovariateVector thetaNow = new CovariateVector(rolling.thetaNowList.get(t).values().clone());
                int thetaDim = (train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length);

                if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
                    StandardScaler scaler = new StandardScaler();
                    scaler.fit(train, thetaDim);
                    for (Sample s : train) {
                        s.theta = new CovariateVector(scaler.transform(s.theta.values()));
                    }
                    thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
                }

                KernelFunction kernel = WeightCalculator.buildKernel(cfg);
                WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
                wc.computeKernelWeights(train, thetaNow, cfg);

                TrialWeightDiag diag = TrialWeightDiag.compute(train, cfg.bandwidthH);
                diags.add(diag);

                try (BufferedWriter bw = Files.newBufferedWriter(outTrial, java.nio.file.StandardOpenOption.APPEND)) {
                    bw.write(String.format(US, "%s,%.6f,%.10f,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%d,%d,%d,%d%n",
                            thetaMode, cH, cfg.bandwidthH, t, train.size(), thetaDim,
                            diag.ess, diag.top1W, diag.top5Wsum, diag.maxWOverMean,
                            (diag.ess < 20.0 ? 1 : 0),
                            (diag.ess < 15.0 ? 1 : 0),
                            (diag.ess < 10.0 ? 1 : 0),
                            (diag.ess < 5.0 ? 1 : 0)));
                }
            }

            outRows.add(SummaryRow.of(thetaMode, cH, diags));
        }
    }

    private static void writeSummary(Path outSummary, List<SummaryRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outSummary)) {
            bw.write("theta模式,C_h,平均bandwidthH,trial数,ESS均值,ESS中位数,ESS最小值,ESS最大值,top1W均值,top5Wsum均值,maxW_over_meanW均值,ESS<20占比,ESS<15占比,ESS<10占比,ESS<5占比");
            bw.newLine();
            for (SummaryRow r : rows) {
                bw.write(String.format(US, "%s,%.6f,%.10f,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        r.thetaMode, r.cH, r.bandwidthMean, r.nTrials,
                        r.essMean, r.essMedian, r.essMin, r.essMax,
                        r.top1Mean, r.top5Mean, r.maxOverMeanMean,
                        r.shareEssLt20, r.shareEssLt15, r.shareEssLt10, r.shareEssLt5));
            }
        }
    }

    private static void writeNote(Path outNote,
                                  Path weeklyCsv,
                                  Path outSummary,
                                  Path outTrial,
                                  int W,
                                  int k1,
                                  List<SummaryRow> rows) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("权重扫描说明");
        lines.add("");
        lines.add("目的：不跑求解，只计算 CSAA 核权重，检查当前 demand 数据下 C_h 变小时，权重是否明显偏离 SAA。");
        lines.add("输入需求文件: " + weeklyCsv.toAbsolutePath());
        lines.add("rolling窗口 W=" + W + ", k1=" + k1 + ", standardizeTheta=true");
        lines.add("扫描网格 C_h=" + Arrays.toString(C_H_GRID));
        lines.add("");
        lines.add("输出文件：");
        lines.add("- 汇总结果: " + outSummary.toAbsolutePath());
        lines.add("- 逐trial结果: " + outTrial.toAbsolutePath());
        lines.add("");
        lines.add("指标解释：");
        lines.add("- ESS = 1 / sum(w_i^2)，越接近 trainSize 越接近均匀权重，越小说明权重越集中。");
        lines.add("- top1W：最大单样本权重。");
        lines.add("- top5Wsum：最大的 5 个权重之和。");
        lines.add("- maxW_over_meanW：最大权重 / 平均权重，SAA 下约等于 1。");
        lines.add("- ESS<20/<15/<10/<5 占比：表示多少个 trial 的权重已经明显脱离均匀分布。");
        lines.add("");
        lines.add("每种 theta 模式下平均 ESS 最小的配置：");
        for (String mode : new String[] {"thetaShare", "thetaDemand"}) {
            SummaryRow best = rows.stream()
                    .filter(r -> r.thetaMode.equals(mode))
                    .min(Comparator.comparingDouble(r -> r.essMean))
                    .orElse(null);
            if (best != null) {
                lines.add(String.format(US,
                        "- %s: C_h=%.6f, 平均ESS=%.4f, 平均top1W=%.4f, 平均top5Wsum=%.4f, ESS<10占比=%.4f",
                        mode, best.cH, best.essMean, best.top1Mean, best.top5Mean, best.shareEssLt10));
            }
        }
        Files.write(outNote, lines);
    }

    private static Config copyBase(Config b) {
        Config c = new Config();
        c.fillMissingDates = b.fillMissingDates;
        c.aggregationDays = b.aggregationDays;
        c.k1LagPeriods = b.k1LagPeriods;
        c.demandAgg = b.demandAgg;
        c.lagDemandAsShare = b.lagDemandAsShare;

        c.featureFlags.includeLagDemand = b.featureFlags.includeLagDemand;
        c.featureFlags.includeHolidayCount = b.featureFlags.includeHolidayCount;
        c.featureFlags.includeFreightIndex = b.featureFlags.includeFreightIndex;
        c.featureFlags.includeConsumptionIndex = b.featureFlags.includeConsumptionIndex;
        c.featureFlags.includeWEIIndex = b.featureFlags.includeWEIIndex;

        c.standardizeTheta = b.standardizeTheta;
        c.thetaScaling = b.thetaScaling;
        c.kernelType = b.kernelType;
        c.C_h = b.C_h;
        c.bandwidthH = b.bandwidthH;
        c.solveMode = b.solveMode;
        return c;
    }

    private static class TrialWeightDiag {
        final double bandwidthH;
        final double ess;
        final double top1W;
        final double top5Wsum;
        final double maxWOverMean;

        TrialWeightDiag(double bandwidthH, double ess, double top1W, double top5Wsum, double maxWOverMean) {
            this.bandwidthH = bandwidthH;
            this.ess = ess;
            this.top1W = top1W;
            this.top5Wsum = top5Wsum;
            this.maxWOverMean = maxWOverMean;
        }

        static TrialWeightDiag compute(List<Sample> train, double bandwidthH) {
            int n = train.size();
            double sum = 0.0;
            double maxW = Double.NEGATIVE_INFINITY;
            List<Double> wNorm = new ArrayList<>(n);
            for (Sample s : train) {
                double w = (Double.isFinite(s.weight) ? s.weight : 0.0);
                sum += w;
                maxW = Math.max(maxW, w);
            }
            if (sum > 0 && Double.isFinite(sum)) {
                for (Sample s : train) {
                    double w = (Double.isFinite(s.weight) ? s.weight : 0.0);
                    wNorm.add(w / sum);
                }
            } else {
                double eq = 1.0 / Math.max(1, n);
                for (int i = 0; i < n; i++) wNorm.add(eq);
            }

            double sumW2 = 0.0;
            for (double w : wNorm) sumW2 += w * w;
            double ess = (sumW2 > 0 ? 1.0 / sumW2 : Double.NaN);
            List<Double> sorted = new ArrayList<>(wNorm);
            sorted.sort(Comparator.reverseOrder());
            double top1W = (sorted.isEmpty() ? Double.NaN : sorted.get(0));
            double top5Wsum = 0.0;
            for (int i = 0; i < Math.min(5, sorted.size()); i++) top5Wsum += sorted.get(i);
            double meanW = 1.0 / Math.max(1, n);
            double maxOverMean = (meanW > 0 && Double.isFinite(top1W) ? top1W / meanW : Double.NaN);
            return new TrialWeightDiag(bandwidthH, ess, top1W, top5Wsum, maxOverMean);
        }
    }

    private static class SummaryRow {
        final String thetaMode;
        final double cH;
        final double bandwidthMean;
        final int nTrials;
        final double essMean;
        final double essMedian;
        final double essMin;
        final double essMax;
        final double top1Mean;
        final double top5Mean;
        final double maxOverMeanMean;
        final double shareEssLt20;
        final double shareEssLt15;
        final double shareEssLt10;
        final double shareEssLt5;

        SummaryRow(String thetaMode,
                   double cH,
                   double bandwidthMean,
                   int nTrials,
                   double essMean,
                   double essMedian,
                   double essMin,
                   double essMax,
                   double top1Mean,
                   double top5Mean,
                   double maxOverMeanMean,
                   double shareEssLt20,
                   double shareEssLt15,
                   double shareEssLt10,
                   double shareEssLt5) {
            this.thetaMode = thetaMode;
            this.cH = cH;
            this.bandwidthMean = bandwidthMean;
            this.nTrials = nTrials;
            this.essMean = essMean;
            this.essMedian = essMedian;
            this.essMin = essMin;
            this.essMax = essMax;
            this.top1Mean = top1Mean;
            this.top5Mean = top5Mean;
            this.maxOverMeanMean = maxOverMeanMean;
            this.shareEssLt20 = shareEssLt20;
            this.shareEssLt15 = shareEssLt15;
            this.shareEssLt10 = shareEssLt10;
            this.shareEssLt5 = shareEssLt5;
        }

        static SummaryRow of(String thetaMode, double cH, List<TrialWeightDiag> diags) {
            double[] bandwidth = diags.stream().mapToDouble(d -> d.bandwidthH).toArray();
            double[] ess = diags.stream().mapToDouble(d -> d.ess).toArray();
            double[] top1 = diags.stream().mapToDouble(d -> d.top1W).toArray();
            double[] top5 = diags.stream().mapToDouble(d -> d.top5Wsum).toArray();
            double[] maxOver = diags.stream().mapToDouble(d -> d.maxWOverMean).toArray();
            int n = diags.size();
            return new SummaryRow(
                    thetaMode,
                    cH,
                    mean(bandwidth),
                    n,
                    mean(ess),
                    median(ess),
                    min(ess),
                    max(ess),
                    mean(top1),
                    mean(top5),
                    mean(maxOver),
                    shareBelow(ess, 20.0),
                    shareBelow(ess, 15.0),
                    shareBelow(ess, 10.0),
                    shareBelow(ess, 5.0)
            );
        }
    }

    private static double mean(double[] x) {
        if (x.length == 0) return Double.NaN;
        double s = 0.0;
        for (double v : x) s += v;
        return s / x.length;
    }

    private static double min(double[] x) {
        if (x.length == 0) return Double.NaN;
        double m = Double.POSITIVE_INFINITY;
        for (double v : x) m = Math.min(m, v);
        return m;
    }

    private static double max(double[] x) {
        if (x.length == 0) return Double.NaN;
        double m = Double.NEGATIVE_INFINITY;
        for (double v : x) m = Math.max(m, v);
        return m;
    }

    private static double median(double[] x) {
        if (x.length == 0) return Double.NaN;
        double[] a = x.clone();
        Arrays.sort(a);
        int n = a.length;
        if (n % 2 == 1) return a[n / 2];
        return 0.5 * (a[n / 2 - 1] + a[n / 2]);
    }

    private static double shareBelow(double[] x, double th) {
        if (x.length == 0) return Double.NaN;
        int c = 0;
        for (double v : x) if (v < th) c++;
        return (double) c / x.length;
    }
}

