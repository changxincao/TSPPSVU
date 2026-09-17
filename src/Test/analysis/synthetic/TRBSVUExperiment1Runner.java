package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Sample;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Experiment 1: one pre-generated case, train-only rolling validation, shared OOS. */
public final class TRBSVUExperiment1Runner {
    public static final double[] RETENTION = {0.4, 0.6, 0.8, 1.0};
    public static final double[] BANDWIDTH = {0.1, 0.5, 1, 3, 5, 10, 30, 50, 100};

    /** A separately implemented forest may supply same-leaf normalized weights. */
    public interface ForestWeights {
        List<Sample> weights(List<Sample> training, CovariateVector query, long seed) throws Exception;
    }

    public record ContextualChoice(String family, double bandwidth, double validationCost,
                                   double validationSd,
                                   List<Double> bandwidthOrder) {
        public ContextualChoice {
            bandwidthOrder = List.copyOf(bandwidthOrder);
        }

        public ContextualChoice(String family, double bandwidth, double validationCost) {
            this(family, bandwidth, validationCost, Double.NaN,
                    Double.isFinite(bandwidth) ? List.of(bandwidth) : List.of());
        }

        public ContextualChoice(String family, double bandwidth, double validationCost,
                                List<Double> bandwidthOrder) {
            this(family, bandwidth, validationCost, Double.NaN, bandwidthOrder);
        }
    }
    public record WeightResult(List<Sample> weights, double effectiveBandwidth) { }
    public record Result(Map<String, Solution> decisions,
                         Map<String, TRBSVUSolveMethods.Oos> oos,
                         Map<String, List<TRBSVUSolveMethods.OosDraw>> oosDetails,
                         Map<String, List<Sample>> finalWeights,
                         Map<String, Double> validationCost,
                         Map<String, Map<Double, Double>> validationCurve,
                         List<TRBSVUValidationTrace> validationDetails,
                         double retention, Map<Kernel, Double> bandwidth,
                         Map<Kernel, Double> finalEffectiveBandwidth,
                         ContextualChoice selectedContextual) { }

    private final Settings settings;
    private final ForestWeights forest;
    private final int validationOrigins;
    private final TRBSVUValidationCheckpoint checkpoint;
    private final TRBSVUFinalCheckpoint finalCheckpoint;

    public TRBSVUExperiment1Runner(Settings settings, ForestWeights forest,
                                    int validationOrigins) {
        this(settings, forest, validationOrigins, null, null);
    }

    public TRBSVUExperiment1Runner(Settings settings, ForestWeights forest,
                                    int validationOrigins,
                                    TRBSVUValidationCheckpoint checkpoint) {
        this(settings, forest, validationOrigins, checkpoint, null);
    }

    public TRBSVUExperiment1Runner(Settings settings, ForestWeights forest,
                                    int validationOrigins,
                                    TRBSVUValidationCheckpoint checkpoint,
                                    TRBSVUFinalCheckpoint finalCheckpoint) {
        if (settings == null || forest == null || validationOrigins < 1)
            throw new IllegalArgumentException("Experiment 1 needs settings, RF weights and validation origins.");
        this.settings = settings;
        this.forest = forest;
        this.validationOrigins = validationOrigins;
        this.checkpoint = checkpoint;
        this.finalCheckpoint = finalCheckpoint;
    }

    public Result run(TRBSVUSyntheticCase instance) throws Exception {
        return run(instance, Set.of("D", "SAA-All", "Tuned-SAA", "CSAA-Exp",
                "CSAA-Gau", "CSAA-Epa", "CSAA-Tri", "RF-CSAA"));
    }

    /** Runs only the requested Experiment 1 methods; each method remains a complete
     * validation -> parameter selection -> final solve -> OOS evaluation chain. */
    public Result run(TRBSVUSyntheticCase instance, Set<String> requestedMethods) throws Exception {
        requireFormalValidationHistory(instance);
        if (instance.oos.isEmpty())
            throw new IllegalArgumentException("Experiment 1 requires OOS draws.");
        if (requestedMethods == null || requestedMethods.isEmpty())
            throw new IllegalArgumentException("No Experiment 1 methods requested.");
        Set<String> known = Set.of("D", "SAA-All", "Tuned-SAA", "CSAA-Exp",
                "CSAA-Gau", "CSAA-Epa", "CSAA-Tri", "RF-CSAA");
        if (!known.containsAll(requestedMethods))
            throw new IllegalArgumentException("Unknown Experiment 1 method(s): " + requestedMethods);
        Map<String, Double> validation = new LinkedHashMap<>();
        Map<String, Map<Double, Double>> curves = new LinkedHashMap<>();
        List<TRBSVUValidationTrace> validationDetails = new ArrayList<>();
        if (requestedMethods.contains("D")) {
            ValidationScore score = validate(instance, 0, null, validationDetails);
            validation.put("D", score.mean());
            curves.put("D", Map.of(Double.NaN, score.mean()));
        }
        if (requestedMethods.contains("SAA-All")) {
            ValidationScore score = validate(instance, 1, null, validationDetails);
            validation.put("SAA-All", score.mean());
            curves.put("SAA-All", Map.of(Double.NaN, score.mean()));
        }
        double retention = Double.NaN;
        if (requestedMethods.contains("Tuned-SAA")) {
            Tuning tuning = tune(RETENTION,
                    fraction -> validate(instance, 2, fraction, validationDetails));
            retention = tuning.parameter();
            validation.put("Tuned-SAA", tuning.cost());
            curves.put("Tuned-SAA", tuning.curve());
        }
        EnumMap<Kernel, Double> selectedBandwidth = new EnumMap<>(Kernel.class);
        EnumMap<Kernel, List<Double>> bandwidthOrder = new EnumMap<>(Kernel.class);
        EnumMap<Kernel, Double> contextualSd = new EnumMap<>(Kernel.class);
        for (Kernel family : Kernel.values()) {
            if (!requestedMethods.contains(name(family))) continue;
            Tuning tuning = tune(BANDWIDTH, candidate ->
                    validate(instance, 3, new ContextualChoice(family.name(), candidate, 0.0),
                            validationDetails));
            double bandwidth = tuning.parameter();
            selectedBandwidth.put(family, bandwidth);
            bandwidthOrder.put(family, tuning.order());
            contextualSd.put(family, tuning.sd());
            validation.put(name(family), tuning.cost());
            curves.put(name(family), tuning.curve());
        }
        ContextualChoice chosen = null;
        for (Kernel family : Kernel.values()) {
            if (!requestedMethods.contains(name(family))) continue;
            String methodName = name(family);
            if (chosen == null || TRBSVUStatistics.better(validation.get(methodName), contextualSd.get(family),
                    selectedBandwidth.get(family), chosen.validationCost(),
                    selectedContextualSd(chosen, contextualSd), chosen.bandwidth()))
                chosen = new ContextualChoice(family.name(), selectedBandwidth.get(family),
                        validation.get(methodName), contextualSd.get(family),
                        bandwidthOrder.get(family));
        }
        if (requestedMethods.contains("RF-CSAA")) {
            ValidationScore rfScore = validate(instance, 4, null, validationDetails);
            validation.put("RF-CSAA", rfScore.mean());
            curves.put("RF-CSAA", Map.of(Double.NaN, rfScore.mean()));
            if (chosen == null || TRBSVUStatistics.better(rfScore.mean(), rfScore.sd(),
                    Double.POSITIVE_INFINITY, chosen.validationCost(),
                    selectedContextualSd(chosen, contextualSd), chosen.bandwidth()))
                chosen = new ContextualChoice("RF", Double.NaN, validation.get("RF-CSAA"),
                        rfScore.sd(), List.of());
        }

        Map<String, List<Sample>> finalWeights = new LinkedHashMap<>();
        EnumMap<Kernel, Double> finalBandwidth = new EnumMap<>(selectedBandwidth);
        if (requestedMethods.contains("D"))
            finalWeights.put("D", TRBSVUScenarioWeights.arithmeticMean(instance.history));
        if (requestedMethods.contains("SAA-All"))
            finalWeights.put("SAA-All", TRBSVUScenarioWeights.equal(instance.history));
        if (requestedMethods.contains("Tuned-SAA"))
            finalWeights.put("Tuned-SAA", TRBSVUScenarioWeights.recent(instance.history, retention));
        for (Kernel family : Kernel.values()) {
            if (!requestedMethods.contains(name(family))) continue;
            WeightResult result = firstValidByValidationRank(instance.history,
                    instance.testContext, family, bandwidthOrder.get(family));
            finalBandwidth.put(family, result.effectiveBandwidth());
            finalWeights.put(name(family), result.weights());
        }
        if (requestedMethods.contains("RF-CSAA"))
            finalWeights.put("RF-CSAA", forest.weights(instance.history,
                    instance.testContext, forestSeed(instance, instance.history)));
        System.out.println("Experiment 1 validation selected gamma=" + retention
                + " contextual=" + chosen + " B=" + selectedBandwidth);

        Map<String, Solution> solutions = new LinkedHashMap<>();
        Map<String, TRBSVUSolveMethods.Oos> oos = new LinkedHashMap<>();
        Map<String, List<TRBSVUSolveMethods.OosDraw>> oosDetails = new LinkedHashMap<>();
        Map<String, Double> finalParameters = new LinkedHashMap<>();
        finalParameters.put("Tuned-SAA", retention);
        for (Kernel family : Kernel.values())
            finalParameters.put(name(family), selectedBandwidth.get(family));
        for (var method : finalWeights.entrySet()) {
            double selectedParameter = finalParameters.getOrDefault(method.getKey(), Double.NaN);
            Solution solved = finalCheckpoint == null ? null
                    : finalCheckpoint.load(method.getKey(), selectedParameter).orElse(null);
            if (solved == null) {
                System.out.printf(java.util.Locale.ROOT,
                        "RUN_CONTEXT experiment=1 stage=final method=%s candidate=%.17g%n",
                        method.getKey(), selectedParameter);
                long started = System.nanoTime();
                solved = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                        method.getValue(), instance.testContext, Method.NOMINAL, 0.0, settings);
                solved.solveTimeSec = (System.nanoTime() - started) / 1.0e9;
                requireCertified(solved, method.getKey(), instance.params.I);
                if (finalCheckpoint != null)
                    finalCheckpoint.save(method.getKey(), selectedParameter, solved);
            }
            requireCertified(solved, method.getKey(), instance.params.I);
            solutions.put(method.getKey(), solved);
            TRBSVUSolveMethods.OosEvaluation evaluation = TRBSVUSolveMethods.evaluateDetailed(
                    instance.params, solved.y, instance.oos);
            oos.put(method.getKey(), evaluation.summary());
            oosDetails.put(method.getKey(), evaluation.draws());
            if (finalCheckpoint != null)
                finalCheckpoint.saveOos(method.getKey(), evaluation.summary(), evaluation.draws());
        }
        return new Result(orderedCopy(solutions), orderedCopy(oos), orderedCopy(oosDetails),
                orderedCopy(finalWeights), orderedCopy(validation), orderedCopy(curves),
                List.copyOf(validationDetails),
                retention, Map.copyOf(selectedBandwidth), Map.copyOf(finalBandwidth), chosen);
    }

    private static void requireCertified(Solution solution, String method, int carriers) {
        if (solution.y == null || solution.y.length != carriers || !solution.certifiedOptimal
                || !Double.isFinite(solution.objValue)) {
            throw new IllegalStateException("Uncertified Experiment 1 final solve for " + method
                    + ", status=" + solution.solverStatus + ", gap=" + solution.relativeGap);
        }
    }

    public List<Sample> contextualWeights(TRBSVUSyntheticCase instance, List<Sample> training,
                                          CovariateVector query, ContextualChoice choice) throws Exception {
        return contextualWeightResult(instance, training, query, choice).weights();
    }

    public WeightResult contextualWeightResult(TRBSVUSyntheticCase instance, List<Sample> training,
                                                CovariateVector query,
                                                ContextualChoice choice) throws Exception {
        if ("RF".equals(choice.family()))
            return new WeightResult(forest.weights(training, query,
                    forestSeed(instance, training)), Double.NaN);
        List<Double> order = choice.bandwidthOrder().isEmpty()
                ? List.of(choice.bandwidth()) : choice.bandwidthOrder();
        return firstValidByValidationRank(training, query,
                Kernel.valueOf(choice.family()), order);
    }

    private ValidationScore validate(TRBSVUSyntheticCase instance, int kind, Object parameter,
                                     List<TRBSVUValidationTrace> details) throws Exception {
        double[] realizedCosts = new double[validationOrigins];
        boolean valid = true;
        int firstOrigin = TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS;
        for (int t = firstOrigin; t < firstOrigin + validationOrigins; t++) {
            String methodName = validationMethodName(kind, parameter);
            double candidate = validationCandidate(kind, parameter);
            if (checkpoint != null) {
                var restored = checkpoint.load(methodName, candidate, t);
                if (restored.isPresent()) {
                    TRBSVUValidationTrace trace = restored.get();
                    verifyCheckpointWindow(instance, trace, t);
                    details.add(trace);
                    if ("EMPTY_KERNEL_SUPPORT".equals(trace.solverStatus())) valid = false;
                    else {
                        if (!trace.certifiedOptimal())
                            throw new IllegalStateException("Uncertified validation checkpoint: " + methodName);
                        realizedCosts[t - firstOrigin] = trace.realizedValidationCost();
                    }
                    continue;
                }
            }
            TRBSVUSyntheticCase.ValidationWindow window = instance.validationWindow(t,
                    TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS);
            List<Sample> training = window.train();
            CovariateVector query = window.realized().theta;
            List<Sample> weighted = switch (kind) {
                case 0 -> TRBSVUScenarioWeights.arithmeticMean(training);
                case 1 -> TRBSVUScenarioWeights.equal(training);
                case 2 -> TRBSVUScenarioWeights.recent(training, (double) parameter);
                case 3 -> TRBSVUScenarioWeights.kernel(training, query,
                        Kernel.valueOf(((ContextualChoice) parameter).family()),
                        ((ContextualChoice) parameter).bandwidth());
                case 4 -> forest.weights(training, query, forestSeed(instance, training));
                default -> throw new IllegalArgumentException("Unknown Experiment 1 method.");
            };
            if (weighted.isEmpty()) {
                valid = false;
                TRBSVUValidationTrace trace = emptyTrace(kind, parameter, t, training);
                details.add(trace);
                saveCheckpoint(trace);
                continue;
            }
            long started = System.nanoTime();
            System.out.printf(java.util.Locale.ROOT,
                    "RUN_CONTEXT experiment=1 stage=validation method=%s candidate=%.17g origin=%d trainingStart=%d trainingEnd=%d%n",
                    methodName, candidate, t, training.get(0).period.tIndex,
                    training.get(training.size() - 1).period.tIndex);
            Solution solved = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                    weighted, query, Method.NOMINAL, 0.0, settings);
            solved.solveTimeSec = (System.nanoTime() - started) / 1.0e9;
            if (solved.y == null || !solved.certifiedOptimal)
                throw new IllegalStateException("Uncertified Experiment 1 validation at origin "
                        + t + ", kind=" + kind + ", status=" + solved.solverStatus
                        + ", gap=" + solved.relativeGap);
            double realized = TRBSVUSolveMethods.realizedCost(instance.params, solved.y,
                    window.realized().demand());
            realizedCosts[t - firstOrigin] = realized;
            TRBSVUValidationTrace trace = trace(kind, parameter, t, training, weighted, solved, realized);
            details.add(trace);
            saveCheckpoint(trace);
        }
        TRBSVUStatistics.Summary summary = valid
                ? TRBSVUStatistics.summarize(realizedCosts) : null;
        double average = valid ? summary.mean() : Double.POSITIVE_INFINITY;
        System.out.println("Experiment 1 validated kind=" + kind + " parameter=" + parameter
                + " origins=" + validationOrigins + " cost=" + average);
        return valid ? new ValidationScore(summary.mean(), summary.sampleStandardDeviation())
                : new ValidationScore(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    }

    private void saveCheckpoint(TRBSVUValidationTrace trace) throws Exception {
        if (checkpoint != null) checkpoint.save(trace);
    }

    private static void verifyCheckpointWindow(TRBSVUSyntheticCase instance,
                                               TRBSVUValidationTrace trace, int origin) {
        TRBSVUSyntheticCase.ValidationWindow expected = instance.validationWindow(origin,
                TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS);
        int start = expected.train().get(0).period.tIndex;
        int end = expected.train().get(expected.train().size() - 1).period.tIndex;
        if (trace.trainingStart() != start || trace.trainingEnd() != end)
            throw new IllegalStateException("Validation checkpoint uses a different training window at origin "
                    + origin + ".");
        double[] decision = trace.decision();
        if (!"EMPTY_KERNEL_SUPPORT".equals(trace.solverStatus())
                && (decision == null || decision.length != instance.params.I))
            throw new IllegalStateException("Validation checkpoint has an invalid decision at origin "
                    + origin + ".");
    }

    private static String validationMethodName(int kind, Object parameter) {
        return switch (kind) {
            case 0 -> "D";
            case 1 -> "SAA-All";
            case 2 -> "Tuned-SAA";
            case 3 -> name(Kernel.valueOf(((ContextualChoice) parameter).family()));
            case 4 -> "RF-CSAA";
            default -> throw new IllegalArgumentException("Unknown Experiment 1 method.");
        };
    }

    private static double validationCandidate(int kind, Object parameter) {
        return kind == 2 ? (double) parameter
                : kind == 3 ? ((ContextualChoice) parameter).bandwidth() : Double.NaN;
    }

    private static TRBSVUValidationTrace emptyTrace(int kind, Object parameter, int origin,
                                                     List<Sample> training) {
        String method = name(Kernel.valueOf(((ContextualChoice) parameter).family()));
        double candidate = ((ContextualChoice) parameter).bandwidth();
        return new TRBSVUValidationTrace(method, candidate, origin,
                training.get(0).period.tIndex,
                training.get(training.size() - 1).period.tIndex,
                candidate, training.size(), 0, Double.NaN, Double.NaN,
                "EMPTY_KERNEL_SUPPORT", Double.NaN, Double.NaN, 0.0,
                false, null, Double.NaN);
    }

    private static WeightResult firstValidByValidationRank(List<Sample> training,
                                                           CovariateVector query,
                                                           Kernel family,
                                                           List<Double> rankedCandidates) {
        for (double candidate : rankedCandidates) {
            List<Sample> result = TRBSVUScenarioWeights.kernel(training, query, family, candidate);
            if (!result.isEmpty()) return new WeightResult(result, candidate);
        }
        throw new IllegalStateException("No validation-valid bandwidth supports query for " + family);
    }

    private static TRBSVUValidationTrace trace(int kind, Object parameter, int origin,
                                               List<Sample> training, List<Sample> weighted,
                                               Solution solution, double realizedCost) {
        String method = switch (kind) {
            case 0 -> "D";
            case 1 -> "SAA-All";
            case 2 -> "Tuned-SAA";
            case 3 -> name(Kernel.valueOf(((ContextualChoice) parameter).family()));
            case 4 -> "RF-CSAA";
            default -> throw new IllegalArgumentException("Unknown Experiment 1 method.");
        };
        double candidate = kind == 2 ? (double) parameter
                : kind == 3 ? ((ContextualChoice) parameter).bandwidth() : Double.NaN;
        double effective = kind == 3 ? candidate : Double.NaN;
        return new TRBSVUValidationTrace(method, candidate, origin,
                training.get(0).period.tIndex,
                training.get(training.size() - 1).period.tIndex,
                effective, weighted.size(), positiveCount(weighted), ess(weighted),
                solution.objValue, solution.solverStatus, solution.bestBound,
                solution.relativeGap, solution.solveTimeSec, solution.certifiedOptimal,
                solution.y, realizedCost);
    }

    static int positiveCount(List<Sample> weighted) {
        int count = 0;
        for (Sample sample : weighted) if (sample.weight > 0.0) count++;
        return count;
    }

    static double ess(List<Sample> weighted) {
        double sum = 0.0, squares = 0.0;
        for (Sample sample : weighted) {
            sum += sample.weight;
            squares += sample.weight * sample.weight;
        }
        return squares > 0.0 ? sum * sum / squares : Double.NaN;
    }

    private static String name(Kernel family) {
        return switch (family) {
            case EXPONENTIAL -> "CSAA-Exp";
            case GAUSSIAN -> "CSAA-Gau";
            case EPANECHNIKOV -> "CSAA-Epa";
            case TRIANGULAR -> "CSAA-Tri";
        };
    }

    static long forestSeed(TRBSVUSyntheticCase instance, List<Sample> training) {
        return instance.seeds.contexts()
                + training.get(training.size() - 1).period.tIndex + 1L;
    }

    private void requireFormalValidationHistory(TRBSVUSyntheticCase instance) {
        int expected = TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS + validationOrigins;
        if (instance.history.size() < expected) {
            throw new IllegalArgumentException("Experiment 1 requires at least " + expected
                    + " history periods for "
                    + TRBSVUFormalProtocol.VALIDATION_TRAINING_PERIODS
                    + " training periods and " + validationOrigins + " validation origins.");
        }
    }

    private interface CandidateCost { ValidationScore evaluate(double value) throws Exception; }

    private record ValidationScore(double mean, double sd) { }

    private record Tuning(double parameter, double cost, double sd,
                          Map<Double, Double> curve, List<Double> order) { }

    private static Tuning tune(double[] grid, CandidateCost cost) throws Exception {
        double choice = Double.NaN, minimum = Double.POSITIVE_INFINITY, selectedSd = Double.POSITIVE_INFINITY;
        Map<Double, Double> curve = new LinkedHashMap<>();
        Map<Double, ValidationScore> scores = new LinkedHashMap<>();
        for (double candidate : grid) {
            ValidationScore score = cost.evaluate(candidate);
            scores.put(candidate, score);
            curve.put(candidate, score.mean());
            if (Double.isFinite(score.mean())
                    && TRBSVUStatistics.better(score.mean(), score.sd(), candidate,
                            minimum, selectedSd, choice)) {
                choice = candidate;
                minimum = score.mean();
                selectedSd = score.sd();
            }
        }
        if (!Double.isFinite(choice)) throw new IllegalStateException("All validation candidates invalid.");
        List<Double> order = scores.entrySet().stream()
                .filter(entry -> Double.isFinite(entry.getValue().mean()))
                .sorted(Comparator.comparingDouble((Map.Entry<Double, ValidationScore> entry)
                                -> entry.getValue().mean())
                        .thenComparingDouble(entry -> entry.getValue().sd())
                        .thenComparingDouble(Map.Entry::getKey))
                .map(Map.Entry::getKey).toList();
        return new Tuning(choice, minimum, selectedSd, orderedCopy(curve), order);
    }

    private static double selectedContextualSd(ContextualChoice choice,
                                               Map<Kernel, Double> sd) {
        return Double.isFinite(choice.validationSd()) ? choice.validationSd()
                : "RF".equals(choice.family()) ? Double.POSITIVE_INFINITY
                : sd.get(Kernel.valueOf(choice.family()));
    }

    private static <K, V> Map<K, V> orderedCopy(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
