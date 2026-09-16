package Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Paired EXPONENTIAL-versus-GAUSSIAN summary for Reviewer 1, Comment 1. */
public final class TRBReviewerKernelAblationSummary {

    private TRBReviewerKernelAblationSummary() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: <kernel-ablation-root>");
        summarize(Path.of(args[0]).toAbsolutePath().normalize());
    }

    static void summarize(Path root) throws Exception {
        Map<String, Double> exponential = read(root.resolve("exponential/cv_selected_actual_trials.csv"));
        Map<String, Double> gaussian = read(root.resolve("gaussian/cv_selected_actual_trials.csv"));
        if (!exponential.keySet().equals(gaussian.keySet())) {
            throw new IllegalArgumentException("Kernel outputs do not contain identical trial/method keys.");
        }

        Map<String, List<Pair>> byMethod = new LinkedHashMap<>();
        for (String key : exponential.keySet()) {
            String method = key.substring(key.lastIndexOf('|') + 1);
            byMethod.computeIfAbsent(method, ignored -> new ArrayList<>())
                    .add(new Pair(exponential.get(key), gaussian.get(key)));
        }

        Path output = root.resolve("kernel_comparison_summary.csv");
        try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            writer.write("method,pairedTrials,meanExponential,meanGaussian,meanDiffGaussianMinusExponential,"
                    + "sdPairedDiff,ciLow,ciHigh,gaussianWinRate");
            writer.newLine();
            for (Map.Entry<String, List<Pair>> entry : byMethod.entrySet()) {
                List<Pair> pairs = entry.getValue();
                double meanExp = pairs.stream().mapToDouble(p -> p.exponential).average().orElseThrow();
                double meanGau = pairs.stream().mapToDouble(p -> p.gaussian).average().orElseThrow();
                double meanDiff = meanGau - meanExp;
                double sumSquares = 0.0;
                int wins = 0;
                for (Pair pair : pairs) {
                    double diff = pair.gaussian - pair.exponential;
                    sumSquares += (diff - meanDiff) * (diff - meanDiff);
                    if (diff < 0.0) wins++;
                }
                double sd = pairs.size() > 1 ? Math.sqrt(sumSquares / (pairs.size() - 1)) : 0.0;
                double halfWidth = pairs.size() > 1 ? 1.96 * sd / Math.sqrt(pairs.size()) : Double.NaN;
                writer.write(String.format(Locale.US,
                        "%s,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                        entry.getKey(), pairs.size(), meanExp, meanGau, meanDiff, sd,
                        meanDiff - halfWidth, meanDiff + halfWidth, wins / (double) pairs.size()));
            }
        }
    }

    private static Map<String, Double> read(Path path) throws Exception {
        Map<String, Double> rows = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            int trial = required(header, "trialId");
            int period = required(header, "actual_test_period");
            int method = required(header, "method_name");
            int cost = required(header, "realized_obj");
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                String key = values.get(trial) + "|" + values.get(period) + "|" + values.get(method);
                if (rows.put(key, Double.parseDouble(values.get(cost))) != null) {
                    throw new IllegalArgumentException("Duplicate selected trial key in " + path + ": " + key);
                }
            }
        }
        return rows;
    }

    private static int required(List<String> header, String name) {
        int index = header.indexOf(name);
        if (index < 0) throw new IllegalArgumentException("Missing column " + name);
        return index;
    }

    private static List<String> parseCsv(String line) {
        if (line == null) throw new IllegalArgumentException("CSV is empty.");
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else current.append(ch);
        }
        values.add(current.toString());
        return values;
    }

    private record Pair(double exponential, double gaussian) {
    }
}
