package Test.analysis.synthetic;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Strict, non-solving aggregation for the Hu-parameterized R7 experiment. */
public final class TRBReviewerR7HuLiteratureMarketPostprocess {
    private static final String PREFIX = "HU_R7_";
    private static final double TOLERANCE = 1.0e-6;
    private static final List<Scope> SCOPES = List.of(
            new Scope("FAST_ALL", List.of("D", "SAA", "CSAA")),
            new Scope("DRO_GATE", List.of("D", "SAA", "CSAA", "DRO")),
            new Scope("RCSAA_GATE",
                    List.of("D", "SAA", "CSAA", "RCSAA_ENUMERATE", "DRO")));

    private TRBReviewerR7HuLiteratureMarketPostprocess() {
    }

    /** Usage: {@code <output-dir> <raw-root> [raw-root ...]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: <output-dir> <raw-root> [raw-root ...]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        requireEmptyDirectory(output);
        Files.createDirectories(output);

        Map<ResultKey, StrictRow> rows = new LinkedHashMap<>();
        List<Path> roots = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            Path root = Path.of(args[i]).toAbsolutePath().normalize();
            roots.add(root);
            readRoot(root, rows);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("No HU_R7 results found.");

        validateMethodSets(rows.values());
        writeStrictRows(output.resolve("query_results_strict.csv"), rows.values());
        List<MatchedSummary> summaries = matchedSummaries(rows.values());
        writeMatchedSummaries(output.resolve("matched_subset_summary.csv"), summaries);
        writePairedImprovements(output.resolve("paired_method_improvements.csv"), summaries);
        writeChains(output.resolve("table2_chain_check.csv"), summaries);
        writeManifest(output.resolve("validation.txt"), roots, rows.values(), summaries);
    }

    private static void readRoot(Path root, Map<ResultKey, StrictRow> combined) throws Exception {
        Path csv = root.resolve("screening_results.csv");
        if (!Files.isRegularFile(csv)) {
            throw new IllegalArgumentException("Missing screening_results.csv: " + root);
        }
        Map<String, Integer> procurementSeeds = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> columns = columns(header);
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                String candidate = value(values, columns, "candidate");
                if (!candidate.startsWith(PREFIX)) continue;
                int procurementSeed = procurementSeeds.computeIfAbsent(candidate,
                        ignored -> readProcurementSeed(root, candidate));
                int training = integer(values, columns, "trainingReplication");
                int query = integer(values, columns, "query");
                long trainingSeed = longInteger(values, columns, "trainingSeed");
                long querySeed = longInteger(values, columns, "querySeed");
                String method = value(values, columns, "method");
                QueryKey queryKey = new QueryKey(candidate, procurementSeed,
                        training, query, trainingSeed, querySeed);
                Path oosPath = root.resolve(candidate).resolve(String.format(Locale.US,
                        "train_%02d/query_%03d/%s/oos_costs.csv", training, query, method));
                OosSeries oos = readOos(oosPath, method);
                Summary canonical = summarize(oos);
                validateSummary(values, columns, canonical, queryKey, method);
                boolean certified = Boolean.parseBoolean(
                        value(values, columns, "certifiedOptimal"));
                double gap = number(values, columns, "relativeGap");
                if (!certified || !Double.isFinite(gap) || gap > 1.0e-4 + 1.0e-12) {
                    throw new IllegalStateException("Uncertified result: " + queryKey
                            + " method=" + method + " gap=" + gap);
                }
                StrictRow row = new StrictRow(queryKey, method,
                        integer(values, columns, "selectedCount"),
                        value(values, columns, "yBinary"), canonical, certified, gap, oos);
                ResultKey key = new ResultKey(queryKey, method);
                StrictRow previous = combined.putIfAbsent(key, row);
                if (previous != null && !same(previous, row)) {
                    throw new IllegalStateException("Conflicting duplicate result: " + key);
                }
            }
        }
    }

    private static int readProcurementSeed(Path root, String candidate) {
        Path path = root.resolve(candidate).resolve("candidate.properties");
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
        if (!"HU_2016".equals(properties.getProperty("market"))) {
            throw new IllegalStateException("Unexpected market in " + path);
        }
        return Integer.parseInt(properties.getProperty("paperProcurementSeed"));
    }

    private static OosSeries readOos(Path path, String method) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing " + path);
        Map<Integer, Cost> costs = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> columns = columns(header);
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                if (!method.equals(value(values, columns, "method"))) {
                    throw new IllegalStateException("Method mismatch in " + path);
                }
                int draw = integer(values, columns, "drawId");
                Cost cost = new Cost(number(values, columns, "totalCost"),
                        number(values, columns, "transportCost"),
                        number(values, columns, "spotCost"),
                        number(values, columns, "penaltyCost"));
                requireClose(cost.total, cost.transport + cost.spot + cost.penalty,
                        "cost decomposition " + path + " draw=" + draw);
                if (costs.putIfAbsent(draw, cost) != null) {
                    throw new IllegalStateException("Duplicate draw " + draw + " in " + path);
                }
            }
        }
        if (costs.isEmpty()) throw new IllegalStateException("No OOS draws in " + path);
        int expected = 0;
        for (int draw : costs.keySet()) {
            if (draw != expected++) {
                throw new IllegalStateException("Non-contiguous draws in " + path);
            }
        }
        return new OosSeries(costs);
    }

    private static Summary summarize(OosSeries series) {
        double[] totals = series.costs.values().stream()
                .mapToDouble(cost -> cost.total).sorted().toArray();
        double mean = Arrays.stream(totals).average().orElseThrow();
        double sumSquares = 0.0;
        double transport = 0.0;
        double spot = 0.0;
        double penalty = 0.0;
        for (Cost cost : series.costs.values()) {
            double difference = cost.total - mean;
            sumSquares += difference * difference;
            transport += cost.transport;
            spot += cost.spot;
            penalty += cost.penalty;
        }
        double q95 = quantile(totals, 0.95);
        double cvar = Arrays.stream(totals).filter(value -> value >= q95)
                .average().orElseThrow();
        int n = totals.length;
        return new Summary(n, mean, Math.sqrt(sumSquares / n), q95, cvar,
                transport / n, spot / n, penalty / n);
    }

    private static void validateSummary(List<String> values, Map<String, Integer> columns,
                                        Summary canonical, QueryKey key, String method) {
        requireClose(number(values, columns, "meanOosCost"), canonical.mean,
                "mean " + key + " " + method);
        requireClose(number(values, columns, "sdOosCost"), canonical.sd,
                "sd " + key + " " + method);
        requireClose(number(values, columns, "q95OosCost"), canonical.q95,
                "q95 " + key + " " + method);
        requireClose(number(values, columns, "cvar95OosCost"), canonical.cvar95,
                "cvar95 " + key + " " + method);
        requireClose(number(values, columns, "meanTransportCost"), canonical.transport,
                "transport " + key + " " + method);
        requireClose(number(values, columns, "meanSpotCost"), canonical.spot,
                "spot " + key + " " + method);
        requireClose(number(values, columns, "meanPenaltyCost"), canonical.penalty,
                "penalty " + key + " " + method);
    }

    private static void validateMethodSets(Iterable<StrictRow> rows) {
        Map<QueryKey, Set<String>> methods = new LinkedHashMap<>();
        for (StrictRow row : rows) {
            methods.computeIfAbsent(row.key, ignored -> new LinkedHashSet<>()).add(row.method);
        }
        for (Map.Entry<QueryKey, Set<String>> entry : methods.entrySet()) {
            Set<String> actual = entry.getValue();
            if (!actual.containsAll(List.of("D", "SAA", "CSAA"))) {
                throw new IllegalStateException("Missing FAST methods for "
                        + entry.getKey() + ": " + actual);
            }
            if (actual.contains("RCSAA_ENUMERATE") && !actual.contains("DRO")) {
                throw new IllegalStateException("Exact gate lacks DRO for " + entry.getKey());
            }
        }
    }

    private static List<MatchedSummary> matchedSummaries(Iterable<StrictRow> allRows) {
        Map<ResultKey, StrictRow> rows = new LinkedHashMap<>();
        Set<String> candidates = new LinkedHashSet<>();
        Set<QueryKey> queries = new LinkedHashSet<>();
        for (StrictRow row : allRows) {
            rows.put(new ResultKey(row.key, row.method), row);
            candidates.add(row.key.candidate);
            queries.add(row.key);
        }
        List<MatchedSummary> summaries = new ArrayList<>();
        for (String candidate : candidates) {
            for (Scope scope : SCOPES) {
                List<QueryKey> matched = queries.stream()
                        .filter(key -> key.candidate.equals(candidate))
                        .filter(key -> scope.methods.stream()
                                .allMatch(method -> rows.containsKey(new ResultKey(key, method))))
                        .toList();
                if (matched.isEmpty()) continue;
                for (String method : scope.methods) {
                    List<StrictRow> subset = matched.stream()
                            .map(key -> rows.get(new ResultKey(key, method))).toList();
                    summaries.add(summarizeMatched(candidate, scope.name, method, subset));
                }
            }
        }
        return summaries;
    }

    private static MatchedSummary summarizeMatched(String candidate, String scope,
                                                   String method, List<StrictRow> rows) {
        List<Double> totals = new ArrayList<>();
        double conditionalMean = 0.0;
        double conditionalSd = 0.0;
        double conditionalQ95 = 0.0;
        double conditionalCvar = 0.0;
        double selected = 0.0;
        for (StrictRow row : rows) {
            conditionalMean += row.summary.mean;
            conditionalSd += row.summary.sd;
            conditionalQ95 += row.summary.q95;
            conditionalCvar += row.summary.cvar95;
            selected += row.selectedCount;
            row.oos.costs.values().forEach(cost -> totals.add(cost.total));
        }
        double[] pooled = totals.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double mean = Arrays.stream(pooled).average().orElseThrow();
        double squares = 0.0;
        for (double value : pooled) squares += (value - mean) * (value - mean);
        double q95 = quantile(pooled, 0.95);
        double cvar = Arrays.stream(pooled).filter(value -> value >= q95)
                .average().orElseThrow();
        int n = rows.size();
        return new MatchedSummary(candidate, scope, method, n, pooled.length,
                conditionalMean / n, conditionalSd / n, conditionalQ95 / n,
                conditionalCvar / n, mean, Math.sqrt(squares / pooled.length),
                q95, cvar, selected / n);
    }

    private static void writeStrictRows(Path path, Iterable<StrictRow> rows) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("candidate,procurementSeed,trainingReplication,query,trainingSeed,querySeed,"
                    + "method,selectedCount,yBinary,nDraws,mean,sd,q95,cvar95,transport,spot,penalty,"
                    + "certifiedOptimal,relativeGap");
            out.newLine();
            for (StrictRow row : rows) {
                Summary s = row.summary;
                out.write(String.format(Locale.US,
                        "%s,%d,%d,%d,%d,%d,%s,%d,%s,%d,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%.12f,%s,%.12g%n",
                        row.key.candidate, row.key.procurementSeed, row.key.training,
                        row.key.query, row.key.trainingSeed, row.key.querySeed,
                        row.method, row.selectedCount, csv(row.yBinary), s.draws,
                        s.mean, s.sd, s.q95, s.cvar95, s.transport, s.spot,
                        s.penalty, row.certified, row.relativeGap));
            }
        }
    }

    private static void writeMatchedSummaries(Path path,
                                              List<MatchedSummary> rows) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("candidate,scope,method,pairedQueries,pairedDraws,meanConditionalMean,"
                    + "meanConditionalSd,meanConditionalQ95,meanConditionalCvar95,pooledMean,"
                    + "pooledPopulationSd,pooledQ95,pooledCvar95,meanSelected");
            out.newLine();
            for (MatchedSummary row : rows) {
                out.write(String.format(Locale.US,
                        "%s,%s,%s,%d,%d,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%.12f%n",
                        row.candidate, row.scope, row.method, row.queries, row.draws,
                        row.conditionalMean, row.conditionalSd, row.conditionalQ95,
                        row.conditionalCvar95, row.pooledMean, row.pooledSd,
                        row.pooledQ95, row.pooledCvar95, row.meanSelected));
            }
        }
    }

    private static void writeChains(Path path, List<MatchedSummary> rows) throws Exception {
        Map<String, Map<String, MatchedSummary>> grouped = new LinkedHashMap<>();
        for (MatchedSummary row : rows) {
            grouped.computeIfAbsent(row.candidate + "|" + row.scope,
                    ignored -> new LinkedHashMap<>()).put(row.method, row);
        }
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("candidate,scope,order,strictPooledMean,strictPooledSd,strictPooledQ95,"
                    + "strictPooledCvar95");
            out.newLine();
            for (Map.Entry<String, Map<String, MatchedSummary>> entry : grouped.entrySet()) {
                String[] key = entry.getKey().split("\\|", 2);
                List<String> order = SCOPES.stream().filter(scope -> scope.name.equals(key[1]))
                        .findFirst().orElseThrow().methods;
                out.write(String.format(Locale.US, "%s,%s,%s,%s,%s,%s,%s%n",
                        key[0], key[1], String.join(">", order),
                        strictlyDescending(entry.getValue(), order, Metric.MEAN),
                        strictlyDescending(entry.getValue(), order, Metric.SD),
                        strictlyDescending(entry.getValue(), order, Metric.Q95),
                        strictlyDescending(entry.getValue(), order, Metric.CVAR)));
            }
        }
    }

    private static void writePairedImprovements(Path path,
                                                List<MatchedSummary> rows) throws Exception {
        Map<String, Map<String, MatchedSummary>> grouped = new LinkedHashMap<>();
        for (MatchedSummary row : rows) {
            grouped.computeIfAbsent(row.candidate + "|" + row.scope,
                    ignored -> new LinkedHashMap<>()).put(row.method, row);
        }
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("candidate,scope,method,baseline,pairedQueries,pairedDraws,"
                    + "pooledMeanImprovementPct,pooledSdImprovementPct,"
                    + "pooledQ95ImprovementPct,pooledCvar95ImprovementPct");
            out.newLine();
            for (Map.Entry<String, Map<String, MatchedSummary>> entry : grouped.entrySet()) {
                String[] key = entry.getKey().split("\\|", 2);
                List<String[]> pairs = switch (key[1]) {
                    case "FAST_ALL" -> List.of(
                            new String[]{"SAA", "D"}, new String[]{"CSAA", "SAA"});
                    case "DRO_GATE" -> List.<String[]>of(new String[]{"DRO", "CSAA"});
                    case "RCSAA_GATE" -> List.of(
                            new String[]{"RCSAA_ENUMERATE", "CSAA"},
                            new String[]{"DRO", "CSAA"});
                    default -> throw new IllegalStateException("Unknown scope: " + key[1]);
                };
                for (String[] pair : pairs) {
                    MatchedSummary method = entry.getValue().get(pair[0]);
                    MatchedSummary baseline = entry.getValue().get(pair[1]);
                    if (method == null || baseline == null
                            || method.queries != baseline.queries
                            || method.draws != baseline.draws) {
                        throw new IllegalStateException("Unpaired summary: "
                                + key[0] + " " + key[1] + " " + Arrays.toString(pair));
                    }
                    out.write(String.format(Locale.US,
                            "%s,%s,%s,%s,%d,%d,%.12f,%.12f,%.12f,%.12f%n",
                            key[0], key[1], pair[0], pair[1], method.queries,
                            method.draws,
                            improvement(method.pooledMean, baseline.pooledMean),
                            improvement(method.pooledSd, baseline.pooledSd),
                            improvement(method.pooledQ95, baseline.pooledQ95),
                            improvement(method.pooledCvar95, baseline.pooledCvar95)));
                }
            }
        }
    }

    private static double improvement(double method, double baseline) {
        return 100.0 * (baseline - method) / baseline;
    }

    private static boolean strictlyDescending(Map<String, MatchedSummary> rows,
                                              List<String> order, Metric metric) {
        double previous = Double.POSITIVE_INFINITY;
        for (String method : order) {
            MatchedSummary row = rows.get(method);
            if (row == null) return false;
            double value = switch (metric) {
                case MEAN -> row.pooledMean;
                case SD -> row.pooledSd;
                case Q95 -> row.pooledQ95;
                case CVAR -> row.pooledCvar95;
            };
            if (!(previous > value)) return false;
            previous = value;
        }
        return true;
    }

    private static void writeManifest(Path path, List<Path> roots,
                                      Iterable<StrictRow> rows,
                                      List<MatchedSummary> summaries) throws Exception {
        int resultCount = 0;
        int drawCount = 0;
        Set<QueryKey> queries = new LinkedHashSet<>();
        Set<Integer> procurementSeeds = new LinkedHashSet<>();
        for (StrictRow row : rows) {
            resultCount++;
            drawCount += row.summary.draws;
            queries.add(row.key);
            procurementSeeds.add(row.key.procurementSeed);
        }
        List<String> lines = new ArrayList<>();
        lines.add("status=PASSED");
        lines.add("rawRoots=" + roots.size());
        lines.add("procurementSeeds=" + procurementSeeds);
        lines.add("uniqueQueryKeys=" + queries.size());
        lines.add("methodQueryRows=" + resultCount);
        lines.add("validatedDrawRows=" + drawCount);
        lines.add("matchedSummaryRows=" + summaries.size());
        lines.add("checks=unique result keys; canonical OOS recomputation; contiguous draw ids; "
                + "total=transport+spot+penalty; certified optimal; gap<=1e-4; paired method sets");
        Files.write(path, lines, StandardCharsets.UTF_8);
    }

    private static boolean same(StrictRow left, StrictRow right) {
        return left.selectedCount == right.selectedCount && left.yBinary.equals(right.yBinary)
                && close(left.summary.mean, right.summary.mean)
                && left.oos.costs.equals(right.oos.costs);
    }

    private static double quantile(double[] values, double probability) {
        if (values.length == 1) return values[0];
        double position = probability * (values.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return values[lower];
        double fraction = position - lower;
        return values[lower] * (1.0 - fraction) + values[upper] * fraction;
    }

    private static void requireClose(double actual, double expected, String label) {
        if (!close(actual, expected)) {
            throw new IllegalStateException(label + ": actual=" + actual
                    + " expected=" + expected);
        }
    }

    private static boolean close(double left, double right) {
        return Math.abs(left - right) <= TOLERANCE * Math.max(1.0,
                Math.max(Math.abs(left), Math.abs(right)));
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) result.put(header.get(i), i);
        return result;
    }

    private static String value(List<String> values, Map<String, Integer> columns,
                                String name) {
        Integer index = columns.get(name);
        if (index == null || index >= values.size()) {
            throw new IllegalArgumentException("Missing CSV column " + name);
        }
        return values.get(index);
    }

    private static int integer(List<String> values, Map<String, Integer> columns,
                               String name) {
        return Integer.parseInt(value(values, columns, name));
    }

    private static long longInteger(List<String> values, Map<String, Integer> columns,
                                    String name) {
        return Long.parseLong(value(values, columns, name));
    }

    private static double number(List<String> values, Map<String, Integer> columns,
                                 String name) {
        return Double.parseDouble(value(values, columns, name));
    }

    private static List<String> parseCsv(String line) {
        if (line == null) throw new IllegalArgumentException("Missing CSV header.");
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (character == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote: " + line);
        values.add(current.toString());
        return values;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void requireEmptyDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output directory must be empty: " + directory);
            }
        }
    }

    private enum Metric {MEAN, SD, Q95, CVAR}

    private record Scope(String name, List<String> methods) {
    }

    private record QueryKey(String candidate, int procurementSeed, int training,
                            int query, long trainingSeed, long querySeed) {
    }

    private record ResultKey(QueryKey query, String method) {
    }

    private record Cost(double total, double transport, double spot, double penalty) {
    }

    private record OosSeries(Map<Integer, Cost> costs) {
    }

    private record Summary(int draws, double mean, double sd, double q95,
                           double cvar95, double transport, double spot, double penalty) {
    }

    private record StrictRow(QueryKey key, String method, int selectedCount,
                             String yBinary, Summary summary, boolean certified,
                             double relativeGap, OosSeries oos) {
    }

    private record MatchedSummary(String candidate, String scope, String method,
                                  int queries, int draws, double conditionalMean,
                                  double conditionalSd, double conditionalQ95,
                                  double conditionalCvar95, double pooledMean,
                                  double pooledSd, double pooledQ95,
                                  double pooledCvar95, double meanSelected) {
    }
}
