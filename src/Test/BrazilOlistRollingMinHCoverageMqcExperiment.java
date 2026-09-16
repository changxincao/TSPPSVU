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
import Model.DROModel;
import Model.RCSAASolverVariant;
import Model.SAAModel;
import Model.SecondStageEvaluator;
import Model.Solution;
import Model.SolveMode;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarType;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Paired Olist rolling sensitivity requested by Reviewer 4, Comment 6.
 *
 * <p>The full-coverage procurement instance is generated once with the Table-2
 * seed. Coverage is then changed by a nested eligibility mask only: retained
 * rates, capacities and spot prices are unchanged. For every carrier, MQC is
 * recomputed using its original U[0.1,0.2] draw ratio times the baseline demand
 * of its retained lanes, and then multiplied by the requested quantity scale.
 * The MQC shortfall rate is the minimum retained eligible r_ij by default and
 * can be switched to the maximum solely for the documented sensitivity. Demand
 * equality, the 50-week rolling windows, old per-trial k/C/lambda selections,
 * kernel, standardization and all other procurement parameters are frozen.</p>
 *
 * <p>Arguments: coverage, MQC quantity scale, comma-separated methods,
 * start-trial, max-trials. Methods are D,SAA,CSAA,RCSAA,DRO and the diagnostic
 * CSAA_MATCHED_RCSAA_COUNT. Each condition has
 * a separate resumable output directory. RCSAA_LBBD is an explicit diagnostic
 * exact-solver gate; uncertified rows must be excluded.</p>
 */
public final class BrazilOlistRollingMinHCoverageMqcExperiment {

    private static final int TRAIN_SIZE = 50;
    private static final int CARDINALITY_SUBTRAIN_SIZE = Integer.getInteger(
            "trb.olist.cardinalitySubtrain", 35);
    private static final int CARDINALITY_VALIDATION_SIZE =
            TRAIN_SIZE - CARDINALITY_SUBTRAIN_SIZE;
    private static final int CARDINALITY_MIN = Integer.getInteger(
            "trb.olist.cardinalityMin", 4);
    private static final String CARDINALITY_VALIDATION_PROTOCOL = System.getProperty(
            "trb.olist.cardinalityValidation", "fixed").toLowerCase(Locale.ROOT);
    private static final int NUM_CARRIERS = Integer.getInteger("trb.olist.carriers", 10);
    private static final double BETA_RATIO = Double.parseDouble(System.getProperty(
            "trb.olist.betaRatio", "0.7"));
    private static final boolean MAX_PENALTY = "max".equalsIgnoreCase(
            System.getProperty("trb.olist.penalty", "min"));
    private static final boolean NORMALIZE_COVERAGE_CAPACITY =
            Boolean.getBoolean("trb.olist.normalizeCoverageCapacity");
    private static final long COVERAGE_MASK_SEED = 2026081101L;
    private static final double WEIGHT_FLOOR = 1e-8;
    private static final double OBJECTIVE_TOLERANCE = 1e-4;
    private static final int RCSAA_LBBD_TIME_LIMIT = Integer.getInteger(
            "trb.olist.rcsaaTimeLimit", 600);

    private static final Path WEEKLY = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
    private static final Path SELECTED_PARAMS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "02_旧结果_弃用/02_每个trial最优参数/每个trial最终选中参数表.csv");
    private static final Path OUTPUT_ROOT = Paths.get(System.getProperty(
            "trb.olist.outputRoot",
            "analysis/TRB_reviewer_revision/03_olist_rolling_min_h_coverage_mqc_20260811"));

    private BrazilOlistRollingMinHCoverageMqcExperiment() {
    }

    public static void main(String[] args) throws Exception {
        double coverage = args.length > 0 ? Double.parseDouble(args[0]) : 1.0;
        double mqcScale = args.length > 1 ? Double.parseDouble(args[1]) : 1.0;
        Set<String> methods = methods(args.length > 2 ? args[2] : "D,SAA,CSAA,RCSAA,DRO");
        int startTrial = args.length > 3 ? Integer.parseInt(args[3]) : 0;
        int maxTrials = args.length > 4 ? Integer.parseInt(args[4]) : Integer.MAX_VALUE;
        validateInputs(coverage, mqcScale, startTrial, maxTrials);

        Path conditionDir = OUTPUT_ROOT.resolve(String.format(Locale.US,
                "coverage_%03d/mqc_%03d", Math.round(100 * coverage), Math.round(100 * mqcScale)));
        Files.createDirectories(conditionDir);
        Path rowsCsv = conditionDir.resolve("rolling_trials.csv");
        Path summaryCsv = conditionDir.resolve("rolling_summary.csv");
        initializeRows(rowsCsv);
        Set<String> completed = readCompleted(rowsCsv);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY);
        double[] baselineDemand = TRBReviewerExperimentSupport.baselineDemand(weekly.periods);
        Config instanceConfig = baseConfig(3, 1.0, 1.0, SolveMode.SAA);
        InstanceGenerator.GenConfig generationConfig = new InstanceGenerator.GenConfig();
        generationConfig.betaRatio = BETA_RATIO;
        ProcurementParams full = InstanceGenerator.generate(
                NUM_CARRIERS, baselineDemand, generationConfig, instanceConfig);
        if (NUM_CARRIERS != 10) full = withMarketSizeScale(full, 10.0 / NUM_CARRIERS);
        ProcurementParams params = withCoverageAndMqc(full, baselineDemand, coverage, mqcScale);
        writeParameterAudit(conditionDir.resolve("parameter_audit.csv"), full, params,
                baselineDemand, coverage, mqcScale);

        List<Selection> selections = readSelections(SELECTED_PARAMS);
        selections.sort(Comparator.comparingInt(row -> row.trialId));
        int end = Math.min(selections.size(), startTrial + maxTrials);

        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : new int[]{1, 2, 3}) {
            Config cfg = baseConfig(k, 1.0, 1.0, SolveMode.CSAA);
            samplesByK.put(k, SampleBuilder.buildFromPeriods(
                    weekly.periods, weekly.laneNames, cfg).samples);
        }

        boolean needsExact = (methods.contains("RCSAA")
                && hasIncomplete("RCSAA", selections, completed, startTrial, end))
                || (methods.contains("CSAA_MATCHED_RCSAA_COUNT")
                && hasIncomplete("CSAA_MATCHED_RCSAA_COUNT", selections, completed, startTrial, end))
                || (methods.contains("CSAA_TRAIN_TUNED_CARDINALITY")
                && hasIncomplete("CSAA_TRAIN_TUNED_CARDINALITY",
                selections, completed, startTrial, end));
        ExactCache exactCache = needsExact ? ExactCache.build(params, weekly) : null;

        System.out.printf(Locale.US,
                "Olist rolling condition I=%d alpha=%d beta=%d betaRatio=%.6f h=%s "
                        + "capacityNormalized=%s coverage=%.6f mqcScale=%.6f "
                        + "methods=%s trials=[%d,%d)%n",
                NUM_CARRIERS, params.alpha, params.beta, BETA_RATIO,
                MAX_PENALTY ? "max" : "min",
                NORMALIZE_COVERAGE_CAPACITY, coverage, mqcScale,
                methods, startTrial, end);
        System.out.println("weekly=" + WEEKLY.toAbsolutePath());
        System.out.println("selectedParams=" + SELECTED_PARAMS.toAbsolutePath());
        System.out.println("output=" + conditionDir.toAbsolutePath());

        for (String method : methods) {
            for (int index = startTrial; index < end; index++) {
                Selection selection = selections.get(index);
                String key = method + "#" + selection.trialId;
                if (completed.contains(key)) {
                    System.out.println("skip completed " + key);
                    continue;
                }
                Result row = solveOne(method, selection, samplesByK, weekly, params, exactCache);
                appendRow(rowsCsv, coverage, mqcScale, selection, row);
                completed.add(key);
                System.out.printf(Locale.US,
                        "done method=%s trial=%d test=%d realized=%.6f time=%.3fs sel=%d penalty=%.6f%n",
                        method, selection.trialId, selection.testPeriodIdx, row.realized,
                        row.solveTimeSec, row.selectedCount, row.penalty);
            }
            writeSummary(rowsCsv, summaryCsv);
        }
        System.out.println("done rows=" + rowsCsv.toAbsolutePath());
        System.out.println("done summary=" + summaryCsv.toAbsolutePath());
    }

    private static Result solveOne(String method,
                                   Selection selection,
                                   Map<Integer, List<Sample>> samplesByK,
                                   WeeklyWideLoader.Result weekly,
                                   ProcurementParams params,
                                   ExactCache exactCache) throws Exception {
        int k;
        double cH;
        double lambda = Double.NaN;
        SolveMode solveMode;
        switch (method) {
            case "D" -> {
                k = 3;
                cH = Double.NaN;
                solveMode = SolveMode.SAA;
            }
            case "SAA" -> {
                k = 3;
                cH = Double.NaN;
                solveMode = SolveMode.SAA;
            }
            case "CSAA" -> {
                k = selection.csaaK;
                cH = selection.csaaCH;
                solveMode = SolveMode.CSAA;
            }
            case "CSAA_TRAIN_TUNED_CARDINALITY" -> {
                k = selection.rcsaaK;
                cH = selection.rcsaaCH;
                lambda = 0.0;
                solveMode = SolveMode.CSAA;
            }
            case "RCSAA", "RCSAA_LBBD", "DRO", "CSAA_MATCHED_RCSAA_COUNT" -> {
                k = selection.rcsaaK;
                cH = selection.rcsaaCH;
                lambda = selection.lambda;
                solveMode = SolveMode.RCSAA;
            }
            default -> throw new IllegalArgumentException("Unsupported method: " + method);
        }

        List<Sample> allSamples = samplesByK.get(k);
        int testSampleIndex = selection.testPeriodIdx - k;
        int trainStart = testSampleIndex - TRAIN_SIZE;
        if (trainStart < 0 || testSampleIndex >= allSamples.size()) {
            throw new IllegalArgumentException("Invalid rolling window at trial " + selection.trialId);
        }
        List<Sample> trainRaw = new ArrayList<>(allSamples.subList(trainStart, testSampleIndex));
        Sample test = allSamples.get(testSampleIndex);
        CovariateVector thetaNow = new CovariateVector(test.theta.values().clone());
        List<Sample> train;

        if (method.equals("D")) {
            PeriodData period = new PeriodData(
                    test.period.tIndex, test.period.startDate, test.period.endDate,
                    meanDemand(trainRaw, params.J), test.period.holidayCount,
                    test.period.avgFreightIndex, test.period.avgConsumptionIndex,
                    test.period.avgWEIIndex);
            train = new ArrayList<>();
            train.add(new Sample(0, period, thetaNow, 1.0));
        } else {
            train = BatchRunner.deepCopySamples(trainRaw);
            if (method.equals("SAA")) {
                double weight = 1.0 / train.size();
                for (Sample sample : train) sample.weight = weight;
            } else {
                thetaNow = standardize(train, thetaNow);
                Config weightConfig = baseConfig(k, cH, lambda, solveMode);
                new WeightCalculator(WeightCalculator.buildKernel(weightConfig), new EuclideanDistance())
                        .computeKernelWeights(train, thetaNow, weightConfig);
            }
        }

        long started = System.nanoTime();
        double expected;
        double modelObjective;
        double[] y;
        String solverStatus;
        boolean certifiedOptimal;
        double relativeGap;

        if (method.equals("RCSAA")) {
            if (exactCache == null) throw new IllegalStateException("Missing exact RCSAA cache.");
            ExactSolution solution = exactCache.solve(train, lambda);
            expected = solution.mean;
            modelObjective = solution.objective;
            y = solution.y;
            solverStatus = "OPTIMAL_ENUMERATION_CACHE";
            certifiedOptimal = true;
            relativeGap = 0.0;
        } else if (method.equals("RCSAA_LBBD")) {
            Config cfg = baseConfig(k, cH, lambda, SolveMode.RCSAA);
            cfg.rcsaaSolverVariant = RCSAASolverVariant.LBBD_PRIMAL_EXACT;
            cfg.timeLimitSeconds = RCSAA_LBBD_TIME_LIMIT;
            Solution solution = new DROModel().solve(
                    new Data(weekly.laneNames, train, thetaNow, params), cfg);
            expected = Double.NaN;
            modelObjective = solution.objValue;
            y = solution.y;
            solverStatus = solution.solverStatus;
            certifiedOptimal = solution.certifiedOptimal;
            relativeGap = solution.relativeGap;
        } else if (method.equals("CSAA_MATCHED_RCSAA_COUNT")) {
            if (exactCache == null) throw new IllegalStateException("Missing exact RCSAA cache.");
            ExactSolution rcsaa = exactCache.solve(train, lambda);
            ExactSolution matched = exactCache.solve(train, 0.0, selectedCount(rcsaa.y));
            expected = matched.mean;
            modelObjective = matched.mean;
            y = matched.y;
            solverStatus = "OPTIMAL_MATCHED_CARDINALITY_ENUMERATION_CACHE";
            certifiedOptimal = true;
            relativeGap = 0.0;
        } else if (method.equals("CSAA_TRAIN_TUNED_CARDINALITY")) {
            if (exactCache == null) throw new IllegalStateException("Missing exact CSAA cache.");
            CardinalitySelection selected = selectCardinality(
                    trainRaw, k, cH, params, exactCache);
            ExactSolution solution = exactCache.solve(train, 0.0, selected.count);
            expected = solution.mean;
            modelObjective = solution.mean;
            y = solution.y;
            solverStatus = String.format(Locale.US,
                    "OPTIMAL_TRAIN_TUNED_CARDINALITY_%s_m=%d_validation=%.6f",
                    CARDINALITY_VALIDATION_PROTOCOL, selected.count, selected.validationMean);
            certifiedOptimal = true;
            relativeGap = 0.0;
        } else if (method.equals("DRO")) {
            Config cfg = baseConfig(k, cH, lambda, SolveMode.RCSAA);
            cfg.rcsaaSolverVariant = RCSAASolverVariant.DRO_EXTENSIVE;
            Solution solution = new DROModel().solve(
                    new Data(weekly.laneNames, train, thetaNow, params), cfg);
            expected = Double.NaN;
            modelObjective = solution.objValue;
            y = solution.y;
            solverStatus = solution.solverStatus;
            certifiedOptimal = solution.certifiedOptimal;
            relativeGap = solution.relativeGap;
        } else {
            Config cfg = baseConfig(k, Double.isFinite(cH) ? cH : 1.0, 1.0, solveMode);
            Solution solution = new SAAModel().solve(
                    new Data(weekly.laneNames, train, thetaNow, params), cfg, null);
            expected = solution.objValue;
            modelObjective = solution.objValue;
            y = solution.y;
            solverStatus = solution.solverStatus;
            certifiedOptimal = solution.certifiedOptimal;
            relativeGap = solution.relativeGap;
        }
        double solveTime = (System.nanoTime() - started) / 1e9;
        SecondStageEvaluator.Result recourse = SecondStageEvaluator.evaluate(
                params, y, test.demand().clone(), true);

        Result result = new Result();
        result.method = method;
        result.k = k;
        result.cH = cH;
        result.lambda = lambda;
        result.expected = expected;
        result.modelObjective = modelObjective;
        result.realized = recourse.objective;
        result.transport = recourse.transportCost;
        result.spot = recourse.spotCost;
        result.penalty = recourse.penaltyCost;
        result.mqcShortfallQuantity = recourse.mqcShortfallQuantity;
        result.solveTimeSec = solveTime;
        result.selectedCount = selectedCount(y);
        result.totalSelectedMqc = selectedSum(params.p, y);
        result.meanSelectedPenaltyRate = selectedMean(params.h, y);
        result.selectedMqcPenaltyExposure = selectedProductSum(params.p, params.h, y);
        result.totalSelectedCapacity = selectedSum(params.M, y);
        result.demandTotal = Arrays.stream(test.demand()).sum();
        result.yBinary = yBinary(y);
        result.solverStatus = solverStatus;
        result.certifiedOptimal = certifiedOptimal;
        result.relativeGap = relativeGap;
        return result;
    }

    /**
     * Selects a fixed carrier count without reading the outer OOS week.
     * The final 15 weeks are validation queries. Their training window is the
     * a fixed subtraining window, an expanding window, or the latest fixed-size window according to
     * the declared protocol. Every candidate count uses the same kernel settings
     * and validation weeks; ties favor the smaller count.
     */
    private static CardinalitySelection selectCardinality(List<Sample> outerTrain,
                                                           int k,
                                                           double cH,
                                                           ProcurementParams params,
                                                           ExactCache exactCache) {
        if (CARDINALITY_SUBTRAIN_SIZE < 2 || CARDINALITY_SUBTRAIN_SIZE >= TRAIN_SIZE) {
            throw new IllegalArgumentException("Cardinality subtraining size must be in [2,49].");
        }
        if (outerTrain.size() != CARDINALITY_SUBTRAIN_SIZE + CARDINALITY_VALIDATION_SIZE) {
            throw new IllegalArgumentException("Cardinality validation requires exactly 50 weeks.");
        }
        int minimumCount = Math.max(params.alpha, CARDINALITY_MIN);
        if (minimumCount > params.beta) {
            throw new IllegalArgumentException("No feasible cardinality candidate in ["
                    + minimumCount + ',' + params.beta + "].");
        }
        if (!Set.of("fixed", "expanding", "rolling", "rolling35")
                .contains(CARDINALITY_VALIDATION_PROTOCOL)) {
            throw new IllegalArgumentException("Unsupported cardinality validation protocol: "
                    + CARDINALITY_VALIDATION_PROTOCOL);
        }
        double[] validationTotals = new double[params.beta - minimumCount + 1];
        for (int offset = 0; offset < CARDINALITY_VALIDATION_SIZE; offset++) {
            int validationIndex = CARDINALITY_SUBTRAIN_SIZE + offset;
            Sample validation = outerTrain.get(validationIndex);
            boolean rolling = CARDINALITY_VALIDATION_PROTOCOL.equals("rolling")
                    || CARDINALITY_VALIDATION_PROTOCOL.equals("rolling35");
            int subtrainStart = rolling
                    ? validationIndex - CARDINALITY_SUBTRAIN_SIZE : 0;
            int subtrainEnd = CARDINALITY_VALIDATION_PROTOCOL.equals("fixed")
                    ? CARDINALITY_SUBTRAIN_SIZE : validationIndex;
            List<Sample> subtrain = BatchRunner.deepCopySamples(
                    outerTrain.subList(subtrainStart, subtrainEnd));
            CovariateVector thetaValidation = new CovariateVector(
                    validation.theta.values().clone());
            thetaValidation = standardize(subtrain, thetaValidation);
            Config weightConfig = baseConfig(k, cH, 0.0, SolveMode.CSAA);
            new WeightCalculator(WeightCalculator.buildKernel(weightConfig),
                    new EuclideanDistance()).computeKernelWeights(
                    subtrain, thetaValidation, weightConfig);
            for (int count = minimumCount; count <= params.beta; count++) {
                ExactSolution solution = exactCache.solve(subtrain, 0.0, count);
                validationTotals[count - minimumCount] +=
                        exactCache.cost(solution, validation.period.tIndex);
            }
        }
        int bestCount = minimumCount;
        double bestMean = Double.POSITIVE_INFINITY;
        for (int count = minimumCount; count <= params.beta; count++) {
            double validationMean = validationTotals[count - minimumCount]
                    / CARDINALITY_VALIDATION_SIZE;
            if (validationMean + OBJECTIVE_TOLERANCE < bestMean) {
                bestMean = validationMean;
                bestCount = count;
            }
        }
        return new CardinalitySelection(bestCount, bestMean);
    }

    /** Main coverage convention: p follows retained eligible baseline demand. */
    static ProcurementParams withCoverageAndMqc(ProcurementParams source,
                                                double[] baselineDemand,
                                                double targetCoverage,
                                                double mqcScale) {
        int lanesPerCarrier = Math.max(1,
                Math.min(source.J, (int) Math.round(targetCoverage * source.J)));
        double capacityScale = NORMALIZE_COVERAGE_CAPACITY
                ? (double) source.J / lanesPerCarrier : 1.0;
        List<Integer> laneOrder = new ArrayList<>(source.J);
        for (int j = 0; j < source.J; j++) laneOrder.add(j);
        Collections.shuffle(laneOrder, new Random(COVERAGE_MASK_SEED));

        double[][] q = new double[source.I][source.J];
        double[][] r = new double[source.I][source.J];
        boolean[][] eligible = new boolean[source.I][source.J];
        double[] p = new double[source.I];
        double[] h = new double[source.I];

        for (int i = 0; i < source.I; i++) {
            double fullEligibleDemand = 0.0;
            for (int j = 0; j < source.J; j++) {
                if (source.eligible[i][j]) fullEligibleDemand += baselineDemand[j];
            }
            if (!(fullEligibleDemand > 0.0)) {
                throw new IllegalStateException("Full-coverage carrier has no positive baseline demand: " + i);
            }
            double originalMqcRatio = source.p[i] / fullEligibleDemand;
            int start = (int) Math.floor((double) i * source.J / source.I);
            double retainedEligibleDemand = 0.0;
            h[i] = MAX_PENALTY ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            for (int offset = 0; offset < lanesPerCarrier; offset++) {
                int j = laneOrder.get((start + offset) % source.J);
                eligible[i][j] = true;
                q[i][j] = source.q[i][j] * capacityScale;
                r[i][j] = source.r[i][j];
                retainedEligibleDemand += baselineDemand[j];
                h[i] = MAX_PENALTY
                        ? Math.max(h[i], r[i][j]) : Math.min(h[i], r[i][j]);
            }
            p[i] = originalMqcRatio * retainedEligibleDemand * mqcScale;
            if (!Double.isFinite(h[i])) throw new IllegalStateException("Carrier has no retained lane: " + i);
        }
        for (int j = 0; j < source.J; j++) {
            boolean covered = false;
            for (int i = 0; i < source.I; i++) covered |= eligible[i][j];
            if (!covered) throw new IllegalStateException("Coverage mask leaves lane uncovered: " + j);
        }
        return new ProcurementParams(new ArrayList<>(source.carriers), source.J,
                source.e.clone(), p, h, q, r, eligible, source.alpha, source.beta);
    }

    private static ProcurementParams withMarketSizeScale(ProcurementParams source,
                                                          double scale) {
        double[] p = source.p.clone();
        double[][] q = new double[source.I][source.J];
        for (int i = 0; i < source.I; i++) {
            p[i] *= scale;
            for (int j = 0; j < source.J; j++) q[i][j] = source.q[i][j] * scale;
        }
        return new ProcurementParams(new ArrayList<>(source.carriers), source.J,
                source.e.clone(), p, source.h.clone(), q, source.r,
                source.eligible, source.alpha, source.beta);
    }

    private static Config baseConfig(int k,
                                     double cH,
                                     double lambda,
                                     SolveMode solveMode) {
        Config cfg = TRBReviewerExperimentSupport.baseConfig(
                k, cH, KernelType.EXPONENTIAL, true, solveMode);
        cfg.lambda = lambda;
        cfg.enforceDemandEquality = true;
        return cfg;
    }

    private static CovariateVector standardize(List<Sample> train, CovariateVector thetaNow) {
        if (train.size() < 2) return thetaNow;
        StandardScaler scaler = new StandardScaler();
        scaler.fit(train, thetaNow.values().length);
        for (Sample sample : train) {
            sample.theta = new CovariateVector(scaler.transform(sample.theta.values()));
        }
        return new CovariateVector(scaler.transform(thetaNow.values()));
    }

    private static double[] meanDemand(List<Sample> samples, int lanes) {
        double[] mean = new double[lanes];
        for (Sample sample : samples) {
            for (int j = 0; j < lanes; j++) mean[j] += sample.demand()[j];
        }
        for (int j = 0; j < lanes; j++) mean[j] /= samples.size();
        return mean;
    }

    private static Set<String> methods(String raw) {
        Set<String> methods = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String method = token.trim().toUpperCase(Locale.ROOT);
            if (!Set.of("D", "SAA", "CSAA", "RCSAA", "DRO",
                    "RCSAA_LBBD", "CSAA_MATCHED_RCSAA_COUNT",
                    "CSAA_TRAIN_TUNED_CARDINALITY")
                    .contains(method)) {
                throw new IllegalArgumentException("Unsupported method: " + method);
            }
            methods.add(method);
        }
        if (methods.isEmpty()) throw new IllegalArgumentException("At least one method is required.");
        return methods;
    }

    private static void validateInputs(double coverage,
                                       double mqcScale,
                                       int startTrial,
                                       int maxTrials) {
        if (!(coverage > 0.0 && coverage <= 1.0)) {
            throw new IllegalArgumentException("coverage must be in (0,1].");
        }
        if (!(mqcScale > 0.0)) throw new IllegalArgumentException("mqcScale must be positive.");
        if (!(BETA_RATIO > 0.0 && BETA_RATIO <= 1.0)) {
            throw new IllegalArgumentException("trb.olist.betaRatio must be in (0,1].");
        }
        if (startTrial < 0 || maxTrials <= 0) {
            throw new IllegalArgumentException("Invalid trial range.");
        }
    }

    private static boolean hasIncomplete(String method,
                                         List<Selection> selections,
                                         Set<String> completed,
                                         int start,
                                         int end) {
        for (int i = start; i < end; i++) {
            if (!completed.contains(method + "#" + selections.get(i).trialId)) return true;
        }
        return false;
    }

    private static List<Selection> readSelections(Path csv) throws Exception {
        List<Selection> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                Selection row = new Selection();
                row.trialId = integer(values, columns, "trialId");
                row.testPeriodIdx = integer(values, columns, "testPeriodIdx");
                row.csaaK = integer(values, columns, "CSAA_best_k");
                row.csaaCH = decimal(values, columns, "CSAA_best_C_h");
                row.rcsaaK = integer(values, columns, "RCSAA_best_k");
                row.rcsaaCH = decimal(values, columns, "RCSAA_best_C_h");
                row.lambda = decimal(values, columns, "RCSAA_best_lambda");
                rows.add(row);
            }
        }
        return rows;
    }

    private static void initializeRows(Path csv) throws Exception {
        if (Files.exists(csv) && Files.size(csv) > 0) return;
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("coverage_target,mqc_quantity_scale,procurement_seed,coverage_mask_seed,"
                    + "method,trialId,testPeriodIdx,k,C_h,lambda,expected_obj,model_obj,realized_obj,"
                    + "solve_time_sec,selected_count,total_selected_mqc,total_selected_capacity,demand_total,"
                    + "mean_selected_penalty_rate,selected_mqc_penalty_exposure,transport_cost,spot_cost,"
                    + "penalty_cost,mqc_shortfall_quantity,solver_status,certified_optimal,relative_gap,yBinary");
            writer.newLine();
        }
    }

    private static Set<String> readCompleted(Path csv) throws Exception {
        Set<String> completed = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                completed.add(values[required(columns, "method")]
                        + "#" + integer(values, columns, "trialId"));
            }
        }
        return completed;
    }

    private static void appendRow(Path csv,
                                  double coverage,
                                  double mqcScale,
                                  Selection selection,
                                  Result result) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            writer.write(String.format(Locale.US,
                    "%.10f,%.10f,0,%d,%s,%d,%d,%d,%s,%s,%s,%.10f,%.10f,%.6f,%d,%.10f,%.10f,%.10f,"
                            + "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%s,%s,%s,%s%n",
                    coverage, mqcScale, COVERAGE_MASK_SEED, result.method,
                    selection.trialId, selection.testPeriodIdx, result.k,
                    number(result.cH), number(result.lambda), number(result.expected),
                    result.modelObjective, result.realized, result.solveTimeSec,
                    result.selectedCount, result.totalSelectedMqc, result.totalSelectedCapacity,
                    result.demandTotal, result.meanSelectedPenaltyRate,
                    result.selectedMqcPenaltyExposure, result.transport, result.spot, result.penalty,
                    result.mqcShortfallQuantity,
                    csvSafe(result.solverStatus), result.certifiedOptimal, number(result.relativeGap),
                    result.yBinary));
        }
    }

    private static void writeSummary(Path rowsCsv, Path summaryCsv) throws Exception {
        Map<String, List<ResultSummaryValue>> byMethod = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(rowsCsv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                ResultSummaryValue value = new ResultSummaryValue();
                value.realized = decimal(values, columns, "realized_obj");
                value.runtime = decimal(values, columns, "solve_time_sec");
                value.selected = decimal(values, columns, "selected_count");
                value.transport = decimal(values, columns, "transport_cost");
                value.spot = decimal(values, columns, "spot_cost");
                value.penalty = decimal(values, columns, "penalty_cost");
                value.totalSelectedMqc = decimal(values, columns, "total_selected_mqc");
                value.demandTotal = decimal(values, columns, "demand_total");
                value.meanSelectedPenaltyRate = decimal(values, columns, "mean_selected_penalty_rate");
                value.selectedMqcPenaltyExposure = decimal(values, columns, "selected_mqc_penalty_exposure");
                byMethod.computeIfAbsent(values[required(columns, "method")], ignored -> new ArrayList<>())
                        .add(value);
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(summaryCsv, StandardCharsets.UTF_8)) {
            writer.write("method,n,mean,sd,q95,cvar95,mean_selected,mean_transport,mean_spot,mean_penalty,"
                    + "penalty_share_pct,mean_selected_mqc,mean_demand,mean_selected_mqc_to_demand,"
                    + "mean_selected_penalty_rate,mean_selected_mqc_penalty_exposure,"
                    + "mean_selected_mqc_penalty_exposure_to_demand,mean_runtime_sec,total_runtime_sec");
            writer.newLine();
            for (String method : List.of("D", "SAA", "CSAA", "RCSAA", "RCSAA_LBBD", "DRO",
                    "CSAA_MATCHED_RCSAA_COUNT", "CSAA_TRAIN_TUNED_CARDINALITY")) {
                List<ResultSummaryValue> values = byMethod.get(method);
                if (values == null || values.isEmpty()) continue;
                List<Double> costs = values.stream().map(value -> value.realized).sorted().toList();
                double mean = mean(costs);
                double q95 = percentile(costs, 0.95);
                double cvar = values.stream().mapToDouble(value -> value.realized)
                        .filter(value -> value + 1e-9 >= q95).average().orElse(Double.NaN);
                double meanPenalty = values.stream().mapToDouble(value -> value.penalty).average().orElse(Double.NaN);
                writer.write(String.format(Locale.US,
                        "%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        method, values.size(), mean, sampleStd(costs, mean), q95, cvar,
                        values.stream().mapToDouble(value -> value.selected).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.transport).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.spot).average().orElse(Double.NaN),
                        meanPenalty, 100.0 * meanPenalty / mean,
                        values.stream().mapToDouble(value -> value.totalSelectedMqc).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.demandTotal).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.totalSelectedMqc / value.demandTotal).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.meanSelectedPenaltyRate).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.selectedMqcPenaltyExposure).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.selectedMqcPenaltyExposure / value.demandTotal).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.runtime).average().orElse(Double.NaN),
                        values.stream().mapToDouble(value -> value.runtime).sum()));
            }
        }
    }

    private static void writeParameterAudit(Path csv,
                                            ProcurementParams full,
                                            ProcurementParams treatment,
                                            double[] baselineDemand,
                                            double coverage,
                                            double mqcScale) throws Exception {
        double baselineTotal = Arrays.stream(baselineDemand).sum();
        int minLaneCarriers = Integer.MAX_VALUE;
        int maxLaneCarriers = 0;
        int eligibleCount = 0;
        for (int j = 0; j < treatment.J; j++) {
            int count = 0;
            for (int i = 0; i < treatment.I; i++) if (treatment.eligible[i][j]) count++;
            minLaneCarriers = Math.min(minLaneCarriers, count);
            maxLaneCarriers = Math.max(maxLaneCarriers, count);
        }
        for (int i = 0; i < treatment.I; i++) {
            for (int j = 0; j < treatment.J; j++) if (treatment.eligible[i][j]) eligibleCount++;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("carrier_count,alpha,beta,beta_ratio,penalty_rule,capacity_normalized,coverage_target,actual_mean_carrier_coverage,lanes_per_carrier,min_carriers_per_lane,"
                    + "max_carriers_per_lane,mqc_quantity_scale,procurement_seed,coverage_mask_seed,"
                    + "baseline_total_demand,full_sum_mqc,treatment_sum_mqc,treatment_sum_mqc_to_baseline,"
                    + "full_sum_capacity,treatment_sum_capacity,treatment_sum_capacity_to_full,"
                    + "mean_min_eligible_penalty,min_min_eligible_penalty,max_min_eligible_penalty");
            writer.newLine();
            writer.write(String.format(Locale.US,
                    "%d,%d,%d,%.10f,%s,%s,%.10f,%.10f,%d,%d,%d,%.10f,0,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                    NUM_CARRIERS, treatment.alpha, treatment.beta, BETA_RATIO,
                    MAX_PENALTY ? "max" : "min",
                    NORMALIZE_COVERAGE_CAPACITY, coverage,
                    (double) eligibleCount / treatment.I / treatment.J,
                    eligibleCount / treatment.I, minLaneCarriers, maxLaneCarriers,
                    mqcScale, COVERAGE_MASK_SEED, baselineTotal,
                    Arrays.stream(full.p).sum(), Arrays.stream(treatment.p).sum(),
                    Arrays.stream(treatment.p).sum() / baselineTotal,
                    Arrays.stream(full.M).sum(), Arrays.stream(treatment.M).sum(),
                    Arrays.stream(treatment.M).sum() / Arrays.stream(full.M).sum(),
                    Arrays.stream(treatment.h).average().orElse(Double.NaN),
                    Arrays.stream(treatment.h).min().orElse(Double.NaN),
                    Arrays.stream(treatment.h).max().orElse(Double.NaN)));
        }
    }

    private static Map<String, Integer> header(String line) {
        if (line == null) throw new IllegalArgumentException("Missing CSV header.");
        String[] names = line.replace("\ufeff", "").split(",", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < names.length; i++) columns.put(names[i].trim(), i);
        return columns;
    }

    private static int integer(String[] values, Map<String, Integer> columns, String name) {
        return Integer.parseInt(values[required(columns, name)].trim());
    }

    private static double decimal(String[] values, Map<String, Integer> columns, String name) {
        return Double.parseDouble(values[required(columns, name)].trim());
    }

    private static int required(Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null) throw new IllegalArgumentException("Missing column: " + name);
        return index;
    }

    private static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.US, "%.10f", value) : "NA";
    }

    private static String csvSafe(String value) {
        if (value == null) return "";
        return value.replace(',', ';').replace('\n', ' ').replace('\r', ' ');
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static double selectedSum(double[] values, double[] y) {
        double sum = 0.0;
        for (int i = 0; i < values.length; i++) if (y[i] > 0.5) sum += values[i];
        return sum;
    }

    private static double selectedMean(double[] values, double[] y) {
        int count = selectedCount(y);
        return count == 0 ? Double.NaN : selectedSum(values, y) / count;
    }

    private static double selectedProductSum(double[] first, double[] second, double[] y) {
        double sum = 0.0;
        for (int i = 0; i < first.length; i++) {
            if (y[i] > 0.5) sum += first[i] * second[i];
        }
        return sum;
    }

    private static String yBinary(double[] y) {
        StringBuilder result = new StringBuilder(y.length);
        for (double value : y) result.append(value > 0.5 ? '1' : '0');
        return result.toString();
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double sampleStd(List<Double> values, double mean) {
        if (values.size() < 2) return 0.0;
        double sum = 0.0;
        for (double value : values) sum += (value - mean) * (value - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    private static double percentile(List<Double> sorted, double probability) {
        double position = probability * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) * (1.0 - fraction) + sorted.get(upper) * fraction;
    }

    private static final class ExactCache {
        final List<double[]> feasibleY;
        final double[][] qByPeriod;

        ExactCache(List<double[]> feasibleY, double[][] qByPeriod) {
            this.feasibleY = feasibleY;
            this.qByPeriod = qByPeriod;
        }

        static ExactCache build(ProcurementParams params,
                                WeeklyWideLoader.Result weekly) throws Exception {
            List<double[]> feasible = enumerateFeasibleSelections(params.I, params.alpha, params.beta);
            double[][] q = new double[feasible.size()][weekly.periods.size()];
            long started = System.nanoTime();
            for (int yi = 0; yi < feasible.size(); yi++) {
                try (FixedYRecourseModel model = new FixedYRecourseModel(params, feasible.get(yi))) {
                    for (int t = 0; t < weekly.periods.size(); t++) {
                        q[yi][t] = model.evaluate(weekly.periods.get(t).demandSum);
                    }
                }
                if ((yi + 1) % 100 == 0 || yi + 1 == feasible.size()) {
                    System.out.printf(Locale.US, "exact cache %d/%d elapsed=%.3fs%n",
                            yi + 1, feasible.size(), (System.nanoTime() - started) / 1e9);
                }
            }
            return new ExactCache(feasible, q);
        }

        ExactSolution solve(List<Sample> weightedTrain, double lambda) {
            return solve(weightedTrain, lambda, -1);
        }

        ExactSolution solve(List<Sample> weightedTrain,
                            double lambda,
                            int requiredSelectedCount) {
            double[] weights = normalizedWeights(weightedTrain);
            double bestObjective = Double.POSITIVE_INFINITY;
            double bestMean = Double.NaN;
            double[] bestY = null;
            int bestIndex = -1;
            for (int yi = 0; yi < feasibleY.size(); yi++) {
                if (requiredSelectedCount >= 0
                        && selectedCount(feasibleY.get(yi)) != requiredSelectedCount) continue;
                double mean = 0.0;
                for (int s = 0; s < weightedTrain.size(); s++) {
                    mean += weights[s] * qByPeriod[yi][weightedTrain.get(s).period.tIndex];
                }
                double variance = 0.0;
                for (int s = 0; s < weightedTrain.size(); s++) {
                    double difference = qByPeriod[yi][weightedTrain.get(s).period.tIndex] - mean;
                    variance += weights[s] * difference * difference;
                }
                double objective = mean + lambda * Math.sqrt(Math.max(0.0, variance));
                if (objective + OBJECTIVE_TOLERANCE < bestObjective) {
                    bestObjective = objective;
                    bestMean = mean;
                    bestY = feasibleY.get(yi).clone();
                    bestIndex = yi;
                }
            }
            if (bestY == null) {
                throw new IllegalStateException("Exact cache found no solution for selected count "
                        + requiredSelectedCount + '.');
            }
            return new ExactSolution(bestObjective, bestMean, bestY, bestIndex);
        }

        double cost(ExactSolution solution, int periodIndex) {
            if (solution.feasibleIndex < 0 || solution.feasibleIndex >= qByPeriod.length) {
                throw new IllegalArgumentException("Invalid cached feasible-selection index.");
            }
            return qByPeriod[solution.feasibleIndex][periodIndex];
        }
    }

    private static final class FixedYRecourseModel implements AutoCloseable {
        final IloCplex cplex;
        final IloRange[] demandConstraints;

        FixedYRecourseModel(ProcurementParams p, double[] y) throws Exception {
            cplex = new IloCplex();
            cplex.setOut(null);
            IloNumVar[][] x = new IloNumVar[p.I][p.J];
            for (int i = 0; i < p.I; i++) {
                for (int j = 0; j < p.J; j++) {
                    x[i][j] = cplex.numVar(0.0,
                            p.eligible[i][j] ? p.q[i][j] : 0.0, IloNumVarType.Float);
                }
            }
            IloNumVar[] spot = new IloNumVar[p.J];
            for (int j = 0; j < p.J; j++) {
                spot[j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float);
            }
            IloNumVar[] shortfall = new IloNumVar[p.I];
            for (int i = 0; i < p.I; i++) {
                shortfall[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float);
            }
            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int i = 0; i < p.I; i++) {
                for (int j = 0; j < p.J; j++) {
                    if (p.eligible[i][j]) objective.addTerm(p.r[i][j], x[i][j]);
                }
                objective.addTerm(p.h[i], shortfall[i]);
            }
            for (int j = 0; j < p.J; j++) objective.addTerm(p.e[j], spot[j]);
            cplex.addMinimize(objective);

            demandConstraints = new IloRange[p.J];
            for (int j = 0; j < p.J; j++) {
                IloLinearNumExpr served = cplex.linearNumExpr();
                for (int i = 0; i < p.I; i++) served.addTerm(1.0, x[i][j]);
                served.addTerm(1.0, spot[j]);
                demandConstraints[j] = cplex.addEq(served, 0.0);
            }
            for (int i = 0; i < p.I; i++) {
                IloLinearNumExpr assigned = cplex.linearNumExpr();
                for (int j = 0; j < p.J; j++) assigned.addTerm(1.0, x[i][j]);
                double selected = y[i] > 0.5 ? 1.0 : 0.0;
                IloLinearNumExpr lower = cplex.linearNumExpr(p.p[i] * selected);
                lower.addTerm(-1.0, shortfall[i]);
                cplex.addLe(lower, assigned);
                cplex.addLe(assigned, p.M[i] * selected);
            }
        }

        double evaluate(double[] demand) throws Exception {
            for (int j = 0; j < demand.length; j++) {
                demandConstraints[j].setBounds(demand[j], demand[j]);
            }
            if (!cplex.solve()) {
                throw new IllegalStateException("Fixed-y recourse failed: " + cplex.getCplexStatus());
            }
            return cplex.getObjValue();
        }

        @Override
        public void close() {
            cplex.end();
        }
    }

    private static List<double[]> enumerateFeasibleSelections(int carriers, int alpha, int beta) {
        List<double[]> selections = new ArrayList<>();
        int totalMasks = 1 << carriers;
        for (int mask = 0; mask < totalMasks; mask++) {
            int selected = Integer.bitCount(mask);
            if (selected < alpha || selected > beta) continue;
            double[] y = new double[carriers];
            for (int i = 0; i < carriers; i++) y[i] = ((mask >>> i) & 1) == 1 ? 1.0 : 0.0;
            selections.add(y);
        }
        return selections;
    }

    private static double[] normalizedWeights(List<Sample> samples) {
        double[] weights = new double[samples.size()];
        double sum = 0.0;
        for (int i = 0; i < samples.size(); i++) {
            weights[i] = Math.max(samples.get(i).weight, WEIGHT_FLOOR);
            sum += weights[i];
        }
        for (int i = 0; i < weights.length; i++) weights[i] /= sum;
        return weights;
    }

    private static final class Selection {
        int trialId;
        int testPeriodIdx;
        int csaaK;
        double csaaCH;
        int rcsaaK;
        double rcsaaCH;
        double lambda;
    }

    private static final class Result {
        String method;
        int k;
        double cH;
        double lambda;
        double expected;
        double modelObjective;
        double realized;
        double solveTimeSec;
        int selectedCount;
        double totalSelectedMqc;
        double meanSelectedPenaltyRate;
        double selectedMqcPenaltyExposure;
        double totalSelectedCapacity;
        double demandTotal;
        double transport;
        double spot;
        double penalty;
        double mqcShortfallQuantity;
        String solverStatus;
        boolean certifiedOptimal;
        double relativeGap;
        String yBinary;
    }

    private static final class ResultSummaryValue {
        double realized;
        double runtime;
        double selected;
        double transport;
        double spot;
        double penalty;
        double totalSelectedMqc;
        double demandTotal;
        double meanSelectedPenaltyRate;
        double selectedMqcPenaltyExposure;
    }

    private record ExactSolution(double objective,
                                 double mean,
                                 double[] y,
                                 int feasibleIndex) {
    }

    private record CardinalitySelection(int count, double validationMean) {
    }
}
