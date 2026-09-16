package Test;

import Basic.CovariateVector;
import Basic.Data;
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
import Model.SAAModel;
import Model.Solution;
import Model.SolveMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class BrazilOlistExactRCSAACVWorkflow {

    // Manual entrypoint settings.
    // On another machine, edit these values and run this main directly.
    private static final int MANUAL_START_TRIAL = 0;
    private static final int MANUAL_MAX_TRIALS = 1;
    private static final String MANUAL_OUT_ROOT =
            "analysis/巴西数据分析/新版_purchase时间/输出/04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果_ExactRCSAA枚举_manual_trial00";

    private static final int[] K_GRID = {1, 2, 3};
    private static final double[] LAMBDA_GRID = {0.01, 0.05, 0.1, 1, 5, 10, 50, 100};
    private static final int SUBTRAIN_SIZE = 35;
    private static final int SUBVAL_SIZE = 15;
    private static final int W = 50;
    private static final int K_MAX = 3;

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = Paths.get(args.length > 0 ? args[0]
                : "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
        int numCarriers = (args.length > 1 ? Integer.parseInt(args[1]) : 10);
        Path oldRoot = Paths.get(args.length > 2 ? args[2]
                : "analysis/巴西数据分析/新版_purchase时间/输出/04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果");
        Path outRoot = Paths.get(args.length > 3 ? args[3]
                : "analysis/巴西数据分析/新版_purchase时间/输出/04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果_ExactRCSAA枚举");
        if (args.length <= 3) {
            outRoot = Paths.get(MANUAL_OUT_ROOT);
        }
        int startTrial = (args.length > 4 ? Integer.parseInt(args[4]) : MANUAL_START_TRIAL);
        int maxTrials = (args.length > 5 ? Integer.parseInt(args[5]) : MANUAL_MAX_TRIALS);

        Path oldRawRoot = oldRoot.resolve("01_原始滚动CV选参输出");
        Path oldFinalRoot = oldRoot.resolve("02_最终结果_4方法");
        Path oldSelectionCsv = oldRawRoot.resolve("cv_selected_params.csv");
        Path oldSelectedActualCsv = oldRawRoot.resolve("cv_selected_actual_trials.csv");
        Path oldFinalDetailCsv = oldFinalRoot.resolve("01_正式最终结果_4方法").resolve("四种方法最终逐trial明细.csv");
        Path oldFinalSummaryCsv = oldFinalRoot.resolve("01_正式最终结果_4方法").resolve("四种方法最终汇总结果.csv");
        Path oldFinalParamCsv = oldFinalRoot.resolve("02_每个trial最优参数").resolve("每个trial最终选中参数表.csv");

        Files.createDirectories(outRoot);
        Path rawOutRoot = outRoot.resolve("01_原始滚动CV选参输出");
        Path finalOutRoot = outRoot.resolve("02_最终结果_4方法");
        Path finalCsvRoot = finalOutRoot.resolve("01_正式最终结果_4方法");
        Path finalParamRoot = finalOutRoot.resolve("02_每个trial最优参数");
        Files.createDirectories(rawOutRoot);
        Files.createDirectories(finalCsvRoot);
        Files.createDirectories(finalParamRoot);

        Path outStage2Csv = rawOutRoot.resolve("cv_stage2_lambda_candidates.csv");
        Path outSelectionCsv = rawOutRoot.resolve("cv_selected_params.csv");
        Path outSelectedActualCsv = rawOutRoot.resolve("cv_selected_actual_trials.csv");
        Path outFinalDetailCsv = finalCsvRoot.resolve("四种方法最终逐trial明细.csv");
        Path outFinalSummaryCsv = finalCsvRoot.resolve("四种方法最终汇总结果.csv");
        Path outFinalParamCsv = finalParamRoot.resolve("每个trial最终选中参数表.csv");
        Path outNote = finalOutRoot.resolve("说明.txt");

        Files.copy(oldRawRoot.resolve("cv_stage1_k_c_candidates.csv"), rawOutRoot.resolve("cv_stage1_k_c_candidates.csv"), StandardCopyOption.REPLACE_EXISTING);
        initStage2Csv(outStage2Csv);
        initSelectionCsv(outSelectionCsv);
        initSelectedActualCsv(outSelectedActualCsv);

        List<SelectedParamRow> oldSelections = loadSelectedParamRows(oldSelectionCsv);
        List<SelectedActualCsvRow> oldActualRows = loadSelectedActualRows(oldSelectedActualCsv);
        List<GenericCsvRow> oldFinalDetailRows = loadGenericCsv(oldFinalDetailCsv);
        List<GenericCsvRow> oldFinalParamRows = loadGenericCsv(oldFinalParamCsv);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = weekly.laneNames;
        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : K_GRID) {
            Config cfg = buildBaseConfig(k, 1.0);
            SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(weekly.periods, lanes, cfg);
            samplesByK.put(k, br.samples);
        }

        double[] dBase = buildBaselineDemand(weekly.periods);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, buildBaseConfig(K_MAX, 1.0));

        oldSelections.sort(Comparator.comparingInt(r -> r.trialId));
        int endTrial = Math.min(oldSelections.size(), startTrial + maxTrials);
        Map<Integer, SelectedParamRow> newSelections = new LinkedHashMap<>();
        Map<Integer, SelectedActualCsvRow> newExactActual = new LinkedHashMap<>();
        Map<Integer, SelectedActualCsvRow> oldCsaaActual = indexSelectedActual(oldActualRows, "CSAA");

        System.out.println("weeklyCsv: " + weeklyCsv.toAbsolutePath());
        System.out.println("oldRoot: " + oldRoot.toAbsolutePath());
        System.out.println("outRoot: " + outRoot.toAbsolutePath());
        System.out.println("trialRange: [" + startTrial + "," + endTrial + ")");

        for (int idx = startTrial; idx < endTrial; idx++) {
            SelectedParamRow oldSel = oldSelections.get(idx);
            int trialId = oldSel.trialId;
            int k = oldSel.bestK;
            double c = oldSel.bestCH;
            int testPeriodIdx = oldSel.testPeriodIdx;

            List<Sample> fullTrain = buildTrainingWindow(samplesByK.get(k), testPeriodIdx, k);
            List<Sample> subTrain = new ArrayList<>(fullTrain.subList(0, SUBTRAIN_SIZE));
            List<Sample> subVal = new ArrayList<>(fullTrain.subList(SUBTRAIN_SIZE, W));
            WindowInfo window = WindowInfo.of(fullTrain, subTrain, subVal);
            ExactEnumerationCache subTrainCache = ExactEnumerationCache.build(params, subTrain);

            List<Stage2Row> stage2Rows = new ArrayList<>();
            double bestLambda = Double.NaN;
            double bestStage2Mean = Double.POSITIVE_INFINITY;
            for (double lambda : LAMBDA_GRID) {
                System.out.println(String.format(Locale.US,
                        "trial=%d stage2 lambda=%.6f start", trialId, lambda));
                Config cfg = buildExactRcsaaConfig(k, c, lambda);
                ValidationSummary summary = summarizeValidationRcsaaExact(lanes, params, subTrain, subVal, cfg, subTrainCache);
                stage2Rows.add(new Stage2Row(trialId, testPeriodIdx, window, k, c, lambda, summary));
                System.out.println(String.format(Locale.US,
                        "trial=%d stage2 lambda=%.6f done meanRealized=%.10f meanExpected=%.10f",
                        trialId, lambda, summary.meanRealized, summary.meanExpected));
                if (isBetterStage2(summary.meanRealized, lambda, bestStage2Mean, bestLambda)) {
                    bestStage2Mean = summary.meanRealized;
                    bestLambda = lambda;
                }
            }
            markChosenStage2(stage2Rows, bestLambda);
            appendStage2Rows(outStage2Csv, stage2Rows);

            SelectedParamRow newSel = new SelectedParamRow(trialId, testPeriodIdx, k, c, bestLambda, oldSel.stage1ValMean, bestStage2Mean);
            newSelections.put(trialId, newSel);
            appendSelection(outSelectionCsv, newSel);

            SelectedActualCsvRow oldCsaa = oldCsaaActual.get(trialId);
            if (oldCsaa != null) {
                appendRawCsvLine(outSelectedActualCsv, oldCsaa.rawLine);
            }

            TrialSolveResult exactSolve = solveSelectedActual(samplesByK, lanes, params, testPeriodIdx, k, c, bestLambda, SolveMode.RCSAA);
            SelectedActualCsvRow exactActual = toSelectedActualCsvRow(trialId, exactSolve, k, c, bestLambda);
            newExactActual.put(trialId, exactActual);
            appendRawCsvLine(outSelectedActualCsv, exactActual.rawLine);

            System.out.println(String.format(Locale.US,
                    "done trial=%d k=%d C=%.6f lambda=%.6f stage2=%.10f actual=%.10f",
                    trialId, k, c, bestLambda, bestStage2Mean, exactActual.realizedObj));
        }

        writeFinalParamCsvFromTemplate(oldFinalParamRows, newSelections, outFinalParamCsv);
        List<GenericCsvRow> finalDetailRows = rewriteFinalDetailFromTemplate(oldFinalDetailRows, newSelections, newExactActual);
        writeGenericCsv(outFinalDetailCsv, finalDetailRows);
        writeSummaryFromFinalDetail(finalDetailRows, outFinalSummaryCsv);
        Files.copy(oldFinalSummaryCsv, finalCsvRoot.resolve("四种方法最终汇总结果_旧版备份.csv"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(outNote,
                "RCSAA 行已替换为 ENUMERATE_EXACT 结果；Mean/SAA/CSAA 沿用旧 04 最终结果模板。", StandardCharsets.UTF_8);

        System.out.println("done: " + outRoot.toAbsolutePath());
    }

    private static Map<Integer, SelectedActualCsvRow> indexSelectedActual(List<SelectedActualCsvRow> rows, String methodName) {
        Map<Integer, SelectedActualCsvRow> out = new HashMap<>();
        for (SelectedActualCsvRow row : rows) {
            if (methodName.equals(row.methodName)) {
                out.put(row.trialId, row);
            }
        }
        return out;
    }

    private static boolean isBetterStage2(double candMean, double candLambda, double bestMean, double bestLambda) {
        if (!Double.isFinite(candMean)) return false;
        if (!Double.isFinite(bestMean)) return true;
        if (candMean < bestMean - 1e-9) return true;
        return Math.abs(candMean - bestMean) <= 1e-9 && candLambda < bestLambda;
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

    private static Sample getTestSample(List<Sample> samplesForK, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        if (testSampleIdx < 0 || testSampleIdx >= samplesForK.size()) {
            throw new IllegalArgumentException("Invalid test sample index for testPeriod=" + testPeriodIdx + ", k=" + k);
        }
        return samplesForK.get(testSampleIdx);
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

    private static Config buildCsaaConfig(int k, double c) {
        Config cfg = buildBaseConfig(k, c);
        cfg.solveMode = SolveMode.CSAA;
        return cfg;
    }

    private static Config buildExactRcsaaConfig(int k, double c, double lambda) {
        Config cfg = buildBaseConfig(k, c);
        cfg.solveMode = SolveMode.RCSAA;
        cfg.lambda = lambda;
        cfg.rcsaaSolverVariant = RCSAASolverVariant.ENUMERATE_EXACT;
        return cfg;
    }

    private static TrialSolveResult solveSelectedActual(Map<Integer, List<Sample>> samplesByK,
                                                        List<String> lanes,
                                                        ProcurementParams params,
                                                        int testPeriodIdx,
                                                        int k,
                                                        double cH,
                                                        double lambda,
                                                        SolveMode solveMode) throws Exception {
        Config cfg = (solveMode == SolveMode.RCSAA) ? buildExactRcsaaConfig(k, cH, lambda) : buildCsaaConfig(k, cH);
        List<Sample> fullTrain = buildTrainingWindow(samplesByK.get(k), testPeriodIdx, k);
        Sample testSample = getTestSample(samplesByK.get(k), testPeriodIdx, k);
        if (solveMode == SolveMode.RCSAA) {
            ExactEnumerationCache cache = ExactEnumerationCache.build(params, fullTrain);
            return solveSingleRcsaaExact(lanes, params, fullTrain, testSample, cfg, cache, cache.buildTimeSec);
        }
        return solveSingleContextual(lanes, params, fullTrain, testSample, cfg);
    }

    private static ValidationSummary summarizeValidationRcsaaExact(List<String> lanes,
                                                                  ProcurementParams params,
                                                                  List<Sample> subTrain,
                                                                  List<Sample> subVal,
                                                                  Config cfg,
                                                                  ExactEnumerationCache cache) throws Exception {
        ValidationAccumulator acc = new ValidationAccumulator();
        for (int i = 0; i < subVal.size(); i++) {
            Sample val = subVal.get(i);
            if ((i + 1) == 1 || (i + 1) == subVal.size() || (i + 1) % 5 == 0) {
                System.out.println(String.format(Locale.US,
                        "  validation progress=%d/%d", i + 1, subVal.size()));
            }
            acc.add(solveSingleRcsaaExact(lanes, params, subTrain, val, cfg, cache, 0.0));
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

        SAAModel model = new SAAModel();
        long st = System.nanoTime();
        Solution sol = model.solve(new Data(lanes, train, thetaNow, params), cfg, null);
        long ed = System.nanoTime();
        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);
        return buildTrialResult(cfg, train, testSample, diag, sol, rec, (ed - st) / 1e9);
    }

    private static TrialSolveResult solveSingleRcsaaExact(List<String> lanes,
                                                          ProcurementParams params,
                                                          List<Sample> trainRaw,
                                                          Sample testSample,
                                                          Config cfg,
                                                          ExactEnumerationCache cache,
                                                          double additionalSolveSec) throws Exception {
        System.out.println(String.format(Locale.US,
                "    exact solve start testPeriod=%d lambda=%.6f train=%d",
                testSample.period.tIndex, cfg.lambda, trainRaw.size()));
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
        double solveTimeSec = (ed - st) / 1e9 + additionalSolveSec;
        System.out.println(String.format(Locale.US,
                "    exact solve done testPeriod=%d lambda=%.6f sec=%.3f obj=%.10f",
                testSample.period.tIndex, cfg.lambda, solveTimeSec, exact.objective));
        Solution sol = new Solution(exact.objective, exact.bestY, solveTimeSec);
        BatchRunner.RecourseEvaluator.RecourseEval rec = BatchRunner.RecourseEvaluator.evaluate(params, sol.y, dTest);

        TrialSolveResult out = buildTrialResult(cfg, train, testSample, diag, sol, rec, solveTimeSec);
        out.expected = exact.meanCost;
        return out;
    }

    private static TrialSolveResult buildTrialResult(Config cfg,
                                                     List<Sample> train,
                                                     Sample testSample,
                                                     BatchRunner.TrialDiag diag,
                                                     Solution sol,
                                                     BatchRunner.RecourseEvaluator.RecourseEval rec,
                                                     double solveTimeSec) {
        TrialSolveResult out = new TrialSolveResult();
        out.cfg = cfg;
        out.testPeriodIdx = testSample.period.tIndex;
        out.trainSize = train.size();
        out.expected = sol.objValue;
        out.realized = rec.objValue;
        out.solveTimeSec = solveTimeSec;
        out.selectedCount = countSelected(sol.y);
        out.yBinary = yBinary(sol.y);
        out.selectedCarriers = selectedCarriers(sol.y);
        out.sol = sol;
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

    private static final class ExactEnumerationCache {
        private static final double FORMAL_WEIGHT_FLOOR = 1e-8;

        final double buildTimeSec;
        final List<double[]> feasibleY;
        final double[][] qMatrix;

        private ExactEnumerationCache(double buildTimeSec, List<double[]> feasibleY, double[][] qMatrix) {
            this.buildTimeSec = buildTimeSec;
            this.feasibleY = feasibleY;
            this.qMatrix = qMatrix;
        }

        static ExactEnumerationCache build(ProcurementParams params, List<Sample> train) throws Exception {
            List<double[]> feasibleY = enumerateFeasibleSelections(params.I, params.alpha, params.beta);
            double[][] qMatrix = new double[feasibleY.size()][train.size()];
            long t0 = System.nanoTime();
            System.out.println(String.format(Locale.US,
                    "    exact-cache build start feasibleY=%d scenarios=%d",
                    feasibleY.size(), train.size()));
            for (int yi = 0; yi < feasibleY.size(); yi++) {
                double[] y = feasibleY.get(yi);
                for (int s = 0; s < train.size(); s++) {
                    qMatrix[yi][s] = BatchRunner.RecourseEvaluator.evaluate(params, y, train.get(s).demand().clone()).objValue;
                }
                if ((yi + 1) % 100 == 0 || yi + 1 == feasibleY.size()) {
                    System.out.println(String.format(Locale.US,
                            "    exact-cache build progress=%d/%d elapsedSec=%.3f",
                            yi + 1, feasibleY.size(), BrazilOlistExactRCSAACVWorkflow.secondsBetween(t0, System.nanoTime())));
                }
            }
            double buildTimeSec = secondsBetween(t0, System.nanoTime());
            System.out.println(String.format(Locale.US,
                    "    exact-cache build done feasibleY=%d scenarios=%d totalSec=%.3f",
                    feasibleY.size(), train.size(), buildTimeSec));
            return new ExactEnumerationCache(buildTimeSec, feasibleY, qMatrix);
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
            if (bestIdx < 0) {
                throw new IllegalStateException("Exact enumeration found no feasible first-stage solution.");
            }
            return new SolveResult(bestObj, bestMean, feasibleY.get(bestIdx).clone(), qMatrix[bestIdx].clone());
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
            for (int i = 0; i < pi.length; i++) pi[i] /= sum;
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
            final double[] scenarioCosts;

            SolveResult(double objective, double meanCost, double[] bestY, double[] scenarioCosts) {
                this.objective = objective;
                this.meanCost = meanCost;
                this.bestY = bestY;
                this.scenarioCosts = scenarioCosts;
            }
        }
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

    private static SelectedActualCsvRow toSelectedActualCsvRow(int trialId,
                                                               TrialSolveResult r,
                                                               int k,
                                                               double cH,
                                                               double lambda) {
        String methodName = (r.cfg.solveMode == SolveMode.RCSAA) ? "RCSAA" : "CSAA";
        String lambdaText = (r.cfg.solveMode == SolveMode.RCSAA) ? String.format(Locale.US, "%.10f", lambda) : "";
        String raw = String.format(Locale.US,
                "%d,%d,%s,%d,%.10f,%s,%s,%s,%s,%d,%s,%.10f,%d," +
                        "%.10f,%.10f,%.6f,%d," +
                        "%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,%.10f," +
                        "%.10f,%.10f,%.10f,\"%s\",\"%s\"",
                trialId, r.testPeriodIdx, methodName, k, cH, lambdaText,
                r.cfg.solveMode.name(), String.valueOf(r.cfg.fillMissingDates), String.valueOf(r.cfg.standardizeTheta),
                r.cfg.k1LagPeriods, r.cfg.kernelType.name(), r.cfg.bandwidthH, r.trainSize,
                r.expected, r.realized, r.solveTimeSec, r.selectedCount,
                r.rec.transportTotalCost, r.rec.spotTotalCost, r.rec.penaltyTotalCost,
                r.diag.sumW, r.diag.sumW2, r.diag.ess, r.diag.top1W, r.diag.top5Wsum, r.diag.maxOverMean,
                r.diag.thetaMean, r.diag.thetaMedian, r.diag.thetaMin, r.diag.thetaMax,
                r.diag.demMean, r.diag.demMedian, r.diag.demMin, r.diag.demMax,
                r.diag.corrWTheta, r.diag.corrWDemand, r.diag.corrThetaDemand,
                r.yBinary.replace("\"", "\"\""), r.selectedCarriers.replace("\"", "\"\""));
        return new SelectedActualCsvRow(trialId, methodName, r.testPeriodIdx, r.realized, raw);
    }

    private static void initStage2Csv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
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

    private static void initSelectionCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write("trialId,testPeriodIdx,bestK,bestC_h,bestLambda,stage1ValMean,stage2ValMean");
            bw.newLine();
        }
    }

    private static void initSelectedActualCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write(String.join(",",
                    "trialId", "actual_test_period", "method_name",
                    "selected_k", "selected_C_h", "selected_lambda",
                    "solve_mode", "fillMissingDates", "standardizeTheta", "k1Lag", "kernelType",
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

    private static void appendStage2Rows(Path csv, List<Stage2Row> rows) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
            for (Stage2Row row : rows) {
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

    private static void markChosenStage2(List<Stage2Row> rows, double bestLambda) {
        for (Stage2Row row : rows) row.chosen = Math.abs(row.lambda - bestLambda) <= 1e-9;
    }

    private static void appendSelection(Path csv, SelectedParamRow row) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
            bw.write(String.format(Locale.US, "%d,%d,%d,%.10f,%.10f,%.10f,%.10f%n",
                    row.trialId, row.testPeriodIdx, row.bestK, row.bestCH, row.bestLambda, row.stage1ValMean, row.stage2ValMean));
        }
    }

    private static void appendRawCsvLine(Path csv, String line) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND)) {
            bw.write(line);
            bw.newLine();
        }
    }

    private static List<SelectedParamRow> loadSelectedParamRows(Path csv) throws Exception {
        List<SelectedParamRow> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> h = headerMap(parseCsvLine(br.readLine()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parseCsvLine(line);
                out.add(new SelectedParamRow(
                        parseInt(f.get(require(h, "trialId"))),
                        parseInt(f.get(require(h, "testPeriodIdx"))),
                        parseInt(f.get(require(h, "bestK"))),
                        parseDouble(f.get(require(h, "bestC_h"))),
                        parseDouble(f.get(require(h, "bestLambda"))),
                        parseDouble(f.get(require(h, "stage1ValMean"))),
                        parseDouble(f.get(require(h, "stage2ValMean")))));
            }
        }
        return out;
    }

    private static List<SelectedActualCsvRow> loadSelectedActualRows(Path csv) throws Exception {
        List<SelectedActualCsvRow> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> h = headerMap(parseCsvLine(br.readLine()));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> f = parseCsvLine(line);
                out.add(new SelectedActualCsvRow(
                        parseInt(f.get(require(h, "trialId"))),
                        f.get(require(h, "method_name")),
                        parseInt(f.get(require(h, "actual_test_period"))),
                        parseDouble(f.get(require(h, "realized_obj"))),
                        line));
            }
        }
        return out;
    }

    private static List<GenericCsvRow> loadGenericCsv(Path csv) throws Exception {
        List<GenericCsvRow> out = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            List<String> headerCols = parseCsvLine(br.readLine());
            Map<String, Integer> h = headerMap(headerCols);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                out.add(new GenericCsvRow(h, parseCsvLine(line)));
            }
        }
        return out;
    }

    private static void writeGenericCsv(Path csv, List<GenericCsvRow> rows) throws Exception {
        if (rows.isEmpty()) return;
        List<String> headers = new ArrayList<>(rows.get(0).header.keySet());
        try (BufferedWriter bw = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            bw.write(String.join(",", headers));
            bw.newLine();
            for (GenericCsvRow row : rows) {
                List<String> vals = new ArrayList<>();
                for (String key : headers) vals.add(csvEscape(row.get(key)));
                bw.write(String.join(",", vals));
                bw.newLine();
            }
        }
    }

    private static void writeFinalParamCsvFromTemplate(List<GenericCsvRow> template,
                                                       Map<Integer, SelectedParamRow> newSelections,
                                                       Path outCsv) throws Exception {
        for (GenericCsvRow row : template) {
            int trialId = parseInt(row.get("trialId"));
            SelectedParamRow sel = newSelections.get(trialId);
            if (sel != null) {
                row.set("RCSAA_best_k", String.valueOf(sel.bestK));
                row.set("RCSAA_best_C_h", fmt10(sel.bestCH));
                row.set("RCSAA_best_lambda", fmt10(sel.bestLambda));
                row.set("stage2_val_mean", fmt10(sel.stage2ValMean));
            }
        }
        writeGenericCsv(outCsv, template);
    }

    private static List<GenericCsvRow> rewriteFinalDetailFromTemplate(List<GenericCsvRow> template,
                                                                      Map<Integer, SelectedParamRow> newSelections,
                                                                      Map<Integer, SelectedActualCsvRow> newExactActual) {
        for (GenericCsvRow row : template) {
            if (!"RCSAA".equals(row.get("final_method_name"))) continue;
            int trialId = parseInt(row.get("trialId"));
            SelectedParamRow sel = newSelections.get(trialId);
            SelectedActualCsvRow actual = newExactActual.get(trialId);
            if (sel == null || actual == null) continue;
            GenericCsvRow src = parseSelectedActualAsFinal(actual.rawLine);
            row.set("selected_k", String.valueOf(sel.bestK));
            row.set("selected_C_h", fmt10(sel.bestCH));
            row.set("selected_lambda", fmt10(sel.bestLambda));
            row.set("stage1_val_mean", fmt10(sel.stage1ValMean));
            row.set("stage2_val_mean", fmt10(sel.stage2ValMean));
            for (String key : Arrays.asList("actual_test_period","expected_obj","realized_obj","solve_time_sec","selected_count",
                    "oos_transport_cost","oos_spot_cost","oos_penalty_cost","ESS","top1W","top5Wsum")) {
                row.set(key, src.get(key));
            }
            row.set("source", "04_exact_rcsaa_cv_selected_actual_trials");
        }
        return template;
    }

    private static GenericCsvRow parseSelectedActualAsFinal(String line) {
        Map<String, Integer> h = headerMap(parseCsvLine("trialId,actual_test_period,method_name,selected_k,selected_C_h,selected_lambda,solve_mode,fillMissingDates,standardizeTheta,k1Lag,kernelType,bandwidthH,train_size,expected_obj,realized_obj,solve_time_sec,selected_count,oos_transport_cost,oos_spot_cost,oos_penalty_cost,sumW,sumW2,ESS,top1W,top5Wsum,maxW_over_meanW,thetaDist_mean,thetaDist_median,thetaDist_min,thetaDist_max,demandDist_mean,demandDist_median,demandDist_min,demandDist_max,corrW_thetaDist,corrW_demandDist,corrTheta_demandDist,yBinary,selectedCarriers"));
        return new GenericCsvRow(h, parseCsvLine(line));
    }

    private static void writeSummaryFromFinalDetail(List<GenericCsvRow> rows, Path outCsv) throws Exception {
        Map<String, List<GenericCsvRow>> byMethod = new LinkedHashMap<>();
        for (GenericCsvRow row : rows) byMethod.computeIfAbsent(row.get("final_method_name"), k -> new ArrayList<>()).add(row);
        try (BufferedWriter bw = Files.newBufferedWriter(outCsv, StandardCharsets.UTF_8)) {
            bw.write("final_method_name,param_strategy,trial_count,mean_expected_obj,std_expected_obj,min_expected_obj,p20_expected_obj,p50_expected_obj,p75_expected_obj,p80_expected_obj,p95_expected_obj,max_expected_obj,mean_realized_obj,std_realized_obj,min_realized_obj,p20_realized_obj,p50_realized_obj,p75_realized_obj,p80_realized_obj,p95_realized_obj,max_realized_obj,mean_transport_cost,mean_spot_cost,mean_penalty_cost,mean_solve_time_sec,mean_selected_count,mean_ess");
            bw.newLine();
            for (Map.Entry<String, List<GenericCsvRow>> e : byMethod.entrySet()) {
                List<Double> exp = extractMetric(e.getValue(), "expected_obj");
                List<Double> real = extractMetric(e.getValue(), "realized_obj");
                Stats sExp = Stats.of(exp);
                Stats sReal = Stats.of(real);
                String paramStrategy = e.getValue().get(0).get("param_strategy");
                bw.write(String.format(Locale.US,
                        "%s,%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        e.getKey(), paramStrategy, e.getValue().size(),
                        sExp.mean, sExp.std, sExp.min, sExp.p20, sExp.p50, sExp.p75, sExp.p80, sExp.p95, sExp.max,
                        sReal.mean, sReal.std, sReal.min, sReal.p20, sReal.p50, sReal.p75, sReal.p80, sReal.p95, sReal.max,
                        mean(extractMetric(e.getValue(), "oos_transport_cost")),
                        mean(extractMetric(e.getValue(), "oos_spot_cost")),
                        mean(extractMetric(e.getValue(), "oos_penalty_cost")),
                        mean(extractMetric(e.getValue(), "solve_time_sec")),
                        mean(extractMetric(e.getValue(), "selected_count")),
                        mean(extractMetric(e.getValue(), "ESS"))));
            }
        }
    }

    private static List<Double> extractMetric(List<GenericCsvRow> rows, String key) {
        List<Double> out = new ArrayList<>();
        for (GenericCsvRow row : rows) out.add(parseDouble(row.get(key)));
        return out;
    }

    private static double mean(List<Double> vals) {
        if (vals.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (double v : vals) sum += v;
        return sum / vals.size();
    }

    private static double sampleStd(List<Double> vals, double mean) {
        if (vals.size() <= 1) return 0.0;
        double sumSq = 0.0;
        for (double v : vals) { double d = v - mean; sumSq += d * d; }
        return Math.sqrt(sumSq / (vals.size() - 1));
    }

    private static double percentile(List<Double> sortedVals, double p) {
        if (sortedVals.isEmpty()) return Double.NaN;
        if (sortedVals.size() == 1) return sortedVals.get(0);
        double pos = (sortedVals.size() - 1) * p;
        int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
        if (lo == hi) return sortedVals.get(lo);
        double frac = pos - lo;
        return sortedVals.get(lo) * (1 - frac) + sortedVals.get(hi) * frac;
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
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            String key = cols.get(i);
            if (key != null) key = key.replace("\uFEFF", "").trim();
            out.put(key, i);
        }
        return out;
    }

    private static int require(Map<String, Integer> header, String name) {
        Integer idx = header.get(name);
        if (idx == null) throw new IllegalArgumentException("Missing column: " + name);
        return idx;
    }

    private static int parseInt(String s) { return Integer.parseInt(s.trim()); }

    private static double parseDouble(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception ex) { return Double.NaN; }
    }

    private static String fmt10(double v) { return String.format(Locale.US, "%.10f", v); }

    private static double secondsBetween(long start, long end) {
        return (end - start) / 1e9;
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        if (!s.contains(",") && !s.contains("\"")) return s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static final class SelectedParamRow {
        final int trialId, testPeriodIdx, bestK;
        final double bestCH, bestLambda, stage1ValMean, stage2ValMean;
        SelectedParamRow(int trialId, int testPeriodIdx, int bestK, double bestCH, double bestLambda, double stage1ValMean, double stage2ValMean) {
            this.trialId = trialId; this.testPeriodIdx = testPeriodIdx; this.bestK = bestK; this.bestCH = bestCH; this.bestLambda = bestLambda; this.stage1ValMean = stage1ValMean; this.stage2ValMean = stage2ValMean;
        }
    }

    private static final class SelectedActualCsvRow {
        final int trialId; final String methodName; final int actualTestPeriod; final double realizedObj; final String rawLine;
        SelectedActualCsvRow(int trialId, String methodName, int actualTestPeriod, double realizedObj, String rawLine) {
            this.trialId = trialId; this.methodName = methodName; this.actualTestPeriod = actualTestPeriod; this.realizedObj = realizedObj; this.rawLine = rawLine;
        }
    }

    private static final class GenericCsvRow {
        final Map<String, Integer> header; final List<String> values;
        GenericCsvRow(Map<String, Integer> header, List<String> values) { this.header = new LinkedHashMap<>(header); this.values = new ArrayList<>(values); }
        String get(String key) { return values.get(require(header, key)); }
        void set(String key, String value) { values.set(require(header, key), value); }
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

    private static final class Stage2Row {
        final int trialId, testPeriodIdx, k; final WindowInfo window; final double cH, lambda; final ValidationSummary summary; boolean chosen;
        Stage2Row(int trialId, int testPeriodIdx, WindowInfo window, int k, double cH, double lambda, ValidationSummary summary) {
            this.trialId = trialId; this.testPeriodIdx = testPeriodIdx; this.window = window; this.k = k; this.cH = cH; this.lambda = lambda; this.summary = summary;
        }
    }

    private static final class TrialSolveResult {
        Config cfg; int testPeriodIdx; int trainSize; double expected; double realized; double solveTimeSec; int selectedCount; String yBinary; String selectedCarriers; Solution sol; BatchRunner.RecourseEvaluator.RecourseEval rec; BatchRunner.TrialDiag diag;
    }

    private static final class ValidationSummary {
        int validCount; double meanExpected; double meanRealized; double meanSolveTimeSec; double meanSelectedCount; double meanTransport; double meanSpot; double meanPenalty;
        double meanSumW; double meanSumW2; double meanEss; double meanTop1W; double meanTop5Wsum; double meanMaxOverMean;
        double meanThetaMean; double meanThetaMedian; double meanThetaMin; double meanThetaMax; double meanDemandMean; double meanDemandMedian; double meanDemandMin; double meanDemandMax; double meanCorrWTheta; double meanCorrWDemand; double meanCorrThetaDemand;
    }

    private static final class ValidationAccumulator {
        int validCount; double sumExpected,sumRealized,sumSolveTimeSec,sumSelectedCount,sumTransport,sumSpot,sumPenalty,sumSumW,sumSumW2,sumEss,sumTop1W,sumTop5Wsum,sumMaxOverMean,sumThetaMean,sumThetaMedian,sumThetaMin,sumThetaMax,sumDemandMean,sumDemandMedian,sumDemandMin,sumDemandMax,sumCorrWTheta,sumCorrWDemand,sumCorrThetaDemand;
        void add(TrialSolveResult one){ if(one==null||!Double.isFinite(one.realized)) return; validCount++; sumExpected+=one.expected; sumRealized+=one.realized; sumSolveTimeSec+=one.solveTimeSec; sumSelectedCount+=one.selectedCount; sumTransport+=one.rec.transportTotalCost; sumSpot+=one.rec.spotTotalCost; sumPenalty+=one.rec.penaltyTotalCost; sumSumW+=one.diag.sumW; sumSumW2+=one.diag.sumW2; sumEss+=one.diag.ess; sumTop1W+=one.diag.top1W; sumTop5Wsum+=one.diag.top5Wsum; sumMaxOverMean+=one.diag.maxOverMean; sumThetaMean+=one.diag.thetaMean; sumThetaMedian+=one.diag.thetaMedian; sumThetaMin+=one.diag.thetaMin; sumThetaMax+=one.diag.thetaMax; sumDemandMean+=one.diag.demMean; sumDemandMedian+=one.diag.demMedian; sumDemandMin+=one.diag.demMin; sumDemandMax+=one.diag.demMax; sumCorrWTheta+=one.diag.corrWTheta; sumCorrWDemand+=one.diag.corrWDemand; sumCorrThetaDemand+=one.diag.corrThetaDemand; }
        ValidationSummary finish(){ ValidationSummary out=new ValidationSummary(); out.validCount=validCount; if(validCount==0){ out.meanExpected=Double.POSITIVE_INFINITY; out.meanRealized=Double.POSITIVE_INFINITY; return out; } double d=validCount; out.meanExpected=sumExpected/d; out.meanRealized=sumRealized/d; out.meanSolveTimeSec=sumSolveTimeSec/d; out.meanSelectedCount=sumSelectedCount/d; out.meanTransport=sumTransport/d; out.meanSpot=sumSpot/d; out.meanPenalty=sumPenalty/d; out.meanSumW=sumSumW/d; out.meanSumW2=sumSumW2/d; out.meanEss=sumEss/d; out.meanTop1W=sumTop1W/d; out.meanTop5Wsum=sumTop5Wsum/d; out.meanMaxOverMean=sumMaxOverMean/d; out.meanThetaMean=sumThetaMean/d; out.meanThetaMedian=sumThetaMedian/d; out.meanThetaMin=sumThetaMin/d; out.meanThetaMax=sumThetaMax/d; out.meanDemandMean=sumDemandMean/d; out.meanDemandMedian=sumDemandMedian/d; out.meanDemandMin=sumDemandMin/d; out.meanDemandMax=sumDemandMax/d; out.meanCorrWTheta=sumCorrWTheta/d; out.meanCorrWDemand=sumCorrWDemand/d; out.meanCorrThetaDemand=sumCorrThetaDemand/d; return out; }
    }

    private static final class Stats {
        double mean,std,min,p20,p50,p75,p80,p95,max;
        static Stats of(List<Double> vals){ List<Double> s=new ArrayList<>(vals); s.sort(Double::compareTo); Stats o=new Stats(); o.mean=mean(s); o.std=sampleStd(s,o.mean); o.min=s.get(0); o.p20=percentile(s,0.2); o.p50=percentile(s,0.5); o.p75=percentile(s,0.75); o.p80=percentile(s,0.8); o.p95=percentile(s,0.95); o.max=s.get(s.size()-1); return o; }
    }

}
