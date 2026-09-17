package Test.analysis.synthetic;

import Model.RCSAASolverVariant;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.TimeUnit;

/**
 * IDE entry point for Experiment 1. The coordinator runs one isolated JVM for every
 * (replication, method) task, so solver logs and checkpoints never collide.
 */
public final class TRBSVUExperiment1IdeMain {
    // Edit these defaults directly, or override them with --key=value program arguments in Eclipse.
    private static final Path DEFAULT_INPUT = Path.of("analysis", "TRB_reviewer_revision",
            "100_svu_experiment12_baseline_cases_20260915");
    private static final Path DEFAULT_OUTPUT = Path.of("analysis", "TRB_reviewer_revision",
            "120_svu_experiment1_results_20260916");
    private static final int DEFAULT_PARALLEL_TASKS = 6;
    private static final int DEFAULT_SOLVER_THREADS = 4;
    private static final int DEFAULT_LIMIT_SECONDS = 14_400;
    private static final int DEFAULT_VALIDATION_ORIGINS = 30;
    private static final String DEFAULT_REPLICATIONS = "0-19";
    private static final String DEFAULT_METHODS = "ALL";
    private static final List<String> ALL_METHODS = List.of("D", "SAA-All", "Tuned-SAA",
            "CSAA-Exp", "CSAA-Gau", "CSAA-Epa", "CSAA-Tri", "RF-CSAA");
    private static final List<String> CONTEXTUAL_METHODS = List.of(
            "CSAA-Exp", "CSAA-Gau", "CSAA-Epa", "CSAA-Tri", "RF-CSAA");

    private TRBSVUExperiment1IdeMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--worker".equals(args[0])) {
            runWorker(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Config config = Config.parse(args);
        List<Task> tasks = buildTasks(config);
        System.out.printf(Locale.ROOT,
                "Experiment 1 plan: input=%s output=%s tasks=%d parallel=%d solverThreads=%d limitSec=%d origins=%d%n",
                config.input, config.output, tasks.size(), config.parallelTasks,
                config.solverThreads, config.limitSeconds, config.validationOrigins);
        for (Task task : tasks) System.out.println("  " + task.label());
        if (config.dryRun) {
            System.out.println("Dry run only; no solver was started.");
            return;
        }
        Files.createDirectories(config.output);
        try (var pool = Executors.newFixedThreadPool(config.parallelTasks)) {
            var completed = new ExecutorCompletionService<TaskResult>(pool);
            for (Task task : tasks) completed.submit(() -> launch(task, config));
            int failed = 0;
            for (int i = 0; i < tasks.size(); i++) {
                TaskResult result = completed.take().get();
                System.out.println(result.message);
                if (result.exitCode != 0) failed++;
            }
            if (failed > 0)
                throw new IllegalStateException(failed + " Experiment 1 task(s) failed; inspect task.log files.");
        }
        if (config.methods.containsAll(CONTEXTUAL_METHODS)) {
            aggregateContextualChoices(config.input, config.output,
                    parseReplications(config.replications));
        } else {
            System.out.println("Replication-level C* not written because the requested methods do not "
                    + "contain all five contextual families.");
        }
        System.out.println("Experiment 1 scheduling complete: " + config.output.toAbsolutePath());
    }

    private static TaskResult launch(Task task, Config config) throws Exception {
        Path taskDirectory = config.output.resolve(task.replicationName).resolve(safe(task.method));
        Files.createDirectories(taskDirectory);
        Path log = taskDirectory.resolve("task.log");
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Djava.library.path=" + System.getProperty("java.library.path"));
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(TRBSVUExperiment1IdeMain.class.getName());
        command.add("--worker");
        command.add(task.instance.toAbsolutePath().toString());
        command.add(taskDirectory.toAbsolutePath().toString());
        command.add(Integer.toString(task.replication));
        command.add(task.method);
        command.add(Integer.toString(config.validationOrigins));
        command.add(Integer.toString(config.solverThreads));
        command.add(Integer.toString(config.limitSeconds));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        int exit = process.waitFor();
        return new TaskResult(exit, String.format(Locale.ROOT,
                "[%s] exit=%d log=%s", task.label(), exit, log.toAbsolutePath()));
    }

    private static void runWorker(String[] args) throws Exception {
        if (args.length != 7)
            throw new IllegalArgumentException("Worker usage: <instance.tsv> <taskOutput> <replication> "
                    + "<method> <validationOrigins> <solverThreads> <limitSeconds>");
        Path instanceFile = Path.of(args[0]);
        Path output = Path.of(args[1]);
        int replication = Integer.parseInt(args[2]);
        String method = canonicalMethod(args[3]);
        int origins = Integer.parseInt(args[4]);
        int threads = Integer.parseInt(args[5]);
        int limit = Integer.parseInt(args[6]);
        Files.createDirectories(output);
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(instanceFile);
        String instanceHash = sha256(Files.readAllBytes(instanceFile));
        Path rfScript = Path.of("analysis", "trb_svu", "rf_leaf_weights.py").toAbsolutePath();
        Path python = Path.of(".venv-rsome", "Scripts", "python.exe").toAbsolutePath();
        String sourceHash = sourceFingerprint(Path.of("src"));
        String rfScriptHash = sha256(Files.readAllBytes(rfScript));
        String pythonEnvironment = "RF-CSAA".equals(method) ? pythonEnvironment(python) : "NOT_USED";
        String protocol = sha256(("TRBSVU_EXP1_METHOD_V1|method=" + method + "|origins=" + origins
                + "|threads=" + threads + "|limit=" + limit + "|instance=" + instanceHash
                + "|retention=" + Arrays.toString(TRBSVUExperiment1Runner.RETENTION)
                + "|bandwidth=" + Arrays.toString(TRBSVUExperiment1Runner.BANDWIDTH)
                + "|source=" + sourceHash + "|rfScript=" + rfScriptHash
                + "|pythonEnvironment=" + pythonEnvironment)
                .getBytes(StandardCharsets.UTF_8));
        Path complete = output.resolve("complete.txt");
        if (Files.exists(complete) && Files.readString(complete).contains("protocol=" + protocol)) {
            System.out.println("Already complete with matching protocol: " + output);
            return;
        }
        Files.deleteIfExists(complete);
        Settings settings = new Settings(threads, limit, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        TRBSVUForestWeights forest = new TRBSVUForestWeights(python.toString(), rfScript);
        TRBSVUExperiment1Runner runner = new TRBSVUExperiment1Runner(settings, forest, origins,
                new TRBSVUValidationCheckpoint(output.resolve("validation_checkpoints"),
                        instanceHash, protocol),
                new TRBSVUFinalCheckpoint(output.resolve("solve_checkpoints"),
                        output.resolve("oos_checkpoints"), instanceHash, protocol,
                        replication, "1"));
        TRBSVUExperiment1Runner.Result result = runner.run(instance, Set.of(method));
        writeResult(output, replication, instance, method, result);
        Files.writeString(complete, "protocol=" + protocol + "\ninstance="
                + instanceFile.toAbsolutePath() + "\nmethod=" + method + "\nsourceSha256="
                + sourceHash + "\nrfScriptSha256=" + rfScriptHash
                + "\npythonEnvironment=" + pythonEnvironment + "\n",
                StandardCharsets.UTF_8);
    }

    private static void writeResult(Path output, int replication, TRBSVUSyntheticCase instance,
                                    String method, TRBSVUExperiment1Runner.Result result) throws Exception {
        Map<String, Double> parameters = new LinkedHashMap<>();
        Map<String, String> parameterTypes = new LinkedHashMap<>();
        Map<String, String> families = new LinkedHashMap<>();
        Map<String, Double> baseBandwidth = new LinkedHashMap<>();
        Map<String, Double> effectiveBandwidth = new LinkedHashMap<>();
        if ("Tuned-SAA".equals(method)) {
            parameters.put(method, result.retention());
            parameterTypes.put(method, "RETENTION");
        } else if (method.startsWith("CSAA-")) {
            Kernel kernel = kernel(method);
            parameters.put(method, result.bandwidth().get(kernel));
            parameterTypes.put(method, "BANDWIDTH");
            families.put(method, kernel.name());
            baseBandwidth.put(method, result.bandwidth().get(kernel));
            effectiveBandwidth.put(method, result.finalEffectiveBandwidth().get(kernel));
        } else if ("RF-CSAA".equals(method)) {
            parameters.put(method, Double.NaN);
            families.put(method, "RANDOM_FOREST");
        } else {
            parameters.put(method, Double.NaN);
            families.put(method, "UNCONDITIONAL");
        }
        Path validation = output.resolve("validation");
        Path solve = output.resolve("solve");
        Path oos = output.resolve("oos");
        TRBSVUResultWriter.writeValidationSummary(validation.resolve("summary.csv"), replication,
                "1", result.validationCurve(), parameters, result.validationDetails());
        TRBSVUResultWriter.writeValidationDetails(validation.resolve("details.csv"), replication,
                "1", instance.params, result.validationDetails());
        if (result.selectedContextual() != null) {
            Files.deleteIfExists(validation.resolve("selected_context.csv"));
            TRBSVUResultWriter.writeContextualChoice(validation.resolve("context_candidate.csv"),
                    replication, result.selectedContextual());
        }
        TRBSVUResultWriter.writeFinalSolves(solve.resolve("final_solve.csv"), replication, "1",
                instance.params, result.decisions(), result.validationCost(), parameters,
                parameterTypes, families, baseBandwidth, effectiveBandwidth, result.finalWeights());
        TRBSVUResultWriter.writeFinalWeights(solve.resolve("final_weights.csv"), replication,
                "1", result.finalWeights());
        TRBSVUResultWriter.writeOosSummary(oos.resolve("summary.csv"), replication, "1", result.oos());
        TRBSVUResultWriter.writeOosDetails(oos.resolve("draws.csv"), replication, "1",
                result.oosDetails());
    }

    private static List<Task> buildTasks(Config config) throws Exception {
        Set<Integer> replications = parseReplications(config.replications);
        List<Task> tasks = new ArrayList<>();
        for (int replication : replications) {
            String name = String.format(Locale.ROOT, "rep_%03d", replication);
            Path instance = config.input.resolve(name).resolve("instance").resolve("instance.tsv");
            if (!Files.isRegularFile(instance))
                throw new IllegalStateException("Missing frozen input: " + instance.toAbsolutePath());
            for (String method : config.methods) tasks.add(new Task(replication, name, method, instance));
        }
        return tasks;
    }

    static void aggregateContextualChoices(Path input, Path output, Set<Integer> replications)
            throws Exception {
        for (int replication : replications) {
            String name = String.format(Locale.ROOT, "rep_%03d", replication);
            Path replicationOutput = output.resolve(name);
            ContextualChoice chosen = null;
            for (String method : CONTEXTUAL_METHODS) {
                Path candidateFile = replicationOutput.resolve(safe(method)).resolve("validation")
                        .resolve("context_candidate.csv");
                if (!Files.isRegularFile(candidateFile))
                    throw new IllegalStateException("Missing contextual candidate for " + name
                            + "/" + method + ": " + candidateFile);
                ContextualChoice candidate = TRBSVUExperiment4Main.loadChoice(candidateFile);
                if (chosen == null || TRBSVUStatistics.better(candidate.validationCost(),
                        candidate.validationSd(), contextualTieParameter(candidate),
                        chosen.validationCost(), chosen.validationSd(),
                        contextualTieParameter(chosen))) {
                    chosen = candidate;
                }
            }
            Path validation = replicationOutput.resolve("validation");
            TRBSVUResultWriter.writeContextualChoice(
                    validation.resolve("experiment1_selected_context.csv"), replication, chosen);
            Path sourceInstance = input.resolve(name).resolve("instance").resolve("instance.tsv");
            Path targetInstance = replicationOutput.resolve("instance").resolve("instance.tsv");
            if (!Files.isRegularFile(sourceInstance))
                throw new IllegalStateException("Missing frozen input during aggregation: " + sourceInstance);
            Files.createDirectories(targetInstance.getParent());
            if (Files.exists(targetInstance) && Files.mismatch(sourceInstance, targetInstance) != -1L)
                throw new IllegalStateException("Output contains a different frozen instance: " + targetInstance);
            if (!Files.exists(targetInstance))
                Files.copy(sourceInstance, targetInstance, StandardCopyOption.COPY_ATTRIBUTES);
            System.out.println("Experiment 1 contextual C* aggregated for " + name + ": " + chosen);
        }
    }

    private static double contextualTieParameter(ContextualChoice choice) {
        return "RF".equals(choice.family()) ? Double.POSITIVE_INFINITY : choice.bandwidth();
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
                if (first > last) throw new IllegalArgumentException("Descending replication range: " + token);
                for (int value = first; value <= last; value++) result.add(value);
            }
        }
        return result;
    }

    private static List<String> parseMethods(String text) {
        if ("ALL".equalsIgnoreCase(text)) return ALL_METHODS;
        List<String> methods = Arrays.stream(text.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).map(TRBSVUExperiment1IdeMain::canonicalMethod)
                .distinct().toList();
        if (methods.isEmpty()) throw new IllegalArgumentException("No methods selected.");
        return methods;
    }

    private static String canonicalMethod(String value) {
        return ALL_METHODS.stream().filter(method -> method.equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Experiment 1 method: " + value
                        + "; choose from " + ALL_METHODS));
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

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    }

    private static String safe(String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sourceFingerprint(Path root) throws Exception {
        Path absolute = root.toAbsolutePath().normalize();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> sources;
        try (var stream = Files.walk(absolute)) {
            sources = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> absolute.relativize(path)
                            .toString().replace('\\', '/'))).toList();
        }
        for (Path source : sources) {
            digest.update(absolute.relativize(source).toString().replace('\\', '/')
                    .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(source));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String pythonEnvironment(Path python) throws Exception {
        String code = "import sys; from importlib.metadata import version; "
                + "print('python='+sys.version.split()[0]+'|numpy='+version('numpy')"
                + "+'|scikit-learn='+version('scikit-learn'))";
        Process process = new ProcessBuilder(python.toString(), "-c", code)
                .redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out while reading the RF Python environment.");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 || output.isEmpty() || output.indexOf('\n') >= 0
                || output.indexOf('\r') >= 0)
            throw new IllegalStateException("Cannot identify the RF Python environment: " + output);
        return output;
    }

    private record Task(int replication, String replicationName, String method, Path instance) {
        String label() { return replicationName + "/" + method; }
    }
    private record TaskResult(int exitCode, String message) { }

    private record Config(Path input, Path output, int parallelTasks, int solverThreads,
                          int limitSeconds, int validationOrigins, String replications,
                          List<String> methods, boolean dryRun) {
        static Config parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean dry = false;
            for (String arg : args) {
                if ("--dry-run".equals(arg)) { dry = true; continue; }
                if (!arg.startsWith("--") || !arg.contains("="))
                    throw new IllegalArgumentException("Arguments must use --key=value; got " + arg);
                int equals = arg.indexOf('=');
                values.put(arg.substring(2, equals), arg.substring(equals + 1));
            }
            Set<String> known = Set.of("input", "output", "parallel", "solver-threads",
                    "limit-seconds", "validation-origins", "replications", "methods");
            if (!known.containsAll(values.keySet()))
                throw new IllegalArgumentException("Unknown argument(s): " + values.keySet().stream()
                        .filter(key -> !known.contains(key)).sorted(Comparator.naturalOrder()).toList());
            int parallel = integer(values, "parallel", DEFAULT_PARALLEL_TASKS);
            int solverThreads = integer(values, "solver-threads", DEFAULT_SOLVER_THREADS);
            int limit = integer(values, "limit-seconds", DEFAULT_LIMIT_SECONDS);
            int origins = integer(values, "validation-origins", DEFAULT_VALIDATION_ORIGINS);
            if (parallel < 1 || solverThreads < 1 || limit < 1 || origins < 1 || origins > 30)
                throw new IllegalArgumentException("Invalid positive parallel/solver/time setting or origins outside 1--30.");
            return new Config(Path.of(values.getOrDefault("input", DEFAULT_INPUT.toString())),
                    Path.of(values.getOrDefault("output", DEFAULT_OUTPUT.toString())), parallel,
                    solverThreads, limit, origins,
                    values.getOrDefault("replications", DEFAULT_REPLICATIONS),
                    parseMethods(values.getOrDefault("methods", DEFAULT_METHODS)), dry);
        }

        private static int integer(Map<String, String> values, String key, int fallback) {
            return Integer.parseInt(values.getOrDefault(key, Integer.toString(fallback)));
        }
    }
}
