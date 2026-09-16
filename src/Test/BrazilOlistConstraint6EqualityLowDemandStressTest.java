package Test;

import Basic.ProcurementParams;
import Helper.calculateHelper.KernelType;
import Model.SecondStageEvaluator;
import Model.SolveMode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Counterfactual low-demand stress test for reviewer-revision diagnosis.
 *
 * <p>The already-solved Mean (D) and SAA first-stage decisions remain fixed.
 * Each actual OOS demand vector is multiplied by a demand factor and evaluated
 * with the equality recourse model. Results are stress-test evidence, not new
 * rolling-OOS observations.</p>
 */
public final class BrazilOlistConstraint6EqualityLowDemandStressTest {

    private static final Path DEFAULT_WEEKLY = TRBReviewerExperimentSupport.DEFAULT_WEEKLY_CSV;
    private static final Path DEFAULT_MAX_H_RESULTS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "14_约束6等式重跑_20260807/01_固定原参数_快速方法/"
                    + "constraint6_EQ_fast_trials.csv");
    private static final Path DEFAULT_MIN_H_RESULTS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "14_约束6等式重跑_20260807/03_MQC机制分解_快速方法/"
                    + "02_MQC罚率改为承运人最小报价/"
                    + "constraint6_EQ_pre53_calibration_trials.csv");
    private static final Path DEFAULT_OUTPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "16_低需求反事实压力测试_20260808");
    private static final double[] DEMAND_FACTORS = {0.0, 0.10, 0.25, 0.50, 0.75, 1.0};
    private static final double REPRODUCTION_TOLERANCE = 1e-4;

    private BrazilOlistConstraint6EqualityLowDemandStressTest() {
    }

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = pathArg(args, 0, DEFAULT_WEEKLY);
        Path maxHResults = pathArg(args, 1, DEFAULT_MAX_H_RESULTS);
        Path minHResults = pathArg(args, 2, DEFAULT_MIN_H_RESULTS);
        Path outputDir = pathArg(args, 3, DEFAULT_OUTPUT);
        Files.createDirectories(outputDir);

        TRBReviewerExperimentSupport.Prepared prepared = TRBReviewerExperimentSupport.prepare(
                weeklyCsv, 10, 3, 1.0, KernelType.EXPONENTIAL, true, SolveMode.SAA);
        ProcurementParams maxHParams = prepared.params;
        ProcurementParams minHParams = withMinimumEligiblePenaltyRate(maxHParams);

        List<DecisionRow> maxHDecisions = readDecisions(maxHResults);
        List<DecisionRow> minHDecisions = readDecisions(minHResults);
        validateDecisionCoverage(maxHDecisions, "h=max eligible rate");
        validateDecisionCoverage(minHDecisions, "h=min eligible rate");

        List<StressRow> rows = new ArrayList<>();
        evaluateSetting("MAX_RATE", maxHParams, maxHDecisions, prepared, rows);
        evaluateSetting("MIN_RATE", minHParams, minHDecisions, prepared, rows);

        validateFactorOneReproduction(rows);
        Path detailCsv = outputDir.resolve("low_demand_stress_per_trial.csv");
        Path summaryCsv = outputDir.resolve("low_demand_stress_paired_summary.csv");
        writeDetail(detailCsv, rows);
        writePairedSummary(summaryCsv, rows);

        System.out.println("Low-demand counterfactual stress test completed.");
        System.out.println("weeklyCsv=" + weeklyCsv.toAbsolutePath());
        System.out.println("maxHResults=" + maxHResults.toAbsolutePath());
        System.out.println("minHResults=" + minHResults.toAbsolutePath());
        System.out.println("detail=" + detailCsv.toAbsolutePath());
        System.out.println("summary=" + summaryCsv.toAbsolutePath());
        System.out.println("factor=1 reproduction tolerance=" + REPRODUCTION_TOLERANCE);
    }

    private static void evaluateSetting(String setting,
                                        ProcurementParams params,
                                        List<DecisionRow> decisions,
                                        TRBReviewerExperimentSupport.Prepared prepared,
                                        List<StressRow> output) throws Exception {
        for (DecisionRow decision : decisions) {
            double[] actualDemand = prepared.weekly.periods.get(decision.testPeriodIdx).demandSum;
            double[] y = parseBinaryDecision(decision.yBinary, params.I);
            for (double factor : DEMAND_FACTORS) {
                double[] stressDemand = scaled(actualDemand, factor);
                SecondStageEvaluator.Result evaluated = SecondStageEvaluator.evaluate(
                        params, y, stressDemand, true);
                StressRow row = new StressRow();
                row.setting = setting;
                row.method = decision.method;
                row.trialId = decision.trialId;
                row.testPeriodIdx = decision.testPeriodIdx;
                row.factor = factor;
                row.actualDemandTotal = sum(actualDemand);
                row.stressDemandTotal = sum(stressDemand);
                row.recordedActualCost = decision.recordedActualCost;
                row.objective = evaluated.objective;
                row.transport = evaluated.transportCost;
                row.spot = evaluated.spotCost;
                row.penalty = evaluated.penaltyCost;
                row.selectedCount = selectedCount(y);
                row.yBinary = decision.yBinary;
                output.add(row);
            }
        }
    }

    private static ProcurementParams withMinimumEligiblePenaltyRate(ProcurementParams source) {
        double[] h = new double[source.I];
        for (int i = 0; i < source.I; i++) {
            double minimum = Double.POSITIVE_INFINITY;
            for (int j = 0; j < source.J; j++) {
                if (source.eligible[i][j]) minimum = Math.min(minimum, source.r[i][j]);
            }
            if (!Double.isFinite(minimum)) {
                throw new IllegalStateException("Carrier has no eligible lane: " + i);
            }
            h[i] = minimum;
        }
        return new ProcurementParams(
                new ArrayList<>(source.carriers), source.J,
                source.e.clone(), source.p.clone(), h,
                copy(source.q), copy(source.r), copy(source.eligible),
                source.alpha, source.beta);
    }

    private static List<DecisionRow> readDecisions(Path csv) throws Exception {
        List<DecisionRow> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IllegalArgumentException("Empty decision CSV: " + csv);
            String[] header = headerLine.split(",", -1);
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < header.length; i++) columns.put(header[i].trim(), i);
            int methodCol = requireColumn(columns, "method");
            int trialCol = requireColumn(columns, "trialId");
            int testCol = requireColumn(columns, "testPeriodIdx");
            int realizedCol = requireColumn(columns, "realized_obj");
            int yCol = requireColumn(columns, "yBinary");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                String method = values[methodCol].trim();
                if (!method.equals("Mean") && !method.equals("SAA")) continue;
                DecisionRow row = new DecisionRow();
                row.method = method;
                row.trialId = Integer.parseInt(values[trialCol].trim());
                row.testPeriodIdx = Integer.parseInt(values[testCol].trim());
                row.recordedActualCost = Double.parseDouble(values[realizedCol].trim());
                row.yBinary = values[yCol].trim();
                rows.add(row);
            }
        }
        rows.sort((a, b) -> {
            int method = a.method.compareTo(b.method);
            return method != 0 ? method : Integer.compare(a.trialId, b.trialId);
        });
        return rows;
    }

    private static void validateDecisionCoverage(List<DecisionRow> rows, String label) {
        Map<String, Integer> counts = new HashMap<>();
        for (DecisionRow row : rows) counts.merge(row.method, 1, Integer::sum);
        if (rows.size() != 102 || counts.getOrDefault("Mean", 0) != 51
                || counts.getOrDefault("SAA", 0) != 51) {
            throw new IllegalStateException(label + " must contain 51 Mean and 51 SAA rows: " + counts);
        }
    }

    private static void validateFactorOneReproduction(List<StressRow> rows) {
        double maxError = 0.0;
        for (StressRow row : rows) {
            if (row.factor != 1.0) continue;
            maxError = Math.max(maxError, Math.abs(row.objective - row.recordedActualCost));
        }
        if (maxError > REPRODUCTION_TOLERANCE) {
            throw new IllegalStateException(String.format(Locale.US,
                    "factor=1 reproduction failed: maxAbsError=%.12f", maxError));
        }
        System.out.println(String.format(Locale.US,
                "factor=1 reproduction passed: maxAbsError=%.12f", maxError));
    }

    private static void writeDetail(Path csv, List<StressRow> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("setting,method,trialId,testPeriodIdx,demand_factor,actual_demand_total,"
                    + "stress_demand_total,recorded_actual_cost,stress_objective,transport_cost,"
                    + "spot_cost,penalty_cost,selected_count,yBinary");
            writer.newLine();
            for (StressRow row : rows) {
                writer.write(String.format(Locale.US,
                        "%s,%s,%d,%d,%.2f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%d,%s%n",
                        row.setting, row.method, row.trialId, row.testPeriodIdx, row.factor,
                        row.actualDemandTotal, row.stressDemandTotal, row.recordedActualCost,
                        row.objective, row.transport, row.spot, row.penalty,
                        row.selectedCount, row.yBinary));
            }
        }
    }

    private static void writePairedSummary(Path csv, List<StressRow> rows) throws Exception {
        Map<String, Map<Integer, Map<String, StressRow>>> grouped = new LinkedHashMap<>();
        for (StressRow row : rows) {
            String key = row.setting + "|" + String.format(Locale.US, "%.2f", row.factor);
            grouped.computeIfAbsent(key, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(row.trialId, ignored -> new HashMap<>())
                    .put(row.method, row);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("setting,demand_factor,trial_count,mean_D,mean_SAA,SAA_minus_D,"
                    + "SAA_minus_D_pct,SAA_wins,ties,SAA_losses,mean_transport_diff,"
                    + "mean_spot_diff,mean_penalty_diff,mean_selected_D,mean_selected_SAA");
            writer.newLine();
            for (Map.Entry<String, Map<Integer, Map<String, StressRow>>> entry : grouped.entrySet()) {
                int n = 0;
                int wins = 0;
                int ties = 0;
                int losses = 0;
                double sumD = 0.0;
                double sumSaa = 0.0;
                double transportDiff = 0.0;
                double spotDiff = 0.0;
                double penaltyDiff = 0.0;
                double selectedD = 0.0;
                double selectedSaa = 0.0;
                StressRow example = null;
                for (Map<String, StressRow> pair : entry.getValue().values()) {
                    StressRow d = pair.get("Mean");
                    StressRow saa = pair.get("SAA");
                    if (d == null || saa == null) continue;
                    example = d;
                    n++;
                    sumD += d.objective;
                    sumSaa += saa.objective;
                    transportDiff += saa.transport - d.transport;
                    spotDiff += saa.spot - d.spot;
                    penaltyDiff += saa.penalty - d.penalty;
                    selectedD += d.selectedCount;
                    selectedSaa += saa.selectedCount;
                    double difference = saa.objective - d.objective;
                    if (difference < -0.01) wins++;
                    else if (difference > 0.01) losses++;
                    else ties++;
                }
                if (example == null || n == 0) continue;
                double meanD = sumD / n;
                double meanSaa = sumSaa / n;
                writer.write(String.format(Locale.US,
                        "%s,%.2f,%d,%.10f,%.10f,%.10f,%.10f,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        example.setting, example.factor, n, meanD, meanSaa,
                        meanSaa - meanD, 100.0 * (meanSaa - meanD) / meanD,
                        wins, ties, losses, transportDiff / n, spotDiff / n,
                        penaltyDiff / n, selectedD / n, selectedSaa / n));
            }
        }
    }

    private static int requireColumn(Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null) throw new IllegalArgumentException("Missing CSV column: " + name);
        return index;
    }

    private static double[] parseBinaryDecision(String text, int expectedLength) {
        if (text.length() != expectedLength) {
            throw new IllegalArgumentException("Bad yBinary length: " + text);
        }
        double[] y = new double[expectedLength];
        for (int i = 0; i < expectedLength; i++) {
            char value = text.charAt(i);
            if (value != '0' && value != '1') {
                throw new IllegalArgumentException("Bad yBinary value: " + text);
            }
            y[i] = value == '1' ? 1.0 : 0.0;
        }
        return y;
    }

    private static double[] scaled(double[] source, double factor) {
        double[] result = new double[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] * factor;
        return result;
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static double sum(double[] values) {
        double total = 0.0;
        for (double value : values) total += value;
        return total;
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

    private static Path pathArg(String[] args, int index, Path fallback) {
        return args.length > index && !args[index].isBlank() ? Paths.get(args[index]) : fallback;
    }

    private static final class DecisionRow {
        String method;
        int trialId;
        int testPeriodIdx;
        double recordedActualCost;
        String yBinary;
    }

    private static final class StressRow {
        String setting;
        String method;
        int trialId;
        int testPeriodIdx;
        double factor;
        double actualDemandTotal;
        double stressDemandTotal;
        double recordedActualCost;
        double objective;
        double transport;
        double spot;
        double penalty;
        int selectedCount;
        String yBinary;
    }
}
