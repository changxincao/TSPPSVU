package Test.analysis.brazil;

import Basic.ProcurementParams;
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
 * Early dedicated RCSAA comparison runner.
 * It predates the more general theta-mode runner and was used to quickly compare lambda settings
 * under a fixed raw-demand configuration.
 */
public class BrazilOlistRCSAAComparison {

    public static void main(String[] args) throws Exception {
        Path dailyCsv = Paths.get(args.length > 0 ? args[0] : "analysis/巴西数据分析/旧版/五大区合并后日度OD需求表_千克.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 15);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 1);
        String outRootArg = (args.length > 4 ? args[4] : null);
        double cH = (args.length > 5 ? Double.parseDouble(args[5]) : 1.0);
        double lambda = (args.length > 6 ? Double.parseDouble(args[6]) : 0.1);

        Path outRoot = (outRootArg == null || outRootArg.isBlank())
                ? Paths.get(String.format(Locale.US,
                "analysis/巴西数据分析/RCSAA对比_W%d_k1=%d_rawDemand_C%.2f_lambda%.2f", W, k1, cH, lambda))
                : Paths.get(outRootArg);
        Files.createDirectories(outRoot);

        Path weeklyCsv = outRoot.resolve("巴西五大区23OD_周度宽表.csv");
        aggregateDailyLongToWeeklyWide(dailyCsv, weeklyCsv);

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Config cfg = buildConfig(k1, cH, lambda);
        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, cfg);
        double[] dBase = buildBaselineDemand(br);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);

        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
        if (rolling.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        String tag = String.format(Locale.US, "巴西Olist23OD_RCSAA_W%d_k1=%d_rawDemand_lambda%.2f", W, k1, lambda);
        GlobalTrialCollector trialCollector = new GlobalTrialCollector(outRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(outRoot);

        System.out.println("dailyCsv: " + dailyCsv.toAbsolutePath());
        System.out.println("weeklyCsv: " + weeklyCsv.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("lanes=" + lanes.size() + ", periods=" + w.periods.size() + ", trials=" + rolling.size());
        System.out.println("W=" + W + ", k1=" + k1 + ", C_h=" + cH + ", lambda=" + lambda + ", theta=rawDemand");

        DROBatchRunner.run(tag, lanes, params, cfg, rolling, outRoot.resolve("RCSAA_rawDemand"), summaryCollector, trialCollector, genCfg);

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
}

