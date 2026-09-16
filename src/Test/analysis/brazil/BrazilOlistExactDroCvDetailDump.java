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
 * Dumps the full 35/15 validation detail table for exact RCSAA and DRO.
 * It fixes each trial's CSAA-best k and C_h, sweeps lambda, and exports per-validation-sample rows
 * without making final parameter selections.
 */
public class BrazilOlistExactDroCvDetailDump {

    private static final int W = 50;
    private static final int SUBTRAIN_SIZE = 35;
    private static final int SUBVAL_SIZE = 15;
    private static final int NUM_CARRIERS = 10;
    private static final double[] LAMBDA_GRID = {0.01, 0.05, 0.1, 1, 5, 10, 50, 100};

    // Edit directly for parallel IDE runs.
    private static final int START_TRIAL = 0;
    private static final int END_TRIAL = 16;

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u5165/\u805a\u5408\u9700\u6c42\u8868_\u65e5\u5ea6\u4e0e\u5468\u5ea6/\u6309purchase\u65f6\u95f4_\u4e94\u5927\u533a23OD_\u5468\u5ea6\u5bbd\u8868_10\u4f9b\u5e94\u5546\u5b9e\u9a8c\u8f93\u5165.csv");

    private static final Path TRIAL_LIST_CSV = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u51fa/04_\u7b2c\u4e00\u7ec4\u4e3b\u5b9e\u9a8c_\u6eda\u52a8CV\u9009\u6700\u4f18\u53c2\u6570\u5e76\u63d0\u53d6\u6700\u7ec8CSAA_RCSAA\u7ed3\u679c/02_\u6700\u7ec8\u7ed3\u679c_4\u65b9\u6cd5/02_\u6bcf\u4e2atrial\u6700\u4f18\u53c2\u6570/\u6bcf\u4e2atrial\u6700\u7ec8\u9009\u4e2d\u53c2\u6570\u8868.csv");

    private static final Path OUT_ROOT = Paths.get(
            "analysis/\u5df4\u897f\u6570\u636e\u5206\u6790/\u65b0\u7248_purchase\u65f6\u95f4/\u8f93\u51fa/\u7ed3\u679c/"
                    + "10_35_15_ExactVsDRO_\u8be6\u7ec6\u9a8c\u8bc1/"
                    + "chunk_00_16");

    public static void main(String[] args) throws Exception {
        int startTrial = START_TRIAL;
        int endTrial = END_TRIAL;
        Path outRoot = OUT_ROOT;

        Files.createDirectories(outRoot);
        Path detailCsv = outRoot.resolve("cv_param_validation_detail.csv");
        Path summaryCsv = outRoot.resolve("cv_param_validation_summary.csv");
        initDetailCsv(detailCsv);
        initSummaryCsv(summaryCsv);

        List<TrialRef> trialRefs = loadTrialRefs(TRIAL_LIST_CSV);
        trialRefs.sort(Comparator.comparingInt(t -> t.trialId));

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY_INPUT);
        List<String> lanes = weekly.laneNames;
        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : new int[] {1, 2, 3}) {
            Config cfg = buildBaseConfig(k, 1.0, 0.01);
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(weekly.periods, lanes, cfg);
            samplesByK.put(k, br.samples);
        }

        double[] dBase = buildBaselineDemand(weekly.periods);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(NUM_CARRIERS, dBase, genCfg, buildBaseConfig(3, 1.0, 0.01));

        for (TrialRef trial : trialRefs) {
            if (trial.trialId < startTrial || trial.trialId > endTrial) continue;

            int k = trial.fixedCsaaK;
            double c = trial.fixedCsaaCH;
            System.out.println(String.format(Locale.US,
                    "trial=%d start testPeriod=%d fixedCSAA_k=%d fixedCSAA_C=%.6f",
                    trial.trialId, trial.testPeriodIdx, k, c));

            List<Sample> fullTrain = buildTrainingWindow(samplesByK.get(k), trial.testPeriodIdx, k);
            List<Sample> subTrain = new ArrayList<>(fullTrain.subList(0, SUBTRAIN_SIZE));
            List<Sample> subVal = new ArrayList<>(fullTrain.subList(SUBTRAIN_SIZE, W));
            WindowInfo window = WindowInfo.of(fullTrain, subTrain, subVal);
            ExactEnumerationCache exactCache = ExactEnumerationCache.build(params, subTrain);

            for (double lambda : LAMBDA_GRID) {
                ValidationAccumulator exactAcc = new ValidationAccumulator();
                ValidationAccumulator droAcc = new ValidationAccumulator();

                Config exactCfg = buildExactRcsaaConfig(k, c, lambda);
                Config droCfg = buildDroRcsaaConfig(k, c, lambda);

                System.out.println(String.format(Locale.US,
                        "trial=%d k=%d C=%.6f lambda=%.6f start", trial.trialId, k, c, lambda));

                for (int vi = 0; vi < subVal.size(); vi++) {
                    Sample val = subVal.get(vi);

                    TrialSolveResult exactOne = solveSingleRcsaaExact(lanes, params, subTrain, val, exactCfg, exactCache);
                    exactAcc.add(exactOne);
                    appendLine(detailCsv, DetailRow.of(trial, window, "RCSAA_EXT", vi, val, k, c, lambda, exactOne).toCsvLine());

                    TrialSolveResult droOne = solveSingleRcsaaDro(lanes, params, subTrain, val, droCfg);
                    droAcc.add(droOne);
                    appendLine(detailCsv, DetailRow.of(trial, window, "DRO", vi, val, k, c, lambda, droOne).toCsvLine());

                    if (vi == 0 || vi == 4 || vi == 9 || vi == subVal.size() - 1) {
                        System.out.println(String.format(Locale.US,
                                "  trial=%d lambda=%.6f progress=%d/%d",
                                trial.trialId, lambda, vi + 1, subVal.size()));
                    }
                }

                appendLine(summaryCsv, SummaryRow.of(trial, window, "RCSAA_EXT", k, c, lambda, exactAcc.finish()).toCsvLine());
                appendLine(summaryCsv, SummaryRow.of(trial, window, "DRO", k, c, lambda, droAcc.finish()).toCsvLine());

                System.out.println(String.format(Locale.US,
                        "trial=%d k=%d C=%.6f lambda=%.6f done", trial.trialId, k, c, lambda));
            }
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
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        cfg.writeCplexLogToFile = false;
        return cfg;
    }

    private static Config buildExactRcsaaConfig(int k, double c, double lambda) {
        Config cfg = buildBaseConfig(k, c, lambda);
        cfg.solveMode = SolveMode.RCSAA;
        cfg.rcsaaSolverVariant = RCSAASolverVariant.ENUMERATE_EXACT;
        return cfg;
    }

    private static Config buildDroRcsaaConfig(int k, double c, double lambda) {
        Config cfg = buildBaseConfig(k, c, lambda);
        cfg.solveMode = SolveMode.RCSAA;
        return cfg;
    }

    private static List<TrialRef> loadTrialRefs(Path csv) throws Exception {
        List<TrialRef> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> h = headerMap(parseCsvLine(br.readLine()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parseCsvLine(line);
                out.add(new TrialRef(
                        parseInt(f.get(require(h, "trialId"))),
                        parseInt(f.get(require(h, "testPeriodIdx"))),
                        parseInt(f.get(require(h, "CSAA_best_k"))),
                        parseDouble(f.get(require(h, "CSAA_best_C_h")))));
            }
        }
        return out;
    }

    private static List<Sample> buildTrainingWindow(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        int start = testSampleIdx - W;
        int end = testSampleIdx;
        if (start < 0 || end > samplesForK.size()) {
            throw new IllegalArgumentException("Invalid training window for testPeriod=" + testPeriodIdx + ", k=" + k);
        }
        return new ArrayList<>(samplesForK.subList(start, end));
    }

    private static TrialSolveResult solveSingleRcsaaDro(List<String> lanes,
                                                        ProcurementParams params,
                                                        List<Sample> trainRaw,
                                                        Sample testSample,
                                                        Config cfg) throws Exception {
        List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());
        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;

        if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler();
            scaler.fit(train, thetaDim);
            for (Sample s : train) s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }

        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        wc.computeKernelWeights(train, thetaNow, cfg);

        double[] dTest = testSample.demand().clone();
        BatchRunner.TrialDiag diag = BatchRunner.TrialDiag.compute(train, thetaNow, dTest, new EuclideanDistance());

        DROModel model = new DROModel();
        long st = System.nanoTime();
        Solution sol = model.solve(new Data(lanes, train, thetaNow, params), cfg);
        long ed = System.nanoTime();

        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);
        TrialSolveResult out = buildTrialResult(train, testSample, diag, sol, rec, (ed - st) / 1e9);
        out.expectedObj = weightedMean(empiricalRecourseCosts(params, train, sol.y), train);
        out.methodName = "DRO";
        return out;
    }

    private static TrialSolveResult solveSingleRcsaaExact(List<String> lanes,
                                                          ProcurementParams params,
                                                          List<Sample> trainRaw,
                                                          Sample testSample,
                                                          Config cfg,
                                                          ExactEnumerationCache cache) throws Exception {
        List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());
        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;

        if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler();
            scaler.fit(train, thetaDim);
            for (Sample s : train) s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }

        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        wc.computeKernelWeights(train, thetaNow, cfg);

        double[] dTest = testSample.demand().clone();
        BatchRunner.TrialDiag diag = BatchRunner.TrialDiag.compute(train, thetaNow, dTest, new EuclideanDistance());

        long st = System.nanoTime();
        ExactEnumerationCache.SolveResult exact = cache.solve(train, cfg.lambda);
        long ed = System.nanoTime();

        Solution sol = new Solution(exact.objective, exact.bestY, (ed - st) / 1e9);
        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);

        TrialSolveResult out = buildTrialResult(train, testSample, diag, sol, rec, (ed - st) / 1e9);
        out.expectedObj = exact.meanCost;
        out.methodName = "RCSAA_EXT";
        return out;
    }

    private static TrialSolveResult buildTrialResult(List<Sample> train,
                                                     Sample testSample,
                                                     BatchRunner.TrialDiag diag,
                                                     Solution sol,
                                                     BatchRunner.RecourseEvaluator.RecourseEval rec,
                                                     double solveTimeSec) {
        TrialSolveResult out = new TrialSolveResult();
        out.testPeriodIdx = testSample.period.tIndex;
        out.trainSize = train.size();
        out.expectedObj = sol.objValue;
        out.modelObj = sol.objValue;
        out.realizedObj = rec.objValue;
        out.solveTimeSec = solveTimeSec;
        out.selectedCount = countSelected(sol.y);
        out.yBinary = yBinary(sol.y);
        out.selectedCarriers = selectedCarriers(sol.y);
        out.sampleWeights = buildSampleWeightsString(train);
        out.rec = rec;
        out.diag = diag;
        return out;
    }

    private static double[] empiricalRecourseCosts(ProcurementParams params, List<Sample> train, double[] y) throws Exception {
        double[] vals = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            vals[i] = BatchRunner.RecourseEvaluator.evaluate(params, y, train.get(i).demand().clone()).objValue;
        }
        return vals;
    }

    private static double weightedMean(double[] vals, List<Sample> train) {
        double sumW = 0.0, sum = 0.0;
        for (int i = 0; i < train.size(); i++) {
            double w = train.get(i).weight;
            if (!Double.isFinite(w)) continue;
            sumW += w;
            sum += w * vals[i];
        }
        return sumW > 0.0 ? sum / sumW : Double.NaN;
    }

    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : periods) for (int j = 0; j < jSize; j++) sum[j] += p.demandSum[j];
        for (int j = 0; j < jSize; j++) sum[j] /= Math.max(1, periods.size());
        return sum;
    }

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y) if (v > 0.5) c++;
        return c;
    }

    private static String yBinary(double[] y) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < y.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(y[i] > 0.5 ? 1 : 0);
        }
        sb.append("]");
        return sb.toString();
    }

    private static String selectedCarriers(double[] y) {
        StringBuilder sb = new StringBuilder("{");
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

    private static String buildSampleWeightsString(List<Sample> train) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < train.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "%.10f", train.get(i).weight));
        }
        sb.append("]");
        return sb.toString();
    }

    private static void initDetailCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write(String.join(",",
                    "trialId", "testPeriodIdx", "method_name", "val_index", "val_period",
                    "fullTrainStartPeriod", "fullTrainEndPeriod", "subTrainStartPeriod", "subTrainEndPeriod", "subValStartPeriod", "subValEndPeriod",
                    "k", "C_h", "lambda",
                    "expected_obj", "model_obj", "realized_obj", "solve_time_sec", "selected_count",
                    "oos_transport_cost", "oos_spot_cost", "oos_penalty_cost",
                    "sumW", "sumW2", "ESS", "top1W", "top5Wsum", "maxW_over_meanW",
                    "thetaDist_mean", "thetaDist_median", "thetaDist_min", "thetaDist_max",
                    "demandDist_mean", "demandDist_median", "demandDist_min", "demandDist_max",
                    "corrW_thetaDist", "corrW_demandDist", "corrTheta_demandDist",
                    "yBinary", "selectedCarriers", "sampleWeights"));
            bw.newLine();
        }
    }

    private static void initSummaryCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write(String.join(",",
                    "trialId", "testPeriodIdx", "method_name",
                    "fullTrainStartPeriod", "fullTrainEndPeriod", "subTrainStartPeriod", "subTrainEndPeriod", "subValStartPeriod", "subValEndPeriod",
                    "k", "C_h", "lambda", "validCount",
                    "meanExpectedObj", "meanRealizedObj", "meanSolveTimeSec", "meanSelectedCount",
                    "meanOosTransportCost", "meanOosSpotCost", "meanOosPenaltyCost",
                    "meanSumW", "meanSumW2", "meanESS", "meanTop1W", "meanTop5Wsum", "meanMaxWOverMean",
                    "meanThetaDistMean", "meanThetaDistMedian", "meanThetaDistMin", "meanThetaDistMax",
                    "meanDemandDistMean", "meanDemandDistMedian", "meanDemandDistMin", "meanDemandDistMax",
                    "meanCorrWThetaDist", "meanCorrWDemandDist", "meanCorrThetaDemandDist"));
            bw.newLine();
        }
    }

    private static void appendLine(Path csv, String line) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            bw.write(line);
            bw.newLine();
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else inQuotes = !inQuotes;
            } else if (ch == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    private static Map<String, Integer> headerMap(List<String> cols) {
        Map<String, Integer> out = new HashMap<>();
        for (int i = 0; i < cols.size(); i++) out.put(cols.get(i).replace("\uFEFF", "").trim(), i);
        return out;
    }

    private static int require(Map<String, Integer> header, String name) {
        Integer idx = header.get(name);
        if (idx == null) throw new IllegalArgumentException("Missing column: " + name);
        return idx;
    }

    private static int parseInt(String s) { return Integer.parseInt(s.trim()); }
    private static double parseDouble(String s) { return Double.parseDouble(s.trim()); }

    private static final class TrialRef {
        final int trialId;
        final int testPeriodIdx;
        final int fixedCsaaK;
        final double fixedCsaaCH;
        TrialRef(int trialId, int testPeriodIdx, int fixedCsaaK, double fixedCsaaCH) {
            this.trialId = trialId;
            this.testPeriodIdx = testPeriodIdx;
            this.fixedCsaaK = fixedCsaaK;
            this.fixedCsaaCH = fixedCsaaCH;
        }
    }

    private static final class WindowInfo {
        int fullTrainStartPeriod, fullTrainEndPeriod, subTrainStartPeriod, subTrainEndPeriod, subValStartPeriod, subValEndPeriod;
        static WindowInfo of(List<Sample> fullTrain, List<Sample> subTrain, List<Sample> subVal) {
            WindowInfo out = new WindowInfo();
            out.fullTrainStartPeriod = fullTrain.get(0).period.tIndex;
            out.fullTrainEndPeriod = fullTrain.get(fullTrain.size() - 1).period.tIndex;
            out.subTrainStartPeriod = subTrain.get(0).period.tIndex;
            out.subTrainEndPeriod = subTrain.get(subTrain.size() - 1).period.tIndex;
            out.subValStartPeriod = subVal.get(0).period.tIndex;
            out.subValEndPeriod = subVal.get(subVal.size() - 1).period.tIndex;
            return out;
        }
    }

    private static final class TrialSolveResult {
        String methodName;
        int testPeriodIdx, trainSize, selectedCount;
        double expectedObj, modelObj, realizedObj, solveTimeSec;
        String yBinary, selectedCarriers, sampleWeights;
        BatchRunner.RecourseEvaluator.RecourseEval rec;
        BatchRunner.TrialDiag diag;
    }

    private static final class DetailRow {
        TrialRef trial;
        WindowInfo window;
        String methodName;
        int valIndex, valPeriod, k;
        double cH, lambda;
        TrialSolveResult solve;

        static DetailRow of(TrialRef trial, WindowInfo window, String methodName, int valIndex, Sample val, int k, double cH, double lambda, TrialSolveResult solve) {
            DetailRow out = new DetailRow();
            out.trial = trial;
            out.window = window;
            out.methodName = methodName;
            out.valIndex = valIndex;
            out.valPeriod = val.period.tIndex;
            out.k = k;
            out.cH = cH;
            out.lambda = lambda;
            out.solve = solve;
            return out;
        }

        String toCsvLine() {
            return String.format(Locale.US,
                    "%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "\"%s\",\"%s\",\"%s\"",
                    trial.trialId, trial.testPeriodIdx, methodName, valIndex, valPeriod,
                    window.fullTrainStartPeriod, window.fullTrainEndPeriod, window.subTrainStartPeriod, window.subTrainEndPeriod, window.subValStartPeriod, window.subValEndPeriod,
                    k, cH, lambda,
                    solve.expectedObj, solve.modelObj, solve.realizedObj, solve.solveTimeSec, solve.selectedCount,
                    solve.rec.transportTotalCost, solve.rec.spotTotalCost, solve.rec.penaltyTotalCost,
                    solve.diag.sumW, solve.diag.sumW2, solve.diag.ess, solve.diag.top1W, solve.diag.top5Wsum, solve.diag.maxOverMean,
                    solve.diag.thetaMean, solve.diag.thetaMedian, solve.diag.thetaMin, solve.diag.thetaMax,
                    solve.diag.demMean, solve.diag.demMedian, solve.diag.demMin, solve.diag.demMax,
                    solve.diag.corrWTheta, solve.diag.corrWDemand, solve.diag.corrThetaDemand,
                    solve.yBinary.replace("\"", "\"\""),
                    solve.selectedCarriers.replace("\"", "\"\""),
                    solve.sampleWeights.replace("\"", "\"\""));
        }
    }

    private static final class SummaryRow {
        TrialRef trial;
        WindowInfo window;
        String methodName;
        int k;
        double cH, lambda;
        ValidationSummary summary;

        static SummaryRow of(TrialRef trial, WindowInfo window, String methodName, int k, double cH, double lambda, ValidationSummary summary) {
            SummaryRow out = new SummaryRow();
            out.trial = trial;
            out.window = window;
            out.methodName = methodName;
            out.k = k;
            out.cH = cH;
            out.lambda = lambda;
            out.summary = summary;
            return out;
        }

        String toCsvLine() {
            ValidationSummary s = summary;
            return String.format(Locale.US,
                    "%d,%d,%s,%d,%d,%d,%d,%d,%d,%d,%.10f,%.10f,%d," +
                            "%.10f,%.10f,%.6f,%.10f," +
                            "%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f",
                    trial.trialId, trial.testPeriodIdx, methodName,
                    window.fullTrainStartPeriod, window.fullTrainEndPeriod, window.subTrainStartPeriod, window.subTrainEndPeriod, window.subValStartPeriod, window.subValEndPeriod,
                    k, cH, lambda, s.validCount,
                    s.meanExpected, s.meanRealized, s.meanSolveTimeSec, s.meanSelectedCount,
                    s.meanTransport, s.meanSpot, s.meanPenalty,
                    s.meanSumW, s.meanSumW2, s.meanEss, s.meanTop1W, s.meanTop5Wsum, s.meanMaxOverMean,
                    s.meanThetaMean, s.meanThetaMedian, s.meanThetaMin, s.meanThetaMax,
                    s.meanDemandMean, s.meanDemandMedian, s.meanDemandMin, s.meanDemandMax,
                    s.meanCorrWTheta, s.meanCorrWDemand, s.meanCorrThetaDemand);
        }
    }

    private static final class ValidationSummary {
        int validCount;
        double meanExpected, meanRealized, meanSolveTimeSec, meanSelectedCount;
        double meanTransport, meanSpot, meanPenalty;
        double meanSumW, meanSumW2, meanEss, meanTop1W, meanTop5Wsum, meanMaxOverMean;
        double meanThetaMean, meanThetaMedian, meanThetaMin, meanThetaMax;
        double meanDemandMean, meanDemandMedian, meanDemandMin, meanDemandMax;
        double meanCorrWTheta, meanCorrWDemand, meanCorrThetaDemand;
    }

    private static final class ValidationAccumulator {
        int validCount;
        double sumExpected,sumRealized,sumSolveTimeSec,sumSelectedCount,sumTransport,sumSpot,sumPenalty,sumSumW,sumSumW2,sumEss,sumTop1W,sumTop5Wsum,sumMaxOverMean,sumThetaMean,sumThetaMedian,sumThetaMin,sumThetaMax,sumDemandMean,sumDemandMedian,sumDemandMin,sumDemandMax,sumCorrWTheta,sumCorrWDemand,sumCorrThetaDemand;

        void add(TrialSolveResult one){
            if(one==null||!Double.isFinite(one.realizedObj)) return;
            validCount++;
            sumExpected+=one.expectedObj; sumRealized+=one.realizedObj; sumSolveTimeSec+=one.solveTimeSec; sumSelectedCount+=one.selectedCount;
            sumTransport+=one.rec.transportTotalCost; sumSpot+=one.rec.spotTotalCost; sumPenalty+=one.rec.penaltyTotalCost;
            sumSumW+=one.diag.sumW; sumSumW2+=one.diag.sumW2; sumEss+=one.diag.ess; sumTop1W+=one.diag.top1W; sumTop5Wsum+=one.diag.top5Wsum; sumMaxOverMean+=one.diag.maxOverMean;
            sumThetaMean+=one.diag.thetaMean; sumThetaMedian+=one.diag.thetaMedian; sumThetaMin+=one.diag.thetaMin; sumThetaMax+=one.diag.thetaMax;
            sumDemandMean+=one.diag.demMean; sumDemandMedian+=one.diag.demMedian; sumDemandMin+=one.diag.demMin; sumDemandMax+=one.diag.demMax;
            sumCorrWTheta+=one.diag.corrWTheta; sumCorrWDemand+=one.diag.corrWDemand; sumCorrThetaDemand+=one.diag.corrThetaDemand;
        }

        ValidationSummary finish(){
            ValidationSummary out=new ValidationSummary();
            out.validCount=validCount;
            if(validCount==0){
                out.meanExpected=Double.POSITIVE_INFINITY;
                out.meanRealized=Double.POSITIVE_INFINITY;
                return out;
            }
            double d=validCount;
            out.meanExpected=sumExpected/d; out.meanRealized=sumRealized/d; out.meanSolveTimeSec=sumSolveTimeSec/d; out.meanSelectedCount=sumSelectedCount/d;
            out.meanTransport=sumTransport/d; out.meanSpot=sumSpot/d; out.meanPenalty=sumPenalty/d;
            out.meanSumW=sumSumW/d; out.meanSumW2=sumSumW2/d; out.meanEss=sumEss/d; out.meanTop1W=sumTop1W/d; out.meanTop5Wsum=sumTop5Wsum/d; out.meanMaxOverMean=sumMaxOverMean/d;
            out.meanThetaMean=sumThetaMean/d; out.meanThetaMedian=sumThetaMedian/d; out.meanThetaMin=sumThetaMin/d; out.meanThetaMax=sumThetaMax/d;
            out.meanDemandMean=sumDemandMean/d; out.meanDemandMedian=sumDemandMedian/d; out.meanDemandMin=sumDemandMin/d; out.meanDemandMax=sumDemandMax/d;
            out.meanCorrWTheta=sumCorrWTheta/d; out.meanCorrWDemand=sumCorrWDemand/d; out.meanCorrThetaDemand=sumCorrThetaDemand/d;
            return out;
        }
    }

    private static final class ExactEnumerationCache {
        private static final double FORMAL_WEIGHT_FLOOR = 1e-8;
        final List<double[]> feasibleY;
        final double[][] qMatrix;

        private ExactEnumerationCache(List<double[]> feasibleY, double[][] qMatrix) {
            this.feasibleY = feasibleY;
            this.qMatrix = qMatrix;
        }

        static ExactEnumerationCache build(ProcurementParams params, List<Sample> train) throws Exception {
            List<double[]> feasibleY = enumerateFeasibleSelections(params.I, params.alpha, params.beta);
            double[][] qMatrix = new double[feasibleY.size()][train.size()];
            for (int yi = 0; yi < feasibleY.size(); yi++) {
                double[] y = feasibleY.get(yi);
                for (int s = 0; s < train.size(); s++) {
                    qMatrix[yi][s] = BatchRunner.RecourseEvaluator.evaluate(params, y, train.get(s).demand().clone()).objValue;
                }
            }
            return new ExactEnumerationCache(feasibleY, qMatrix);
        }

        SolveResult solve(List<Sample> weightedTrain, double lambda) {
            double[] pi = normalizeFormalWeights(weightedTrain);
            double bestObj = Double.POSITIVE_INFINITY;
            double bestMean = Double.NaN;
            int bestIdx = -1;
            for (int yi = 0; yi < feasibleY.size(); yi++) {
                double[] q = qMatrix[yi];
                double mean = 0.0;
                for (int s = 0; s < q.length; s++) mean += pi[s] * q[s];
                double variance = 0.0;
                for (int s = 0; s < q.length; s++) {
                    double diff = q[s] - mean;
                    variance += pi[s] * diff * diff;
                }
                double obj = mean + lambda * Math.sqrt(Math.max(0.0, variance));
                if (obj + 1e-4 < bestObj) {
                    bestObj = obj;
                    bestMean = mean;
                    bestIdx = yi;
                }
            }
            if (bestIdx < 0) throw new IllegalStateException("No feasible exact solution");
            return new SolveResult(bestObj, bestMean, feasibleY.get(bestIdx).clone());
        }

        private static double[] normalizeFormalWeights(List<Sample> weightedTrain) {
            double[] pi = new double[weightedTrain.size()];
            double sum = 0.0;
            for (int i = 0; i < weightedTrain.size(); i++) {
                double w = weightedTrain.get(i).weight;
                if (!Double.isFinite(w) || w < 0.0) throw new IllegalStateException("Invalid weight " + w);
                pi[i] = Math.max(w, FORMAL_WEIGHT_FLOOR);
                sum += pi[i];
            }
            for (int i = 0; i < pi.length; i++) pi[i] /= sum;
            return pi;
        }

        private static List<double[]> enumerateFeasibleSelections(int iSize, int alpha, int beta) {
            if (iSize > 30) throw new IllegalArgumentException("Exact enumeration only supports I <= 30, current I=" + iSize);
            List<double[]> out = new ArrayList<>();
            int totalMasks = 1 << iSize;
            for (int mask = 0; mask < totalMasks; mask++) {
                int selected = Integer.bitCount(mask);
                if (selected < alpha || selected > beta) continue;
                double[] y = new double[iSize];
                for (int i = 0; i < iSize; i++) y[i] = ((mask >>> i) & 1) == 1 ? 1.0 : 0.0;
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
}
