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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Re-runs the 40/10 DRO rolling-CV experiment.
 * For each trial it fixes the selected k and C_h, sweeps lambda, writes validation details,
 * 10 validation-sample rows, final selected parameters, out-of-sample summaries, and frequencies.
 */
public class BrazilOlistDroCv4010Rerun {
    private static final int W = 50;
    private static final int SUBTRAIN_SIZE = 40;
    private static final int SUBVAL_SIZE = 10;
    private static final int NUM_CARRIERS = 10;
    private static final int[] K_GRID = {1, 2, 3};
    private static final double[] LAMBDA_GRID = {0.01, 0.05, 0.1, 1, 5, 10, 50, 100};
    private static final int DEFAULT_START_TRIAL = 0;
    private static final int DEFAULT_END_TRIAL = 50;
    private static final int DEFAULT_THREADS = 4;

    private static final Path WEEKLY_INPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
    private static final Path SELECTED_PARAMS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/02_最终结果_4方法/02_每个trial最优参数/每个trial最终选中参数表.csv");
    private static final Path OUT_ROOT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/结果/10_35_15_ExactVsDRO_详细验证/汇总结果");

    public static void main(String[] args) throws Exception {
        RunConfig run = RunConfig.fromArgs(args);
        Path outRoot = run.outRoot.resolve(run.outSubdir);
        Files.createDirectories(outRoot);
        Path bestCsv = outRoot.resolve("40_10_DRO_重算最优参数_逐trial.csv");
        Path lambdaCsv = outRoot.resolve("40_10_DRO_逐trial逐lambda明细.csv");
        Path valCsv = outRoot.resolve("40_10_DRO_逐trial逐lambda_10个验证样本明细.csv");
        Path summaryCsv = outRoot.resolve("40_10_DRO_重算最优参数_样本外汇总.csv");
        Path freqCsv = outRoot.resolve("40_10_DRO_重算最优参数_频率汇总.csv");

        initCsv(bestCsv, "trialId,testPeriodIdx,fixed_k,fixed_C_h,selected_lambda_40_10,selected_10avg_oos,direct_oos_expected,direct_oos_model,direct_oos_realized,direct_oos_transport,direct_oos_spot,direct_oos_penalty,direct_oos_solve_time,direct_oos_selected_count,direct_oos_ess,y_binary,selected_carriers");
        initCsv(lambdaCsv, "trialId,testPeriodIdx,fixed_k,fixed_C_h,lambda,cv_mean_realized,cv_mean_expected,cv_mean_model,cv_mean_transport,cv_mean_spot,cv_mean_penalty,cv_mean_solve_time,cv_mean_selected_count,cv_mean_ess,direct_oos_expected,direct_oos_model,direct_oos_realized,direct_oos_transport,direct_oos_spot,direct_oos_penalty,direct_oos_solve_time,direct_oos_selected_count,direct_oos_ess,y_binary,selected_carriers");
        initCsv(valCsv, "trialId,testPeriodIdx,fixed_k,fixed_C_h,lambda,val_order,val_periodIdx,val_expected,val_model,val_realized,val_transport,val_spot,val_penalty,val_solve_time,val_selected_count,val_ess,y_binary,selected_carriers");
        initCsv(summaryCsv, "final_method_name,param_strategy,trial_count,mean_expected_obj,std_expected_obj,mean_realized_obj,std_realized_obj,mean_transport_cost,mean_spot_cost,mean_penalty_cost,mean_solve_time_sec,mean_selected_count,mean_ess");
        initCsv(freqCsv, "k,C_h,lambda,freq,pct");

        List<SelectedParamRow> selections = loadSelections(run.selectedParams);
        selections.sort(Comparator.comparingInt(r -> r.trialId));

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(run.weeklyInput);
        List<String> lanes = weekly.laneNames;
        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : K_GRID) {
            Config cfg = buildBaseConfig(k, 1.0, 0.01);
            cfg.threads = run.threads;
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(weekly.periods, lanes, cfg);
            samplesByK.put(k, br.samples);
        }

        double[] dBase = buildBaselineDemand(weekly.periods);
        Config genCfg = buildBaseConfig(3, 1.0, 0.01);
        genCfg.threads = run.threads;
        ProcurementParams params = InstanceGenerator.generate(NUM_CARRIERS, dBase, new InstanceGenerator.GenConfig(), genCfg);

        List<TrialResult> finalRows = new ArrayList<>();
        Map<String, Integer> freq = new HashMap<>();
        for (SelectedParamRow sel : selections) {
            if (sel.trialId < run.startTrial || sel.trialId > run.endTrial) {
                continue;
            }

            System.out.println(String.format(Locale.US, "trial=%d start testPeriod=%d fixedK=%d fixedC=%.6f", sel.trialId, sel.testPeriodIdx, sel.bestK, sel.bestCH));
            List<Sample> fullTrain = buildCvWindow(samplesByK.get(sel.bestK), sel.testPeriodIdx, sel.bestK);
            List<Sample> subTrain = new ArrayList<>(fullTrain.subList(0, SUBTRAIN_SIZE));
            List<Sample> subVal = new ArrayList<>(fullTrain.subList(SUBTRAIN_SIZE, W));

            double bestLambda = Double.NaN;
            double bestValMean = Double.POSITIVE_INFINITY;
            TrialResult bestRow = null;

            for (double lambda : LAMBDA_GRID) {
                System.out.println(String.format(Locale.US, "  lambda=%.6f start", lambda));
                Config cfg = buildBaseConfig(sel.bestK, sel.bestCH, lambda);
                cfg.threads = run.threads;
                ValidationAccumulator acc = new ValidationAccumulator();
                for (int i = 0; i < subVal.size(); i++) {
                    Sample val = subVal.get(i);
                    TrialSolveResult valSolve = solveSingleDro(lanes, params, subTrain, val, cfg);
                    acc.add(valSolve);
                    appendCsv(valCsv, toValCsvLine(sel, lambda, i + 1, val.period.tIndex, valSolve));
                }
                ValidationSummary cvSummary = acc.finish();
                TrialSolveResult directSolve = solveActualOos(lanes, params, samplesByK.get(sel.bestK), sel.testPeriodIdx, sel.bestK, sel.bestCH, lambda, run.threads);
                appendCsv(lambdaCsv, toLambdaCsvLine(sel, lambda, cvSummary, directSolve));

                if (cvSummary.meanRealized + 1e-6 < bestValMean || (Math.abs(cvSummary.meanRealized - bestValMean) <= 1e-6 && lambda < bestLambda)) {
                    bestValMean = cvSummary.meanRealized;
                    bestLambda = lambda;
                    bestRow = TrialResult.of(sel, lambda, bestValMean, directSolve);
                }
                System.out.println(String.format(Locale.US, "  lambda=%.6f done cvMean=%.10f directRealized=%.10f", lambda, cvSummary.meanRealized, directSolve.realizedObj));
            }

            if (bestRow == null) {
                throw new IllegalStateException("No best row found for trial=" + sel.trialId);
            }
            finalRows.add(bestRow);
            appendCsv(bestCsv, bestRow.toCsvLine());
            String key = String.format(Locale.US, "%d|%.10f|%.10f", sel.bestK, sel.bestCH, bestLambda);
            freq.put(key, freq.getOrDefault(key, 0) + 1);
            System.out.println(String.format(Locale.US, "trial=%d done bestLambda=%.6f cvMean=%.10f realized=%.10f", sel.trialId, bestLambda, bestValMean, bestRow.realizedObj));
        }

        appendCsv(summaryCsv, buildSummaryLine(finalRows));
        writeFreqCsv(freqCsv, freq, finalRows.size());
        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static final class RunConfig {
        final int startTrial;
        final int endTrial;
        final int threads;
        final String outSubdir;
        final Path weeklyInput;
        final Path selectedParams;
        final Path outRoot;

        private RunConfig(int startTrial, int endTrial, int threads, String outSubdir, Path weeklyInput, Path selectedParams, Path outRoot) {
            this.startTrial = startTrial;
            this.endTrial = endTrial;
            this.threads = threads;
            this.outSubdir = outSubdir;
            this.weeklyInput = weeklyInput;
            this.selectedParams = selectedParams;
            this.outRoot = outRoot;
        }

        static RunConfig fromArgs(String[] args) {
            int startTrial = args.length >= 1 ? Integer.parseInt(args[0]) : DEFAULT_START_TRIAL;
            int endTrial = args.length >= 2 ? Integer.parseInt(args[1]) : DEFAULT_END_TRIAL;
            String outSubdir = args.length >= 3 ? args[2] : "40_10_DRO_chunk_" + String.format(Locale.US, "%02d_%02d", startTrial, endTrial);
            Path weeklyInput = args.length >= 4 ? Paths.get(args[3]) : WEEKLY_INPUT;
            Path selectedParams = args.length >= 5 ? Paths.get(args[4]) : SELECTED_PARAMS;
            Path outRoot = args.length >= 6 ? Paths.get(args[5]) : OUT_ROOT;
            int threads = args.length >= 7 ? Integer.parseInt(args[6]) : DEFAULT_THREADS;
            return new RunConfig(startTrial, endTrial, threads, outSubdir, weeklyInput, selectedParams, outRoot);
        }
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
        cfg.threads = DEFAULT_THREADS;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        cfg.writeCplexLogToFile = false;
        return cfg;
    }

    private static TrialSolveResult solveSingleDro(List<String> lanes,
                                                   ProcurementParams params,
                                                   List<Sample> trainRaw,
                                                   Sample testSample,
                                                   Config cfg) throws Exception {
        List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());
        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;
        if (cfg.standardizeTheta && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler();
            scaler.fit(train, thetaDim);
            for (Sample s : train) {
                s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            }
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }
        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        new WeightCalculator(kernel, new EuclideanDistance()).computeKernelWeights(train, thetaNow, cfg);

        double[] dTest = testSample.demand().clone();
        long st = System.nanoTime();
        Solution sol = new DROModel().solve(new Data(lanes, train, thetaNow, params), cfg);
        long ed = System.nanoTime();
        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);

        TrialSolveResult out = new TrialSolveResult();
        out.expectedObj = weightedMean(empiricalRecourseCosts(params, train, sol.y), train);
        out.modelObj = sol.objValue;
        out.realizedObj = rec.objValue;
        out.solveTimeSec = (ed - st) / 1e9;
        out.selectedCount = countSelected(sol.y);
        out.yBinary = encodeY(sol.y);
        out.selectedCarriers = encodeSelected(sol.y);
        out.transportCost = rec.transportTotalCost;
        out.spotCost = rec.spotTotalCost;
        out.penaltyCost = rec.penaltyTotalCost;
        out.ess = ess(train);
        return out;
    }

    private static TrialSolveResult solveActualOos(List<String> lanes,
                                                   ProcurementParams params,
                                                   List<Sample> samplesForK,
                                                   int testPeriodIdx,
                                                   int k,
                                                   double c,
                                                   double lambda,
                                                   int threads) throws Exception {
        TrainWindow window = buildActualWindow(samplesForK, testPeriodIdx, k);
        Config cfg = buildBaseConfig(k, c, lambda);
        cfg.threads = threads;
        return solveSingleDro(lanes, params, window.trainSamples, window.testSample, cfg);
    }

    private static List<SelectedParamRow> loadSelections(Path csv) throws Exception {
        List<SelectedParamRow> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String[] header = parseCsvLine(br.readLine());
            Map<String, Integer> h = new HashMap<>();
            for (int i = 0; i < header.length; i++) {
                h.put(normalize(header[i]), i);
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = parseCsvLine(line);
                out.add(new SelectedParamRow(
                        Integer.parseInt(f[h.get("trialId")]),
                        Integer.parseInt(f[h.get("testPeriodIdx")]),
                        Integer.parseInt(f[h.get("CSAA_best_k")]),
                        Double.parseDouble(f[h.get("CSAA_best_C_h")]),
                        Double.parseDouble(f[h.get("RCSAA_best_lambda")])));
            }
        }
        return out;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replace("\ufeff", "").trim();
    }

    private static List<Sample> buildCvWindow(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        return new ArrayList<>(samplesForK.subList(testSampleIdx - W, testSampleIdx));
    }

    private static TrainWindow buildActualWindow(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        int trainStart = testSampleIdx - W;
        return new TrainWindow(new ArrayList<>(samplesForK.subList(trainStart, testSampleIdx)), samplesForK.get(testSampleIdx));
    }

    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : periods) {
            for (int j = 0; j < jSize; j++) {
                sum[j] += p.demandSum[j];
            }
        }
        for (int j = 0; j < jSize; j++) {
            sum[j] /= Math.max(1, periods.size());
        }
        return sum;
    }

    private static double[] empiricalRecourseCosts(ProcurementParams params, List<Sample> train, double[] y) throws Exception {
        double[] vals = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            vals[i] = BatchRunner.RecourseEvaluator.evaluate(params, y, train.get(i).demand().clone()).objValue;
        }
        return vals;
    }

    private static double weightedMean(double[] vals, List<Sample> train) {
        double sumW = 0.0;
        double sum = 0.0;
        for (int i = 0; i < train.size(); i++) {
            double w = train.get(i).weight;
            if (!Double.isFinite(w)) {
                continue;
            }
            sumW += w;
            sum += w * vals[i];
        }
        return sumW > 0.0 ? sum / sumW : Double.NaN;
    }

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y) {
            if (v >= 0.5) {
                c++;
            }
        }
        return c;
    }

    private static String encodeY(double[] y) {
        StringBuilder sb = new StringBuilder();
        for (double v : y) {
            sb.append(v >= 0.5 ? '1' : '0');
        }
        return sb.toString();
    }

    private static String encodeSelected(double[] y) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (y[i] >= 0.5) {
                if (sb.length() > 0) {
                    sb.append('|');
                }
                sb.append(i);
            }
        }
        return sb.toString();
    }

    private static double ess(List<Sample> train) {
        double s = 0.0;
        for (Sample x : train) {
            s += x.weight * x.weight;
        }
        return s <= 0.0 ? Double.NaN : 1.0 / s;
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

    private static void initCsv(Path csv, String header) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            bw.write(header);
            bw.newLine();
        }
    }

    private static void appendCsv(Path csv, String line) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            bw.write(line);
            bw.newLine();
        }
    }

    private static String buildSummaryLine(List<TrialResult> rows) {
        double[] ex = rows.stream().mapToDouble(r -> r.expectedObj).toArray();
        double[] re = rows.stream().mapToDouble(r -> r.realizedObj).toArray();
        double mex = Arrays.stream(ex).average().orElse(Double.NaN);
        double mre = Arrays.stream(re).average().orElse(Double.NaN);
        double sex = std(ex, mex);
        double sre = std(re, mre);
        return String.format(Locale.US, "DRO,40/10 CV-selected lambda under CSAA-best (k,C),%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f",
                rows.size(), mex, sex, mre, sre,
                rows.stream().mapToDouble(r -> r.transportCost).average().orElse(Double.NaN),
                rows.stream().mapToDouble(r -> r.spotCost).average().orElse(Double.NaN),
                rows.stream().mapToDouble(r -> r.penaltyCost).average().orElse(Double.NaN),
                rows.stream().mapToDouble(r -> r.solveTimeSec).average().orElse(Double.NaN),
                rows.stream().mapToDouble(r -> r.selectedCount).average().orElse(Double.NaN),
                rows.stream().mapToDouble(r -> r.ess).average().orElse(Double.NaN));
    }

    private static double std(double[] a, double m) {
        double s = 0.0;
        for (double v : a) {
            double d = v - m;
            s += d * d;
        }
        return Math.sqrt(s / a.length);
    }

    private static void writeFreqCsv(Path csv, Map<String, Integer> freq, int total) throws Exception {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(freq.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Integer> e : entries) {
            String[] p = e.getKey().split("\\|");
            appendCsv(csv, String.format(Locale.US, "%s,%s,%s,%d,%.4f", p[0], p[1], p[2], e.getValue(), 100.0 * e.getValue() / total));
        }
    }

    private static String toLambdaCsvLine(SelectedParamRow sel, double lambda, ValidationSummary cvSummary, TrialSolveResult directSolve) {
        return String.format(Locale.US,
                "%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%.10f,%s,%s",
                sel.trialId, sel.testPeriodIdx, sel.bestK, sel.bestCH, lambda,
                cvSummary.meanRealized, cvSummary.meanExpected, cvSummary.meanModel,
                cvSummary.meanTransport, cvSummary.meanSpot, cvSummary.meanPenalty,
                cvSummary.meanSolveTimeSec, cvSummary.meanSelectedCount, cvSummary.meanEss,
                directSolve.expectedObj, directSolve.modelObj, directSolve.realizedObj,
                directSolve.transportCost, directSolve.spotCost, directSolve.penaltyCost,
                directSolve.solveTimeSec, directSolve.selectedCount, directSolve.ess,
                directSolve.yBinary, directSolve.selectedCarriers);
    }

    private static String toValCsvLine(SelectedParamRow sel, double lambda, int valOrder, int valPeriodIdx, TrialSolveResult valSolve) {
        return String.format(Locale.US,
                "%d,%d,%d,%.10f,%.10f,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%.10f,%s,%s",
                sel.trialId, sel.testPeriodIdx, sel.bestK, sel.bestCH, lambda,
                valOrder, valPeriodIdx, valSolve.expectedObj, valSolve.modelObj, valSolve.realizedObj,
                valSolve.transportCost, valSolve.spotCost, valSolve.penaltyCost,
                valSolve.solveTimeSec, valSolve.selectedCount, valSolve.ess,
                valSolve.yBinary, valSolve.selectedCarriers);
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
        double transportCost;
        double spotCost;
        double penaltyCost;
        double ess;
        int selectedCount;
        String yBinary;
        String selectedCarriers;
    }

    private static final class TrialResult {
        int trialId;
        int testPeriodIdx;
        int fixedK;
        int selectedCount;
        double fixedCH;
        double selectedLambda;
        double selectedValMean;
        double expectedObj;
        double modelObj;
        double realizedObj;
        double transportCost;
        double spotCost;
        double penaltyCost;
        double solveTimeSec;
        double ess;
        String yBinary;
        String selectedCarriers;

        static TrialResult of(SelectedParamRow sel, double selectedLambda, double selectedValMean, TrialSolveResult solve) {
            TrialResult out = new TrialResult();
            out.trialId = sel.trialId;
            out.testPeriodIdx = sel.testPeriodIdx;
            out.fixedK = sel.bestK;
            out.fixedCH = sel.bestCH;
            out.selectedLambda = selectedLambda;
            out.selectedValMean = selectedValMean;
            out.expectedObj = solve.expectedObj;
            out.modelObj = solve.modelObj;
            out.realizedObj = solve.realizedObj;
            out.transportCost = solve.transportCost;
            out.spotCost = solve.spotCost;
            out.penaltyCost = solve.penaltyCost;
            out.solveTimeSec = solve.solveTimeSec;
            out.selectedCount = solve.selectedCount;
            out.ess = solve.ess;
            out.yBinary = solve.yBinary;
            out.selectedCarriers = solve.selectedCarriers;
            return out;
        }

        String toCsvLine() {
            return String.format(Locale.US, "%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%.10f,%s,%s",
                    trialId, testPeriodIdx, fixedK, fixedCH, selectedLambda, selectedValMean,
                    expectedObj, modelObj, realizedObj, transportCost, spotCost, penaltyCost,
                    solveTimeSec, selectedCount, ess, yBinary, selectedCarriers);
        }
    }

    private static final class ValidationAccumulator {
        int count;
        double sumExpected;
        double sumModel;
        double sumRealized;
        double sumTransport;
        double sumSpot;
        double sumPenalty;
        double sumSolveTimeSec;
        double sumSelectedCount;
        double sumEss;

        void add(TrialSolveResult r) {
            count++;
            sumExpected += r.expectedObj;
            sumModel += r.modelObj;
            sumRealized += r.realizedObj;
            sumTransport += r.transportCost;
            sumSpot += r.spotCost;
            sumPenalty += r.penaltyCost;
            sumSolveTimeSec += r.solveTimeSec;
            sumSelectedCount += r.selectedCount;
            sumEss += r.ess;
        }

        ValidationSummary finish() {
            ValidationSummary out = new ValidationSummary();
            if (count == 0) {
                out.meanExpected = Double.POSITIVE_INFINITY;
                out.meanModel = Double.POSITIVE_INFINITY;
                out.meanRealized = Double.POSITIVE_INFINITY;
                return out;
            }
            double d = count;
            out.meanExpected = sumExpected / d;
            out.meanModel = sumModel / d;
            out.meanRealized = sumRealized / d;
            out.meanTransport = sumTransport / d;
            out.meanSpot = sumSpot / d;
            out.meanPenalty = sumPenalty / d;
            out.meanSolveTimeSec = sumSolveTimeSec / d;
            out.meanSelectedCount = sumSelectedCount / d;
            out.meanEss = sumEss / d;
            return out;
        }
    }

    private static final class ValidationSummary {
        double meanExpected;
        double meanModel;
        double meanRealized;
        double meanTransport;
        double meanSpot;
        double meanPenalty;
        double meanSolveTimeSec;
        double meanSelectedCount;
        double meanEss;
    }
}
