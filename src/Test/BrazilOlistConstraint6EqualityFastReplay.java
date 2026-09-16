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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Replays the manuscript's fast methods with all historical parameters frozen.
 * The only model change is constraint (6): sum_i x_ij + s_j == d_j.
 */
public final class BrazilOlistConstraint6EqualityFastReplay {

    private static final int TRAIN_SIZE = 50;
    private static final int NUM_CARRIERS = 10;

    private static final Path DEFAULT_WEEKLY = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
    private static final Path DEFAULT_PARAMS = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "02_旧结果_弃用/02_每个trial最优参数/每个trial最终选中参数表.csv");
    private static final Path DEFAULT_OLD_DETAIL = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "04_第一组主实验_滚动CV选最优参数并提取最终CSAA_RCSAA结果/"
                    + "02_旧结果_弃用/01_正式最终结果_4方法/四种方法最终逐trial明细.csv");
    private static final Path DEFAULT_OUTPUT = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输出/"
                    + "14_约束6等式重跑_20260807/01_固定原参数_快速方法");

    private BrazilOlistConstraint6EqualityFastReplay() {
    }

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = pathArg(args, 0, DEFAULT_WEEKLY);
        Path selectedParamCsv = pathArg(args, 1, DEFAULT_PARAMS);
        Path oldDetailCsv = pathArg(args, 2, DEFAULT_OLD_DETAIL);
        Path outDir = pathArg(args, 3, DEFAULT_OUTPUT);
        int startTrial = intArg(args, 4, 0);
        int maxTrials = intArg(args, 5, Integer.MAX_VALUE);
        List<String> methods = methodArg(args, 6);
        String demandRelation = args.length > 7 ? args[7].trim().toUpperCase(Locale.ROOT) : "GE";
        if (!demandRelation.equals("GE") && !demandRelation.equals("EQ")) {
            throw new IllegalArgumentException("demandRelation must be GE or EQ: " + demandRelation);
        }
        boolean enforceDemandEquality = demandRelation.equals("EQ");

        Files.createDirectories(outDir);
        Path gateFile = outDir.resolve("GE_REPRODUCTION_GATE_PASSED.txt");
        Path gateReportFile = outDir.resolve("GE_REPRODUCTION_GATE_REPORT.txt");
        if (enforceDemandEquality && !Files.exists(gateFile)) {
            throw new IllegalStateException(
                    "EQ run is blocked until the complete 51-trial GE reproduction gate passes: " + gateFile);
        }
        Path trialsCsv = outDir.resolve("constraint6_" + demandRelation + "_fast_trials.csv");
        Path summaryCsv = outDir.resolve("constraint6_" + demandRelation + "_fast_summary.csv");

        List<SelectedParams> selections = readSelections(selectedParamCsv);
        selections.sort((a, b) -> Integer.compare(a.trialId, b.trialId));
        Map<String, OldResult> oldResults = readOldResults(oldDetailCsv);

        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(weeklyCsv);
        Map<Integer, List<Sample>> samplesByK = new HashMap<>();
        for (int k : new int[]{1, 2, 3}) {
            Config cfg = baseConfig(k, 1.0, SolveMode.CSAA, enforceDemandEquality);
            samplesByK.put(k, SampleBuilder.buildFromPeriods(weekly.periods, weekly.laneNames, cfg).samples);
        }

        Config instanceCfg = baseConfig(3, 1.0, SolveMode.SAA, enforceDemandEquality);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(
                NUM_CARRIERS,
                TRBReviewerExperimentSupport.baselineDemand(weekly.periods),
                genCfg,
                instanceCfg);

        initializeTrialsCsv(trialsCsv);
        Set<String> completed = readCompletedKeys(trialsCsv);
        int end = Math.min(selections.size(), startTrial + maxTrials);

        System.out.println("Constraint (6) replay, demandRelation=" + demandRelation);
        System.out.println("weeklyCsv=" + weeklyCsv.toAbsolutePath());
        System.out.println("selectedParamCsv=" + selectedParamCsv.toAbsolutePath());
        System.out.println("oldDetailCsv=" + oldDetailCsv.toAbsolutePath());
        System.out.println("outDir=" + outDir.toAbsolutePath());
        System.out.println("methods=" + methods + ", trialRange=[" + startTrial + "," + end + ")");
        System.out.println("frozen: carriers=10, W=50, seed=0, z-score=true, kernel=EXPONENTIAL");
        System.out.println(enforceDemandEquality
                ? "experiment mode: demand balance =="
                : "reproduction gate: historical demand balance >=");

        for (String method : methods) {
            for (int index = startTrial; index < end; index++) {
                SelectedParams selection = selections.get(index);
                String key = method + "#" + selection.trialId;
                if (completed.contains(key)) {
                    System.out.println("skip completed " + key);
                    continue;
                }
                ResultRow row = solveOne(
                        method, selection, samplesByK, weekly.laneNames, params, oldResults,
                        enforceDemandEquality, demandRelation);
                appendRow(trialsCsv, row);
                completed.add(key);
                System.out.println(String.format(Locale.US,
                        "done method=%s trial=%d test=%d k=%d C=%s realized=%.10f old=%.10f delta=%.4f%% time=%.3fs sel=%d",
                        row.method, row.trialId, row.testPeriodIdx, row.k,
                        Double.isFinite(row.cH) ? String.format(Locale.US, "%.2f", row.cH) : "NA",
                        row.realized, row.oldRealized, row.deltaPct, row.solveTimeSec, row.selectedCount));
            }
            writeSummary(trialsCsv, summaryCsv);
        }

        if (!enforceDemandEquality && startTrial == 0 && end == selections.size()
                && methods.containsAll(List.of("Mean", "SAA", "CSAA"))) {
            ReproductionGate gate = verifyReproductionGate(trialsCsv, selections.size());
            Files.writeString(gateReportFile, gate.report, StandardCharsets.UTF_8);
            System.out.println(gate.report);
            if (!gate.passed) {
                Files.deleteIfExists(gateFile);
                throw new IllegalStateException("Historical GE reproduction did not pass; EQ remains blocked.");
            }
            Files.writeString(gateFile, gate.report, StandardCharsets.UTF_8);
        }

        System.out.println("done trials=" + trialsCsv.toAbsolutePath());
        System.out.println("done summary=" + summaryCsv.toAbsolutePath());
    }

    private static ResultRow solveOne(String method,
                                      SelectedParams selection,
                                      Map<Integer, List<Sample>> samplesByK,
                                      List<String> lanes,
                                      ProcurementParams params,
                                      Map<String, OldResult> oldResults,
                                      boolean enforceDemandEquality,
                                      String demandRelation) throws Exception {
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
            throw new IllegalArgumentException("Unsupported fast method: " + method);
        }

        List<Sample> allSamples = samplesByK.get(k);
        List<Sample> trainRaw = trainingWindow(allSamples, selection.testPeriodIdx, k);
        Sample test = testSample(allSamples, selection.testPeriodIdx, k);
        Config cfg = baseConfig(
                k, Double.isFinite(cH) ? cH : 1.0, solveMode, enforceDemandEquality);

        List<Sample> train;
        CovariateVector thetaNow = new CovariateVector(test.theta.values().clone());
        if ("Mean".equals(method)) {
            double[] meanDemand = meanDemand(trainRaw, params.J);
            PeriodData period = new PeriodData(
                    test.period.tIndex,
                    test.period.startDate,
                    test.period.endDate,
                    meanDemand,
                    test.period.holidayCount,
                    test.period.avgFreightIndex,
                    test.period.avgConsumptionIndex,
                    test.period.avgWEIIndex);
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

        SAAModel model = new SAAModel();
        long start = System.nanoTime();
        Solution solution = model.solve(new Data(lanes, train, thetaNow, params), cfg, null);
        double solveTime = (System.nanoTime() - start) / 1e9;
        SecondStageEvaluator.Result recourse = SecondStageEvaluator.evaluate(
                params, solution.y, test.demand().clone(), enforceDemandEquality);

        ResultRow row = new ResultRow();
        row.method = method;
        row.demandRelation = demandRelation;
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
        row.yBinary = yBinary(solution.y);
        OldResult old = oldResults.get(method + "#" + selection.trialId);
        row.oldExpected = old == null ? Double.NaN : old.expected;
        row.oldRealized = old == null ? Double.NaN : old.realized;
        row.oldSelectedCount = old == null ? -1 : old.selectedCount;
        row.expectedDeltaPct = Double.isFinite(row.oldExpected) && row.oldExpected != 0.0
                ? 100.0 * (row.expected - row.oldExpected) / row.oldExpected
                : Double.NaN;
        row.deltaPct = Double.isFinite(row.oldRealized) && row.oldRealized != 0.0
                ? 100.0 * (row.realized - row.oldRealized) / row.oldRealized
                : Double.NaN;
        return row;
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

    private static Config baseConfig(int k,
                                     double cH,
                                     SolveMode solveMode,
                                     boolean enforceDemandEquality) {
        Config cfg = TRBReviewerExperimentSupport.baseConfig(
                k, cH, KernelType.EXPONENTIAL, true, solveMode);
        cfg.enforceDemandEquality = enforceDemandEquality;
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

    private static List<SelectedParams> readSelections(Path csv) throws Exception {
        List<SelectedParams> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> columns = columnMap(header);
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

    private static Map<String, OldResult> readOldResults(Path csv) throws Exception {
        Map<String, OldResult> values = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> columns = columnMap(header);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> row = parseCsv(line);
                String method = value(row, columns, "final_method_name");
                if (!method.equals("Mean") && !method.equals("SAA") && !method.equals("CSAA")) continue;
                int trialId = integer(row, columns, "trialId");
                OldResult old = new OldResult();
                old.expected = decimal(row, columns, "expected_obj");
                old.realized = decimal(row, columns, "realized_obj");
                old.selectedCount = integer(row, columns, "selected_count");
                values.put(method + "#" + trialId, old);
            }
        }
        return values;
    }

    private static void initializeTrialsCsv(Path csv) throws Exception {
        if (Files.exists(csv) && Files.size(csv) > 0) return;
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("method,trialId,testPeriodIdx,k,C_h,demand_relation,expected_obj,old_expected_obj,"
                    + "expected_delta_pct,realized_obj,old_realized_obj,delta_pct,solve_time_sec,selected_count,"
                    + "old_selected_count,selected_count_match,transport_cost,spot_cost,"
                    + "penalty_cost,yBinary");
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
                    "%s,%d,%d,%d,%s,%s,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.6f,%d,%d,%s,%.10f,%.10f,%.10f,%s%n",
                    row.method, row.trialId, row.testPeriodIdx, row.k,
                    Double.isFinite(row.cH) ? String.format(Locale.US, "%.10f", row.cH) : "",
                    row.demandRelation,
                    row.expected, row.oldExpected, row.expectedDeltaPct,
                    row.realized, row.oldRealized, row.deltaPct, row.solveTimeSec,
                    row.selectedCount, row.oldSelectedCount,
                    String.valueOf(row.selectedCount == row.oldSelectedCount),
                    row.transport, row.spot, row.penalty, row.yBinary));
        }
    }

    private static void writeSummary(Path trialsCsv, Path summaryCsv) throws Exception {
        Map<String, List<ResultRow>> byMethod = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(trialsCsv, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> columns = columnMap(header);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                ResultRow row = new ResultRow();
                row.method = value(values, columns, "method");
                row.realized = decimal(values, columns, "realized_obj");
                row.oldRealized = decimal(values, columns, "old_realized_obj");
                row.solveTimeSec = decimal(values, columns, "solve_time_sec");
                row.selectedCount = integer(values, columns, "selected_count");
                byMethod.computeIfAbsent(row.method, ignored -> new ArrayList<>()).add(row);
            }
        }
        try (BufferedWriter writer = Files.newBufferedWriter(summaryCsv, StandardCharsets.UTF_8)) {
            writer.write("method,n,mean_realized,old_mean_realized,mean_delta_pct,total_solve_time_sec,mean_selected_count");
            writer.newLine();
            for (Map.Entry<String, List<ResultRow>> entry : byMethod.entrySet()) {
                List<ResultRow> rows = entry.getValue();
                double realized = rows.stream().mapToDouble(r -> r.realized).average().orElse(Double.NaN);
                double old = rows.stream().mapToDouble(r -> r.oldRealized).average().orElse(Double.NaN);
                double delta = 100.0 * (realized - old) / old;
                double totalTime = rows.stream().mapToDouble(r -> r.solveTimeSec).sum();
                double selected = rows.stream().mapToInt(r -> r.selectedCount).average().orElse(Double.NaN);
                writer.write(String.format(Locale.US, "%s,%d,%.10f,%.10f,%.10f,%.6f,%.10f%n",
                        entry.getKey(), rows.size(), realized, old, delta, totalTime, selected));
            }
        }
    }

    private static ReproductionGate verifyReproductionGate(Path trialsCsv, int expectedTrials) throws Exception {
        Map<String, Integer> counts = new HashMap<>();
        double maxRealizedRelativeError = 0.0;
        double maxExpectedRelativeError = 0.0;
        int selectedCountMismatches = 0;

        try (BufferedReader reader = Files.newBufferedReader(trialsCsv, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> columns = columnMap(header);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> row = parseCsv(line);
                String method = value(row, columns, "method");
                counts.merge(method, 1, Integer::sum);
                double realized = decimal(row, columns, "realized_obj");
                double oldRealized = decimal(row, columns, "old_realized_obj");
                double expected = decimal(row, columns, "expected_obj");
                double oldExpected = decimal(row, columns, "old_expected_obj");
                maxRealizedRelativeError = Math.max(
                        maxRealizedRelativeError,
                        Math.abs(realized - oldRealized) / Math.max(1.0, Math.abs(oldRealized)));
                maxExpectedRelativeError = Math.max(
                        maxExpectedRelativeError,
                        Math.abs(expected - oldExpected) / Math.max(1.0, Math.abs(oldExpected)));
                if (integer(row, columns, "selected_count")
                        != integer(row, columns, "old_selected_count")) {
                    selectedCountMismatches++;
                }
            }
        }

        boolean countsOk = counts.getOrDefault("Mean", 0) == expectedTrials
                && counts.getOrDefault("SAA", 0) == expectedTrials
                && counts.getOrDefault("CSAA", 0) == expectedTrials;
        boolean passed = countsOk
                && maxRealizedRelativeError <= 1e-6
                && maxExpectedRelativeError <= 1e-6
                && selectedCountMismatches == 0;
        String report = String.format(Locale.US,
                "GE_REPRODUCTION_GATE passed=%s expectedTrials=%d counts=%s "
                        + "maxRealizedRelativeError=%.12g maxExpectedRelativeError=%.12g "
                        + "selectedCountMismatches=%d%n",
                passed, expectedTrials, counts, maxRealizedRelativeError,
                maxExpectedRelativeError, selectedCountMismatches);
        return new ReproductionGate(passed, report);
    }

    private static List<String> methodArg(String[] args, int index) {
        String raw = args.length > index ? args[index] : "Mean,SAA,CSAA";
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
            if (i == 0 && !name.isEmpty() && name.charAt(0) == '\uFEFF') {
                name = name.substring(1);
            }
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

    private static final class SelectedParams {
        int trialId;
        int testPeriodIdx;
        int csaaK;
        double csaaCH;
    }

    private static final class ResultRow {
        String method;
        String demandRelation;
        int trialId;
        int testPeriodIdx;
        int k;
        double cH;
        double expected;
        double oldExpected;
        double expectedDeltaPct;
        double realized;
        double oldRealized;
        double deltaPct;
        double solveTimeSec;
        int selectedCount;
        int oldSelectedCount;
        double transport;
        double spot;
        double penalty;
        String yBinary;
    }

    private static final class OldResult {
        double expected;
        double realized;
        int selectedCount;
    }

    private static final class ReproductionGate {
        final boolean passed;
        final String report;

        ReproductionGate(boolean passed, String report) {
            this.passed = passed;
            this.report = report;
        }
    }
}
