package Test.analysis.realhistory;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.CsvHistoryLoader;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Model.SolveMode;
import Test.DeterministicBatchRunner;
import Test.ExperimentBatches;
import Test.ExperimentBuilder;
import Test.SAACSAAPlainBatchRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * 原始真实数据 baseline 口径求解入口。
 *
 * 功能：
 * 1. 读取 `fFreight_with_indicators_and_holidays.csv`；
 * 2. 使用与 quick-eval 中 `old_baseline_current` 一致的协变量：
 *    前 k1 个周期总需求 + 当前周期 holidayCount / avgFreight / avgConsumption / avgWEI；
 * 3. 在同一 rolling 划分下运行 SAA / CSAA / MeanDeterministic / CompleteDeterministic；
 * 4. 输出与既有 global_summary / global_trials 同口径的结果文件。
 *
 * 额外说明：
 * 1. `k1=0` 合法，此时 theta 只包含当前周期外生协变量，不再拼接过去总需求；
 * 2. 为避免在不同 `C_h` 下重复求解同一组 baseline，本入口支持：
 *    - `baselines`：只跑 SAA / MeanDeterministic / CompleteDeterministic
 *    - `csaa`：只跑 CSAA
 *    - `all`：兼容旧行为，四种方法都跑
 *
 * 默认实验口径：
 * - fillMissingDates = true
 * - aggregationDays = 7
 * - standardizeTheta = true
 * - W = 50
 */
public class RealHistoryBaselineSolveComparison {

    private enum RunMode {
        ALL,
        BASELINES,
        CSAA
    }

    public static void main(String[] args) throws Exception {
        Path historyCsv = Paths.get(args.length > 0 ? args[0] : "fFreight_with_indicators_and_holidays.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 15);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 1);
        String outRootArg = (args.length > 4 ? args[4] : null);
        double cH = (args.length > 5 ? Double.parseDouble(args[5]) : 1.0);
        RunMode runMode = parseRunMode(args.length > 6 ? args[6] : "all");

        Path outRoot = (outRootArg == null || outRootArg.isBlank())
                ? Paths.get(defaultOutRoot(W, k1, cH, runMode))
                : Paths.get(outRootArg);
        Files.createDirectories(outRoot);

        Config base = buildBaseConfig(k1, cH);
        CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, base);
        SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, base);
        double[] dBase = buildBaselineDemand(br);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, base);

        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
        if (rolling.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        String baseTag = buildBaseTag(W, k1, cH, runMode);
        GlobalTrialCollector trialCollector = new GlobalTrialCollector(outRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(outRoot);

        System.out.println("historyCsv: " + historyCsv.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("lanes=" + hist.laneNames.size() + ", days=" + hist.days.size() + ", periods=" + br.periods.size() + ", trials=" + rolling.size());
        System.out.println("W=" + W + ", k1=" + k1 + ", C_h=" + cH + ", runMode=" + runMode
                + ", theta=baseline(totalDemandLag + current exogenous), fillMissingDates=true, standardizeTheta=true");

        if (runMode == RunMode.ALL || runMode == RunMode.BASELINES) {
            runSAA(outRoot.resolve("SAA"), baseTag, hist.laneNames, params, base, rolling, genCfg, summaryCollector, trialCollector);
            runMeanDet(outRoot.resolve("MeanDeterministic"), baseTag, hist.laneNames, params, base, rolling, genCfg, summaryCollector, trialCollector);
            runCompleteDet(outRoot.resolve("CompleteDeterministic"), baseTag, hist.laneNames, params, base, rolling, genCfg, summaryCollector, trialCollector);
        }
        if (runMode == RunMode.ALL || runMode == RunMode.CSAA) {
            runCSAA(outRoot.resolve("CSAA"), baseTag, hist.laneNames, params, base, rolling, genCfg, summaryCollector, trialCollector);
        }

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static RunMode parseRunMode(String raw) {
        String s = raw == null ? "all" : raw.trim().toLowerCase(Locale.ROOT);
        if (s.equals("all")) return RunMode.ALL;
        if (s.equals("baselines")) return RunMode.BASELINES;
        if (s.equals("csaa")) return RunMode.CSAA;
        throw new IllegalArgumentException("Unsupported run mode: " + raw + " (use all/baselines/csaa)");
    }

    private static String defaultOutRoot(int W, int k1, double cH, RunMode runMode) {
        if (runMode == RunMode.BASELINES) {
            return String.format(Locale.US, "analysis/原始真实数据/求解对比_W%d_k1=%d_基线", W, k1);
        }
        if (runMode == RunMode.CSAA) {
            return String.format(Locale.US, "analysis/原始真实数据/求解对比_W%d_k1=%d_CSAA_C%.2f", W, k1, cH);
        }
        return String.format(Locale.US, "analysis/原始真实数据/求解对比_W%d_k1=%d_C%.2f", W, k1, cH);
    }

    private static String buildBaseTag(int W, int k1, double cH, RunMode runMode) {
        if (runMode == RunMode.BASELINES) {
            return String.format(Locale.US, "原始真实数据_W%d_k1=%d_基线", W, k1);
        }
        if (runMode == RunMode.CSAA) {
            return String.format(Locale.US, "原始真实数据_W%d_k1=%d_CSAA_C%.2f", W, k1, cH);
        }
        return String.format(Locale.US, "原始真实数据_W%d_k1=%d_C%.2f", W, k1, cH);
    }

    private static Config buildBaseConfig(int k1, double cH) {
        Config cfg = new Config();
        cfg.fillMissingDates = true;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = k1;
        cfg.demandAgg = true;
        cfg.lagDemandAsShare = false;

        cfg.featureFlags.includeLagDemand = (k1 > 0);
        cfg.featureFlags.includeHolidayCount = true;
        cfg.featureFlags.includeFreightIndex = true;
        cfg.featureFlags.includeConsumptionIndex = true;
        cfg.featureFlags.includeWEIIndex = true;

        cfg.standardizeTheta = true;
        cfg.kernelType = Helper.calculateHelper.KernelType.EXPONENTIAL;
        cfg.C_h = cH;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        return cfg;
    }

    private static void runSAA(Path outDir, String tag, List<String> lanes, ProcurementParams params, Config base,
                               ExperimentBatches rolling, InstanceGenerator.GenConfig genCfg,
                               GlobalSummaryCollector summaryCollector, GlobalTrialCollector trialCollector) throws Exception {
        Config cfg = copyBase(base);
        cfg.solveMode = SolveMode.SAA;
        SAACSAAPlainBatchRunner.run(tag, lanes, params, cfg, rolling, outDir, summaryCollector, trialCollector, genCfg);
    }

    private static void runCSAA(Path outDir, String tag, List<String> lanes, ProcurementParams params, Config base,
                                ExperimentBatches rolling, InstanceGenerator.GenConfig genCfg,
                                GlobalSummaryCollector summaryCollector, GlobalTrialCollector trialCollector) throws Exception {
        Config cfg = copyBase(base);
        cfg.solveMode = SolveMode.CSAA;
        SAACSAAPlainBatchRunner.run(tag, lanes, params, cfg, rolling, outDir, summaryCollector, trialCollector, genCfg);
    }

    private static void runMeanDet(Path outDir, String tag, List<String> lanes, ProcurementParams params, Config base,
                                   ExperimentBatches rolling, InstanceGenerator.GenConfig genCfg,
                                   GlobalSummaryCollector summaryCollector, GlobalTrialCollector trialCollector) throws Exception {
        Config cfg = copyBase(base);
        cfg.solveMode = SolveMode.MeanDeterministic;
        DeterministicBatchRunner.run(tag, lanes, params, cfg, rolling, outDir, summaryCollector, trialCollector, genCfg);
    }

    private static void runCompleteDet(Path outDir, String tag, List<String> lanes, ProcurementParams params, Config base,
                                       ExperimentBatches rolling, InstanceGenerator.GenConfig genCfg,
                                       GlobalSummaryCollector summaryCollector, GlobalTrialCollector trialCollector) throws Exception {
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
}

