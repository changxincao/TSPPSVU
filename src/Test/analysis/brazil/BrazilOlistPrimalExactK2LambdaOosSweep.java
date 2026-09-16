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
import Model.DROModel;
import Model.RCSAASolverVariant;
import Model.Solution;
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
 * Sweeps the full lambda grid with LBBD_PRIMAL_SEARCH(radius=2) after fixing each trial's CSAA-best k and C_h.
 * It provides the primal-search counterpart to the DRO and exact-enumeration full-lambda scans.
 */
public class BrazilOlistPrimalExactK2LambdaOosSweep {

    private static final int W = 50;
    private static final int NUM_CARRIERS = 10;
    private static final int[] K_GRID = {1, 2, 3};
    private static final int SEARCH_RADIUS = 2;
    private static final double[] LAMBDA_GRID = {0.01, 0.05, 0.1, 1, 5, 10, 50, 100};

    // Edit directly for parallel IDE runs.
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
                    + "13_RCSAA_LBBD_PRIMAL_SEARCH_r2_\u5168\u90e8lambda\u6837\u672c\u5916\u6d4b\u8bd5/"
                    + "chunk_00_16");

    public static void main(String[] args) throws Exception {
        int startTrial = START_TRIAL;
        int endTrial = END_TRIAL;
        Path outRoot = OUT_ROOT;

        Files.createDirectories(outRoot);
        Path rowsCsv = outRoot.resolve("primal_exact_k2_lambda_oos_rows.csv");
        Path trialSummaryCsv = outRoot.resolve("primal_exact_k2_lambda_oos_trial_summary.csv");
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

        for (SelectedParamRow sel : selections) {
            if (sel.trialId < startTrial || sel.trialId > endTrial) {
                continue;
            }

            System.out.println(String.format(Locale.US,
                    "trial=%d start testPeriod=%d fixedK=%d fixedC=%.6f",
                    sel.trialId, sel.testPeriodIdx, sel.bestK, sel.bestCH));

            List<TrialLambdaRow> trialRows = new ArrayList<>();
            for (double lambda : LAMBDA_GRID) {
                System.out.println(String.format(Locale.US, "  lambda=%.6f start", lambda));
                TrialSolveResult result = solveOneTrial(
                        lanes, samplesByK.get(sel.bestK), params, sel.testPeriodIdx, sel.bestK, sel.bestCH, lambda);
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
                    "trial=%d done bestOosLambda=%.6f bestOosRealized=%.10f",
                    sel.trialId, summary.bestOosLambda, summary.bestOosRealizedObj));
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
        cfg.rcsaaSolverVariant = RCSAASolverVariant.LBBD_PRIMAL_SEARCH;
        cfg.rcsaaSearchNeighborhoodRadius = SEARCH_RADIUS;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        cfg.writeCplexLogToFile = false;
        return cfg;
    }

    private static TrialSolveResult solveOneTrial(List<String> lanes,
                                                  List<Sample> samplesForK,
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
        Solution sol = new DROModel().solve(new Data(lanes, train, thetaNow, params), cfg);
        long ed = System.nanoTime();
        BatchRunner.RecourseEvaluator.RecourseEval rec =
                BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);

        TrialSolveResult out = new TrialSolveResult();
        out.expectedObj = weightedMean(empiricalRecourseCosts(params, train, sol.y), train);
        out.modelObj = sol.objValue;
        out.realizedObj = rec.objValue;
        out.solveTimeSec = (ed - st) / 1e9;
        out.selectedCount = countSelected(sol.y);
        out.yBinary = encodeY(sol.y);
        out.selectedCarriers = encodeSelectedCarriers(sol.y);
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
                        Double.NaN));
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
        return new TrainWindow(new ArrayList<>(samplesForK.subList(start, end)), samplesForK.get(testSampleIdx));
    }

    private static double[] empiricalRecourseCosts(ProcurementParams params, List<Sample> train, double[] y) throws Exception {
        double[] q = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            q[i] = BatchRunner.RecourseEvaluator.evaluate(params, y, train.get(i).demand().clone()).objValue;
        }
        return q;
    }

    private static double weightedMean(double[] vals, List<Sample> weightedSamples) {
        double sum = 0.0;
        double sumW = 0.0;
        for (int i = 0; i < vals.length; i++) {
            double w = weightedSamples.get(i).weight;
            sum += w * vals[i];
            sumW += w;
        }
        if (!(sumW > 0.0)) {
            return Double.NaN;
        }
        return sum / sumW;
    }

    private static double ess(List<Sample> weightedSamples) {
        double sumW = 0.0;
        double sumW2 = 0.0;
        for (Sample s : weightedSamples) {
            double w = s.weight;
            sumW += w;
            sumW2 += w * w;
        }
        if (!(sumW2 > 0.0)) {
            return Double.NaN;
        }
        return sumW * sumW / sumW2;
    }

    private static double topKWeightSum(List<Sample> weightedSamples, int k) {
        List<Double> ws = new ArrayList<>();
        for (Sample s : weightedSamples) {
            ws.add(s.weight);
        }
        ws.sort(Comparator.reverseOrder());
        double sum = 0.0;
        for (int i = 0; i < Math.min(k, ws.size()); i++) {
            sum += ws.get(i);
        }
        return sum;
    }

    private static int countSelected(double[] y) {
        int count = 0;
        for (double v : y) {
            if (v > 0.5) count++;
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

    private static TrialSummary summarizeTrial(SelectedParamRow sel, List<TrialLambdaRow> rows) {
        TrialSummary s = new TrialSummary();
        s.trialId = sel.trialId;
        s.testPeriodIdx = sel.testPeriodIdx;
        s.fixedK = sel.bestK;
        s.fixedCH = sel.bestCH;

        List<TrialLambdaRow> byRealized = new ArrayList<>(rows);
        byRealized.sort(Comparator.comparingDouble(r -> r.realizedObj));
        s.bestOosLambda = byRealized.get(0).lambda;
        s.bestOosRealizedObj = byRealized.get(0).realizedObj;

        List<TrialLambdaRow> byExpected = new ArrayList<>(rows);
        byExpected.sort(Comparator.comparingDouble(r -> r.expectedObj));
        s.bestExpectedLambda = byExpected.get(0).lambda;
        s.bestExpectedObj = byExpected.get(0).expectedObj;
        return s;
    }

    private static void initRowsCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write("trialId,testPeriodIdx,fixed_k,fixed_C_h,solver_variant,lambda,"
                    + "expected_obj,model_obj,realized_obj,solve_time_sec,selected_count,y_binary,selected_carriers,"
                    + "oos_transport_cost,oos_spot_cost,oos_penalty_cost,ESS,top1W,top5Wsum");
            bw.newLine();
        }
    }

    private static void initTrialSummaryCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write("trialId,testPeriodIdx,fixed_k,fixed_C_h,solver_variant,"
                    + "best_oos_lambda,best_oos_realized_obj,best_expected_lambda,best_expected_obj");
            bw.newLine();
        }
    }

    private static void appendRow(Path csv, String line) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
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
        final List<Sample> trainSamples;
        final Sample testSample;

        TrainWindow(List<Sample> trainSamples, Sample testSample) {
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
                    "%d,%d,%d,%.10f,%s,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%s,%s,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f",
                    trialId, testPeriodIdx, fixedK, fixedCH, RCSAASolverVariant.LBBD_PRIMAL_SEARCH.name(), lambda,
                    expectedObj, modelObj, realizedObj, solveTimeSec, selectedCount, yBinary, selectedCarriers,
                    transportCost, spotCost, penaltyCost, ess, top1W, top5Wsum);
        }
    }

    private static final class TrialSummary {
        int trialId;
        int testPeriodIdx;
        int fixedK;
        double fixedCH;
        double bestOosLambda;
        double bestOosRealizedObj;
        double bestExpectedLambda;
        double bestExpectedObj;

        String toCsvLine() {
            return String.format(Locale.US,
                    "%d,%d,%d,%.10f,%s,%.10f,%.10f,%.10f,%.10f",
                    trialId, testPeriodIdx, fixedK, fixedCH, RCSAASolverVariant.LBBD_PRIMAL_SEARCH.name(),
                    bestOosLambda, bestOosRealizedObj, bestExpectedLambda, bestExpectedObj);
        }
    }
}
