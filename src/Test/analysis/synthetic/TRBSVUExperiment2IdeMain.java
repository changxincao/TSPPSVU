package Test.analysis.synthetic;

import Model.RCSAASolverVariant;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;

/**
 * IDE entry point for Experiment 2. Each replication tunes every robust method once on
 * the common rolling validation windows, then reuses that tuning for all forty queries.
 */
public final class TRBSVUExperiment2IdeMain {
    private enum Phase {
        PRIMARY("primary", List.of("RCSAA", "C-Chi2", "C-W1")),
        MOMENT("moment", List.of("C-MM", "C-PCM"));

        final String directory;
        final List<String> methods;

        Phase(String directory, List<String> methods) {
            this.directory = directory;
            this.methods = methods;
        }
    }
    private static final Path DEFAULT_INPUT = Path.of("analysis", "TRB_reviewer_revision",
            "131_svu_experiment12_random40_I15J50S75_20260926");
    private static final Path DEFAULT_EXPERIMENT1_OUTPUT = Path.of("analysis", "TRB_reviewer_revision",
            "132_svu_experiment1_random40_I15J50S75_20260926");
    private static final Path DEFAULT_OUTPUT = Path.of("analysis", "TRB_reviewer_revision",
            "133_svu_experiment2_random40_I15J50S75_20260926");
    private static final int DEFAULT_PARALLEL_TASKS = 4;
    private static final int DEFAULT_SOLVER_THREADS = 4;
    private static final int DEFAULT_LIMIT_SECONDS = 14_400;
    private static final String DEFAULT_REPLICATIONS = "0-9";

    private TRBSVUExperiment2IdeMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--worker".equals(args[0])) {
            runWorker(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Config config = Config.parse(args);
        List<Task> tasks = buildTasks(config);
        System.out.printf(Locale.ROOT,
                "Experiment 2 plan: input=%s exp1=%s output=%s replications=%d phase=%s parallel=%d solverThreads=%d limitSec=%d%n",
                config.input, config.experiment1Output, config.output, tasks.size(),
                config.phase, config.parallelTasks, config.solverThreads, config.limitSeconds);
        for (Task task : tasks) System.out.println("  " + task.label());
        if (config.dryRun) {
            System.out.println("Dry run only; no solver was started.");
            return;
        }
        Files.createDirectories(config.output);
        if (config.phase.equals("all") || config.phase.equals("primary"))
            runPhase(tasks, config, Phase.PRIMARY);
        if (config.phase.equals("all") || config.phase.equals("moment"))
            runPhase(tasks, config, Phase.MOMENT);
        System.out.println("Experiment 2 scheduling complete: " + config.output.toAbsolutePath());
    }

    private static void runPhase(List<Task> tasks, Config config, Phase phase) throws Exception {
        System.out.println("Starting Experiment 2 phase=" + phase.directory
                + " methods=" + phase.methods);
        try (var pool = Executors.newFixedThreadPool(config.parallelTasks)) {
            var completed = new ExecutorCompletionService<TaskResult>(pool);
            for (Task task : tasks) completed.submit(() -> launch(task, config, phase));
            int failed = 0;
            for (int i = 0; i < tasks.size(); i++) {
                TaskResult result = completed.take().get();
                System.out.println(result.message());
                if (result.exitCode() != 0) failed++;
            }
            if (failed > 0)
                throw new IllegalStateException(failed + " Experiment 2 " + phase.directory
                        + " replication(s) failed; later phases were not started.");
        }
        System.out.println("Completed Experiment 2 phase=" + phase.directory);
    }

    private static TaskResult launch(Task task, Config config, Phase phase) throws Exception {
        Path output = config.output.resolve(phase.directory).resolve(task.name());
        Files.createDirectories(output);
        Path log = output.resolve("task.log");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Djava.library.path=" + System.getProperty("java.library.path"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TRBSVUExperiment2IdeMain.class.getName());
        command.add("--worker");
        command.add(task.input().toAbsolutePath().toString());
        command.add(task.selectedContext().toAbsolutePath().toString());
        command.add(output.toAbsolutePath().toString());
        command.add(Integer.toString(task.replication()));
        command.add(Integer.toString(config.solverThreads));
        command.add(Integer.toString(config.limitSeconds));
        command.add(phase.name());
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        int exit = process.waitFor();
        return new TaskResult(exit, "[" + phase.directory + "/" + task.label()
                + "] exit=" + exit + " log=" + log);
    }

    private static void runWorker(String[] args) throws Exception {
        if (args.length != 7)
            throw new IllegalArgumentException("Worker usage: <replicationInput> <selectedContext.csv> "
                    + "<output> <replication> <solverThreads> <limitSeconds> <phase>");
        Path replicationInput = Path.of(args[0]);
        Path selectedFile = Path.of(args[1]);
        Path output = Path.of(args[2]);
        int replication = Integer.parseInt(args[3]);
        int threads = Integer.parseInt(args[4]);
        int limit = Integer.parseInt(args[5]);
        Phase phase = Phase.valueOf(args[6]);
        Files.createDirectories(output);
        List<TRBSVUExperiment1IdeMain.QueryInput> queries =
                TRBSVUExperiment1IdeMain.loadQueries(replicationInput);
        TRBSVUSyntheticCase reference = TRBSVUSyntheticCaseIO.loadText(queries.get(0).file());
        TRBSVUExperiment1IdeMain.verifyFormalDimensions(reference);
        ContextualChoice selected = TRBSVUExperiment4Main.loadChoice(selectedFile);
        String queryPoolHash = TRBSVUExperiment1IdeMain.queryPoolFingerprint(queries);
        String sourceHash = sourceFingerprint(Path.of("src"));
        String protocol = sha256((TRBSVUFormalProtocol.EXPERIMENT12_VERSION
                + "|experiment=2|phase=" + phase.directory + "|methods=" + phase.methods
                + "|queryPool=" + queryPoolHash + "|selected=" + selected
                + "|lambda=" + Arrays.toString(TRBSVUExperiment2Runner.LAMBDA)
                + "|w1=" + Arrays.toString(TRBSVUExperiment2Runner.W1_RADIUS)
                + "|momentKappa=" + Arrays.toString(TRBSVUExperiment2Runner.MOMENT_KAPPA)
                + "|momentValidationLimit="
                + TRBSVUExperiment2Runner.MOMENT_VALIDATION_LIMIT_SECONDS
                + "|momentQueryLimit=" + TRBSVUExperiment2Runner.MOMENT_QUERY_LIMIT_SECONDS
                + "|threads=" + threads + "|limit=" + limit + "|source=" + sourceHash)
                .getBytes(StandardCharsets.UTF_8));
        Path complete = output.resolve("complete.txt");
        if (Files.isRegularFile(complete)) {
            String previous = Files.readString(complete);
            if (previous.contains("protocol=" + protocol)
                    && previous.contains("allRequestedMethodsCompleted=true")) {
                System.out.println("Already complete with matching protocol: " + output);
                return;
            }
        }
        Files.deleteIfExists(complete);
        Settings settings = new Settings(threads, limit, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        Path python = Path.of(".venv-rsome", "Scripts", "python.exe").toAbsolutePath();
        TRBSVUForestWeights forest = new TRBSVUForestWeights(python.toString(),
                Path.of("analysis", "trb_svu", "rf_leaf_weights.py").toAbsolutePath());
        TRBSVUExperiment1Runner contextual = new TRBSVUExperiment1Runner(
                settings, forest, TRBSVUFormalProtocol.VALIDATION_ORIGINS);
        TRBSVUValidationCheckpoint validationCheckpoint = new TRBSVUValidationCheckpoint(
                output.resolve("validation_checkpoints"), queryPoolHash, protocol);
        Map<String, Double> frozenParameters = null;
        Map<String, Double> frozenValidationCost = null;
        Map<String, Map<Double, Double>> frozenValidationCurve = null;
        List<String> incompleteQueries = new ArrayList<>();
        for (TRBSVUExperiment1IdeMain.QueryInput query : queries) {
            TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(query.file());
            TRBSVUExperiment1IdeMain.verifyFormalDimensions(instance);
            TRBSVUExperiment1IdeMain.verifySharedTrainingCore(reference, instance, query.index());
            String queryHash = sha256(Files.readAllBytes(query.file()));
            String queryProtocol = sha256((protocol + "|queryIndex=" + query.index()
                    + "|queryType=" + query.type() + "|querySha256=" + queryHash)
                    .getBytes(StandardCharsets.UTF_8));
            Path queryOutput = output.resolve("queries")
                    .resolve(String.format(Locale.ROOT, "query_%03d", query.index()));
            TRBSVUExperiment2Runner runner = new TRBSVUExperiment2Runner(settings, contextual,
                    TRBSVUFormalProtocol.VALIDATION_ORIGINS, validationCheckpoint,
                    new TRBSVUFinalCheckpoint(queryOutput.resolve("solve_checkpoints"),
                            queryOutput.resolve("oos_checkpoints"), queryHash, queryProtocol,
                            replication, "2"));
            TRBSVUExperiment2Runner.Result result;
            if (frozenParameters == null) {
                result = runner.run(instance, selected, Set.copyOf(phase.methods));
                frozenParameters = result.selectedParameter();
                frozenValidationCost = result.validationCost();
                frozenValidationCurve = result.validationCurve();
            } else if (frozenParameters.isEmpty()) {
                result = emptyResult(frozenValidationCost, frozenValidationCurve);
            } else {
                result = runner.runWithSelectedParameters(instance, selected, frozenParameters,
                        frozenValidationCost, frozenValidationCurve);
            }
            writeResult(queryOutput, replication, instance, selected, result, query.index() == 0);
            List<String> missingMethods = phase.methods.stream()
                    .filter(method -> !result.decisions().containsKey(method)).toList();
            if (!missingMethods.isEmpty())
                incompleteQueries.add(String.format(Locale.ROOT, "query_%03d:%s",
                        query.index(), String.join(";", missingMethods)));
            Files.writeString(queryOutput.resolve("query_metadata.txt"),
                    "queryIndex=" + query.index() + "\nqueryType=" + query.type()
                            + "\nsourceCandidate=" + query.sourceCandidate()
                            + "\ndemandRatio=" + query.demandRatio()
                            + "\nquerySha256=" + queryHash
                            + "\ncompletedMethods=" + String.join(";", result.decisions().keySet())
                            + "\nmissingMethods=" + String.join(";", missingMethods) + "\n",
                    StandardCharsets.UTF_8);
        }
        Files.writeString(complete, "protocol=" + protocol + "\nqueryCount=" + queries.size()
                + "\nselectedContext=" + selected + "\nsourceSha256=" + sourceHash
                + "\nphase=" + phase.directory
                + "\nrequestedMethods=" + String.join(";", phase.methods)
                + "\nallRequestedMethodsCompleted=" + incompleteQueries.isEmpty()
                + "\nincompleteQueries=" + String.join(",", incompleteQueries) + "\n",
                StandardCharsets.UTF_8);
        if (!incompleteQueries.isEmpty() && phase == Phase.PRIMARY)
            throw new IllegalStateException("Primary robust phase is incomplete: "
                    + String.join(",", incompleteQueries));
        if (!incompleteQueries.isEmpty())
            System.err.println("Experiment 2 phase=" + phase.directory + " finished with missing results: "
                    + String.join(",", incompleteQueries)
                    + ". A later launch will retry them while reusing valid checkpoints.");
    }

    private static TRBSVUExperiment2Runner.Result emptyResult(
            Map<String, Double> validationCost,
            Map<String, Map<Double, Double>> validationCurve) {
        return new TRBSVUExperiment2Runner.Result(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), validationCost, validationCurve, List.of());
    }

    private static void writeResult(Path output, int replication, TRBSVUSyntheticCase instance,
                                    ContextualChoice selected,
                                    TRBSVUExperiment2Runner.Result result,
                                    boolean includeValidation) throws Exception {
        Map<String, String> parameterTypes = new LinkedHashMap<>();
        Map<String, String> families = new LinkedHashMap<>();
        Map<String, Double> baseBandwidth = new LinkedHashMap<>();
        for (String method : result.decisions().keySet()) {
            parameterTypes.put(method, TRBSVUExperiment12Main.parameterType(method));
            families.put(method, TRBSVUExperiment12Main.contextFamily(selected));
            if (Double.isFinite(selected.bandwidth())) baseBandwidth.put(method, selected.bandwidth());
        }
        if (includeValidation) {
            TRBSVUExperiment12Main.writeExperiment(replication, "2", instance,
                    output.resolve("validation"), output.resolve("solve"), output.resolve("oos"),
                    result.decisions(), result.oos(), result.oosDetails(), result.finalWeights(),
                    result.validationCost(), result.validationCurve(), result.validationDetails(),
                    result.selectedParameter(), parameterTypes, families, baseBandwidth,
                    result.effectiveContextBandwidth());
            return;
        }
        Path solve = output.resolve("solve"), oos = output.resolve("oos");
        TRBSVUResultWriter.writeFinalSolves(solve.resolve("experiment2_final_solves.csv"),
                replication, "2", instance.params, result.decisions(), result.validationCost(),
                result.selectedParameter(), parameterTypes, families, baseBandwidth,
                result.effectiveContextBandwidth(), result.finalWeights());
        TRBSVUResultWriter.writeFinalWeights(solve.resolve("experiment2_final_weights.csv"),
                replication, "2", result.finalWeights());
        if (result.finalWeights().keySet().stream().anyMatch(method ->
                method.endsWith("MM") || method.endsWith("PCM")))
            TRBSVUResultWriter.writeMarginalMomentInputs(
                    solve.resolve("experiment2_moment_inputs.csv"), replication, "2",
                    instance.params.J, result.finalWeights(), result.selectedParameter());
        TRBSVUResultWriter.writeOosSummary(oos.resolve("experiment2_summary.csv"), replication,
                "2", result.oos(), result.decisions());
        TRBSVUResultWriter.writeOosDetails(oos.resolve("experiment2_draws.csv"), replication,
                "2", result.oosDetails(), result.decisions());
    }

    private static List<Task> buildTasks(Config config) throws Exception {
        List<Task> tasks = new ArrayList<>();
        for (int replication : parseReplications(config.replications)) {
            String name = String.format(Locale.ROOT, "rep_%03d", replication);
            Path input = config.input.resolve(name);
            TRBSVUExperiment1IdeMain.loadQueries(input);
            Path selected = config.experiment1Output.resolve(name).resolve("CSAA-Tri")
                    .resolve("queries").resolve("query_000").resolve("validation")
                    .resolve("context_candidate.csv");
            if (!Files.isRegularFile(selected))
                throw new IllegalStateException("Missing Experiment 1 validation-selected Triangular context: "
                        + selected);
            ContextualChoice choice = TRBSVUExperiment4Main.loadChoice(selected);
            if (!"TRIANGULAR".equals(choice.family()))
                throw new IllegalStateException("Experiment 2 requires frozen Triangular weights: "
                        + selected + " contains " + choice.family());
            Path inputInstance = input.resolve("instance").resolve("instance.tsv");
            Path experiment1Instance = config.experiment1Output.resolve(name)
                    .resolve("instance").resolve("instance.tsv");
            Path inputQueries = input.resolve("queries").resolve("queries.tsv");
            Path experiment1Queries = config.experiment1Output.resolve(name).resolve("queries.tsv");
            Path experiment1PoolHash = config.experiment1Output.resolve(name)
                    .resolve("query_pool_sha256.txt");
            String currentPoolHash = TRBSVUExperiment1IdeMain.queryPoolFingerprint(
                    TRBSVUExperiment1IdeMain.loadQueries(input));
            if (!Files.isRegularFile(experiment1Instance)
                    || Files.mismatch(inputInstance, experiment1Instance) != -1L
                    || !Files.isRegularFile(experiment1Queries)
                    || Files.mismatch(inputQueries, experiment1Queries) != -1L
                    || !Files.isRegularFile(experiment1PoolHash)
                    || !Files.readString(experiment1PoolHash, StandardCharsets.UTF_8)
                            .trim().equals(currentPoolHash))
                throw new IllegalStateException("Experiment 1 C* belongs to a different frozen "
                        + "instance or query pool: " + name);
            tasks.add(new Task(replication, name, input, selected));
        }
        return tasks;
    }

    private static Set<Integer> parseReplications(String text) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String part : text.split(",")) {
            String token = part.trim();
            int dash = token.indexOf('-');
            if (dash < 0) result.add(Integer.parseInt(token));
            else {
                int first = Integer.parseInt(token.substring(0, dash));
                int last = Integer.parseInt(token.substring(dash + 1));
                if (first > last) throw new IllegalArgumentException("Descending range: " + token);
                for (int value = first; value <= last; value++) result.add(value);
            }
        }
        return result;
    }

    private static String sourceFingerprint(Path root) throws Exception {
        Path absolute = root.toAbsolutePath().normalize();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> sources;
        try (var stream = Files.walk(absolute)) {
            sources = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> absolute.relativize(path).toString()))
                    .toList();
        }
        for (Path source : sources) {
            digest.update(absolute.relativize(source).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(source));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }

    private record Task(int replication, String name, Path input, Path selectedContext) {
        String label() { return name; }
    }
    private record TaskResult(int exitCode, String message) { }

    private record Config(Path input, Path experiment1Output, Path output, int parallelTasks,
                          int solverThreads, int limitSeconds, String replications,
                          String phase, boolean dryRun) {
        static Config parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean dry = false;
            for (String arg : args) {
                if ("--dry-run".equals(arg)) { dry = true; continue; }
                if (!arg.startsWith("--") || !arg.contains("="))
                    throw new IllegalArgumentException("Arguments must use --key=value: " + arg);
                int equals = arg.indexOf('=');
                values.put(arg.substring(2, equals), arg.substring(equals + 1));
            }
            Set<String> known = Set.of("input", "experiment1-output", "output", "parallel",
                    "solver-threads", "limit-seconds", "replications", "phase");
            if (!known.containsAll(values.keySet()))
                throw new IllegalArgumentException("Unknown argument(s): " + values.keySet().stream()
                        .filter(key -> !known.contains(key)).toList());
            int parallel = integer(values, "parallel", DEFAULT_PARALLEL_TASKS);
            int threads = integer(values, "solver-threads", DEFAULT_SOLVER_THREADS);
            int limit = integer(values, "limit-seconds", DEFAULT_LIMIT_SECONDS);
            if (parallel < 1 || threads < 1 || limit < 1)
                throw new IllegalArgumentException("Parallelism, threads and time limit must be positive.");
            String phase = values.getOrDefault("phase", "all").toLowerCase(Locale.ROOT);
            if (!Set.of("all", "primary", "moment").contains(phase))
                throw new IllegalArgumentException("Phase must be all, primary or moment: " + phase);
            return new Config(Path.of(values.getOrDefault("input", DEFAULT_INPUT.toString())),
                    Path.of(values.getOrDefault("experiment1-output",
                            DEFAULT_EXPERIMENT1_OUTPUT.toString())),
                    Path.of(values.getOrDefault("output", DEFAULT_OUTPUT.toString())),
                    parallel, threads, limit,
                    values.getOrDefault("replications", DEFAULT_REPLICATIONS), phase, dry);
        }

        private static int integer(Map<String, String> values, String key, int fallback) {
            return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
        }
    }
}
