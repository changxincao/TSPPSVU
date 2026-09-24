package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.OosDraw;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

/** Machine-readable, long-format audit output for synthetic Experiments 1--2. */
public final class TRBSVUResultWriter {
    private TRBSVUResultWriter() { }

    public static void writeInstance(Path directory, TRBSVUSyntheticCase instance) throws Exception {
        Files.createDirectories(directory);
        try (BufferedWriter out = writer(directory.resolve("carriers.csv"))) {
            out.write("carrier_index,carrier,mqc,penalty,total_capacity");
            out.newLine();
            for (int i = 0; i < instance.params.I; i++) {
                out.write(String.format(Locale.ROOT, "%d,%s,%.17g,%.17g,%.17g%n", i,
                        csv(instance.params.carriers.get(i)), instance.params.p[i],
                        instance.params.h[i], instance.params.M[i]));
            }
        }
        try (BufferedWriter out = writer(directory.resolve("lanes.csv"))) {
            out.write("lane_index,lane,spot_cost");
            out.newLine();
            for (int j = 0; j < instance.params.J; j++) {
                out.write(String.format(Locale.ROOT, "%d,%s,%.17g%n", j,
                        csv(instance.lanes.get(j)), instance.params.e[j]));
            }
        }
        try (BufferedWriter out = writer(directory.resolve("carrier_lane.csv"))) {
            out.write("carrier_index,lane_index,eligible,rate,capacity");
            out.newLine();
            for (int i = 0; i < instance.params.I; i++) {
                for (int j = 0; j < instance.params.J; j++) {
                    out.write(String.format(Locale.ROOT, "%d,%d,%s,%s,%.17g%n", i, j,
                            instance.params.eligible[i][j],
                            instance.params.eligible[i][j]
                                    ? String.format(Locale.ROOT, "%.17g", instance.params.r[i][j])
                                    : "NA",
                            instance.params.q[i][j]));
                }
            }
        }
        writeSamples(directory.resolve("history.csv"), instance.history, instance.params.J);
        writeSamples(directory.resolve("oos_demands.csv"), instance.oos, instance.params.J);
        try (BufferedWriter out = writer(directory.resolve("test_context.csv"))) {
            out.write("dimension,value");
            out.newLine();
            for (int k = 0; k < instance.testContext.dim(); k++)
                out.write(String.format(Locale.ROOT, "%d,%.17g%n", k,
                        instance.testContext.values()[k]));
        }
    }

    public static void writeDgpParameters(Path directory, Parameters parameters,
                                          Distribution distribution, Volatility volatility)
            throws Exception {
        Files.createDirectories(directory);
        double[] base = parameters.base(), market = parameters.market();
        double[] trend = parameters.trend(), promotion = parameters.promotion();
        double[] attention = parameters.attention();
        double[] quantile = parameters.volatilityQuantile();
        double[] commonLoading = parameters.commonLoading();
        double[] cv = parameters.volatilityParameters(volatility);
        double[] typical = parameters.typicalDemand();
        try (BufferedWriter out = writer(directory.resolve("dgp_parameters.csv"))) {
            out.write("lane_index,distribution,volatility,base,market_coefficient,trend_coefficient,"
                    + "promotion_coefficient,attention_coefficient,volatility_quantile,cv,"
                    + "common_factor_loading,common_variance_share,context_coefficient_scale,"
                    + "context_structure,typical_demand");
            out.newLine();
            for (int j = 0; j < parameters.laneCount(); j++) {
                out.write(String.format(Locale.ROOT,
                        "%d,%s,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%s,%.17g%n",
                        j, distribution, volatility, base[j], market[j], trend[j], promotion[j],
                        attention[j], quantile[j], cv[j], commonLoading[j],
                        commonLoading[j] * commonLoading[j], parameters.contextCoefficientScale(),
                        parameters.contextStructure(), typical[j]));
            }
        }
    }

    private static void writeSamples(Path file, List<Sample> samples, int lanes) throws Exception {
        try (BufferedWriter out = writer(file)) {
            out.write("row_index,sample_id,period_index,weight");
            int contextDimension = samples.get(0).theta.dim();
            for (int k = 0; k < contextDimension; k++) out.write(",context_" + k);
            for (int j = 0; j < lanes; j++) out.write(",demand_" + j);
            out.newLine();
            for (int s = 0; s < samples.size(); s++) {
                Sample sample = samples.get(s);
                out.write(String.format(Locale.ROOT, "%d,%d,%d,%.17g", s, sample.id,
                        sample.period.tIndex, sample.weight));
                for (double value : sample.theta.values())
                    out.write(String.format(Locale.ROOT, ",%.17g", value));
                for (double value : sample.demand())
                    out.write(String.format(Locale.ROOT, ",%.17g", value));
                out.newLine();
            }
        }
    }

    public static void writeValidationSummary(Path file, int replication, String experiment,
                                               Map<String, Map<Double, Double>> curves,
                                               Map<String, Double> selectedParameters,
                                               List<TRBSVUValidationTrace> details)
            throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = writer(file)) {
            out.write("replication,experiment,method,candidate,mean_validation_cost,"
                    + "sd_validation_cost,q95_validation_cost,cvar95_validation_cost,"
                    + "maximum_validation_cost,valid,selected");
            out.newLine();
            for (var method : curves.entrySet()) {
                for (var candidate : method.getValue().entrySet()) {
                    double cost = candidate.getValue();
                    TRBSVUStatistics.Summary statistics = Double.isFinite(cost)
                            ? validationStatistics(details, method.getKey(), candidate.getKey()) : null;
                    boolean hasSelection = selectedParameters.containsKey(method.getKey());
                    double selected = selectedParameters.getOrDefault(method.getKey(), Double.NaN);
                    out.write(String.format(Locale.ROOT,
                            "%d,%s,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%s,%s%n",
                            replication, experiment, method.getKey(), candidate.getKey(),
                            cost,
                            statistics == null ? Double.NaN : statistics.sampleStandardDeviation(),
                            statistics == null ? Double.NaN : statistics.q95(),
                            statistics == null ? Double.NaN : statistics.cvar95(),
                            statistics == null ? Double.NaN : statistics.maximum(),
                            Double.isFinite(cost),
                            hasSelection && Double.doubleToLongBits(selected)
                                    == Double.doubleToLongBits(candidate.getKey())));
                }
            }
        }
    }

    private static TRBSVUStatistics.Summary validationStatistics(
            List<TRBSVUValidationTrace> details, String method, double candidate) {
        double[] costs = details.stream()
                .filter(trace -> trace.method().equals(method)
                        && Double.doubleToLongBits(trace.candidateParameter())
                                == Double.doubleToLongBits(candidate)
                        && Double.isFinite(trace.realizedValidationCost()))
                .mapToDouble(TRBSVUValidationTrace::realizedValidationCost)
                .toArray();
        if (costs.length == 0) throw new IllegalStateException(
                "Missing validation details for " + method + " candidate=" + candidate);
        return TRBSVUStatistics.summarize(costs);
    }

    public static void writeContextualChoice(Path file, int replication,
                                             TRBSVUExperiment1Runner.ContextualChoice choice)
            throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = writer(file)) {
            out.write("replication,family,validation_selected_B,validation_cost,validation_sd,"
                    + "bandwidth_order");
            out.newLine();
            String order = choice.bandwidthOrder().stream()
                    .map(String::valueOf).collect(java.util.stream.Collectors.joining(";"));
            out.write(String.format(Locale.ROOT, "%d,%s,%.17g,%.17g,%.17g,%s%n",
                    replication, choice.family(), choice.bandwidth(), choice.validationCost(),
                    choice.validationSd(), csv(order)));
        }
    }

    public static void writeValidationDetails(Path file, int replication, String experiment,
                                               ProcurementParams params,
                                               List<TRBSVUValidationTrace> details)
            throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = writer(file)) {
            out.write("replication,experiment,method,candidate,origin,training_start,training_end,"
                    + "effective_context_bandwidth,scenario_count,positive_weight_count,ess,"
                    + "training_objective,status,best_bound,bound_available,gap,gap_available,"
                    + "model_build_and_solve_wall_sec,wall_time_scope,optimizer_call_wall_sec,"
                    + "optimizer_time_scope,certified_optimal,proof_scope,"
                    + "selected_count,decision_vector,selected_carriers,realized_validation_cost");
            out.newLine();
            for (TRBSVUValidationTrace trace : details) {
                out.write(String.format(Locale.ROOT,
                        "%d,%s,%s,%.17g,%d,%d,%d,%.17g,%d,%d,%.17g,%.17g,%s,%.17g,%s,%.17g,%s,"
                                + "%.9f,%s,%.9f,%s,%s,%s,%d,%s,%s,%.17g%n",
                        replication, experiment, trace.method(), trace.candidateParameter(),
                        trace.origin(), trace.trainingStart(), trace.trainingEnd(),
                        trace.effectiveContextBandwidth(), trace.scenarioCount(),
                        trace.positiveWeightCount(), trace.effectiveSampleSize(),
                        trace.trainingObjective(), csv(trace.solverStatus()), trace.bestBound(),
                        Double.isFinite(trace.bestBound()), trace.relativeGap(),
                        Double.isFinite(trace.relativeGap()), trace.solveTimeSec(),
                        "MODEL_BUILD_AND_SOLVE_WALL", trace.optimizerTimeSec(),
                        "NATIVE_OPTIMIZER_CALL_WALL", trace.certifiedOptimal(),
                        csv(trace.certifiedOptimal() ? proofScope(trace.method())
                                : "NO_OPTIMALITY_CERTIFICATE"),
                        selectedCount(trace.decision()), csv(decisionVector(trace.decision())),
                        csv(selectedCarriers(params, trace.decision())),
                        trace.realizedValidationCost()));
            }
        }
    }

    public static void writeFinalSolves(Path file, int replication, String experiment,
                                        ProcurementParams params, Map<String, Solution> decisions,
                                        Map<String, Double> validationCosts,
                                        Map<String, Double> selectedParameters,
                                        Map<String, String> parameterTypes,
                                        Map<String, String> contextFamilies,
                                        Map<String, Double> baseContextBandwidth,
                                        Map<String, Double> effectiveContextBandwidth,
                                        Map<String, List<Sample>> finalWeights) throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = writer(file)) {
            out.write("replication,experiment,method,parameter_type,selected_parameter,"
                    + "context_family,validation_selected_B,final_effective_B,"
                    + "validation_cost,scenario_count,positive_weight_count,ess,training_objective,"
                    + "status,best_bound,bound_available,gap,gap_available,"
                    + "model_build_and_solve_wall_sec,wall_time_scope,optimizer_call_wall_sec,"
                    + "optimizer_time_scope,"
                    + "certified_optimal,proof_scope,nodes,iterations,cuts,"
                    + "candidates,selected_count,selected_total_capacity,selected_total_mqc,"
                    + "decision_vector,selected_carriers,w1_radius,w1_eta,w1_initial_points,"
                    + "w1_generated_cuts,w1_total_points,w1_box_upper,w1_distance_scale");
            out.newLine();
            for (var entry : decisions.entrySet()) {
                String method = entry.getKey();
                Solution solution = entry.getValue();
                List<Sample> weights = finalWeights.get(method);
                out.write(String.format(Locale.ROOT,
                        "%d,%s,%s,%s,%.17g,%s,%.17g,%.17g,%.17g,%d,%d,%.17g,%.17g,%s,"
                                + "%.17g,%s,%.17g,%s,%.9f,%s,%.9f,%s,%s,%s,%d,%d,%d,%d,%d,%.17g,%.17g,%s,%s,"
                                + "%.17g,%.17g,%d,%d,%d,%s,%s%n",
                        replication, experiment, method,
                        parameterTypes.getOrDefault(method, "NONE"),
                        selectedParameters.getOrDefault(method, Double.NaN),
                        contextFamilies.getOrDefault(method, "UNCONDITIONAL"),
                        baseContextBandwidth.getOrDefault(method, Double.NaN),
                        effectiveContextBandwidth.getOrDefault(method, Double.NaN),
                        validationCosts.getOrDefault(method, Double.NaN),
                        weights == null ? 0 : weights.size(),
                        weights == null ? 0 : TRBSVUExperiment1Runner.positiveCount(weights),
                        weights == null ? Double.NaN : TRBSVUExperiment1Runner.ess(weights),
                        solution.objValue, csv(solution.solverStatus), solution.bestBound,
                        Double.isFinite(solution.bestBound), solution.relativeGap,
                        Double.isFinite(solution.relativeGap), solution.solveTimeSec,
                        "MODEL_BUILD_AND_SOLVE_WALL", solution.optimizerTimeSec,
                        "NATIVE_OPTIMIZER_CALL_WALL", solution.certifiedOptimal,
                        csv(solution.certifiedOptimal ? proofScope(method)
                                : "NO_OPTIMALITY_CERTIFICATE"),
                        solution.nodeCount, solution.iterationCount, solution.cutCount,
                        solution.candidateCount, selectedCount(solution.y),
                        selectedTotal(params.M, solution.y), selectedTotal(params.p, solution.y),
                        csv(decisionVector(solution.y)), csv(selectedCarriers(params, solution.y)),
                        solution.wassersteinRadius, solution.wassersteinEta,
                        solution.wassersteinInitialPointCount,
                        solution.wassersteinGeneratedCutCount,
                        solution.wassersteinTotalPointCount,
                        csv(vector(solution.wassersteinBoxUpper)),
                        csv(vector(solution.wassersteinDistanceScale))));
            }
        }
    }

    public static void writeFinalWeights(Path file, int replication, String experiment,
                                          Map<String, List<Sample>> weights) throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = writer(file)) {
            out.write("replication,experiment,method,row_index,sample_id,period_index,"
                    + "input_weight,solver_reference_weight");
            out.newLine();
            for (var method : weights.entrySet()) {
                double[] solverWeights = solverReferenceWeights(method.getKey(), method.getValue());
                for (int s = 0; s < method.getValue().size(); s++) {
                    Sample sample = method.getValue().get(s);
                    out.write(String.format(Locale.ROOT, "%d,%s,%s,%d,%d,%d,%.17g,%.17g%n",
                            replication, experiment, method.getKey(), s, sample.id,
                            sample.period.tIndex, sample.weight, solverWeights[s]));
                }
            }
        }
    }

    /** Exact empirical inputs passed to the lifted-affine marginal-moment diagnostic. */
    public static void writeMarginalMomentInputs(Path file, int replication, String experiment,
                                            int lanes, Map<String, List<Sample>> weights,
                                            Map<String, Double> selectedParameters) throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = writer(file)) {
            out.write("replication,experiment,method,kappa,row_type,lane_index,mean_demand,"
                    + "marginal_variance_bound,support_upper");
            out.newLine();
            for (var entry : weights.entrySet()) {
                String method = entry.getKey();
                if (!method.endsWith("MM")) continue;
                double kappa = selectedParameters.getOrDefault(method, Double.NaN);
                if (!Double.isFinite(kappa))
                    throw new IllegalArgumentException("Missing selected MM kappa for " + method);
                TRBSVUPcmSolver.Moments moments = TRBSVUPcmSolver.moments(entry.getValue(), lanes, kappa);
                for (int j = 0; j < lanes; j++) {
                    out.write(String.format(Locale.ROOT,
                            "%d,%s,%s,%.17g,LANE,%d,%.17g,%.17g,%.17g%n",
                            replication, experiment, method, kappa, j, moments.mean()[j],
                            moments.variance()[j], moments.upper()[j]));
                }
            }
        }
    }

    public static void writeOosSummary(Path file, int replication, String experiment,
                                       Map<String, Oos> performance) throws Exception {
        writeOosSummary(file, replication, experiment, performance, Map.of());
    }

    public static void writeOosSummary(Path file, int replication, String experiment,
                                       Map<String, Oos> performance,
                                       Map<String, Solution> decisions) throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = writer(file)) {
            out.write("replication,experiment,method,solve_status,certified_optimal,"
                    + "solve_best_bound,solve_gap,mean,sd,q95,cvar95,maximum,"
                    + "mean_transport_cost,mean_spot_cost,mean_mqc_penalty,"
                    + "mean_contracted_quantity,mean_spot_quantity,mean_mqc_shortfall_quantity,"
                    + "spot_share_total,mean_draw_spot_share,capacity_utilization_total,"
                    + "mean_lane_capacity_utilization");
            out.newLine();
            for (var entry : performance.entrySet()) {
                Oos value = entry.getValue();
                Solution solution = decisions.get(entry.getKey());
                out.write(String.format(Locale.ROOT,
                        "%d,%s,%s,%s,%s,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                                + "%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                        replication, experiment, entry.getKey(),
                        csv(solution == null ? "UNKNOWN" : solution.solverStatus),
                        solution != null && solution.certifiedOptimal,
                        solution == null ? Double.NaN : solution.bestBound,
                        solution == null ? Double.NaN : solution.relativeGap,
                        value.mean(),
                        value.standardDeviation(), value.q95(), value.cvar95(), value.maximum(),
                        value.meanTransportCost(), value.meanSpotCost(), value.meanPenalty(),
                        value.meanContractedQuantity(), value.meanSpotQuantity(),
                        value.meanMqcShortfallQuantity(), value.spotShare(),
                        value.meanDrawSpotShare(), value.capacityUtilization(),
                        value.meanLaneCapacityUtilization()));
            }
        }
    }

    public static void writeOosDetails(Path file, int replication, String experiment,
                                       Map<String, List<OosDraw>> details) throws Exception {
        writeOosDetails(file, replication, experiment, details, Map.of());
    }

    public static void writeOosDetails(Path file, int replication, String experiment,
                                       Map<String, List<OosDraw>> details,
                                       Map<String, Solution> decisions) throws Exception {
        Files.createDirectories(file.getParent());
        try (BufferedWriter out = writer(file)) {
            out.write("replication,experiment,method,solve_status,certified_optimal,solve_gap,"
                    + "draw_index,sample_id,total_demand,total_cost,"
                    + "transport_cost,spot_cost,mqc_penalty,contracted_quantity,spot_quantity,"
                    + "mqc_shortfall_quantity,spot_share,capacity_utilization,"
                    + "mean_lane_capacity_utilization");
            out.newLine();
            for (var method : details.entrySet()) {
                Solution solution = decisions.get(method.getKey());
                for (OosDraw draw : method.getValue()) {
                    out.write(String.format(Locale.ROOT,
                            "%d,%s,%s,%s,%s,%.17g,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,"
                                    + "%.17g,%.17g,%.17g,%.17g%n",
                            replication, experiment, method.getKey(),
                            csv(solution == null ? "UNKNOWN" : solution.solverStatus),
                            solution != null && solution.certifiedOptimal,
                            solution == null ? Double.NaN : solution.relativeGap,
                            draw.drawIndex(),
                            draw.sampleId(), draw.totalDemand(), draw.totalCost(),
                            draw.transportCost(), draw.spotCost(), draw.mqcPenalty(),
                            draw.contractedQuantity(), draw.spotQuantity(),
                            draw.mqcShortfallQuantity(), draw.spotShare(),
                            draw.capacityUtilization(), draw.meanLaneCapacityUtilization()));
                }
            }
        }
    }

    private static BufferedWriter writer(Path file) throws Exception {
        return Files.newBufferedWriter(file, StandardCharsets.UTF_8);
    }

    private static int selectedCount(double[] y) {
        if (y == null) return -1;
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static double selectedTotal(double[] values, double[] y) {
        if (y == null || values.length != y.length) return Double.NaN;
        double total = 0.0;
        for (int i = 0; i < y.length; i++) if (y[i] > 0.5) total += values[i];
        return total;
    }

    private static String decisionVector(double[] y) {
        if (y == null) return "NA";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (i > 0) text.append(';');
            text.append(y[i] > 0.5 ? 1 : 0);
        }
        return text.toString();
    }

    private static String selectedCarriers(ProcurementParams params, double[] y) {
        if (y == null) return "NA";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (y[i] <= 0.5) continue;
            if (!text.isEmpty()) text.append(';');
            text.append(params.carriers.get(i));
        }
        return text.toString();
    }

    private static double[] solverReferenceWeights(String method, List<Sample> samples) {
        double[] result = new double[samples.size()];
        boolean formalFloor = method.endsWith("Chi2")
                || method.equals("RSAA") || method.equals("RCSAA");
        double total = 0.0;
        for (int s = 0; s < samples.size(); s++) {
            double value = samples.get(s).weight;
            if (formalFloor && value > 0.0 && value < 1e-8) value = 1e-8;
            result[s] = value;
            total += value;
        }
        if (!(total > 0.0)) throw new IllegalArgumentException("No positive method weight: " + method);
        for (int s = 0; s < result.length; s++) result[s] /= total;
        return result;
    }

    private static String proofScope(String method) {
        return method.endsWith("MM")
                ? "OPTIMAL_FOR_LIFTED_AFFINE_MARGINAL_MOMENT_APPROXIMATION"
                : "OPTIMAL_FOR_STATED_METHOD_MODEL";
    }

    private static String vector(double[] values) {
        if (values == null) return "NA";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) text.append(';');
            text.append(Double.toString(values[i]));
        }
        return text.toString();
    }

    private static String csv(String value) {
        if (value == null) return "NA";
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
