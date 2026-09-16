package Test;

import Basic.CovariateVector;
import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Model.SAAModel;
import Model.Solution;
import Model.SolveMode;

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
 * Study how "single in-sample scenario deterministic optima" generalize to the out-of-sample test sample
 * on the Brazil Olist purchase-time dataset.
 *
 * For each rolling trial:
 * 1. Solve standard SAA on the full training window and evaluate on the test demand.
 * 2. For each training sample in that trial, solve a deterministic one-sample problem and evaluate the
 *    resulting y on the same test demand.
 * 3. Write sample-level rows and a trial-level summary that compares the single-sample average/median/min
 *    against SAA.
 */
public class BrazilOlistSingleSampleDetStudy {

    public static void main(String[] args) throws Exception {
        Path dailyCsv = Paths.get(args.length > 0 ? args[0]
                : "analysis/巴西数据分析/新版_purchase时间/按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 10);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 1);
        Path outRoot = Paths.get(args.length > 4 ? args[4]
                : "analysis/巴西数据分析/新版_purchase时间/单样本确定性泛化测试_10供应商");

        Files.createDirectories(outRoot);

        Path weeklyCsv = outRoot.resolve("巴西五大区23OD_周度宽表.csv");
        aggregateDailyLongToWeeklyWide(dailyCsv, weeklyCsv);

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Config cfg = buildBaseConfig(k1);
        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, cfg);
        double[] dBase = buildBaselineDemand(br);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);

        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
        if (rolling.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        Path perSampleCsv = outRoot.resolve("single_sample_trials.csv");
        Path trialSummaryCsv = outRoot.resolve("single_sample_trial_summary.csv");
        Path globalSummaryTxt = outRoot.resolve("single_sample_vs_saa_summary.txt");

        writePerSampleHeader(perSampleCsv);
        writeTrialSummaryHeader(trialSummaryCsv);

        SAAModel model = new SAAModel();
        List<Double> saaRealizedList = new ArrayList<>();
        List<Double> meanSingleRealizedList = new ArrayList<>();
        List<Double> medianSingleRealizedList = new ArrayList<>();
        List<Double> minSingleRealizedList = new ArrayList<>();

        for (int t = 0; t < rolling.size(); t++) {
            List<Sample> trainRaw = rolling.trainSets.get(t);
            Sample test = rolling.testSamples.get(t);
            int testIdx = rolling.testIndex.get(t);
            double[] dTest = test.demand().clone();

            Solution saaSol = solveOnTrain(model, params, lanes, cfg, trainRaw);
            BatchRunner.RecourseEvaluator.RecourseEval saaRec =
                    BatchRunner.RecourseEvaluator.evaluate(params, saaSol.y, dTest);

            List<Double> realizedList = new ArrayList<>();
            List<Double> expectedList = new ArrayList<>();
            List<Integer> selectedCountList = new ArrayList<>();

            try (BufferedWriter bw = Files.newBufferedWriter(perSampleCsv, java.nio.file.StandardOpenOption.APPEND)) {
                for (int samplePos = 0; samplePos < trainRaw.size(); samplePos++) {
                    Sample s0 = trainRaw.get(samplePos);
                    List<Sample> singleTrain = new ArrayList<>(1);
                    singleTrain.add(copySingleSample(s0));

                    long st = System.nanoTime();
                    Solution sol = solveOnTrain(model, params, lanes, cfg, singleTrain);
                    long ed = System.nanoTime();
                    double solveTimeSec = (ed - st) / 1e9;

                    BatchRunner.RecourseEvaluator.RecourseEval rec =
                            BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);
                    double expected = sol.objValue;
                    double realized = rec.objValue;
                    int selectedCount = countSelected(sol.y);

                    realizedList.add(realized);
                    expectedList.add(expected);
                    selectedCountList.add(selectedCount);

                    bw.write(String.format(Locale.US,
                            "%d,%d,%d,%d,%s,%.10f,%.10f,%.6f,%d,%.10f,%.10f,%.10f,%s,%s%n",
                            t,
                            testIdx,
                            trainRaw.size(),
                            samplePos,
                            String.valueOf(s0.id),
                            expected,
                            realized,
                            solveTimeSec,
                            selectedCount,
                            rec.transportTotalCost,
                            rec.spotTotalCost,
                            rec.penaltyTotalCost,
                            csvQuote(yBinary(sol.y)),
                            csvQuote(selectedCarriers(sol.y))));
                }
            }

            Collections.sort(realizedList);
            Collections.sort(expectedList);
            Collections.sort(selectedCountList);

            double meanSingle = mean(realizedList);
            double medianSingle = percentile(realizedList, 0.5);
            double minSingle = realizedList.get(0);
            double p20Single = percentile(realizedList, 0.2);
            double p80Single = percentile(realizedList, 0.8);
            double meanExpected = mean(expectedList);
            int medianSelected = percentileInt(selectedCountList, 0.5);

            saaRealizedList.add(saaRec.objValue);
            meanSingleRealizedList.add(meanSingle);
            medianSingleRealizedList.add(medianSingle);
            minSingleRealizedList.add(minSingle);

            try (BufferedWriter bw = Files.newBufferedWriter(trialSummaryCsv, java.nio.file.StandardOpenOption.APPEND)) {
                bw.write(String.format(Locale.US,
                        "%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%d,%.10f,%.10f,%.10f,%.10f,%s,%s%n",
                        t,
                        testIdx,
                        trainRaw.size(),
                        saaSol.objValue,
                        saaRec.objValue,
                        meanSingle,
                        medianSingle,
                        minSingle,
                        p20Single,
                        medianSelected,
                        p80Single,
                        meanExpected,
                        meanSingle - saaRec.objValue,
                        medianSingle - saaRec.objValue,
                        csvQuote(yBinary(saaSol.y)),
                        csvQuote(selectedCarriers(saaSol.y))));
            }
        }

        try (BufferedWriter bw = Files.newBufferedWriter(globalSummaryTxt)) {
            bw.write(String.format(Locale.US, "trials=%d%n", rolling.size()));
            bw.write(String.format(Locale.US, "mean_saa_realized=%.10f%n", mean(saaRealizedList)));
            bw.write(String.format(Locale.US, "mean_single_realized=%.10f%n", mean(meanSingleRealizedList)));
            bw.write(String.format(Locale.US, "median_single_realized=%.10f%n", mean(medianSingleRealizedList)));
            bw.write(String.format(Locale.US, "min_single_realized=%.10f%n", mean(minSingleRealizedList)));
            bw.write(String.format(Locale.US, "gap_meanSingle_minus_SAA=%.10f%n",
                    mean(meanSingleRealizedList) - mean(saaRealizedList)));
            bw.write(String.format(Locale.US, "gap_medianSingle_minus_SAA=%.10f%n",
                    mean(medianSingleRealizedList) - mean(saaRealizedList)));
            bw.write(String.format(Locale.US, "gap_minSingle_minus_SAA=%.10f%n",
                    mean(minSingleRealizedList) - mean(saaRealizedList)));
        }

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static Solution solveOnTrain(SAAModel model,
                                         ProcurementParams params,
                                         List<String> lanes,
                                         Config baseCfg,
                                         List<Sample> trainRaw) throws Exception {
        Config cfg = copyBase(baseCfg);
        cfg.solveMode = SolveMode.SAA;
        List<Sample> train = new ArrayList<>(trainRaw.size());
        double eq = 1.0 / Math.max(1, trainRaw.size());
        for (Sample s : trainRaw) {
            train.add(new Sample(s.id, s.period, new CovariateVector(s.theta.values().clone()), eq));
        }
        CovariateVector thetaNow = new CovariateVector(train.get(train.size() - 1).theta.values().clone());
        Data data = new Data(lanes, train, thetaNow, params);
        return model.solve(data, cfg, null);
    }

    private static Sample copySingleSample(Sample s) {
        return new Sample(s.id, s.period, new CovariateVector(s.theta.values().clone()), 1.0);
    }

    private static Config buildBaseConfig(int k1) {
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
        cfg.C_h = 1.0;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        cfg.solveMode = SolveMode.SAA;
        return cfg;
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
        c.lambda = b.lambda;
        c.solveMode = b.solveMode;
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

    private static void writePerSampleHeader(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write("trialId,testIdx,trainSize,samplePos,sampleId,expectedObj,realizedObj,solveTimeSec,selectedCount,oosTransportCost,oosSpotCost,oosPenaltyCost,yBinary,selectedCarriers");
            bw.newLine();
        }
    }

    private static void writeTrialSummaryHeader(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write("trialId,testIdx,trainSize,saaExpectedObj,saaRealizedObj,meanSingleRealized,medianSingleRealized,minSingleRealized,p20SingleRealized,medianSelectedCount,p80SingleRealized,meanSingleExpected,gapMeanSingleMinusSAA,gapMedianSingleMinusSAA,saaYBinary,saaSelectedCarriers");
            bw.newLine();
        }
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

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y) if (v > 0.5) c++;
        return c;
    }

    private static String csvQuote(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static double mean(List<Double> vals) {
        double sum = 0.0;
        for (double v : vals) sum += v;
        return sum / Math.max(1, vals.size());
    }

    private static double percentile(List<Double> sortedVals, double p) {
        if (sortedVals.isEmpty()) return Double.NaN;
        if (sortedVals.size() == 1) return sortedVals.get(0);
        double pos = (sortedVals.size() - 1) * p;
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sortedVals.get(lo);
        double frac = pos - lo;
        return sortedVals.get(lo) * (1 - frac) + sortedVals.get(hi) * frac;
    }

    private static int percentileInt(List<Integer> sortedVals, double p) {
        if (sortedVals.isEmpty()) return 0;
        int idx = (int) Math.floor((sortedVals.size() - 1) * p);
        return sortedVals.get(idx);
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
            for (String lane : lanes) bw.write("," + lane);
            bw.newLine();

            int weekIdx = 0;
            for (int s = 0; s + 6 < allDays.size(); s += 7) {
                bw.write(Integer.toString(weekIdx++));
                for (String lane : lanes) {
                    double sum = 0.0;
                    for (int k = 0; k < 7; k++) {
                        LocalDate d = allDays.get(s + k);
                        sum += byDayLane.getOrDefault(d, Collections.emptyMap()).getOrDefault(lane, 0.0);
                    }
                    bw.write("," + String.format(Locale.US, "%.6f", sum));
                }
                bw.newLine();
            }
        }
    }

    private static double parseDoubleSafe(String s) {
        if (s == null || s.isBlank()) return 0.0;
        return Double.parseDouble(s.trim());
    }
}
