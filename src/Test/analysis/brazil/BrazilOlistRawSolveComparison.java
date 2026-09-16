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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Main baseline runner for Brazil data under raw covariates.
 * This is the core input-processing-output entry for Mean, SAA, CSAA, and related baseline methods.
 */
public class BrazilOlistRawSolveComparison {

    public static void main(String[] args) throws Exception {
        Path dailyCsv = Paths.get(args.length > 0 ? args[0] : "analysis/巴西数据分析/旧版/五大区合并后日度OD需求表_千克.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 15);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 1);
        String outRootArg = (args.length > 4 ? args[4] : null);
        double cH = (args.length > 5 ? Double.parseDouble(args[5]) : 1.0);

        Path outRoot = (outRootArg == null || outRootArg.isBlank())
                ? Paths.get(String.format(Locale.US,
                "analysis/巴西数据分析/求解对比_W%d_k1=%d_rawDemand_C%.2f", W, k1, cH))
                : Paths.get(outRootArg);
        Files.createDirectories(outRoot);

        Path weeklyCsv = outRoot.resolve("巴西五大区23OD_周度宽表.csv");
        aggregateDailyLongToWeeklyWide(dailyCsv, weeklyCsv);

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Config base = buildBaseConfig(k1, cH);
        base.lagDemandAsShare = false;

        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, base);
        double[] dBase = buildBaselineDemand(br);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, base);

        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
        if (rolling.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        String baseTag = String.format(Locale.US, "巴西Olist23OD_W%d_k1=%d_rawDemand", W, k1);
        GlobalTrialCollector trialCollector = new GlobalTrialCollector(outRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(outRoot);

        System.out.println("dailyCsv: " + dailyCsv.toAbsolutePath());
        System.out.println("weeklyCsv: " + weeklyCsv.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("lanes=" + lanes.size() + ", periods=" + w.periods.size() + ", trials=" + rolling.size());
        System.out.println("W=" + W + ", k1=" + k1 + ", C_h=" + cH + ", theta=rawDemand");

        System.out.println("=== Run SAA ===");
        runSAA(outRoot.resolve("SAA"), baseTag, lanes, params, base, rolling, genCfg, summaryCollector, trialCollector);

        System.out.println("=== Run CSAA (raw demand theta) ===");
        runCSAA(outRoot.resolve("CSAA_rawDemand"), baseTag + "_thetaDemand", lanes, params, base, rolling, genCfg, summaryCollector, trialCollector);

        System.out.println("=== Run MeanDeterministic ===");
        runMeanDet(outRoot.resolve("MeanDeterministic"), baseTag, lanes, params, base, rolling, genCfg, summaryCollector, trialCollector);

        System.out.println("=== Run CompleteDeterministic ===");
        runCompleteDet(outRoot.resolve("CompleteDeterministic"), baseTag, lanes, params, base, rolling, genCfg, summaryCollector, trialCollector);

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static Config buildBaseConfig(int k1, double cH) {
        Config base = new Config();
        base.fillMissingDates = false;
        base.aggregationDays = 7;
        base.k1LagPeriods = k1;
        base.demandAgg = false;
        base.lagDemandAsShare = false;

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
                                List<String> lanes,
                                ProcurementParams params,
                                Config base,
                                ExperimentBatches rolling,
                                InstanceGenerator.GenConfig genCfg,
                                GlobalSummaryCollector summaryCollector,
                                GlobalTrialCollector trialCollector) throws Exception {
        Config cfg = copyBase(base);
        cfg.solveMode = SolveMode.CSAA;
        cfg.lagDemandAsShare = false;
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
                byDayLane.computeIfAbsent(date, k -> new HashMap<>())
                        .merge(lane, demand, Double::sum);
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

