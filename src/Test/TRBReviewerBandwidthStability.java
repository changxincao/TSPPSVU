package Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reviewer analysis for R4-M14 and the selection-frequency part of R4-M31 (P5).
 *
 * <p><strong>Input.</strong> A per-trial selected-parameter CSV containing trial,
 * k, C_h and optional lambda columns.</p>
 *
 * <p><strong>Operation.</strong> Pure postprocessing: counts selections and
 * adjacent-trial transitions and computes normalized entropy. It performs no
 * optimization and can be applied to existing 35/15 CV outputs.</p>
 *
 * <p><strong>Output.</strong> parameter_frequency.csv,
 * adjacent_trial_transitions.csv and stability_summary.txt. It does not infer
 * configured grid boundaries or reconstruct validation curves; those remain a
 * separate R4-M14 diagnostic when candidate-grid CSVs are available.</p>
 */
public class TRBReviewerBandwidthStability {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException(
                    "Usage: TRBReviewerBandwidthStability <selected-parameter.csv> [output-dir]");
        }
        Path input = Paths.get(args[0]);
        Path outDir = Paths.get(args.length > 1 ? args[1]
                : "analysis/巴西数据分析/新版_purchase时间/输出/返修实验/R4-M14_参数稳定性");
        Files.createDirectories(outDir);

        List<Map<String, String>> rows = readCsv(input);
        if (rows.isEmpty()) throw new IllegalArgumentException("No data rows in " + input);

        List<Selection> selections = new ArrayList<>();
        for (Map<String, String> row : rows) {
            String trial = first(row, "trialId", "trial_id");
            String k = first(row, "selected_k", "bestK", "k1Lag", "k");
            String c = first(row, "selected_C_h", "bestC_h", "C_h", "cH");
            String lambda = firstOptional(row, "selected_lambda", "bestLambda", "lambda");
            selections.add(new Selection(Integer.parseInt(trial), k, c, lambda));
        }
        selections.sort(Comparator.comparingInt(s -> s.trial));

        Map<String, Integer> kCounts = count(selections, Axis.K);
        Map<String, Integer> cCounts = count(selections, Axis.C);
        Map<String, Integer> lambdaCounts = count(selections, Axis.LAMBDA);
        writeFrequency(outDir.resolve("parameter_frequency.csv"), "k", kCounts, selections.size());
        appendFrequency(outDir.resolve("parameter_frequency.csv"), "C_h", cCounts, selections.size());
        if (!lambdaCounts.isEmpty()) {
            appendFrequency(outDir.resolve("parameter_frequency.csv"), "lambda", lambdaCounts, selections.size());
        }

        int kSwitches = 0;
        int cSwitches = 0;
        int lambdaSwitches = 0;
        int comparableLambdaPairs = 0;
        try (BufferedWriter writer = Files.newBufferedWriter(
                outDir.resolve("adjacent_trial_transitions.csv"), StandardCharsets.UTF_8)) {
            writer.write("fromTrial,toTrial,kChanged,cChanged,lambdaChanged");
            writer.newLine();
            for (int i = 1; i < selections.size(); i++) {
                Selection a = selections.get(i - 1);
                Selection b = selections.get(i);
                boolean kChanged = !a.k.equals(b.k);
                boolean cChanged = !a.c.equals(b.c);
                boolean lambdaComparable = !a.lambda.isBlank() && !b.lambda.isBlank();
                boolean lambdaChanged = lambdaComparable && !a.lambda.equals(b.lambda);
                if (kChanged) kSwitches++;
                if (cChanged) cSwitches++;
                if (lambdaComparable) {
                    comparableLambdaPairs++;
                    if (lambdaChanged) lambdaSwitches++;
                }
                writer.write(String.format(Locale.US, "%d,%d,%s,%s,%s%n",
                        a.trial, b.trial, kChanged, cChanged,
                        lambdaComparable ? Boolean.toString(lambdaChanged) : "NA"));
            }
        }

        int pairs = Math.max(0, selections.size() - 1);
        try (BufferedWriter writer = Files.newBufferedWriter(
                outDir.resolve("stability_summary.txt"), StandardCharsets.UTF_8)) {
            writer.write("source=" + input.toAbsolutePath());
            writer.newLine();
            writer.write("trials=" + selections.size());
            writer.newLine();
            writer.write(String.format(Locale.US, "k_switch_rate=%.10f%n", rate(kSwitches, pairs)));
            writer.write(String.format(Locale.US, "C_h_switch_rate=%.10f%n", rate(cSwitches, pairs)));
            writer.write(String.format(Locale.US, "lambda_switch_rate=%s%n",
                    comparableLambdaPairs == 0 ? "NA"
                            : String.format(Locale.US, "%.10f", rate(lambdaSwitches, comparableLambdaPairs))));
            writer.write(String.format(Locale.US, "k_normalized_entropy=%.10f%n",
                    normalizedEntropy(kCounts, selections.size())));
            writer.write(String.format(Locale.US, "C_h_normalized_entropy=%.10f%n",
                    normalizedEntropy(cCounts, selections.size())));
            writer.write(String.format(Locale.US, "lambda_normalized_entropy=%s%n",
                    lambdaCounts.isEmpty() ? "NA"
                            : String.format(Locale.US, "%.10f", normalizedEntropy(lambdaCounts, selections.size()))));
        }
    }

    private enum Axis { K, C, LAMBDA }

    private static final class Selection {
        final int trial;
        final String k;
        final String c;
        final String lambda;

        Selection(int trial, String k, String c, String lambda) {
            this.trial = trial;
            this.k = canonicalNumber(k);
            this.c = canonicalNumber(c);
            this.lambda = lambda.isBlank() ? "" : canonicalNumber(lambda);
        }
    }

    private static Map<String, Integer> count(List<Selection> selections, Axis axis) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Selection selection : selections) {
            String value = axis == Axis.K ? selection.k : axis == Axis.C ? selection.c : selection.lambda;
            if (value.isBlank()) continue;
            counts.merge(value, 1, Integer::sum);
        }
        return counts;
    }

    private static void writeFrequency(Path path, String axis, Map<String, Integer> counts, int total) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("parameter,value,count,frequency");
            writer.newLine();
        }
        appendFrequency(path, axis, counts, total);
    }

    private static void appendFrequency(Path path, String axis, Map<String, Integer> counts, int total) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND)) {
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                writer.write(String.format(Locale.US, "%s,%s,%d,%.10f%n",
                        axis, entry.getKey(), entry.getValue(), rate(entry.getValue(), total)));
            }
        }
    }

    private static double normalizedEntropy(Map<String, Integer> counts, int total) {
        if (counts.size() <= 1 || total <= 0) return 0.0;
        double entropy = 0.0;
        for (int count : counts.values()) {
            double p = (double) count / total;
            entropy -= p * Math.log(p);
        }
        return entropy / Math.log(counts.size());
    }

    private static double rate(int numerator, int denominator) {
        return denominator <= 0 ? Double.NaN : (double) numerator / denominator;
    }

    private static String canonicalNumber(String raw) {
        String trimmed = raw.trim();
        try {
            return Double.toString(Double.parseDouble(trimmed));
        } catch (NumberFormatException ignored) {
            return trimmed;
        }
    }

    private static String first(Map<String, String> row, String... names) {
        String value = firstOptional(row, names);
        if (value.isBlank()) throw new IllegalArgumentException("Missing required column: " + String.join("/", names));
        return value;
    }

    private static String firstOptional(Map<String, String> row, String... names) {
        for (String name : names) {
            String value = row.get(name);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static List<Map<String, String>> readCsv(Path path) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) return rows;
            List<String> header = parseCsvLine(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = parseCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) {
                    row.put(header.get(i), i < fields.size() ? fields.get(i) : "");
                }
                rows.add(row);
            }
        }
        return rows;
    }

    static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
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
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(ch);
            }
        }
        out.add(field.toString());
        return out;
    }
}
