package Test.analysis.brazil;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Model.RCSAASolverVariant;
import Model.SolveMode;
import Test.DROBatchRunner;
import Test.ExperimentBatches;
import Test.ExperimentBuilder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Compares exact RCSAA against LBBD / primal-search solvers under matched settings.
 * It is mainly used to study objective consistency, out-of-sample performance, and solve-time gaps.
 */
public class BrazilOlistRCSAALBBDComparison {

    public static void main(String[] args) throws Exception {
        Path inputCsv = Paths.get(args.length > 0 ? args[0]
                : "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 10);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 1);
        String outRootArg = (args.length > 4 ? args[4] : null);
        double cH = (args.length > 5 ? Double.parseDouble(args[5]) : 0.10);
        double lambda = (args.length > 6 ? Double.parseDouble(args[6]) : 5.0);
        int searchRadius = (args.length > 7 ? Integer.parseInt(args[7]) : 2);

        Path outRoot = (outRootArg == null || outRootArg.isBlank())
                ? Paths.get(String.format(Locale.US,
                "analysis/巴西数据分析/新版_purchase时间/输出/13_RCSAA_LBBD对比/原口径_10供应商_W%d_k1=%d_raw_C%.2f_lambda%.2f_r%d",
                W, k1, cH, lambda, searchRadius))
                : Paths.get(outRootArg);
        Files.createDirectories(outRoot);

        Path weeklyCsv;
        if (looksLikeWeeklyWideCsv(inputCsv)) {
            // 传入的是现成周度宽表时，直接读取，保持原始实验输入口径。
            weeklyCsv = inputCsv;
        } else {
            // 仅当传入的是日度长表时，才重新聚合生成周度宽表。
            weeklyCsv = outRoot.resolve("巴西五大区23OD_周度宽表.csv");
            aggregateDailyLongToWeeklyWide(inputCsv, weeklyCsv);
        }

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Config baseCfg = buildConfig(k1, cH, lambda);
        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, baseCfg);
        double[] dBase = buildBaselineDemand(br);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, baseCfg);

        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
        if (rolling.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        GlobalTrialCollector trialCollector = new GlobalTrialCollector(outRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(outRoot);

        System.out.println("inputCsv: " + inputCsv.toAbsolutePath());
        System.out.println("weeklyCsv: " + weeklyCsv.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("lanes=" + lanes.size() + ", periods=" + w.periods.size() + ", trials=" + rolling.size());
        System.out.println("W=" + W + ", k1=" + k1 + ", C_h=" + cH + ", lambda=" + lambda
                + ", theta=rawDemand, searchRadius=" + searchRadius);

        Config exactCfg = copyConfig(baseCfg);
        exactCfg.rcsaaSolverVariant = RCSAASolverVariant.LBBD_EXACT;
        String exactTag = String.format(Locale.US,
                "巴西Olist23OD_RCSAA_LBBD_EXACT_W%d_k1=%d_rawDemand_lambda%.2f", W, k1, lambda);
        DROBatchRunner.run(exactTag, lanes, params, exactCfg, rolling,
                outRoot.resolve("RCSAA_LBBD_EXACT"), summaryCollector, trialCollector, genCfg);

        Config searchCfg = copyConfig(baseCfg);
        searchCfg.rcsaaSolverVariant = RCSAASolverVariant.LBBD_SEARCH;
        searchCfg.rcsaaSearchNeighborhoodRadius = searchRadius;
        String searchTag = String.format(Locale.US,
                "巴西Olist23OD_RCSAA_LBBD_SEARCH_W%d_k1=%d_rawDemand_lambda%.2f_r%d",
                W, k1, lambda, searchRadius);
        DROBatchRunner.run(searchTag, lanes, params, searchCfg, rolling,
                outRoot.resolve("RCSAA_LBBD_SEARCH"), summaryCollector, trialCollector, genCfg);

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static Config buildConfig(int k1, double cH, double lambda) {
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
        cfg.lambda = lambda;
        cfg.solveMode = SolveMode.RCSAA;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        return cfg;
    }

    private static Config copyConfig(Config src) {
        Config cfg = new Config();
        cfg.tol = src.tol;
        cfg.aggregationDays = src.aggregationDays;
        cfg.k1LagPeriods = src.k1LagPeriods;
        cfg.demandAgg = src.demandAgg;
        cfg.fillMissingDates = src.fillMissingDates;
        cfg.lagDemandAsShare = src.lagDemandAsShare;

        cfg.featureFlags.includeLagDemand = src.featureFlags.includeLagDemand;
        cfg.featureFlags.includeHolidayCount = src.featureFlags.includeHolidayCount;
        cfg.featureFlags.includeFreightIndex = src.featureFlags.includeFreightIndex;
        cfg.featureFlags.includeConsumptionIndex = src.featureFlags.includeConsumptionIndex;
        cfg.featureFlags.includeWEIIndex = src.featureFlags.includeWEIIndex;

        cfg.standardizeTheta = src.standardizeTheta;
        cfg.thetaScaling = src.thetaScaling;
        cfg.bandwidthH = src.bandwidthH;
        cfg.C_h = src.C_h;
        cfg.epsDenominator = src.epsDenominator;
        cfg.lambda = src.lambda;
        cfg.lambda_lower = src.lambda_lower;
        cfg.lambda_upper = src.lambda_upper;
        cfg.kernelType = src.kernelType;

        cfg.writeCplexLogToFile = src.writeCplexLogToFile;
        cfg.timeLimitSeconds = src.timeLimitSeconds;
        cfg.threads = src.threads;
        cfg.solveMode = src.solveMode;
        cfg.maxBendersIter = src.maxBendersIter;
        cfg.rcsaaSolverVariant = src.rcsaaSolverVariant;
        cfg.rcsaaSearchNeighborhoodRadius = src.rcsaaSearchNeighborhoodRadius;
        cfg.seed = src.seed;
        cfg.enableTrialSingleSampleDeterministicEval = src.enableTrialSingleSampleDeterministicEval;
        return cfg;
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
                    for (int j = 0; j < lanes.size(); j++) {
                        sum[j] += rec.getOrDefault(lanes.get(j), 0.0);
                    }
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

    private static boolean looksLikeWeeklyWideCsv(Path csv) throws Exception {
        try (BufferedReader br = Files.newBufferedReader(csv)) {
            String header = br.readLine();
            if (header == null) return false;
            String h = header.trim().toLowerCase(Locale.ROOT);
            return h.startsWith("weekindex,") || h.equals("weekindex");
        }
    }
}
