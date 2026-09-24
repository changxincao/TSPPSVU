package Test;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.OutputManager;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.DROModel;
import Model.RCSAASolverVariant;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class BrazilOlistAdaptiveCVSolveComparison {

    private static final int[] K_GRID = {1, 2, 3};
    private static final double[] C_GRID = {0.1, 0.5, 1, 3, 5, 10, 30, 50, 100};
    private static final double[] LAMBDA_GRID = {0.01, 0.05, 0.1, 1, 5, 10, 50, 100};

    private static final int SUBTRAIN_SIZE = 35;
    private static final int SUBVAL_SIZE = 15;
    private static final int K_MAX = 3;
    private static final int DEFAULT_MAX_PARALLEL_TRIALS = 3;

    public static final class Stage2Best {
        public final double bestLambda;
        public final double bestStage2MeanRealized;

        public Stage2Best(double bestLambda, double bestStage2MeanRealized) {
            this.bestLambda = bestLambda;
            this.bestStage2MeanRealized = bestStage2MeanRealized;
        }
    }

    public static void main(String[] args) throws Exception {
        Path dailyCsv = Paths.get(args.length > 0 ? args[0] : "analysis/巴西数据分析/旧版/五大区合并后日度OD需求表_千克.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 10);
        int W = (args.length > 2 ? Integer.parseInt(args[2]) : 50);
        String outRootArg = (args.length > 3 ? args[3] : null);
        int startTrial = (args.length > 4 ? Integer.parseInt(args[4]) : 0);
        int maxTrials = (args.length > 5 ? Integer.parseInt(args[5]) : Integer.MAX_VALUE);
        int maxParallelTrials = (args.length > 6 ? Integer.parseInt(args[6]) : DEFAULT_MAX_PARALLEL_TRIALS);
        if (args.length == 0) {
            dailyCsv = Paths.get("analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/按purchase时间_按大区OD聚合_清洗后_日度需求表_千克.csv");
        }

        if (W != SUBTRAIN_SIZE + SUBVAL_SIZE) {
            throw new IllegalArgumentException("This runner assumes W=35+15=50. Current W=" + W);
        }

        Path outRoot = (outRootArg == null || outRootArg.isBlank())
                ? Paths.get("analysis/巴西数据分析/新版_purchase时间/按purchase时间_raw滚动CV实验_10供应商")
                : Paths.get(outRootArg);
        if (outRootArg == null || outRootArg.isBlank()) {
            outRoot = Paths.get("analysis/巴西数据分析/新版_purchase时间/输出/04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/01_原始滚动CV选参输出");
        }
        Files.createDirectories(outRoot);

        Path weeklyCsv = outRoot.resolve("巴西五大区23OD_周度宽表.csv");
        weeklyCsv = outRoot.resolve("巴西五大区23OD_周度宽表.csv");
        aggregateDailyLongToWeeklyWide(dailyCsv, weeklyCsv);

        WeeklyWideLoader.Result w = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = w.laneNames;

        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : K_GRID) {
            Config cfg = buildBaseConfig(k, 1.0);
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(w.periods, lanes, cfg);
            samplesByK.put(k, br.samples);
        }

        double[] dBase = buildBaselineDemand(w.periods);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, buildBaseConfig(K_MAX, 1.0));
        params = applyPenaltyRule(params);

        ExperimentBatches alignedRollingAll = ExperimentBuilder.buildRolling(samplesByK.get(K_MAX), W);
        if (alignedRollingAll.size() == 0) {
            throw new IllegalStateException("No aligned rolling trials. Check W/k-grid and data length.");
        }
        int totalAlignedTrials = alignedRollingAll.size();
        if (startTrial < 0 || startTrial >= totalAlignedTrials) {
            throw new IllegalArgumentException("startTrial out of range: " + startTrial + ", totalAlignedTrials=" + totalAlignedTrials);
        }
        int trialCount = Math.min(maxTrials, totalAlignedTrials - startTrial);
        if (trialCount <= 0) {
            throw new IllegalArgumentException("No trials selected. startTrial=" + startTrial + ", maxTrials=" + maxTrials
                    + ", totalAlignedTrials=" + totalAlignedTrials);
        }
        ExperimentBatches alignedRolling = sliceBatches(alignedRollingAll, startTrial, trialCount);

        System.out.println("dailyCsv: " + dailyCsv.toAbsolutePath());
        System.out.println("weeklyCsv: " + weeklyCsv.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("lanes=" + lanes.size() + ", periods=" + w.periods.size()
                + ", alignedTrials=" + alignedRolling.size()
                + ", totalAlignedTrials=" + totalAlignedTrials
                + ", startTrial=" + startTrial
                + ", trialCount=" + trialCount
                + ", maxParallelTrials=" + maxParallelTrials);
        System.out.println("CV grid: k=" + Arrays.toString(K_GRID)
                + ", C_h=" + Arrays.toString(C_GRID)
                + ", lambda=" + Arrays.toString(LAMBDA_GRID));
        System.out.println("MQC penalty rule: "
                + System.getProperty("trb.reviewer.penalty", "max").trim().toLowerCase(Locale.ROOT));

        if (Boolean.parseBoolean(System.getProperty("trb.reviewer.includeBaselines", "false"))) {
            runAlignedBaselines(outRoot, lanes, params, alignedRolling, genCfg,
                    new GlobalSummaryCollector(outRoot), new GlobalTrialCollector(outRoot));
        }
        runAdaptiveMethods(outRoot, lanes, params, alignedRolling, samplesByK, startTrial, maxParallelTrials);

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static void runAlignedBaselines(Path outRoot,
                                            List<String> lanes,
                                            ProcurementParams params,
                                            ExperimentBatches alignedRolling,
                                            InstanceGenerator.GenConfig genCfg,
                                            GlobalSummaryCollector summaryCollector,
                                            GlobalTrialCollector trialCollector) throws Exception {
        String baseTag = String.format(Locale.US, "巴西Olist23OD_CVAligned_W%d_kmax=%d", SUBTRAIN_SIZE + SUBVAL_SIZE, K_MAX);
        Config base = buildBaseConfig(K_MAX, 1.0);

        Config saaCfg = copyBase(base);
        saaCfg.solveMode = SolveMode.SAA;
        SAACSAAPlainBatchRunner.run(baseTag + "_SAA", lanes, params, saaCfg, alignedRolling,
                outRoot.resolve("SAA"), summaryCollector, trialCollector, genCfg);

        Config meanCfg = copyBase(base);
        meanCfg.solveMode = SolveMode.MeanDeterministic;
        DeterministicBatchRunner.run(baseTag + "_MeanDeterministic", lanes, params, meanCfg, alignedRolling,
                outRoot.resolve("MeanDeterministic"), summaryCollector, trialCollector, genCfg);

        Config completeCfg = copyBase(base);
        completeCfg.solveMode = SolveMode.CompleteDeterministic;
        DeterministicBatchRunner.run(baseTag + "_CompleteDeterministic", lanes, params, completeCfg, alignedRolling,
                outRoot.resolve("CompleteDeterministic"), summaryCollector, trialCollector, genCfg);
    }

    private static void runAdaptiveMethods(Path outRoot,
                                           List<String> lanes,
                                           ProcurementParams params,
                                           ExperimentBatches alignedRolling,
                                           Map<Integer, List<Sample>> samplesByK,
                                           int startTrial,
                                           int maxParallelTrials) throws Exception {
        Path selectionCsv = outRoot.resolve("cv_selected_params.csv");
        Path stage1Csv = outRoot.resolve("cv_stage1_k_c_candidates.csv");
        Path stage2Csv = outRoot.resolve("cv_stage2_lambda_candidates.csv");
        Path selectedActualCsv = outRoot.resolve("cv_selected_actual_trials.csv");
        initSelectionCsv(selectionCsv);
        initStage1DetailCsv(stage1Csv);
        initStage2DetailCsv(stage2Csv);
        initSelectedActualCsv(selectedActualCsv);

        int totalTrials = alignedRolling.size();
        int parallelism = Math.max(1, Math.min(maxParallelTrials, totalTrials));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CompletionService<TrialSelectionResult> completion = new ExecutorCompletionService<>(pool);

        for (int outerTrial = 0; outerTrial < totalTrials; outerTrial++) {
            final int trialId = startTrial + outerTrial;
            final int testPeriodIdx = alignedRolling.testSamples.get(outerTrial).period.tIndex;
            Callable<TrialSelectionResult> task =
                    () -> selectByInnerCv(samplesByK, testPeriodIdx, lanes, params, trialId);
            completion.submit(task);
        }

        int done = 0;
        try {
            while (done < totalTrials) {
                Future<TrialSelectionResult> f = completion.take();
                TrialSelectionResult r;
                try {
                    r = f.get();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    throw new RuntimeException(cause);
                }

                appendStage1DetailRows(stage1Csv, r.stage1Rows);
                appendStage2DetailRows(stage2Csv, r.stage2Rows);
                appendSelectionCsv(selectionCsv, r.outerTrial, r.testPeriodIdx, r.selection);
                appendSelectedActualRows(selectedActualCsv, r.selectedActualRows);
                done++;
                System.out.println("adaptive selection done=" + done + "/" + totalTrials
                        + " trial=" + r.outerTrial
                        + " testPeriod=" + r.testPeriodIdx
                        + " bestK=" + r.selection.bestK
                        + " bestC=" + fmt(r.selection.bestC)
                        + " bestLambda=" + fmt(r.selection.bestLambda)
                        + " stage1Val=" + fmt(r.selection.stage1Mean)
                        + " stage2Val=" + fmt(r.selection.stage2Mean));
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ex;
        } finally {
            pool.shutdownNow();
        }
    }

    private static TrialSelectionResult selectByInnerCv(Map<Integer, List<Sample>> samplesByK,
                                                        int testPeriodIdx,
                                                        List<String> lanes,
                                                        ProcurementParams params,
                                                        int outerTrial) throws Exception {
        double bestStage1 = Double.POSITIVE_INFINITY;
        int bestK = -1;
        double bestC = Double.NaN;
        List<Stage1CandidateRow> stage1Rows = new ArrayList<>();

        for (int k : K_GRID) {
            List<Sample> fullTrain = buildTrainingWindow(samplesByK.get(k), testPeriodIdx, k);
            List<Sample> subTrain = new ArrayList<>(fullTrain.subList(0, SUBTRAIN_SIZE));
            List<Sample> subVal = new ArrayList<>(fullTrain.subList(SUBTRAIN_SIZE, SUBTRAIN_SIZE + SUBVAL_SIZE));
            WindowInfo window = WindowInfo.of(fullTrain, subTrain, subVal);

            for (double c : C_GRID) {
                Config cfg = buildCsaaConfig(k, c);
                ValidationSummary summary = summarizeValidationContextual(lanes, params, subTrain, subVal, cfg);
                stage1Rows.add(new Stage1CandidateRow(outerTrial, testPeriodIdx, window, k, c, summary));
                if (isBetterStage1(summary.meanRealized, k, c, bestStage1, bestK, bestC)) {
                    bestStage1 = summary.meanRealized;
                    bestK = k;
                    bestC = c;
                }
            }
        }

        if (bestK < 0 || !Double.isFinite(bestStage1)) {
            throw new IllegalStateException("Failed to find valid (k, C_h) in stage 1 for testPeriod=" + testPeriodIdx);
        }
        markChosenStage1(stage1Rows, bestK, bestC);

        List<Sample> bestTrain = buildTrainingWindow(samplesByK.get(bestK), testPeriodIdx, bestK);
        List<Sample> bestSubTrain = new ArrayList<>(bestTrain.subList(0, SUBTRAIN_SIZE));
        List<Sample> bestSubVal = new ArrayList<>(bestTrain.subList(SUBTRAIN_SIZE, SUBTRAIN_SIZE + SUBVAL_SIZE));
        WindowInfo bestWindow = WindowInfo.of(bestTrain, bestSubTrain, bestSubVal);

        double bestStage2 = Double.POSITIVE_INFINITY;
        double bestLambda = Double.NaN;
        List<Stage2CandidateRow> stage2Rows = new ArrayList<>();
        for (double lambda : LAMBDA_GRID) {
            Config cfg = buildRcsaaConfig(bestK, bestC, lambda);
            ValidationSummary summary = summarizeValidationRcsaa(lanes, params, bestSubTrain, bestSubVal, cfg);
            stage2Rows.add(new Stage2CandidateRow(outerTrial, testPeriodIdx, bestWindow, bestK, bestC, lambda, summary));
            if (isBetterStage2(summary.meanRealized, lambda, bestStage2, bestLambda)) {
                bestStage2 = summary.meanRealized;
                bestLambda = lambda;
            }
        }

        if (!Double.isFinite(bestStage2) || !Double.isFinite(bestLambda)) {
            throw new IllegalStateException("Failed to find valid lambda in stage 2 for testPeriod=" + testPeriodIdx);
        }
        markChosenStage2(stage2Rows, bestLambda);

        CvSelection sel = new CvSelection();
        sel.bestK = bestK;
        sel.bestC = bestC;
        sel.bestLambda = bestLambda;
        sel.stage1Mean = bestStage1;
        sel.stage2Mean = bestStage2;
        TrialSolveResult selectedCsaa = solveSelectedActual(samplesByK, lanes, params, testPeriodIdx, bestK, bestC, Double.NaN, SolveMode.CSAA);
        TrialSolveResult selectedRcsaa = solveSelectedActual(samplesByK, lanes, params, testPeriodIdx, bestK, bestC, bestLambda, SolveMode.RCSAA);
        TrialSelectionResult out = new TrialSelectionResult();
        out.outerTrial = outerTrial;
        out.testPeriodIdx = testPeriodIdx;
        out.selection = sel;
        out.stage1Rows = stage1Rows;
        out.stage2Rows = stage2Rows;
        out.selectedActualRows = new ArrayList<>();
        out.selectedActualRows.add(toSelectedActualRow(outerTrial, selectedCsaa, bestK, bestC, Double.NaN));
        out.selectedActualRows.add(toSelectedActualRow(outerTrial, selectedRcsaa, bestK, bestC, bestLambda));
        return out;
    }

    /**
     * Compute stage-2 (lambda) validation for a fixed (trialId, k, C_h), append all candidate rows to {@code stage2Csv},
     * and return the best lambda by mean realized objective (tie-break: smaller lambda).
     *
     * This is exposed for reuse by offline runners that want to avoid recomputing stage-1.
     */
    public static Stage2Best computeAndAppendStage2ForFixedKC(Map<Integer, List<Sample>> samplesByK,
                                                              int testPeriodIdx,
                                                              List<String> lanes,
                                                              ProcurementParams params,
                                                              int trialId,
                                                              int k,
                                                              double cH,
                                                              Path stage2Csv) throws Exception {
        Objects.requireNonNull(samplesByK, "samplesByK");
        Objects.requireNonNull(lanes, "lanes");
        Objects.requireNonNull(params, "params");
        Objects.requireNonNull(stage2Csv, "stage2Csv");
        List<Sample> bestTrain = buildTrainingWindow(samplesByK.get(k), testPeriodIdx, k);
        List<Sample> bestSubTrain = new ArrayList<>(bestTrain.subList(0, SUBTRAIN_SIZE));
        List<Sample> bestSubVal = new ArrayList<>(bestTrain.subList(SUBTRAIN_SIZE, SUBTRAIN_SIZE + SUBVAL_SIZE));
        WindowInfo bestWindow = WindowInfo.of(bestTrain, bestSubTrain, bestSubVal);

        double bestStage2 = Double.POSITIVE_INFINITY;
        double bestLambda = Double.NaN;
        List<Stage2CandidateRow> stage2Rows = new ArrayList<>();
        for (double lambda : LAMBDA_GRID) {
            Config cfg = buildRcsaaConfig(k, cH, lambda);
            ValidationSummary summary = summarizeValidationRcsaa(lanes, params, bestSubTrain, bestSubVal, cfg);
            stage2Rows.add(new Stage2CandidateRow(trialId, testPeriodIdx, bestWindow, k, cH, lambda, summary));
            if (isBetterStage2(summary.meanRealized, lambda, bestStage2, bestLambda)) {
                bestStage2 = summary.meanRealized;
                bestLambda = lambda;
            }
        }

        if (!Double.isFinite(bestStage2) || !Double.isFinite(bestLambda)) {
            throw new IllegalStateException("Failed to find valid lambda in stage 2 for trial=" + trialId + ", testPeriod=" + testPeriodIdx);
        }
        markChosenStage2(stage2Rows, bestLambda);
        appendStage2DetailRows(stage2Csv, stage2Rows);
        return new Stage2Best(bestLambda, bestStage2);
    }

    private static ValidationSummary summarizeValidationContextual(List<String> lanes,
                                                                  ProcurementParams params,
                                                                  List<Sample> subTrain,
                                                                  List<Sample> subVal,
                                                                  Config cfg) throws Exception {
        ValidationAccumulator acc = new ValidationAccumulator();
        for (Sample val : subVal) {
            TrialSolveResult one = solveSingleContextual(lanes, params, subTrain, val, cfg);
            acc.add(one);
        }
        return acc.finish();
    }

    private static ValidationSummary summarizeValidationRcsaa(List<String> lanes,
                                                              ProcurementParams params,
                                                              List<Sample> subTrain,
                                                              List<Sample> subVal,
                                                              Config cfg) throws Exception {
        ValidationAccumulator acc = new ValidationAccumulator();
        for (Sample val : subVal) {
            TrialSolveResult one = solveSingleRcsaa(lanes, params, subTrain, val, cfg);
            acc.add(one);
        }
        return acc.finish();
    }

    private static TrialSolveResult solveSingleContextual(List<String> lanes,
                                                          ProcurementParams params,
                                                          List<Sample> trainRaw,
                                                          Sample testSample,
                                                          Config cfg) throws Exception {
        List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());
        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;

        if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler(cfg.thetaScaling);
            scaler.fit(train, thetaDim);
            for (Sample s : train) {
                s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            }
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }

        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new Helper.calculateHelper.EuclideanDistance());
        wc.computeKernelWeights(train, thetaNow, cfg);

        double[] dTest = testSample.demand().clone();
        BatchRunner.TrialDiag diag = BatchRunner.TrialDiag.compute(train, thetaNow, dTest, new Helper.calculateHelper.EuclideanDistance());

        SAAModel model = new SAAModel();
        long st = System.nanoTime();
        Solution sol = model.solve(new Basic.Data(lanes, train, thetaNow, params), cfg, null);
        long ed = System.nanoTime();

        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(
                params, sol.y, dTest, cfg.enforceDemandEquality);
        return buildTrialResult(cfg, train, testSample, diag, sol, rec, (ed - st) / 1e9);
    }

    private static TrialSolveResult solveSingleRcsaa(List<String> lanes,
                                                     ProcurementParams params,
                                                     List<Sample> trainRaw,
                                                     Sample testSample,
                                                     Config cfg) throws Exception {
        List<Sample> train = deepCopySamples(trainRaw);
        CovariateVector thetaNow = new CovariateVector(testSample.theta.values().clone());
        int thetaDim = train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length;

        if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
            StandardScaler scaler = new StandardScaler(cfg.thetaScaling);
            scaler.fit(train, thetaDim);
            for (Sample s : train) {
                s.theta = new CovariateVector(scaler.transform(s.theta.values()));
            }
            thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
        }

        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new Helper.calculateHelper.EuclideanDistance());
        wc.computeKernelWeights(train, thetaNow, cfg);

        double[] dTest = testSample.demand().clone();
        BatchRunner.TrialDiag diag = BatchRunner.TrialDiag.compute(train, thetaNow, dTest, new Helper.calculateHelper.EuclideanDistance());

        DROModel model = new DROModel();
        long st = System.nanoTime();
        Solution sol = model.solve(new Basic.Data(lanes, train, thetaNow, params), cfg);
        long ed = System.nanoTime();

        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(
                params, sol.y, dTest, cfg.enforceDemandEquality);
        TrialSolveResult out = buildTrialResult(cfg, train, testSample, diag, sol, rec, (ed - st) / 1e9);
        out.expected = weightedMean(empiricalRecourseCosts(
                params, train, sol.y, cfg.enforceDemandEquality), train);
        return out;
    }

    private static TrialSolveResult solveSelectedActual(Map<Integer, List<Sample>> samplesByK,
                                                        List<String> lanes,
                                                        ProcurementParams params,
                                                        int testPeriodIdx,
                                                        int k,
                                                        double cH,
                                                        double lambda,
                                                        SolveMode solveMode) throws Exception {
        Config cfg = (solveMode == SolveMode.RCSAA) ? buildRcsaaConfig(k, cH, lambda) : buildCsaaConfig(k, cH);
        List<Sample> fullTrain = buildTrainingWindow(samplesByK.get(k), testPeriodIdx, k);
        Sample testSample = getTestSample(samplesByK.get(k), testPeriodIdx, k);
        if (solveMode == SolveMode.RCSAA) {
            return solveSingleRcsaa(lanes, params, fullTrain, testSample, cfg);
        }
        return solveSingleContextual(lanes, params, fullTrain, testSample, cfg);
    }

    private static Sample getTestSample(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        if (testSampleIdx < 0 || testSampleIdx >= samplesForK.size()) {
            throw new IllegalArgumentException("Invalid test sample index for testPeriod=" + testPeriodIdx + ", k=" + k);
        }
        return samplesForK.get(testSampleIdx);
    }

    private static TrialSolveResult buildTrialResult(Config cfg,
                                                     List<Sample> train,
                                                     Sample testSample,
                                                     BatchRunner.TrialDiag diag,
                                                     Solution sol,
                                                     BatchRunner.RecourseEvaluator.RecourseEval rec,
                                                     double solveTimeSec) {
        TrialSolveResult out = new TrialSolveResult();
        out.cfg = copyBase(cfg);
        out.testPeriodIdx = testSample.period.tIndex;
        out.trainSize = train.size();
        out.diag = diag;
        out.sol = sol;
        out.rec = rec;
        out.expected = sol.objValue;
        out.realized = rec.objValue;
        out.solveTimeSec = solveTimeSec;
        out.selectedCount = countSelected(sol.y);
        out.yBinary = yBinary(sol.y);
        out.selectedCarriers = selectedCarriers(sol.y);
        out.sampleWeights = buildSampleWeightsString(train);
        return out;
    }

    private static List<Sample> buildTrainingWindow(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        int start = testSampleIdx - (SUBTRAIN_SIZE + SUBVAL_SIZE);
        int end = testSampleIdx;
        if (start < 0 || end > samplesForK.size()) {
            throw new IllegalArgumentException("Invalid training window for testPeriod=" + testPeriodIdx + ", k=" + k);
        }
        return new ArrayList<>(samplesForK.subList(start, end));
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
        base.thetaScaling = StandardScaler.Mode.TRAINING_MAX;
        // Reviewer-kernel ablation hook.  The normal/default path remains EXPONENTIAL.
        // TRBReviewerKernelAblation sets this JVM property before invoking this runner,
        // which lets both kernels use the identical 35/15 rolling-CV implementation.
        String kernelName = System.getProperty("trb.reviewer.kernelType", "EXPONENTIAL");
        base.kernelType = Helper.calculateHelper.KernelType.valueOf(
                kernelName.trim().toUpperCase(Locale.ROOT));
        // Reviewer-only hook.  The property is unset in historical runs, so
        // their original >= convention is unchanged.  TRBReviewerKernelAblation
        // sets it to true to use the corrected constraint-(6) equality.
        base.enforceDemandEquality = Boolean.parseBoolean(
                System.getProperty("trb.reviewer.enforceDemandEquality", "false"));
        base.C_h = cH;
        base.threads = 4;
        base.timeLimitSeconds = 3600;
        base.seed = 0;
        base.writeCplexLogToFile = false;
        return base;
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
        c.enforceDemandEquality = b.enforceDemandEquality;
        c.C_h = b.C_h;
        c.bandwidthH = b.bandwidthH;
        c.lambda = b.lambda;
        c.solveMode = b.solveMode;
        c.rcsaaSolverVariant = b.rcsaaSolverVariant;
        c.threads = b.threads;
        c.timeLimitSeconds = b.timeLimitSeconds;
        c.seed = b.seed;
        c.writeCplexLogToFile = b.writeCplexLogToFile;
        return c;
    }

    private static Config buildCsaaConfig(int k, double c) {
        Config cfg = buildBaseConfig(k, c);
        cfg.solveMode = SolveMode.CSAA;
        return cfg;
    }

    private static Config buildRcsaaConfig(int k, double c, double lambda) {
        Config cfg = buildBaseConfig(k, c);
        cfg.solveMode = SolveMode.RCSAA;
        cfg.lambda = lambda;
        cfg.rcsaaSolverVariant = RCSAASolverVariant.valueOf(System.getProperty(
                "trb.reviewer.rcsaaSolverVariant", RCSAASolverVariant.DRO_EXTENSIVE.name())
                .trim().toUpperCase(Locale.ROOT));
        return cfg;
    }

    private static ExperimentBatches sliceBatches(ExperimentBatches src, int startTrial, int trialCount) {
        return new ExperimentBatches(
                src.mode,
                new ArrayList<>(src.trainSets.subList(startTrial, startTrial + trialCount)),
                new ArrayList<>(src.thetaNowList.subList(startTrial, startTrial + trialCount)),
                new ArrayList<>(src.testSamples.subList(startTrial, startTrial + trialCount)),
                new ArrayList<>(src.testIndex.subList(startTrial, startTrial + trialCount))
        );
    }


    private static void initStage1DetailCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write(String.join(",",
                    "trialId", "testPeriodIdx",
                    "fullTrainStartPeriod", "fullTrainEndPeriod",
                    "subTrainStartPeriod", "subTrainEndPeriod",
                    "subValStartPeriod", "subValEndPeriod",
                    "k", "C_h", "isChosen",
                    "validCount",
                    "meanExpectedObj", "meanRealizedObj", "meanSolveTimeSec", "meanSelectedCount",
                    "meanOosTransportCost", "meanOosSpotCost", "meanOosPenaltyCost",
                    "meanSumW", "meanSumW2", "meanESS", "meanTop1W", "meanTop5Wsum", "meanMaxWOverMean",
                    "meanThetaDistMean", "meanThetaDistMedian", "meanThetaDistMin", "meanThetaDistMax",
                    "meanDemandDistMean", "meanDemandDistMedian", "meanDemandDistMin", "meanDemandDistMax",
                    "meanCorrWThetaDist", "meanCorrWDemandDist", "meanCorrThetaDemandDist"));
            bw.newLine();
        }
    }

    private static void initStage2DetailCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write(String.join(",",
                    "trialId", "testPeriodIdx",
                    "fullTrainStartPeriod", "fullTrainEndPeriod",
                    "subTrainStartPeriod", "subTrainEndPeriod",
                    "subValStartPeriod", "subValEndPeriod",
                    "k", "C_h", "lambda", "isChosen",
                    "validCount",
                    "meanExpectedObj", "meanRealizedObj", "meanSolveTimeSec", "meanSelectedCount",
                    "meanOosTransportCost", "meanOosSpotCost", "meanOosPenaltyCost",
                    "meanSumW", "meanSumW2", "meanESS", "meanTop1W", "meanTop5Wsum", "meanMaxWOverMean",
                    "meanThetaDistMean", "meanThetaDistMedian", "meanThetaDistMin", "meanThetaDistMax",
                    "meanDemandDistMean", "meanDemandDistMedian", "meanDemandDistMin", "meanDemandDistMax",
                    "meanCorrWThetaDist", "meanCorrWDemandDist", "meanCorrThetaDemandDist"));
            bw.newLine();
        }
    }

    private static void appendStage1DetailRows(Path csv, List<Stage1CandidateRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, java.nio.file.StandardOpenOption.APPEND)) {
            for (Stage1CandidateRow row : rows) {
                ValidationSummary s = row.summary;
                bw.write(String.format(Locale.US,
                        "%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%s,%d," +
                                "%.10f,%.10f,%.6f,%.10f," +
                                "%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f%n",
                        row.trialId, row.testPeriodIdx,
                        row.window.fullTrainStartPeriod, row.window.fullTrainEndPeriod,
                        row.window.subTrainStartPeriod, row.window.subTrainEndPeriod,
                        row.window.subValStartPeriod, row.window.subValEndPeriod,
                        row.k, row.cH, String.valueOf(row.chosen), s.validCount,
                        s.meanExpected, s.meanRealized, s.meanSolveTimeSec, s.meanSelectedCount,
                        s.meanTransport, s.meanSpot, s.meanPenalty,
                        s.meanSumW, s.meanSumW2, s.meanEss, s.meanTop1W, s.meanTop5Wsum, s.meanMaxOverMean,
                        s.meanThetaMean, s.meanThetaMedian, s.meanThetaMin, s.meanThetaMax,
                        s.meanDemandMean, s.meanDemandMedian, s.meanDemandMin, s.meanDemandMax,
                        s.meanCorrWTheta, s.meanCorrWDemand, s.meanCorrThetaDemand));
            }
        }
    }

    private static void appendStage2DetailRows(Path csv, List<Stage2CandidateRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, java.nio.file.StandardOpenOption.APPEND)) {
            for (Stage2CandidateRow row : rows) {
                ValidationSummary s = row.summary;
                bw.write(String.format(Locale.US,
                        "%d,%d,%d,%d,%d,%d,%d,%d,%d,%.6f,%.6f,%s,%d," +
                                "%.10f,%.10f,%.6f,%.10f," +
                                "%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f%n",
                        row.trialId, row.testPeriodIdx,
                        row.window.fullTrainStartPeriod, row.window.fullTrainEndPeriod,
                        row.window.subTrainStartPeriod, row.window.subTrainEndPeriod,
                        row.window.subValStartPeriod, row.window.subValEndPeriod,
                        row.k, row.cH, row.lambda, String.valueOf(row.chosen), s.validCount,
                        s.meanExpected, s.meanRealized, s.meanSolveTimeSec, s.meanSelectedCount,
                        s.meanTransport, s.meanSpot, s.meanPenalty,
                        s.meanSumW, s.meanSumW2, s.meanEss, s.meanTop1W, s.meanTop5Wsum, s.meanMaxOverMean,
                        s.meanThetaMean, s.meanThetaMedian, s.meanThetaMin, s.meanThetaMax,
                        s.meanDemandMean, s.meanDemandMedian, s.meanDemandMin, s.meanDemandMax,
                        s.meanCorrWTheta, s.meanCorrWDemand, s.meanCorrThetaDemand));
            }
        }
    }

    private static void markChosenStage1(List<Stage1CandidateRow> rows, int bestK, double bestC) {
        for (Stage1CandidateRow row : rows) {
            row.chosen = row.k == bestK && Math.abs(row.cH - bestC) <= 1e-9;
        }
    }

    private static void markChosenStage2(List<Stage2CandidateRow> rows, double bestLambda) {
        for (Stage2CandidateRow row : rows) {
            row.chosen = Math.abs(row.lambda - bestLambda) <= 1e-9;
        }
    }

    private static void initSelectionCsv(Path selectionCsv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(selectionCsv)) {
            bw.write("trialId,testPeriodIdx,bestK,bestC_h,bestLambda,stage1ValMean,stage2ValMean");
            bw.newLine();
        }
    }

    private static void appendSelectionCsv(Path selectionCsv, int trialId, int testPeriodIdx, CvSelection sel) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(selectionCsv, java.nio.file.StandardOpenOption.APPEND)) {
            bw.write(String.format(Locale.US, "%d,%d,%d,%.10f,%.10f,%.10f,%.10f%n",
                    trialId, testPeriodIdx, sel.bestK, sel.bestC, sel.bestLambda, sel.stage1Mean, sel.stage2Mean));
        }
    }

    private static void initSelectedActualCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write(String.join(",",
                    "trialId", "actual_test_period", "method_name",
                    "selected_k", "selected_C_h", "selected_lambda",
                    "solve_mode", "solver_variant", "fillMissingDates", "standardizeTheta", "thetaScaling", "k1Lag", "kernelType",
                    "bandwidthH", "train_size",
                    "expected_obj", "realized_obj", "solve_time_sec", "selected_count",
                    "oos_transport_cost", "oos_spot_cost", "oos_penalty_cost",
                    "sumW", "sumW2", "ESS", "top1W", "top5Wsum", "maxW_over_meanW",
                    "thetaDist_mean", "thetaDist_median", "thetaDist_min", "thetaDist_max",
                    "demandDist_mean", "demandDist_median", "demandDist_min", "demandDist_max",
                    "corrW_thetaDist", "corrW_demandDist", "corrTheta_demandDist",
                    "yBinary", "selectedCarriers"));
            bw.newLine();
        }
    }

    private static void appendSelectedActualRows(Path csv, List<SelectedActualRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, java.nio.file.StandardOpenOption.APPEND)) {
            for (SelectedActualRow row : rows) {
                bw.write(row.csvLine);
                bw.newLine();
            }
        }
    }

    private static boolean isBetterStage1(double candMean, int candK, double candC,
                                          double bestMean, int bestK, double bestC) {
        if (!Double.isFinite(candMean)) return false;
        if (!Double.isFinite(bestMean)) return true;
        if (candMean < bestMean - 1e-9) return true;
        if (Math.abs(candMean - bestMean) <= 1e-9 && candK < bestK) return true;
        return Math.abs(candMean - bestMean) <= 1e-9 && candK == bestK && candC < bestC;
    }

    private static boolean isBetterStage2(double candMean, double candLambda, double bestMean, double bestLambda) {
        if (!Double.isFinite(candMean)) return false;
        if (!Double.isFinite(bestMean)) return true;
        if (candMean < bestMean - 1e-9) return true;
        return Math.abs(candMean - bestMean) <= 1e-9 && candLambda < bestLambda;
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static SelectedActualRow toSelectedActualRow(int trialId,
                                                         TrialSolveResult r,
                                                         int k,
                                                         double cH,
                                                         double lambda) {
        String methodName = (r.cfg.solveMode == SolveMode.RCSAA)
                ? System.getProperty("trb.reviewer.rcsaaResultLabel", "RCSAA") : "CSAA";
        String solverVariant = r.cfg.solveMode == SolveMode.RCSAA && r.cfg.rcsaaSolverVariant != null
                ? r.cfg.rcsaaSolverVariant.name() : "";
        String lambdaText = (r.cfg.solveMode == SolveMode.RCSAA)
                ? String.format(Locale.US, "%.10f", lambda)
                : "";
        String line = String.format(Locale.US,
                "%d,%d,%s,%d,%.10f,%s,%s,%s,%s,%s,%s,%d,%s,%.10f,%d," +
                        "%.10f,%.10f,%.6f,%d," +
                        "%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,\"%s\",\"%s\"",
                trialId, r.testPeriodIdx, methodName, k, cH, lambdaText,
                r.cfg.solveMode.name(), solverVariant, String.valueOf(r.cfg.fillMissingDates), String.valueOf(r.cfg.standardizeTheta),
                r.cfg.thetaScaling.name(),
                r.cfg.k1LagPeriods, r.cfg.kernelType.name(), r.cfg.bandwidthH, r.trainSize,
                r.expected, r.realized, r.solveTimeSec, r.selectedCount,
                r.rec.transportTotalCost, r.rec.spotTotalCost, r.rec.penaltyTotalCost,
                r.diag.sumW, r.diag.sumW2, r.diag.ess, r.diag.top1W, r.diag.top5Wsum, r.diag.maxOverMean,
                r.diag.thetaMean, r.diag.thetaMedian, r.diag.thetaMin, r.diag.thetaMax,
                r.diag.demMean, r.diag.demMedian, r.diag.demMin, r.diag.demMax,
                r.diag.corrWTheta, r.diag.corrWDemand, r.diag.corrThetaDemand,
                r.yBinary.replace("\"", "\"\""), r.selectedCarriers.replace("\"", "\"\""));
        return new SelectedActualRow(line);
    }

    private static double[] buildBaselineDemand(List<PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : periods) {
            for (int j = 0; j < jSize; j++) sum[j] += p.demandSum[j];
        }
        double denom = Math.max(1, periods.size());
        for (int j = 0; j < jSize; j++) sum[j] /= denom;
        return sum;
    }

    private static ProcurementParams applyPenaltyRule(ProcurementParams source) {
        String rule = System.getProperty("trb.reviewer.penalty", "max")
                .trim().toLowerCase(Locale.ROOT);
        if ("max".equals(rule)) return source;
        if (!"min".equals(rule)) {
            throw new IllegalArgumentException("Unsupported trb.reviewer.penalty=" + rule
                    + "; expected min or max.");
        }

        double[] h = new double[source.I];
        for (int i = 0; i < source.I; i++) {
            double min = Double.POSITIVE_INFINITY;
            for (int j = 0; j < source.J; j++) {
                if (source.eligible[i][j]) min = Math.min(min, source.r[i][j]);
            }
            if (!Double.isFinite(min)) {
                throw new IllegalStateException("Carrier has no eligible lane: " + i);
            }
            h[i] = min;
        }
        return new ProcurementParams(new ArrayList<>(source.carriers), source.J,
                source.e.clone(), source.p.clone(), h,
                copy(source.q), copy(source.r), copy(source.eligible),
                source.alpha, source.beta);
    }

    private static double[][] copy(double[][] source) {
        double[][] result = new double[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    private static boolean[][] copy(boolean[][] source) {
        boolean[][] result = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
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
            for (String lane : lanes) bw.write("," + csvEscape(lane));
            bw.newLine();

            int weekIndex = 0;
            for (int start = 0; start + 7 <= allDays.size(); start += 7) {
                bw.write(String.valueOf(weekIndex++));
                for (String lane : lanes) {
                    double sum = 0.0;
                    for (int k = start; k < start + 7; k++) {
                        LocalDate d = allDays.get(k);
                        Map<String, Double> m = byDayLane.getOrDefault(d, Collections.emptyMap());
                        sum += m.getOrDefault(lane, 0.0);
                    }
                    bw.write("," + String.format(Locale.US, "%.10f", sum));
                }
                bw.newLine();
            }
        }
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (!s.contains(",") && !s.contains("\"")) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception ex) {
            return 0.0;
        }
    }

    private static double[] empiricalRecourseCosts(ProcurementParams params,
                                                   List<Sample> train,
                                                   double[] y,
                                                   boolean enforceDemandEquality) throws Exception {
        double[] vals = new double[train.size()];
        for (int i = 0; i < train.size(); i++) {
            vals[i] = BatchRunner.RecourseEvaluator.evaluate(
                    params, y, train.get(i).demand().clone(), enforceDemandEquality).objValue;
        }
        return vals;
    }

    private static double weightedMean(double[] vals, List<Sample> train) {
        double sumW = 0.0;
        double sum = 0.0;
        for (int i = 0; i < train.size(); i++) {
            double w = train.get(i).weight;
            if (!Double.isFinite(w)) continue;
            sumW += w;
            sum += w * vals[i];
        }
        return sumW > 0.0 ? sum / sumW : Double.NaN;
    }

    private static List<Sample> deepCopySamples(List<Sample> src) {
        List<Sample> out = new ArrayList<>(src.size());
        for (Sample s : src) {
            CovariateVector thetaCopy = new CovariateVector(s.theta.values().clone());
            out.add(new Sample(s.id, s.period, thetaCopy, s.weight));
        }
        return out;
    }

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y) if (v > 0.5) c++;
        return c;
    }

    private static String yBinary(double[] y) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < y.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(y[i] > 0.5 ? 1 : 0);
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

    private static String buildSampleWeightsString(List<Sample> train) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < train.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "%.10f", train.get(i).weight));
        }
        sb.append("]");
        return sb.toString();
    }

    private static class CvSelection {
        int bestK;
        double bestC;
        double bestLambda;
        double stage1Mean;
        double stage2Mean;
    }

    private static class WindowInfo {
        int fullTrainStartPeriod;
        int fullTrainEndPeriod;
        int subTrainStartPeriod;
        int subTrainEndPeriod;
        int subValStartPeriod;
        int subValEndPeriod;

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

    private static class ValidationSummary {
        int validCount;
        double meanExpected;
        double meanRealized;
        double meanSolveTimeSec;
        double meanSelectedCount;
        double meanTransport;
        double meanSpot;
        double meanPenalty;
        double meanSumW;
        double meanSumW2;
        double meanEss;
        double meanTop1W;
        double meanTop5Wsum;
        double meanMaxOverMean;
        double meanThetaMean;
        double meanThetaMedian;
        double meanThetaMin;
        double meanThetaMax;
        double meanDemandMean;
        double meanDemandMedian;
        double meanDemandMin;
        double meanDemandMax;
        double meanCorrWTheta;
        double meanCorrWDemand;
        double meanCorrThetaDemand;
    }

    private static class ValidationAccumulator {
        int validCount;
        double sumExpected;
        double sumRealized;
        double sumSolveTimeSec;
        double sumSelectedCount;
        double sumTransport;
        double sumSpot;
        double sumPenalty;
        double sumSumW;
        double sumSumW2;
        double sumEss;
        double sumTop1W;
        double sumTop5Wsum;
        double sumMaxOverMean;
        double sumThetaMean;
        double sumThetaMedian;
        double sumThetaMin;
        double sumThetaMax;
        double sumDemandMean;
        double sumDemandMedian;
        double sumDemandMin;
        double sumDemandMax;
        double sumCorrWTheta;
        double sumCorrWDemand;
        double sumCorrThetaDemand;

        void add(TrialSolveResult one) {
            if (one == null || !Double.isFinite(one.realized)) {
                return;
            }
            validCount++;
            sumExpected += one.expected;
            sumRealized += one.realized;
            sumSolveTimeSec += one.solveTimeSec;
            sumSelectedCount += one.selectedCount;
            sumTransport += one.rec.transportTotalCost;
            sumSpot += one.rec.spotTotalCost;
            sumPenalty += one.rec.penaltyTotalCost;
            sumSumW += one.diag.sumW;
            sumSumW2 += one.diag.sumW2;
            sumEss += one.diag.ess;
            sumTop1W += one.diag.top1W;
            sumTop5Wsum += one.diag.top5Wsum;
            sumMaxOverMean += one.diag.maxOverMean;
            sumThetaMean += one.diag.thetaMean;
            sumThetaMedian += one.diag.thetaMedian;
            sumThetaMin += one.diag.thetaMin;
            sumThetaMax += one.diag.thetaMax;
            sumDemandMean += one.diag.demMean;
            sumDemandMedian += one.diag.demMedian;
            sumDemandMin += one.diag.demMin;
            sumDemandMax += one.diag.demMax;
            sumCorrWTheta += one.diag.corrWTheta;
            sumCorrWDemand += one.diag.corrWDemand;
            sumCorrThetaDemand += one.diag.corrThetaDemand;
        }

        ValidationSummary finish() {
            ValidationSummary out = new ValidationSummary();
            out.validCount = validCount;
            if (validCount == 0) {
                out.meanExpected = Double.POSITIVE_INFINITY;
                out.meanRealized = Double.POSITIVE_INFINITY;
                out.meanSolveTimeSec = Double.NaN;
                out.meanSelectedCount = Double.NaN;
                out.meanTransport = Double.NaN;
                out.meanSpot = Double.NaN;
                out.meanPenalty = Double.NaN;
                out.meanSumW = Double.NaN;
                out.meanSumW2 = Double.NaN;
                out.meanEss = Double.NaN;
                out.meanTop1W = Double.NaN;
                out.meanTop5Wsum = Double.NaN;
                out.meanMaxOverMean = Double.NaN;
                out.meanThetaMean = Double.NaN;
                out.meanThetaMedian = Double.NaN;
                out.meanThetaMin = Double.NaN;
                out.meanThetaMax = Double.NaN;
                out.meanDemandMean = Double.NaN;
                out.meanDemandMedian = Double.NaN;
                out.meanDemandMin = Double.NaN;
                out.meanDemandMax = Double.NaN;
                out.meanCorrWTheta = Double.NaN;
                out.meanCorrWDemand = Double.NaN;
                out.meanCorrThetaDemand = Double.NaN;
                return out;
            }
            double denom = validCount;
            out.meanExpected = sumExpected / denom;
            out.meanRealized = sumRealized / denom;
            out.meanSolveTimeSec = sumSolveTimeSec / denom;
            out.meanSelectedCount = sumSelectedCount / denom;
            out.meanTransport = sumTransport / denom;
            out.meanSpot = sumSpot / denom;
            out.meanPenalty = sumPenalty / denom;
            out.meanSumW = sumSumW / denom;
            out.meanSumW2 = sumSumW2 / denom;
            out.meanEss = sumEss / denom;
            out.meanTop1W = sumTop1W / denom;
            out.meanTop5Wsum = sumTop5Wsum / denom;
            out.meanMaxOverMean = sumMaxOverMean / denom;
            out.meanThetaMean = sumThetaMean / denom;
            out.meanThetaMedian = sumThetaMedian / denom;
            out.meanThetaMin = sumThetaMin / denom;
            out.meanThetaMax = sumThetaMax / denom;
            out.meanDemandMean = sumDemandMean / denom;
            out.meanDemandMedian = sumDemandMedian / denom;
            out.meanDemandMin = sumDemandMin / denom;
            out.meanDemandMax = sumDemandMax / denom;
            out.meanCorrWTheta = sumCorrWTheta / denom;
            out.meanCorrWDemand = sumCorrWDemand / denom;
            out.meanCorrThetaDemand = sumCorrThetaDemand / denom;
            return out;
        }
    }

    private static class Stage1CandidateRow {
        int trialId;
        int testPeriodIdx;
        WindowInfo window;
        int k;
        double cH;
        boolean chosen;
        ValidationSummary summary;

        Stage1CandidateRow(int trialId, int testPeriodIdx, WindowInfo window, int k, double cH, ValidationSummary summary) {
            this.trialId = trialId;
            this.testPeriodIdx = testPeriodIdx;
            this.window = window;
            this.k = k;
            this.cH = cH;
            this.summary = summary;
        }
    }

    private static class Stage2CandidateRow {
        int trialId;
        int testPeriodIdx;
        WindowInfo window;
        int k;
        double cH;
        double lambda;
        boolean chosen;
        ValidationSummary summary;

        Stage2CandidateRow(int trialId, int testPeriodIdx, WindowInfo window, int k, double cH, double lambda, ValidationSummary summary) {
            this.trialId = trialId;
            this.testPeriodIdx = testPeriodIdx;
            this.window = window;
            this.k = k;
            this.cH = cH;
            this.lambda = lambda;
            this.summary = summary;
        }
    }

    private static class TrialSolveResult {
        Config cfg;
        int testPeriodIdx;
        int trainSize;
        double expected;
        double realized;
        double solveTimeSec;
        int selectedCount;
        String yBinary;
        String selectedCarriers;
        String sampleWeights;
        Solution sol;
        BatchRunner.RecourseEvaluator.RecourseEval rec;
        BatchRunner.TrialDiag diag;
    }

    private static class TrialSelectionResult {
        int outerTrial;
        int testPeriodIdx;
        CvSelection selection;
        List<Stage1CandidateRow> stage1Rows;
        List<Stage2CandidateRow> stage2Rows;
        List<SelectedActualRow> selectedActualRows;
    }

    private static class SelectedActualRow {
        String csvLine;

        SelectedActualRow(String csvLine) {
            this.csvLine = csvLine;
        }
    }

    private static class AdaptiveMethodRecorder {
        private final String tag;
        private final Path trialsCsv;
        private final List<Double> expectedList = new ArrayList<>();
        private final List<Double> modelObjectiveList = new ArrayList<>();
        private final List<Double> realizedList = new ArrayList<>();
        private final List<Double> essList = new ArrayList<>();
        private final List<Double> corrTDList = new ArrayList<>();
        private final List<Double> timeList = new ArrayList<>();
        private final List<Double> transportList = new ArrayList<>();
        private final List<Double> spotList = new ArrayList<>();
        private final List<Double> penaltyList = new ArrayList<>();

        AdaptiveMethodRecorder(Path outDir, String tag) throws Exception {
            this.tag = tag;
            this.trialsCsv = outDir.resolve("trials_" + tag + ".csv");
            try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv)) {
                bw.write(String.join(",",
                        "tag", "solveMode", "fillMissingDates", "standardizeTheta", "thetaScaling", "k1Lag", "kernelType", "bandwidthH",
                        "C_h", "lambda",
                        "trialId", "testIdx", "trainSize",
                        "expectedObj", "realizedObj", "solveTimeSec", "selectedCount",
                        "oosTransportCost", "oosSpotCost", "oosPenaltyCost",
                        "sumW", "sumW2", "ESS", "top1W", "top5Wsum", "maxW_over_meanW",
                        "thetaDist_mean", "thetaDist_median", "thetaDist_min", "thetaDist_max",
                        "demandDist_mean", "demandDist_median", "demandDist_min", "demandDist_max",
                        "corrW_thetaDist", "corrW_demandDist", "corrTheta_demandDist",
                        "yBinary", "selectedCarriers", "sampleWeights",
                        "cvStage1ValMean", "cvStage2ValMean"));
                bw.newLine();
            }
        }

        void append(int trialId,
                    int testPeriodIdx,
                    int chosenK,
                    double chosenC,
                    double chosenLambda,
                    double stage1ValMean,
                    double stage2ValMean,
                    TrialSolveResult r) throws Exception {
            expectedList.add(r.expected);
            modelObjectiveList.add(r.sol.objValue);
            realizedList.add(r.realized);
            essList.add(r.diag.ess);
            corrTDList.add(r.diag.corrThetaDemand);
            timeList.add(r.solveTimeSec);
            transportList.add(r.rec.transportTotalCost);
            spotList.add(r.rec.spotTotalCost);
            penaltyList.add(r.rec.penaltyTotalCost);

            try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv, java.nio.file.StandardOpenOption.APPEND)) {
                bw.write(String.format(Locale.US,
                        "%s,%s,%s,%s,%s,%d,%s,%.6f,%.6f,%.6f," +
                                "%d,%d,%d," +
                                "%.10f,%.10f,%.6f,%d," +
                                "%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f," +
                                "\"%s\",\"%s\",\"%s\",%.10f,%.10f%n",
                        tag,
                        r.cfg.solveMode.name(),
                        String.valueOf(r.cfg.fillMissingDates),
                        String.valueOf(r.cfg.standardizeTheta),
                        r.cfg.thetaScaling.name(),
                        chosenK,
                        r.cfg.kernelType.name(),
                        r.cfg.bandwidthH,
                        chosenC,
                        (r.cfg.solveMode == SolveMode.RCSAA ? chosenLambda : Double.NaN),
                        trialId,
                        testPeriodIdx,
                        r.trainSize,
                        r.expected,
                        r.realized,
                        r.solveTimeSec,
                        r.selectedCount,
                        r.rec.transportTotalCost,
                        r.rec.spotTotalCost,
                        r.rec.penaltyTotalCost,
                        r.diag.sumW,
                        r.diag.sumW2,
                        r.diag.ess,
                        r.diag.top1W,
                        r.diag.top5Wsum,
                        r.diag.maxOverMean,
                        r.diag.thetaMean,
                        r.diag.thetaMedian,
                        r.diag.thetaMin,
                        r.diag.thetaMax,
                        r.diag.demMean,
                        r.diag.demMedian,
                        r.diag.demMin,
                        r.diag.demMax,
                        r.diag.corrWTheta,
                        r.diag.corrWDemand,
                        r.diag.corrThetaDemand,
                        r.yBinary.replace("\"", "\"\""),
                        r.selectedCarriers.replace("\"", "\"\""),
                        r.sampleWeights.replace("\"", "\"\""),
                        stage1ValMean,
                        stage2ValMean
                ));
            }

            OutputManager out = new OutputManager(tag, trialId, r.cfg, new InstanceGenerator.GenConfig());
            OutputManager.writeSummary(out.resultsDir.resolve("summary.csv"), r.sol, r.trainSize, r.realized, r.diag);
        }

        void finish(GlobalSummaryCollector collector, SolveMode solveMode) throws Exception {
            SummaryStats exp = SummaryStats.of(expectedList);
            SummaryStats model = SummaryStats.of(modelObjectiveList);
            SummaryStats rea = SummaryStats.of(realizedList);
            SummaryStats ess = SummaryStats.of(essList);
            SummaryStats corr = SummaryStats.of(corrTDList);
            collector.append(
                    tag,
                    solveMode,
                    false,
                    true,
                    -1,
                    Helper.calculateHelper.KernelType.valueOf(
                            System.getProperty("trb.reviewer.kernelType", "EXPONENTIAL")
                                    .trim().toUpperCase(Locale.ROOT)),
                    1.0,
                    -1.0,
                    (solveMode == SolveMode.RCSAA ? -1.0 : Double.NaN),
                    expectedList.size(),
                    exp.toCollectorStats(),
                    model.toCollectorStats(),
                    rea.toCollectorStats(),
                    ess.toCollectorStats(),
                    corr.toCollectorStats(),
                    mean(transportList),
                    mean(spotList),
                    mean(penaltyList),
                    mean(timeList)
            );
        }
    }

    private static class SummaryStats {
        final int n;
        final double mean;
        final double std;
        final double min;
        final double max;
        final double p20;
        final double p50;
        final double p80;
        final double p95;

        private SummaryStats(int n, double mean, double std, double min, double max,
                             double p20, double p50, double p80, double p95) {
            this.n = n;
            this.mean = mean;
            this.std = std;
            this.min = min;
            this.max = max;
            this.p20 = p20;
            this.p50 = p50;
            this.p80 = p80;
            this.p95 = p95;
        }

        static SummaryStats of(List<Double> xs) {
            double[] a = xs.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).filter(Double::isFinite).toArray();
            if (a.length == 0) {
                return new SummaryStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            Arrays.sort(a);
            int n = a.length;
            double sum = 0.0;
            for (double v : a) sum += v;
            double mean = sum / n;
            double var = 0.0;
            for (double v : a) {
                double d = v - mean;
                var += d * d;
            }
            double std = Math.sqrt(var / Math.max(1, n - 1));
            return new SummaryStats(n, mean, std, a[0], a[n - 1], q(a, 0.2), q(a, 0.5), q(a, 0.8), q(a, 0.95));
        }

        private static double q(double[] a, double p) {
            if (a.length == 1) return a[0];
            double idx = p * (a.length - 1);
            int lo = (int) Math.floor(idx);
            int hi = (int) Math.ceil(idx);
            if (lo == hi) return a[lo];
            double w = idx - lo;
            return a[lo] * (1 - w) + a[hi] * w;
        }

        GlobalSummaryCollector.Stats toCollectorStats() {
            return new GlobalSummaryCollector.Stats(n, mean, std, min, max, p20, p50, p80, p95);
        }
    }

    private static double mean(List<Double> xs) {
        if (xs.isEmpty()) return Double.NaN;
        double sum = 0.0;
        int c = 0;
        for (Double x : xs) {
            if (x != null && Double.isFinite(x)) {
                sum += x;
                c++;
            }
        }
        return c == 0 ? Double.NaN : sum / c;
    }
}
