package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Sample;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.ContextualChoice;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.WeightResult;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Experiment 2: robust alternatives on the same frozen case and validation origins. */
public final class TRBSVUExperiment2Runner {
    public static final double[] LAMBDA = {0.01, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 50, 100};
    public static final double[] W1_RADIUS = {0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1};
    public static final double[] PCM_KAPPA = {1, 1.25, 1.5, 2};

    public record Result(Map<String, Solution> decisions,
                         Map<String, TRBSVUSolveMethods.Oos> oos,
                         Map<String, List<TRBSVUSolveMethods.OosDraw>> oosDetails,
                         Map<String, List<Sample>> finalWeights,
                         Map<String, Double> effectiveContextBandwidth,
                         Map<String, Double> selectedParameter,
                         Map<String, Double> validationCost,
                         Map<String, Map<Double, Double>> validationCurve,
                         List<TRBSVUValidationTrace> validationDetails) { }

    private final Settings settings;
    private final TRBSVUExperiment1Runner contextual;
    private final int validationOrigins;
    private final double[] lambdaGrid;
    private final double[] w1Grid;
    private final double[] pcmGrid;
    private final TRBSVUPcmSolver pcmSolver;
    private final TRBSVUValidationCheckpoint checkpoint;
    private final TRBSVUFinalCheckpoint finalCheckpoint;

    public TRBSVUExperiment2Runner(Settings settings, TRBSVUExperiment1Runner contextual,
                                    int validationOrigins) {
        this(settings, contextual, validationOrigins, LAMBDA, W1_RADIUS, PCM_KAPPA,
                new TRBSVUPcmSolver(java.nio.file.Path.of(".venv-rsome", "Scripts", "python.exe"),
                        java.nio.file.Path.of("analysis", "trb_svu", "solve_pcm.py")), null, null);
    }

    public TRBSVUExperiment2Runner(Settings settings, TRBSVUExperiment1Runner contextual,
                                    int validationOrigins,
                                    TRBSVUValidationCheckpoint checkpoint) {
        this(settings, contextual, validationOrigins, LAMBDA, W1_RADIUS, PCM_KAPPA,
                new TRBSVUPcmSolver(java.nio.file.Path.of(".venv-rsome", "Scripts", "python.exe"),
                        java.nio.file.Path.of("analysis", "trb_svu", "solve_pcm.py")), checkpoint, null);
    }

    public TRBSVUExperiment2Runner(Settings settings, TRBSVUExperiment1Runner contextual,
                                    int validationOrigins,
                                    TRBSVUValidationCheckpoint checkpoint,
                                    TRBSVUFinalCheckpoint finalCheckpoint) {
        this(settings, contextual, validationOrigins, LAMBDA, W1_RADIUS, PCM_KAPPA,
                new TRBSVUPcmSolver(java.nio.file.Path.of(".venv-rsome", "Scripts", "python.exe"),
                        java.nio.file.Path.of("analysis", "trb_svu", "solve_pcm.py")),
                checkpoint, finalCheckpoint);
    }

    /** Package-local grid injection is for the isolated small self-check only. */
    TRBSVUExperiment2Runner(Settings settings, TRBSVUExperiment1Runner contextual,
                            int validationOrigins, double[] lambdaGrid, double[] w1Grid) {
        this(settings, contextual, validationOrigins, lambdaGrid, w1Grid, new double[]{1},
                new TRBSVUPcmSolver(java.nio.file.Path.of(".venv-rsome", "Scripts", "python.exe"),
                        java.nio.file.Path.of("analysis", "trb_svu", "solve_pcm.py")), null, null);
    }

    TRBSVUExperiment2Runner(Settings settings, TRBSVUExperiment1Runner contextual,
                            int validationOrigins, double[] lambdaGrid, double[] w1Grid,
                            double[] pcmGrid, TRBSVUPcmSolver pcmSolver) {
        this(settings, contextual, validationOrigins, lambdaGrid, w1Grid, pcmGrid, pcmSolver,
                null, null);
    }

    TRBSVUExperiment2Runner(Settings settings, TRBSVUExperiment1Runner contextual,
                            int validationOrigins, double[] lambdaGrid, double[] w1Grid,
                            double[] pcmGrid, TRBSVUPcmSolver pcmSolver,
                            TRBSVUValidationCheckpoint checkpoint) {
        this(settings, contextual, validationOrigins, lambdaGrid, w1Grid, pcmGrid, pcmSolver,
                checkpoint, null);
    }

    TRBSVUExperiment2Runner(Settings settings, TRBSVUExperiment1Runner contextual,
                            int validationOrigins, double[] lambdaGrid, double[] w1Grid,
                            double[] pcmGrid, TRBSVUPcmSolver pcmSolver,
                            TRBSVUValidationCheckpoint checkpoint,
                            TRBSVUFinalCheckpoint finalCheckpoint) {
        if (settings == null || contextual == null || validationOrigins < 1 || validationOrigins > 30)
            throw new IllegalArgumentException("Invalid Experiment 2 settings.");
        if (lambdaGrid.length == 0 || w1Grid.length == 0 || pcmGrid.length == 0 || pcmSolver == null)
            throw new IllegalArgumentException("Empty robustness grid.");
        this.settings = settings;
        this.contextual = contextual;
        this.validationOrigins = validationOrigins;
        this.lambdaGrid = lambdaGrid.clone();
        this.w1Grid = w1Grid.clone();
        this.pcmGrid = pcmGrid.clone();
        this.pcmSolver = pcmSolver;
        this.checkpoint = checkpoint;
        this.finalCheckpoint = finalCheckpoint;
    }

    /** Pass Experiment 1's validation-selected C*, never an OOS-selected family. */
    public Result run(TRBSVUSyntheticCase instance, ContextualChoice selected) throws Exception {
        if (instance.history.size() != 100 || selected == null)
            throw new IllegalArgumentException("Experiment 2 requires 100 history periods and C*.");
        Map<String, Method> methods = new LinkedHashMap<>();
        methods.put("RSAA", Method.RCSAA);
        methods.put("RCSAA", Method.RCSAA);
        methods.put("U-Chi2", Method.CHI_SQUARED);
        methods.put("C-Chi2", Method.CHI_SQUARED);
        methods.put("U-W1", Method.WASSERSTEIN);
        methods.put("C-W1", Method.WASSERSTEIN);
        Map<String, Double> chosen = new LinkedHashMap<>();
        Map<String, Double> validation = new LinkedHashMap<>();
        Map<String, Map<Double, Double>> curves = new LinkedHashMap<>();
        Map<String, Solution> solutions = new LinkedHashMap<>();
        Map<String, TRBSVUSolveMethods.Oos> oos = new LinkedHashMap<>();
        Map<String, List<TRBSVUSolveMethods.OosDraw>> oosDetails = new LinkedHashMap<>();
        Map<String, List<Sample>> finalWeights = new LinkedHashMap<>();
        Map<String, Double> effectiveContextBandwidth = new LinkedHashMap<>();
        List<TRBSVUValidationTrace> validationDetails = new ArrayList<>();
        for (var entry : methods.entrySet()) {
            String name = entry.getKey();
            Method method = entry.getValue();
            double[] grid = method == Method.WASSERSTEIN ? w1Grid : lambdaGrid;
            double bestParameter = Double.NaN, bestCost = Double.POSITIVE_INFINITY;
            double bestSd = Double.POSITIVE_INFINITY;
            Map<Double, Double> curve = new LinkedHashMap<>();
            for (double parameter : grid) {
                ValidationScore score = validate(instance, selected, name.startsWith("C-" ) || name.equals("RCSAA"),
                        method, parameter, name, validationDetails);
                curve.put(parameter, score.mean());
                System.out.println("Experiment 2 validated method=" + name + " parameter="
                        + parameter + " origins=" + validationOrigins + " cost=" + score.mean()
                        + " sd=" + score.sd());
                if (better(score, parameter, bestCost, bestSd, bestParameter)) {
                    bestCost = score.mean();
                    bestSd = score.sd();
                    bestParameter = parameter;
                }
            }
            if (!Double.isFinite(bestParameter))
                throw new IllegalStateException("All validation values invalid for " + name);
            boolean isContextual = name.startsWith("C-") || name.equals("RCSAA");
            WeightResult weightResult = isContextual
                    ? contextual.contextualWeightResult(instance, instance.history,
                            instance.testContext, selected)
                    : new WeightResult(TRBSVUScenarioWeights.equal(instance.history), Double.NaN);
            List<Sample> weighted = weightResult.weights();
            if (weighted.isEmpty()) throw new IllegalStateException("No final contextual support for " + name);
            Solution solution = finalCheckpoint == null ? null
                    : finalCheckpoint.load(name, bestParameter).orElse(null);
            if (solution == null) {
                System.out.printf(java.util.Locale.ROOT,
                        "RUN_CONTEXT experiment=2 stage=final method=%s candidate=%.17g%n",
                        name, bestParameter);
                long started = System.nanoTime();
                solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                        weighted, instance.testContext, method, bestParameter, settings);
                solution.solveTimeSec = (System.nanoTime() - started) / 1.0e9;
                requireCertified(solution, name, instance.params.I);
                if (finalCheckpoint != null) finalCheckpoint.save(name, bestParameter, solution);
            }
            requireCertified(solution, name, instance.params.I);
            chosen.put(name, bestParameter);
            validation.put(name, bestCost);
            curves.put(name, Collections.unmodifiableMap(new LinkedHashMap<>(curve)));
            solutions.put(name, solution);
            finalWeights.put(name, weighted);
            effectiveContextBandwidth.put(name, weightResult.effectiveBandwidth());
            TRBSVUSolveMethods.OosEvaluation evaluation = TRBSVUSolveMethods.evaluateDetailed(
                    instance.params, solution.y, instance.oos);
            oos.put(name, evaluation.summary());
            oosDetails.put(name, evaluation.draws());
            if (finalCheckpoint != null)
                finalCheckpoint.saveOos(name, evaluation.summary(), evaluation.draws());
        }
        for (String name : List.of("U-PCM", "C-PCM")) {
            boolean isContextual = name.startsWith("C-");
            double bestParameter = Double.NaN, bestCost = Double.POSITIVE_INFINITY;
            double bestSd = Double.POSITIVE_INFINITY;
            Map<Double, Double> curve = new LinkedHashMap<>();
            for (double kappa : pcmGrid) {
                ValidationScore score = validatePcm(instance, selected, isContextual, kappa,
                        name, validationDetails);
                curve.put(kappa, score.mean());
                System.out.println("Experiment 2 validated method=" + name + " kappa="
                        + kappa + " origins=" + validationOrigins + " cost=" + score.mean()
                        + " sd=" + score.sd());
                if (better(score, kappa, bestCost, bestSd, bestParameter)) {
                    bestCost = score.mean();
                    bestSd = score.sd();
                    bestParameter = kappa;
                }
            }
            if (!Double.isFinite(bestParameter))
                throw new IllegalStateException("All PCM validation values invalid for " + name);
            WeightResult weightResult = isContextual
                    ? contextual.contextualWeightResult(instance, instance.history,
                            instance.testContext, selected)
                    : new WeightResult(TRBSVUScenarioWeights.equal(instance.history), Double.NaN);
            List<Sample> weighted = weightResult.weights();
            Solution solution = finalCheckpoint == null ? null
                    : finalCheckpoint.load(name, bestParameter).orElse(null);
            if (solution == null) {
                System.out.printf(java.util.Locale.ROOT,
                        "RUN_CONTEXT experiment=2 stage=final method=%s candidate=%.17g%n",
                        name, bestParameter);
                solution = pcmSolver.solve(instance.params, weighted, bestParameter, settings);
                requireCertified(solution, name, instance.params.I);
                if (finalCheckpoint != null) finalCheckpoint.save(name, bestParameter, solution);
            }
            requireCertified(solution, name, instance.params.I);
            chosen.put(name, bestParameter);
            validation.put(name, bestCost);
            curves.put(name, Collections.unmodifiableMap(new LinkedHashMap<>(curve)));
            solutions.put(name, solution);
            finalWeights.put(name, weighted);
            effectiveContextBandwidth.put(name, weightResult.effectiveBandwidth());
            TRBSVUSolveMethods.OosEvaluation evaluation = TRBSVUSolveMethods.evaluateDetailed(
                    instance.params, solution.y, instance.oos);
            oos.put(name, evaluation.summary());
            oosDetails.put(name, evaluation.draws());
            if (finalCheckpoint != null)
                finalCheckpoint.saveOos(name, evaluation.summary(), evaluation.draws());
        }
        return new Result(orderedCopy(solutions), orderedCopy(oos), orderedCopy(oosDetails),
                orderedCopy(finalWeights), orderedCopy(effectiveContextBandwidth),
                orderedCopy(chosen), orderedCopy(validation), orderedCopy(curves),
                List.copyOf(validationDetails));
    }

    private ValidationScore validatePcm(TRBSVUSyntheticCase instance, ContextualChoice selected,
                                        boolean isContextual, double kappa, String methodName,
                                        List<TRBSVUValidationTrace> details) throws Exception {
        double[] realizedCosts = new double[validationOrigins];
        for (int t = 70; t < 70 + validationOrigins; t++) {
            if (checkpoint != null) {
                var restored = checkpoint.load(methodName, kappa, t);
                if (restored.isPresent()) {
                    TRBSVUValidationTrace trace = restored.get();
                    verifyCheckpointWindow(instance, trace, t);
                    if (!trace.certifiedOptimal())
                        throw new IllegalStateException("Uncertified PCM validation checkpoint: " + methodName);
                    details.add(trace);
                    realizedCosts[t - 70] = trace.realizedValidationCost();
                    continue;
                }
            }
            TRBSVUSyntheticCase.ValidationWindow window = instance.validationWindow(t, 70);
            WeightResult weightResult = isContextual
                    ? contextual.contextualWeightResult(instance, window.train(),
                            window.realized().theta, selected)
                    : new WeightResult(TRBSVUScenarioWeights.equal(window.train()), Double.NaN);
            List<Sample> weighted = weightResult.weights();
            if (weighted.isEmpty()) return ValidationScore.invalid();
            System.out.printf(java.util.Locale.ROOT,
                    "RUN_CONTEXT experiment=2 stage=validation method=%s candidate=%.17g origin=%d trainingStart=%d trainingEnd=%d%n",
                    methodName, kappa, t, window.train().get(0).period.tIndex,
                    window.train().get(window.train().size() - 1).period.tIndex);
            Solution solution = pcmSolver.solve(instance.params, weighted, kappa, settings);
            if (solution.y == null || !solution.certifiedOptimal)
                throw new IllegalStateException("Uncertified lifted-affine PCM solve at origin "
                        + t + ", kappa=" + kappa + ", status=" + solution.solverStatus);
            double realized = TRBSVUSolveMethods.realizedCost(instance.params, solution.y,
                    window.realized().demand());
            realizedCosts[t - 70] = realized;
            TRBSVUValidationTrace trace = trace(methodName, kappa, t, window.train(), weighted,
                    weightResult.effectiveBandwidth(), solution, realized);
            details.add(trace);
            saveCheckpoint(trace);
        }
        return ValidationScore.from(realizedCosts);
    }

    private static void requireCertified(Solution solution, String method, int carriers) {
        if (solution.y == null || solution.y.length != carriers || !solution.certifiedOptimal
                || !Double.isFinite(solution.objValue)) {
            throw new IllegalStateException("Uncertified Experiment 2 final solve for " + method
                    + ", status=" + solution.solverStatus + ", gap=" + solution.relativeGap);
        }
    }

    private ValidationScore validate(TRBSVUSyntheticCase instance, ContextualChoice selected,
                                     boolean isContextual, Method method, double parameter,
                                     String methodName, List<TRBSVUValidationTrace> details) throws Exception {
        double[] realizedCosts = new double[validationOrigins];
        for (int t = 70; t < 70 + validationOrigins; t++) {
            if (checkpoint != null) {
                var restored = checkpoint.load(methodName, parameter, t);
                if (restored.isPresent()) {
                    TRBSVUValidationTrace trace = restored.get();
                    verifyCheckpointWindow(instance, trace, t);
                    if (!trace.certifiedOptimal())
                        throw new IllegalStateException("Uncertified validation checkpoint: " + methodName);
                    details.add(trace);
                    realizedCosts[t - 70] = trace.realizedValidationCost();
                    continue;
                }
            }
            TRBSVUSyntheticCase.ValidationWindow window = instance.validationWindow(t, 70);
            CovariateVector query = window.realized().theta;
            WeightResult weightResult = isContextual
                    ? contextual.contextualWeightResult(instance, window.train(), query, selected)
                    : new WeightResult(TRBSVUScenarioWeights.equal(window.train()), Double.NaN);
            List<Sample> weighted = weightResult.weights();
            if (weighted.isEmpty()) return ValidationScore.invalid();
            System.out.printf(java.util.Locale.ROOT,
                    "RUN_CONTEXT experiment=2 stage=validation method=%s candidate=%.17g origin=%d trainingStart=%d trainingEnd=%d%n",
                    methodName, parameter, t, window.train().get(0).period.tIndex,
                    window.train().get(window.train().size() - 1).period.tIndex);
            long started = System.nanoTime();
            Solution solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                    weighted, query, method, parameter, settings);
            solution.solveTimeSec = (System.nanoTime() - started) / 1.0e9;
            if (solution.y == null || !solution.certifiedOptimal)
                throw new IllegalStateException("Uncertified validation solve at origin " + t
                        + ", method=" + method + ", parameter=" + parameter
                        + ", status=" + solution.solverStatus + ", gap=" + solution.relativeGap);
            double realized = TRBSVUSolveMethods.realizedCost(instance.params, solution.y,
                    window.realized().demand());
            realizedCosts[t - 70] = realized;
            TRBSVUValidationTrace trace = trace(methodName, parameter, t, window.train(), weighted,
                    weightResult.effectiveBandwidth(), solution, realized);
            details.add(trace);
            saveCheckpoint(trace);
        }
        return ValidationScore.from(realizedCosts);
    }

    private void saveCheckpoint(TRBSVUValidationTrace trace) throws Exception {
        if (checkpoint != null) checkpoint.save(trace);
    }

    private static void verifyCheckpointWindow(TRBSVUSyntheticCase instance,
                                               TRBSVUValidationTrace trace, int origin) {
        TRBSVUSyntheticCase.ValidationWindow expected = instance.validationWindow(origin, 70);
        int start = expected.train().get(0).period.tIndex;
        int end = expected.train().get(expected.train().size() - 1).period.tIndex;
        if (trace.trainingStart() != start || trace.trainingEnd() != end)
            throw new IllegalStateException("Validation checkpoint uses a different training window at origin "
                    + origin + ".");
        double[] decision = trace.decision();
        if (decision == null || decision.length != instance.params.I)
            throw new IllegalStateException("Validation checkpoint has an invalid decision at origin "
                    + origin + ".");
    }

    private static TRBSVUValidationTrace trace(String method, double parameter, int origin,
                                               List<Sample> training, List<Sample> weighted,
                                               double effectiveBandwidth, Solution solution,
                                               double realizedCost) {
        return new TRBSVUValidationTrace(method, parameter, origin,
                training.get(0).period.tIndex,
                training.get(training.size() - 1).period.tIndex,
                effectiveBandwidth, weighted.size(),
                TRBSVUExperiment1Runner.positiveCount(weighted),
                TRBSVUExperiment1Runner.ess(weighted), solution.objValue,
                solution.solverStatus, solution.bestBound, solution.relativeGap,
                solution.solveTimeSec, solution.certifiedOptimal, solution.y, realizedCost);
    }

    private static <K, V> Map<K, V> orderedCopy(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private record ValidationScore(double mean, double sd) {
        static ValidationScore from(double[] costs) {
            TRBSVUStatistics.Summary summary = TRBSVUStatistics.summarize(costs);
            return new ValidationScore(summary.mean(), summary.sampleStandardDeviation());
        }

        static ValidationScore invalid() {
            return new ValidationScore(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        }
    }

    private static boolean better(ValidationScore score, double parameter,
                                  double incumbentMean, double incumbentSd,
                                  double incumbentParameter) {
        if (!Double.isFinite(score.mean())) return false;
        return TRBSVUStatistics.better(score.mean(), score.sd(), parameter,
                incumbentMean, incumbentSd, incumbentParameter);
    }
}
