package Test.analysis.synthetic;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/** Strict, solve-free merger for the complete 20-market lambda curve. */
public final class TRBReviewerLambdaCurvePostprocessor {
    private static final int MARKETS = 20;
    private static final int OOS_DRAWS = 500;
    private static final double TOLERANCE = 1e-8;
    private static final Set<String> TIME_COLUMNS = Set.of(
            "preparationTimeSec", "optimizerTimeSec", "oosEvaluationTimeSec", "totalEvaluationTimeSec");

    private TRBReviewerLambdaCurvePostprocessor() {
    }

    /**
     * Usage: output-root, then formal roots 357, 358, 362, 359 and 361 in that order.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 6 && args.length != 4) {
            throw new IllegalArgumentException(
                    "Usage: <output> <root357> <root358> <root362> <root359> <root361>"
                            + " OR <output> <min|max> <root-markets-0-9> <root-markets-10-19>");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        requireEmpty(output);
        int expectedTrainingSamples;
        String expectedHRule;
        List<SourceSpec> sources;
        if (args.length == 6) {
            expectedTrainingSamples = 50;
            expectedHRule = "max";
            sources = List.of(
                    source("357", args[1], 0, 19, 0.0, 1.0),
                    source("358", args[2], 0, 14, 0.01, 0.05, 0.1, 5.0),
                    source("362", args[3], 15, 19, 0.01, 0.05, 0.1, 5.0),
                    source("359", args[4], 0, 9, 10.0, 50.0, 100.0, 300.0),
                    source("361", args[5], 10, 19, 10.0, 50.0, 100.0, 300.0));
        } else {
            expectedTrainingSamples = 100;
            expectedHRule = args[1].trim().toLowerCase(Locale.ROOT);
            if (!expectedHRule.equals("min") && !expectedHRule.equals("max")) {
                throw new IllegalArgumentException("h rule must be min or max: " + args[1]);
            }
            double[] lambdas = {0.0, 0.01, 0.05, 0.1, 1.0, 5.0, 10.0, 50.0, 100.0, 300.0};
            sources = List.of(
                    source(label(args[2]), args[2], 0, 9, lambdas),
                    source(label(args[3]), args[3], 10, 19, lambdas));
        }

        Audit audit = new Audit();
        Map<Integer, Baseline> baselines = new TreeMap<>();
        List<ResultRow> results = new ArrayList<>();
        for (SourceSpec source : sources) {
            validateSourceDirectory(source);
            for (int market = source.firstMarket; market <= source.lastMarket; market++) {
                Path marketRoot = source.root.resolve(String.format(Locale.US, "market_%02d", market));
                validateProperties(marketRoot.resolve("experiment.properties"), market,
                        source.lambdas, expectedTrainingSamples, expectedHRule);
                Baseline baseline = readBaseline(source.label, marketRoot.resolve("D"), audit);
                Baseline canonical = baselines.putIfAbsent(market, baseline);
                if (canonical != null) {
                    compareBaselines(canonical, baseline, audit);
                }
                for (double lambda : source.lambdas) {
                    Path resultRoot = marketRoot.resolve("B_0_5").resolve("lambda_" + token(lambda));
                    results.add(readResult(source.label, market, lambda, resultRoot, audit));
                }
            }
        }
        validateCompleteGrid(baselines, results);
        validateAudit(audit);
        Files.createDirectories(output);
        writePerMarket(output.resolve("per_market.csv"), baselines, results);
        List<AggregateRow> aggregates = aggregate(baselines, results);
        writeAggregate(output.resolve("aggregate_summary.csv"), aggregates);
        writeCurve(output.resolve("curve.csv"), aggregates);
        writeValidation(output.resolve("validation.txt"), sources, baselines, results, audit);
    }

    private static SourceSpec source(String label, String argument, int first, int last,
                                     double... lambdas) {
        Path root = Path.of(argument).toAbsolutePath().normalize();
        String text = root.toString().toLowerCase(Locale.ROOT);
        if (text.contains("excluded") || text.contains("356_") || text.contains("360_")) {
            throw new IllegalArgumentException("Excluded/partial root is forbidden: " + root);
        }
        String leaf = root.getFileName().toString();
        if (!leaf.startsWith(label + "_")) {
            throw new IllegalArgumentException("Expected formal root " + label + "_*, got: " + root);
        }
        return new SourceSpec(label, root, first, last, List.copyOf(
                Arrays.stream(lambdas).boxed().toList()));
    }

    private static String label(String argument) {
        String leaf = Path.of(argument).toAbsolutePath().normalize().getFileName().toString();
        int underscore = leaf.indexOf('_');
        return underscore < 0 ? leaf : leaf.substring(0, underscore);
    }

    private static void requireEmpty(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output root must be empty: " + root);
            }
        }
    }

    private static void validateSourceDirectory(SourceSpec source) throws Exception {
        if (!Files.isDirectory(source.root)) {
            throw new IllegalArgumentException("Missing source root: " + source.root);
        }
        Set<String> expected = new LinkedHashSet<>();
        for (int market = source.firstMarket; market <= source.lastMarket; market++) {
            expected.add(String.format(Locale.US, "market_%02d", market));
        }
        Set<String> actual = new LinkedHashSet<>();
        try (var paths = Files.list(source.root)) {
            paths.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("market_"))
                    .sorted()
                    .forEach(actual::add);
        }
        if (!actual.equals(expected)) {
            throw new IllegalStateException(source.label + " market directories mismatch: " + actual);
        }
    }

    private static void validateProperties(Path path, int market, List<Double> expectedLambdas,
                                           int expectedTrainingSamples, String expectedHRule)
            throws Exception {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        requireProperty(properties, "coverage", "1.0", path);
        requireProperty(properties, "B", "0.5", path);
        requireProperty(properties, "I", "30", path);
        requireProperty(properties, "J", "23", path);
        requireProperty(properties, "S", Integer.toString(expectedTrainingSamples), path);
        requireProperty(properties, "oosDraws", "500", path);
        requireProperty(properties, "hRule", expectedHRule + " eligible r_ij", path);
        requireProperty(properties, "capacityNormalized", "true", path);
        requireProperty(properties, "mqcScale", "1.0", path);
        requireProperty(properties, "distribution", "LOGNORMAL", path);
        requireProperty(properties, "innovationCv", "0.345", path);
        requireProperty(properties, "laneSharePersistence", "0.5", path);
        requireProperty(properties, "k", "1", path);
        requireProperty(properties, "procurementSeed", Integer.toString(market), path);
        requireProperty(properties, "demandSeed", Long.toString(40_000L + 10_000L * market), path);
        List<Double> actual = Arrays.stream(required(properties, "lambda", path).split(","))
                .map(String::trim).map(Double::parseDouble).toList();
        if (!actual.equals(expectedLambdas)) {
            throw new IllegalStateException("Lambda property mismatch in " + path + ": " + actual);
        }
    }

    private static void requireProperty(Properties properties, String key, String expected, Path path) {
        String actual = required(properties, key, path);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(key + " mismatch in " + path + ": " + actual);
        }
    }

    private static String required(Properties properties, String key, Path path) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing property " + key + " in " + path);
        }
        return value;
    }

    private static Baseline readBaseline(String source, Path root, Audit audit) throws Exception {
        Map<String, String> summary = readSingleCsv(root.resolve("solve_summary.csv"));
        Observation observation = readObservation(root.resolve("oos_costs.csv"), summary, audit);
        return new Baseline(source, summary, observation);
    }

    private static ResultRow readResult(String source, int market, double lambda,
                                        Path root, Audit audit) throws Exception {
        Map<String, String> summary = readSingleCsv(root.resolve("solve_summary.csv"));
        Observation observation = readObservation(root.resolve("oos_costs.csv"), summary, audit);
        String expectedMethod = lambda == 0.0 ? "CSAA" : "DRO";
        if (!expectedMethod.equals(summary.get("method"))) {
            throw new IllegalStateException("Method mismatch in " + root + ": " + summary.get("method"));
        }
        boolean certified = Boolean.parseBoolean(required(summary, "certifiedOptimal", root));
        double optimizerTime = number(summary, "optimizerTimeSec", root);
        String solverStatus = required(summary, "solverStatus", root);
        boolean timeout = solverStatus.toLowerCase(Locale.ROOT).contains("time")
                || (!certified && optimizerTime >= 599.0);
        return new ResultRow(source, market, lambda,
                (int) number(summary, "selectedCount", root), required(summary, "yBinary", root),
                certified, number(summary, "relativeGap", root), solverStatus, timeout,
                optimizerTime, observation);
    }

    private static Observation readObservation(Path path, Map<String, String> summary, Audit audit)
            throws Exception {
        List<Map<String, String>> rows = readCsv(path);
        if (rows.size() != OOS_DRAWS) {
            throw new IllegalStateException("Expected 500 OOS rows in " + path + ", got " + rows.size());
        }
        List<CostRow> costs = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            Map<String, String> row = rows.get(index);
            int draw = (int) number(row, "drawId", path);
            if (draw != index) throw new IllegalStateException("Unexpected drawId in " + path + ": " + draw);
            CostRow cost = new CostRow(number(row, "totalCost", path),
                    number(row, "transportCost", path), number(row, "spotCost", path),
                    number(row, "penaltyCost", path), number(row, "mqcShortfallQuantity", path));
            audit.maxDecompositionError = Math.max(audit.maxDecompositionError,
                    Math.abs(cost.total - cost.transport - cost.spot - cost.penalty));
            costs.add(cost);
        }
        Stats stats = stats(costs);
        audit.maxSummaryMeanError = Math.max(audit.maxSummaryMeanError,
                Math.abs(stats.mean - number(summary, "meanOosCost", path)));
        audit.maxSummarySdError = Math.max(audit.maxSummarySdError,
                Math.abs(stats.sd - number(summary, "sdOosCost", path)));
        audit.maxSummaryQ95Error = Math.max(audit.maxSummaryQ95Error,
                Math.abs(stats.q95 - number(summary, "q95OosCost", path)));
        audit.maxSummaryCvarError = Math.max(audit.maxSummaryCvarError,
                Math.abs(stats.cvar95 - number(summary, "cvar95OosCost", path)));
        audit.maxSummaryTransportError = Math.max(audit.maxSummaryTransportError,
                Math.abs(stats.transport - number(summary, "meanTransportCost", path)));
        audit.maxSummarySpotError = Math.max(audit.maxSummarySpotError,
                Math.abs(stats.spot - number(summary, "meanSpotCost", path)));
        audit.maxSummaryPenaltyError = Math.max(audit.maxSummaryPenaltyError,
                Math.abs(stats.penalty - number(summary, "meanPenaltyCost", path)));
        if ((int) number(summary, "oosDraws", path) != OOS_DRAWS) {
            throw new IllegalStateException("Summary oosDraws mismatch in " + path);
        }
        return new Observation(List.copyOf(costs), stats);
    }

    private static void compareBaselines(Baseline expected, Baseline actual, Audit audit) {
        if (expected.observation.costs.size() != actual.observation.costs.size()) {
            throw new IllegalStateException("D OOS size differs between " + expected.source + " and " + actual.source);
        }
        for (int i = 0; i < expected.observation.costs.size(); i++) {
            CostRow left = expected.observation.costs.get(i);
            CostRow right = actual.observation.costs.get(i);
            audit.maxBaselineRawDifference = Math.max(audit.maxBaselineRawDifference,
                    maxDifference(left, right));
        }
        for (Map.Entry<String, String> entry : expected.summary.entrySet()) {
            if (TIME_COLUMNS.contains(entry.getKey())) continue;
            String other = actual.summary.get(entry.getKey());
            if (!equivalent(entry.getValue(), other)) {
                throw new IllegalStateException("D summary differs for " + entry.getKey()
                        + " between " + expected.source + " and " + actual.source);
            }
        }
        if (audit.maxBaselineRawDifference > TOLERANCE) {
            throw new IllegalStateException("D raw OOS mismatch: " + audit.maxBaselineRawDifference);
        }
        audit.baselineComparisons++;
    }

    private static double maxDifference(CostRow left, CostRow right) {
        return Collections.max(List.of(Math.abs(left.total - right.total),
                Math.abs(left.transport - right.transport), Math.abs(left.spot - right.spot),
                Math.abs(left.penalty - right.penalty), Math.abs(left.shortfall - right.shortfall)));
    }

    private static boolean equivalent(String left, String right) {
        if (left == null || right == null) return left == right;
        try {
            double a = Double.parseDouble(left);
            double b = Double.parseDouble(right);
            if (Double.isNaN(a) && Double.isNaN(b)) return true;
            return Math.abs(a - b) <= TOLERANCE;
        } catch (NumberFormatException ignored) {
            return left.equals(right);
        }
    }

    private static void validateCompleteGrid(Map<Integer, Baseline> baselines, List<ResultRow> results) {
        if (baselines.size() != MARKETS) {
            throw new IllegalStateException("Expected 20 unique D baselines, got " + baselines.size());
        }
        Map<Double, Set<Integer>> grid = new TreeMap<>();
        for (ResultRow row : results) {
            if (!grid.computeIfAbsent(row.lambda, ignored -> new LinkedHashSet<>()).add(row.market)) {
                throw new IllegalStateException("Duplicate lambda/market: " + row.lambda + "/" + row.market);
            }
        }
        Set<Double> expected = new LinkedHashSet<>(List.of(
                0.0, 0.01, 0.05, 0.1, 1.0, 5.0, 10.0, 50.0, 100.0, 300.0));
        if (!grid.keySet().equals(expected)) throw new IllegalStateException("Lambda grid mismatch: " + grid.keySet());
        for (Map.Entry<Double, Set<Integer>> entry : grid.entrySet()) {
            if (entry.getValue().size() != MARKETS) {
                throw new IllegalStateException("Lambda " + entry.getKey() + " has " + entry.getValue().size() + " markets");
            }
        }
    }

    private static List<AggregateRow> aggregate(Map<Integer, Baseline> baselines,
                                                List<ResultRow> results) {
        List<CostRow> baselineCosts = new ArrayList<>(MARKETS * OOS_DRAWS);
        for (Baseline baseline : baselines.values()) baselineCosts.addAll(baseline.observation.costs);
        Stats pooledBaseline = stats(baselineCosts);
        Map<Double, List<ResultRow>> groups = new TreeMap<>();
        for (ResultRow row : results) groups.computeIfAbsent(row.lambda, ignored -> new ArrayList<>()).add(row);
        List<AggregateRow> output = new ArrayList<>();
        for (Map.Entry<Double, List<ResultRow>> entry : groups.entrySet()) {
            List<ResultRow> rows = entry.getValue();
            rows.sort(Comparator.comparingInt(row -> row.market));
            List<CostRow> pooled = new ArrayList<>(MARKETS * OOS_DRAWS);
            for (ResultRow row : rows) pooled.addAll(row.observation.costs);
            Stats pooledStats = stats(pooled);
            output.add(new AggregateRow(entry.getKey(), rows, pooledStats,
                    averageImprovement(rows, baselines, Metric.MEAN),
                    averageImprovement(rows, baselines, Metric.SD),
                    averageImprovement(rows, baselines, Metric.Q95),
                    averageImprovement(rows, baselines, Metric.CVAR),
                    averageImprovement(rows, baselines, Metric.MAX),
                    improvement(pooledBaseline.mean, pooledStats.mean),
                    improvement(pooledBaseline.sd, pooledStats.sd),
                    improvement(pooledBaseline.q95, pooledStats.q95),
                    improvement(pooledBaseline.cvar95, pooledStats.cvar95),
                    improvement(pooledBaseline.maximum, pooledStats.maximum)));
        }
        return output;
    }

    private static void validateAudit(Audit audit) {
        double maximum = Collections.max(List.of(audit.maxDecompositionError,
                audit.maxSummaryMeanError, audit.maxSummarySdError, audit.maxSummaryQ95Error,
                audit.maxSummaryCvarError, audit.maxSummaryTransportError,
                audit.maxSummarySpotError, audit.maxSummaryPenaltyError));
        if (maximum > TOLERANCE) {
            throw new IllegalStateException("Raw/summary or decomposition validation failed: " + maximum);
        }
    }

    private static double averageImprovement(List<ResultRow> rows, Map<Integer, Baseline> baselines,
                                             Metric metric) {
        double sum = 0.0;
        for (ResultRow row : rows) {
            double baseline = metric.value(baselines.get(row.market).observation.stats);
            sum += 100.0 * (baseline - metric.value(row.observation.stats)) / baseline;
        }
        return sum / rows.size();
    }

    private static Stats stats(List<CostRow> rows) {
        List<Double> sorted = rows.stream().map(row -> row.total).sorted().toList();
        double mean = rows.stream().mapToDouble(row -> row.total).average().orElseThrow();
        double variance = rows.stream().mapToDouble(row -> (row.total - mean) * (row.total - mean))
                .average().orElseThrow();
        double q95 = quantile(sorted, 0.95);
        double cvar = sorted.stream().mapToDouble(Double::doubleValue).filter(value -> value >= q95)
                .average().orElseThrow();
        return new Stats(mean, Math.sqrt(variance), q95, cvar, sorted.get(sorted.size() - 1),
                rows.stream().mapToDouble(row -> row.transport).average().orElseThrow(),
                rows.stream().mapToDouble(row -> row.spot).average().orElseThrow(),
                rows.stream().mapToDouble(row -> row.penalty).average().orElseThrow());
    }

    private static double quantile(List<Double> sorted, double probability) {
        double position = probability * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double fraction = position - lower;
        return sorted.get(lower) * (1.0 - fraction) + sorted.get(upper) * fraction;
    }

    private static void writePerMarket(Path path, Map<Integer, Baseline> baselines,
                                       List<ResultRow> results) throws Exception {
        results.sort(Comparator.comparingDouble((ResultRow row) -> row.lambda)
                .thenComparingInt(row -> row.market));
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("source,market,procurementSeed,demandSeed,lambda,selectedCount,yBinary,certifiedOptimal,"
                    + "solverStatus,timeout,relativeGap,optimizerTimeSec,mean,sd,q95,cvar95,max,transport,spot,penalty,"
                    + "meanImprovementPct,sdImprovementPct,q95ImprovementPct,cvar95ImprovementPct,maxImprovementPct");
            writer.newLine();
            for (ResultRow row : results) {
                Stats value = row.observation.stats;
                Stats base = baselines.get(row.market).observation.stats;
                writer.write(String.format(Locale.US,
                        "%s,%d,%d,%d,%.17g,%d,%s,%s,%s,%s,%.17g,%.9f,"
                                + "%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                                + "%.17g,%.17g,%.17g,%.17g,%.17g%n",
                        row.source, row.market, row.market, 40_000L + 10_000L * row.market,
                        row.lambda, row.selected, csv(row.yBinary), row.certified,
                        csv(row.solverStatus), row.timeout, row.relativeGap, row.optimizerTime,
                        value.mean, value.sd, value.q95, value.cvar95, value.maximum,
                        value.transport, value.spot, value.penalty,
                        improvement(base.mean, value.mean), improvement(base.sd, value.sd),
                        improvement(base.q95, value.q95), improvement(base.cvar95, value.cvar95),
                        improvement(base.maximum, value.maximum)));
            }
        }
    }

    private static void writeAggregate(Path path, List<AggregateRow> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(aggregateHeader());
            writer.newLine();
            for (AggregateRow row : rows) writeAggregateRow(writer, row);
        }
    }

    private static void writeCurve(Path path, List<AggregateRow> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write(aggregateHeader());
            writer.newLine();
            for (AggregateRow row : rows) writeAggregateRow(writer, row);
        }
    }

    private static String aggregateHeader() {
        return "lambda,nMarkets,nDraws,selectedMean,selectedMin,selectedMax,marketAvgMean,marketAvgSd,"
                + "marketAvgQ95,marketAvgCVaR95,marketAvgMax,marketAvgTransport,marketAvgSpot,marketAvgPenalty,"
                + "pooledMean,pooledPopulationSd,pooledQ95,pooledCVaR95,pooledMax,pooledTransport,pooledSpot,"
                + "pooledPenalty,meanImprovementPct,sdImprovementPct,q95ImprovementPct,cvar95ImprovementPct,"
                + "maxImprovementPct,pooledMeanImprovementPct,pooledSdImprovementPct,pooledQ95ImprovementPct,"
                + "pooledCVaR95ImprovementPct,pooledMaxImprovementPct,certifiedCount,timeoutCount,maxGap,"
                + "optimizerMeanSec,optimizerMaxSec";
    }

    private static void writeAggregateRow(BufferedWriter writer, AggregateRow row) throws Exception {
        List<ResultRow> values = row.rows;
        writer.write(String.format(Locale.US,
                "%.17g,%d,%d,%.17g,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                        + "%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                        + "%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%d,%d,%.17g,%.9f,%.9f%n",
                row.lambda, values.size(), values.size() * OOS_DRAWS,
                mean(values, value -> value.selected),
                values.stream().mapToInt(value -> value.selected).min().orElseThrow(),
                values.stream().mapToInt(value -> value.selected).max().orElseThrow(),
                mean(values, value -> value.observation.stats.mean),
                mean(values, value -> value.observation.stats.sd),
                mean(values, value -> value.observation.stats.q95),
                mean(values, value -> value.observation.stats.cvar95),
                mean(values, value -> value.observation.stats.maximum),
                mean(values, value -> value.observation.stats.transport),
                mean(values, value -> value.observation.stats.spot),
                mean(values, value -> value.observation.stats.penalty),
                row.pooled.mean, row.pooled.sd, row.pooled.q95, row.pooled.cvar95, row.pooled.maximum,
                row.pooled.transport, row.pooled.spot, row.pooled.penalty,
                row.meanImprovement, row.sdImprovement, row.q95Improvement,
                row.cvarImprovement, row.maxImprovement,
                row.pooledMeanImprovement, row.pooledSdImprovement, row.pooledQ95Improvement,
                row.pooledCvarImprovement, row.pooledMaxImprovement,
                values.stream().mapToInt(value -> value.certified ? 1 : 0).sum(),
                values.stream().mapToInt(value -> value.timeout ? 1 : 0).sum(),
                values.stream().mapToDouble(value -> value.relativeGap).max().orElseThrow(),
                mean(values, value -> value.optimizerTime),
                values.stream().mapToDouble(value -> value.optimizerTime).max().orElseThrow()));
    }

    private static void writeValidation(Path path, List<SourceSpec> sources,
                                        Map<Integer, Baseline> baselines, List<ResultRow> results,
                                        Audit audit) throws Exception {
        List<ResultRow> nonCertified = results.stream().filter(row -> !row.certified).toList();
        List<ResultRow> timeouts = results.stream().filter(row -> row.timeout).toList();
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("Formal sources (356/360 and EXCLUDED roots forbidden):"); writer.newLine();
            for (SourceSpec source : sources) {
                writer.write(source.label + "=" + source.root + " markets=" + source.firstMarket + ".."
                        + source.lastMarket + " lambda=" + source.lambdas); writer.newLine();
            }
            writer.write("Unique D baselines=" + baselines.size()); writer.newLine();
            writer.write("Cross-partition D comparisons=" + audit.baselineComparisons); writer.newLine();
            writer.write("D equality covers every non-time summary field and all 500 raw OOS rows; wall-clock fields are excluded."); writer.newLine();
            writer.write("Max cross-partition D raw difference=" + audit.maxBaselineRawDifference); writer.newLine();
            writer.write("Lambda results=" + results.size() + " (10 lambda * 20 markets)"); writer.newLine();
            writer.write("OOS rows per result=500; lambda OOS rows=" + results.size() * OOS_DRAWS); writer.newLine();
            writer.write("Max cost decomposition error=" + audit.maxDecompositionError); writer.newLine();
            writer.write("Max raw-vs-summary mean error=" + audit.maxSummaryMeanError); writer.newLine();
            writer.write("Max raw-vs-summary population SD error=" + audit.maxSummarySdError); writer.newLine();
            writer.write("Max raw-vs-summary Q95 error=" + audit.maxSummaryQ95Error); writer.newLine();
            writer.write("Max raw-vs-summary CVaR95 error=" + audit.maxSummaryCvarError); writer.newLine();
            writer.write("Max raw-vs-summary transport error=" + audit.maxSummaryTransportError); writer.newLine();
            writer.write("Max raw-vs-summary spot error=" + audit.maxSummarySpotError); writer.newLine();
            writer.write("Max raw-vs-summary penalty error=" + audit.maxSummaryPenaltyError); writer.newLine();
            writer.write("Certified=" + (results.size() - nonCertified.size()) + "/" + results.size()); writer.newLine();
            writer.write("Timeout rule: solverStatus contains 'time', or uncertified with optimizerTimeSec>=599."); writer.newLine();
            writer.write("Timeout count=" + timeouts.size()); writer.newLine();
            writer.write("Non-certified results:"); writer.newLine();
            if (nonCertified.isEmpty()) writer.write("none\n");
            for (ResultRow row : nonCertified) {
                writer.write(String.format(Locale.US,
                        "source=%s market=%d lambda=%.17g status=%s gap=%.17g time=%.9f timeout=%s%n",
                        row.source, row.market, row.lambda, row.solverStatus,
                        row.relativeGap, row.optimizerTime, row.timeout));
            }
            writer.write("Aggregation: marketAvg* is the arithmetic mean of 20 market-level statistics; ");
            writer.write("pooled* is recomputed from all 10,000 OOS draws for that lambda."); writer.newLine();
            writer.write("Improvement columns are paired market-level 100*(D-method)/D, then averaged over 20 markets.");
            writer.newLine();
        }
    }

    private static double improvement(double baseline, double value) {
        return 100.0 * (baseline - value) / baseline;
    }

    private static double mean(List<ResultRow> rows, Value value) {
        return rows.stream().mapToDouble(value::get).average().orElseThrow();
    }

    private static Map<String, String> readSingleCsv(Path path) throws Exception {
        List<Map<String, String>> rows = readCsv(path);
        if (rows.size() != 1) throw new IllegalStateException("Expected one row in " + path);
        return rows.get(0);
    }

    private static List<Map<String, String>> readCsv(Path path) throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) throw new IllegalStateException("Empty CSV: " + path);
            List<String> header = parseCsvLine(headerLine);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> fields = parseCsvLine(line);
                if (fields.size() != header.size()) {
                    throw new IllegalStateException("CSV width mismatch in " + path);
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < header.size(); i++) row.put(header.get(i), fields.get(i));
                rows.add(row);
            }
        }
        return rows;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (ch == ',' && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else field.append(ch);
        }
        fields.add(field.toString());
        return fields;
    }

    private static String required(Map<String, String> row, String key, Path path) {
        String value = row.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key + " in " + path);
        return value;
    }

    private static double number(Map<String, String> row, String key, Path path) {
        return Double.parseDouble(required(row, key, path));
    }

    private static String token(double value) {
        return Double.toString(value).replace('.', '_');
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private record SourceSpec(String label, Path root, int firstMarket, int lastMarket,
                              List<Double> lambdas) { }
    private record CostRow(double total, double transport, double spot, double penalty,
                           double shortfall) { }
    private record Stats(double mean, double sd, double q95, double cvar95, double maximum,
                         double transport, double spot, double penalty) { }
    private record Observation(List<CostRow> costs, Stats stats) { }
    private record Baseline(String source, Map<String, String> summary, Observation observation) { }
    private record ResultRow(String source, int market, double lambda, int selected, String yBinary,
                             boolean certified, double relativeGap, String solverStatus, boolean timeout,
                             double optimizerTime, Observation observation) { }
    private record AggregateRow(double lambda, List<ResultRow> rows, Stats pooled,
                                double meanImprovement, double sdImprovement, double q95Improvement,
                                double cvarImprovement, double maxImprovement,
                                double pooledMeanImprovement, double pooledSdImprovement,
                                double pooledQ95Improvement, double pooledCvarImprovement,
                                double pooledMaxImprovement) { }

    private enum Metric {
        MEAN { @Override double value(Stats stats) { return stats.mean; } },
        SD { @Override double value(Stats stats) { return stats.sd; } },
        Q95 { @Override double value(Stats stats) { return stats.q95; } },
        CVAR { @Override double value(Stats stats) { return stats.cvar95; } },
        MAX { @Override double value(Stats stats) { return stats.maximum; } };
        abstract double value(Stats stats);
    }

    @FunctionalInterface
    private interface Value { double get(ResultRow row); }

    private static final class Audit {
        int baselineComparisons;
        double maxBaselineRawDifference;
        double maxDecompositionError;
        double maxSummaryMeanError;
        double maxSummarySdError;
        double maxSummaryQ95Error;
        double maxSummaryCvarError;
        double maxSummaryTransportError;
        double maxSummarySpotError;
        double maxSummaryPenaltyError;
    }
}
