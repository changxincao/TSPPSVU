package Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict, optimization-free postprocessing for the 12-cell Olist grid. */
public final class BrazilOlistRollingMinHCoverageMqcPostprocess {

    private static final Path ROOT = Paths.get(
            "analysis/TRB_reviewer_revision/03_olist_rolling_min_h_coverage_mqc_20260811");
    private static final double[] COVERAGES = {1.00, 0.75, 0.50};
    private static final double[] MQC_SCALES = {0.50, 0.75, 1.00, 1.25};
    private static final double GAP_TOLERANCE = 1e-4;
    private static final List<String> BASE_METHODS = List.of("D", "SAA", "CSAA", "RCSAA");
    private static final List<String> METHODS_WITH_DRO = List.of("D", "SAA", "CSAA", "RCSAA", "DRO");
    private static final String[][] PAIRS = {
            {"D", "SAA"}, {"D", "CSAA"}, {"D", "RCSAA"},
            {"SAA", "CSAA"}, {"SAA", "RCSAA"}, {"CSAA", "RCSAA"},
            {"D", "DRO"}, {"SAA", "DRO"}, {"CSAA", "DRO"}, {"RCSAA", "DRO"}
    };

    private BrazilOlistRollingMinHCoverageMqcPostprocess() {
    }

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Paths.get(args[0]) : ROOT;
        double[] coverages = args.length > 1 ? decimals(args[1]) : COVERAGES;
        double[] scales = args.length > 2 ? decimals(args[2]) : MQC_SCALES;
        List<String> fixedMethods = args.length > 3 ? List.of(args[3].split(",")) : null;
        List<Cell> cells = new ArrayList<>();
        int checkedRows = 0;
        for (double coverage : coverages) {
            for (double scale : scales) {
                Cell cell = readAndValidateCell(root, coverage, scale,
                        fixedMethods == null ? methodsFor(coverage, scale) : fixedMethods);
                cells.add(cell);
                checkedRows += cell.rows.size() * 51;
            }
        }
        writeCombined(cells, root.resolve("combined_grid_summary.csv"));
        writePairs(cells, root.resolve("paired_improvements.csv"));
        Files.writeString(root.resolve("grid_validation.txt"), String.format(Locale.US,
                "status=PASSED%nvalidatedCells=%d%nexpectedTrialsPerMethod=51%n"
                        + "validatedMethodTrialRows=%d%nuniqueTrialsPerMethod=true%n"
                        + "pairedTrialSetsIdentical=true%ncostDecompositionTolerance=1e-6%n"
                        + "allRowsCertifiedOptimal=true%nrelativeGapTolerance=1e-4%n",
                cells.size(), checkedRows), StandardCharsets.UTF_8);
        System.out.println("validated cells=" + cells.size() + " methodTrialRows=" + checkedRows);
        System.out.println("combined=" + root.resolve("combined_grid_summary.csv").toAbsolutePath());
        System.out.println("paired=" + root.resolve("paired_improvements.csv").toAbsolutePath());
    }

    private static Cell readAndValidateCell(Path root, double coverage, double scale,
                                            List<String> methods) throws Exception {
        Path csv = root.resolve(String.format(Locale.US,
                "coverage_%03d/mqc_%03d/rolling_trials.csv",
                Math.round(100 * coverage), Math.round(100 * scale)));
        Map<String, Map<Integer, Row>> rows = new LinkedHashMap<>();
        for (String method : methods) rows.put(method, new LinkedHashMap<>());
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            Map<String, Integer> columns = header(reader.readLine());
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] values = line.split(",", -1);
                String method = value(values, columns, "method");
                if (!rows.containsKey(method)) {
                    throw new IllegalStateException("Unexpected method in " + csv + ": " + method);
                }
                double rowCoverage = decimal(values, columns, "coverage_target");
                double rowScale = decimal(values, columns, "mqc_quantity_scale");
                if (Math.abs(rowCoverage - coverage) > 1e-9 || Math.abs(rowScale - scale) > 1e-9) {
                    throw new IllegalStateException("Condition metadata mismatch in " + csv);
                }
                Row row = new Row();
                row.trial = integer(values, columns, "trialId");
                row.testPeriod = integer(values, columns, "testPeriodIdx");
                row.realized = decimal(values, columns, "realized_obj");
                row.transport = decimal(values, columns, "transport_cost");
                row.spot = decimal(values, columns, "spot_cost");
                row.penalty = decimal(values, columns, "penalty_cost");
                row.mqcShortfall = optionalDecimal(values, columns, "mqc_shortfall_quantity");
                row.selected = decimal(values, columns, "selected_count");
                row.selectedMqc = decimal(values, columns, "total_selected_mqc");
                row.demand = decimal(values, columns, "demand_total");
                row.selectedH = decimal(values, columns, "mean_selected_penalty_rate");
                row.mqcExposure = decimal(values, columns, "selected_mqc_penalty_exposure");
                row.runtime = decimal(values, columns, "solve_time_sec");
                boolean certified = Boolean.parseBoolean(value(values, columns, "certified_optimal"));
                double relativeGap = decimal(values, columns, "relative_gap");
                if (!certified || !Double.isFinite(relativeGap) || relativeGap > GAP_TOLERANCE) {
                    throw new IllegalStateException("Uncertified row in " + csv
                            + " method=" + method + " trial=" + row.trial
                            + " certified=" + certified + " gap=" + relativeGap);
                }
                double decomposition = row.transport + row.spot + row.penalty;
                if (Math.abs(row.realized - decomposition) > 1e-6) {
                    throw new IllegalStateException("Cost decomposition mismatch in " + csv
                            + " method=" + method + " trial=" + row.trial);
                }
                if (Double.isFinite(row.mqcShortfall) && row.mqcShortfall < -1e-8) {
                    throw new IllegalStateException("Negative MQC shortfall in " + csv
                            + " method=" + method + " trial=" + row.trial);
                }
                if (rows.get(method).put(row.trial, row) != null) {
                    throw new IllegalStateException("Duplicate method/trial in " + csv
                            + ": " + method + "/" + row.trial);
                }
            }
        }
        Set<Integer> reference = null;
        Map<Integer, Integer> testPeriodByTrial = new HashMap<>();
        for (String method : methods) {
            Map<Integer, Row> methodRows = rows.get(method);
            if (methodRows.size() != 51) {
                throw new IllegalStateException("Expected 51 rows in " + csv
                        + " method=" + method + ", found=" + methodRows.size());
            }
            if (reference == null) reference = Set.copyOf(methodRows.keySet());
            else if (!reference.equals(methodRows.keySet())) {
                throw new IllegalStateException("Paired trial sets differ in " + csv);
            }
            for (Row row : methodRows.values()) {
                Integer previous = testPeriodByTrial.putIfAbsent(row.trial, row.testPeriod);
                if (previous != null && previous != row.testPeriod) {
                    throw new IllegalStateException("Test-period mismatch in " + csv
                            + " trial=" + row.trial);
                }
            }
        }
        return new Cell(coverage, scale, rows);
    }

    private static double[] decimals(String csv) {
        String[] tokens = csv.split(",");
        double[] values = new double[tokens.length];
        for (int i = 0; i < tokens.length; i++) values[i] = Double.parseDouble(tokens[i]);
        return values;
    }

    private static void writeCombined(List<Cell> cells, Path csv) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("coverage,mqc_scale,method,n,mean,sd,q95,cvar95,mean_selected,mean_transport,"
                    + "mean_spot,mean_penalty,penalty_share_pct,mean_selected_mqc,mean_demand,"
                    + "mean_selected_mqc_to_demand,mean_selected_h,mean_selected_mqc_h,"
                    + "mean_selected_mqc_h_to_demand,mean_mqc_shortfall,positive_shortfall_pct,"
                    + "effective_penalty_per_shortfall,mean_runtime_sec");
            writer.newLine();
            for (Cell cell : cells) {
                for (String method : cell.rows.keySet()) {
                    List<Row> rows = new ArrayList<>(cell.rows.get(method).values());
                    List<Double> costs = rows.stream().map(row -> row.realized).sorted().toList();
                    double mean = mean(costs);
                    double q95 = percentile(costs, 0.95);
                    double cvar = costs.stream().mapToDouble(Double::doubleValue)
                            .filter(value -> value + 1e-9 >= q95).average().orElse(Double.NaN);
                    double meanPenalty = rows.stream().mapToDouble(row -> row.penalty).average().orElseThrow();
                    double meanShortfall = averageFinite(rows, row -> row.mqcShortfall);
                    long positiveShortfall = rows.stream()
                            .filter(row -> Double.isFinite(row.mqcShortfall) && row.mqcShortfall > 1e-8)
                            .count();
                    double totalShortfall = rows.stream().mapToDouble(row -> row.mqcShortfall)
                            .filter(Double::isFinite).sum();
                    double effectivePenaltyRate = totalShortfall > 1e-12
                            ? rows.stream().mapToDouble(row -> row.penalty).sum() / totalShortfall
                            : 0.0;
                    writer.write(String.format(Locale.US,
                            "%.2f,%.2f,%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,"
                                    + "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                            cell.coverage, cell.scale, method, rows.size(), mean, sampleStd(costs, mean),
                            q95, cvar,
                            average(rows, row -> row.selected), average(rows, row -> row.transport),
                            average(rows, row -> row.spot), meanPenalty, 100.0 * meanPenalty / mean,
                            average(rows, row -> row.selectedMqc), average(rows, row -> row.demand),
                            average(rows, row -> row.selectedMqc / row.demand),
                            average(rows, row -> row.selectedH), average(rows, row -> row.mqcExposure),
                            average(rows, row -> row.mqcExposure / row.demand),
                            meanShortfall, 100.0 * positiveShortfall / rows.size(), effectivePenaltyRate,
                            average(rows, row -> row.runtime)));
                }
            }
        }
    }

    private static void writePairs(List<Cell> cells, Path csv) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            writer.write("coverage,mqc_scale,baseline,method,n,mean_baseline,mean_method,"
                    + "mean_cost_difference_method_minus_baseline,aggregate_improvement_pct,"
                    + "mean_paired_improvement_pct,wins,ties,losses,mean_transport_difference,"
                    + "mean_spot_difference,mean_penalty_difference,mean_mqc_shortfall_difference,"
                    + "mean_selected_difference");
            writer.newLine();
            for (Cell cell : cells) {
                for (String[] pair : PAIRS) {
                    if (!cell.rows.containsKey(pair[0]) || !cell.rows.containsKey(pair[1])) continue;
                    Map<Integer, Row> baseline = cell.rows.get(pair[0]);
                    Map<Integer, Row> method = cell.rows.get(pair[1]);
                    List<Double> baselineCosts = new ArrayList<>();
                    List<Double> methodCosts = new ArrayList<>();
                    List<Double> differences = new ArrayList<>();
                    List<Double> improvements = new ArrayList<>();
                    List<Double> transportDifferences = new ArrayList<>();
                    List<Double> spotDifferences = new ArrayList<>();
                    List<Double> penaltyDifferences = new ArrayList<>();
                    List<Double> shortfallDifferences = new ArrayList<>();
                    List<Double> selectedDifferences = new ArrayList<>();
                    int wins = 0;
                    int ties = 0;
                    List<Integer> trials = new ArrayList<>(baseline.keySet());
                    Collections.sort(trials);
                    for (int trial : trials) {
                        Row base = baseline.get(trial);
                        Row candidate = method.get(trial);
                        double difference = candidate.realized - base.realized;
                        baselineCosts.add(base.realized);
                        methodCosts.add(candidate.realized);
                        differences.add(difference);
                        improvements.add(100.0 * (base.realized - candidate.realized) / base.realized);
                        transportDifferences.add(candidate.transport - base.transport);
                        spotDifferences.add(candidate.spot - base.spot);
                        penaltyDifferences.add(candidate.penalty - base.penalty);
                        if (Double.isFinite(candidate.mqcShortfall) && Double.isFinite(base.mqcShortfall)) {
                            shortfallDifferences.add(candidate.mqcShortfall - base.mqcShortfall);
                        }
                        selectedDifferences.add(candidate.selected - base.selected);
                        if (difference < -1e-9) wins++;
                        else if (difference > 1e-9) { /* loss */ }
                        else ties++;
                    }
                    double baselineMean = mean(baselineCosts);
                    double methodMean = mean(methodCosts);
                    writer.write(String.format(Locale.US,
                            "%.2f,%.2f,%s,%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%d,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                            cell.coverage, cell.scale, pair[0], pair[1], trials.size(),
                            baselineMean, methodMean, mean(differences),
                            100.0 * (baselineMean - methodMean) / baselineMean,
                            mean(improvements), wins, ties, trials.size() - wins - ties,
                            mean(transportDifferences), mean(spotDifferences),
                            mean(penaltyDifferences), mean(shortfallDifferences), mean(selectedDifferences)));
                }
            }
        }
    }

    private static double average(List<Row> rows, RowValue value) {
        return rows.stream().mapToDouble(value::get).average().orElse(Double.NaN);
    }

    private static double averageFinite(List<Row> rows, RowValue value) {
        return rows.stream().mapToDouble(value::get).filter(Double::isFinite)
                .average().orElse(Double.NaN);
    }

    private static List<String> methodsFor(double coverage, double scale) {
        boolean selectedForDro = Math.abs(coverage - 1.0) < 1e-9
                || Math.abs(scale - 1.0) < 1e-9;
        return selectedForDro ? METHODS_WITH_DRO : BASE_METHODS;
    }

    private static Map<String, Integer> header(String line) {
        if (line == null) throw new IllegalArgumentException("Missing CSV header.");
        String[] values = line.replace("\ufeff", "").split(",", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < values.length; i++) columns.put(values[i].trim(), i);
        return columns;
    }

    private static String value(String[] values, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null) throw new IllegalArgumentException("Missing column " + name);
        return values[index].trim();
    }

    private static int integer(String[] values, Map<String, Integer> columns, String name) {
        return Integer.parseInt(value(values, columns, name));
    }

    private static double decimal(String[] values, Map<String, Integer> columns, String name) {
        return Double.parseDouble(value(values, columns, name));
    }

    private static double optionalDecimal(String[] values, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || values[index].isBlank() ? Double.NaN : Double.parseDouble(values[index].trim());
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    private static double sampleStd(List<Double> values, double mean) {
        double sum = 0.0;
        for (double value : values) sum += (value - mean) * (value - mean);
        return values.size() < 2 ? 0.0 : Math.sqrt(sum / (values.size() - 1));
    }

    private static double percentile(List<Double> sorted, double probability) {
        double position = probability * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double weight = position - lower;
        return sorted.get(lower) * (1.0 - weight) + sorted.get(upper) * weight;
    }

    private record Cell(double coverage, double scale, Map<String, Map<Integer, Row>> rows) {
    }

    private static final class Row {
        int trial;
        int testPeriod;
        double realized;
        double transport;
        double spot;
        double penalty;
        double mqcShortfall;
        double selected;
        double selectedMqc;
        double demand;
        double selectedH;
        double mqcExposure;
        double runtime;
    }

    @FunctionalInterface
    private interface RowValue {
        double get(Row row);
    }
}
