package Test.analysis.synthetic;

import Model.RCSAASolverVariant;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/** One executable for paired Experiment 1/2 nominal, RCSAA, chi-square, and W1 methods. */
public final class TRBSVUExperiment12Main {
    private TRBSVUExperiment12Main() { }

    public static void main(String[] args) throws Exception {
        if ((args.length == 1 || args.length == 2) && args[0].equals("--source-fingerprint")) {
            System.out.println(javaSourceFingerprint(Path.of(args.length == 2 ? args[1] : "src")));
            return;
        }
        if (args.length < 5 || args.length > 8)
            throw new IllegalArgumentException("Usage: <1|both> <replicationIndex> <baseSeed> "
                    + "<repair|compact> <outputDir> [validationOrigins=25] [threads=4] [limitSec=14400]");
        String mode = args[0];
        if (!mode.equals("1") && !mode.equals("both"))
            throw new IllegalArgumentException("Mode must be 1 or both.");
        int index = Integer.parseInt(args[1]);
        if (index < 0) throw new IllegalArgumentException("Negative replication index.");
        long baseSeed = Long.parseLong(args[2]);
        String algorithm = args[3];
        if (!algorithm.equals("repair") && !algorithm.equals("compact"))
            throw new IllegalArgumentException("RCSAA algorithm must be explicit: repair or compact.");
        Path outputDirectory = Path.of(args[4]);
        int origins = args.length > 5 ? Integer.parseInt(args[5])
                : TRBSVUFormalProtocol.VALIDATION_ORIGINS;
        if (origins != TRBSVUFormalProtocol.VALIDATION_ORIGINS)
            throw new IllegalArgumentException("Formal Experiment 1/2 requires "
                    + TRBSVUFormalProtocol.VALIDATION_ORIGINS + " validation origins.");
        int threads = args.length > 6 ? Integer.parseInt(args[6]) : 4;
        int limitSeconds = args.length > 7 ? Integer.parseInt(args[7]) : 14400;
        Settings settings = algorithm.equals("repair")
                ? new Settings(threads, limitSeconds, 1e-4,
                    RCSAASolverVariant.LBBD_PRIMAL_SEARCH, true, false)
                : new Settings(threads, limitSeconds, 1e-4,
                    RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        SplittableRandom random = new SplittableRandom(baseSeed + index);
        TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                random.nextLong(), random.nextLong(), random.nextLong(),
                random.nextLong(), random.nextLong());
        Path replication = outputDirectory.resolve(String.format("rep_%03d", index));
        Path instanceDirectory = replication.resolve("instance");
        Path validationDirectory = replication.resolve("validation");
        Path solveDirectory = replication.resolve("solve");
        Path oosDirectory = replication.resolve("oos");
        Files.createDirectories(instanceDirectory);
        Path caseFile = instanceDirectory.resolve("instance.tsv");
        Path legacyBinary = instanceDirectory.resolve("instance.bin");
        TRBSVUSyntheticCase instance;
        if (Files.exists(caseFile)) {
            instance = TRBSVUSyntheticCaseIO.loadText(caseFile);
            verifyFrozenBaseline(instance, seeds);
        } else if (Files.exists(legacyBinary)) {
            instance = TRBSVUSyntheticCaseIO.load(legacyBinary);
            verifyFrozenBaseline(instance, seeds);
            TRBSVUSyntheticCaseIO.saveText(instance, caseFile);
        } else {
            // Baseline cell only. The remaining paired DGP cells belong to Experiment 3.
            TRBSVUSyntheticCase.Generated generated = TRBSVUSyntheticCase.generateDetailed(
                    TRBSVUFormalProtocol.CARRIERS, TRBSVUFormalProtocol.LANES,
                    TRBSVUFormalProtocol.HISTORY_PERIODS, TRBSVUFormalProtocol.OOS_DRAWS,
                    Distribution.NORMAL, Volatility.LOW, seeds);
            instance = generated.instance();
            TRBSVUSyntheticCaseIO.saveText(instance, caseFile);
            TRBSVUResultWriter.writeInstance(instanceDirectory, instance);
            TRBSVUResultWriter.writeDgpParameters(instanceDirectory,
                    generated.demandParameters(), Distribution.NORMAL, Volatility.LOW);
        }
        Path rfPython = Path.of(".venv-rsome", "Scripts", "python.exe").toAbsolutePath();
        Path rfScript = Path.of("analysis", "trb_svu", "rf_leaf_weights.py").toAbsolutePath();
        String pythonEnvironment = pythonEnvironment(rfPython);
        String rfScriptSha256 = sha256(Files.readAllBytes(rfScript));
        String javaSourceSha256 = javaSourceFingerprint(Path.of("src"));
        TRBSVUForestWeights forest = new TRBSVUForestWeights(rfPython.toString(), rfScript);
        String instanceSha256 = sha256(Files.readAllBytes(caseFile));
        String commonProtocol = TRBSVUFormalProtocol.EXPERIMENT12_VERSION
                + "|equality=true|algorithm=" + algorithm
                + "|threads=" + threads + "|limitSeconds=" + limitSeconds + "|tolerance=1e-4"
                + "|validationTrainingPeriods="
                + TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS
                + "|validationOrigins=" + origins + "|rfTrees=500|rfSeed=frozen"
                 + "|rfScriptSha256=" + rfScriptSha256
                + "|javaSourceSha256=" + javaSourceSha256
                + "|pythonEnvironment=" + pythonEnvironment
                 + "|chi2PositiveWeightFloor=1e-8|chi2StrictZeroWeightsPruned=true"
                 + "|w1ZeroWeightsRetainedForSharedSupport=true";
        String experiment1Protocol = sha256((commonProtocol
                + "|experiment=1|retention=" + Arrays.toString(TRBSVUExperiment1Runner.RETENTION)
                + "|bandwidth=" + Arrays.toString(TRBSVUExperiment1Runner.BANDWIDTH))
                .getBytes(StandardCharsets.UTF_8));
        String experiment2Protocol = sha256((commonProtocol
                + "|experiment=2|lambda=" + Arrays.toString(TRBSVUExperiment2Runner.LAMBDA)
                 + "|w1=" + Arrays.toString(TRBSVUExperiment2Runner.W1_RADIUS))
                .getBytes(StandardCharsets.UTF_8));
        Path seedFile = instanceDirectory.resolve("manifest.txt");
        String runManifest = "baseline=Normal-Low\nI=" + TRBSVUFormalProtocol.CARRIERS
                + "\nJ=" + TRBSVUFormalProtocol.LANES
                + "\nH=" + TRBSVUFormalProtocol.HISTORY_PERIODS
                + "\nOOS=" + TRBSVUFormalProtocol.OOS_DRAWS + "\n"
                + "validationTrainingPeriods="
                + TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS + "\n"
                + "baseSeed=" + baseSeed + "\nreplication=" + index + "\n"
                + "demandParameters=" + seeds.demandParameters() + "\n"
                + "procurement=" + seeds.procurement() + "\n"
                + "contexts=" + seeds.contexts() + "\n"
                + "historicalNoise=" + seeds.historicalNoise() + "\n"
                + "oosNoise=" + seeds.oosNoise() + "\n"
                + "validationOrigins=" + origins + "\nalgorithm=" + algorithm + "\n"
                + "threads=" + threads + "\nlimitSecondsPerSolve=" + limitSeconds + "\n"
                + "instanceSha256=" + instanceSha256 + "\n"
                + "experiment1ProtocolFingerprint=" + experiment1Protocol + "\n"
                + "experiment2ProtocolFingerprint=" + experiment2Protocol + "\n"
                + "rfTrees=500\nrfPythonCommand=" + rfPython + "\n"
                + "pythonEnvironment=" + pythonEnvironment + "\n"
                + "rfScriptSha256=" + rfScriptSha256 + "\n"
                + "javaSourceSha256=" + javaSourceSha256 + "\n"
                 + "chi2PositiveWeightFloor=1e-8\n"
                 + "chi2StrictZeroWeightsPruned=true\n"
                 + "w1ZeroWeightsRetainedForSharedSupport=true\n"
                 + "rcsaaCompactFormulation="
                 + (algorithm.equals("compact") ? "PRODUCT_MCCORMICK_COMPACT" : "REPAIR_CUT") + "\n";
        Path completionMarker = replication.resolve("experiment12_complete.txt");
        if (mode.equals("both")) Files.deleteIfExists(completionMarker);
        TRBSVUExperiment1Runner exp1 = new TRBSVUExperiment1Runner(settings, forest, origins,
                new TRBSVUValidationCheckpoint(validationDirectory.resolve("checkpoints_experiment1"),
                        instanceSha256, experiment1Protocol),
                new TRBSVUFinalCheckpoint(solveDirectory.resolve("checkpoints_experiment1"),
                        oosDirectory.resolve("checkpoints_experiment1"), instanceSha256,
                        experiment1Protocol, index, "1"));
        TRBSVUExperiment1Runner.Result result1 = exp1.run(instance);
        Map<String, Double> exp1Parameters = new LinkedHashMap<>();
        Map<String, String> exp1ParameterTypes = new LinkedHashMap<>();
        Map<String, String> exp1Families = new LinkedHashMap<>();
        Map<String, Double> exp1BaseBandwidth = new LinkedHashMap<>();
        Map<String, Double> exp1EffectiveBandwidth = new LinkedHashMap<>();
        exp1Parameters.put("D", Double.NaN);
        exp1Parameters.put("SAA-All", Double.NaN);
        exp1Parameters.put("Tuned-SAA", result1.retention());
        exp1ParameterTypes.put("Tuned-SAA", "RETENTION");
        for (Kernel family : Kernel.values()) {
            String name = kernelName(family);
            exp1Parameters.put(name, result1.bandwidth().get(family));
            exp1ParameterTypes.put(name, "BANDWIDTH");
            exp1Families.put(name, family.name());
            exp1BaseBandwidth.put(name, result1.bandwidth().get(family));
            exp1EffectiveBandwidth.put(name, result1.finalEffectiveBandwidth().get(family));
        }
        exp1Parameters.put("RF-CSAA", Double.NaN);
        exp1Families.put("RF-CSAA", "RANDOM_FOREST");
        TRBSVUResultWriter.writeContextualChoice(
                validationDirectory.resolve("experiment1_selected_context.csv"),
                index, result1.selectedContextual());
        writeExperiment(index, "1", instance, validationDirectory, solveDirectory, oosDirectory,
                result1.decisions(), result1.oos(), result1.oosDetails(), result1.finalWeights(),
                result1.validationCost(), result1.validationCurve(), result1.validationDetails(),
                exp1Parameters, exp1ParameterTypes, exp1Families, exp1BaseBandwidth,
                exp1EffectiveBandwidth);
        if (mode.equals("1")) {
            writeAtomically(seedFile, runManifest);
            writeAtomically(replication.resolve("run_manifest.txt"), runManifest);
        }
        System.out.println("Experiment 1 complete: " + replication.toAbsolutePath());
        if (mode.equals("both")) {
            String selectedContextProtocol = sha256((experiment2Protocol
                    + "|selectedContext=" + result1.selectedContextual().family()
                    + "|selectedBandwidth=" + result1.selectedContextual().bandwidth()
                    + "|selectedValidationSd=" + result1.selectedContextual().validationSd()
                    + "|bandwidthOrder=" + result1.selectedContextual().bandwidthOrder())
                    .getBytes(StandardCharsets.UTF_8));
            TRBSVUExperiment2Runner exp2 = new TRBSVUExperiment2Runner(settings, exp1, origins,
                    new TRBSVUValidationCheckpoint(validationDirectory.resolve("checkpoints_experiment2"),
                            instanceSha256, selectedContextProtocol),
                    new TRBSVUFinalCheckpoint(solveDirectory.resolve("checkpoints_experiment2"),
                            oosDirectory.resolve("checkpoints_experiment2"), instanceSha256,
                            selectedContextProtocol, index, "2"));
            TRBSVUExperiment2Runner.Result result2 = exp2.run(instance,
                    result1.selectedContextual());
            Map<String, String> exp2ParameterTypes = new LinkedHashMap<>();
            Map<String, String> exp2Families = new LinkedHashMap<>();
            Map<String, Double> exp2BaseBandwidth = new LinkedHashMap<>();
            for (String method : result2.decisions().keySet()) {
                exp2ParameterTypes.put(method, parameterType(method));
                boolean conditional = method.equals("RCSAA") || method.startsWith("C-");
                exp2Families.put(method, conditional
                        ? contextFamily(result1.selectedContextual()) : "UNCONDITIONAL");
                if (conditional && Double.isFinite(result1.selectedContextual().bandwidth()))
                    exp2BaseBandwidth.put(method, result1.selectedContextual().bandwidth());
            }
            writeExperiment(index, "2", instance, validationDirectory, solveDirectory, oosDirectory,
                    result2.decisions(), result2.oos(), result2.oosDetails(), result2.finalWeights(),
                    result2.validationCost(), result2.validationCurve(), result2.validationDetails(),
                    result2.selectedParameter(), exp2ParameterTypes, exp2Families,
                    exp2BaseBandwidth, result2.effectiveContextBandwidth());
            String completedManifest = runManifest
                    + "experiment2SelectedContextFingerprint=" + selectedContextProtocol + "\n";
            writeAtomically(seedFile, completedManifest);
            writeAtomically(replication.resolve("run_manifest.txt"), completedManifest);
            writeAtomically(completionMarker,
                    "protocolVersion=" + TRBSVUFormalProtocol.EXPERIMENT12_VERSION + "\n"
                            + "baseSeed=" + baseSeed + "\n"
                            + "replication=" + index + "\n"
                            + "algorithm=" + algorithm + "\n"
                            + "validationTrainingPeriods="
                            + TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS + "\n"
                            + "validationOrigins=" + origins + "\n"
                            + "threads=" + threads + "\n"
                            + "limitSeconds=" + limitSeconds + "\n"
                            + "instanceSha256=" + instanceSha256 + "\n"
                            + "javaSourceSha256=" + javaSourceSha256 + "\n"
                            + "rfScriptSha256=" + rfScriptSha256 + "\n"
                            + "pythonEnvironment=" + pythonEnvironment + "\n"
                            + "experiment1ProtocolFingerprint=" + experiment1Protocol + "\n"
                            + "experiment2SelectedContextFingerprint=" + selectedContextProtocol + "\n");
            System.out.println("Experiment 2 complete: " + replication.toAbsolutePath());
        }
    }

    private static void verifyFrozenBaseline(TRBSVUSyntheticCase instance,
                                             TRBSVUSyntheticCase.Seeds expectedSeeds) {
        int expectedAlpha = (int) Math.ceil(0.1 * instance.params.I);
        int expectedBeta = (int) Math.ceil(0.7 * instance.params.I);
        boolean invalidContext = instance.testContext.dim() != 4
                || instance.history.stream().anyMatch(sample -> sample.theta.dim() != 4)
                || instance.oos.stream().anyMatch(sample -> sample.theta.dim() != 4);
        if (instance.params.I != TRBSVUFormalProtocol.CARRIERS
                || instance.params.J != TRBSVUFormalProtocol.LANES
                || instance.history.size() != TRBSVUFormalProtocol.HISTORY_PERIODS
                || instance.oos.size() != TRBSVUFormalProtocol.OOS_DRAWS
                || instance.params.alpha != expectedAlpha || instance.params.beta != expectedBeta
                || invalidContext
                || !instance.seeds.equals(expectedSeeds)) {
            throw new IllegalStateException("Existing frozen instance does not match the requested "
                    + "Normal-Low baseline dimensions, selection bounds, context dimension, and seeds.");
        }
    }

    private static void writeExperiment(int index, String experiment,
                                        TRBSVUSyntheticCase instance,
                                        Path validationDirectory, Path solveDirectory,
                                        Path oosDirectory,
                                        Map<String, Model.Solution> decisions,
                                        Map<String, TRBSVUSolveMethods.Oos> oos,
                                        Map<String, java.util.List<TRBSVUSolveMethods.OosDraw>> oosDetails,
                                        Map<String, java.util.List<Basic.Sample>> finalWeights,
                                        Map<String, Double> validationCost,
                                        Map<String, Map<Double, Double>> validationCurve,
                                        java.util.List<TRBSVUValidationTrace> validationDetails,
                                        Map<String, Double> parameters,
                                        Map<String, String> parameterTypes,
                                        Map<String, String> contextFamilies,
                                        Map<String, Double> baseBandwidth,
                                        Map<String, Double> effectiveBandwidth) throws Exception {
        String prefix = "experiment" + experiment;
        TRBSVUResultWriter.writeValidationSummary(validationDirectory.resolve(prefix + "_summary.csv"),
                index, experiment, validationCurve, parameters, validationDetails);
        TRBSVUResultWriter.writeValidationDetails(validationDirectory.resolve(prefix + "_details.csv"),
                index, experiment, instance.params, validationDetails);
        TRBSVUResultWriter.writeFinalSolves(solveDirectory.resolve(prefix + "_final_solves.csv"),
                index, experiment, instance.params, decisions, validationCost, parameters,
                parameterTypes, contextFamilies, baseBandwidth, effectiveBandwidth, finalWeights);
        TRBSVUResultWriter.writeFinalWeights(solveDirectory.resolve(prefix + "_final_weights.csv"),
                index, experiment, finalWeights);
        TRBSVUResultWriter.writeOosSummary(oosDirectory.resolve(prefix + "_summary.csv"),
                index, experiment, oos, decisions);
        TRBSVUResultWriter.writeOosDetails(oosDirectory.resolve(prefix + "_draws.csv"),
                index, experiment, oosDetails, decisions);
    }

    private static String kernelName(Kernel family) {
        return switch (family) {
            case EXPONENTIAL -> "CSAA-Exp";
            case GAUSSIAN -> "CSAA-Gau";
            case EPANECHNIKOV -> "CSAA-Epa";
            case TRIANGULAR -> "CSAA-Tri";
        };
    }

    private static String contextFamily(TRBSVUExperiment1Runner.ContextualChoice choice) {
        return "RF".equals(choice.family()) ? "RANDOM_FOREST" : choice.family();
    }

    private static String parameterType(String method) {
        if (method.endsWith("W1")) return "W1_RADIUS";
        return "LAMBDA";
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    /** Conservative protocol fingerprint: any Java source change invalidates stored results. */
    private static String javaSourceFingerprint(Path sourceRoot) throws Exception {
        Path absoluteRoot = sourceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteRoot))
            throw new IllegalStateException("Java source root not found: " + absoluteRoot);
        List<Path> sources;
        try (var stream = Files.walk(absoluteRoot)) {
            sources = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .sorted(Comparator.comparing(path -> absoluteRoot.relativize(path)
                            .toString().replace('\\', '/')))
                    .toList();
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (Path source : sources) {
            String relative = absoluteRoot.relativize(source).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Files.readAllBytes(source));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String pythonEnvironment(Path python) throws Exception {
        String code = "import sys; from importlib.metadata import version; "
                + "print('python='+sys.version.split()[0]+'|numpy='+version('numpy')"
                + "+'|scikit-learn='+version('scikit-learn')+'|rsome='+version('rsome')"
                + "+'|Mosek='+version('Mosek'))";
        Process process = new ProcessBuilder(python.toString(), "-c", code)
                .redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Timed out while reading the frozen Python environment.");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 || output.isEmpty() || output.indexOf('\n') >= 0
                || output.indexOf('\r') >= 0)
            throw new IllegalStateException("Cannot identify the frozen Python environment: " + output);
        return output;
    }

    private static void writeAtomically(Path target, String content) throws Exception {
        Path temporary = Files.createTempFile(target.getParent(), "pending_complete_", ".txt");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
