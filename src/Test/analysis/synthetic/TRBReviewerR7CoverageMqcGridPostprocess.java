package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;

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

/** Strict, non-solving postprocessor for the R7 coverage/MQC grid. */
public final class TRBReviewerR7CoverageMqcGridPostprocess {
    private static final int J = 23;
    private static final long BASELINE_SEED = 20260809L;
    private static final List<String[]> PAIRS = List.of(
            new String[]{"SAA", "D"},
            new String[]{"CSAA", "SAA"},
            new String[]{"DRO", "CSAA"},
            new String[]{"RCSAA_LBBD_SEARCH", "CSAA"},
            new String[]{"RCSAA_LBBD_SEARCH", "DRO"},
            new String[]{"RCSAA_ENUMERATE", "CSAA"},
            new String[]{"RCSAA_ENUMERATE", "DRO"});

    private TRBReviewerR7CoverageMqcGridPostprocess() {
    }

    /** Usage: {@code <summary-output-dir> <raw-root> [raw-root ...]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: <summary-output-dir> <raw-root> [raw-root ...]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        requireEmptyDirectory(output);
        Files.createDirectories(output);

        List<Path> roots = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            roots.add(Path.of(args[i]).toAbsolutePath().normalize());
        }
        int procurementSeed = consistentIntProperty(roots, "procurementSeed");
        int carriers = optionalConsistentIntProperty(roots, "carrierCount", 10);
        double betaRatio = optionalConsistentDoubleProperty(roots, "betaRatio", 0.7);
        String penaltyRule = penaltyRule(roots);
        boolean normalizeCoverageCapacity = optionalConsistentBooleanProperty(
                roots, "coverageCapacityNormalization", false);
        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                J, 2300.0, 0.65, BASELINE_SEED);
        Config procurementConfig = new Config();
        procurementConfig.seed = procurementSeed;
        InstanceGenerator.GenConfig marketConfig = new InstanceGenerator.GenConfig();
        marketConfig.betaRatio = betaRatio;
        ProcurementParams fullMarket = InstanceGenerator.generate(
                carriers, baseline, marketConfig, procurementConfig);
        double marketScale = 10.0 / carriers;
        if (marketScale != 1.0) {
            fullMarket = TRBReviewerR7CoverageMqcGridExperiment.withMarketSizeScale(
                    fullMarket, marketScale);
        }

        Map<String, EnrichedRow> rows = new LinkedHashMap<>();
        for (Path root : roots) {
            readRoot(root, fullMarket, baseline, penaltyRule,
                    normalizeCoverageCapacity, rows);
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("No query results found.");

        writeEnrichedQueries(output.resolve("query_results_enriched.csv"), rows.values());
        Map<String, MethodSummary> summaries = summarize(rows.values());
        writeMethodSummaries(output.resolve("pooled_method_summary.csv"), summaries.values());
        writeStrictPairs(output.resolve("paired_method_comparisons.csv"), rows.values(), summaries);
        writeManifest(output.resolve("postprocess.properties"), roots, rows.size());
    }

    private static void readRoot(Path root,
                                 ProcurementParams fullMarket,
                                 double[] baseline,
                                 String penaltyRule,
                                 boolean normalizeCoverageCapacity,
                                 Map<String, EnrichedRow> combined) throws Exception {
        Path csv = root.resolve("query_results.csv");
        if (!Files.isRegularFile(csv)) {
            throw new IllegalArgumentException("Missing query_results.csv: " + root);
        }
        try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> columns = columns(header);
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) continue;
                List<String> values = parseCsv(line);
                String cell = value(values, columns, "cell");
                double coverage = number(values, columns, "targetCoverage");
                double quantityScale = number(values, columns, "quantityScale");
                int training = integer(values, columns, "trainingReplication");
                int query = integer(values, columns, "query");
                String method = value(values, columns, "method");
                String yBinary = value(values, columns, "yBinary");
                ProcurementParams params = TRBReviewerR7CoverageMqcGridExperiment.withNestedCoverage(
                        fullMarket, baseline, coverage, quantityScale, penaltyRule,
                        normalizeCoverageCapacity);
                Exposure exposure = exposure(yBinary, params);
                double meanDemand = number(values, columns, "meanOosDemand");
                Path oosPath = root.resolve(cell).resolve(String.format(Locale.US,
                        "train_%02d/query_%03d/%s/oos_costs.csv", training, query, method));
                OosSeries oos = readOos(oosPath, method);
                OosSummary canonical = summarizeOos(oos);
                validateQuerySummary(values, columns, canonical, cell, training, query, method);
                EnrichedRow row = new EnrichedRow(root, cell, coverage, quantityScale,
                        training, query, method,
                        integer(values, columns, "selectedCount"), yBinary,
                        canonical.mean, canonical.sd, canonical.q95, canonical.cvar95,
                        canonical.transport, canonical.spot, canonical.penalty,
                        number(values, columns, "selectedAggregateMqc"), meanDemand,
                        number(values, columns, "meanSelectedMqcToDemandRatio"),
                        number(values, columns, "meanStructuralExcessMqc"),
                        Boolean.parseBoolean(value(values, columns, "certifiedOptimal")),
                        exposure.meanSelectedH, exposure.selectedMqcRateExposure,
                        exposure.selectedMqcRateExposure / meanDemand, oos);
                String key = rowKey(cell, training, query, method);
                EnrichedRow previous = combined.putIfAbsent(key, row);
                if (previous != null && !sameResult(previous, row)) {
                    throw new IllegalStateException("Conflicting duplicate result: " + key);
                }
            }
        }
    }

    private static boolean sameResult(EnrichedRow left, EnrichedRow right) {
        return left.cell.equals(right.cell)
                && close(left.coverage, right.coverage)
                && close(left.quantityScale, right.quantityScale)
                && left.training == right.training && left.query == right.query
                && left.method.equals(right.method)
                && left.selectedCount == right.selectedCount
                && left.yBinary.equals(right.yBinary)
                && close(left.mean, right.mean)
                && close(left.conditionalSd, right.conditionalSd)
                && close(left.conditionalQ95, right.conditionalQ95)
                && close(left.conditionalCvar95, right.conditionalCvar95)
                && close(left.transport, right.transport)
                && close(left.spot, right.spot)
                && close(left.penalty, right.penalty)
                && close(left.selectedMqc, right.selectedMqc)
                && close(left.meanDemand, right.meanDemand)
                && close(left.meanMqcToDemandRatio, right.meanMqcToDemandRatio)
                && close(left.meanStructuralExcessMqc, right.meanStructuralExcessMqc)
                && left.certified == right.certified
                && close(left.meanSelectedH, right.meanSelectedH)
                && close(left.mqcRateExposure, right.mqcRateExposure)
                && close(left.mqcRateExposureOverMeanDemand,
                         right.mqcRateExposureOverMeanDemand)
                && sameOos(left.oos, right.oos);
    }

    private static boolean sameOos(OosSeries left, OosSeries right) {
        return sameCosts(left.total, right.total)
                && sameCosts(left.transport, right.transport)
                && sameCosts(left.spot, right.spot)
                && sameCosts(left.penalty, right.penalty);
    }

    private static boolean sameCosts(Map<Integer, Double> left,
                                     Map<Integer, Double> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (int draw : left.keySet()) {
            if (!close(left.get(draw), right.get(draw))) return false;
        }
        return true;
    }

    private static boolean close(double left, double right) {
        return Double.doubleToLongBits(left) == Double.doubleToLongBits(right)
                || Math.abs(left - right) <= 1e-9
                * Math.max(1.0, Math.max(Math.abs(left), Math.abs(right)));
    }

    private static OosSeries readOos(Path path, String expectedMethod) throws Exception {
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("Missing OOS file: " + path);
        Map<Integer, Double> total = new LinkedHashMap<>();
        Map<Integer, Double> transport = new LinkedHashMap<>();
        Map<Integer, Double> spot = new LinkedHashMap<>();
        Map<Integer, Double> penalty = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = parseCsv(reader.readLine());
            Map<String, Integer> columns = columns(header);
            for (String line; (line = reader.readLine()) != null;) {
                List<String> values = parseCsv(line);
                String method = value(values, columns, "method");
                if (!expectedMethod.equals(method)) {
                    throw new IllegalStateException("Method mismatch in " + path);
                }
                int draw = integer(values, columns, "drawId");
                double totalValue = number(values, columns, "totalCost");
                double transportValue = number(values, columns, "transportCost");
                double spotValue = number(values, columns, "spotCost");
                double penaltyValue = number(values, columns, "penaltyCost");
                double reconstructed = transportValue + spotValue + penaltyValue;
                if (Math.abs(totalValue - reconstructed) > 1e-7 * Math.max(1.0, Math.abs(totalValue))) {
                    throw new IllegalStateException("Cost decomposition mismatch: " + path + " draw=" + draw);
                }
                if (total.putIfAbsent(draw, totalValue) != null) {
                    throw new IllegalStateException("Duplicate draw: " + path + " draw=" + draw);
                }
                transport.put(draw, transportValue);
                spot.put(draw, spotValue);
                penalty.put(draw, penaltyValue);
            }
        }
        if (total.isEmpty()) throw new IllegalStateException("Empty OOS file: " + path);
        return new OosSeries(total, transport, spot, penalty);
    }

    private static OosSummary summarizeOos(OosSeries oos) {
        List<Double> totals = new ArrayList<>(oos.total.values());
        double mean = mean(totals);
        return new OosSummary(mean, sd(totals, mean), quantile(totals, 0.95),
                cvar95(totals), mean(new ArrayList<>(oos.transport.values())),
                mean(new ArrayList<>(oos.spot.values())),
                mean(new ArrayList<>(oos.penalty.values())));
    }

    private static void validateQuerySummary(List<String> values,
                                             Map<String, Integer> columns,
                                             OosSummary canonical,
                                             String cell,
                                             int training,
                                             int query,
                                             String method) {
        requireClose(number(values, columns, "meanOosCost"), canonical.mean,
                "meanOosCost", cell, training, query, method);
        requireClose(number(values, columns, "sdOosCost"), canonical.sd,
                "sdOosCost", cell, training, query, method);
        requireClose(number(values, columns, "q95OosCost"), canonical.q95,
                "q95OosCost", cell, training, query, method);
        requireClose(number(values, columns, "cvar95OosCost"), canonical.cvar95,
                "cvar95OosCost", cell, training, query, method);
        requireClose(number(values, columns, "meanTransportCost"), canonical.transport,
                "meanTransportCost", cell, training, query, method);
        requireClose(number(values, columns, "meanSpotCost"), canonical.spot,
                "meanSpotCost", cell, training, query, method);
        requireClose(number(values, columns, "meanMqcPenalty"), canonical.penalty,
                "meanMqcPenalty", cell, training, query, method);
    }

    private static void requireClose(double reported, double canonical,
                                     String metric, String cell, int training,
                                     int query, String method) {
        if (!close(reported, canonical)) {
            throw new IllegalStateException("Query summary mismatch for " + metric
                    + ": cell=" + cell + " train=" + training + " query=" + query
                    + " method=" + method + " reported=" + reported
                    + " canonical=" + canonical);
        }
    }

    private static Exposure exposure(String binary, ProcurementParams params) {
        double[] y = parseBinary(binary);
        int selected = 0;
        double h = 0.0;
        double ph = 0.0;
        for (int i = 0; i < y.length; i++) {
            if (y[i] <= 0.5) continue;
            selected++;
            h += params.h[i];
            ph += params.p[i] * params.h[i];
        }
        return new Exposure(selected == 0 ? Double.NaN : h / selected, ph);
    }

    private static Map<String, MethodSummary> summarize(Iterable<EnrichedRow> rows) {
        Map<String, SummaryAccumulator> accumulators = new LinkedHashMap<>();
        for (EnrichedRow row : rows) {
            accumulators.computeIfAbsent(row.cell + "|" + row.method,
                    ignored -> new SummaryAccumulator(row.cell, row.coverage,
                            row.quantityScale, row.method)).add(row);
        }
        Map<String, MethodSummary> summaries = new LinkedHashMap<>();
        for (Map.Entry<String, SummaryAccumulator> entry : accumulators.entrySet()) {
            summaries.put(entry.getKey(), entry.getValue().finish());
        }
        return summaries;
    }

    private static void writeEnrichedQueries(Path path, Iterable<EnrichedRow> rows) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("cell,targetCoverage,quantityScale,trainingReplication,query,method,"
                    + "selectedCount,yBinary,meanOosCost,sdOosCost,q95OosCost,cvar95OosCost,"
                    + "meanTransportCost,meanSpotCost,meanMqcPenalty,selectedAggregateMqc,"
                    + "meanOosDemand,meanSelectedMqcToDemandRatio,meanStructuralExcessMqc,"
                    + "meanSelectedH,selectedMqcRateExposure,selectedMqcRateExposureOverMeanDemand,"
                    + "certifiedOptimal,rawRoot");
            out.newLine();
            for (EnrichedRow row : rows) {
                out.write(String.format(Locale.US,
                        "%s,%.2f,%.2f,%d,%d,%s,%d,%s,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%.12f,%s,%s%n",
                        row.cell, row.coverage, row.quantityScale, row.training, row.query,
                        row.method, row.selectedCount, csv(row.yBinary), row.mean,
                        row.conditionalSd, row.conditionalQ95, row.conditionalCvar95,
                        row.transport, row.spot, row.penalty, row.selectedMqc,
                        row.meanDemand, row.meanMqcToDemandRatio,
                        row.meanStructuralExcessMqc, row.meanSelectedH,
                        row.mqcRateExposure, row.mqcRateExposureOverMeanDemand,
                        row.certified, csv(row.root.toString())));
            }
        }
    }

    private static void writeMethodSummaries(Path path,
                                             Iterable<MethodSummary> summaries) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("cell,targetCoverage,quantityScale,method,queries,nDraws,meanConditionalMean,"
                    + "meanConditionalSd,meanConditionalQ95,meanConditionalCvar95,pooledMean,"
                    + "pooledPopulationSd,pooledQ95,pooledCvar95,meanTransportCost,meanSpotCost,"
                    + "meanMqcPenalty,meanSelectedCount,meanSelectedAggregateMqc,meanOosDemand,"
                    + "meanSelectedMqcToDemandRatio,meanStructuralExcessMqc,meanSelectedH,"
                    + "meanSelectedMqcRateExposure,selectedMqcRateExposureOverMeanDemand,certifiedCount");
            out.newLine();
            for (MethodSummary row : summaries) {
                out.write(String.format(Locale.US,
                        "%s,%.2f,%.2f,%s,%d,%d,%.12f,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,"
                                + "%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%d%n",
                        row.cell, row.coverage, row.quantityScale, row.method,
                        row.queries, row.draws, row.meanConditionalMean,
                        row.meanConditionalSd, row.meanConditionalQ95,
                        row.meanConditionalCvar95, row.pooledMean, row.pooledSd,
                        row.pooledQ95, row.pooledCvar95, row.transport, row.spot,
                        row.penalty, row.selectedCount, row.selectedMqc, row.meanDemand,
                        row.meanMqcToDemandRatio, row.meanStructuralExcessMqc,
                        row.meanSelectedH, row.mqcRateExposure,
                        row.mqcRateExposure / row.meanDemand, row.certifiedCount));
            }
        }
    }

    private static void writeStrictPairs(Path path,
                                         Iterable<EnrichedRow> allRows,
                                         Map<String, MethodSummary> summaries) throws Exception {
        Map<String, Map<String, Map<String, EnrichedRow>>> cells = new LinkedHashMap<>();
        for (EnrichedRow row : allRows) {
            String queryKey = row.training + "|" + row.query;
            cells.computeIfAbsent(row.cell, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(row.method, ignored -> new LinkedHashMap<>())
                    .put(queryKey, row);
        }
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("cell,method,baseline,comparisonScope,methodAvailableQueries,"
                    + "baselineAvailableQueries,pairedQueries,pairedDraws,"
                    + "meanQueryMeanImprovementPct,meanQueryConditionalSdImprovementPct,"
                    + "meanQueryConditionalQ95ImprovementPct,meanQueryConditionalCvar95ImprovementPct,"
                    + "pooledMeanImprovementPct,pooledSdImprovementPct,pooledQ95ImprovementPct,"
                    + "pooledCvar95ImprovementPct,meanPairedCostDifference,sdPairedCostDifference,"
                    + "meanPairedDrawImprovementPct,methodWinRate,meanSelectedCountDifference,"
                    + "meanMqcPenaltyDifference,meanSpotCostDifference");
            out.newLine();
            for (Map.Entry<String, Map<String, Map<String, EnrichedRow>>> cellEntry : cells.entrySet()) {
                String cellName = cellEntry.getKey();
                Map<String, Map<String, EnrichedRow>> methods = cellEntry.getValue();
                for (String[] pair : PAIRS) {
                    Map<String, EnrichedRow> methodRows = methods.get(pair[0]);
                    Map<String, EnrichedRow> baselineRows = methods.get(pair[1]);
                    if (methodRows == null || baselineRows == null) continue;
                    boolean fullQueryMatch = methodRows.keySet().equals(baselineRows.keySet());
                    boolean declaredGateSubset = ("RCSAA_ENUMERATE".equals(pair[0])
                            || "RCSAA_LBBD_SEARCH".equals(pair[0])
                            || "DRO".equals(pair[0]))
                            && baselineRows.keySet().containsAll(methodRows.keySet());
                    if (!fullQueryMatch && !declaredGateSubset) {
                        throw new IllegalStateException("Unpaired query sets for " + cellName
                                + " " + pair[0] + " vs " + pair[1]);
                    }
                    String comparisonScope = fullQueryMatch
                            ? "FULL_QUERY_SET" : pair[0] + "_METHOD_QUERY_SUBSET_GATE";
                    PairAccumulator paired = new PairAccumulator();
                    for (String queryKey : methodRows.keySet()) {
                        EnrichedRow method = methodRows.get(queryKey);
                        EnrichedRow baseline = baselineRows.get(queryKey);
                        paired.addQuery(method, baseline);
                    }
                    paired.write(out, cellName, pair[0], pair[1], comparisonScope,
                            methodRows.size(), baselineRows.size());
                }
            }
        }
    }

    private static int consistentIntProperty(List<Path> roots, String key) throws Exception {
        Integer expected = null;
        for (Path root : roots) {
            Properties properties = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(
                    root.resolve("experiment.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            int value = Integer.parseInt(properties.getProperty(key));
            if (expected != null && expected != value) {
                throw new IllegalArgumentException("Inconsistent " + key + " across raw roots.");
            }
            expected = value;
        }
        return expected;
    }

    private static int optionalConsistentIntProperty(List<Path> roots,
                                                     String key,
                                                     int defaultValue) throws Exception {
        Integer expected = null;
        for (Path root : roots) {
            Properties properties = loadExperimentProperties(root);
            int value = Integer.parseInt(properties.getProperty(
                    key, Integer.toString(defaultValue)));
            if (expected != null && expected != value) {
                throw new IllegalArgumentException(
                        "Inconsistent " + key + " across raw roots.");
            }
            expected = value;
        }
        return expected;
    }

    private static double optionalConsistentDoubleProperty(List<Path> roots,
                                                           String key,
                                                           double defaultValue) throws Exception {
        Double expected = null;
        for (Path root : roots) {
            Properties properties = loadExperimentProperties(root);
            double value = Double.parseDouble(properties.getProperty(
                    key, Double.toString(defaultValue)));
            if (expected != null && Double.compare(expected, value) != 0) {
                throw new IllegalArgumentException(
                        "Inconsistent " + key + " across raw roots.");
            }
            expected = value;
        }
        return expected;
    }

    private static String penaltyRule(List<Path> roots) throws Exception {
        String expected = null;
        for (Path root : roots) {
            String description = loadExperimentProperties(root).getProperty(
                    "mqcPenalty", "minimum retained eligible r_ij");
            String value = description.startsWith("maximum") ? "max" : "min";
            if (expected != null && !expected.equals(value)) {
                throw new IllegalArgumentException(
                        "Inconsistent mqcPenalty across raw roots.");
            }
            expected = value;
        }
        return expected;
    }

    private static boolean optionalConsistentBooleanProperty(List<Path> roots,
                                                             String key,
                                                             boolean defaultValue)
            throws Exception {
        Boolean expected = null;
        for (Path root : roots) {
            boolean value = Boolean.parseBoolean(loadExperimentProperties(root)
                    .getProperty(key, Boolean.toString(defaultValue)));
            if (expected != null && expected != value) {
                throw new IllegalArgumentException(
                        "Inconsistent " + key + " across raw roots.");
            }
            expected = value;
        }
        return expected;
    }

    private static Properties loadExperimentProperties(Path root) throws Exception {
        Properties properties = new Properties();
        try (BufferedReader reader = Files.newBufferedReader(
                root.resolve("experiment.properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void writeManifest(Path path, List<Path> roots, int rowCount) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("strictPairKey", "cell,trainingReplication,query,drawId");
        properties.setProperty("canonicalQueryStatistics", "recomputed from oos_costs.csv");
        properties.setProperty("validatedQueryMetrics",
                "mean,sd,q95,cvar95,transport,spot,penalty");
        properties.setProperty("duplicateKeyValidation",
                "all summary fields and draw-level total,transport,spot,penalty");
        properties.setProperty("subsetPairing",
                "DRO or RCSAA gates may use an explicit method-query subset of baseline queries");
        properties.setProperty("queryRows", Integer.toString(rowCount));
        for (int i = 0; i < roots.size(); i++) {
            properties.setProperty("rawRoot." + (i + 1), roots.get(i).toString());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "R7 grid strict postprocessing");
        }
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int i = 0; i < header.size(); i++) result.put(header.get(i), i);
        return result;
    }

    private static String value(List<String> values, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index >= values.size()) {
            throw new IllegalArgumentException("Missing CSV column: " + name);
        }
        return values.get(index);
    }

    private static double number(List<String> values, Map<String, Integer> columns, String name) {
        return Double.parseDouble(value(values, columns, name));
    }

    private static int integer(List<String> values, Map<String, Integer> columns, String name) {
        return Integer.parseInt(value(values, columns, name));
    }

    private static List<String> parseCsv(String line) {
        if (line == null) throw new IllegalArgumentException("Missing CSV header.");
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (current == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(current);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed CSV quote: " + line);
        fields.add(field.toString());
        return fields;
    }

    private static String rowKey(String cell, int training, int query, String method) {
        return cell + "|" + training + "|" + query + "|" + method;
    }

    private static double[] parseBinary(String binary) {
        String[] values = binary.substring(1, binary.length() - 1).split(",");
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = Double.parseDouble(values[i]);
        return result;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static double improvement(double method, double baseline) {
        return 100.0 * (baseline - method) / baseline;
    }

    private static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    private static double sd(List<Double> values, double mean) {
        double sum = 0.0;
        for (double value : values) sum += (value - mean) * (value - mean);
        return Math.sqrt(sum / values.size());
    }

    private static double quantile(List<Double> values, double probability) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        double position = probability * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double fraction = position - lower;
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction;
    }

    private static double cvar95(List<Double> values) {
        double q95 = quantile(values, 0.95);
        return values.stream().mapToDouble(Double::doubleValue)
                .filter(value -> value >= q95).average().orElseThrow();
    }

    private static void requireEmptyDirectory(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output directory must be empty: " + directory);
            }
        }
    }

    private record Exposure(double meanSelectedH, double selectedMqcRateExposure) {
    }

    private record OosSeries(Map<Integer, Double> total,
                             Map<Integer, Double> transport,
                             Map<Integer, Double> spot,
                             Map<Integer, Double> penalty) {
    }

    private record OosSummary(double mean, double sd, double q95, double cvar95,
                              double transport, double spot, double penalty) {
    }

    private record EnrichedRow(Path root, String cell, double coverage, double quantityScale,
                               int training, int query, String method, int selectedCount,
                               String yBinary, double mean, double conditionalSd,
                               double conditionalQ95, double conditionalCvar95,
                               double transport, double spot, double penalty,
                               double selectedMqc, double meanDemand,
                               double meanMqcToDemandRatio,
                               double meanStructuralExcessMqc, boolean certified,
                               double meanSelectedH, double mqcRateExposure,
                               double mqcRateExposureOverMeanDemand, OosSeries oos) {
    }

    private record MethodSummary(String cell, double coverage, double quantityScale,
                                 String method, int queries, int draws,
                                 double meanConditionalMean, double meanConditionalSd,
                                 double meanConditionalQ95, double meanConditionalCvar95,
                                 double pooledMean, double pooledSd, double pooledQ95,
                                 double pooledCvar95, double transport, double spot,
                                 double penalty, double selectedCount, double selectedMqc,
                                 double meanDemand, double meanMqcToDemandRatio,
                                 double meanStructuralExcessMqc, double meanSelectedH,
                                 double mqcRateExposure, int certifiedCount) {
    }

    private static final class SummaryAccumulator {
        final String cell;
        final double coverage;
        final double quantityScale;
        final String method;
        final List<Double> pooled = new ArrayList<>();
        double conditionalMean;
        double conditionalSd;
        double conditionalQ95;
        double conditionalCvar95;
        double transport;
        double spot;
        double penalty;
        double selectedCount;
        double selectedMqc;
        double meanDemand;
        double meanMqcToDemandRatio;
        double meanStructuralExcessMqc;
        double meanSelectedH;
        double mqcRateExposure;
        int queries;
        int certifiedCount;

        SummaryAccumulator(String cell, double coverage, double quantityScale, String method) {
            this.cell = cell;
            this.coverage = coverage;
            this.quantityScale = quantityScale;
            this.method = method;
        }

        void add(EnrichedRow row) {
            queries++;
            if (row.certified) certifiedCount++;
            conditionalMean += row.mean;
            conditionalSd += row.conditionalSd;
            conditionalQ95 += row.conditionalQ95;
            conditionalCvar95 += row.conditionalCvar95;
            transport += row.transport;
            spot += row.spot;
            penalty += row.penalty;
            selectedCount += row.selectedCount;
            selectedMqc += row.selectedMqc;
            meanDemand += row.meanDemand;
            meanMqcToDemandRatio += row.meanMqcToDemandRatio;
            meanStructuralExcessMqc += row.meanStructuralExcessMqc;
            meanSelectedH += row.meanSelectedH;
            mqcRateExposure += row.mqcRateExposure;
            pooled.addAll(row.oos.total.values());
        }

        MethodSummary finish() {
            double pooledMean = mean(pooled);
            return new MethodSummary(cell, coverage, quantityScale, method,
                    queries, pooled.size(), conditionalMean / queries,
                    conditionalSd / queries, conditionalQ95 / queries,
                    conditionalCvar95 / queries, pooledMean, sd(pooled, pooledMean),
                    quantile(pooled, 0.95), cvar95(pooled), transport / queries,
                    spot / queries, penalty / queries, selectedCount / queries,
                    selectedMqc / queries, meanDemand / queries,
                    meanMqcToDemandRatio / queries,
                    meanStructuralExcessMqc / queries, meanSelectedH / queries,
                    mqcRateExposure / queries, certifiedCount);
        }
    }

    private static final class PairAccumulator {
        final List<Double> differences = new ArrayList<>();
        final List<Double> methodCosts = new ArrayList<>();
        final List<Double> baselineCosts = new ArrayList<>();
        double queryMeanImprovement;
        double querySdImprovement;
        double queryQ95Improvement;
        double queryCvarImprovement;
        double drawRelativeImprovement;
        double selectedDifference;
        double penaltyDifference;
        double spotDifference;
        int queries;
        int wins;

        void addQuery(EnrichedRow method, EnrichedRow baseline) {
            if (!method.oos.total.keySet().equals(baseline.oos.total.keySet())) {
                throw new IllegalStateException("Unpaired draw sets for " + method.cell
                        + " train=" + method.training + " query=" + method.query);
            }
            queries++;
            queryMeanImprovement += improvement(method.mean, baseline.mean);
            querySdImprovement += improvement(method.conditionalSd, baseline.conditionalSd);
            queryQ95Improvement += improvement(method.conditionalQ95, baseline.conditionalQ95);
            queryCvarImprovement += improvement(
                    method.conditionalCvar95, baseline.conditionalCvar95);
            selectedDifference += method.selectedCount - baseline.selectedCount;
            penaltyDifference += method.penalty - baseline.penalty;
            spotDifference += method.spot - baseline.spot;
            for (int draw : method.oos.total.keySet()) {
                double methodCost = method.oos.total.get(draw);
                double baselineCost = baseline.oos.total.get(draw);
                double difference = baselineCost - methodCost;
                methodCosts.add(methodCost);
                baselineCosts.add(baselineCost);
                differences.add(difference);
                drawRelativeImprovement += 100.0 * difference / baselineCost;
                if (methodCost < baselineCost) wins++;
            }
        }

        void write(BufferedWriter out, String cell, String method, String baseline,
                   String comparisonScope, int methodAvailableQueries,
                   int baselineAvailableQueries) throws Exception {
            if (methodCosts.size() != differences.size()
                    || baselineCosts.size() != differences.size()) {
                throw new IllegalStateException("Paired pooled draw count mismatch for "
                        + cell + " " + method + " vs " + baseline);
            }
            if ("RCSAA_METHOD_QUERY_SUBSET_GATE".equals(comparisonScope)
                    && differences.size() != 200 * queries) {
                throw new IllegalStateException("RCSAA gate must have 200 paired draws per query: "
                        + cell + " draws=" + differences.size());
            }
            double differenceMean = mean(differences);
            double methodPooledMean = mean(methodCosts);
            double baselinePooledMean = mean(baselineCosts);
            out.write(String.format(Locale.US,
                    "%s,%s,%s,%s,%d,%d,%d,%d,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,"
                            + "%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f%n",
                    cell, method, baseline, comparisonScope, methodAvailableQueries,
                    baselineAvailableQueries, queries, differences.size(),
                    queryMeanImprovement / queries, querySdImprovement / queries,
                    queryQ95Improvement / queries, queryCvarImprovement / queries,
                    improvement(methodPooledMean, baselinePooledMean),
                    improvement(sd(methodCosts, methodPooledMean),
                            sd(baselineCosts, baselinePooledMean)),
                    improvement(quantile(methodCosts, 0.95),
                            quantile(baselineCosts, 0.95)),
                    improvement(cvar95(methodCosts), cvar95(baselineCosts)),
                    differenceMean, sd(differences, differenceMean),
                    drawRelativeImprovement / differences.size(),
                    (double) wins / differences.size(), selectedDifference / queries,
                    penaltyDifference / queries, spotDifference / queries));
        }
    }
}
