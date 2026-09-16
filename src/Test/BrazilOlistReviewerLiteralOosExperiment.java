package Test;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.WeeklyWideLoader;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Reviewer-3 literal OOS check: keep historical first-stage decisions fixed,
 * center OOS demand on each realized Olist week, and change only the
 * innovation distribution and CV. Realized test demand never enters the
 * first-stage solve.
 */
public final class BrazilOlistReviewerLiteralOosExperiment {
    private static final Path WEEKLY = TRBReviewerExperimentSupport.DEFAULT_WEEKLY_CSV;
    private static final Path SOURCE = Paths.get(
            "analysis/TRB_reviewer_revision/03_olist_rolling_min_h_coverage_mqc_20260811/"
                    + "coverage_100/mqc_100/rolling_trials.csv");
    private static final Path DEFAULT_OUTPUT = Paths.get(
            "analysis/TRB_reviewer_revision/266_reviewer_literal_olist_centered_oos_20260813");
    private static final List<Cell> CELLS = List.of(
            new Cell("lognormal_low", Distribution.LOGNORMAL, 0.1725, 11_001L),
            new Cell("lognormal_high", Distribution.LOGNORMAL, 0.3450, 11_002L),
            new Cell("uniform_low", Distribution.UNIFORM, 0.1725, 11_003L),
            new Cell("uniform_high", Distribution.UNIFORM, 0.3450, 11_004L));
    private static final List<String> METHODS = List.of("D", "SAA", "CSAA", "RCSAA", "DRO");

    private BrazilOlistReviewerLiteralOosExperiment() {
    }

    public static void main(String[] args) throws Exception {
        Path output = args.length > 0 ? Path.of(args[0]) : DEFAULT_OUTPUT;
        int draws = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        int maxTrials = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;
        if (draws <= 0 || maxTrials <= 0) throw new IllegalArgumentException("draws/maxTrials must be positive");
        requireEmpty(output);
        Files.createDirectories(output);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(WEEKLY);
        List<Decision> decisions = readDecisions(SOURCE, maxTrials);
        validateDecisionMatrix(decisions);
        ProcurementParams params = procurement(weekly);

        Path raw = output.resolve("oos_costs.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(raw, StandardCharsets.UTF_8)) {
            writer.write("cell,distribution,cv,trialId,testPeriodIdx,method,draw,total,transport,spot,penalty,mqcShortfall");
            writer.newLine();
            for (Decision decision : decisions) {
                PeriodData period = period(weekly.periods, decision.testPeriodIdx);
                double[][][] demands = demands(period.demandSum, draws, decision.trialId);
                try (FixedYRecourse model = new FixedYRecourse(params, decision.y)) {
                    for (int c = 0; c < CELLS.size(); c++) {
                        Cell cell = CELLS.get(c);
                        for (int draw = 0; draw < draws; draw++) {
                            SecondStageEvaluator.Result result = model.evaluate(demands[c][draw]);
                            writer.write(String.format(Locale.US,
                                    "%s,%s,%.6f,%d,%d,%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                                    cell.name, cell.distribution, cell.cv, decision.trialId,
                                    decision.testPeriodIdx, decision.method, draw, result.objective,
                                    result.transportCost, result.spotCost, result.penaltyCost,
                                    result.mqcShortfallQuantity));
                        }
                    }
                }
            }
        }
        summarize(raw, output.resolve("method_summary.csv"), output.resolve("validation.txt"),
                decisions.size(), draws);
    }

    private static ProcurementParams procurement(WeeklyWideLoader.Result weekly) {
        double[] baseline = TRBReviewerExperimentSupport.baselineDemand(weekly.periods);
        Config config = TRBReviewerExperimentSupport.baseConfig(
                3, 1.0, Helper.calculateHelper.KernelType.EXPONENTIAL, true, SolveMode.SAA);
        ProcurementParams full = InstanceGenerator.generate(
                10, baseline, new InstanceGenerator.GenConfig(), config);
        return BrazilOlistRollingMinHCoverageMqcExperiment.withCoverageAndMqc(
                full, baseline, 1.0, 1.0);
    }

    private static double[][][] demands(double[] center, int draws, int trial) {
        double[][][] result = new double[CELLS.size()][draws][center.length];
        for (int c = 0; c < CELLS.size(); c++) {
            Cell cell = CELLS.get(c);
            Random random = new Random(cell.seed + 1_000_003L * trial);
            double logVariance = Math.log1p(cell.cv * cell.cv);
            double logSigma = Math.sqrt(logVariance);
            for (int draw = 0; draw < draws; draw++) {
                for (int j = 0; j < center.length; j++) {
                    if (center[j] <= 0.0) continue;
                    double multiplier = cell.distribution == Distribution.LOGNORMAL
                            ? Math.exp(logSigma * random.nextGaussian() - 0.5 * logVariance)
                            : 1.0 + Math.sqrt(3.0) * cell.cv * (2.0 * random.nextDouble() - 1.0);
                    result[c][draw][j] = center[j] * multiplier;
                }
            }
        }
        return result;
    }

    private static List<Decision> readDecisions(Path source, int maxTrials) throws Exception {
        List<Decision> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                int trial = Integer.parseInt(values[columns.get("trialId")]);
                if (trial >= maxTrials) continue;
                if (!Boolean.parseBoolean(values[columns.get("certified_optimal")])) {
                    throw new IllegalStateException("Uncertified source decision trial=" + trial);
                }
                double gap = Double.parseDouble(values[columns.get("relative_gap")]);
                if (gap > 1e-4 + 1e-12) throw new IllegalStateException("Source gap exceeds tolerance");
                rows.add(new Decision(
                        values[columns.get("method")], trial,
                        Integer.parseInt(values[columns.get("testPeriodIdx")]),
                        y(values[columns.get("yBinary")])));
            }
        }
        return rows;
    }

    private static void validateDecisionMatrix(List<Decision> decisions) {
        Map<Integer, Map<String, Decision>> matrix = new LinkedHashMap<>();
        for (Decision decision : decisions) {
            Decision duplicate = matrix.computeIfAbsent(decision.trialId, ignored -> new HashMap<>())
                    .put(decision.method, decision);
            if (duplicate != null) throw new IllegalStateException("Duplicate source decision");
        }
        if (matrix.isEmpty()) throw new IllegalStateException("No source decisions");
        for (Map.Entry<Integer, Map<String, Decision>> entry : matrix.entrySet()) {
            if (!entry.getValue().keySet().equals(new java.util.HashSet<>(METHODS))) {
                throw new IllegalStateException("Incomplete methods at trial " + entry.getKey());
            }
        }
    }

    private static PeriodData period(List<PeriodData> periods, int tIndex) {
        for (PeriodData period : periods) if (period.tIndex == tIndex) return period;
        throw new IllegalArgumentException("Missing Olist period " + tIndex);
    }

    private static void summarize(Path raw,
                                  Path summary,
                                  Path validation,
                                  int decisionCount,
                                  int draws) throws Exception {
        Map<String, List<Cost>> groups = new LinkedHashMap<>();
        int rows = 0;
        double maxDecompositionError = 0.0;
        try (BufferedReader reader = Files.newBufferedReader(raw, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                Cost cost = new Cost(
                        number(values, columns, "total"), number(values, columns, "transport"),
                        number(values, columns, "spot"), number(values, columns, "penalty"));
                maxDecompositionError = Math.max(maxDecompositionError,
                        Math.abs(cost.total - cost.transport - cost.spot - cost.penalty));
                String key = values[columns.get("cell")] + "#" + values[columns.get("method")];
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(cost);
                rows++;
            }
        }
        int expectedRows = decisionCount * CELLS.size() * draws;
        if (rows != expectedRows || maxDecompositionError > 1e-6) {
            throw new IllegalStateException("OOS validation failed rows=" + rows
                    + " expected=" + expectedRows + " decomposition=" + maxDecompositionError);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(summary, StandardCharsets.UTF_8)) {
            writer.write("cell,method,n,mean,sd,q95,cvar95,meanTransport,meanSpot,meanPenalty");
            writer.newLine();
            for (Cell cell : CELLS) {
                for (String method : METHODS) {
                    List<Cost> costs = groups.get(cell.name + "#" + method);
                    if (costs == null) throw new IllegalStateException("Missing group " + cell.name + " " + method);
                    double[] values = costs.stream().mapToDouble(cost -> cost.total).sorted().toArray();
                    double mean = Arrays.stream(values).average().orElseThrow();
                    double variance = Arrays.stream(values).map(value -> (value - mean) * (value - mean))
                            .average().orElseThrow();
                    double q95 = quantile(values, 0.95);
                    double cvar = Arrays.stream(values).filter(value -> value >= q95 - 1e-12)
                            .average().orElseThrow();
                    writer.write(String.format(Locale.US,
                            "%s,%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                            cell.name, method, values.length, mean, Math.sqrt(variance), q95, cvar,
                            costs.stream().mapToDouble(cost -> cost.transport).average().orElseThrow(),
                            costs.stream().mapToDouble(cost -> cost.spot).average().orElseThrow(),
                            costs.stream().mapToDouble(cost -> cost.penalty).average().orElseThrow()));
                }
            }
        }
        Files.writeString(validation, String.format(Locale.US,
                "status=PASSED%ndecisions=%d%ncells=%d%ndrawsPerTrial=%d%noosRows=%d%n"
                        + "maxCostDecompositionError=%.12g%nsource=%s%n",
                decisionCount, CELLS.size(), draws, rows, maxDecompositionError,
                SOURCE.toAbsolutePath()), StandardCharsets.UTF_8);
    }

    private static double quantile(double[] sorted, double probability) {
        double position = probability * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double fraction = position - lower;
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction;
    }

    private static Map<String, Integer> header(String line) {
        if (line == null) throw new IllegalArgumentException("Missing CSV header");
        String[] names = line.split(",", -1);
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < names.length; i++) result.put(names[i], i);
        return result;
    }

    private static double number(String[] values, Map<String, Integer> columns, String name) {
        return Double.parseDouble(values[columns.get(name)]);
    }

    private static double[] y(String binary) {
        double[] result = new double[binary.length()];
        for (int i = 0; i < binary.length(); i++) result[i] = binary.charAt(i) == '1' ? 1.0 : 0.0;
        return result;
    }

    private static void requireEmpty(Path directory) throws Exception {
        if (Files.exists(directory)) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException("Output directory must be empty: " + directory);
                }
            }
        }
    }

    private static final class FixedYRecourse implements AutoCloseable {
        private final ProcurementParams params;
        private final IloCplex cplex;
        private final IloNumVar[][] x;
        private final IloNumVar[] spot;
        private final IloNumVar[] shortfall;
        private final IloRange[] demandConstraints;

        FixedYRecourse(ProcurementParams params, double[] y) throws Exception {
            this.params = params;
            cplex = new IloCplex();
            cplex.setOut(null);
            x = new IloNumVar[params.I][params.J];
            for (int i = 0; i < params.I; i++) {
                for (int j = 0; j < params.J; j++) {
                    x[i][j] = cplex.numVar(0.0,
                            params.eligible[i][j] ? params.q[i][j] : 0.0,
                            IloNumVarType.Float);
                }
            }
            spot = new IloNumVar[params.J];
            for (int j = 0; j < params.J; j++) {
                spot[j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float);
            }
            shortfall = new IloNumVar[params.I];
            for (int i = 0; i < params.I; i++) {
                shortfall[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float);
            }
            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int i = 0; i < params.I; i++) {
                for (int j = 0; j < params.J; j++) {
                    if (params.eligible[i][j]) objective.addTerm(params.r[i][j], x[i][j]);
                }
                objective.addTerm(params.h[i], shortfall[i]);
            }
            for (int j = 0; j < params.J; j++) objective.addTerm(params.e[j], spot[j]);
            cplex.addMinimize(objective);

            demandConstraints = new IloRange[params.J];
            for (int j = 0; j < params.J; j++) {
                IloLinearNumExpr served = cplex.linearNumExpr();
                for (int i = 0; i < params.I; i++) served.addTerm(1.0, x[i][j]);
                served.addTerm(1.0, spot[j]);
                demandConstraints[j] = cplex.addEq(served, 0.0);
            }
            for (int i = 0; i < params.I; i++) {
                IloLinearNumExpr assigned = cplex.linearNumExpr();
                for (int j = 0; j < params.J; j++) assigned.addTerm(1.0, x[i][j]);
                double selected = y[i] > 0.5 ? 1.0 : 0.0;
                IloLinearNumExpr lower = cplex.linearNumExpr(params.p[i] * selected);
                lower.addTerm(-1.0, shortfall[i]);
                cplex.addLe(lower, assigned);
                cplex.addLe(assigned, params.M[i] * selected);
            }
        }

        SecondStageEvaluator.Result evaluate(double[] demand) throws Exception {
            for (int j = 0; j < params.J; j++) {
                demandConstraints[j].setBounds(demand[j], demand[j]);
            }
            if (!cplex.solve()) throw new IllegalStateException("Fixed-y recourse failed");
            SecondStageEvaluator.Result result = new SecondStageEvaluator.Result();
            result.objective = cplex.getObjValue();
            for (int i = 0; i < params.I; i++) {
                for (int j = 0; j < params.J; j++) {
                    if (params.eligible[i][j]) {
                        result.transportCost += params.r[i][j] * cplex.getValue(x[i][j]);
                    }
                }
                double quantity = cplex.getValue(shortfall[i]);
                result.mqcShortfallQuantity += quantity;
                result.penaltyCost += params.h[i] * quantity;
            }
            for (int j = 0; j < params.J; j++) {
                result.spotCost += params.e[j] * cplex.getValue(spot[j]);
            }
            return result;
        }

        @Override
        public void close() {
            cplex.end();
        }
    }

    private enum Distribution { LOGNORMAL, UNIFORM }

    private record Cell(String name, Distribution distribution, double cv, long seed) {
    }

    private record Decision(String method, int trialId, int testPeriodIdx, double[] y) {
    }

    private record Cost(double total, double transport, double spot, double penalty) {
    }
}
