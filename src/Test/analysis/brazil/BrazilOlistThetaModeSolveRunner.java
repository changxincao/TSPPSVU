package Test.analysis.brazil;

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
import Test.DROBatchRunner;
import Test.ExperimentBatches;
import Test.ExperimentBuilder;
import Test.SAACSAAPlainBatchRunner;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

/**
 * General Brazil-data runner that switches among raw / PCA theta modes and CSAA / RCSAA variants.
 * Use this class for systematic comparisons of covariate representation, C_h, and lambda effects.
 */
public class BrazilOlistThetaModeSolveRunner {

    public static void main(String[] args) throws Exception {
        Path dailyCsv = Paths.get(args.length > 0 ? args[0] : "analysis/巴西数据分析/旧版/五大区合并后日度OD需求表_千克.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 15);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 1);
        String outRootArg = (args.length > 4 ? args[4] : null);
        double cH = (args.length > 5 ? Double.parseDouble(args[5]) : 1.0);
        SolveMode solveMode = parseSolveMode(args.length > 6 ? args[6] : "csaa");
        ThetaPostMode thetaPostMode = parseThetaPostMode(args.length > 7 ? args[7] : "raw");
        double lambda = (args.length > 8 ? Double.parseDouble(args[8]) : 1.0);
        Double mqcLowArg = (args.length > 9 ? Double.parseDouble(args[9]) : null);
        Double mqcHighArg = (args.length > 10 ? Double.parseDouble(args[10]) : null);
        Double capLowArg = (args.length > 11 ? Double.parseDouble(args[11]) : null);
        Double capHighArg = (args.length > 12 ? Double.parseDouble(args[12]) : null);

        String defaultRootName;
        if (solveMode == SolveMode.RCSAA) {
            defaultRootName = String.format(Locale.US,
                    "analysis/巴西数据分析/RCSAA对比_W%d_k1=%d_%s_C%.2f_lambda%.2f",
                    W, k1, thetaPostMode.tag, cH, lambda);
        } else {
            defaultRootName = String.format(Locale.US,
                    "analysis/巴西数据分析/CSAA对比_W%d_k1=%d_%s_C%.2f",
                    W, k1, thetaPostMode.tag, cH);
        }

        Path outRoot = (outRootArg == null || outRootArg.isBlank())
                ? Paths.get(defaultRootName)
                : Paths.get(outRootArg);
        Files.createDirectories(outRoot);

        Path weeklyCsv = outRoot.resolve("巴西五大区23OD_周度宽表.csv");
        aggregateDailyLongToWeeklyWide(dailyCsv, weeklyCsv);

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Config cfg = buildConfig(k1, cH, solveMode, lambda);
        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, cfg);
        applyThetaPostMode(br.samples, thetaPostMode);

        double[] dBase = buildBaselineDemand(br);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        applyGeneratorOverrides(genCfg, mqcLowArg, mqcHighArg, capLowArg, capHighArg);
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);

        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
        if (rolling.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        String tag = buildTag(solveMode, W, k1, thetaPostMode, lambda);
        GlobalTrialCollector trialCollector = new GlobalTrialCollector(outRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(outRoot);

        System.out.println("dailyCsv: " + dailyCsv.toAbsolutePath());
        System.out.println("weeklyCsv: " + weeklyCsv.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("lanes=" + lanes.size() + ", periods=" + w.periods.size() + ", trials=" + rolling.size());
        System.out.println("W=" + W + ", k1=" + k1 + ", C_h=" + cH + ", solveMode=" + solveMode + ", thetaPost=" + thetaPostMode.tag + ", lambda=" + lambda);
        System.out.println("mqcLow=" + genCfg.mqcLow + ", mqcHigh=" + genCfg.mqcHigh
                + ", capacityFactorLow=" + genCfg.capacityFactorLow + ", capacityFactorHigh=" + genCfg.capacityFactorHigh);

        if (solveMode == SolveMode.CSAA) {
            SAACSAAPlainBatchRunner.run(tag, lanes, params, cfg, rolling, outRoot.resolve("CSAA"), summaryCollector, trialCollector, genCfg);
        } else if (solveMode == SolveMode.RCSAA) {
            DROBatchRunner.run(tag, lanes, params, cfg, rolling, outRoot.resolve("RCSAA"), summaryCollector, trialCollector, genCfg);
        } else if (solveMode == SolveMode.MeanDeterministic || solveMode == SolveMode.CompleteDeterministic) {
            DeterministicBatchRunner.run(tag, lanes, params, cfg, rolling, outRoot.resolve("Deterministic"), summaryCollector, trialCollector, genCfg);
        } else {
            throw new IllegalArgumentException("Unsupported solve mode for this runner: " + solveMode);
        }

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static void applyGeneratorOverrides(InstanceGenerator.GenConfig genCfg,
                                                Double mqcLowArg,
                                                Double mqcHighArg,
                                                Double capLowArg,
                                                Double capHighArg) {
        if (mqcLowArg != null) genCfg.mqcLow = mqcLowArg;
        if (mqcHighArg != null) genCfg.mqcHigh = mqcHighArg;
        if (capLowArg != null) genCfg.capacityFactorLow = capLowArg;
        if (capHighArg != null) genCfg.capacityFactorHigh = capHighArg;
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
            return String.format(Locale.US, "宸磋タOlist23OD_RCSAA_W%d_k1=%d_%s_lambda%.2f", W, k1, thetaPostMode.tag, lambda);
        }
        if (solveMode == SolveMode.MeanDeterministic) {
            return String.format(Locale.US, "宸磋タOlist23OD_MeanDeterministic_W%d_k1=%d_%s", W, k1, thetaPostMode.tag);
        }
        if (solveMode == SolveMode.CompleteDeterministic) {
            return String.format(Locale.US, "宸磋タOlist23OD_CompleteDeterministic_W%d_k1=%d_%s", W, k1, thetaPostMode.tag);
        }
        return String.format(Locale.US, "宸磋タOlist23OD_CSAA_W%d_k1=%d_%s", W, k1, thetaPostMode.tag);
    }

    private static SolveMode parseSolveMode(String raw) {
        String s = raw == null ? "csaa" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("csaa")) return SolveMode.CSAA;
        if (s.equals("rcsaa")) return SolveMode.RCSAA;
        if (s.equals("meandet") || s.equals("mean") || s.equals("meandeterministic")) return SolveMode.MeanDeterministic;
        if (s.equals("completedet") || s.equals("complete") || s.equals("completedeterministic")) return SolveMode.CompleteDeterministic;
        throw new IllegalArgumentException("Unsupported solve mode: " + raw + " (use csaa/rcsaa/meandet/completedet)");
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

    private static void aggregateDailyLongToWeeklyWide(Path dailyCsv, Path weeklyCsv) throws Exception {
        Map<LocalDate, Map<String, Double>> byDayLane = new HashMap<>();
        TreeSet<String> laneSet = new TreeSet<>();
        LocalDate minDate = null;
        LocalDate maxDate = null;

        try (BufferedReader br = Files.newBufferedReader(dailyCsv)) {
            String header = br.readLine();
            if (header == null) throw new IllegalArgumentException("Empty CSV: " + dailyCsv);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length < 3) continue;
                LocalDate date = LocalDate.parse(f[0].trim());
                String lane = f[1].trim();
                double demand = parseDoubleSafe(f[2]);

                laneSet.add(lane);
                byDayLane.computeIfAbsent(date, k -> new HashMap<>()).merge(lane, demand, Double::sum);
                if (minDate == null || date.isBefore(minDate)) minDate = date;
                if (maxDate == null || date.isAfter(maxDate)) maxDate = date;
            }
        }

        if (minDate == null || maxDate == null || laneSet.isEmpty()) {
            throw new IllegalArgumentException("No valid daily rows in: " + dailyCsv);
        }

        List<String> lanes = new ArrayList<>(laneSet);
        List<LocalDate> allDays = new ArrayList<>();
        for (LocalDate d = minDate; !d.isAfter(maxDate); d = d.plusDays(1)) {
            allDays.add(d);
        }

        try (BufferedWriter bw = Files.newBufferedWriter(weeklyCsv)) {
            bw.write("weekIndex");
            for (String lane : lanes) {
                bw.write(",");
                bw.write(lane);
            }
            bw.newLine();

            int weekIndex = 0;
            for (int start = 0; start + 6 < allDays.size(); start += 7) {
                double[] sum = new double[lanes.size()];
                for (int k = start; k < start + 7; k++) {
                    LocalDate d = allDays.get(k);
                    Map<String, Double> rec = byDayLane.getOrDefault(d, Collections.emptyMap());
                    for (int j = 0; j < lanes.size(); j++) sum[j] += rec.getOrDefault(lanes.get(j), 0.0);
                }
                bw.write(Integer.toString(weekIndex));
                for (double v : sum) {
                    bw.write(",");
                    bw.write(String.format(Locale.US, "%.6f", v));
                }
                bw.newLine();
                weekIndex++;
            }
        }
    }

    private static double parseDoubleSafe(String s) {
        try {
            String t = (s == null ? "" : s.trim());
            if (t.isEmpty()) return 0.0;
            return Double.parseDouble(t);
        } catch (Exception ex) {
            return 0.0;
        }
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


