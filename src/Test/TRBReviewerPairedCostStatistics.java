package Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Reviewer analysis for R4-M9, R4-M10, R4-M11, R4-M32 and R4-m8 (partial P5).
 *
 * <p><strong>Input.</strong> label=trial.csv arguments with the first method as
 * baseline; rows are paired by actual test period/trial key.</p>
 *
 * <p><strong>Operation.</strong> Pure postprocessing. Reports absolute and
 * paired cost distributions, win/tie/loss rates, a paired randomization test
 * and an ordinary percentile bootstrap CI. It performs no optimization.</p>
 *
 * <p><strong>Output.</strong> Absolute method summary, paired differences and
 * paired-vs-baseline summary CSVs. The current bootstrap resamples individual
 * trials; it is suitable for independent synthetic replications, but must not
 * be presented as the final serial-dependence-aware interval for the 51-week
 * Olist path. A moving/stationary-block bootstrap remains required there.</p>
 */
public class TRBReviewerPairedCostStatistics {

    private static final int BOOTSTRAP_REPLICATIONS = 20_000;
    private static final int RANDOMIZATION_REPLICATIONS = 100_000;
    private static final long RANDOM_SEED = 20260807L;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: TRBReviewerPairedCostStatistics <output-dir> <baseline=csv> <method=csv> [...]");
        }
        Path outDir = Paths.get(args[0]);
        Files.createDirectories(outDir);

        LinkedHashMap<String, Map<Integer, TrialRow>> methods = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            int split = args[i].indexOf('=');
            if (split <= 0 || split == args[i].length() - 1) {
                throw new IllegalArgumentException("Expected label=csv, got: " + args[i]);
            }
            String label = args[i].substring(0, split);
            Path csv = Paths.get(args[i].substring(split + 1));
            methods.put(label, readTrials(csv));
        }

        String baselineLabel = methods.keySet().iterator().next();
        Map<Integer, TrialRow> baseline = methods.get(baselineLabel);
        writeMethodSummary(outDir.resolve("absolute_method_summary.csv"), methods);

        try (BufferedWriter summary = Files.newBufferedWriter(
                outDir.resolve("paired_vs_baseline_summary.csv"), StandardCharsets.UTF_8);
             BufferedWriter detail = Files.newBufferedWriter(
                     outDir.resolve("paired_trial_differences.csv"), StandardCharsets.UTF_8)) {
            summary.write("baseline,method,n,meanBaseline,meanMethod,meanDifference,meanPercentDifference,"
                    + "medianDifference,winRate,tieRate,lossRate,bootstrap95Low,bootstrap95High,randomizationPValue");
            summary.newLine();
            detail.write("baseline,method,trialKey,baselineCost,methodCost,difference,percentDifference");
            detail.newLine();

            for (Map.Entry<String, Map<Integer, TrialRow>> entry : methods.entrySet()) {
                if (entry.getKey().equals(baselineLabel)) continue;
                List<Pair> pairs = pair(baseline, entry.getValue());
                if (pairs.isEmpty()) {
                    throw new IllegalArgumentException("No paired trials for " + entry.getKey());
                }
                List<Double> differences = new ArrayList<>();
                List<Double> percentDifferences = new ArrayList<>();
                int wins = 0;
                int ties = 0;
                for (Pair pair : pairs) {
                    double difference = pair.method.cost - pair.baseline.cost;
                    double percent = pair.baseline.cost == 0.0
                            ? Double.NaN : 100.0 * difference / pair.baseline.cost;
                    differences.add(difference);
                    if (Double.isFinite(percent)) percentDifferences.add(percent);
                    if (difference < -1e-9) wins++;
                    else if (difference > 1e-9) { /* loss */ }
                    else ties++;
                    detail.write(String.format(Locale.US, "%s,%s,%d,%.10f,%.10f,%.10f,%s%n",
                            baselineLabel, entry.getKey(), pair.key,
                            pair.baseline.cost, pair.method.cost, difference,
                            Double.isFinite(percent) ? String.format(Locale.US, "%.10f", percent) : "NA"));
                }
                double[] ci = bootstrapMeanCi(differences);
                double pValue = pairedRandomizationPValue(differences);
                int losses = pairs.size() - wins - ties;
                summary.write(String.format(Locale.US,
                        "%s,%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                        baselineLabel, entry.getKey(), pairs.size(),
                        meanCost(pairs, true), meanCost(pairs, false), mean(differences),
                        mean(percentDifferences), quantile(differences, 0.5),
                        (double) wins / pairs.size(), (double) ties / pairs.size(), (double) losses / pairs.size(),
                        ci[0], ci[1], pValue));
            }
        }
    }

    private static void writeMethodSummary(Path path,
                                           LinkedHashMap<String, Map<Integer, TrialRow>> methods) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("method,n,mean,sd,min,q05,q25,median,q75,q95,max,meanRuntimeSec,medianRuntimeSec");
            writer.newLine();
            for (Map.Entry<String, Map<Integer, TrialRow>> entry : methods.entrySet()) {
                List<Double> costs = new ArrayList<>();
                List<Double> runtimes = new ArrayList<>();
                for (TrialRow row : entry.getValue().values()) {
                    costs.add(row.cost);
                    if (Double.isFinite(row.runtime)) runtimes.add(row.runtime);
                }
                writer.write(String.format(Locale.US,
                        "%s,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%s,%s%n",
                        entry.getKey(), costs.size(), mean(costs), sampleSd(costs),
                        quantile(costs, 0.0), quantile(costs, 0.05), quantile(costs, 0.25),
                        quantile(costs, 0.5), quantile(costs, 0.75), quantile(costs, 0.95),
                        quantile(costs, 1.0),
                        runtimes.isEmpty() ? "NA" : String.format(Locale.US, "%.10f", mean(runtimes)),
                        runtimes.isEmpty() ? "NA" : String.format(Locale.US, "%.10f", quantile(runtimes, 0.5))));
            }
        }
    }

    private static Map<Integer, TrialRow> readTrials(Path csv) throws Exception {
        Map<Integer, TrialRow> rows = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return rows;
            List<String> header = TRBReviewerBandwidthStability.parseCsvLine(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = TRBReviewerBandwidthStability.parseCsvLine(line);
                Map<String, String> row = toMap(header, fields);
                int key = Integer.parseInt(required(row, "actual_test_period", "testIdx", "testPeriodIdx", "trialId"));
                double cost = Double.parseDouble(required(row, "realized_obj", "realizedObj", "realized"));
                String runtimeRaw = optional(row, "solve_time_sec", "solveTimeSec", "runtimeSec", "runtime");
                double runtime = runtimeRaw.isBlank() ? Double.NaN : Double.parseDouble(runtimeRaw);
                if (rows.put(key, new TrialRow(key, cost, runtime)) != null) {
                    throw new IllegalArgumentException("Duplicate trial key " + key + " in " + csv);
                }
            }
        }
        return rows;
    }

    private static List<Pair> pair(Map<Integer, TrialRow> baseline, Map<Integer, TrialRow> method) {
        List<Pair> pairs = new ArrayList<>();
        for (Map.Entry<Integer, TrialRow> entry : baseline.entrySet()) {
            TrialRow other = method.get(entry.getKey());
            if (other != null) pairs.add(new Pair(entry.getKey(), entry.getValue(), other));
        }
        pairs.sort(Comparator.comparingInt(p -> p.key));
        return pairs;
    }

    private static double[] bootstrapMeanCi(List<Double> values) {
        Random random = new Random(RANDOM_SEED);
        List<Double> means = new ArrayList<>(BOOTSTRAP_REPLICATIONS);
        for (int b = 0; b < BOOTSTRAP_REPLICATIONS; b++) {
            double sum = 0.0;
            for (int i = 0; i < values.size(); i++) sum += values.get(random.nextInt(values.size()));
            means.add(sum / values.size());
        }
        return new double[] {quantile(means, 0.025), quantile(means, 0.975)};
    }

    private static double pairedRandomizationPValue(List<Double> differences) {
        double observed = Math.abs(mean(differences));
        Random random = new Random(RANDOM_SEED + 1);
        int atLeastAsExtreme = 0;
        for (int r = 0; r < RANDOMIZATION_REPLICATIONS; r++) {
            double sum = 0.0;
            for (double value : differences) sum += random.nextBoolean() ? value : -value;
            if (Math.abs(sum / differences.size()) + 1e-12 >= observed) atLeastAsExtreme++;
        }
        return (atLeastAsExtreme + 1.0) / (RANDOMIZATION_REPLICATIONS + 1.0);
    }

    private static double meanCost(List<Pair> pairs, boolean baseline) {
        double sum = 0.0;
        for (Pair pair : pairs) sum += baseline ? pair.baseline.cost : pair.method.cost;
        return sum / pairs.size();
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.size();
    }

    private static double sampleSd(List<Double> values) {
        if (values.size() < 2) return 0.0;
        double mean = mean(values);
        double sum = 0.0;
        for (double value : values) sum += (value - mean) * (value - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    private static double quantile(List<Double> values, double q) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double position = q * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted.get(lower);
        double weight = position - lower;
        return sorted.get(lower) * (1.0 - weight) + sorted.get(upper) * weight;
    }

    private static Map<String, String> toMap(List<String> header, List<String> fields) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) row.put(header.get(i), i < fields.size() ? fields.get(i) : "");
        return row;
    }

    private static String required(Map<String, String> row, String... names) {
        String value = optional(row, names);
        if (value.isBlank()) throw new IllegalArgumentException("Missing required column: " + String.join("/", names));
        return value;
    }

    private static String optional(Map<String, String> row, String... names) {
        for (String name : names) {
            String value = row.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static final class TrialRow {
        final int key;
        final double cost;
        final double runtime;

        TrialRow(int key, double cost, double runtime) {
            this.key = key;
            this.cost = cost;
            this.runtime = runtime;
        }
    }

    private static final class Pair {
        final int key;
        final TrialRow baseline;
        final TrialRow method;

        Pair(int key, TrialRow baseline, TrialRow method) {
            this.key = key;
            this.baseline = baseline;
            this.method = method;
        }
    }
}
