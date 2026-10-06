package Test.analysis.synthetic;

import Basic.Sample;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUExperiment4Runner.Certificate;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Paired unlimited lambda sweep with common OOS and Proposition 2 diagnostics; no CV. */
public final class TRBSVULambdaDecisionComparison {
    static final String VERSION = "TRBSVU_LAMBDA_COMPARISON_V2";
    static final List<String> METHODS = List.of("C-Chi2", "RCSAA");

    private TRBSVULambdaDecisionComparison() { }

    public static void main(String[] args) throws Exception {
        boolean checkOnly = args.length > 0 && "--check-complete".equals(args[0]);
        if (checkOnly) args = Arrays.copyOfRange(args, 1, args.length);
        if (args.length != 4) throw new IllegalArgumentException(
                "Usage: <config.properties> <instance.tsv> <context_weights.tsv> <case-query-output-dir>");
        Path configFile = Path.of(args[0]).toAbsolutePath();
        Properties p = TRBSVUScaleExperiment.readProperties(configFile);
        double[] lambdas = validateConfiguration(p);
        Path instanceFile = Path.of(args[1]).toAbsolutePath();
        Path weightFile = Path.of(args[2]).toAbsolutePath();
        Path output = Path.of(args[3]).toAbsolutePath().normalize();
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(instanceFile);
        List<Sample> weights = readWeights(weightFile, instance.history);
        String fingerprint = VERSION + ":" + TRBSVUScaleExperiment.sha256(configFile) + ":"
                + TRBSVUScaleExperiment.sha256(instanceFile) + ":" + TRBSVUScaleExperiment.sha256(weightFile)
                + ":" + required(p, "runtimeFingerprint");
        Path receipt = output.resolve("input_fingerprint.txt");
        if (checkOnly) {
            if (Files.isRegularFile(receipt) && !Files.readString(receipt).trim().equals(fingerprint)) {
                System.err.println("Comparison input/configuration fingerprint differs; preserve existing output");
                System.exit(20);
            }
            auditComplete(output, fingerprint, lambdas, instance.oos.size());
            System.out.println("COMPARISON_COMPLETE_AUDIT_PASS " + output);
            return;
        }
        if (Files.exists(output)) {
            if (!Files.isRegularFile(receipt) || !Files.readString(receipt).trim().equals(fingerprint))
                throw new IllegalStateException("Output input/configuration/runtime mismatch; use a new directory");
        } else {
            Files.createDirectories(output);
            TRBSVUScaleExperiment.atomicText(receipt, fingerprint + "\n");
        }
        Files.deleteIfExists(output.resolve("complete.txt"));
        TRBSVUScaleExperiment.atomicText(output.resolve("configuration.properties"), Files.readString(configFile));
        TRBSVUScaleExperiment.atomicText(output.resolve("sources.txt"), "instance=" + instanceFile
                + "\nweights=" + weightFile + "\ncv=false\noos=true\ntimeLimit=UNLIMITED\n");
        Map<String, List<Sample>> weightMap = Map.of("C-Chi2", weights, "RCSAA", weights);
        int replication = Integer.parseInt(required(p, "replication"));
        TRBSVUResultWriter.writeFinalWeights(output.resolve("weights.csv"), replication, "LAMBDA_COMPARISON", weightMap);
        Settings settings = Settings.unlimitedRobust(Integer.parseInt(required(p, "solverThreads")));
        var checkpoint = new TRBSVUFinalCheckpoint(output.resolve("checkpoints"), output.resolve("checkpoints"),
                TRBSVUScaleExperiment.sha256(instanceFile), fingerprint, replication, "LAMBDA_COMPARISON");
        var runner = new TRBSVUExperiment4Runner(settings, checkpoint);
        List<Sample> effectiveReference = TRBSVUExperiment4Runner.comparisonReference(weights);
        StringBuilder referenceText = new StringBuilder("sample_id\tweight\n");
        for (Sample s : effectiveReference) referenceText.append(s.id).append('\t').append(s.weight).append('\n');
        TRBSVUScaleExperiment.atomicText(output.resolve("effective_reference.tsv"), referenceText.toString());
        var evaluationCache = new TRBSVULambdaEvaluationCache(output.resolve("evaluations"), instance,
                effectiveReference, fingerprint);
        boolean reuseEvaluations = Boolean.parseBoolean(p.getProperty("reuseEvaluations", "true"));
        StringBuilder comparison = new StringBuilder(header());
        boolean allRecorded = true;
        int failures = 0;
        for (double lambda : lambdas) {
            Path dir = output.resolve("lambda_" + Double.toString(lambda));
            Files.createDirectories(dir);
            Map<String, Solution> solutions = new LinkedHashMap<>();
            Map<String, Oos> oos = new LinkedHashMap<>();
            Certificate certificate = null;
            for (String name : METHODS) {
                PrintStream console = System.out, consoleError = System.err;
                try (PrintStream log = new PrintStream(Files.newOutputStream(dir.resolve(name + ".log"),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8)) {
                    System.setOut(log); System.setErr(log);
                    try {
                        Solution solution = runner.solveDecisionOnly(instance, weights, lambda,
                                name.equals("RCSAA") ? Method.RCSAA : Method.CHI_SQUARED);
                        sanitize(solution, instance.params.I);
                        solutions.put(name, solution);
                        writeSolve(dir, instance, p, name, lambda, weights, solution);
                        var modelEvaluation = reuseEvaluations ? evaluationCache
                                : new TRBSVULambdaEvaluationCache(dir.resolve(name + "_evaluations"),
                                        instance, effectiveReference, fingerprint);
                        if (name.equals("C-Chi2") && solution.y != null && solution.certifiedOptimal) {
                            certificate = modelEvaluation.certificate(solution, lambda);
                            TRBSVUScaleExperiment.atomicText(dir.resolve("certificate.csv"),
                                    "mean,sd,minimum,denominator,lambda_threshold,holds\n" + certificateValues(certificate) + "\n");
                        }
                        if (solution.y != null) {
                            var evaluated = modelEvaluation.oos(solution);
                            oos.put(name, evaluated.summary());
                            TRBSVUResultWriter.writeOosSummary(dir.resolve(name + "_oos_summary.csv"), replication,
                                    "LAMBDA_COMPARISON", Map.of(name, evaluated.summary()), Map.of(name, solution));
                            TRBSVUScaleExperiment.atomicText(dir.resolve(name + "_oos_details.txt"),
                                    "relativeDirectory=" + dir.relativize(evaluated.directory()).toString().replace('\\', '/')
                                            + "\ndraws=decision_draws.csv\n");
                        }
                        Files.deleteIfExists(dir.resolve(name + "_failure.txt"));
                    } catch (Exception ex) {
                        ex.printStackTrace(log);
                        // Keep an already-saved other model; still attempt the other model and later lambdas.
                        TRBSVUScaleExperiment.atomicText(dir.resolve(name + "_failure.txt"), ex.toString() + "\n");
                        allRecorded = false; failures++;
                    } finally {
                        System.setOut(console); System.setErr(consoleError);
                    }
                }
            }
            Solution chi = solutions.get("C-Chi2");
            comparison.append(row(required(p, "caseId"), required(p, "queryId"), lambda,
                    chi, solutions.get("RCSAA"), oos.get("C-Chi2"), oos.get("RCSAA"), certificate));
            TRBSVUScaleExperiment.atomicText(output.resolve("comparison.csv"), comparison.toString());
            System.out.println("LAMBDA_DECISION_RECORDED lambda=" + lambda + " outputs=" + solutions.size());
        }
        // Completion means all solver outcomes are saved, not that all have an incumbent or optimality proof.
        if (allRecorded) TRBSVUScaleExperiment.atomicText(output.resolve("complete.txt"),
                "fingerprint=" + fingerprint + "\nlambdaCount=" + lambdas.length + "\n");
        if (failures > 0) throw new IllegalStateException("Saved partial sweep; failed model/lambda tasks=" + failures);
    }

    /** Read-only audit of saved solves and the hash-sealed fixed-decision OOS cache. */
    static void auditComplete(Path output, String fingerprint, double[] lambdas, int oosCount) throws Exception {
        if (!Files.readString(output.resolve("input_fingerprint.txt")).trim().equals(fingerprint))
            throw new IllegalStateException("Comparison input fingerprint mismatch");
        String complete = Files.readString(output.resolve("complete.txt"));
        if (!complete.lines().anyMatch(line -> line.equals("fingerprint=" + fingerprint))
                || !complete.lines().anyMatch(line -> line.equals("lambdaCount=" + lambdas.length)))
            throw new IllegalStateException("Comparison completion receipt mismatch");
        List<String> rows = Files.readAllLines(output.resolve("comparison.csv"));
        if (rows.size() != lambdas.length + 1 || !rows.get(0).equals(header().trim()))
            throw new IllegalStateException("Incomplete comparison table");
        for (int index = 0; index < lambdas.length; index++) {
            double lambda = lambdas[index];
            String[] resultRow = csvFields(rows.get(index + 1));
            if (resultRow.length != csvFields(rows.get(0)).length || resultRow[3].equals("MODEL_FAILURE"))
                throw new IllegalStateException("Malformed/failed comparison result");
            if (Double.parseDouble(resultRow[2]) != lambda)
                throw new IllegalStateException("Comparison lambda/order mismatch");
            Path dir = output.resolve("lambda_" + Double.toString(lambda));
            for (String name : METHODS) {
                if (Files.exists(dir.resolve(name + "_failure.txt")))
                    throw new IllegalStateException("Saved comparison failure: " + dir);
                Map<String, String> solve = csvRecord(dir.resolve(name + "_solve.csv"));
                if (!name.equals(solve.get("method")) || Double.parseDouble(solve.get("selected_parameter")) != lambda)
                    throw new IllegalStateException("Comparison solve method/parameter mismatch");
                String decision = solve.get("decision_vector");
                if (decision == null) throw new IllegalStateException("Missing decision column");
                if (decision.equals("NA")) continue; // Writer's explicit no-incumbent outcome has no OOS.
                Map<String, String> summary = csvRecord(dir.resolve(name + "_oos_summary.csv"));
                if (!name.equals(summary.get("method")) || !Double.isFinite(Double.parseDouble(summary.get("mean"))))
                    throw new IllegalStateException("Invalid comparison OOS summary");
                Properties pointer = TRBSVUScaleExperiment.readProperties(dir.resolve(name + "_oos_details.txt"));
                Path cache = dir.resolve(required(pointer, "relativeDirectory")).toAbsolutePath().normalize();
                if (!cache.startsWith(output.toAbsolutePath().normalize())
                        || !"decision_draws.csv".equals(required(pointer, "draws")))
                    throw new IllegalStateException("Invalid OOS cache pointer");
                String key = String.join("", decision.split(";", -1));
                if (!cache.getFileName().toString().equals(key) || !key.matches("[01]+"))
                    throw new IllegalStateException("OOS cache decision mismatch");
                Properties sealed = TRBSVUScaleExperiment.readProperties(cache.resolve("oos_complete.properties"));
                if (!fingerprint.equals(required(sealed, "fingerprint"))
                        || !TRBSVUScaleExperiment.sha256(cache.resolve("decision_summary.csv")).equals(required(sealed, "summarySha"))
                        || !TRBSVUScaleExperiment.sha256(cache.resolve("decision_draws.csv")).equals(required(sealed, "drawsSha"))
                        || !TRBSVUCompletionMarker.oosDrawsComplete(cache.resolve("decision_draws.csv"), oosCount))
                    throw new IllegalStateException("Incomplete/corrupt comparison OOS cache");
            }
        }
    }

    private static Map<String, String> csvRecord(Path file) throws Exception {
        List<String> lines = Files.readAllLines(file);
        if (lines.size() != 2) throw new IllegalStateException("Expected one result record: " + file);
        String[] keys = csvFields(lines.get(0)), values = csvFields(lines.get(1));
        if (keys.length != values.length) throw new IllegalStateException("Malformed result CSV: " + file);
        Map<String, String> record = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) record.put(keys[i], values[i]);
        return record;
    }

    private static String[] csvFields(String row) {
        String[] fields = row.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
        for (int i = 0; i < fields.length; i++)
            if (fields[i].startsWith("\"") && fields[i].endsWith("\""))
                fields[i] = fields[i].substring(1, fields[i].length() - 1).replace("\"\"", "\"");
        return fields;
    }

    static double[] validateConfiguration(Properties p) {
        if (!VERSION.equals(required(p, "protocol")) || !"FROZEN".equals(required(p, "state")))
            throw new IllegalStateException("Comparison settings/main-experiment inputs are not frozen");
        for (String key : List.of("caseId", "queryId", "contextFamily", "contextParameter", "weightSource",
                "runtimeFingerprint", "lambdaGrid", "timeLimit", "solverThreads", "replication", "validationContextParameter")) {
            if (required(p, key).equals("UNSET")) throw new IllegalArgumentException("Unresolved setting: " + key);
        }
        if (Integer.parseInt(required(p, "solverThreads")) < 1 || !"UNLIMITED".equals(required(p, "timeLimit"))
                || Integer.parseInt(required(p, "replication")) < 0)
            throw new IllegalArgumentException("Invalid threads/time limit");
        String family = required(p, "contextFamily");
        if (!List.of("RF", "EXPONENTIAL", "GAUSSIAN", "EPANECHNIKOV", "TRIANGULAR").contains(family))
            throw new IllegalArgumentException("Unknown context family");
        for (String key : List.of("contextParameter", "validationContextParameter")) {
            double parameter = Double.parseDouble(required(p, key));
            if (!Double.isFinite(parameter) || parameter <= 0 || family.equals("RF") && parameter != Math.rint(parameter))
                throw new IllegalArgumentException("Invalid context parameter");
        }
        return parseLambdas(required(p, "lambdaGrid"));
    }

    static double[] parseLambdas(String text) {
        double[] values = Arrays.stream(text.split(",", -1)).map(String::trim).mapToDouble(Double::parseDouble).toArray();
        for (double value : values) if (!Double.isFinite(value) || value <= 0)
            throw new IllegalArgumentException("Lambdas must be finite and positive");
        if (Arrays.stream(values).distinct().count() != values.length) throw new IllegalArgumentException("Duplicate lambda");
        return values;
    }

    static List<Sample> readWeights(Path file, List<Sample> history) throws Exception {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() != history.size() + 1 || !lines.get(0).equals("row_index\tsample_id\tweight"))
            throw new IllegalArgumentException("Expected frozen full-window weight table");
        double[] values = new double[history.size()];
        double sum = 0;
        for (int s = 0; s < values.length; s++) {
            String[] f = lines.get(s + 1).split("\t", -1);
            if (f.length != 3 || Integer.parseInt(f[0]) != s || Integer.parseInt(f[1]) != history.get(s).id)
                throw new IllegalArgumentException("Weight row/id mismatch");
            values[s] = Double.parseDouble(f[2]);
            if (!Double.isFinite(values[s]) || values[s] < 0) throw new IllegalArgumentException("Invalid weight");
            sum += values[s];
        }
        if (Math.abs(sum - 1) > 1e-10) throw new IllegalArgumentException("Weights must sum to one");
        return TRBSVUScenarioWeights.copyWithWeights(history, values, false);
    }

    private static void writeSolve(Path dir, TRBSVUSyntheticCase data, Properties config, String name,
                                   double lambda, List<Sample> weights, Solution result) throws Exception {
        String family = required(config, "contextFamily");
        double bandwidth = family.equals("RF") ? Double.NaN : Double.parseDouble(required(config, "contextParameter"));
        double selectedBandwidth = family.equals("RF") ? Double.NaN : Double.parseDouble(required(config, "validationContextParameter"));
        // The common writer keeps all solver diagnostics and carrier identities, not just the distance metrics.
        Path pending = dir.resolve(name + "_solve.pending.csv");
        TRBSVUResultWriter.writeFinalSolves(pending, Integer.parseInt(required(config, "replication")), "LAMBDA_COMPARISON", data.params, Map.of(name, result),
                Map.of(), Map.of(name, lambda), Map.of(name, "LAMBDA"), Map.of(name, family),
                Map.of(name, selectedBandwidth), Map.of(name, bandwidth), Map.of(name, weights));
        TRBSVUScaleExperiment.atomicText(dir.resolve(name + "_solve.csv"), Files.readString(pending));
        Files.delete(pending);
    }

    static void sanitize(Solution s, int carriers) {
        if (s.y == null) {
            s.objValue = Double.NaN; s.relativeGap = Double.NaN; s.certifiedOptimal = false;
        } else {
            if (s.y.length != carriers || !Double.isFinite(s.objValue)) throw new IllegalStateException("Invalid incumbent");
            for (double y : s.y) if (!Double.isFinite(y) || Math.min(Math.abs(y), Math.abs(y - 1)) > 1e-4)
                throw new IllegalStateException("Nonbinary carrier selection");
            if (!Double.isFinite(s.bestBound)) s.relativeGap = Double.NaN;
            else if (s.bestBound > s.objValue + 1e-8 * Math.max(1, Math.abs(s.objValue))
                    && !Model.RCSAABoundDiagnostics.acceptedOverlap(s)) {
                s.relativeGap = Double.NaN; s.certifiedOptimal = false; s.solverStatus += "/BOUND_INCONSISTENT";
            }
        }
    }

    static String header() {
        return "case_id,query_id,lambda,pair_status,both_certified,same_decision,certificate_status,"
                + "certificate_mean,certificate_sd,certificate_minimum,certificate_denominator,certificate_lambda_threshold,certificate_holds,"
                + "objective_gap_percent,chi_status,chi_certified,chi_objective,chi_bound,chi_gap,chi_wall_sec,"
                + "rcsaa_status,rcsaa_certified,rcsaa_objective,rcsaa_bound,rcsaa_gap,rcsaa_wall_sec,"
                + "chi_oos_mean,chi_oos_sd,chi_oos_q95,chi_oos_cvar95,chi_oos_max,"
                + "rcsaa_oos_mean,rcsaa_oos_sd,rcsaa_oos_q95,rcsaa_oos_cvar95,rcsaa_oos_max\n";
    }

    static String row(String caseId, String queryId, double lambda, Solution chi, Solution exact,
                      Oos chiOos, Oos exactOos, Certificate certificate) {
        boolean paired = chi != null && exact != null && chi.y != null && exact.y != null;
        boolean certified = paired && chi.certifiedOptimal && exact.certifiedOptimal;
        String state = chi == null || exact == null ? "MODEL_FAILURE" : !paired ? "NO_PAIRED_INCUMBENT"
                : certified ? "BOTH_CERTIFIED" : "INCUMBENT_ONLY";
        if (certificate != null && (chi == null || !chi.certifiedOptimal || chi.y == null))
            throw new IllegalArgumentException("Optimal-solution certificate needs a certified DRO incumbent");
        String same = paired ? Boolean.toString(TRBSVULambdaEvaluationCache.decisionKey(chi.y)
                .equals(TRBSVULambdaEvaluationCache.decisionKey(exact.y))) : "NA";
        StringBuilder out = new StringBuilder().append(csv(caseId)).append(',').append(csv(queryId)).append(',')
                .append(lambda).append(',').append(state).append(',').append(certified).append(',').append(same).append(',')
                .append(certificate == null ? "UNRESOLVED" : certificate.holds() ? "HOLDS" : "FAILS")
                .append(',').append(certificateValues(certificate)).append(',');
        double difference = certified && Double.isFinite(chi.objValue) && Double.isFinite(exact.objValue)
                && Math.abs(chi.objValue) > 1e-12 ? 100 * (exact.objValue - chi.objValue) / Math.abs(chi.objValue) : Double.NaN;
        out.append(difference).append(',').append(diagnostics(chi)).append(',').append(diagnostics(exact))
                .append(',').append(oosValues(chiOos)).append(',').append(oosValues(exactOos)).append('\n');
        return out.toString();
    }

    private static String certificateValues(Certificate c) {
        return c == null ? "NaN,NaN,NaN,NaN,NaN,NA" : c.weightedMean() + "," + c.weightedSd() + ","
                + c.minimum() + "," + c.denominator() + "," + c.ratio() + "," + c.holds();
    }

    private static String oosValues(Oos oos) {
        return oos == null ? "NaN,NaN,NaN,NaN,NaN" : oos.mean() + "," + oos.standardDeviation()
                + "," + oos.q95() + "," + oos.cvar95() + "," + oos.maximum();
    }

    private static String csv(String text) { return "\"" + text.replace("\"", "\"\"") + "\""; }

    private static String diagnostics(Solution s) {
        if (s == null) return "MODEL_FAILURE,false,NaN,NaN,NaN,NaN";
        return "\"" + String.valueOf(s.solverStatus).replace("\"", "\"\"") + "\"," + s.certifiedOptimal + ","
                + s.objValue + "," + s.bestBound + "," + s.relativeGap + "," + s.solveTimeSec;
    }

    private static String required(Properties p, String key) { return TRBSVUScaleExperiment.required(p, key); }
}
