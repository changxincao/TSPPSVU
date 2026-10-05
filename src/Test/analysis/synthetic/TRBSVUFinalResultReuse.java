package Test.analysis.synthetic;

import Basic.Sample;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.OosDraw;
import Test.analysis.synthetic.TRBSVUSolveMethods.OosEvaluation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in reuse after the grid-completion driver has verified the old source/settings protocol. */
final class TRBSVUFinalResultReuse {
    record Reused(Solution solution, OosEvaluation evaluation) { }
    private static Path indexedInput;
    private static final Map<String, TRBSVUExperiment1IdeMain.QueryInput> queries = new LinkedHashMap<>();

    private TRBSVUFinalResultReuse() { }

    static Reused load(TRBSVUSyntheticCase instance, String method, double parameter,
                       List<Sample> weights) throws Exception {
        String configured = System.getProperty("trb.svu.finalReuseInput");
        if (configured == null) return null;
        Path input = Path.of(configured), baseline = Path.of(requiredProperty("finalReuseBaseline"));
        String protocol = requiredProperty("finalReuseProtocol");
        if (!input.equals(indexedInput)) {
            queries.clear();
            for (var query : TRBSVUExperiment1IdeMain.loadQueries(input)) {
                var frozen = TRBSVUSyntheticCaseIO.loadText(query.file());
                if (queries.put(contextKey(frozen), query) != null)
                    throw new IllegalStateException("Duplicate query context in reuse input: " + input);
            }
            indexedInput = input;
        }
        var query = queries.get(contextKey(instance));
        if (query == null) throw new IllegalStateException("Reuse query is not in the frozen input pool");
        Path source = baseline.resolve(String.format(java.util.Locale.ROOT, "queries/query_%03d", query.index()));
        String experiment = method.equals("C-Chi2") || method.equals("RCSAA") ? "2" : "1";
        String safe = method.replaceAll("[^A-Za-z0-9_-]", "_");
        Path checkpoint = source.resolve("solve_checkpoints/" + safe + ".checkpoint");
        if (!Files.isRegularFile(checkpoint)) return null;
        Map<String, String> saved = metadata(checkpoint);
        if (Double.doubleToLongBits(parameter) != Double.doubleToLongBits(
                Double.parseDouble(saved.get("selectedParameter")))) return null;
        Path weightFile = source.resolve(experiment.equals("1")
                ? "solve/final_weights.csv" : "solve/experiment2_final_weights.csv");
        if (!sameWeights(table(weightFile), method, weights)) return null;

        var frozen = TRBSVUSyntheticCaseIO.loadText(query.file());
        TRBSVUExperiment1IdeMain.verifySharedTrainingCore(frozen, instance, query.index());
        if (frozen.oos.size() != instance.oos.size()) throw new IllegalStateException("Reuse OOS size mismatch");
        for (int s = 0; s < frozen.oos.size(); s++) {
            if (frozen.oos.get(s).id != instance.oos.get(s).id
                    || !Arrays.equals(frozen.oos.get(s).demand(), instance.oos.get(s).demand()))
                throw new IllegalStateException("Reuse OOS demand mismatch at draw " + s);
        }
        String inputHash = hash(query.file());
        if (!inputHash.equals(metadata(source.resolve("query_metadata.txt")).get("querySha256")))
            throw new IllegalStateException("Reuse query input hash mismatch: " + source);
        String queryProtocol = hash((protocol + "|queryIndex=" + query.index()
                + "|queryType=" + query.type() + "|querySha256=" + inputHash).getBytes(StandardCharsets.UTF_8));
        int replication = Integer.parseInt(saved.get("replication"));
        var old = new TRBSVUFinalCheckpoint(source.resolve("solve_checkpoints"),
                source.resolve("oos_checkpoints"), inputHash, queryProtocol, replication, experiment);
        Solution solution = old.load(method, parameter).orElseThrow(
                () -> new IllegalStateException("Reuse final checkpoint protocol mismatch: " + source));
        if (solution.y == null || solution.y.length != instance.params.I || !Double.isFinite(solution.objValue))
            throw new IllegalStateException("Reuse final checkpoint has no usable decision: " + source);
        List<Map<String, String>> solveRows = table(source.resolve(experiment.equals("1")
                ? "solve/final_solve.csv" : "solve/experiment2_final_solves.csv"));
        if (solveRows.size() != 1) throw new IllegalStateException("Invalid reuse final-solve report: " + source);
        var solveRow = solveRows.get(0);
        if (!method.equals(solveRow.get("method")) || !solution.solverStatus.equals(solveRow.get("status"))
                || !Boolean.toString(solution.certifiedOptimal).equals(solveRow.get("certified_optimal")))
            throw new IllegalStateException("Reuse final-solve report metadata mismatch: " + source);
        close(parameter, number(solveRow, "selected_parameter"), "selected parameter");
        close(solution.objValue, number(solveRow, "training_objective"), "training objective");
        String[] decision = solveRow.get("decision_vector").split(";", -1);
        if (decision.length != solution.y.length) throw new IllegalStateException("Reuse reported decision dimension mismatch");
        for (int i = 0; i < decision.length; i++) {
            if (!Double.isFinite(solution.y[i]) || Math.abs(solution.y[i] - Math.rint(solution.y[i])) > 1e-5
                    || Integer.parseInt(decision[i]) != (solution.y[i] > 0.5 ? 1 : 0))
                throw new IllegalStateException("Reuse reported decision mismatch at carrier " + i);
        }
        Path summaryFile = source.resolve("oos_checkpoints/" + safe + "_summary.csv");
        Path drawsFile = source.resolve("oos_checkpoints/" + safe + "_draws.csv");
        if (!Files.isRegularFile(summaryFile) || !Files.isRegularFile(drawsFile)) return null;
        List<Map<String, String>> rows = table(drawsFile);
        if (rows.size() != instance.oos.size()) throw new IllegalStateException("Incomplete reuse OOS: " + source);
        List<OosDraw> draws = new ArrayList<>(rows.size());
        for (int s = 0; s < rows.size(); s++) {
            Map<String, String> row = rows.get(s);
            verifySolveMetadata(row, replication, experiment, method, solution);
            if (integer(row, "draw_index") != s || integer(row, "sample_id") != instance.oos.get(s).id)
                throw new IllegalStateException("Reuse OOS id mismatch at draw " + s);
            double demand = 0;
            for (double d : instance.oos.get(s).demand()) demand += d;
            close(number(row, "total_demand"), demand, "OOS total demand");
            OosDraw draw = new OosDraw(s, instance.oos.get(s).id, number(row, "total_demand"),
                    number(row, "total_cost"), number(row, "transport_cost"), number(row, "spot_cost"),
                    number(row, "mqc_penalty"), number(row, "contracted_quantity"), number(row, "spot_quantity"),
                    number(row, "mqc_shortfall_quantity"), number(row, "spot_share"),
                    number(row, "capacity_utilization"), number(row, "mean_lane_capacity_utilization"));
            close(draw.totalCost(), draw.transportCost() + draw.spotCost() + draw.mqcPenalty(), "cost decomposition");
            draws.add(draw);
        }
        List<Map<String, String>> summaries = table(summaryFile);
        if (summaries.size() != 1) throw new IllegalStateException("Invalid reuse OOS summary: " + source);
        var row = summaries.get(0);
        verifySolveMetadata(row, replication, experiment, method, solution);
        Oos summary = new Oos(number(row, "mean"), number(row, "sd"), number(row, "q95"),
                number(row, "cvar95"), number(row, "maximum"), number(row, "mean_transport_cost"),
                number(row, "mean_spot_cost"), number(row, "mean_mqc_penalty"),
                number(row, "mean_contracted_quantity"), number(row, "mean_spot_quantity"),
                number(row, "mean_mqc_shortfall_quantity"), number(row, "spot_share_total"),
                number(row, "mean_draw_spot_share"), number(row, "capacity_utilization_total"),
                number(row, "mean_lane_capacity_utilization"));
        var stats = TRBSVUStatistics.summarize(draws.stream().mapToDouble(OosDraw::totalCost).toArray());
        close(summary.mean(), stats.mean(), "Mean");
        close(summary.standardDeviation(), stats.sampleStandardDeviation(), "SD");
        close(summary.q95(), stats.q95(), "Q95");
        close(summary.cvar95(), stats.cvar95(), "CVaR95");
        close(summary.maximum(), stats.maximum(), "Max");
        close(summary.meanTransportCost(), mean(draws, OosDraw::transportCost), "transport cost");
        close(summary.meanSpotCost(), mean(draws, OosDraw::spotCost), "spot cost");
        close(summary.meanPenalty(), mean(draws, OosDraw::mqcPenalty), "MQC penalty");
        close(summary.meanContractedQuantity(), mean(draws, OosDraw::contractedQuantity), "contract quantity");
        close(summary.meanSpotQuantity(), mean(draws, OosDraw::spotQuantity), "spot quantity");
        close(summary.meanMqcShortfallQuantity(), mean(draws, OosDraw::mqcShortfallQuantity), "MQC shortfall");
        double totalDemand = draws.stream().mapToDouble(OosDraw::totalDemand).sum();
        close(summary.spotShare(), totalDemand > 0 ? draws.stream().mapToDouble(OosDraw::spotQuantity).sum() / totalDemand : 0, "spot share");
        close(summary.meanDrawSpotShare(), mean(draws, OosDraw::spotShare), "draw spot share");
        close(summary.capacityUtilization(), mean(draws, OosDraw::capacityUtilization), "capacity utilization");
        close(summary.meanLaneCapacityUtilization(), mean(draws, OosDraw::meanLaneCapacityUtilization), "lane utilization");
        Path target = Path.of(requiredProperty("finalReuseTarget"))
                .resolve(String.format(java.util.Locale.ROOT, "queries/query_%03d/final_reuse.txt", query.index()));
        Files.createDirectories(target.getParent());
        TRBSVUCompletionMarker.writeAtomically(target, "source=" + source.toAbsolutePath()
                + "\nmethod=" + method + "\nparameter=" + parameter + "\ninputSha256=" + inputHash
                + "\noldProtocol=" + protocol + "\nweightsSha256=" + hash(weightFile)
                + "\ncheckpointSha256=" + hash(checkpoint)
                + "\nsummarySha256=" + hash(summaryFile) + "\ndrawsSha256=" + hash(drawsFile)
                + "\noptimizerCalled=false\noosRecourseCalled=false\n");
        System.out.println("FINAL_REUSED method=" + method + " query=" + query.index() + " parameter=" + parameter);
        return new Reused(solution, new OosEvaluation(summary, List.copyOf(draws)));
    }

    private static boolean sameWeights(List<Map<String, String>> rows, String method, List<Sample> weights) {
        if (rows.size() != weights.size()) return false;
        for (int s = 0; s < weights.size(); s++) {
            var row = rows.get(s);
            Sample weight = weights.get(s);
            if (!method.equals(row.get("method")) || integer(row, "row_index") != s
                    || integer(row, "sample_id") != weight.id || integer(row, "period_index") != weight.period.tIndex
                    || Double.doubleToLongBits(number(row, "input_weight")) != Double.doubleToLongBits(weight.weight))
                return false;
        }
        return true;
    }

    private static void verifySolveMetadata(Map<String, String> row, int rep, String experiment,
                                           String method, Solution solution) {
        if (integer(row, "replication") != rep || !experiment.equals(row.get("experiment"))
                || !method.equals(row.get("method")) || !solution.solverStatus.equals(row.get("solve_status"))
                || !Boolean.toString(solution.certifiedOptimal).equals(row.get("certified_optimal")))
            throw new IllegalStateException("Reuse OOS solve metadata mismatch");
        double gap = Double.parseDouble(row.get("solve_gap"));
        if (Double.doubleToLongBits(gap) != Double.doubleToLongBits(solution.relativeGap))
            throw new IllegalStateException("Reuse OOS gap mismatch");
        if (row.containsKey("solve_best_bound") && Double.doubleToLongBits(Double.parseDouble(row.get("solve_best_bound")))
                != Double.doubleToLongBits(solution.bestBound))
            throw new IllegalStateException("Reuse OOS bound mismatch");
    }

    private static double mean(List<OosDraw> draws, java.util.function.ToDoubleFunction<OosDraw> field) {
        return draws.stream().mapToDouble(field).average().orElseThrow();
    }
    private static void close(double a, double b, String label) {
        // Only CSV round-trip/summation tolerance; this is not an optimality acceptance gate.
        if (!Double.isFinite(a) || !Double.isFinite(b) || Math.abs(a - b) > 1e-9 * Math.max(1, Math.abs(b)))
            throw new IllegalStateException("Reuse output mismatch: " + label + " " + a + " != " + b);
    }
    private static String contextKey(TRBSVUSyntheticCase instance) { return Arrays.toString(instance.testContext.values()); }
    private static String requiredProperty(String name) {
        String value = System.getProperty("trb.svu." + name);
        if (value == null) throw new IllegalStateException("Missing reuse property: " + name);
        return value;
    }
    private static int integer(Map<String, String> row, String key) { return Integer.parseInt(row.get(key)); }
    private static double number(Map<String, String> row, String key) {
        double value = Double.parseDouble(row.get(key));
        if (!Double.isFinite(value)) throw new IllegalStateException("Non-finite cached OOS/weight field: " + key);
        return value;
    }
    private static Map<String, String> metadata(Path file) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            int split = line.indexOf('=');
            if (split <= 0 || result.put(line.substring(0, split), line.substring(split + 1)) != null)
                throw new IllegalStateException("Invalid reuse metadata: " + file);
        }
        return result;
    }
    static List<Map<String, String>> table(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty()) throw new IllegalStateException("Empty reuse CSV: " + file);
        List<String> header = fields(lines.get(0));
        List<Map<String, String>> result = new ArrayList<>();
        for (int r = 1; r < lines.size(); r++) {
            List<String> values = fields(lines.get(r));
            if (values.size() != header.size()) throw new IllegalStateException("Invalid reuse CSV row: " + file);
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) row.put(header.get(c), values.get(c));
            result.add(row);
        }
        return result;
    }
    private static List<String> fields(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int c = 0; c < line.length(); c++) {
            char ch = line.charAt(c);
            if (ch == '"') {
                if (quoted && c + 1 < line.length() && line.charAt(c + 1) == '"') { value.append('"'); c++; }
                else quoted = !quoted;
            } else if (ch == ',' && !quoted) { values.add(value.toString()); value.setLength(0); }
            else value.append(ch);
        }
        if (quoted) throw new IllegalStateException("Unclosed CSV quote");
        values.add(value.toString());
        return values;
    }
    private static String hash(Path file) throws Exception { return hash(Files.readAllBytes(file)); }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
