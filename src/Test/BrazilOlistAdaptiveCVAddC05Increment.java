package Test;

import Basic.CovariateVector;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.DROModel;
import Model.SAAModel;
import Model.Solution;
import Model.SolveMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class BrazilOlistAdaptiveCVAddC05Increment {

    private static final int[] K_GRID = {1, 2, 3};
    private static final double TARGET_C = 0.5;
    private static final double[] LAMBDA_GRID = {0.01, 0.05, 0.1, 1, 5, 10, 50, 100};
    private static final int SUBTRAIN_SIZE = 35;
    private static final int SUBVAL_SIZE = 15;
    private static final int W = 50;
    private static final int K_MAX = 3;
    private static final int DEFAULT_MAX_PARALLEL_TRIALS = 3;

    public static void main(String[] args) throws Exception {
        Path existingRoot = Paths.get(args.length > 0 ? args[0]
                : "analysis/巴西数据分析/新版_purchase时间/第一组_完整选参输出");
        Path outRoot = Paths.get(args.length > 1 ? args[1]
                : "analysis/巴西数据分析/新版_purchase时间/第一组_C_h0.5增量补充");
        int maxParallelTrials = (args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_MAX_PARALLEL_TRIALS);
        int startTrial = (args.length > 3 ? Integer.parseInt(args[3]) : 0);
        int maxTrials = (args.length > 4 ? Integer.parseInt(args[4]) : Integer.MAX_VALUE);

        Files.createDirectories(outRoot);
        Path weeklyCsv = existingRoot.resolve("chunks").resolve("chunk_00_12").resolve("巴西五大区23OD_周度宽表.csv");
        if (!Files.exists(weeklyCsv)) {
            throw new IllegalArgumentException("weekly csv not found: " + weeklyCsv.toAbsolutePath());
        }

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
        ProcurementParams params = InstanceGenerator.generate(10, dBase, genCfg, buildBaseConfig(K_MAX, 1.0));

        ExperimentBatches alignedRollingAll = ExperimentBuilder.buildRolling(samplesByK.get(K_MAX), W);
        Map<Integer, ExistingSelection> existingSelections = loadExistingSelections(existingRoot);
        if (existingSelections.size() != alignedRollingAll.size()) {
            throw new IllegalStateException("Expected existing selection count=" + alignedRollingAll.size()
                    + ", got=" + existingSelections.size());
        }
        if (startTrial < 0 || startTrial >= alignedRollingAll.size()) {
            throw new IllegalArgumentException("startTrial out of range: " + startTrial + ", total=" + alignedRollingAll.size());
        }
        int trialCount = Math.min(maxTrials, alignedRollingAll.size() - startTrial);
        if (trialCount <= 0) {
            throw new IllegalArgumentException("No trials selected.");
        }
        ExperimentBatches alignedRolling = sliceBatches(alignedRollingAll, startTrial, trialCount);

        Path stage1Csv = outRoot.resolve("cv_stage1_k_c_candidates_C0.5.csv");
        Path stage2Csv = outRoot.resolve("cv_stage2_lambda_candidates_C0.5_winners.csv");
        Path compareCsv = outRoot.resolve("trial_compare_C0.5_vs_old.csv");
        Path changedActualCsv = outRoot.resolve("changed_trials_actual_results.csv");
        Path updatedSelectionCsv = outRoot.resolve("cv_selected_params_含C0.5增量.csv");
        initStage1DetailCsv(stage1Csv);
        initStage2DetailCsv(stage2Csv);
        initCompareCsv(compareCsv);
        initChangedActualCsv(changedActualCsv);

        int totalTrials = alignedRolling.size();
        int parallelism = Math.max(1, Math.min(maxParallelTrials, totalTrials));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        CompletionService<TrialIncrementResult> completion = new ExecutorCompletionService<>(pool);

        for (int offset = 0; offset < totalTrials; offset++) {
            final int finalTrialId = startTrial + offset;
            final int testPeriodIdx = alignedRolling.testSamples.get(offset).period.tIndex;
            final ExistingSelection existing = Objects.requireNonNull(existingSelections.get(finalTrialId), "missing existing selection trial=" + finalTrialId);
            Callable<TrialIncrementResult> task = () ->
                    processTrial(samplesByK, params, lanes, finalTrialId, testPeriodIdx, existing);
            completion.submit(task);
        }

        Map<Integer, UpdatedSelection> updatedSelections = new HashMap<>();
        int changedTrials = 0;
        int done = 0;
        try {
            while (done < totalTrials) {
                Future<TrialIncrementResult> future = completion.take();
                TrialIncrementResult result;
                try {
                    result = future.get();
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    if (cause instanceof Exception) throw (Exception) cause;
                    throw new RuntimeException(cause);
                }
                appendStage1DetailRows(stage1Csv, result.stage1Rows);
                if (!result.stage2Rows.isEmpty()) {
                    appendStage2DetailRows(stage2Csv, result.stage2Rows);
                }
                appendCompareRow(compareCsv, result);
                if (!result.changedActualRows.isEmpty()) {
                    appendChangedActualRows(changedActualCsv, result.changedActualRows);
                }
                updatedSelections.put(result.trialId, result.updatedSelection);
                if (result.updatedSelection.stage1Updated) changedTrials++;
                done++;
                System.out.println("c0.5 increment done=" + done + "/" + totalTrials
                        + " trial=" + result.trialId
                        + " updated=" + result.updatedSelection.stage1Updated
                        + " newBestK=" + result.updatedSelection.bestK
                        + " newBestC=" + fmt(result.updatedSelection.bestC)
                        + " newBestLambda=" + fmt(result.updatedSelection.bestLambda));
            }
        } finally {
            pool.shutdownNow();
        }

        writeUpdatedSelections(updatedSelectionCsv, updatedSelections, startTrial, totalTrials);
        writeSummaryTxt(outRoot.resolve("increment_summary.txt"), startTrial, totalTrials, changedTrials);
        System.out.println("changedTrials=" + changedTrials);
        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static TrialIncrementResult processTrial(Map<Integer, List<Sample>> samplesByK,
                                                     ProcurementParams params,
                                                     List<String> lanes,
                                                     int trialId,
                                                     int testPeriodIdx,
                                                     ExistingSelection existing) throws Exception {
        TrialIncrementResult out = new TrialIncrementResult();
        out.trialId = trialId;
        out.testPeriodIdx = testPeriodIdx;
        out.stage1Rows = new ArrayList<>();
        out.stage2Rows = new ArrayList<>();
        out.changedActualRows = new ArrayList<>();

        double bestNewMean = Double.POSITIVE_INFINITY;
        int bestNewK = -1;
        for (int k : K_GRID) {
            List<Sample> fullTrain = buildTrainingWindow(samplesByK.get(k), testPeriodIdx, k);
            List<Sample> subTrain = new ArrayList<>(fullTrain.subList(0, SUBTRAIN_SIZE));
            List<Sample> subVal = new ArrayList<>(fullTrain.subList(SUBTRAIN_SIZE, SUBTRAIN_SIZE + SUBVAL_SIZE));
            WindowInfo window = WindowInfo.of(fullTrain, subTrain, subVal);
            Config cfg = buildCsaaConfig(k, TARGET_C);
            ValidationSummary summary = summarizeValidationContextual(lanes, params, subTrain, subVal, cfg);
            Stage1CandidateRow row = new Stage1CandidateRow(trialId, testPeriodIdx, window, k, TARGET_C, summary);
            out.stage1Rows.add(row);
            if (isBetterStage1(summary.meanRealized, k, TARGET_C, bestNewMean, bestNewK, TARGET_C)) {
                bestNewMean = summary.meanRealized;
                bestNewK = k;
            }
        }

        UpdatedSelection updated = new UpdatedSelection();
        updated.trialId = trialId;
        updated.testPeriodIdx = testPeriodIdx;
        updated.oldBestK = existing.bestK;
        updated.oldBestC = existing.bestC;
        updated.oldBestLambda = existing.bestLambda;
        updated.oldStage1Mean = existing.stage1Mean;
        updated.oldStage2Mean = existing.stage2Mean;
        updated.newC05BestK = bestNewK;
        updated.newC05Stage1Mean = bestNewMean;
        updated.bestK = existing.bestK;
        updated.bestC = existing.bestC;
        updated.bestLambda = existing.bestLambda;
        updated.stage1Mean = existing.stage1Mean;
        updated.stage2Mean = existing.stage2Mean;
        updated.stage1Updated = isBetterStage1(bestNewMean, bestNewK, TARGET_C,
                existing.stage1Mean, existing.bestK, existing.bestC);

        if (updated.stage1Updated) {
            updated.bestK = bestNewK;
            updated.bestC = TARGET_C;
            updated.stage1Mean = bestNewMean;
            markChosenStage1(out.stage1Rows, bestNewK, TARGET_C);

            List<Sample> fullTrain = buildTrainingWindow(samplesByK.get(bestNewK), testPeriodIdx, bestNewK);
            List<Sample> subTrain = new ArrayList<>(fullTrain.subList(0, SUBTRAIN_SIZE));
            List<Sample> subVal = new ArrayList<>(fullTrain.subList(SUBTRAIN_SIZE, SUBTRAIN_SIZE + SUBVAL_SIZE));
            WindowInfo window = WindowInfo.of(fullTrain, subTrain, subVal);

            double bestStage2 = Double.POSITIVE_INFINITY;
            double bestLambda = Double.NaN;
            for (double lambda : LAMBDA_GRID) {
                Config cfg = buildRcsaaConfig(bestNewK, TARGET_C, lambda);
                ValidationSummary summary = summarizeValidationRcsaa(lanes, params, subTrain, subVal, cfg);
                Stage2CandidateRow row = new Stage2CandidateRow(trialId, testPeriodIdx, window, bestNewK, TARGET_C, lambda, summary);
                out.stage2Rows.add(row);
                if (isBetterStage2(summary.meanRealized, lambda, bestStage2, bestLambda)) {
                    bestStage2 = summary.meanRealized;
                    bestLambda = lambda;
                }
            }
            if (!Double.isFinite(bestLambda)) {
                throw new IllegalStateException("No valid lambda for trial=" + trialId + ", testPeriod=" + testPeriodIdx);
            }
            markChosenStage2(out.stage2Rows, bestLambda);
            updated.bestLambda = bestLambda;
            updated.stage2Mean = bestStage2;

            TrialSolveResult csaaActual = solveActualTrial(samplesByK, lanes, params, testPeriodIdx, bestNewK, TARGET_C, Double.NaN, SolveMode.CSAA);
            TrialSolveResult rcsaaActual = solveActualTrial(samplesByK, lanes, params, testPeriodIdx, bestNewK, TARGET_C, bestLambda, SolveMode.RCSAA);
            out.changedActualRows.add(toMergedTrialRow(trialId, csaaActual, bestNewK, TARGET_C, Double.NaN));
            out.changedActualRows.add(toMergedTrialRow(trialId, rcsaaActual, bestNewK, TARGET_C, bestLambda));
        }

        out.updatedSelection = updated;
        return out;
    }

    private static TrialSolveResult solveActualTrial(Map<Integer, List<Sample>> samplesByK,
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

    private static Map<Integer, ExistingSelection> loadExistingSelections(Path existingRoot) throws Exception {
        Map<Integer, ExistingSelection> out = new HashMap<>();
        Path chunks = existingRoot.resolve("chunks");
        try (var paths = Files.walk(chunks)) {
            paths.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equals("cv_selected_params.csv"))
                    .forEach(p -> {
                        try (BufferedReader br = Files.newBufferedReader(p)) {
                            br.readLine();
                            String line;
                            while ((line = br.readLine()) != null) {
                                String[] parts = line.split(",", -1);
                                ExistingSelection s = new ExistingSelection();
                                s.trialId = Integer.parseInt(parts[0].trim());
                                s.testPeriodIdx = Integer.parseInt(parts[1].trim());
                                s.bestK = Integer.parseInt(parts[2].trim());
                                s.bestC = Double.parseDouble(parts[3].trim());
                                s.bestLambda = Double.parseDouble(parts[4].trim());
                                s.stage1Mean = Double.parseDouble(parts[5].trim());
                                s.stage2Mean = Double.parseDouble(parts[6].trim());
                                out.put(s.trialId, s);
                            }
                        } catch (Exception ex) {
                            throw new RuntimeException("Failed reading " + p.toAbsolutePath(), ex);
                        }
                    });
        }
        return out;
    }

    private static void initCompareCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write("trialId,testPeriodIdx,oldBestK,oldBestC_h,oldBestLambda,oldStage1Mean,oldStage2Mean,newC05BestK,newC05Stage1Mean,isStage1Updated,updatedBestK,updatedBestC_h,updatedBestLambda,updatedStage1Mean,updatedStage2Mean");
            bw.newLine();
        }
    }

    private static void appendCompareRow(Path csv, TrialIncrementResult result) throws Exception {
        UpdatedSelection s = result.updatedSelection;
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardOpenOption.APPEND)) {
            bw.write(String.format(Locale.US,
                    "%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%d,%.10f,%s,%d,%.10f,%.10f,%.10f,%.10f%n",
                    s.trialId, s.testPeriodIdx, s.oldBestK, s.oldBestC, s.oldBestLambda, s.oldStage1Mean, s.oldStage2Mean,
                    s.newC05BestK, s.newC05Stage1Mean, String.valueOf(s.stage1Updated),
                    s.bestK, s.bestC, s.bestLambda, s.stage1Mean, s.stage2Mean));
        }
    }

    private static void initChangedActualCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write(String.join(",",
                    "k1","source_dir","method_name","param","标签","试验ID","测试索引","求解模式","fillMissingDates","standardizeTheta","k1Lag","kernelType","bandwidthH","C_h","lambda","训练集大小","期望目标值","实现目标值","求解时间秒","选中供应商数量","样本外运输成本","样本外现货成本","样本外罚金成本","权重和","权重平方和","有效样本量","最大权重","前5权重和","最大权重相对平均值","Theta距离均值","Theta距离中位数","Theta距离最小值","Theta距离最大值","需求距离均值","需求距离中位数","需求距离最小值","需求距离最大值","权重Theta距离相关系数","权重需求距离相关系数","Theta需求距离相关系数","y二进制向量","选中供应商集合"));
            bw.newLine();
        }
    }

    private static void appendChangedActualRows(Path csv, List<MergedTrialRow> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardOpenOption.APPEND)) {
            for (MergedTrialRow row : rows) {
                bw.write(row.csvLine);
                bw.newLine();
            }
        }
    }

    private static void writeUpdatedSelections(Path csv, Map<Integer, UpdatedSelection> selections, int startTrial, int totalTrials) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write("trialId,testPeriodIdx,bestK,bestC_h,bestLambda,stage1ValMean,stage2ValMean,oldBestK,oldBestC_h,oldBestLambda,oldStage1ValMean,oldStage2ValMean,isUpdatedByC0.5");
            bw.newLine();
            for (int trialId = startTrial; trialId < startTrial + totalTrials; trialId++) {
                UpdatedSelection s = Objects.requireNonNull(selections.get(trialId), "missing updated selection trial=" + trialId);
                bw.write(String.format(Locale.US, "%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%d,%.10f,%.10f,%.10f,%.10f,%s%n",
                        s.trialId, s.testPeriodIdx, s.bestK, s.bestC, s.bestLambda, s.stage1Mean, s.stage2Mean,
                        s.oldBestK, s.oldBestC, s.oldBestLambda, s.oldStage1Mean, s.oldStage2Mean, String.valueOf(s.stage1Updated)));
            }
        }
    }

    private static void writeSummaryTxt(Path txt, int startTrial, int totalTrials, int changedTrials) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(txt)) {
            bw.write("start_trial=" + startTrial);
            bw.newLine();
            bw.write("total_trials=" + totalTrials);
            bw.newLine();
            bw.write("changed_trials=" + changedTrials);
            bw.newLine();
            bw.write("target_C_h=" + TARGET_C);
            bw.newLine();
        }
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

    private static MergedTrialRow toMergedTrialRow(int trialId, TrialSolveResult r, int k, double cH, double lambda) {
        String sourceDir;
        String methodName;
        String param;
        String tag;
        if (r.cfg.solveMode == SolveMode.RCSAA) {
            sourceDir = String.format(Locale.US, "RCSAA_W50_k1=%d_raw_C%.2f_lambda%.2f_C0.5增量", k, cH, lambda);
            methodName = "RCSAA";
            param = String.format(Locale.US, "C_h=%.1f, lambda=%s", cH, trimNum(lambda));
            tag = String.format(Locale.US, "巴西Olist23OD_RCSAA_W50_k1=%d_raw_增量C%.2f_lambda%.2f", k, cH, lambda);
        } else {
            sourceDir = String.format(Locale.US, "CSAA_W50_k1=%d_raw_C%.2f_C0.5增量", k, cH);
            methodName = "CSAA";
            param = String.format(Locale.US, "C_h=%.1f", cH);
            tag = String.format(Locale.US, "巴西Olist23OD_CSAA_W50_k1=%d_raw_增量C%.2f", k, cH);
        }
        int testIdx = r.testPeriodIdx - k;
        String line = String.format(Locale.US,
                "%d,%s,%s,%s,%s,%d,%d,%s,%s,%s,%d,%s,%.6f,%.6f,%s,%d,%.10f,%.10f,%.6f,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,\"%s\",\"%s\"",
                k, sourceDir, methodName, param, tag, trialId, testIdx,
                r.cfg.solveMode.name(), String.valueOf(r.cfg.fillMissingDates), String.valueOf(r.cfg.standardizeTheta),
                r.cfg.k1LagPeriods, r.cfg.kernelType.name(), r.cfg.bandwidthH, cH,
                (r.cfg.solveMode == SolveMode.RCSAA ? String.format(Locale.US, "%.6f", lambda) : "NaN"),
                r.trainSize, r.expected, r.realized, r.solveTimeSec, r.selectedCount,
                r.rec.transportTotalCost, r.rec.spotTotalCost, r.rec.penaltyTotalCost,
                r.diag.sumW, r.diag.sumW2, r.diag.ess, r.diag.top1W, r.diag.top5Wsum, r.diag.maxOverMean,
                r.diag.thetaMean, r.diag.thetaMedian, r.diag.thetaMin, r.diag.thetaMax,
                r.diag.demMean, r.diag.demMedian, r.diag.demMin, r.diag.demMax,
                r.diag.corrWTheta, r.diag.corrWDemand, r.diag.corrThetaDemand,
                r.yBinary.replace("\"", "\"\""), r.selectedCarriers.replace("\"", "\"\""));
        return new MergedTrialRow(line);
    }

    private static String trimNum(double v) {
        if (Math.abs(v - Math.rint(v)) <= 1e-9) {
            return String.format(Locale.US, "%.0f", v);
        }
        return String.format(Locale.US, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
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
            StandardScaler scaler = new StandardScaler();
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
        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);
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
            StandardScaler scaler = new StandardScaler();
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
        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);
        TrialSolveResult out = buildTrialResult(cfg, train, testSample, diag, sol, rec, (ed - st) / 1e9);
        out.expected = weightedMean(empiricalRecourseCosts(params, train, sol.y), train);
        return out;
    }

    private static ValidationSummary summarizeValidationContextual(List<String> lanes,
                                                                  ProcurementParams params,
                                                                  List<Sample> subTrain,
                                                                  List<Sample> subVal,
                                                                  Config cfg) throws Exception {
        ValidationAccumulator acc = new ValidationAccumulator();
        for (Sample val : subVal) {
            acc.add(solveSingleContextual(lanes, params, subTrain, val, cfg));
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
            acc.add(solveSingleRcsaa(lanes, params, subTrain, val, cfg));
        }
        return acc.finish();
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
        int start = testSampleIdx - W;
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
        base.kernelType = Helper.calculateHelper.KernelType.EXPONENTIAL;
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

    private static Config buildCsaaConfig(int k, double c) {
        Config cfg = buildBaseConfig(k, c);
        cfg.solveMode = SolveMode.CSAA;
        return cfg;
    }

    private static Config buildRcsaaConfig(int k, double c, double lambda) {
        Config cfg = buildBaseConfig(k, c);
        cfg.solveMode = SolveMode.RCSAA;
        cfg.lambda = lambda;
        return cfg;
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
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardOpenOption.APPEND)) {
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
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardOpenOption.APPEND)) {
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

    private static double[] buildBaselineDemand(List<Basic.PeriodData> periods) {
        int jSize = periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (Basic.PeriodData p : periods) {
            for (int j = 0; j < jSize; j++) sum[j] += p.demandSum[j];
        }
        double denom = Math.max(1, periods.size());
        for (int j = 0; j < jSize; j++) sum[j] /= denom;
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

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static class ExistingSelection {
        int trialId;
        int testPeriodIdx;
        int bestK;
        double bestC;
        double bestLambda;
        double stage1Mean;
        double stage2Mean;
    }

    private static class UpdatedSelection {
        int trialId;
        int testPeriodIdx;
        int oldBestK;
        double oldBestC;
        double oldBestLambda;
        double oldStage1Mean;
        double oldStage2Mean;
        int newC05BestK;
        double newC05Stage1Mean;
        boolean stage1Updated;
        int bestK;
        double bestC;
        double bestLambda;
        double stage1Mean;
        double stage2Mean;
    }

    private static class TrialIncrementResult {
        int trialId;
        int testPeriodIdx;
        List<Stage1CandidateRow> stage1Rows;
        List<Stage2CandidateRow> stage2Rows;
        List<MergedTrialRow> changedActualRows;
        UpdatedSelection updatedSelection;
    }

    private static class MergedTrialRow {
        String csvLine;

        MergedTrialRow(String csvLine) {
            this.csvLine = csvLine;
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
            if (one == null || !Double.isFinite(one.realized)) return;
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
}
