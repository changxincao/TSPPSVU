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
import Helper.calculateHelper.KernelType;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.SAAModel;
import Model.SecondStageEvaluator;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Equality-balance sensitivity test that calibrates q_ij and MQC p_i only from
 * the 53 weeks available before the first out-of-sample week. All other model,
 * data, random-seed, rolling-window, and method settings match the controlled
 * equality replay in {@link BrazilOlistConstraint6EqualityFastReplay}.
 */
public final class BrazilOlistConstraint6EqualityPreOosCalibration {

    private static final int TRAIN_SIZE = 50;
    private static final int NUM_CARRIERS = 10;
    private static final int CALIBRATION_WEEKS = 53;

    private static final Path DEFAULT_WEEKLY = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
    private static final Path DEFAULT_PARAMS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "02_旧结果_弃用/02_每个trial最优参数/每个trial最终选中参数表.csv");
    private static final Path DEFAULT_ALL104_EQ = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "14_约束6等式重跑_20260807/01_固定原参数_快速方法/"
                    + "constraint6_EQ_fast_trials.csv");
    private static final Path DEFAULT_OUTPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "14_约束6等式重跑_20260807/02_前53周标定_快速方法");

    private BrazilOlistConstraint6EqualityPreOosCalibration() {
    }

    public static void main(String[] args) throws Exception {
        run(args, CalibrationMode.Q_AND_P_PRE53, DEFAULT_OUTPUT);
    }

    static void run(String[] args, CalibrationMode calibrationMode, Path defaultOutput) throws Exception {
        Path weeklyCsv = pathArg(args, 0, DEFAULT_WEEKLY);
        Path selectedParamCsv = pathArg(args, 1, DEFAULT_PARAMS);
        Path all104EqCsv = pathArg(args, 2, DEFAULT_ALL104_EQ);
        Path outDir = pathArg(args, 3, defaultOutput);
        int startTrial = intArg(args, 4, 0);
        int maxTrials = intArg(args, 5, Integer.MAX_VALUE);
        List<String> methods = methodArg(args, 6);

        Files.createDirectories(outDir);
        Path trialsCsv = outDir.resolve("constraint6_EQ_pre53_calibration_trials.csv");
        Path summaryCsv = outDir.resolve("constraint6_EQ_pre53_calibration_summary.csv");
        Path auditTxt = outDir.resolve("parameter_calibration_audit.txt");
        Path laneAuditCsv = outDir.resolve("lane_calibration_audit.csv");

        List<SelectedParams> selections = readSelections(selectedParamCsv);
        selections.sort((a, b) -> Integer.compare(a.trialId, b.trialId));
        Map<String, BenchmarkResult> all104Results = readBenchmark(all104EqCsv);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(weeklyCsv);
        if (weekly.periods.size() < CALIBRATION_WEEKS) {
            throw new IllegalArgumentException("Need at least " + CALIBRATION_WEEKS + " weekly periods.");
        }
        List<PeriodData> preOosPeriods = new ArrayList<>(weekly.periods.subList(0, CALIBRATION_WEEKS));

        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : new int[]{1, 2, 3}) {
            Config cfg = baseConfig(k, 1.0, SolveMode.CSAA);
            samplesByK.put(k, SampleBuilder.buildFromPeriods(
                    weekly.periods, weekly.laneNames, cfg).samples);
        }

        Config instanceCfg = baseConfig(3, 1.0, SolveMode.SAA);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        double[] all104Baseline = TRBReviewerExperimentSupport.baselineDemand(weekly.periods);
        double[] pre53Baseline = TRBReviewerExperimentSupport.baselineDemand(preOosPeriods);
        ProcurementParams all104Params = InstanceGenerator.generate(
                NUM_CARRIERS, all104Baseline, genCfg, instanceCfg);
        ProcurementParams pre53Params = InstanceGenerator.generate(
                NUM_CARRIERS, pre53Baseline, genCfg, instanceCfg);
        ProcurementParams treatmentParams = treatmentParams(calibrationMode, all104Params, pre53Params);
        auditCalibration(calibrationMode, all104Params, treatmentParams, all104Baseline, pre53Baseline,
                weekly.laneNames, auditTxt, laneAuditCsv);

        initializeTrialsCsv(trialsCsv);
        Set<String> completed = readCompletedKeys(trialsCsv);
        int end = Math.min(selections.size(), startTrial + maxTrials);

        System.out.println("Constraint (6) equality with pre-OOS calibration");
        System.out.println("weeklyCsv=" + weeklyCsv.toAbsolutePath());
        System.out.println("selectedParamCsv=" + selectedParamCsv.toAbsolutePath());
        System.out.println("all104EqCsv=" + all104EqCsv.toAbsolutePath());
        System.out.println("outDir=" + outDir.toAbsolutePath());
        System.out.println("methods=" + methods + ", trialRange=[" + startTrial + "," + end + ")");
        System.out.println("calibrationMode=" + calibrationMode);
        System.out.println("frozen: equality balance, carriers=10, W=50, seed=0, rates, spot, coverage, alpha/beta");

        for (String method : methods) {
            for (int index = startTrial; index < end; index++) {
                SelectedParams selection = selections.get(index);
                String key = method + "#" + selection.trialId;
                if (completed.contains(key)) {
                    System.out.println("skip completed " + key);
                    continue;
                }
                ResultRow row = solveOne(method, selection, samplesByK, weekly.laneNames,
                        treatmentParams, all104Results);
                appendRow(trialsCsv, row);
                completed.add(key);
                System.out.println(String.format(Locale.US,
                        "done method=%s trial=%d test=%d realized=%.10f all104EQ=%.10f delta=%.4f%% time=%.3fs sel=%d",
                        row.method, row.trialId, row.testPeriodIdx, row.realized,
                        row.benchmarkRealized, row.benchmarkDeltaPct, row.solveTimeSec, row.selectedCount));
            }
            writeSummary(trialsCsv, summaryCsv);
        }

        System.out.println("done trials=" + trialsCsv.toAbsolutePath());
        System.out.println("done summary=" + summaryCsv.toAbsolutePath());
        System.out.println("done audit=" + auditTxt.toAbsolutePath());
    }

    private static ResultRow solveOne(String method,
                                      SelectedParams selection,
                                      Map<Integer, List<Sample>> samplesByK,
                                      List<String> lanes,
                                      ProcurementParams params,
                                      Map<String, BenchmarkResult> benchmark) throws Exception {
        int k;
        double cH;
        SolveMode solveMode;
        if ("Mean".equals(method)) {
            k = 3;
            cH = Double.NaN;
            solveMode = SolveMode.SAA;
        } else if ("SAA".equals(method)) {
            k = 3;
            cH = Double.NaN;
            solveMode = SolveMode.SAA;
        } else if ("CSAA".equals(method)) {
            k = selection.csaaK;
            cH = selection.csaaCH;
            solveMode = SolveMode.CSAA;
        } else {
            throw new IllegalArgumentException("Unsupported method: " + method);
        }

        List<Sample> allSamples = samplesByK.get(k);
        List<Sample> trainRaw = trainingWindow(allSamples, selection.testPeriodIdx, k);
        Sample test = testSample(allSamples, selection.testPeriodIdx, k);
        Config cfg = baseConfig(k, Double.isFinite(cH) ? cH : 1.0, solveMode);

        List<Sample> train;
        CovariateVector thetaNow = new CovariateVector(test.theta.values().clone());
        if ("Mean".equals(method)) {
            double[] meanDemand = meanDemand(trainRaw, params.J);
            PeriodData period = new PeriodData(
                    test.period.tIndex, test.period.startDate, test.period.endDate,
                    meanDemand, test.period.holidayCount, test.period.avgFreightIndex,
                    test.period.avgConsumptionIndex, test.period.avgWEIIndex);
            train = new ArrayList<>();
            train.add(new Sample(0, period, thetaNow, 1.0));
        } else {
            train = BatchRunner.deepCopySamples(trainRaw);
            if (solveMode == SolveMode.SAA) {
                double weight = 1.0 / train.size();
                for (Sample sample : train) sample.weight = weight;
            } else {
                thetaNow = standardizeTrainingAndNow(train, cfg, thetaNow);
                WeightCalculator calculator = new WeightCalculator(
                        WeightCalculator.buildKernel(cfg), new EuclideanDistance());
                calculator.computeKernelWeights(train, thetaNow, cfg);
            }
        }

        long start = System.nanoTime();
        Solution solution = new SAAModel().solve(new Data(lanes, train, thetaNow, params), cfg, null);
        double solveTime = (System.nanoTime() - start) / 1e9;
        SecondStageEvaluator.Result recourse = SecondStageEvaluator.evaluate(
                params, solution.y, test.demand().clone(), true);

        int trainingPenaltyPositiveCount = 0;
        double trainingPenaltySum = 0.0;
        for (Sample sample : trainRaw) {
            SecondStageEvaluator.Result trainingRecourse = SecondStageEvaluator.evaluate(
                    params, solution.y, sample.demand().clone(), true);
            trainingPenaltySum += trainingRecourse.penaltyCost;
            if (trainingRecourse.penaltyCost > 1e-6) trainingPenaltyPositiveCount++;
        }

        ResultRow row = new ResultRow();
        row.method = method;
        row.trialId = selection.trialId;
        row.testPeriodIdx = selection.testPeriodIdx;
        row.k = k;
        row.cH = cH;
        row.expected = solution.objValue;
        row.realized = recourse.objective;
        row.solveTimeSec = solveTime;
        row.selectedCount = selectedCount(solution.y);
        row.transport = recourse.transportCost;
        row.spot = recourse.spotCost;
        row.penalty = recourse.penaltyCost;
        row.trainingScenarioCount = trainRaw.size();
        row.trainingPenaltyPositiveCount = trainingPenaltyPositiveCount;
        row.trainingMeanPenalty = trainingPenaltySum / trainRaw.size();
        row.yBinary = yBinary(solution.y);
        BenchmarkResult old = benchmark.get(method + "#" + selection.trialId);
        row.benchmarkRealized = old == null ? Double.NaN : old.realized;
        row.benchmarkSelectedCount = old == null ? -1 : old.selectedCount;
        row.benchmarkDeltaPct = Double.isFinite(row.benchmarkRealized) && row.benchmarkRealized != 0.0
                ? 100.0 * (row.realized - row.benchmarkRealized) / row.benchmarkRealized
                : Double.NaN;
        return row;
    }

    private static ProcurementParams treatmentParams(CalibrationMode mode,
                                                      ProcurementParams all104,
                                                      ProcurementParams pre53) {
        double[] p = all104.p.clone();
        double[] h = all104.h.clone();
        double[] e = all104.e.clone();
        double[][] q = copy(all104.q);
        switch (mode) {
            case Q_AND_P_PRE53:
                p = pre53.p.clone();
                q = copy(pre53.q);
                break;
            case P_PRE53_ONLY:
                p = pre53.p.clone();
                break;
            case H_MIN_RATE_ONLY:
                h = minimumEligibleRates(all104);
                break;
            case P_PRE53_AND_H_MIN_RATE:
                p = pre53.p.clone();
                h = minimumEligibleRates(all104);
                break;
            case MQC_OFF:
                p = new double[all104.I];
                break;
            case P_SCALE_050:
                p = scaled(all104.p, 0.50);
                break;
            case P_SCALE_025:
                p = scaled(all104.p, 0.25);
                break;
            case P_SCALE_040:
                p = scaled(all104.p, 0.40);
                break;
            case P_SCALE_075:
                p = scaled(all104.p, 0.75);
                break;
            case P_SCALE_100:
                p = all104.p.clone();
                break;
            case H_SCALE_025:
                h = scaled(all104.h, 0.25);
                break;
            case H_SCALE_050:
                h = scaled(all104.h, 0.50);
                break;
            case H_SCALE_075:
                h = scaled(all104.h, 0.75);
                break;
            case H_MQC_FILL_UNIT_COST:
                h = mqcFillUnitCosts(all104);
                break;
            case Q_SCALE_075:
                q = scaled(all104.q, 0.75);
                break;
            case Q_SCALE_125:
                q = scaled(all104.q, 1.25);
                break;
            case Q_SCALE_150:
                q = scaled(all104.q, 1.50);
                break;
            case E_SCALE_075:
                e = scaled(all104.e, 0.75);
                break;
            case E_SCALE_125:
                e = scaled(all104.e, 1.25);
                break;
            default:
                throw new IllegalArgumentException("Unsupported calibration mode: " + mode);
        }
        return new ProcurementParams(
                new ArrayList<>(all104.carriers), all104.J, e, p, h,
                q, copy(all104.r), copy(all104.eligible), all104.alpha, all104.beta);
    }

    private static double[] minimumEligibleRates(ProcurementParams params) {
        double[] result = new double[params.I];
        for (int i = 0; i < params.I; i++) {
            double minimum = Double.POSITIVE_INFINITY;
            for (int j = 0; j < params.J; j++) {
                if (params.eligible[i][j]) minimum = Math.min(minimum, params.r[i][j]);
            }
            if (!Double.isFinite(minimum)) throw new IllegalStateException("Carrier has no eligible lane: " + i);
            result[i] = minimum;
        }
        return result;
    }

    private static double[] scaled(double[] values, double factor) {
        double[] result = values.clone();
        for (int i = 0; i < result.length; i++) result[i] *= factor;
        return result;
    }

    private static double[][] scaled(double[][] values, double factor) {
        double[][] result = copy(values);
        for (int i = 0; i < result.length; i++) {
            for (int j = 0; j < result[i].length; j++) result[i][j] *= factor;
        }
        return result;
    }

    private static void auditCalibration(CalibrationMode mode,
                                         ProcurementParams all104,
                                         ProcurementParams treatment,
                                         double[] all104Baseline,
                                         double[] pre53Baseline,
                                         List<String> lanes,
                                         Path auditTxt,
                                         Path laneAuditCsv) throws Exception {
        double maxRateDifference = maxAbsDifference(all104.r, treatment.r);
        double maxSpotDifference = maxAbsDifference(all104.e, treatment.e);
        double maxMqcDifference = maxAbsDifference(all104.p, treatment.p);
        double maxPenaltyRateDifference = maxAbsDifference(all104.h, treatment.h);
        double maxCapacityDifference = maxAbsDifference(all104.q, treatment.q);
        boolean eligibilitySame = sameEligibility(all104.eligible, treatment.eligible);
        if (maxRateDifference != 0.0
                || (maxSpotDifference != 0.0 && !changesSpot(mode))
                || (maxMqcDifference != 0.0 && !changesMqc(mode))
                || (maxPenaltyRateDifference != 0.0 && !changesPenaltyRate(mode))
                || (maxCapacityDifference != 0.0 && !changesCapacity(mode))
                || !eligibilitySame
                || all104.alpha != treatment.alpha || all104.beta != treatment.beta) {
            throw new IllegalStateException("Unintended parameter changed during calibration audit.");
        }

        double allDemand = sum(all104Baseline);
        double preDemand = sum(pre53Baseline);
        double[] minimumRates = minimumEligibleRates(all104);
        double[] mqcFillUnitCosts = mqcFillUnitCosts(all104);
        double minRateToMaxRateMean = meanRatio(minimumRates, all104.h);
        double minRateToMaxRateMin = minRatio(minimumRates, all104.h);
        double minRateToMaxRateMax = maxRatio(minimumRates, all104.h);
        String report = String.format(Locale.US,
                "calibrationMode=%s%ncalibrationWeeks=0..52%n"
                        + "all104MeanTotalDemand=%.10f%n"
                        + "pre53MeanTotalDemand=%.10f%n"
                        + "pre53ToAll104DemandRatio=%.10f%n"
                        + "all104MeanP=%.10f%n"
                        + "treatmentMeanP=%.10f%n"
                        + "all104MeanQ=%.10f%n"
                        + "treatmentMeanQ=%.10f%n"
                        + "all104MeanH=%.10f%n"
                        + "treatmentMeanH=%.10f%n"
                        + "meanMinimumEligibleRate=%.10f%n"
                        + "meanMqcFillUnitCost=%.10f%n"
                        + "meanMqcFillCostToMaxRateRatio=%.10f%n"
                        + "meanMinRateToMaxRateRatio=%.10f%n"
                        + "minMinRateToMaxRateRatio=%.10f%n"
                        + "maxMinRateToMaxRateRatio=%.10f%n"
                        + "maxRateDifference=%.17g%n"
                        + "maxSpotDifference=%.17g%n"
                        + "maxMqcDifference=%.17g%n"
                        + "maxPenaltyRateDifference=%.17g%n"
                        + "maxCapacityDifference=%.17g%n"
                        + "eligibilitySame=%s%nalphaSame=%s%nbetaSame=%s%n",
                mode, allDemand, preDemand, preDemand / allDemand,
                mean(all104.p), mean(treatment.p), mean(all104.q), mean(treatment.q),
                mean(all104.h), mean(treatment.h), mean(minimumRates),
                mean(mqcFillUnitCosts), meanRatio(mqcFillUnitCosts, all104.h),
                minRateToMaxRateMean, minRateToMaxRateMin, minRateToMaxRateMax,
                maxRateDifference, maxSpotDifference, maxMqcDifference,
                maxPenaltyRateDifference, maxCapacityDifference,
                eligibilitySame, all104.alpha == treatment.alpha, all104.beta == treatment.beta);
        Files.writeString(auditTxt, report, StandardCharsets.UTF_8);

        try (BufferedWriter writer = Files.newBufferedWriter(laneAuditCsv, StandardCharsets.UTF_8)) {
            writer.write("lane,all104_mean_demand,pre53_mean_demand,pre53_to_all104_ratio,all104_mean_q,treatment_mean_q");
            writer.newLine();
            for (int j = 0; j < all104.J; j++) {
                double ratio = all104Baseline[j] == 0.0 ? Double.NaN : pre53Baseline[j] / all104Baseline[j];
                writer.write(String.format(Locale.US, "%s,%.10f,%.10f,%s,%.10f,%.10f%n",
                        csvEscape(lanes.get(j)), all104Baseline[j], pre53Baseline[j],
                        Double.isFinite(ratio) ? String.format(Locale.US, "%.10f", ratio) : "",
                        columnMean(all104.q, j), columnMean(treatment.q, j)));
            }
        }
        System.out.print(report);
    }

    private static double[] mqcFillUnitCosts(ProcurementParams params) {
        double[] result = new double[params.I];
        for (int i = 0; i < params.I; i++) {
            List<Integer> lanes = new ArrayList<>();
            for (int j = 0; j < params.J; j++) {
                if (params.eligible[i][j] && params.q[i][j] > 0.0) lanes.add(j);
            }
            final int carrier = i;
            lanes.sort((a, b) -> Double.compare(params.r[carrier][a], params.r[carrier][b]));
            double remaining = params.p[i];
            double cost = 0.0;
            for (int lane : lanes) {
                double quantity = Math.min(remaining, params.q[i][lane]);
                cost += quantity * params.r[i][lane];
                remaining -= quantity;
                if (remaining <= 1e-9) break;
            }
            if (remaining > 1e-9 || params.p[i] <= 0.0) {
                throw new IllegalStateException("Cannot price MQC fill for carrier " + i);
            }
            result[i] = cost / params.p[i];
        }
        return result;
    }

    private static double meanRatio(double[] numerators, double[] denominators) {
        double sum = 0.0;
        for (int i = 0; i < numerators.length; i++) sum += numerators[i] / denominators[i];
        return sum / numerators.length;
    }

    private static double minRatio(double[] numerators, double[] denominators) {
        double result = Double.POSITIVE_INFINITY;
        for (int i = 0; i < numerators.length; i++) {
            result = Math.min(result, numerators[i] / denominators[i]);
        }
        return result;
    }

    private static double maxRatio(double[] numerators, double[] denominators) {
        double result = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < numerators.length; i++) {
            result = Math.max(result, numerators[i] / denominators[i]);
        }
        return result;
    }

    private static boolean changesMqc(CalibrationMode mode) {
        return switch (mode) {
            case Q_AND_P_PRE53, P_PRE53_ONLY, P_PRE53_AND_H_MIN_RATE, MQC_OFF,
                    P_SCALE_025, P_SCALE_040, P_SCALE_050, P_SCALE_075 -> true;
            default -> false;
        };
    }

    private static boolean changesPenaltyRate(CalibrationMode mode) {
        return switch (mode) {
            case H_MIN_RATE_ONLY, P_PRE53_AND_H_MIN_RATE,
                    H_SCALE_025, H_SCALE_050, H_SCALE_075, H_MQC_FILL_UNIT_COST -> true;
            default -> false;
        };
    }

    private static boolean changesCapacity(CalibrationMode mode) {
        return switch (mode) {
            case Q_AND_P_PRE53, Q_SCALE_075, Q_SCALE_125, Q_SCALE_150 -> true;
            default -> false;
        };
    }

    private static boolean changesSpot(CalibrationMode mode) {
        return mode == CalibrationMode.E_SCALE_075 || mode == CalibrationMode.E_SCALE_125;
    }

    private static Config baseConfig(int k, double cH, SolveMode solveMode) {
        Config cfg = TRBReviewerExperimentSupport.baseConfig(
                k, cH, KernelType.EXPONENTIAL, true, solveMode);
        cfg.enforceDemandEquality = true;
        return cfg;
    }

    private static List<Sample> trainingWindow(List<Sample> samples, int testPeriodIdx, int k) {
        int testSampleIdx = testPeriodIdx - k;
        int start = testSampleIdx - TRAIN_SIZE;
        if (start < 0 || testSampleIdx > samples.size()) {
            throw new IllegalArgumentException("Invalid window for testPeriod=" + testPeriodIdx + ", k=" + k);
        }
        return new ArrayList<>(samples.subList(start, testSampleIdx));
    }

    private static Sample testSample(List<Sample> samples, int testPeriodIdx, int k) {
        return samples.get(testPeriodIdx - k);
    }

    private static double[] meanDemand(List<Sample> samples, int laneCount) {
        double[] mean = new double[laneCount];
        for (Sample sample : samples) {
            for (int j = 0; j < laneCount; j++) mean[j] += sample.demand()[j];
        }
        for (int j = 0; j < laneCount; j++) mean[j] /= samples.size();
        return mean;
    }

    private static CovariateVector standardizeTrainingAndNow(List<Sample> train,
                                                             Config cfg,
                                                             CovariateVector thetaNow) {
        if (!cfg.standardizeTheta || train.size() < 2) return thetaNow;
        StandardScaler scaler = new StandardScaler();
        scaler.fit(train, thetaNow.values().length);
        for (Sample sample : train) {
            sample.theta = new CovariateVector(scaler.transform(sample.theta.values()));
        }
        return new CovariateVector(scaler.transform(thetaNow.values()));
    }

    private static List<SelectedParams> readSelections(Path csv) throws Exception {
        List<SelectedParams> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = columnMap(parseCsv(reader.readLine()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                SelectedParams row = new SelectedParams();
                row.trialId = integer(values, columns, "trialId");
                row.testPeriodIdx = integer(values, columns, "testPeriodIdx");
                row.csaaK = integer(values, columns, "CSAA_best_k");
                row.csaaCH = decimal(values, columns, "CSAA_best_C_h");
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, BenchmarkResult> readBenchmark(Path csv) throws Exception {
        Map<String, BenchmarkResult> rows = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = columnMap(parseCsv(reader.readLine()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                BenchmarkResult row = new BenchmarkResult();
                String method = value(values, columns, "method");
                int trialId = integer(values, columns, "trialId");
                row.realized = decimal(values, columns, "realized_obj");
                row.selectedCount = integer(values, columns, "selected_count");
                rows.put(method + "#" + trialId, row);
            }
        }
        return rows;
    }

    private static void initializeTrialsCsv(Path csv) throws Exception {
        if (Files.exists(csv) && Files.size(csv) > 0) return;
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("method,trialId,testPeriodIdx,k,C_h,demand_relation,calibration_weeks,expected_obj,"
                    + "realized_obj,all104_eq_realized_obj,delta_vs_all104_eq_pct,solve_time_sec,selected_count,"
                    + "all104_eq_selected_count,transport_cost,spot_cost,penalty_cost,yBinary,"
                    + "training_scenario_count,training_penalty_positive_count,training_penalty_positive_rate,"
                    + "training_mean_penalty");
            writer.newLine();
        }
    }

    private static Set<String> readCompletedKeys(Path csv) throws Exception {
        Set<String> keys = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsv(line);
                if (row.size() >= 2) keys.add(row.get(0) + "#" + row.get(1));
            }
        }
        return keys;
    }

    private static void appendRow(Path csv, ResultRow row) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(
                csv, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            writer.write(String.format(Locale.US,
                    "%s,%d,%d,%d,%s,EQ,0-52,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%d,%.10f,%.10f,%.10f,%s,%d,%d,%.10f,%.10f%n",
                    row.method, row.trialId, row.testPeriodIdx, row.k,
                    Double.isFinite(row.cH) ? String.format(Locale.US, "%.10f", row.cH) : "",
                    row.expected, row.realized, row.benchmarkRealized, row.benchmarkDeltaPct,
                    row.solveTimeSec, row.selectedCount, row.benchmarkSelectedCount,
                    row.transport, row.spot, row.penalty, row.yBinary,
                    row.trainingScenarioCount, row.trainingPenaltyPositiveCount,
                    (double) row.trainingPenaltyPositiveCount / row.trainingScenarioCount,
                    row.trainingMeanPenalty));
        }
    }

    private static void writeSummary(Path trialsCsv, Path summaryCsv) throws Exception {
        Map<String, List<ResultRow>> byMethod = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(trialsCsv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = columnMap(parseCsv(reader.readLine()));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                ResultRow row = new ResultRow();
                row.method = value(values, columns, "method");
                row.realized = decimal(values, columns, "realized_obj");
                row.benchmarkRealized = decimal(values, columns, "all104_eq_realized_obj");
                row.solveTimeSec = decimal(values, columns, "solve_time_sec");
                row.selectedCount = integer(values, columns, "selected_count");
                row.transport = decimal(values, columns, "transport_cost");
                row.spot = decimal(values, columns, "spot_cost");
                row.penalty = decimal(values, columns, "penalty_cost");
                row.trainingScenarioCount = optionalInteger(values, columns, "training_scenario_count", 0);
                row.trainingPenaltyPositiveCount = optionalInteger(
                        values, columns, "training_penalty_positive_count", 0);
                row.trainingMeanPenalty = optionalDecimal(values, columns, "training_mean_penalty", Double.NaN);
                byMethod.computeIfAbsent(row.method, ignored -> new ArrayList<>()).add(row);
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(summaryCsv, StandardCharsets.UTF_8)) {
            writer.write("method,n,mean_realized,all104_eq_mean_realized,delta_vs_all104_eq_pct,"
                    + "mean_transport,mean_spot,mean_penalty,total_solve_time_sec,mean_selected_count,"
                    + "training_penalty_positive_rate,mean_training_penalty,oos_penalty_positive_rate");
            writer.newLine();
            for (Map.Entry<String, List<ResultRow>> entry : byMethod.entrySet()) {
                List<ResultRow> rows = entry.getValue();
                double realized = rows.stream().mapToDouble(r -> r.realized).average().orElse(Double.NaN);
                double benchmark = rows.stream().mapToDouble(r -> r.benchmarkRealized).average().orElse(Double.NaN);
                double delta = 100.0 * (realized - benchmark) / benchmark;
                double transport = rows.stream().mapToDouble(r -> r.transport).average().orElse(Double.NaN);
                double spot = rows.stream().mapToDouble(r -> r.spot).average().orElse(Double.NaN);
                double penalty = rows.stream().mapToDouble(r -> r.penalty).average().orElse(Double.NaN);
                double time = rows.stream().mapToDouble(r -> r.solveTimeSec).sum();
                double selected = rows.stream().mapToInt(r -> r.selectedCount).average().orElse(Double.NaN);
                int trainingScenarios = rows.stream().mapToInt(r -> r.trainingScenarioCount).sum();
                int trainingPenaltyPositive = rows.stream().mapToInt(
                        r -> r.trainingPenaltyPositiveCount).sum();
                double trainingPenaltyRate = trainingScenarios == 0 ? Double.NaN
                        : (double) trainingPenaltyPositive / trainingScenarios;
                double meanTrainingPenalty = rows.stream().mapToDouble(
                        r -> r.trainingMeanPenalty).filter(Double::isFinite).average().orElse(Double.NaN);
                double oosPenaltyRate = rows.stream().filter(r -> r.penalty > 1e-6).count()
                        / (double) rows.size();
                writer.write(String.format(Locale.US,
                        "%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%.10f,%.10f,%.10f,%.10f%n",
                        entry.getKey(), rows.size(), realized, benchmark, delta,
                        transport, spot, penalty, time, selected,
                        trainingPenaltyRate, meanTrainingPenalty, oosPenaltyRate));
            }
        }
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static String yBinary(double[] y) {
        StringBuilder out = new StringBuilder();
        for (double value : y) out.append(value > 0.5 ? '1' : '0');
        return out.toString();
    }

    private static double maxAbsDifference(double[] a, double[] b) {
        double max = 0.0;
        for (int i = 0; i < a.length; i++) max = Math.max(max, Math.abs(a[i] - b[i]));
        return max;
    }

    private static double maxAbsDifference(double[][] a, double[][] b) {
        double max = 0.0;
        for (int i = 0; i < a.length; i++) {
            max = Math.max(max, maxAbsDifference(a[i], b[i]));
        }
        return max;
    }

    private static boolean sameEligibility(boolean[][] a, boolean[][] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (a[i].length != b[i].length) return false;
            for (int j = 0; j < a[i].length; j++) if (a[i][j] != b[i][j]) return false;
        }
        return true;
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

    private static double sum(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum;
    }

    private static double mean(double[] values) {
        return sum(values) / values.length;
    }

    private static double mean(double[][] values) {
        double sum = 0.0;
        int count = 0;
        for (double[] row : values) {
            for (double value : row) {
                sum += value;
                count++;
            }
        }
        return sum / count;
    }

    private static double columnMean(double[][] values, int column) {
        double sum = 0.0;
        for (double[] row : values) sum += row[column];
        return sum / values.length;
    }

    private static List<String> methodArg(String[] args, int index) {
        String raw = args.length > index ? args[index] : "Mean,SAA";
        List<String> methods = new ArrayList<>();
        for (String value : raw.split(",")) {
            String method = value.trim();
            if (!method.isEmpty()) methods.add(method);
        }
        return methods;
    }

    private static Path pathArg(String[] args, int index, Path fallback) {
        return args.length > index && !args[index].isBlank() ? Paths.get(args[index]) : fallback;
    }

    private static int intArg(String[] args, int index, int fallback) {
        return args.length > index && !args[index].isBlank() ? Integer.parseInt(args[index]) : fallback;
    }

    private static Map<String, Integer> columnMap(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i);
            if (i == 0 && !name.isEmpty() && name.charAt(0) == '\uFEFF') name = name.substring(1);
            columns.put(name, i);
        }
        return columns;
    }

    private static String value(List<String> row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= row.size()) throw new IllegalArgumentException("Missing CSV column: " + name);
        return row.get(index);
    }

    private static int integer(List<String> row, Map<String, Integer> columns, String name) {
        return (int) Double.parseDouble(value(row, columns, name));
    }

    private static double decimal(List<String> row, Map<String, Integer> columns, String name) {
        String value = value(row, columns, name);
        return value.isBlank() ? Double.NaN : Double.parseDouble(value);
    }

    private static double optionalDecimal(List<String> row,
                                          Map<String, Integer> columns,
                                          String name,
                                          double fallback) {
        Integer index = columns.get(name);
        if (index == null || index >= row.size() || row.get(index).isBlank()) return fallback;
        return Double.parseDouble(row.get(index));
    }

    private static int optionalInteger(List<String> row,
                                       Map<String, Integer> columns,
                                       String name,
                                       int fallback) {
        Integer index = columns.get(name);
        if (index == null || index >= row.size() || row.get(index).isBlank()) return fallback;
        return (int) Double.parseDouble(row.get(index));
    }

    private static List<String> parseCsv(String line) {
        if (line == null) return List.of();
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static String csvEscape(String value) {
        if (!value.contains(",") && !value.contains("\"") && !value.contains("\n")) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static final class SelectedParams {
        int trialId;
        int testPeriodIdx;
        int csaaK;
        double csaaCH;
    }

    private static final class BenchmarkResult {
        double realized;
        int selectedCount;
    }

    private static final class ResultRow {
        String method;
        int trialId;
        int testPeriodIdx;
        int k;
        double cH;
        double expected;
        double realized;
        double benchmarkRealized;
        double benchmarkDeltaPct;
        double solveTimeSec;
        int selectedCount;
        int benchmarkSelectedCount;
        double transport;
        double spot;
        double penalty;
        int trainingScenarioCount;
        int trainingPenaltyPositiveCount;
        double trainingMeanPenalty;
        String yBinary;
    }

    enum CalibrationMode {
        Q_AND_P_PRE53,
        P_PRE53_ONLY,
        H_MIN_RATE_ONLY,
        P_PRE53_AND_H_MIN_RATE,
        MQC_OFF,
        P_SCALE_025,
        P_SCALE_040,
        P_SCALE_050,
        P_SCALE_075,
        P_SCALE_100,
        H_SCALE_025,
        H_SCALE_050,
        H_SCALE_075,
        H_MQC_FILL_UNIT_COST,
        Q_SCALE_075,
        Q_SCALE_125,
        Q_SCALE_150,
        E_SCALE_075,
        E_SCALE_125
    }
}
