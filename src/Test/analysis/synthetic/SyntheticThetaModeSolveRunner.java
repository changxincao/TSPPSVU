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
import Test.DROBatchRunner;
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
 * 构造数据上的通用 theta 模式求解入口。
 *
 * 功能：
 * 1. 读取周度宽表；
 * 2. 构造 `k1` 阶 lag demand 协变量；
 * 3. 支持 `raw / pca3 / pca4 / pca5` 四种后处理模式；
 * 4. 支持 `CSAA / RCSAA` 两类求解；
 * 5. 输出 `global_summary / global_trials`。
 *
 * 说明：
 * - 默认使用 raw demand 口径，`lagDemandAsShare=false`。
 * - demand/share 口径在周总量固定时信息可能等价。
 *
 * Args:
 * 0 weekly csv path
 * 1 num carriers
 * 2 rolling window W
 * 3 k1 lag periods
 * 4 output root
 * 5 C_h
 * 6 solve mode: csaa | rcsaa
 * 7 theta post mode: raw | pca3 | pca4 | pca5
 * 8 lambda (used only for RCSAA)
 */
public class SyntheticThetaModeSolveRunner {

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = Paths.get(args.length > 0 ? args[0] : "analysis/合成需求_周度_仅前90%需求线路.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 15);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 24);
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 3);
        String outRootArg = (args.length > 4 ? args[4] : null);
        double cH = (args.length > 5 ? Double.parseDouble(args[5]) : 1.0);
        SolveMode solveMode = parseSolveMode(args.length > 6 ? args[6] : "rcsaa");
        ThetaPostMode thetaPostMode = parseThetaPostMode(args.length > 7 ? args[7] : "raw");
        double lambda = (args.length > 8 ? Double.parseDouble(args[8]) : 1.0);

        String defaultRootName;
        if (solveMode == SolveMode.RCSAA) {
            defaultRootName = String.format(Locale.US,
                    "analysis/构造数据RCSAA对比_W%d_k1=%d_%s_C%.2f_lambda%.2f",
                    W, k1, thetaPostMode.tag, cH, lambda);
        } else {
            defaultRootName = String.format(Locale.US,
                    "analysis/构造数据CSAA对比_W%d_k1=%d_%s_C%.2f",
                    W, k1, thetaPostMode.tag, cH);
        }

        Path outRoot = (outRootArg == null || outRootArg.isBlank())
                ? Paths.get(defaultRootName)
                : Paths.get(outRootArg);
        Files.createDirectories(outRoot);

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Config cfg = buildConfig(k1, cH, solveMode, lambda);
        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, cfg);
        applyThetaPostMode(br.samples, thetaPostMode);

        double[] dBase = buildBaselineDemand(br);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);

        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
        if (rolling.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        String tag = buildTag(solveMode, W, k1, thetaPostMode, lambda);
        GlobalTrialCollector trialCollector = new GlobalTrialCollector(outRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(outRoot);

        System.out.println("weeklyCsv: " + weeklyCsv.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("lanes=" + lanes.size() + ", periods=" + w.periods.size() + ", trials=" + rolling.size());
        System.out.println("W=" + W + ", k1=" + k1 + ", C_h=" + cH + ", solveMode=" + solveMode + ", thetaPost=" + thetaPostMode.tag + ", lambda=" + lambda);

        if (solveMode == SolveMode.CSAA) {
            SAACSAAPlainBatchRunner.run(tag, lanes, params, cfg, rolling, outRoot.resolve("CSAA"), summaryCollector, trialCollector, genCfg);
        } else if (solveMode == SolveMode.RCSAA) {
            DROBatchRunner.run(tag, lanes, params, cfg, rolling, outRoot.resolve("RCSAA"), summaryCollector, trialCollector, genCfg);
        } else {
            throw new IllegalArgumentException("Unsupported solve mode for this runner: " + solveMode);
        }

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static Config buildConfig(int k1, double cH, SolveMode solveMode, double lambda) {
        Config cfg = new Config();
        cfg.fillMissingDates = false;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = k1;
        cfg.demandAgg = false;
        cfg.lagDemandAsShare = false;

        cfg.featureFlags.includeLagDemand = true;
        cfg.featureFlags.includeHolidayCount = false;
        cfg.featureFlags.includeFreightIndex = false;
        cfg.featureFlags.includeConsumptionIndex = false;
        cfg.featureFlags.includeWEIIndex = false;

        cfg.standardizeTheta = true;
        cfg.kernelType = Helper.calculateHelper.KernelType.EXPONENTIAL;
        cfg.C_h = cH;
        cfg.solveMode = solveMode;
        cfg.lambda = lambda;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        return cfg;
    }

    private static String buildTag(SolveMode solveMode, int W, int k1, ThetaPostMode thetaPostMode, double lambda) {
        if (solveMode == SolveMode.RCSAA) {
            return String.format(Locale.US, "合成需求RCSAA_W%d_k1=%d_%s_lambda%.2f", W, k1, thetaPostMode.tag, lambda);
        }
        return String.format(Locale.US, "合成需求CSAA_W%d_k1=%d_%s", W, k1, thetaPostMode.tag);
    }

    private static SolveMode parseSolveMode(String raw) {
        String s = raw == null ? "csaa" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("csaa")) return SolveMode.CSAA;
        if (s.equals("rcsaa")) return SolveMode.RCSAA;
        throw new IllegalArgumentException("Unsupported solve mode: " + raw + " (use csaa/rcsaa)");
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
            for (int p = 0; p < m; p++) z[p] = dot(xCentered[i], vecs[p]);
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

    private static double dot(double[] a, double[] b) {
        double s = 0.0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static double norm(double[] x) {
        return Math.sqrt(dot(x, x));
    }

    private static void normalize(double[] x) {
        double n = norm(x);
        if (n <= 0.0) return;
        for (int i = 0; i < x.length; i++) x[i] /= n;
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
        int m = br.periods.get(0).demandSum.length;
        double[] d = new double[m];
        int cnt = Math.max(1, br.samples.size());
        for (Sample s : br.samples) {
            for (int j = 0; j < m; j++) d[j] += s.demand()[j];
        }
        for (int j = 0; j < m; j++) d[j] /= cnt;
        return d;
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

