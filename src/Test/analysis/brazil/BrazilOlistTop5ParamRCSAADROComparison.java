package Test.analysis.brazil;

import Basic.ProcurementParams;
import Basic.PeriodData;
import Basic.Sample;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Early post-processing utility for high-frequency parameter groups.
 * It counts exact-RCSAA selection frequencies and can optionally compare DRO and RCSAA
 * on the top-ranked parameter groups.
 */
public class BrazilOlistTop5ParamRCSAADROComparison {

    private static final boolean RUN_COMPARISON = false;
    private static final int TOP_N = 5;
    private static final int W = 50;
    private static final int NUM_CARRIERS = 10;
    private static final int RCSAA_SEARCH_RADIUS = 2;

    private static final Path MERGED_RCSAA_PARAMS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/结果/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果_ExactRCSAA枚举_merged/"
                    + "RCSAA_每个trial最优参数_合并结果.csv");

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");

    private static final Path OUT_ROOT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/结果/"
                    + "05_RCSAA高频参数_DRO对比");

    public static void main(String[] args) throws Exception {
        Path paramsCsv = (args.length > 0 ? Paths.get(args[0]) : MERGED_RCSAA_PARAMS);
        Path weeklyCsv = (args.length > 1 ? Paths.get(args[1]) : WEEKLY_INPUT);
        Path outRoot = (args.length > 2 ? Paths.get(args[2]) : OUT_ROOT);

        Files.createDirectories(outRoot);

        List<ParamSelectionRow> rows = loadMergedSelections(paramsCsv);
        Map<ParamKey, Integer> freq = countFrequencies(rows);
        List<ParamFrequencyRow> ranked = rankFrequencies(freq);
        List<ParamFrequencyRow> top = ranked.subList(0, Math.min(TOP_N, ranked.size()));

        writeFrequencyCsv(outRoot.resolve("RCSAA_参数频次统计_全部.csv"), ranked);
        writeFrequencyCsv(outRoot.resolve("RCSAA_参数频次统计_前5组.csv"), top);
        writeTextNote(outRoot.resolve("说明.txt"), top, paramsCsv, weeklyCsv);

        if (!RUN_COMPARISON) {
            System.out.println("count-only done: " + outRoot.toAbsolutePath());
            return;
        }

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = weekly.laneNames;
        double[] dBase = buildBaselineDemand(weekly.periods);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();

        for (int rank = 0; rank < top.size(); rank++) {
            ParamFrequencyRow row = top.get(rank);
            Config baseCfg = buildBaseConfig(row.key.bestK, row.key.bestCH, row.key.bestLambda);
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(weekly.periods, lanes, baseCfg);
            ProcurementParams params = InstanceGenerator.generate(NUM_CARRIERS, dBase, genCfg, baseCfg);
            ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
            if (rolling.size() == 0) {
                throw new IllegalStateException("No rolling trials for parameter group rank=" + (rank + 1));
            }

            String groupName = String.format(Locale.US,
                    "rank%02d_k%d_C%.2f_lambda%.2f_freq%d",
                    rank + 1,
                    row.key.bestK,
                    row.key.bestCH,
                    row.key.bestLambda,
                    row.count);
            Path groupRoot = outRoot.resolve(groupName);
            Files.createDirectories(groupRoot);

            runCompareForGroup(groupRoot, lanes, params, rolling, genCfg, row);
        }

        System.out.println("comparison done: " + outRoot.toAbsolutePath());
    }

    private static void runCompareForGroup(Path groupRoot,
                                           List<String> lanes,
                                           ProcurementParams params,
                                           ExperimentBatches rolling,
                                           InstanceGenerator.GenConfig genCfg,
                                           ParamFrequencyRow row) throws Exception {
        GlobalTrialCollector trialCollector = new GlobalTrialCollector(groupRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(groupRoot);

        Config droCfg = buildBaseConfig(row.key.bestK, row.key.bestCH, row.key.bestLambda);
        droCfg.solveMode = SolveMode.RCSAA;
        droCfg.rcsaaSolverVariant = RCSAASolverVariant.DRO_EXTENSIVE;
        String droTag = String.format(Locale.US,
                "巴西Olist23OD_RCSAA_DRO_EXTENSIVE_W%d_k1=%d_C%.2f_lambda%.2f",
                W, row.key.bestK, row.key.bestCH, row.key.bestLambda);
        DROBatchRunner.run(droTag, lanes, params, droCfg, rolling,
                groupRoot.resolve("RCSAA_DRO_EXTENSIVE"),
                summaryCollector, trialCollector, genCfg);

        Config primalSearchCfg = buildBaseConfig(row.key.bestK, row.key.bestCH, row.key.bestLambda);
        primalSearchCfg.solveMode = SolveMode.RCSAA;
        primalSearchCfg.rcsaaSolverVariant = RCSAASolverVariant.LBBD_PRIMAL_SEARCH;
        primalSearchCfg.rcsaaSearchNeighborhoodRadius = RCSAA_SEARCH_RADIUS;
        String searchTag = String.format(Locale.US,
                "巴西Olist23OD_RCSAA_LBBD_PRIMAL_SEARCH_W%d_k1=%d_C%.2f_lambda%.2f_r%d",
                W, row.key.bestK, row.key.bestCH, row.key.bestLambda, RCSAA_SEARCH_RADIUS);
        DROBatchRunner.run(searchTag, lanes, params, primalSearchCfg, rolling,
                groupRoot.resolve("RCSAA_LBBD_PRIMAL_SEARCH_r2"),
                summaryCollector, trialCollector, genCfg);
    }

    private static Config buildBaseConfig(int k1, double cH, double lambda) {
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
        cfg.rcsaaSearchNeighborhoodRadius = RCSAA_SEARCH_RADIUS;
        return cfg;
    }

    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : periods) {
            for (int j = 0; j < jSize; j++) {
                sum[j] += p.demandSum[j];
            }
        }
        double denom = Math.max(1, periods.size());
        for (int j = 0; j < jSize; j++) {
            sum[j] /= denom;
        }
        return sum;
    }

    private static List<ParamSelectionRow> loadMergedSelections(Path csv) throws Exception {
        List<ParamSelectionRow> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) {
                throw new IllegalArgumentException("Empty csv: " + csv);
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", -1);
                out.add(new ParamSelectionRow(
                        Integer.parseInt(f[0].trim()),
                        Integer.parseInt(f[1].trim()),
                        Integer.parseInt(f[2].trim()),
                        Double.parseDouble(f[3].trim()),
                        Double.parseDouble(f[4].trim())));
            }
        }
        return out;
    }

    private static Map<ParamKey, Integer> countFrequencies(List<ParamSelectionRow> rows) {
        Map<ParamKey, Integer> out = new LinkedHashMap<>();
        for (ParamSelectionRow row : rows) {
            ParamKey key = new ParamKey(row.bestK, row.bestCH, row.bestLambda);
            out.put(key, out.getOrDefault(key, 0) + 1);
        }
        return out;
    }

    private static List<ParamFrequencyRow> rankFrequencies(Map<ParamKey, Integer> freq) {
        List<ParamFrequencyRow> out = new ArrayList<>();
        for (Map.Entry<ParamKey, Integer> e : freq.entrySet()) {
            out.add(new ParamFrequencyRow(e.getKey(), e.getValue()));
        }
        out.sort(Comparator
                .comparingInt((ParamFrequencyRow r) -> r.count).reversed()
                .thenComparingInt(r -> r.key.bestK)
                .thenComparingDouble(r -> r.key.bestCH)
                .thenComparingDouble(r -> r.key.bestLambda));
        return out;
    }

    private static void writeFrequencyCsv(Path outCsv, List<ParamFrequencyRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outCsv, StandardCharsets.UTF_8)) {
            bw.write("rank,bestK,bestC_h,bestLambda,frequency");
            bw.newLine();
            for (int i = 0; i < rows.size(); i++) {
                ParamFrequencyRow row = rows.get(i);
                bw.write(String.format(Locale.US, "%d,%d,%.10f,%.10f,%d",
                        i + 1,
                        row.key.bestK,
                        row.key.bestCH,
                        row.key.bestLambda,
                        row.count));
                bw.newLine();
            }
        }
    }

    private static void writeTextNote(Path outTxt,
                                      List<ParamFrequencyRow> top,
                                      Path paramsCsv,
                                      Path weeklyCsv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outTxt, StandardCharsets.UTF_8)) {
            bw.write("Source merged params: " + paramsCsv.toAbsolutePath());
            bw.newLine();
            bw.write("Weekly input: " + weeklyCsv.toAbsolutePath());
            bw.newLine();
            bw.write("RCSAA compare solver variant: " + RCSAASolverVariant.LBBD_PRIMAL_SEARCH.name());
            bw.newLine();
            bw.write("RCSAA search radius: " + RCSAA_SEARCH_RADIUS);
            bw.newLine();
            bw.write("RUN_COMPARISON: " + RUN_COMPARISON);
            bw.newLine();
            bw.write("Top parameter groups:");
            bw.newLine();
            for (int i = 0; i < top.size(); i++) {
                ParamFrequencyRow row = top.get(i);
                bw.write(String.format(Locale.US,
                        "rank=%d, k=%d, C_h=%.10f, lambda=%.10f, frequency=%d",
                        i + 1,
                        row.key.bestK,
                        row.key.bestCH,
                        row.key.bestLambda,
                        row.count));
                bw.newLine();
            }
        }
    }

    private record ParamSelectionRow(int trialId, int testPeriodIdx, int bestK, double bestCH, double bestLambda) {}

    private record ParamKey(int bestK, double bestCH, double bestLambda) {}

    private record ParamFrequencyRow(ParamKey key, int count) {}

    @SuppressWarnings("unused")
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
}
