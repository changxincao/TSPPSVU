package Test.analysis.synthetic;

import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.WeightResult;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Solves added ordinary queries with Experiment 1 parameters frozen before OOS evaluation. */
public final class TRBSVURandomQueryExtensionSolveMain {
    private static final String EXPERIMENT = "1-RANDOM-EXTENSION";

    private TRBSVURandomQueryExtensionSolveMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 10) throw new IllegalArgumentException(
                "Usage: <extension-rep-input> <old-method-output> <choice-file-or-NONE> "
                        + "<output> <replication> <method> <threads> <limit-seconds> "
                        + "<python> <rf-script>");
        Path input = Path.of(args[0]);
        Path oldOutput = Path.of(args[1]);
        String choiceArg = args[2];
        Path output = Path.of(args[3]);
        int replication = Integer.parseInt(args[4]);
        String method = canonicalMethod(args[5]);
        int threads = Integer.parseInt(args[6]);
        int limitSeconds = Integer.parseInt(args[7]);
        Path python = Path.of(args[8]);
        Path rfScript = Path.of(args[9]);
        if (threads < 1 || limitSeconds < 1)
            throw new IllegalArgumentException("Invalid solve settings.");

        List<QueryInput> queries = loadQueries(input);
        TRBSVUSyntheticCase reference = TRBSVUSyntheticCaseIO.loadText(queries.get(0).file());
        TRBSVUExperiment1IdeMain.verifyFormalDimensions(reference);
        ContextualChoice choice = needsChoice(method)
                ? TRBSVUExperiment4Main.loadChoice(Path.of(choiceArg)) : null;
        Path legacyChoiceFile = oldOutput.resolve("queries").resolve("query_000")
                .resolve("validation").resolve("context_candidate.csv");
        if (method.startsWith("CSAA-") && Files.isRegularFile(legacyChoiceFile)) {
            choice = mergeKernelFallbackOrder(choice,
                    TRBSVUExperiment4Main.loadChoice(legacyChoiceFile));
        }
        verifyChoice(method, choice);
        double validationCost = choice == null
                ? loadValidationCost(oldOutput, method) : choice.validationCost();
        Settings settings = new Settings(threads, limitSeconds, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        TRBSVUForestWeights forest = new TRBSVUForestWeights(python.toString(), rfScript);
        TRBSVUExperiment1Runner contextual = new TRBSVUExperiment1Runner(settings, forest,
                TRBSVUFormalProtocol.VALIDATION_ORIGINS);
        String poolHash = queryPoolFingerprint(queries);
        String choiceHash = choice == null ? "NONE" : sha256(choice.toString()
                .getBytes(StandardCharsets.UTF_8));
        String protocol = sha256(("TRBSVU_RANDOM_QUERY_EXTENSION_SOLVE_V2|method=" + method
                + "|threads=" + threads + "|limit=" + limitSeconds + "|pool=" + poolHash
                + "|choice=" + choiceHash).getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(output);
        Path complete = output.resolve("complete.txt");
        if (Files.isRegularFile(complete)
                && Files.readString(complete).contains("protocol=" + protocol)) {
            System.out.println("Already complete with matching protocol: " + output);
            return;
        }
        Files.deleteIfExists(complete);
        if (choice != null) {
            Files.deleteIfExists(output.resolve("frozen_context_choice.csv"));
            TRBSVUResultWriter.writeContextualChoice(output.resolve("frozen_context_choice.csv"),
                    replication, choice);
        }

        for (QueryInput query : queries) {
            TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(query.file());
            TRBSVUExperiment1IdeMain.verifyFormalDimensions(instance);
            TRBSVUExperiment1IdeMain.verifySharedTrainingCore(reference, instance, query.index());
            WeightResult weights = weights(method, instance, contextual, choice);
            double parameter = parameter(method, choice);
            String queryHash = sha256(Files.readAllBytes(query.file()));
            Path queryOutput = output.resolve("queries")
                    .resolve(String.format(Locale.ROOT, "query_%03d", query.index()));
            TRBSVUFinalCheckpoint checkpoint = new TRBSVUFinalCheckpoint(
                    queryOutput.resolve("solve_checkpoints"),
                    queryOutput.resolve("oos_checkpoints"), queryHash, protocol,
                    replication, EXPERIMENT);
            Solution solution = checkpoint.load(method, parameter).orElse(null);
            if (solution == null) {
                long started = System.nanoTime();
                solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                        weights.weights(), instance.testContext, Method.NOMINAL, 0.0, settings);
                solution.solveTimeSec = (System.nanoTime() - started) / 1.0e9;
                requireIncumbent(solution, instance.params.I, method, query.index());
                checkpoint.save(method, parameter, solution);
            }
            requireIncumbent(solution, instance.params.I, method, query.index());
            TRBSVUSolveMethods.OosEvaluation evaluation = TRBSVUSolveMethods.evaluateDetailed(
                    instance.params, solution.y, instance.oos);
            checkpoint.saveOos(method, evaluation.summary(), evaluation.draws(), solution);
            writeQuery(queryOutput, replication, instance, method, choice, validationCost,
                    weights, solution, evaluation, query);
        }
        Files.writeString(complete, "protocol=" + protocol + "\nmethod=" + method
                + "\nqueryCount=" + queries.size() + "\nqueryPoolSha256=" + poolHash
                + "\nchoiceSha256=" + choiceHash + "\n", StandardCharsets.UTF_8);
        System.out.printf(Locale.ROOT, "RANDOM_EXTENSION_COMPLETE rep=%d method=%s queries=%d%n",
                replication, method, queries.size());
    }

    private static WeightResult weights(String method, TRBSVUSyntheticCase instance,
                                        TRBSVUExperiment1Runner contextual,
                                        ContextualChoice choice) throws Exception {
        return switch (method) {
            case "D" -> new WeightResult(TRBSVUScenarioWeights.arithmeticMean(instance.history),
                    Double.NaN);
            case "SAA-All" -> new WeightResult(TRBSVUScenarioWeights.equal(instance.history),
                    Double.NaN);
            default -> contextual.contextualWeightResult(instance, instance.history,
                    instance.testContext, choice);
        };
    }

    private static void writeQuery(Path output, int replication, TRBSVUSyntheticCase instance,
                                   String method, ContextualChoice choice, double validationCost,
                                   WeightResult weights, Solution solution,
                                   TRBSVUSolveMethods.OosEvaluation evaluation,
                                   QueryInput query) throws Exception {
        double parameter = parameter(method, choice);
        String parameterType = method.startsWith("CSAA-") ? "BANDWIDTH"
                : "RF-CSAA".equals(method) ? "MIN_SAMPLES_LEAF" : "NONE";
        String family = choice == null ? "UNCONDITIONAL" : choice.family();
        Map<String, Solution> decisions = Map.of(method, solution);
        Map<String, List<Sample>> finalWeights = Map.of(method, weights.weights());
        Map<String, Double> baseBandwidth = method.startsWith("CSAA-")
                ? Map.of(method, choice.bandwidth()) : Map.of();
        Map<String, Double> effectiveBandwidth = method.startsWith("CSAA-")
                ? Map.of(method, weights.effectiveBandwidth()) : Map.of();
        for (Path file : List.of(
                output.resolve("solve").resolve("final_solve.csv"),
                output.resolve("solve").resolve("final_weights.csv"),
                output.resolve("oos").resolve("summary.csv"),
                output.resolve("oos").resolve("draws.csv"),
                output.resolve("query_metadata.txt")))
            Files.deleteIfExists(file);
        TRBSVUResultWriter.writeFinalSolves(output.resolve("solve").resolve("final_solve.csv"),
                replication, EXPERIMENT, instance.params, decisions,
                Map.of(method, validationCost), Map.of(method, parameter),
                Map.of(method, parameterType), Map.of(method, family),
                baseBandwidth, effectiveBandwidth, finalWeights);
        TRBSVUResultWriter.writeFinalWeights(output.resolve("solve").resolve("final_weights.csv"),
                replication, EXPERIMENT, finalWeights);
        TRBSVUResultWriter.writeOosSummary(output.resolve("oos").resolve("summary.csv"),
                replication, EXPERIMENT, Map.of(method, evaluation.summary()), decisions);
        TRBSVUResultWriter.writeOosDetails(output.resolve("oos").resolve("draws.csv"),
                replication, EXPERIMENT, Map.of(method, evaluation.draws()), decisions);
        Files.writeString(output.resolve("query_metadata.txt"), "queryIndex=" + query.index()
                + "\nqueryType=RANDOM\nsourceCandidate=" + query.sourceCandidate()
                + "\ndemandRatio=" + query.demandRatio() + "\n",
                StandardCharsets.UTF_8);
    }

    private static void verifyChoice(String method, ContextualChoice choice) {
        if (!needsChoice(method)) return;
        if (choice == null) throw new IllegalStateException("Missing frozen choice for " + method);
        if (method.startsWith("CSAA-")) {
            if (!kernel(method).name().equals(choice.family()))
                throw new IllegalStateException("Wrong kernel choice for " + method + ": " + choice);
        } else if (!"RF".equals(choice.family()) || choice.rfMinLeaf() < 1) {
            throw new IllegalStateException("Wrong RF choice: " + choice);
        }
    }

    private static ContextualChoice mergeKernelFallbackOrder(ContextualChoice selected,
                                                              ContextualChoice legacy) {
        if (selected == null || legacy == null || !selected.family().equals(legacy.family()))
            throw new IllegalStateException("Cannot merge incompatible kernel choices.");
        List<Double> merged = new ArrayList<>();
        for (double candidate : selected.bandwidthOrder())
            if (!merged.contains(candidate)) merged.add(candidate);
        for (double candidate : legacy.bandwidthOrder())
            if (!merged.contains(candidate)) merged.add(candidate);
        if (!merged.contains(selected.bandwidth())) merged.add(0, selected.bandwidth());
        return new ContextualChoice(selected.family(), selected.bandwidth(),
                selected.validationCost(), selected.validationSd(), merged, 0);
    }

    private static double parameter(String method, ContextualChoice choice) {
        if (method.startsWith("CSAA-")) return choice.bandwidth();
        if ("RF-CSAA".equals(method)) return choice.rfMinLeaf();
        return Double.NaN;
    }

    private static double loadValidationCost(Path oldOutput, String method) throws Exception {
        Path file = oldOutput.resolve("queries").resolve("query_000")
                .resolve("validation").resolve("summary.csv");
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int row = 1; row < lines.size(); row++) {
            String[] fields = lines.get(row).split(",", -1);
            if (fields.length >= 6 && method.equals(fields[2])
                    && Boolean.parseBoolean(fields[fields.length - 1]))
                return Double.parseDouble(fields[4]);
        }
        throw new IllegalStateException("Missing selected validation cost for " + method + ": " + file);
    }

    private static List<QueryInput> loadQueries(Path input) throws Exception {
        Path manifest = input.resolve("queries").resolve("queries.tsv");
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.size() != 21 || !lines.get(0).equals(
                "query_index\tquery_type\tsource_candidate\tdemand_ratio\tinstance_file"))
            throw new IllegalStateException("Invalid random-query extension manifest: " + manifest);
        List<QueryInput> result = new ArrayList<>(20);
        for (int row = 1; row < lines.size(); row++) {
            String[] fields = lines.get(row).split("\t", -1);
            int index = Integer.parseInt(fields[0]);
            if (fields.length != 5 || index != 19 + row || !"RANDOM".equals(fields[1]))
                throw new IllegalStateException("Invalid random-query extension row: " + lines.get(row));
            Path file = manifest.getParent().resolve(fields[4]).normalize();
            if (!Files.isRegularFile(file)) throw new IllegalStateException("Missing query input: " + file);
            result.add(new QueryInput(index, Integer.parseInt(fields[2]),
                    Double.parseDouble(fields[3]), file));
        }
        return List.copyOf(result);
    }

    private static String queryPoolFingerprint(List<QueryInput> queries) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (QueryInput query : queries) {
            digest.update(Files.readAllBytes(query.file()));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean needsChoice(String method) {
        return method.startsWith("CSAA-") || "RF-CSAA".equals(method);
    }

    private static String canonicalMethod(String value) {
        for (String method : List.of("D", "SAA-All", "CSAA-Exp", "CSAA-Gau",
                "CSAA-Epa", "CSAA-Tri", "RF-CSAA"))
            if (method.equalsIgnoreCase(value)) return method;
        throw new IllegalArgumentException("Unknown method: " + value);
    }

    private static Kernel kernel(String method) {
        return switch (method) {
            case "CSAA-Exp" -> Kernel.EXPONENTIAL;
            case "CSAA-Gau" -> Kernel.GAUSSIAN;
            case "CSAA-Epa" -> Kernel.EPANECHNIKOV;
            case "CSAA-Tri" -> Kernel.TRIANGULAR;
            default -> throw new IllegalArgumentException("Not a kernel method: " + method);
        };
    }

    private static void requireIncumbent(Solution solution, int carriers,
                                         String method, int query) {
        if (solution == null || solution.y == null || solution.y.length != carriers
                || !Double.isFinite(solution.objValue))
            throw new IllegalStateException("No usable incumbent for " + method + " query=" + query);
        for (double value : solution.y)
            if (!Double.isFinite(value) || Math.abs(value - Math.rint(value)) > 1e-5)
                throw new IllegalStateException("Invalid first-stage decision for " + method
                        + " query=" + query + ": " + value);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record QueryInput(int index, int sourceCandidate, double demandRatio, Path file) { }
}
