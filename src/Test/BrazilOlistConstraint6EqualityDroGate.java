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
import Helper.calculateHelper.KernelType;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.DROModel;
import Model.RCSAASolverVariant;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fixed-parameter gate for the manuscript DRO approximation. It freezes the
 * original 104-week data, 50-week rolling windows, generated procurement
 * instance, kernel parameters and lambda; only the demand relation changes.
 */
public final class BrazilOlistConstraint6EqualityDroGate {

    private static final int TRAIN_SIZE = 50;
    private static final int NUM_CARRIERS = 10;

    private static final Path WEEKLY = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
    private static final Path SELECTED_PARAMS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "02_旧结果_弃用/02_每个trial最优参数/每个trial最终选中参数表.csv");
    private static final Path OLD_DETAIL = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "02_旧结果_弃用/01_正式最终结果_4方法/四种方法最终逐trial明细.csv");
    private static final Path OUTPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "14_约束6等式重跑_20260807/02_固定原参数_DRO");

    private BrazilOlistConstraint6EqualityDroGate() {
    }

    public static void main(String[] args) throws Exception {
        String relation = args.length > 0 ? args[0].trim().toUpperCase(Locale.ROOT) : "GE";
        int startTrial = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int maxTrials = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        if (!relation.equals("GE") && !relation.equals("EQ")) {
            throw new IllegalArgumentException("relation must be GE or EQ: " + relation);
        }
        boolean equality = relation.equals("EQ");

        Files.createDirectories(OUTPUT);
        Path rowsCsv = OUTPUT.resolve("constraint6_" + relation + "_dro_trials.csv");
        initializeCsv(rowsCsv);
        Set<Integer> completedTrials = readCompletedTrials(rowsCsv);

        List<Selection> selections = readSelections(SELECTED_PARAMS);
        selections.sort(Comparator.comparingInt(row -> row.trialId));
        Map<Integer, OldResult> oldResults = readOldResults(OLD_DETAIL);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY);
        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : new int[]{1, 2, 3}) {
            Config cfg = baseConfig(k, 1.0, 1.0, equality);
            samplesByK.put(k,
                    SampleBuilder.buildFromPeriods(weekly.periods, weekly.laneNames, cfg).samples);
        }

        Config instanceCfg = baseConfig(3, 1.0, 1.0, equality);
        ProcurementParams params = InstanceGenerator.generate(
                NUM_CARRIERS,
                TRBReviewerExperimentSupport.baselineDemand(weekly.periods),
                new InstanceGenerator.GenConfig(),
                instanceCfg);

        int end = Math.min(selections.size(), startTrial + maxTrials);
        System.out.println("DRO fixed-parameter gate relation=" + relation
                + ", trials=[" + startTrial + "," + end + ")");
        System.out.println("frozen: original weekly input, I=10, W=50, seed=0, exponential kernel, old selected k/C/lambda");

        for (int index = startTrial; index < end; index++) {
            Selection selection = selections.get(index);
            if (completedTrials.contains(selection.trialId)) {
                System.out.println("skip completed trial=" + selection.trialId);
                continue;
            }
            Result row = solve(selection, samplesByK.get(selection.k), weekly, params, equality);
            OldResult old = oldResults.get(selection.trialId);
            append(rowsCsv, relation, selection, row, old);
            System.out.printf(Locale.US,
                    "done trial=%d test=%d k=%d C=%.3f lambda=%.3f realized=%.10f oldGE=%.10f deltaOld=%.4f%% time=%.3fs selected=%d y=%s%n",
                    selection.trialId, selection.testPeriodIdx, selection.k, selection.cH,
                    selection.lambda, row.realized, old == null ? Double.NaN : old.realized,
                    old == null ? Double.NaN : 100.0 * (row.realized - old.realized) / old.realized,
                    row.solveTimeSec, row.selectedCount, row.yBinary);
        }
        writeSummary(rowsCsv, OUTPUT.resolve("constraint6_" + relation + "_dro_summary.csv"));
    }

    private static Result solve(Selection selection,
                                List<Sample> samples,
                                WeeklyWideLoader.Result weekly,
                                ProcurementParams params,
                                boolean equality) throws Exception {
        int testSampleIndex = selection.testPeriodIdx - selection.k;
        int trainStart = testSampleIndex - TRAIN_SIZE;
        if (trainStart < 0 || testSampleIndex >= samples.size()) {
            throw new IllegalArgumentException("Invalid trial window: " + selection.trialId);
        }

        List<Sample> train = BatchRunner.deepCopySamples(
                new ArrayList<>(samples.subList(trainStart, testSampleIndex)));
        Sample test = samples.get(testSampleIndex);
        CovariateVector thetaNow = new CovariateVector(test.theta.values().clone());

        StandardScaler scaler = new StandardScaler();
        scaler.fit(train, thetaNow.values().length);
        for (Sample sample : train) {
            sample.theta = new CovariateVector(scaler.transform(sample.theta.values()));
        }
        thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));

        Config cfg = baseConfig(selection.k, selection.cH, selection.lambda, equality);
        WeightCalculator calculator = new WeightCalculator(
                WeightCalculator.buildKernel(cfg), new EuclideanDistance());
        calculator.computeKernelWeights(train, thetaNow, cfg);

        long started = System.nanoTime();
        Solution solution = new DROModel().solve(
                new Data(weekly.laneNames, train, thetaNow, params), cfg);
        double solveTimeSec = (System.nanoTime() - started) / 1e9;
        SecondStageEvaluator.Result recourse = SecondStageEvaluator.evaluate(
                params, solution.y, test.demand().clone(), equality);

        Result result = new Result();
        result.modelObjective = solution.objValue;
        result.realized = recourse.objective;
        result.transport = recourse.transportCost;
        result.spot = recourse.spotCost;
        result.penalty = recourse.penaltyCost;
        result.solveTimeSec = solveTimeSec;
        result.selectedCount = selectedCount(solution.y);
        result.yBinary = yBinary(solution.y);
        return result;
    }

    private static Config baseConfig(int k, double cH, double lambda, boolean equality) {
        Config cfg = TRBReviewerExperimentSupport.baseConfig(
                k, cH, KernelType.EXPONENTIAL, true, SolveMode.RCSAA);
        cfg.lambda = lambda;
        cfg.rcsaaSolverVariant = RCSAASolverVariant.DRO_EXTENSIVE;
        cfg.enforceDemandEquality = equality;
        return cfg;
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
                row.k = integer(values, columns, "RCSAA_best_k");
                row.cH = decimal(values, columns, "RCSAA_best_C_h");
                row.lambda = decimal(values, columns, "RCSAA_best_lambda");
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<Integer, OldResult> readOldResults(Path csv) throws Exception {
        Map<Integer, OldResult> rows = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                if (!values[columns.get("final_method_name")].equals("RCSAA")) continue;
                OldResult row = new OldResult();
                row.realized = decimal(values, columns, "realized_obj");
                rows.put(integer(values, columns, "trialId"), row);
            }
        }
        return rows;
    }

    private static Map<String, Integer> header(String line) {
        String[] values = line.replace("\ufeff", "").split(",", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < values.length; i++) columns.put(values[i].trim(), i);
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
        if (index == null) throw new IllegalArgumentException("Missing column " + name);
        return index;
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static String yBinary(double[] y) {
        StringBuilder result = new StringBuilder(y.length);
        for (double value : y) result.append(value > 0.5 ? '1' : '0');
        return result.toString();
    }

    private static void initializeCsv(Path csv) throws Exception {
        if (Files.exists(csv) && Files.size(csv) > 0) return;
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("relation,trialId,testPeriodIdx,k,C_h,lambda,model_obj,realized_obj,"
                    + "old_ge_rcsaa_realized,delta_vs_old_ge_pct,solve_time_sec,selected_count,"
                    + "transport_cost,spot_cost,penalty_cost,yBinary");
            writer.newLine();
        }
    }

    private static Set<Integer> readCompletedTrials(Path csv) throws Exception {
        Set<Integer> completed = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                completed.add(integer(line.split(",", -1), columns, "trialId"));
            }
        }
        return completed;
    }

    private static void append(Path csv,
                               String relation,
                               Selection selection,
                               Result result,
                               OldResult old) throws Exception {
        double oldRealized = old == null ? Double.NaN : old.realized;
        double delta = Double.isFinite(oldRealized)
                ? 100.0 * (result.realized - oldRealized) / oldRealized : Double.NaN;
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            writer.write(String.format(Locale.US,
                    "%s,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%.10f,%.10f,%.10f,%s",
                    relation, selection.trialId, selection.testPeriodIdx, selection.k, selection.cH,
                    selection.lambda, result.modelObjective, result.realized, oldRealized, delta,
                    result.solveTimeSec, result.selectedCount, result.transport, result.spot,
                    result.penalty, result.yBinary));
            writer.newLine();
        }
    }

    private static void writeSummary(Path rowsCsv, Path summaryCsv) throws Exception {
        List<Double> realized = new ArrayList<>();
        List<Double> old = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(rowsCsv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                realized.add(decimal(values, columns, "realized_obj"));
                old.add(decimal(values, columns, "old_ge_rcsaa_realized"));
                times.add(decimal(values, columns, "solve_time_sec"));
            }
        }
        double meanRealized = mean(realized);
        double meanOld = mean(old);
        try (BufferedWriter writer = Files.newBufferedWriter(summaryCsv, StandardCharsets.UTF_8)) {
            writer.write("n,mean_realized,old_ge_rcsaa_mean,delta_vs_old_ge_pct,total_solve_time_sec");
            writer.newLine();
            writer.write(String.format(Locale.US, "%d,%.10f,%.10f,%.10f,%.6f",
                    realized.size(), meanRealized, meanOld,
                    100.0 * (meanRealized - meanOld) / meanOld, sum(times)));
            writer.newLine();
        }
    }

    private static double mean(List<Double> values) {
        return sum(values) / values.size();
    }

    private static double sum(List<Double> values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
    }

    private static final class Selection {
        int trialId;
        int testPeriodIdx;
        int k;
        double cH;
        double lambda;
    }

    private static final class OldResult {
        double realized;
    }

    private static final class Result {
        double modelObjective;
        double realized;
        double solveTimeSec;
        double transport;
        double spot;
        double penalty;
        int selectedCount;
        String yBinary;
    }
}
