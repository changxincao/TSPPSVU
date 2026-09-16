package Test.analysis.synthetic;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Reviewer 3, Comment 3: paired statistics for the synthetic conditional-OOS
 * protocol. Draws are paired only within one frozen query. Confidence intervals
 * used for cross-query claims are computed from replication-level mean
 * differences, never by pretending that all conditional draws are independent
 * experiment replications.
 */
public final class TRBReviewerR3M3SyntheticPairedStatistics {

    private static final List<String> EXPECTED_METHODS = List.of(
            "D", "SAA", "CSAA", "DRO", "RCSAA_LBBD");

    private TRBReviewerR3M3SyntheticPairedStatistics() {
    }

    /** Usage: {@code <experiment-root> [output-directory]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: <experiment-root> [output-directory]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = args.length == 2
                ? Path.of(args[1]).toAbsolutePath().normalize()
                : root.resolve("statistics");
        summarize(root, output);
    }

    public static void summarize(Path experimentRoot, Path outputDirectory) throws Exception {
        Map<String, Replication> replications = loadReplications(experimentRoot);
        if (replications.isEmpty()) {
            throw new IllegalArgumentException("No synthetic result cells found under " + experimentRoot);
        }
        Files.createDirectories(outputDirectory);
        writeRunInventory(outputDirectory.resolve("run_inventory.csv"), replications.values());

        List<PairRow> pairRows = new ArrayList<>();
        for (Replication replication : replications.values()) {
            List<String> methods = new ArrayList<>(replication.costsByMethod.keySet());
            Collections.sort(methods);
            for (int a = 0; a < methods.size(); a++) {
                for (int b = a + 1; b < methods.size(); b++) {
                    pairRows.add(compare(replication, methods.get(a), methods.get(b)));
                }
            }
        }
        writePerReplication(outputDirectory.resolve("per_replication_pairwise.csv"), pairRows);
        writeAcrossReplications(outputDirectory.resolve("across_replication_pairwise.csv"), pairRows);
    }

    private static Map<String, Replication> loadReplications(Path root) throws Exception {
        Map<String, Replication> out = new LinkedHashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path oos : paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("oos_costs.csv"))
                    .toList()) {
                Path methodDirectory = oos.getParent();
                Path cellDirectory = findCellDirectory(methodDirectory);
                Path manifest = cellDirectory.resolve("instance").resolve("manifest.properties");
                Properties properties = new Properties();
                try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                String key = cellDirectory.toAbsolutePath().normalize().toString();
                Replication replication = out.computeIfAbsent(key,
                        ignored -> Replication.from(cellDirectory, properties));
                String method = methodDirectory.getFileName().toString();
                replication.costsByMethod.put(method, readCosts(oos));
                replication.statusByMethod.put(method,
                        readStatus(methodDirectory.resolve("solve_summary.csv"), method));
            }
        }
        return out;
    }

    private static Path findCellDirectory(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isRegularFile(current.resolve("instance").resolve("manifest.properties"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalArgumentException("Cannot find cell manifest above " + start);
    }

    private static Map<Integer, Double> readCosts(Path path) throws Exception {
        Map<Integer, Double> out = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            int drawColumn = header.indexOf("drawId");
            int costColumn = header.indexOf("totalCost");
            if (drawColumn < 0 || costColumn < 0) {
                throw new IllegalArgumentException("Synthetic OOS schema missing drawId/totalCost: " + path);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                int draw = Integer.parseInt(values.get(drawColumn));
                double cost = Double.parseDouble(values.get(costColumn));
                if (out.put(draw, cost) != null) {
                    throw new IllegalArgumentException("Duplicate drawId=" + draw + " in " + path);
                }
            }
        }
        return out;
    }

    private static RunStatus readStatus(Path path, String expectedMethod) throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            List<String> values = parseCsv(reader.readLine());
            if (reader.readLine() != null) {
                throw new IllegalArgumentException("Expected one summary row: " + path);
            }
            int methodColumn = required(header, "method", path);
            if (!expectedMethod.equals(values.get(methodColumn))) {
                throw new IllegalArgumentException("Method label/directory mismatch in " + path);
            }
            return new RunStatus(
                    values.get(required(header, "solverStatus", path)),
                    Boolean.parseBoolean(values.get(required(header, "certifiedOptimal", path))),
                    Double.parseDouble(values.get(required(header, "relativeGap", path))));
        }
    }

    private static int required(List<String> header, String name, Path path) {
        int column = header.indexOf(name);
        if (column < 0) throw new IllegalArgumentException("Missing " + name + " in " + path);
        return column;
    }

    private static PairRow compare(Replication replication, String methodA, String methodB) {
        Map<Integer, Double> a = replication.costsByMethod.get(methodA);
        Map<Integer, Double> b = replication.costsByMethod.get(methodB);
        if (!a.keySet().equals(b.keySet())) {
            throw new IllegalArgumentException(
                    "OOS draw IDs differ for " + methodA + " and " + methodB
                            + " in " + replication.cellDirectory);
        }
        double sumA = 0.0;
        double sumB = 0.0;
        double sumDiff = 0.0;
        int winsA = 0;
        List<Double> differences = new ArrayList<>(a.size());
        for (int draw : a.keySet()) {
            double av = a.get(draw);
            double bv = b.get(draw);
            double diff = av - bv;
            sumA += av;
            sumB += bv;
            sumDiff += diff;
            differences.add(diff);
            if (av < bv) winsA++;
        }
        double meanDiff = sumDiff / differences.size();
        double sdDiff = sampleSd(differences, meanDiff);
        double halfWidth = 1.96 * sdDiff / Math.sqrt(differences.size());
        RunStatus statusA = replication.statusByMethod.get(methodA);
        RunStatus statusB = replication.statusByMethod.get(methodB);
        return new PairRow(replication, methodA, methodB, differences.size(),
                sumA / differences.size(), sumB / differences.size(), meanDiff,
                sdDiff, meanDiff - halfWidth, meanDiff + halfWidth,
                winsA / (double) differences.size(), statusA, statusB);
    }

    private static void writePerReplication(Path path, List<PairRow> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("distribution,innovationCv,replicationSeed,I,J,S,methodA,methodB,draws,"
                    + "meanA,meanB,meanDiffAminusB,sdDrawDiff,conditionalCiLow,conditionalCiHigh,winRateA,"
                    + "statusA,certifiedA,gapA,statusB,certifiedB,gapB,pairCertified");
            writer.newLine();
            for (PairRow row : rows) {
                writer.write(String.format(Locale.US,
                        "%s,%.17g,%d,%d,%d,%d,%s,%s,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                                + "%s,%s,%.17g,%s,%s,%.17g,%s%n",
                        row.replication.distribution, row.replication.innovationCv,
                        row.replication.seed, row.replication.i, row.replication.j, row.replication.s,
                        csv(row.methodA), csv(row.methodB), row.draws,
                        row.meanA, row.meanB, row.meanDiff, row.sdDiff,
                        row.ciLow, row.ciHigh, row.winRateA,
                        csv(row.statusA.status), row.statusA.certified, row.statusA.gap,
                        csv(row.statusB.status), row.statusB.certified, row.statusB.gap,
                        row.statusA.certified && row.statusB.certified));
            }
        }
    }

    private static void writeRunInventory(Path path,
                                          Iterable<Replication> replications) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("distribution,innovationCv,replicationSeed,I,J,S,method,present,"
                    + "solverStatus,certifiedOptimal,relativeGap");
            writer.newLine();
            for (Replication replication : replications) {
                for (String method : EXPECTED_METHODS) {
                    RunStatus status = replication.statusByMethod.get(method);
                    writer.write(String.format(Locale.US,
                            "%s,%.17g,%d,%d,%d,%d,%s,%s,%s,%s,%.17g%n",
                            replication.distribution, replication.innovationCv,
                            replication.seed, replication.i, replication.j, replication.s,
                            csv(method), status != null,
                            status == null ? "" : csv(status.status),
                            status != null && status.certified,
                            status == null ? Double.NaN : status.gap));
                }
            }
        }
    }

    private static void writeAcrossReplications(Path path, List<PairRow> rows) throws Exception {
        Map<String, List<PairRow>> groups = new LinkedHashMap<>();
        for (PairRow row : rows) {
            String key = row.replication.distribution + "|" + row.replication.innovationCv
                    + "|" + row.replication.i + "|" + row.replication.j + "|" + row.replication.s
                    + "|" + row.methodA + "|" + row.methodB;
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("distribution,innovationCv,I,J,S,methodA,methodB,replications,"
                    + "certifiedPairReplications,incompletePairReplications,maxGapA,maxGapB,"
                    + "meanReplicationDiffAminusB,sdReplicationDiff,ciLow,ciHigh,replicationWinRateA");
            writer.newLine();
            for (List<PairRow> group : groups.values()) {
                PairRow first = group.get(0);
                List<Double> values = group.stream().map(row -> row.meanDiff).toList();
                double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
                double sd = sampleSd(values, mean);
                double halfWidth = group.size() > 1 ? 1.96 * sd / Math.sqrt(group.size()) : Double.NaN;
                long wins = group.stream().filter(row -> row.meanDiff < 0.0).count();
                long certifiedPairs = group.stream()
                        .filter(row -> row.statusA.certified && row.statusB.certified).count();
                double maxGapA = finiteMax(group.stream().map(row -> row.statusA.gap).toList());
                double maxGapB = finiteMax(group.stream().map(row -> row.statusB.gap).toList());
                writer.write(String.format(Locale.US,
                        "%s,%.17g,%d,%d,%d,%s,%s,%d,%d,%d,%.17g,%.17g,"
                                + "%.17g,%.17g,%.17g,%.17g,%.17g%n",
                        first.replication.distribution, first.replication.innovationCv,
                        first.replication.i, first.replication.j, first.replication.s,
                        csv(first.methodA), csv(first.methodB), group.size(), certifiedPairs,
                        group.size() - certifiedPairs, maxGapA, maxGapB, mean, sd,
                        mean - halfWidth, mean + halfWidth, wins / (double) group.size()));
            }
        }
    }

    private static double sampleSd(List<Double> values, double mean) {
        if (values.size() <= 1) return 0.0;
        double sum = 0.0;
        for (double value : values) sum += (value - mean) * (value - mean);
        return Math.sqrt(sum / (values.size() - 1));
    }

    private static double finiteMax(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).filter(Double::isFinite)
                .max().orElse(Double.NaN);
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
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static final class Replication {
        final Path cellDirectory;
        final String distribution;
        final double innovationCv;
        final long seed;
        final int i;
        final int j;
        final int s;
        final Map<String, Map<Integer, Double>> costsByMethod = new LinkedHashMap<>();
        final Map<String, RunStatus> statusByMethod = new LinkedHashMap<>();

        private Replication(Path cellDirectory, String distribution, double innovationCv,
                            long seed, int i, int j, int s) {
            this.cellDirectory = cellDirectory;
            this.distribution = distribution;
            this.innovationCv = innovationCv;
            this.seed = seed;
            this.i = i;
            this.j = j;
            this.s = s;
        }

        static Replication from(Path cellDirectory, Properties properties) {
            Path procurement = cellDirectory.resolve("instance").resolve("procurement_generation.properties");
            int carriers = -1;
            if (Files.isRegularFile(procurement)) {
                Properties p = new Properties();
                try (BufferedReader reader = Files.newBufferedReader(procurement, StandardCharsets.UTF_8)) {
                    p.load(reader);
                    carriers = Integer.parseInt(p.getProperty("carrierCount"));
                } catch (Exception ex) {
                    throw new IllegalArgumentException("Cannot read " + procurement, ex);
                }
            }
            return new Replication(cellDirectory,
                    properties.getProperty("innovationDistribution"),
                    Double.parseDouble(properties.getProperty("innovationCv")),
                    Long.parseLong(properties.getProperty("replicationSeed")),
                    carriers,
                    Integer.parseInt(properties.getProperty("laneCount")),
                    Integer.parseInt(properties.getProperty("trainingSampleCount")));
        }
    }

    private record PairRow(Replication replication, String methodA, String methodB,
                           int draws, double meanA, double meanB, double meanDiff,
                           double sdDiff, double ciLow, double ciHigh, double winRateA,
                           RunStatus statusA, RunStatus statusB) {
    }

    private record RunStatus(String status, boolean certified, double gap) {
    }
}
