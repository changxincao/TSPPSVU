package Test.analysis.synthetic;

import Basic.Sample;
import Helper.basicHelper.Config;
import Model.ContextualWassersteinBoxCcgSolver;
import Model.Solution;
import Model.WassersteinBoxInput;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Local Linf W1: one rolling CV per market, then ONE selected radius for all queries. */
public final class TRBSVUWassersteinInfinityCvMain {
    static final boolean EXTENDED = Boolean.getBoolean("trb.svu.linf.extended");
    static final boolean CAPPED = Boolean.getBoolean("trb.svu.linf.cap01");
    static final double[] SMALL_RADII = {.0001, .00025, .0005, .001, .0025, .005};
    private static final double[] ORIGINAL_EXTENDED_RADII =
            {.0001, .00025, .0005, .001, .0025, .005, .01, .025, .05, .1, .25, .5};
    static final double[] RADII = EXTENDED
            ? (CAPPED ? Arrays.copyOf(ORIGINAL_EXTENDED_RADII, 10) : ORIGINAL_EXTENDED_RADII)
            : SMALL_RADII;
    private static final String MODULE = CAPPED ? "cv_upto01" : EXTENDED ? "cv_large" : "cv";
    private static final String VERSION = "W1_LINF_ROLLING_CV_V1", NAME = "W1-Linf";
    private static final int TRAIN = 50, ORIGINS = 25, QUERIES = 40;
    private record Market(int rep, TRBSVUSyntheticCase instance,
                          TRBSVUExperiment1Runner.ContextualChoice choice,
                          String inputHash, String protocol, String baseProtocol, String extendedProtocol) { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException(
                "preflight|worker sourceRoot existingFixedRadiusRoot shard");
        boolean check = args[0].equals("preflight");
        if (CAPPED && !EXTENDED) throw new IllegalArgumentException("Capped mode requires extended mode");
        if (!check && !args[0].equals("worker")) throw new IllegalArgumentException("Unknown mode");
        int shard = Integer.parseInt(args[3]);
        if (shard < 0 || shard > 1) throw new IllegalArgumentException("Shard must be 0 or 1");
        Path source = Path.of(args[1]).toAbsolutePath().normalize();
        Path pilot = Path.of(args[2]).toAbsolutePath().normalize();
        if (source.equals(pilot) || pilot.startsWith(source))
            throw new IllegalArgumentException("Output must be separate from source results");
        String frozenCode = (String) frozen("codeFingerprint", new Class<?>[]{});
        var forest = new TRBSVUForestWeights(System.getProperty("trb.svu.python"),
                Path.of("analysis/trb_svu/rf_leaf_weights.py"));
        var contextual = new TRBSVUExperiment1Runner(
                TRBSVUSolveMethods.Settings.unlimitedRobust(4), forest, ORIGINS);
        int failed = 0;
        try (var lock = TRBSVUWorkerLock.acquire(pilot.resolve(MODULE + "/worker_" + shard))) {
            for (int rep = 1; rep <= 5; rep++) {
                if (!check && (rep - 1) % 2 != shard) continue;
                Path output = pilot.resolve(String.format(MODULE + "/results/rep_%03d", rep));
                try {
                    Market market = market(source, pilot, rep, frozenCode);
                    if (check) {
                        auditReferenceWeights(source, market, contextual);
                        if (EXTENDED) auditOriginalValidation(pilot, market);
                        if (CAPPED) previousExtended(pilot, market);
                        System.out.println("CV_PREFLIGHT_PASS rep=" + rep + " origins=25 training=50"
                                + " candidates=" + RADII.length + " finalQueries=40 metric=L_INFINITY frozenCode=" + frozenCode);
                    } else run(source, pilot, output, market, contextual, frozenCode);
                } catch (Exception failure) {
                    if (check) throw failure;
                    failed++;
                    TRBSVUScaleExperiment.atomicText(output.resolve("failure.txt"), failure + "\n");
                    System.err.println("CV_MARKET_FAILED rep=" + rep + "; continuing remaining markets");
                    failure.printStackTrace(System.err);
                }
            }
            if (!check) TRBSVUScaleExperiment.atomicText(pilot.resolve(MODULE + "/worker_" + shard + "/finished.txt"),
                    "failedMarkets=" + failed + "\n");
        }
        if (failed > 0) throw new IllegalStateException("Incomplete markets=" + failed);
    }

    private static Market market(Path source, Path pilot, int rep, String frozenCode) throws Exception {
        String repName = String.format("rep_%03d", rep);
        Path input = source.resolve("inputs/main_input/" + repName + "/queries");
        Path first = input.resolve("query_000.instance.tsv");
        var instance = TRBSVUSyntheticCaseIO.loadText(first);
        TRBSVUExperiment1IdeMain.verifyFormalDimensions(instance);
        var digest = MessageDigest.getInstance("SHA-256");
        for (int q = 0; q < QUERIES; q++) {
            Path file = input.resolve(String.format("query_%03d.instance.tsv", q));
            TRBSVUExperiment1IdeMain.verifySharedTrainingCore(instance,
                    TRBSVUSyntheticCaseIO.loadText(file), q);
            digest.update(file.getFileName().toString().getBytes(StandardCharsets.UTF_8));
            digest.update(Files.readAllBytes(file));
        }
        Path choiceFile = source.resolve("frozen_rf/" + repName + "/validation/experiment1_selected_context.csv");
        var choice = TRBSVUExperiment4Main.loadChoice(choiceFile);
        if (!choice.family().equals("RF")) throw new IllegalArgumentException("Expected frozen RF family");
        var rf = TRBSVUExperiment2IdeMain.rfFingerprint(choice,
                Path.of(System.getProperty("trb.svu.python")));
        String poolHash = HexFormat.of().formatHex(digest.digest());
        String suffix = "|choice=" + choice + "|radii=";
        String settings = "|origins=50..74|training=50|threads=4|limit=3600|tol=1e-4"
                + "|support=full_window_min_max|scale=U|metric=max_abs_over_U"
                + "|tie=mean,sd,smaller_radius" + rf.protocolSuffix();
        String prefix = VERSION + "|pool=" + poolHash + "|frozenSolveCode=" + frozenCode + "|driver=";
        String protocol = hash((prefix + driverHash(null) + suffix + Arrays.toString(RADII)
                + settings).getBytes(StandardCharsets.UTF_8));
        String baseProtocol = null;
        String extendedProtocol = null;
        if (EXTENDED) {
            // Reconstruct the ORIGINAL protocol from the unchanged, running deployment, not the new driver.
            baseProtocol = hash((prefix + driverHash(pilot.resolve(
                    "cv/classes/Test/analysis/synthetic/TRBSVUWassersteinInfinityCvMain.class"))
                    + suffix + Arrays.toString(SMALL_RADII) + settings).getBytes(StandardCharsets.UTF_8));
            Path file = pilot.resolve("cv/results/" + repName + "/protocol.txt");
            if (Files.exists(file) && !Files.readString(file).trim().equals(baseProtocol))
                throw new IllegalStateException("Original CV input/runtime protocol changed; refusing reuse: " + file);
        }
        if (CAPPED) {
            // Read the unchanged old driver bytes, rather than relabelling old
            // checkpoints as having been produced by the capped driver.
            extendedProtocol = hash((prefix + driverHash(pilot.resolve(
                    "cv_large/classes/Test/analysis/synthetic/TRBSVUWassersteinInfinityCvMain.class"))
                    + suffix + Arrays.toString(ORIGINAL_EXTENDED_RADII) + settings)
                    .getBytes(StandardCharsets.UTF_8));
        }
        return new Market(rep, instance, choice, TRBSVUScaleExperiment.sha256(first), protocol,
                baseProtocol, extendedProtocol);
    }

    private static TRBSVUValidationCheckpoint previousExtended(Path pilot, Market market) throws Exception {
        Path root = pilot.resolve(String.format("cv_large/results/rep_%03d", market.rep));
        if (!Files.exists(root.resolve("protocol.txt"))) return null;
        if (!Files.readString(root.resolve("protocol.txt")).trim().equals(market.extendedProtocol))
            throw new IllegalStateException("Extended CV input/runtime protocol changed; refusing reuse: " + root);
        return new TRBSVUValidationCheckpoint(root.resolve("validation_checkpoints"),
                market.inputHash, market.extendedProtocol);
    }

    @SuppressWarnings("unchecked")
    private static void auditReferenceWeights(Path source, Market market,
                                              TRBSVUExperiment1Runner contextual) throws Exception {
        Path file = source.resolve(String.format(
                "results/primary/C-W1/rep_%03d/queries/query_000/solve/experiment2_final_weights.csv", market.rep));
        List<Sample> saved = (List<Sample>) frozen("weights", new Class<?>[]{List.class, Path.class},
                market.instance.history, file);
        var generated = contextual.contextualWeights(market.instance, market.instance.history,
                market.instance.testContext, market.choice);
        if (saved.size() != generated.size()) throw new IllegalStateException("RF reference length changed");
        for (int s = 0; s < saved.size(); s++)
            if (saved.get(s).id != generated.get(s).id
                    || Math.abs(saved.get(s).weight - generated.get(s).weight) > 1e-12)
                throw new IllegalStateException("RF reference weight changed at sample " + s);
    }

    private static void auditOriginalValidation(Path pilot, Market market) throws Exception {
        Path root = pilot.resolve(String.format("cv/results/rep_%03d", market.rep));
        if (!Files.exists(root.resolve("protocol.txt"))) return; // this market is still queued
        var saved = new TRBSVUValidationCheckpoint(root.resolve("validation_checkpoints"),
                market.inputHash, market.baseProtocol);
        int reusable = 0;
        for (double radius : SMALL_RADII) for (int t = TRAIN; t < TRAIN + ORIGINS; t++) {
            var trace = saved.load(NAME, radius, t).orElse(null);
            if (trace == null) continue;
            var window = market.instance.validationWindow(t, TRAIN);
            if (trace.trainingStart() != window.train().get(0).period.tIndex
                    || trace.trainingEnd() != window.train().get(TRAIN-1).period.tIndex
                    || trace.scenarioCount() != TRAIN || trace.decision() == null
                    || trace.decision().length != market.instance.params.I
                    || !Double.isFinite(trace.realizedValidationCost()))
                throw new IllegalStateException("Invalid original validation checkpoint at " + radius + "/" + t);
            reusable++;
        }
        System.out.println("CV_REUSE_PREFLIGHT_PASS rep=" + market.rep + " reusableOrigins=" + reusable);
    }

    private static void run(Path source, Path pilot, Path output, Market market,
                            TRBSVUExperiment1Runner contextual, String frozenCode) throws Exception {
        Files.deleteIfExists(output.resolve("complete.txt"));
        TRBSVUScaleExperiment.atomicText(output.resolve("protocol.txt"), market.protocol + "\n");
        var saved = new TRBSVUValidationCheckpoint(output.resolve("validation_checkpoints"),
                market.inputHash, market.protocol);
        TRBSVUValidationCheckpoint original = null;
        TRBSVUValidationCheckpoint extended = CAPPED ? previousExtended(pilot, market) : null;
        if (extended != null) TRBSVUScaleExperiment.atomicText(output.resolve("extended_validation_reuse_source.txt"),
                "source=cv_large\nverifiedProtocol=" + market.extendedProtocol + "\nexcludedRadii=0.25,0.5\n");
        if (EXTENDED) {
            Path originalRoot = pilot.resolve(String.format("cv/results/rep_%03d", market.rep));
            if (!Files.readString(originalRoot.resolve("protocol.txt")).trim().equals(market.baseProtocol))
                throw new IllegalStateException("Original CV protocol missing/changed; refusing reuse");
            original = new TRBSVUValidationCheckpoint(originalRoot.resolve("validation_checkpoints"),
                    market.inputHash, market.baseProtocol);
            TRBSVUScaleExperiment.atomicText(output.resolve("validation_reuse_source.txt"),
                    "source=" + originalRoot + "\nverifiedProtocol=" + market.baseProtocol + "\n");
        }
        var traces = new ArrayList<TRBSVUValidationTrace>();
        double[] means = new double[RADII.length], sds = new double[RADII.length];
        StringBuilder curve = new StringBuilder("radius,origins,mean,sd\n");
        for (int c = 0; c < RADII.length; c++) {
            double radius = RADII[c];
            double[] costs = new double[ORIGINS];
            for (int t = TRAIN; t < TRAIN + ORIGINS; t++) {
                var window = market.instance.validationWindow(t, TRAIN);
                var trace = saved.load(NAME, radius, t).orElse(null);
                if (trace == null && extended != null) {
                    trace = extended.load(NAME, radius, t).orElse(null);
                    if (trace != null) {
                        saved.save(trace);
                        System.out.println("CV_ORIGIN_REUSED rep=" + market.rep + " radius=" + radius
                                + " origin=" + t + " source=cv_large");
                    }
                }
                if (trace == null && original != null
                        && Arrays.stream(SMALL_RADII).anyMatch(r -> Double.compare(r, radius) == 0)) {
                    trace = original.load(NAME, radius, t).orElse(null);
                    if (trace != null) {
                        saved.save(trace);
                        System.out.println("CV_ORIGIN_REUSED rep=" + market.rep + " radius=" + radius
                                + " origin=" + t + " source=cv");
                    }
                }
                if (trace == null) {
                    var weightResult = contextual.contextualWeightResult(market.instance,
                            window.train(), window.realized().theta, market.choice);
                    var weighted = weightResult.weights();
                    Path stage = output.resolve("validation_solves/radius_" + radius + "/origin_" + t);
                    TRBSVUResultWriter.writeFinalWeights(stage.resolve("weights.csv"), market.rep,
                            "LINF_CV", Map.of(NAME, weighted));
                    String identity = hash((market.protocol + "|radius=" + radius + "|origin=" + t)
                            .getBytes(StandardCharsets.UTF_8));
                    var cp = new TRBSVUFinalCheckpoint(stage.resolve("solve_checkpoint"),
                            stage.resolve("oos_checkpoint"), market.inputHash, identity, market.rep, "LINF_CV");
                    Solution sol = cp.load(NAME, radius).orElse(null);
                    if (sol == null) {
                        System.out.println("CV_ORIGIN_START rep=" + market.rep + " radius=" + radius
                                + " origin=" + t + " training=" + (t-TRAIN) + ".." + (t-1));
                        sol = solve(market.instance, weighted, radius);
                        usable(sol, market.instance.params.I);
                        cp.save(NAME, radius, sol); // preserve solve before the realized-cost LP
                    }
                    usable(sol, market.instance.params.I);
                    double cost = TRBSVUSolveMethods.realizedCost(market.instance.params, sol.y,
                            window.realized().demand());
                    trace = new TRBSVUValidationTrace(NAME, radius, t,
                            window.train().get(0).period.tIndex, window.train().get(TRAIN-1).period.tIndex,
                            weightResult.effectiveBandwidth(), weighted.size(),
                            TRBSVUExperiment1Runner.positiveCount(weighted), TRBSVUExperiment1Runner.ess(weighted),
                            sol.objValue, sol.solverStatus, sol.bestBound, sol.relativeGap,
                            sol.solveTimeSec, sol.optimizerTimeSec, sol.certifiedOptimal, sol.y, cost);
                    saved.save(trace);
                }
                if (trace.trainingStart() != window.train().get(0).period.tIndex
                        || trace.trainingEnd() != window.train().get(TRAIN-1).period.tIndex
                        || trace.scenarioCount() != TRAIN || trace.decision() == null
                        || trace.decision().length != market.instance.params.I
                        || !Double.isFinite(trace.realizedValidationCost()))
                    throw new IllegalStateException("Invalid restored validation origin");
                traces.add(trace);
                costs[t-TRAIN] = trace.realizedValidationCost();
                System.out.println("CV_ORIGIN_COMPLETE rep=" + market.rep + " radius=" + radius
                        + " origin=" + t + " cost=" + trace.realizedValidationCost());
            }
            var summary = TRBSVUStatistics.summarize(costs);
            means[c] = summary.mean(); sds[c] = summary.sampleStandardDeviation();
            curve.append(radius).append(',').append(ORIGINS).append(',').append(means[c])
                    .append(',').append(sds[c]).append('\n');
            TRBSVUScaleExperiment.atomicText(output.resolve("validation_curve.csv"), curve.toString());
            TRBSVUResultWriter.writeValidationDetails(output.resolve("validation_details.csv"),
                    market.rep, "LINF_CV", market.instance.params, traces);
            System.out.println("CV_CANDIDATE_COMPLETE rep=" + market.rep + " radius=" + radius);
        }
        double selected = selectRadius(means, sds);
        TRBSVUScaleExperiment.atomicText(output.resolve("selection.csv"),
                "replication,method,selected_radius,origins,training,selection_basis\n"
                + market.rep + "," + NAME + "," + selected + ",25,50,ROLLING_VALIDATION_ONLY\n");
        System.out.println("CV_RADIUS_SELECTED rep=" + market.rep + " radius=" + selected
                + " finalQueries=40 allOtherRadiiSkipped=true");
        for (int query = 0; query < QUERIES; query++) {
            String id = String.format("rep_%03d/queries/query_%03d", market.rep, query);
            Path artifact = pilot.resolve("radius_" + selected).resolve(id);
            // The frozen helper verifies hashes, weights, checkpoints and all 1000 OOS rows before reuse.
            frozen("run", new Class<?>[]{Path.class, Path.class, Path.class, int.class, int.class,
                    double.class, String.class}, source, pilot, artifact, market.rep, query, selected, frozenCode);
            Path target = output.resolve(String.format("queries/query_%03d", query));
            copyTree(artifact, target);
            TRBSVUScaleExperiment.atomicText(target.resolve("selection_context.txt"),
                    "parameterSelection=ROLLING_CV\nselectedRadius=" + selected + "\nprotocol="
                    + market.protocol + "\nvalidatedSource=" + artifact + "\n");
            TRBSVUScaleExperiment.atomicText(target.resolve("complete.txt"),
                    Files.readString(artifact.resolve("complete.txt")));
            System.out.println("CV_FINAL_QUERY_COMPLETE rep=" + market.rep + " query=" + query
                    + " selectedRadius=" + selected);
        }
        Files.deleteIfExists(output.resolve("failure.txt"));
        TRBSVUScaleExperiment.atomicText(output.resolve("complete.txt"),
                "protocol=" + market.protocol + "\nselectedRadius=" + selected + "\norigins="
                        + RADII.length * ORIGINS + "\nqueries=40\n");
    }

    static double selectRadius(double[] means, double[] sds) {
        if (means.length != RADII.length || sds.length != RADII.length)
            throw new IllegalArgumentException("All " + RADII.length + " complete candidate scores are required");
        int best = -1;
        for (int i = 0; i < RADII.length; i++) {
            if (!Double.isFinite(means[i]) || !Double.isFinite(sds[i]))
                throw new IllegalArgumentException("Incomplete/non-finite candidate score");
            if (best < 0 || TRBSVUStatistics.better(means[i], sds[i], RADII[i],
                    means[best], sds[best], RADII[best])) best = i;
        }
        return RADII[best];
    }

    private static Solution solve(TRBSVUSyntheticCase instance, List<Sample> weighted, double radius)
            throws Exception {
        var support = TRBSVUSolveMethods.wassersteinSupportBox(weighted, instance.params.J);
        double[] scale = support.upper().clone();
        for (int j = 0; j < scale.length; j++) if (!(scale[j] > 0)) scale[j] = 1;
        var input = new WassersteinBoxInput(instance.params, weighted.stream().map(Sample::demand).toArray(double[][]::new),
                weighted.stream().mapToDouble(s -> s.weight).toArray(), support.lower(), support.upper(),
                scale, radius, WassersteinBoxInput.GroundNorm.L_INFINITY);
        Config config = new Config();
        config.enforceDemandEquality = true; config.threads = 4;
        config.timeLimitSeconds = 3600; config.tol = 1e-4; config.writeSolverLogToConsole = true;
        return new ContextualWassersteinBoxCcgSolver().solve(input, config).solution();
    }

    private static void usable(Solution sol, int carriers) {
        if (sol == null || sol.y == null || sol.y.length != carriers || !Double.isFinite(sol.objValue))
            throw new IllegalStateException("No usable incumbent");
        for (double y : sol.y) if (!Double.isFinite(y) || Math.abs(y-Math.rint(y)) > 1e-5 || y < 0 || y > 1)
            throw new IllegalStateException("Invalid incumbent decision");
    }

    // Adapter to the unchanged, frozen pilot deployment: do not alter its byte fingerprint to claim reuse.
    private static Object frozen(String name, Class<?>[] signature, Object... arguments) throws Exception {
        Method method = TRBSVUWassersteinInfinityProbe.class.getDeclaredMethod(name, signature);
        method.setAccessible(true);
        try { return method.invoke(null, arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    private static void copyTree(Path source, Path target) throws Exception {
        Files.deleteIfExists(target.resolve("complete.txt"));
        try (var files = Files.walk(source)) {
            for (Path file : files.toList()) {
                if (file.equals(source.resolve("complete.txt"))) continue;
                Path destination = target.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(destination);
                else Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String driverHash(Path originalMainClass) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String name : List.of("TRBSVUWassersteinInfinityCvMain.class",
                "TRBSVUExperiment1Runner.class", "TRBSVUForestWeights.class", "TRBSVUStatistics.class",
                "TRBSVUSyntheticCase.class", "TRBSVUValidationCheckpoint.class", "TRBSVUFinalCheckpoint.class")) {
            try (var stream = originalMainClass != null && name.equals("TRBSVUWassersteinInfinityCvMain.class")
                    ? Files.newInputStream(originalMainClass)
                    : TRBSVUWassersteinInfinityCvMain.class.getResourceAsStream(name)) {
                if (stream == null) throw new IllegalStateException("Missing CV runtime class: " + name);
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update(stream.readAllBytes());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
