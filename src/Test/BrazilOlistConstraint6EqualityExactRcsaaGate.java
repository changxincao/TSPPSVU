package Test;

import Basic.CovariateVector;
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
import Model.SecondStageEvaluator;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Fixed-parameter exact RCSAA gate for constraint (6). The original data,
 * generated instance, rolling windows, kernel settings and selected
 * k/C_h/lambda are frozen; only the demand relation changes between GE and EQ.
 */
public final class BrazilOlistConstraint6EqualityExactRcsaaGate {

    private static final int TRAIN_SIZE = 50;
    private static final int NUM_CARRIERS = 10;
    private static final double WEIGHT_FLOOR = 1e-8;
    private static final double OBJECTIVE_TOLERANCE = 1e-4;

    private static final Path WEEKLY = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
    private static final Path SELECTED_PARAMS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "02_旧结果_弃用/02_每个trial最优参数/每个trial最终选中参数表.csv");
    private static final Path OLD_EXACT_RESULTS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "03_35_15_滚动CV结果/04_RCSAAEXT_套用DRO最终选中参数_真实样本外结果/"
                    + "01_样本外逐trial结果与汇总/"
                    + "RCSAAEXT_35_15_套用DRO最终选中参数对应的真实样本外_逐trial结果.csv");
    private static final Path OUTPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "14_约束6等式重跑_20260807/03_固定原参数_RCSAAEXT");

    private BrazilOlistConstraint6EqualityExactRcsaaGate() {
    }

    public static void main(String[] args) throws Exception {
        String relation = args.length > 0 ? args[0].trim().toUpperCase(Locale.ROOT) : "GE";
        int startTrial = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int maxTrials = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;
        if (!relation.equals("GE") && !relation.equals("EQ")) {
            throw new IllegalArgumentException("relation must be GE or EQ: " + relation);
        }
        boolean equality = relation.equals("EQ");

        Files.createDirectories(OUTPUT);
        Path rowsCsv = OUTPUT.resolve("constraint6_" + relation + "_rcsaaext_trials.csv");
        initializeCsv(rowsCsv);
        Set<Integer> completed = readCompletedTrials(rowsCsv);

        List<Selection> selections = readSelections(SELECTED_PARAMS);
        selections.sort(Comparator.comparingInt(row -> row.trialId));
        Map<Integer, OldExactResult> oldResults = readOldExactResults(OLD_EXACT_RESULTS);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY);
        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : new int[]{1, 2, 3}) {
            Config cfg = baseConfig(k, 1.0);
            samplesByK.put(k,
                    SampleBuilder.buildFromPeriods(weekly.periods, weekly.laneNames, cfg).samples);
        }

        Config instanceConfig = baseConfig(3, 1.0);
        ProcurementParams params = InstanceGenerator.generate(
                NUM_CARRIERS,
                TRBReviewerExperimentSupport.baselineDemand(weekly.periods),
                new InstanceGenerator.GenConfig(),
                instanceConfig);

        System.out.println("Exact RCSAA fixed-parameter gate relation=" + relation);
        System.out.println("building equality-aware global Q(y,d_t) cache once for all k and trials");
        ExactCache cache = ExactCache.build(params, weekly, equality);

        int end = Math.min(selections.size(), startTrial + maxTrials);
        for (int index = startTrial; index < end; index++) {
            Selection selection = selections.get(index);
            if (completed.contains(selection.trialId)) {
                System.out.println("skip completed trial=" + selection.trialId);
                continue;
            }
            Result result = solveOne(
                    selection, samplesByK.get(selection.k), params, cache, equality);
            OldExactResult old = oldResults.get(selection.trialId);
            append(rowsCsv, relation, selection, result, old);
            System.out.printf(Locale.US,
                    "done trial=%d test=%d k=%d C=%.3f lambda=%.3f realized=%.10f oldExactGE=%.10f deltaOld=%.4f%% time=%.6fs selected=%d y=%s%n",
                    selection.trialId, selection.testPeriodIdx, selection.k, selection.cH,
                    selection.lambda, result.realized, old == null ? Double.NaN : old.realized,
                    old == null ? Double.NaN : 100.0 * (result.realized - old.realized) / old.realized,
                    result.solveTimeSec, result.selectedCount, result.yBinary);
        }
        writeSummary(rowsCsv, OUTPUT.resolve("constraint6_" + relation + "_rcsaaext_summary.csv"));
    }

    private static Result solveOne(Selection selection,
                                   List<Sample> samples,
                                   ProcurementParams params,
                                   ExactCache cache,
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

        Config cfg = baseConfig(selection.k, selection.cH);
        WeightCalculator calculator = new WeightCalculator(
                WeightCalculator.buildKernel(cfg), new EuclideanDistance());
        calculator.computeKernelWeights(train, thetaNow, cfg);

        long started = System.nanoTime();
        ExactSolution exact = cache.solve(train, selection.lambda);
        double solveTimeSec = (System.nanoTime() - started) / 1e9;
        SecondStageEvaluator.Result recourse = SecondStageEvaluator.evaluate(
                params, exact.y, test.demand().clone(), equality);

        Result result = new Result();
        result.expected = exact.mean;
        result.modelObjective = exact.objective;
        result.realized = recourse.objective;
        result.transport = recourse.transportCost;
        result.spot = recourse.spotCost;
        result.penalty = recourse.penaltyCost;
        result.solveTimeSec = solveTimeSec;
        result.selectedCount = selectedCount(exact.y);
        result.yBinary = yBinary(exact.y);
        return result;
    }

    private static Config baseConfig(int k, double cH) {
        return TRBReviewerExperimentSupport.baseConfig(
                k, cH, KernelType.EXPONENTIAL, true, SolveMode.RCSAA);
    }

    private static final class ExactCache {
        final List<double[]> feasibleY;
        final double[][] qByPeriod;

        ExactCache(List<double[]> feasibleY, double[][] qByPeriod) {
            this.feasibleY = feasibleY;
            this.qByPeriod = qByPeriod;
        }

        static ExactCache build(ProcurementParams params,
                                WeeklyWideLoader.Result weekly,
                                boolean equality) throws Exception {
            List<double[]> feasible = enumerateFeasibleSelections(
                    params.I, params.alpha, params.beta);
            double[][] q = new double[feasible.size()][weekly.periods.size()];
            long started = System.nanoTime();
            for (int yi = 0; yi < feasible.size(); yi++) {
                try (FixedYRecourseModel model = new FixedYRecourseModel(
                        params, feasible.get(yi), equality)) {
                    for (int t = 0; t < weekly.periods.size(); t++) {
                        q[yi][t] = model.evaluate(weekly.periods.get(t).demandSum);
                    }
                }
                if ((yi + 1) % 100 == 0 || yi + 1 == feasible.size()) {
                    System.out.printf(Locale.US,
                            "cache progress %d/%d elapsedSec=%.3f%n",
                            yi + 1, feasible.size(), seconds(started));
                }
            }
            System.out.printf(Locale.US, "cache done feasibleY=%d periods=%d totalSec=%.3f%n",
                    feasible.size(), weekly.periods.size(), seconds(started));
            return new ExactCache(feasible, q);
        }

        ExactSolution solve(List<Sample> weightedTrain, double lambda) {
            double[] weights = normalizedWeights(weightedTrain);
            double bestObjective = Double.POSITIVE_INFINITY;
            double bestMean = Double.NaN;
            double[] bestY = null;

            for (int yi = 0; yi < feasibleY.size(); yi++) {
                double mean = 0.0;
                for (int s = 0; s < weightedTrain.size(); s++) {
                    int period = weightedTrain.get(s).period.tIndex;
                    mean += weights[s] * qByPeriod[yi][period];
                }
                double variance = 0.0;
                for (int s = 0; s < weightedTrain.size(); s++) {
                    int period = weightedTrain.get(s).period.tIndex;
                    double difference = qByPeriod[yi][period] - mean;
                    variance += weights[s] * difference * difference;
                }
                double objective = mean + lambda * Math.sqrt(Math.max(0.0, variance));
                if (objective + OBJECTIVE_TOLERANCE < bestObjective) {
                    bestObjective = objective;
                    bestMean = mean;
                    bestY = feasibleY.get(yi).clone();
                }
            }
            if (bestY == null) throw new IllegalStateException("Exact cache found no solution.");
            return new ExactSolution(bestObjective, bestMean, bestY);
        }
    }

    private static final class FixedYRecourseModel implements AutoCloseable {
        final IloCplex cplex;
        final IloRange[] demandConstraints;
        final boolean equality;

        FixedYRecourseModel(ProcurementParams p, double[] y, boolean equality) throws Exception {
            this.equality = equality;
            cplex = new IloCplex();
            cplex.setOut(null);

            IloNumVar[][] x = new IloNumVar[p.I][p.J];
            for (int i = 0; i < p.I; i++) {
                for (int j = 0; j < p.J; j++) {
                    double upper = p.eligible[i][j] ? p.q[i][j] : 0.0;
                    x[i][j] = cplex.numVar(0.0, upper, IloNumVarType.Float);
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
                demandConstraints[j] = equality
                        ? cplex.addEq(served, 0.0)
                        : cplex.addGe(served, 0.0);
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
            for (int j = 0; j < demandConstraints.length; j++) {
                if (equality) {
                    demandConstraints[j].setBounds(demand[j], demand[j]);
                } else {
                    demandConstraints[j].setLB(demand[j]);
                }
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
            for (int i = 0; i < carriers; i++) {
                y[i] = ((mask >>> i) & 1) == 1 ? 1.0 : 0.0;
            }
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

    private static Map<Integer, OldExactResult> readOldExactResults(Path csv) throws Exception {
        Map<Integer, OldExactResult> rows = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                OldExactResult row = new OldExactResult();
                row.realized = decimal(values, columns, "realized_obj");
                rows.put(integer(values, columns, "trialId"), row);
            }
        }
        return rows;
    }

    private static Map<String, Integer> header(String line) {
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
        if (index == null) throw new IllegalArgumentException("Missing column " + name);
        return index;
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static String yBinary(double[] y) {
        StringBuilder binary = new StringBuilder(y.length);
        for (double value : y) binary.append(value > 0.5 ? '1' : '0');
        return binary.toString();
    }

    private static void initializeCsv(Path csv) throws Exception {
        if (Files.exists(csv) && Files.size(csv) > 0) return;
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("relation,trialId,testPeriodIdx,k,C_h,lambda,expected_obj,model_obj,"
                    + "realized_obj,old_exact_ge_realized,delta_vs_old_exact_ge_pct,solve_time_sec,"
                    + "selected_count,transport_cost,spot_cost,penalty_cost,yBinary");
            writer.newLine();
        }
    }

    private static Set<Integer> readCompletedTrials(Path csv) throws Exception {
        Set<Integer> completed = new HashSet<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) completed.add(integer(line.split(",", -1), columns, "trialId"));
            }
        }
        return completed;
    }

    private static void append(Path csv,
                               String relation,
                               Selection selection,
                               Result result,
                               OldExactResult old) throws Exception {
        double oldRealized = old == null ? Double.NaN : old.realized;
        double delta = Double.isFinite(oldRealized)
                ? 100.0 * (result.realized - oldRealized) / oldRealized : Double.NaN;
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            writer.write(String.format(Locale.US,
                    "%s,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%.10f,%.10f,%.10f,%s",
                    relation, selection.trialId, selection.testPeriodIdx, selection.k, selection.cH,
                    selection.lambda, result.expected, result.modelObjective, result.realized,
                    oldRealized, delta, result.solveTimeSec, result.selectedCount,
                    result.transport, result.spot, result.penalty, result.yBinary));
            writer.newLine();
        }
    }

    private static void writeSummary(Path rowsCsv, Path summaryCsv) throws Exception {
        List<Double> realized = new ArrayList<>();
        List<Double> selected = new ArrayList<>();
        List<Double> times = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(rowsCsv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                realized.add(decimal(values, columns, "realized_obj"));
                selected.add(decimal(values, columns, "selected_count"));
                times.add(decimal(values, columns, "solve_time_sec"));
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(summaryCsv, StandardCharsets.UTF_8)) {
            writer.write("n,mean_realized,std_realized,min_realized,p20_realized,p50_realized,"
                    + "p75_realized,p80_realized,p95_realized,max_realized,mean_selected,mean_solve_time_sec");
            writer.newLine();
            List<Double> sorted = new ArrayList<>(realized);
            sorted.sort(Double::compareTo);
            double mean = mean(realized);
            writer.write(String.format(Locale.US,
                    "%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f",
                    realized.size(), mean, sampleStd(realized, mean), percentile(sorted, 0.0),
                    percentile(sorted, 0.2), percentile(sorted, 0.5), percentile(sorted, 0.75),
                    percentile(sorted, 0.8), percentile(sorted, 0.95), percentile(sorted, 1.0),
                    mean(selected), mean(times)));
            writer.newLine();
        }
    }

    private static double mean(List<Double> values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.size();
    }

    private static double sampleStd(List<Double> values, double mean) {
        double sum = 0.0;
        for (double value : values) sum += (value - mean) * (value - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    private static double percentile(List<Double> sorted, double probability) {
        double position = (sorted.size() - 1) * probability;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double fraction = position - lower;
        return sorted.get(lower) * (1.0 - fraction) + sorted.get(upper) * fraction;
    }

    private static double seconds(long started) {
        return (System.nanoTime() - started) / 1e9;
    }

    private static final class Selection {
        int trialId;
        int testPeriodIdx;
        int k;
        double cH;
        double lambda;
    }

    private static final class OldExactResult {
        double realized;
    }

    private static final class ExactSolution {
        final double objective;
        final double mean;
        final double[] y;

        ExactSolution(double objective, double mean, double[] y) {
            this.objective = objective;
            this.mean = mean;
            this.y = y;
        }
    }

    private static final class Result {
        double expected;
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
