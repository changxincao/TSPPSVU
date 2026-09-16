package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Model.SolveMode;
import Test.DeterministicBatchRunner;
import Test.ExperimentBatches;
import Test.ExperimentBuilder;
import Test.SAACSAAPlainBatchRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Compare SAA / CSAA / MeanDeterministic / CompleteDeterministic on synthetic weekly demand.
 *
 * Args:
 * 0 weekly csv path (default: analysis/合成需求_周度_仅前90%需求线路.csv)
 * 1 num carriers (default: 15)
 * 2 rolling window W (default: 24)
 * 3 k1 lag periods (default: 2)
 * 4 output root (optional)
 * 5 C_h (default: 50)
 * 6 theta post mode: raw | pca3 | pca4 | pca5 (default: raw)
 *
 * Notes:
 * - CSAA(thetaShare) and CSAA(thetaDemand) are built from different sample sets.
 * - PCA mode is fitted globally on theta samples (for fast experiment comparison).
 */
public class SyntheticSolveComparison {

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = Paths.get(args.length > 0 ? args[0] : "analysis/合成需求_周度_仅前90%需求线路.csv");

        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 15);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 24);
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 2);
        String outRootArg = (args.length > 4 ? args[4] : null);
        double cH = (args.length > 5 ? Double.parseDouble(args[5]) : 50.0);
        ThetaPostMode thetaPostMode = parseThetaPostMode(args.length > 6 ? args[6] : "raw");

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Config base = buildBaseConfig(k1, cH);

        Config cfgShare = copyBase(base);
        cfgShare.lagDemandAsShare = true;
        SampleBuilder.BuildResult brShare = SampleBuilder.buildFromPeriods(w.periods, lanes, cfgShare);
        applyThetaPostMode(brShare.samples, thetaPostMode);

        Config cfgDemand = copyBase(base);
        cfgDemand.lagDemandAsShare = false;
        SampleBuilder.BuildResult brDemand = SampleBuilder.buildFromPeriods(w.periods, lanes, cfgDemand);
        applyThetaPostMode(brDemand.samples, thetaPostMode);

        double[] dBase = buildBaselineDemand(brShare);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, base);

        ExperimentBatches rollingShare = ExperimentBuilder.buildRolling(brShare.samples, W);
        ExperimentBatches rollingDemand = ExperimentBuilder.buildRolling(brDemand.samples, W);
        if (rollingShare.size() == 0 || rollingDemand.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        String baseTag = "合成需求v3_k1=" + base.k1LagPeriods + "_" + thetaPostMode.tag;
        Path outRoot = (outRootArg == null || outRootArg.isBlank())
                ? Paths.get("analysis/" + baseTag + "_求解对比")
                : Paths.get(outRootArg);
        Files.createDirectories(outRoot);

        GlobalTrialCollector trialCollector = new GlobalTrialCollector(outRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(outRoot);

        System.out.println("weeklyCsv: " + weeklyCsv.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("k1=" + base.k1LagPeriods
                + ", W=" + W
                + ", carriers=" + numCarriers
                + ", lanes=" + lanes.size()
                + ", C_h=" + base.C_h
                + ", thetaPost=" + thetaPostMode.tag);

        System.out.println("=== Run SAA ===");
        runSAA(outRoot.resolve("SAA"), baseTag, lanes, params, base, rollingShare, genCfg, summaryCollector, trialCollector);

        System.out.println("=== Run CSAA (thetaShare) ===");
        runCSAA(outRoot.resolve("CSAA_thetaShare"), baseTag + "_thetaShare", true, lanes, params, base, rollingShare, genCfg, summaryCollector, trialCollector);

        System.out.println("=== Run CSAA (thetaDemand) ===");
        runCSAA(outRoot.resolve("CSAA_thetaDemand"), baseTag + "_thetaDemand", false, lanes, params, base, rollingDemand, genCfg, summaryCollector, trialCollector);

        System.out.println("=== Run MeanDeterministic ===");
        runMeanDet(outRoot.resolve("MeanDeterministic"), baseTag, lanes, params, base, rollingShare, genCfg, summaryCollector, trialCollector);

        System.out.println("=== Run CompleteDeterministic ===");
        runCompleteDet(outRoot.resolve("CompleteDeterministic"), baseTag, lanes, params, base, rollingShare, genCfg, summaryCollector, trialCollector);

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static Config buildBaseConfig(int k1, double cH) {
        Config base = new Config();
        base.fillMissingDates = false;
        base.aggregationDays = 7;
        base.k1LagPeriods = k1;
        base.demandAgg = false;
        base.lagDemandAsShare = true;

        base.featureFlags.includeLagDemand = true;
        base.featureFlags.includeHolidayCount = false;
        base.featureFlags.includeFreightIndex = false;
        base.featureFlags.includeConsumptionIndex = false;
        base.featureFlags.includeWEIIndex = false;

        base.standardizeTheta = true;
        base.kernelType = Helper.calculateHelper.KernelType.EXPONENTIAL;
        base.C_h = cH;
        base.threads = 4;
        base.timeLimitSeconds = 3600;
        base.seed = 0;
        return base;
    }

    private static void runSAA(Path outDir,
                               String tag,
                               List<String> lanes,
                               ProcurementParams params,
                               Config base,
                               ExperimentBatches rolling,
                               InstanceGenerator.GenConfig genCfg,
                               GlobalSummaryCollector summaryCollector,
                               GlobalTrialCollector trialCollector) throws Exception {
        Config cfg = copyBase(base);
        cfg.solveMode = SolveMode.SAA;
        SAACSAAPlainBatchRunner.run(tag, lanes, params, cfg, rolling, outDir, summaryCollector, trialCollector, genCfg);
    }

    private static void runCSAA(Path outDir,
                                String tag,
                                boolean lagDemandAsShare,
                                List<String> lanes,
                                ProcurementParams params,
                                Config base,
                                ExperimentBatches rolling,
                                InstanceGenerator.GenConfig genCfg,
                                GlobalSummaryCollector summaryCollector,
                                GlobalTrialCollector trialCollector) throws Exception {
        Config cfg = copyBase(base);
        cfg.solveMode = SolveMode.CSAA;
        cfg.lagDemandAsShare = lagDemandAsShare;
        SAACSAAPlainBatchRunner.run(tag, lanes, params, cfg, rolling, outDir, summaryCollector, trialCollector, genCfg);
    }

    private static void runMeanDet(Path outDir,
                                   String tag,
                                   List<String> lanes,
                                   ProcurementParams params,
                                   Config base,
                                   ExperimentBatches rolling,
                                   InstanceGenerator.GenConfig genCfg,
                                   GlobalSummaryCollector summaryCollector,
                                   GlobalTrialCollector trialCollector) throws Exception {
        Config cfg = copyBase(base);
        cfg.solveMode = SolveMode.MeanDeterministic;
        DeterministicBatchRunner.run(tag, lanes, params, cfg, rolling, outDir, summaryCollector, trialCollector, genCfg);
    }

    private static void runCompleteDet(Path outDir,
                                       String tag,
                                       List<String> lanes,
                                       ProcurementParams params,
                                       Config base,
                                       ExperimentBatches rolling,
                                       InstanceGenerator.GenConfig genCfg,
                                       GlobalSummaryCollector summaryCollector,
                                       GlobalTrialCollector trialCollector) throws Exception {
        Config cfg = copyBase(base);
        cfg.solveMode = SolveMode.CompleteDeterministic;
        DeterministicBatchRunner.run(tag, lanes, params, cfg, rolling, outDir, summaryCollector, trialCollector, genCfg);
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

        c.threads = b.threads;
        c.timeLimitSeconds = b.timeLimitSeconds;
        c.seed = b.seed;
        c.writeCplexLogToFile = b.writeCplexLogToFile;
        return c;
    }

    private static ThetaPostMode parseThetaPostMode(String raw) {
        String s = raw == null ? "raw" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("raw")) return ThetaPostMode.RAW;
        if (s.equals("pca3")) return ThetaPostMode.PCA3;
        if (s.equals("pca4")) return ThetaPostMode.PCA4;
        if (s.equals("pca5")) return ThetaPostMode.PCA5;
        throw new IllegalArgumentException("Unsupported theta post mode: " + raw + " (use raw/pca3/pca4/pca5)");
    }

    private static void applyThetaPostMode(List<Sample> samples, ThetaPostMode mode) {
        if (mode == ThetaPostMode.RAW) return;
        applyPcaProjection(samples, mode.k);
    }

    /**
     * Solve-runner 使用的 PCA 后处理说明：
     * 1. 这里是 Java 端自实现 PCA，不依赖外部库；
     * 2. 先对全部 theta 样本做中心化，再构造协方差矩阵；
     * 3. 用 power iteration + Gram-Schmidt 近似求前 k 个特征向量；
     * 4. 最后把每个 theta 投影到前 k 个主成分上。
     *
     * 注意：
     * - 这里做的是中心化 PCA，不是 z-score 标准化 PCA；
     * - 这里采用“先对当前整批 samples 做一次 PCA，再进入 rolling 求解”的口径，
     *   目的是快速比较 raw / pca3 / pca4 / pca5；
     * - 因此它和 quick-eval 脚本里的“每个 trial 内 train-only 标准化 + PCA”口径不同。
     */
    private static void applyPcaProjection(List<Sample> samples, int k) {
        if (samples == null || samples.isEmpty()) return;
        int n = samples.size();
        int d = samples.get(0).theta.values().length;
        if (d <= 1) return;
        int m = Math.min(k, d);
        if (m <= 0) return;

        double[] mean = new double[d];
        for (Sample s : samples) {
            double[] x = s.theta.values();
            for (int j = 0; j < d; j++) mean[j] += x[j];
        }
        for (int j = 0; j < d; j++) mean[j] /= n;

        double[][] xCentered = new double[n][d];
        for (int i = 0; i < n; i++) {
            double[] x = samples.get(i).theta.values();
            for (int j = 0; j < d; j++) xCentered[i][j] = x[j] - mean[j];
        }

        double[][] cov = new double[d][d];
        double denom = Math.max(1.0, n - 1.0);
        for (int i = 0; i < n; i++) {
            double[] r = xCentered[i];
            for (int a = 0; a < d; a++) {
                double va = r[a];
                for (int b = a; b < d; b++) {
                    cov[a][b] += va * r[b];
                }
            }
        }
        for (int a = 0; a < d; a++) {
            for (int b = a; b < d; b++) {
                cov[a][b] /= denom;
                cov[b][a] = cov[a][b];
            }
        }

        double[][] vecs = topKEigenvectors(cov, m);
        for (int i = 0; i < n; i++) {
            double[] z = new double[m];
            for (int p = 0; p < m; p++) {
                z[p] = dot(xCentered[i], vecs[p]);
            }
            samples.get(i).theta = new CovariateVector(z);
        }
    }

    private static double[][] topKEigenvectors(double[][] cov, int k) {
        int d = cov.length;
        double[][] vecs = new double[k][d];
        Random rnd = new Random(12345);

        for (int p = 0; p < k; p++) {
            double[] v = new double[d];
            for (int j = 0; j < d; j++) v[j] = rnd.nextDouble() - 0.5;
            normalize(v);
            if (norm(v) == 0.0) v[0] = 1.0;

            for (int it = 0; it < 300; it++) {
                double[] nv = matVec(cov, v);
                for (int q = 0; q < p; q++) {
                    double proj = dot(nv, vecs[q]);
                    for (int j = 0; j < d; j++) nv[j] -= proj * vecs[q][j];
                }
                normalize(nv);
                if (norm(nv) == 0.0) {
                    Arrays.fill(nv, 0.0);
                    nv[(p + it) % d] = 1.0;
                }
                if (distance(v, nv) < 1e-9) {
                    v = nv;
                    break;
                }
                v = nv;
            }
            vecs[p] = v;
        }
        return vecs;
    }

    private static double[] matVec(double[][] m, double[] v) {
        int n = m.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0.0;
            for (int j = 0; j < n; j++) s += m[i][j] * v[j];
            out[i] = s;
        }
        return out;
    }

    private static double norm(double[] x) {
        return Math.sqrt(dot(x, x));
    }

    private static void normalize(double[] x) {
        double n = norm(x);
        if (n <= 0) return;
        for (int i = 0; i < x.length; i++) x[i] /= n;
    }

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double distance(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) {
            double d = a[i] - b[i];
            s += d * d;
        }
        return Math.sqrt(s);
    }

    private static double[] buildBaselineDemand(SampleBuilder.BuildResult br) {
        int jSize = br.periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (var p : br.periods) {
            for (int j = 0; j < jSize; j++) sum[j] += p.demandSum[j];
        }
        double denom = Math.max(1, br.periods.size());
        for (int j = 0; j < jSize; j++) sum[j] /= denom;
        return sum;
    }

    private enum ThetaPostMode {
        RAW("raw", 0),
        PCA3("pca3", 3),
        PCA4("pca4", 4),
        PCA5("pca5", 5);

        final String tag;
        final int k;

        ThetaPostMode(String tag, int k) {
            this.tag = tag;
            this.k = k;
        }
    }
}

