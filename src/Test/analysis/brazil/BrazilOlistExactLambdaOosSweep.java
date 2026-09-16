package Test;

import Basic.CovariateVector;
import Basic.Data;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.RCSAASolverVariant;
import Model.SolveMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sweeps the full lambda grid for exact RCSAA enumeration after fixing each trial's CSAA-best k and C_h.
 * The outputs are the enumeration benchmark used in the fixed-kC, full-lambda comparison workflow.
 */
public class BrazilOlistExactLambdaOosSweep {

    private static final int W = 50;
    private static final int NUM_CARRIERS = 10;
    private static final int[] K_GRID = {1, 2, 3};
    private static final double[] LAMBDA_GRID = {0.01, 0.05, 0.1, 1, 5, 10, 50, 100};
    // Edit these constants directly when launching multiple IDE run configs.
    // Example:
    //   config A -> START_TRIAL=0,  END_TRIAL=16, OUT_ROOT=.../chunk_00_16
    //   config B -> START_TRIAL=17, END_TRIAL=33, OUT_ROOT=.../chunk_17_33
    //   config C -> START_TRIAL=34, END_TRIAL=50, OUT_ROOT=.../chunk_34_50
    private static final int START_TRIAL = 0;
    private static final int END_TRIAL = 16;

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u5165/\u805a\u5408\u9700\u6c42\u8868_\u65e5\u5ea6\u4e0e\u5468\u5ea6/"
                    + "\u6309purchase\u65f6\u95f4_\u4e94\u5927\u533a23OD_\u5468\u5ea6\u5bbd\u8868_10\u4f9b\u5e94\u5546\u5b9e\u9a8c\u8f93\u5165.csv");

    private static final Path SELECTED_PARAMS = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u51fa/04_\u7b2c\u4e00\u7ec4\u4e3b\u5b9e\u9a8c_\u6eda\u52a8CV\u9009\u6700\u4f18\u53c2\u6570\u5e76\u63d0\u53d6\u6700\u7ec8CSAA_RCSAA\u7ed3\u679c/"
                    + "02_\u6700\u7ec8\u7ed3\u679c_4\u65b9\u6cd5/02_\u6bcf\u4e2atrial\u6700\u4f18\u53c2\u6570/"
                    + "\u6bcf\u4e2atrial\u6700\u7ec8\u9009\u4e2d\u53c2\u6570\u8868.csv");

    private static final Path OUT_ROOT = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u51fa/\u7ed3\u679c/"
                    + "09_RCSAA\u56fa\u5b9akC_\u5168\u90e8lambda\u6837\u672c\u5916\u6d4b\u8bd5/"
                    + "chunk_00_16");

    public static void main(String[] args) throws Exception {
        int startTrial = START_TRIAL;
        int endTrial = END_TRIAL;
        Path outRoot = OUT_ROOT;

        Files.createDirectories(outRoot);
        Path rowsCsv = outRoot.resolve("lambda_oos_scan_rows.csv");
        Path trialSummaryCsv = outRoot.resolve("lambda_oos_scan_trial_summary.csv");
        initRowsCsv(rowsCsv);
        initTrialSummaryCsv(trialSummaryCsv);

        List<SelectedParamRow> selections = loadSelections(SELECTED_PARAMS);
        selections.sort(Comparator.comparingInt(r -> r.trialId));

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY_INPUT);
        List<String> lanes = weekly.laneNames;

        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : K_GRID) {
            Config cfg = buildBaseConfig(k, 1.0, 0.01);
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(weekly.periods, lanes, cfg);
            samplesByK.put(k, br.samples);
        }

        double[] dBase = buildBaselineDemand(weekly.periods);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(NUM_CARRIERS, dBase, genCfg, buildBaseConfig(3, 1.0, 0.01));
        Map<Integer, GlobalExactEnumerationCache> exactCacheByK = new HashMap<>();
        for (int k : K_GRID) {
            exactCacheByK.put(k, GlobalExactEnumerationCache.build(params, samplesByK.get(k), k));
        }

        for (SelectedParamRow sel : selections) {
            if (sel.trialId < startTrial || sel.trialId > endTrial) {
                continue;
            }

            System.out.println(String.format(Locale.US,
                    "trial=%d start testPeriod=%d fixedK=%d fixedC=%.6f chosenLambda=%.6f",
                    sel.trialId, sel.testPeriodIdx, sel.bestK, sel.bestCH, sel.bestLambda));

            List<TrialLambdaRow> trialRows = new ArrayList<>();
            for (double lambda : LAMBDA_GRID) {
                System.out.println(String.format(Locale.US, "  lambda=%.6f start", lambda));
                TrialSolveResult result = solveOneTrial(
                        samplesByK.get(sel.bestK), exactCacheByK.get(sel.bestK), params,
                        sel.testPeriodIdx, sel.bestK, sel.bestCH, lambda);
                TrialLambdaRow row = TrialLambdaRow.from(sel, lambda, result);
                trialRows.add(row);
                appendRow(rowsCsv, row.toCsvLine());
                System.out.println(String.format(Locale.US,
                        "  lambda=%.6f done realized=%.10f expected=%.10f sec=%.3f",
                        lambda, result.realizedObj, result.expectedObj, result.solveTimeSec));
            }

            TrialSummary summary = summarizeTrial(sel, trialRows);
            appendRow(trialSummaryCsv, summary.toCsvLine());
            System.out.println(String.format(Locale.US,
                    "trial=%d done chosenLambda=%.6f chosenRealized=%.10f bestOosLambda=%.6f bestOosRealized=%.10f chosenRank=%d",
                    sel.trialId, summary.selectedLambda, summary.selectedRealizedObj,
                    summary.bestOosLambda, summary.bestOosRealizedObj, summary.selectedLambdaRankByRealized));
        }

        System.out.println("done: " + outRoot.toAbsolutePath());
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
        cfg.rcsaaSolverVariant = RCSAASolverVariant.ENUMERATE_EXACT;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        cfg.writeCplexLogToFile = false;
        return cfg;
    }

    private static TrialSolveResult solveOneTrial(List<Sample> samplesForK,
                                                  GlobalExactEnumerationCache exactCache,
                                                  ProcurementParams params,
                                                  int testPeriodIdx,
                                                  int k,
                                                  double c,
                                                  double lambda) throws Exception {
        TrainWindow window = buildTrainingWindow(samplesForK, testPeriodIdx, k);
        List<Sample> trainRaw = window.trainSamples;
        Sample testSample = window.testSample;

        List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());
        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;

        if (!train.isEmpty() && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler();
            scaler.fit(train, thetaDim);
            for (Sample s : train) {
                s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            }
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }

        Config cfg = buildBaseConfig(k, c, lambda);
        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        wc.computeKernelWeights(train, thetaNow, cfg);

        double[] dTest = testSample.demand().clone();
        long st = System.nanoTime();
        GlobalExactEnumerationCache.SolveResult exact = exactCache.solve(train, window.trainStartIdx, cfg.lambda);
        long ed = System.nanoTime();
        BatchRunner.RecourseEvaluator.RecourseEval rec =
                BatchRunner.RecourseEvaluator.evaluate(params, exact.bestY, dTest);

        TrialSolveResult out = new TrialSolveResult();
        out.expectedObj = exact.meanCost;
        out.modelObj = exact.objective;
        out.realizedObj = rec.objValue;
        out.solveTimeSec = (ed - st) / 1e9;
        out.selectedCount = countSelected(exact.bestY);
        out.yBinary = encodeY(exact.bestY);
        out.selectedCarriers = encodeSelectedCarriers(exact.bestY);
        out.transportCost = rec.transportTotalCost;
        out.spotCost = rec.spotTotalCost;
        out.penaltyCost = rec.penaltyTotalCost;
        out.ess = ess(train);
        out.top1W = topKWeightSum(train, 1);
        out.top5Wsum = topKWeightSum(train, 5);
        return out;
    }

    private static List<SelectedParamRow> loadSelections(Path csv) throws Exception {
        List<SelectedParamRow> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = br.readLine();
            if (headerLine == null) {
                return out;
            }
            String[] header = parseCsvLine(headerLine);
            Map<String, Integer> h = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                h.put(normalizeHeaderName(header[i]), i);
            }
            int trialIdCol = requireColumn(h, "trialId");
            int testPeriodCol = requireColumn(h, "testPeriodIdx");
            int csaaKCol = requireColumn(h, "CSAA_best_k");
            int csaaCHCol = requireColumn(h, "CSAA_best_C_h");
            int chosenLambdaCol = requireColumn(h, "RCSAA_best_lambda");
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = parseCsvLine(line);
                out.add(new SelectedParamRow(
                        Integer.parseInt(f[trialIdCol]),
                        Integer.parseInt(f[testPeriodCol]),
                        Integer.parseInt(f[csaaKCol]),
                        Double.parseDouble(f[csaaCHCol]),
                        Double.parseDouble(f[chosenLambdaCol])));
            }
        }
        return out;
    }

    private static int requireColumn(Map<String, Integer> headerMap, String name) {
        Integer idx = headerMap.get(normalizeHeaderName(name));
        if (idx == null) {
            throw new IllegalArgumentException("Missing required column: " + name + ", available=" + headerMap.keySet());
        }
        return idx;
    }

    private static String normalizeHeaderName(String s) {
        if (s == null) return "";
        return s.replace("\ufeff", "").trim();
    }

    private static TrainWindow buildTrainingWindow(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        int start = testSampleIdx - W;
        int end = testSampleIdx;
        if (start < 0 || end > samplesForK.size()) {
            throw new IllegalArgumentException("Invalid training window for testPeriod=" + testPeriodIdx + ", k=" + k);
        }
        return new TrainWindow(start, new ArrayList<>(samplesForK.subList(start, end)), samplesForK.get(testSampleIdx));
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

    private static int countSelected(double[] y) {
        int count = 0;
        for (double v : y) {
            if (v > 0.5) {
                count++;
            }
        }
        return count;
    }

    private static String encodeY(double[] y) {
        StringBuilder sb = new StringBuilder(y.length);
        for (double v : y) {
            sb.append(v > 0.5 ? '1' : '0');
        }
        return sb.toString();
    }

    private static String encodeSelectedCarriers(double[] y) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (y[i] > 0.5) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(i);
            }
        }
        return sb.toString();
    }

    private static double ess(List<Sample> train) {
        double sumW2 = 0.0;
        for (Sample s : train) {
            double w = s.weight;
            if (Double.isFinite(w)) {
                sumW2 += w * w;
            }
        }
        return sumW2 > 0.0 ? 1.0 / sumW2 : Double.NaN;
    }

    private static double topKWeightSum(List<Sample> train, int topK) {
        List<Double> weights = new ArrayList<>();
        for (Sample s : train) {
            if (Double.isFinite(s.weight)) {
                weights.add(s.weight);
            }
        }
        weights.sort(Comparator.reverseOrder());
        double sum = 0.0;
        for (int i = 0; i < Math.min(topK, weights.size()); i++) {
            sum += weights.get(i);
        }
        return sum;
    }

    private static TrialSummary summarizeTrial(SelectedParamRow sel, List<TrialLambdaRow> rows) {
        List<TrialLambdaRow> byRealized = new ArrayList<>(rows);
        byRealized.sort(Comparator.comparingDouble((TrialLambdaRow r) -> r.realizedObj).thenComparingDouble(r -> r.lambda));
        TrialLambdaRow bestOos = byRealized.get(0);

        List<TrialLambdaRow> byExpected = new ArrayList<>(rows);
        byExpected.sort(Comparator.comparingDouble((TrialLambdaRow r) -> r.expectedObj).thenComparingDouble(r -> r.lambda));
        TrialLambdaRow bestExpected = byExpected.get(0);

        TrialLambdaRow chosen = null;
        int rankByRealized = -1;
        for (int i = 0; i < byRealized.size(); i++) {
            TrialLambdaRow row = byRealized.get(i);
            if (Math.abs(row.lambda - sel.bestLambda) <= 1e-9) {
                chosen = row;
                rankByRealized = i + 1;
                break;
            }
        }

        TrialSummary out = new TrialSummary();
        out.trialId = sel.trialId;
        out.testPeriodIdx = sel.testPeriodIdx;
        out.fixedK = sel.bestK;
        out.fixedCH = sel.bestCH;
        out.selectedLambda = sel.bestLambda;
        out.selectedRealizedObj = chosen == null ? Double.NaN : chosen.realizedObj;
        out.selectedExpectedObj = chosen == null ? Double.NaN : chosen.expectedObj;
        out.selectedSolveTimeSec = chosen == null ? Double.NaN : chosen.solveTimeSec;
        out.bestOosLambda = bestOos.lambda;
        out.bestOosRealizedObj = bestOos.realizedObj;
        out.bestExpectedLambda = bestExpected.lambda;
        out.bestExpectedObj = bestExpected.expectedObj;
        out.selectedLambdaRankByRealized = rankByRealized;
        out.isSelectedLambdaBestOos = Math.abs(out.selectedLambda - out.bestOosLambda) <= 1e-9;
        return out;
    }

    private static void initRowsCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("trialId,testPeriodIdx,fixed_k,fixed_C_h,selected_lambda,lambda,expected_obj,model_obj,realized_obj,solve_time_sec,selected_count,y_binary,selected_carriers,oos_transport_cost,oos_spot_cost,oos_penalty_cost,ESS,top1W,top5Wsum");
            bw.newLine();
        }
    }

    private static void initTrialSummaryCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("trialId,testPeriodIdx,fixed_k,fixed_C_h,selected_lambda,selected_realized_obj,selected_expected_obj,selected_solve_time_sec,best_oos_lambda,best_oos_realized_obj,best_expected_lambda,best_expected_obj,selected_lambda_rank_by_realized,is_selected_lambda_best_oos");
            bw.newLine();
        }
    }

    private static void appendRow(Path csv, String line) throws Exception {
        if (csv.getParent() != null) {
            Files.createDirectories(csv.getParent());
        }
        if (!Files.exists(csv)) {
            if (csv.getFileName().toString().contains("trial_summary")) {
                initTrialSummaryCsv(csv);
            } else {
                initRowsCsv(csv);
            }
        }
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            bw.write(line);
            bw.newLine();
        }
    }

    private static String[] parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    private static final class SelectedParamRow {
        final int trialId;
        final int testPeriodIdx;
        final int bestK;
        final double bestCH;
        final double bestLambda;

        SelectedParamRow(int trialId, int testPeriodIdx, int bestK, double bestCH, double bestLambda) {
            this.trialId = trialId;
            this.testPeriodIdx = testPeriodIdx;
            this.bestK = bestK;
            this.bestCH = bestCH;
            this.bestLambda = bestLambda;
        }
    }

    private static final class TrainWindow {
        final int trainStartIdx;
        final List<Sample> trainSamples;
        final Sample testSample;

        TrainWindow(int trainStartIdx, List<Sample> trainSamples, Sample testSample) {
            this.trainStartIdx = trainStartIdx;
            this.trainSamples = trainSamples;
            this.testSample = testSample;
        }
    }

    private static final class TrialSolveResult {
        double expectedObj;
        double modelObj;
        double realizedObj;
        double solveTimeSec;
        int selectedCount;
        String yBinary;
        String selectedCarriers;
        double transportCost;
        double spotCost;
        double penaltyCost;
        double ess;
        double top1W;
        double top5Wsum;
    }

    private static final class TrialLambdaRow {
        int trialId;
        int testPeriodIdx;
        int fixedK;
        double fixedCH;
        double selectedLambda;
        double lambda;
        double expectedObj;
        double modelObj;
        double realizedObj;
        double solveTimeSec;
        int selectedCount;
        String yBinary;
        String selectedCarriers;
        double transportCost;
        double spotCost;
        double penaltyCost;
        double ess;
        double top1W;
        double top5Wsum;

        static TrialLambdaRow from(SelectedParamRow sel, double lambda, TrialSolveResult result) {
            TrialLambdaRow row = new TrialLambdaRow();
            row.trialId = sel.trialId;
            row.testPeriodIdx = sel.testPeriodIdx;
            row.fixedK = sel.bestK;
            row.fixedCH = sel.bestCH;
            row.selectedLambda = sel.bestLambda;
            row.lambda = lambda;
            row.expectedObj = result.expectedObj;
            row.modelObj = result.modelObj;
            row.realizedObj = result.realizedObj;
            row.solveTimeSec = result.solveTimeSec;
            row.selectedCount = result.selectedCount;
            row.yBinary = result.yBinary;
            row.selectedCarriers = result.selectedCarriers;
            row.transportCost = result.transportCost;
            row.spotCost = result.spotCost;
            row.penaltyCost = result.penaltyCost;
            row.ess = result.ess;
            row.top1W = result.top1W;
            row.top5Wsum = result.top5Wsum;
            return row;
        }

        String toCsvLine() {
            return String.format(Locale.US,
                    "%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%s,%s,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f",
                    trialId, testPeriodIdx, fixedK, fixedCH, selectedLambda, lambda,
                    expectedObj, modelObj, realizedObj, solveTimeSec, selectedCount, yBinary, selectedCarriers,
                    transportCost, spotCost, penaltyCost, ess, top1W, top5Wsum);
        }
    }

    private static final class TrialSummary {
        int trialId;
        int testPeriodIdx;
        int fixedK;
        double fixedCH;
        double selectedLambda;
        double selectedRealizedObj;
        double selectedExpectedObj;
        double selectedSolveTimeSec;
        double bestOosLambda;
        double bestOosRealizedObj;
        double bestExpectedLambda;
        double bestExpectedObj;
        int selectedLambdaRankByRealized;
        boolean isSelectedLambdaBestOos;

        String toCsvLine() {
            return String.format(Locale.US,
                    "%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.6f,%.10f,%.10f,%.10f,%.10f,%d,%s",
                    trialId, testPeriodIdx, fixedK, fixedCH, selectedLambda,
                    selectedRealizedObj, selectedExpectedObj, selectedSolveTimeSec,
                    bestOosLambda, bestOosRealizedObj, bestExpectedLambda, bestExpectedObj,
                    selectedLambdaRankByRealized, Boolean.toString(isSelectedLambdaBestOos));
        }
    }

    private static final class GlobalExactEnumerationCache {
        private static final double FORMAL_WEIGHT_FLOOR = 1e-8;

        final List<double[]> feasibleY;
        final double[][] qMatrix;

        private GlobalExactEnumerationCache(List<double[]> feasibleY, double[][] qMatrix) {
            this.feasibleY = feasibleY;
            this.qMatrix = qMatrix;
        }

        static GlobalExactEnumerationCache build(ProcurementParams params, List<Sample> allSamplesForK, int k) throws Exception {
            List<double[]> feasibleY = enumerateFeasibleSelections(params.I, params.alpha, params.beta);
            double[][] qMatrix = new double[feasibleY.size()][allSamplesForK.size()];
            long t0 = System.nanoTime();
            System.out.println(String.format(Locale.US,
                    "global-exact-cache build start k=%d feasibleY=%d samples=%d",
                    k, feasibleY.size(), allSamplesForK.size()));
            for (int yi = 0; yi < feasibleY.size(); yi++) {
                double[] y = feasibleY.get(yi);
                for (int s = 0; s < allSamplesForK.size(); s++) {
                    qMatrix[yi][s] = BatchRunner.RecourseEvaluator.evaluate(
                            params, y, allSamplesForK.get(s).demand().clone()).objValue;
                }
                if ((yi + 1) % 100 == 0 || yi + 1 == feasibleY.size()) {
                    System.out.println(String.format(Locale.US,
                            "global-exact-cache build progress k=%d %d/%d elapsedSec=%.3f",
                            k, yi + 1, feasibleY.size(), secondsBetween(t0, System.nanoTime())));
                }
            }
            System.out.println(String.format(Locale.US,
                    "global-exact-cache build done k=%d totalSec=%.3f",
                    k, secondsBetween(t0, System.nanoTime())));
            return new GlobalExactEnumerationCache(feasibleY, qMatrix);
        }

        SolveResult solve(List<Sample> weightedTrain, int trainStartIdx, double lambda) {
            double[] pi = normalizeFormalWeights(weightedTrain);
            double bestObj = Double.POSITIVE_INFINITY;
            double bestMean = Double.NaN;
            int bestIdx = -1;
            for (int yi = 0; yi < feasibleY.size(); yi++) {
                double[] qAll = qMatrix[yi];
                double mean = 0.0;
                for (int s = 0; s < pi.length; s++) {
                    mean += pi[s] * qAll[trainStartIdx + s];
                }
                double variance = 0.0;
                for (int s = 0; s < pi.length; s++) {
                    double diff = qAll[trainStartIdx + s] - mean;
                    variance += pi[s] * diff * diff;
                }
                double obj = mean + lambda * Math.sqrt(Math.max(0.0, variance));
                if (obj + 1e-4 < bestObj) {
                    bestObj = obj;
                    bestMean = mean;
                    bestIdx = yi;
                }
            }
            if (bestIdx < 0) {
                throw new IllegalStateException("Exact enumeration found no feasible first-stage solution.");
            }
            return new SolveResult(bestObj, bestMean, feasibleY.get(bestIdx).clone());
        }

        private static double[] normalizeFormalWeights(List<Sample> weightedTrain) {
            double[] pi = new double[weightedTrain.size()];
            double sum = 0.0;
            for (int i = 0; i < weightedTrain.size(); i++) {
                double w = weightedTrain.get(i).weight;
                if (!Double.isFinite(w) || w < 0.0) {
                    throw new IllegalStateException("Invalid weight at sample " + i + ": " + w);
                }
                pi[i] = Math.max(w, FORMAL_WEIGHT_FLOOR);
                sum += pi[i];
            }
            if (!(sum > 0.0)) {
                throw new IllegalStateException("Normalized weights sum to zero.");
            }
            for (int i = 0; i < pi.length; i++) {
                pi[i] /= sum;
            }
            return pi;
        }

        private static List<double[]> enumerateFeasibleSelections(int iSize, int alpha, int beta) {
            if (iSize > 30) {
                throw new IllegalArgumentException("Exact enumeration only supports I <= 30, current I=" + iSize);
            }
            List<double[]> out = new ArrayList<>();
            int totalMasks = 1 << iSize;
            for (int mask = 0; mask < totalMasks; mask++) {
                int selected = Integer.bitCount(mask);
                if (selected < alpha || selected > beta) {
                    continue;
                }
                double[] y = new double[iSize];
                for (int i = 0; i < iSize; i++) {
                    y[i] = ((mask >>> i) & 1) == 1 ? 1.0 : 0.0;
                }
                out.add(y);
            }
            return out;
        }

        private static final class SolveResult {
            final double objective;
            final double meanCost;
            final double[] bestY;

            SolveResult(double objective, double meanCost, double[] bestY) {
                this.objective = objective;
                this.meanCost = meanCost;
                this.bestY = bestY;
            }
        }
    }

    private static double secondsBetween(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1e9;
    }
}
