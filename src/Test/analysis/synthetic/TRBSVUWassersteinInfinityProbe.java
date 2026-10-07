package Test.analysis.synthetic;

import Basic.Sample;
import Helper.basicHelper.Config;
import Model.ContextualWassersteinBoxCcgSolver;
import Model.Solution;
import Model.WassersteinBoxInput;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Isolated local fixed-radius pilot; reuses saved RF probabilities and the original OOS pool. */
public final class TRBSVUWassersteinInfinityProbe {
    private static final String VERSION = "W1_LINF_MONOTONE_PILOT_V1";
    private static final double[] RADII = {.0001, .00025, .0005, .001, .0025, .005};
    private static final String ROBUST = "W1-Linf", BASELINE = "RF-CSAA";

    public static void main(String[] args) throws Exception {
        if (args.length < 4 || args.length > 6)
            throw new IllegalArgumentException("sourceRoot outputRoot shard queryCount [radiiCsv [frozenScopeOnlyControlClass]]");
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path root = Path.of(args[1]).toAbsolutePath().normalize();
        if (source.equals(root) || root.startsWith(source))
            throw new IllegalArgumentException("Pilot output must be separate from source results");
        int shard = Integer.parseInt(args[2]), count = Integer.parseInt(args[3]);
        if (shard < 0 || shard > 1 || count < 1 || count > 40)
            throw new IllegalArgumentException("Two shards (0/1), queryCount in 1..40 required");
        double[] radii = args.length >= 5 ? Arrays.stream(args[4].split(","))
                .mapToDouble(Double::parseDouble).distinct().sorted().toArray() : RADII;
        if (radii.length == 0) throw new IllegalArgumentException("Empty radius set");
        for (double radius : radii)
            if (Arrays.stream(RADII).noneMatch(allowed -> Double.compare(radius, allowed) == 0))
                throw new IllegalArgumentException("Radius must be from the existing fixed pilot set: " + radius);
        Path frozenControl = args.length == 6 ? Path.of(args[5]).toAbsolutePath().normalize() : null;
        if (frozenControl != null && !frozenControl.equals(root.resolve(
                "classes/Test/analysis/synthetic/TRBSVUWassersteinInfinityProbe.class")))
            throw new IllegalArgumentException("Scope-only continuation must use original frozen control class");
        String executionCode = codeFingerprint(null);
        // Explicit compatibility bridge ONLY for this scope-only queue extension.
        // All solver, input, weight and evaluator classes remain in the unchanged frozen classpath.
        // Only the driver's loop count/radius list differs; record both identities, never conceal the change.
        String code = codeFingerprint(frozenControl);
        int failed = 0, completed = 0;
        try (TRBSVUWorkerLock lock = TRBSVUWorkerLock.acquire(root.resolve("worker_" + shard))) {
            TRBSVUScaleExperiment.atomicText(root.resolve("worker_" + shard + "/control_metadata.txt"),
                    "executionCodeSha256=" + executionCode + "\ncompatibleSolveCodeSha256=" + code
                    + "\nfrozenScopeOnlyControlClass=" + frozenControl + "\nqueryCount=" + count
                    + "\nradii=" + Arrays.toString(radii) + "\nstarted=" + Instant.now() + "\n");
            for (double radius : radii) for (int rep = 1; rep <= 5; rep++)
                for (int query = 0; query < count; query++) {
                    if (((rep - 1) * count + query) % 2 != shard) continue;
                    String id = String.format(java.util.Locale.ROOT, "rep_%03d/queries/query_%03d", rep, query);
                    Path directory = root.resolve("radius_" + radius).resolve(id);
                    try {
                        run(source, root, directory, rep, query, radius, code);
                        completed++;
                    } catch (Exception ex) {
                        failed++;
                        TRBSVUScaleExperiment.atomicText(directory.resolve("failure.txt"),
                                Instant.now() + "\n" + ex + "\n");
                        System.err.println("PILOT_TASK_FAILED radius=" + radius + " " + id);
                        ex.printStackTrace(System.err);
                    }
                }
            TRBSVUScaleExperiment.atomicText(root.resolve("worker_" + shard + "/finished.txt"),
                    "finished=" + Instant.now() + "\ncompleted=" + completed + "\nfailed=" + failed + "\n");
            System.out.println("PILOT_WORKER_FINISHED completed=" + completed + " failed=" + failed);
        }
    }

    private static void run(Path source, Path root, Path output, int rep, int query,
                            double radius, String code) throws Exception {
        String repName = String.format(java.util.Locale.ROOT, "rep_%03d", rep);
        String queryName = String.format(java.util.Locale.ROOT, "query_%03d", query);
        Path instanceFile = source.resolve("inputs/main_input/" + repName + "/queries/" + queryName + ".instance.tsv");
        Path weightsFile = source.resolve("results/primary/C-W1/" + repName + "/queries/" + queryName
                + "/solve/experiment2_final_weights.csv");
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(instanceFile);
        if (instance.oos.size() != 1000) throw new IllegalArgumentException("Expected original 1000 OOS");
        List<Sample> weighted = weights(instance.history, weightsFile);
        String inputHash = TRBSVUScaleExperiment.sha256(instanceFile);
        String weightsHash = TRBSVUScaleExperiment.sha256(weightsFile);
        String common = VERSION + ";code=" + code + ";instance=" + inputHash + ";weights=" + weightsHash;
        Path nominalOutput = root.resolve("baseline/" + repName + "/queries/" + queryName);
        TRBSVUFinalCheckpoint nominalCheckpoint = checkpoint(nominalOutput, rep, inputHash, common);
        if (!complete(nominalOutput, common, instance.oos)) {
            Files.deleteIfExists(nominalOutput.resolve("complete.txt"));
            Solution nominal = nominalCheckpoint.load(BASELINE, 0).orElse(null);
            if (nominal == null) {
                System.out.println("PILOT_BASELINE_START rep=" + rep + " query=" + query);
                nominal = TRBSVUSolveMethods.solve(instance.params, instance.lanes, weighted,
                        instance.testContext, TRBSVUSolveMethods.Method.NOMINAL, 0,
                        new TRBSVUSolveMethods.Settings(4, 3600, 1e-4,
                                Model.RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true, true));
                nominalCheckpoint.save(BASELINE, 0, nominal);
            }
            saveAndEvaluate(nominalOutput, instance, weighted, rep, BASELINE, 0, nominal, common);
        }
        String identity = common + ";metric=max_abs_over_U;support=full_window_min_max;radius=" + radius;
        if (complete(output, identity, instance.oos)) {
            System.out.println("PILOT_REUSE_COMPLETE " + output);
            return;
        }
        Files.createDirectories(output);
        Files.deleteIfExists(output.resolve("complete.txt"));
        TRBSVUScaleExperiment.atomicText(output.resolve("query_metadata.txt"),
                "protocol=" + VERSION + "\ncodeSha256=" + code + "\ninstanceSha256=" + inputHash
                + "\nweightsSha256=" + weightsHash + "\nsourceInstance=" + instanceFile
                + "\nsourceWeights=" + weightsFile + "\nseeds=" + instance.seeds
                + "\nquery=" + query + "\nreplication=" + rep
                + "\nmetric=max_j(abs(d_j-ds_j)/U_j)\nwassersteinOrder=1\ngroundNorm=L_INFINITY"
                + "\nsupport=full_75_sample_window_min_max\nmonotonicity=h_i<=r_ij_on_eligible_pairs"
                + "\nparameterSelection=NONE_FIXED_RADIUS_PILOT\nradius=" + radius
                + "\nthreads=4\ntimeLimitSeconds=3600\nrelativeTolerance=1e-4\n");
        TRBSVUFinalCheckpoint cp = checkpoint(output, rep, inputHash, identity);
        Solution solution = cp.load(ROBUST, radius).orElse(null);
        if (solution == null) {
            var support = TRBSVUSolveMethods.wassersteinSupportBox(weighted, instance.params.J);
            double[][] demands = weighted.stream().map(Sample::demand).toArray(double[][]::new);
            double[] probabilities = weighted.stream().mapToDouble(s -> s.weight).toArray();
            double[] scale = support.upper().clone();
            for (int j = 0; j < scale.length; j++) if (!(scale[j] > 0)) scale[j] = 1;
            var input = new WassersteinBoxInput(instance.params, demands, probabilities,
                    support.lower(), support.upper(), scale, radius, WassersteinBoxInput.GroundNorm.L_INFINITY);
            Config config = new Config();
            config.enforceDemandEquality = true; config.threads = 4;
            config.timeLimitSeconds = 3600; config.writeSolverLogToConsole = true;
            System.out.println("PILOT_SOLVE_START rep=" + rep + " query=" + query + " radius=" + radius);
            solution = new ContextualWassersteinBoxCcgSolver().solve(input, config).solution();
            cp.save(ROBUST, radius, solution); // persist solve BEFORE potentially failing OOS evaluation
        }
        saveAndEvaluate(output, instance, weighted, rep, ROBUST, radius, solution, identity);
        Files.deleteIfExists(output.resolve("failure.txt"));
        System.out.println("PILOT_TASK_COMPLETE rep=" + rep + " query=" + query + " radius=" + radius
                + " status=" + solution.solverStatus + " gap=" + solution.relativeGap);
    }

    private static TRBSVUFinalCheckpoint checkpoint(Path dir, int rep, String inputHash, String identity)
            throws Exception {
        return new TRBSVUFinalCheckpoint(dir.resolve("checkpoint/solve"), dir.resolve("checkpoint/oos"),
                inputHash, identity, rep, "LINF_PILOT");
    }

    private static void saveAndEvaluate(Path dir, TRBSVUSyntheticCase instance, List<Sample> weights,
            int rep, String method, double radius, Solution sol, String identity) throws Exception {
        Path solve = dir.resolve("solve/final_solve.csv");
        TRBSVUResultWriter.writeFinalSolves(solve, rep, "LINF_PILOT", instance.params,
                Map.of(method, sol), Map.of(), Map.of(method, radius),
                Map.of(method, method.equals(BASELINE) ? "NONE" : "W1_RADIUS"),
                Map.of(method, "RF"), Map.of(method, Double.NaN), Map.of(method, Double.NaN), Map.of(method, weights));
        TRBSVUScaleExperiment.atomicText(dir.resolve("input_fingerprint.txt"), identity + "\n");
        System.out.println("PILOT_OOS_START " + dir);
        var oos = TRBSVUSolveMethods.evaluateDetailed(instance.params, sol.y, instance.oos);
        TRBSVUResultWriter.writeOosSummary(dir.resolve("oos/summary.csv"), rep, "LINF_PILOT",
                Map.of(method, oos.summary()), Map.of(method, sol));
        TRBSVUResultWriter.writeOosDetails(dir.resolve("oos/draws.csv"), rep, "LINF_PILOT",
                Map.of(method, oos.draws()), Map.of(method, sol));
        if (!outputsComplete(dir, instance.oos)) throw new IllegalStateException("Incomplete output: " + dir);
        TRBSVUScaleExperiment.atomicText(dir.resolve("complete.txt"), identity + "\n");
    }

    private static List<Sample> weights(List<Sample> history, Path file) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() != history.size() + 1) throw new IllegalArgumentException("Weight count mismatch");
        double[] weights = new double[history.size()];
        for (int s = 0; s < weights.length; s++) {
            String[] f = lines.get(s + 1).split(",", -1);
            if (f.length != 8 || Integer.parseInt(f[3]) != s || Integer.parseInt(f[4]) != history.get(s).id
                    || Integer.parseInt(f[5]) != history.get(s).period.tIndex)
                throw new IllegalArgumentException("Saved weight sample identity mismatch at " + s);
            weights[s] = Double.parseDouble(f[6]);
            if (!Double.isFinite(weights[s]) || weights[s] < 0)
                throw new IllegalArgumentException("Invalid saved weight");
            double reference = Double.parseDouble(f[7]);
            // The saved solver reference is normalized again; last-bit differences are expected.
            if (!Double.isFinite(reference) || reference < 0
                    || Math.abs(weights[s] - reference) > 1e-12 * Math.max(1, weights[s]))
                throw new IllegalArgumentException("W1 reference weight changed");
        }
        if (Math.abs(Arrays.stream(weights).sum() - 1) > 1e-9)
            throw new IllegalArgumentException("Saved weights do not sum to one");
        return TRBSVUScenarioWeights.copyWithWeights(history, weights, false);
    }

    private static boolean complete(Path dir, String identity, List<Sample> oos) throws Exception {
        Path marker = dir.resolve("complete.txt");
        return Files.isRegularFile(marker) && Files.readString(marker).trim().equals(identity)
                && outputsComplete(dir, oos);
    }

    private static boolean outputsComplete(Path dir, List<Sample> oos) throws Exception {
        for (String name : List.of("solve/final_solve.csv", "oos/summary.csv", "oos/draws.csv")) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file) || Files.size(file) == 0) return false;
        }
        if (Files.readAllLines(dir.resolve("solve/final_solve.csv")).size() != 2
                || Files.readAllLines(dir.resolve("oos/summary.csv")).size() != 2) return false;
        List<String> lines = Files.readAllLines(dir.resolve("oos/draws.csv"));
        if (lines.size() != oos.size() + 1) return false;
        List<String> header = Arrays.asList(lines.get(0).split(","));
        int id = header.indexOf("sample_id"), demand = header.indexOf("total_demand");
        if (id < 0 || demand < 0) return false;
        for (int s = 0; s < oos.size(); s++) {
            String[] f = lines.get(s + 1).split(",", -1);
            double total = Arrays.stream(oos.get(s).demand()).sum();
            double actual = Double.parseDouble(f[demand]);
            if (Integer.parseInt(f[id]) != oos.get(s).id || !Double.isFinite(actual)
                    || Math.abs(actual - total) > 1e-9 * Math.max(1, total)) return false;
        }
        return true;
    }

    private static String codeFingerprint(Path frozenScopeOnlyControlClass) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String file : List.of("/Model/WassersteinBoxInput.class", "/Model/WassersteinBoxInput$GroundNorm.class",
                "/Model/WassersteinInfinityMonotoneOracle.class", "/Model/ContextualWassersteinBoxCcgSolver.class",
                "/Helper/basicHelper/Config.class", "/Test/analysis/synthetic/TRBSVUWassersteinInfinityProbe.class",
                "/Test/analysis/synthetic/TRBSVUSolveMethods.class", "/Test/analysis/synthetic/TRBSVUResultWriter.class",
                "/Test/analysis/synthetic/TRBSVUScenarioWeights.class", "/Test/BatchRunner$RecourseEvaluator.class")) {
            // Class bytes identify the actually loaded deployment, not a possibly different source tree.
            try (var stream = TRBSVUWassersteinInfinityProbe.class.getResourceAsStream(file)) {
                if (stream == null) throw new IllegalStateException("Missing runtime class " + file);
                digest.update(file.getBytes(StandardCharsets.UTF_8));
                digest.update(frozenScopeOnlyControlClass != null
                        && file.equals("/Test/analysis/synthetic/TRBSVUWassersteinInfinityProbe.class")
                        ? Files.readAllBytes(frozenScopeOnlyControlClass) : stream.readAllBytes());
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
