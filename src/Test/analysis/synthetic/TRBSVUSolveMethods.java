package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Model.ContextualWassersteinBoxCcgSolver;
import Model.DROModel;
import Model.RCSAASolverVariant;
import Model.SAAModel;
import Model.Solution;
import Model.SolveMode;
import Model.WassersteinBoxInput;
import Test.BatchRunner;

import java.util.List;
import java.util.Locale;

/** Shared model and OOS adapter for synthetic Experiments 1--2. */
public final class TRBSVUSolveMethods {
    public enum Method { NOMINAL, CHI_SQUARED, RCSAA, WASSERSTEIN }

    public record Settings(int threads, int timeLimitSeconds, double tolerance,
                           RCSAASolverVariant rcsaaVariant,
                           boolean repairCuts, boolean compactDual) {
        public Settings {
            if (threads < 1 || timeLimitSeconds < 1 || !(tolerance > 0.0)
                    || rcsaaVariant == null) throw new IllegalArgumentException("Invalid solver settings.");
        }
    }

    public record Oos(double mean, double standardDeviation, double q95, double cvar95,
                      double maximum, double meanTransportCost, double meanSpotCost,
                      double meanPenalty, double meanContractedQuantity,
                      double meanSpotQuantity, double meanMqcShortfallQuantity,
                      double spotShare, double meanDrawSpotShare,
                      double capacityUtilization, double meanLaneCapacityUtilization) { }
    public record OosDraw(int drawIndex, int sampleId, double totalDemand,
                          double totalCost, double transportCost, double spotCost,
                          double mqcPenalty, double contractedQuantity,
                          double spotQuantity, double mqcShortfallQuantity,
                          double spotShare, double capacityUtilization,
                          double meanLaneCapacityUtilization) { }
    public record OosEvaluation(Oos summary, List<OosDraw> draws) { }

    private TRBSVUSolveMethods() { }

    public static Solution solve(ProcurementParams params, List<String> lanes,
                                 List<Sample> weighted, CovariateVector query,
                                 Method method, double robustness,
                                 Settings settings) throws Exception {
        if (weighted.isEmpty()) throw new IllegalArgumentException("No training scenarios.");
        // The original Sample objects and weights belong to the case, not to any method.
        List<Sample> samples = TRBSVUScenarioWeights.copyWithWeights(weighted,
                weighted.stream().mapToDouble(s -> s.weight).toArray(),
                method == Method.CHI_SQUARED || method == Method.RCSAA);
        Data data = new Data(lanes, samples, query.copy(), params);
        Config config = config(settings);
        System.out.printf(Locale.ROOT,
                "SOLVE_BEGIN method=%s robustness=%.17g scenarios=%d positiveWeights=%d ess=%.10f threads=%d limitSec=%d%n",
                method, robustness, samples.size(), TRBSVUExperiment1Runner.positiveCount(samples),
                TRBSVUExperiment1Runner.ess(samples), settings.threads(), settings.timeLimitSeconds());
        try {
        Solution solution = switch (method) {
            case NOMINAL -> new SAAModel().solve(data, config, null);
            case CHI_SQUARED -> {
                if (!(robustness > 0.0)) throw new IllegalArgumentException("Chi-squared radius must be positive.");
                config.lambda = robustness;
                config.solveMode = SolveMode.CSAA;
                yield new DROModel().solve(data, config);
            }
            case RCSAA -> {
                if (!(robustness > 0.0)) throw new IllegalArgumentException("RCSAA lambda must be positive.");
                if (settings.rcsaaVariant() == RCSAASolverVariant.DRO_EXTENSIVE)
                    throw new IllegalArgumentException("DRO extensive form is not an exact RCSAA solver.");
                config.lambda = robustness;
                config.solveMode = SolveMode.RCSAA;
                config.rcsaaSolverVariant = settings.rcsaaVariant();
                config.rcsaaRepairCuts = settings.repairCuts();
                config.rcsaaCompactDual = settings.compactDual();
                yield new DROModel().solve(data, config);
            }
            case WASSERSTEIN -> {
                if (!(robustness >= 0.0)) throw new IllegalArgumentException("Negative W1 radius.");
                double[] max = trainingMax(weighted, params.J);
                double[] upper = new double[params.J];
                double[] distanceScale = new double[params.J];
                for (int j = 0; j < params.J; j++) {
                    // No arbitrary floor: a zero-max lane invalidates this training origin.
                    if (!(max[j] > 0.0)) throw new IllegalArgumentException("Zero training maximum at lane " + j);
                    upper[j] = 1.5 * max[j];
                    distanceScale[j] = params.J * max[j];
                }
                WassersteinBoxInput input = WassersteinBoxInput.fromData(data,
                        upper, distanceScale, robustness);
                yield new ContextualWassersteinBoxCcgSolver().solve(input, config).solution();
            }
        };
        System.out.printf(Locale.ROOT,
                "SOLVE_END method=%s robustness=%.17g status=%s certified=%s objective=%.17g bestBound=%.17g gap=%.17g solveSec=%.6f selected=%d%n",
                method, robustness, solution.solverStatus, solution.certifiedOptimal,
                solution.objValue, solution.bestBound, solution.relativeGap, solution.solveTimeSec,
                selectedCount(solution.y));
        return solution;
        } catch (Exception ex) {
            System.out.printf(Locale.ROOT,
                    "SOLVE_FAIL method=%s robustness=%.17g exception=%s message=%s%n",
                    method, robustness, ex.getClass().getName(), String.valueOf(ex.getMessage()));
            throw ex;
        }
    }

    private static int selectedCount(double[] selection) {
        if (selection == null) return 0;
        int count = 0;
        for (double value : selection) if (value > 0.5) count++;
        return count;
    }

    public static double realizedCost(ProcurementParams params, double[] selection,
                                      double[] demand) throws Exception {
        return BatchRunner.RecourseEvaluator.evaluate(params, selection, demand, true).objValue;
    }

    /** All methods are evaluated on the same fixed conditional OOS pool. */
    public static Oos evaluate(ProcurementParams params, double[] selection,
                               List<Sample> oos) throws Exception {
        return evaluateDetailed(params, selection, oos).summary();
    }

    /** Same evaluation, retaining one auditable cost decomposition per OOS draw. */
    public static OosEvaluation evaluateDetailed(ProcurementParams params, double[] selection,
                                                 List<Sample> oos) throws Exception {
        if (oos.isEmpty()) throw new IllegalArgumentException("Empty OOS pool.");
        double[] costs = new double[oos.size()];
        double spot = 0.0, demand = 0.0, assigned = 0.0, penalty = 0.0, sum = 0.0;
        double transportCost = 0.0, spotCost = 0.0, shortfall = 0.0;
        java.util.ArrayList<OosDraw> draws = new java.util.ArrayList<>(oos.size());
        double selectedCapacity = 0.0;
        double[] selectedLaneCapacity = new double[params.J];
        for (int i = 0; i < params.I; i++) {
            if (selection[i] > 0.5) {
                selectedCapacity += params.M[i];
                for (int j = 0; j < params.J; j++) selectedLaneCapacity[j] += params.q[i][j];
            }
        }
        double drawSpotShareSum = 0.0, laneUtilizationSum = 0.0;
        int laneUtilizationCount = 0;
        for (int s = 0; s < oos.size(); s++) {
            BatchRunner.RecourseEvaluator.RecourseEval recourse =
                    BatchRunner.RecourseEvaluator.evaluate(params, selection, oos.get(s).demand(), true);
            costs[s] = recourse.objValue;
            sum += costs[s];
            penalty += recourse.penaltyTotalCost;
            transportCost += recourse.transportTotalCost;
            spotCost += recourse.spotTotalCost;
            double drawAssigned = 0.0, drawSpot = 0.0, drawDemand = 0.0, drawShortfall = 0.0;
            for (double quantity : recourse.carrierAssignedQty) drawAssigned += quantity;
            for (double quantity : recourse.carrierPenaltyQty) drawShortfall += quantity;
            for (int j = 0; j < params.J; j++) {
                drawSpot += recourse.laneSpotQty[j];
                drawDemand += oos.get(s).demand()[j];
                if (selectedLaneCapacity[j] > 0.0) {
                    double laneContracted = oos.get(s).demand()[j] - recourse.laneSpotQty[j];
                    laneUtilizationSum += laneContracted / selectedLaneCapacity[j];
                    laneUtilizationCount++;
                }
            }
            assigned += drawAssigned;
            spot += drawSpot;
            demand += drawDemand;
            shortfall += drawShortfall;
            double drawSpotShare = drawDemand > 0.0 ? drawSpot / drawDemand : 0.0;
            double drawCapacityUtilization = selectedCapacity > 0.0
                    ? drawAssigned / selectedCapacity : 0.0;
            double drawLaneUtilization = 0.0;
            int activeLanes = 0;
            for (int j = 0; j < params.J; j++) {
                if (selectedLaneCapacity[j] > 0.0) {
                    drawLaneUtilization += (oos.get(s).demand()[j] - recourse.laneSpotQty[j])
                            / selectedLaneCapacity[j];
                    activeLanes++;
                }
            }
            if (activeLanes > 0) drawLaneUtilization /= activeLanes;
            drawSpotShareSum += drawSpotShare;
            draws.add(new OosDraw(s, oos.get(s).id, drawDemand, recourse.objValue,
                    recourse.transportTotalCost, recourse.spotTotalCost,
                    recourse.penaltyTotalCost, drawAssigned, drawSpot, drawShortfall,
                    drawSpotShare, drawCapacityUtilization, drawLaneUtilization));
        }
        TRBSVUStatistics.Summary statistics = TRBSVUStatistics.summarize(costs);
        Oos summary = new Oos(statistics.mean(), statistics.sampleStandardDeviation(),
                statistics.q95(), statistics.cvar95(), statistics.maximum(),
                transportCost / oos.size(), spotCost / oos.size(), penalty / oos.size(),
                assigned / oos.size(), spot / oos.size(), shortfall / oos.size(),
                demand > 0.0 ? spot / demand : 0.0, drawSpotShareSum / oos.size(),
                selectedCapacity > 0.0 ? assigned / (oos.size() * selectedCapacity) : 0.0,
                laneUtilizationCount > 0 ? laneUtilizationSum / laneUtilizationCount : 0.0);
        return new OosEvaluation(summary, List.copyOf(draws));
    }

    private static Config config(Settings settings) {
        Config config = new Config();
        config.enforceDemandEquality = true;
        config.threads = settings.threads();
        config.timeLimitSeconds = settings.timeLimitSeconds();
        config.tol = settings.tolerance();
        config.writeSolverLogToConsole = true;
        return config;
    }

    private static double[] trainingMax(List<Sample> weighted, int lanes) {
        double[] max = new double[lanes];
        for (Sample sample : weighted) {
            for (int j = 0; j < lanes; j++) max[j] = Math.max(max[j], sample.demand()[j]);
        }
        return max;
    }
}
