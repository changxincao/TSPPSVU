package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.SecondStageEvaluator;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.SplittableRandom;

/**
 * Diagnoses why a single known demand vector selects many carriers.
 * For each demand sample it compares the free-cardinality optimum with the
 * optimum obtained after fixing the selected-carrier count to every admissible m.
 */
public final class TRBSVUSingleSampleCardinalityDiagnostic {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int HISTORY = 60;
    private static final int OOS = 1;

    private TRBSVUSingleSampleCardinalityDiagnostic() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: <output-directory> [replications]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int replications = args.length == 2 ? Integer.parseInt(args[1]) : 3;
        Files.createDirectories(output);

        TRBSVUSolveMethods.Settings settings = new TRBSVUSolveMethods.Settings(
                1, 600, 1e-4, RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        SplittableRandom seeds = new SplittableRandom(20260917L);
        List<String> lanes = laneNames();

        List<String> rows = new ArrayList<>();
        rows.add("replication\tmarket_structure\tsample\tdemand_type\ttotal_demand"
                + "\tfree_count\tfixed_count\ttotal_cost"
                + "\ttransport_cost\tspot_cost\tmqc_penalty\tspot_quantity\tmqc_shortfall"
                + "\tselected_capacity\tselected_mqc\tcovered_lanes\tcapacity_short_lanes"
                + "\tlane_capacity_shortfall\tselected");
        for (int replication = 1; replication <= replications; replication++) {
            TRBSVUSyntheticCase.Seeds paired = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(),
                    seeds.nextLong(), seeds.nextLong());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, HISTORY, paired.demandParameters(), 1.0,
                    ContextStructure.DENSE_INDEPENDENT_LEVELS, BaseStructure.THREE_LEVEL_WIDE);
            MultiQueryReplication generated = TRBSVUSyntheticDemandGenerator.generateMultiQuery(
                    parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, 1, OOS,
                    paired.contexts(), paired.historicalNoise(), paired.oosNoise());
            ProcurementParams current = TRBSVUProcurementGenerator.generate(
                    CARRIERS, parameters.typicalDemand(), paired.procurement());
            ProcurementParams currentMqc150 = scaleMqc(current, 1.50);
            ProcurementParams persistent20 = persistentCarrierRates(current,
                    paired.procurement() ^ 0x5DEECE66DL, 0.8, 1.2);
            ProcurementParams persistent30 = persistentCarrierRates(current,
                    paired.procurement() ^ 0x5DEECE66DL, 0.7, 1.3);
            ProcurementParams persistent30Mqc125 = scaleMqc(persistent30, 1.25);
            ProcurementParams persistent30Mqc150 = scaleMqc(persistent30, 1.50);
            for (int t = 0; t < HISTORY; t++) {
                Sample observed = generated.history.get(t);
                diagnose(rows, replication, "CURRENT_LANE_TIERS", t, "REALIZED",
                        observed.theta, observed.demand(), current, lanes, settings);
                diagnose(rows, replication, "CURRENT_LANE_TIERS_MQC_1.50", t, "REALIZED",
                        observed.theta, observed.demand(), currentMqc150, lanes, settings);
                diagnose(rows, replication, "PERSISTENT_CARRIER_FACTOR", t, "REALIZED",
                        observed.theta, observed.demand(), persistent20, lanes, settings);
                diagnose(rows, replication, "PERSISTENT_CARRIER_FACTOR_WIDE", t, "REALIZED",
                        observed.theta, observed.demand(), persistent30, lanes, settings);
                diagnose(rows, replication, "PERSISTENT_WIDE_MQC_1.25", t, "REALIZED",
                        observed.theta, observed.demand(), persistent30Mqc125, lanes, settings);
                diagnose(rows, replication, "PERSISTENT_WIDE_MQC_1.50", t, "REALIZED",
                        observed.theta, observed.demand(), persistent30Mqc150, lanes, settings);
                diagnose(rows, replication, "CURRENT_LANE_TIERS", t, "CONDITIONAL_MEAN",
                        observed.theta, parameters.nominalDemand(observed.theta),
                        current, lanes, settings);
                diagnose(rows, replication, "CURRENT_LANE_TIERS_MQC_1.50", t,
                        "CONDITIONAL_MEAN", observed.theta,
                        parameters.nominalDemand(observed.theta), currentMqc150, lanes, settings);
                diagnose(rows, replication, "PERSISTENT_CARRIER_FACTOR", t,
                        "CONDITIONAL_MEAN", observed.theta,
                        parameters.nominalDemand(observed.theta), persistent20, lanes, settings);
                diagnose(rows, replication, "PERSISTENT_CARRIER_FACTOR_WIDE", t,
                        "CONDITIONAL_MEAN", observed.theta,
                        parameters.nominalDemand(observed.theta), persistent30, lanes, settings);
                diagnose(rows, replication, "PERSISTENT_WIDE_MQC_1.25", t,
                        "CONDITIONAL_MEAN", observed.theta,
                        parameters.nominalDemand(observed.theta), persistent30Mqc125, lanes, settings);
                diagnose(rows, replication, "PERSISTENT_WIDE_MQC_1.50", t,
                        "CONDITIONAL_MEAN", observed.theta,
                        parameters.nominalDemand(observed.theta), persistent30Mqc150, lanes, settings);
                Files.write(output.resolve("cardinality_cost_curve.tsv"), rows,
                        StandardCharsets.UTF_8);
            }
        }
    }

    private static void diagnose(List<String> rows, int replication, String structure,
                                 int sampleId, String type,
                                 CovariateVector context, double[] demand,
                                 ProcurementParams market, List<String> lanes,
                                 TRBSVUSolveMethods.Settings settings) throws Exception {
        Sample sample = unitSample(sampleId, context, demand);
        Solution free = solve(market, lanes, sample, context, settings);
        int freeCount = selectedCount(free.y);
        for (int m = market.alpha; m <= market.beta; m++) {
            ProcurementParams fixed = copyWithBounds(market, m, m);
            Solution solution = solve(fixed, lanes, sample, context, settings);
            SecondStageEvaluator.Result recourse = SecondStageEvaluator.evaluate(
                    fixed, solution.y, demand, true);
            double selectedCapacity = 0.0;
            double selectedMqc = 0.0;
            int coveredLanes = 0;
            int capacityShortLanes = 0;
            double laneCapacityShortfall = 0.0;
            for (int i = 0; i < fixed.I; i++) {
                if (solution.y[i] <= 0.5) continue;
                selectedCapacity += fixed.M[i];
                selectedMqc += fixed.p[i];
            }
            for (int j = 0; j < fixed.J; j++) {
                double laneCapacity = 0.0;
                for (int i = 0; i < fixed.I; i++) {
                    if (solution.y[i] > 0.5) laneCapacity += fixed.q[i][j];
                }
                if (laneCapacity > 0.0) coveredLanes++;
                if (laneCapacity + 1e-9 < demand[j]) {
                    capacityShortLanes++;
                    laneCapacityShortfall += demand[j] - laneCapacity;
                }
            }
            // Recover spot quantity through a one-draw detailed evaluation.
            TRBSVUSolveMethods.OosDraw draw = TRBSVUSolveMethods.evaluateDetailed(
                    fixed, solution.y, List.of(sample)).draws().get(0);
            rows.add(String.format(Locale.ROOT,
                    "%d\t%s\t%d\t%s\t%.10f\t%d\t%d\t%.10f\t%.10f\t%.10f\t%.10f"
                            + "\t%.10f\t%.10f\t%.10f\t%.10f\t%d\t%d\t%.10f\t%s",
                    replication, structure, sampleId, type, sum(demand), freeCount, m,
                    recourse.objective,
                    recourse.transportCost, recourse.spotCost, recourse.penaltyCost,
                    draw.spotQuantity(), recourse.mqcShortfallQuantity, selectedCapacity,
                    selectedMqc, coveredLanes, capacityShortLanes, laneCapacityShortfall,
                    selected(solution.y)));
        }
    }

    /** Replaces only the independently shuffled lane rate tiers. */
    static ProcurementParams persistentCarrierRates(ProcurementParams source, long seed,
                                                     double factorLower,
                                                     double factorUpper) {
        return persistentCarrierRates(source, seed, factorLower, factorUpper, 0.9, 1.1);
    }

    /** Keeps the carrier factor fixed while varying only within-carrier lane rates. */
    static ProcurementParams persistentCarrierRates(ProcurementParams source, long seed,
                                                     double factorLower, double factorUpper,
                                                     double localLower, double localUpper) {
        if (!(localLower > 0.0 && localUpper >= localLower)
                || !Double.isFinite(localLower) || !Double.isFinite(localUpper)) {
            throw new IllegalArgumentException("Invalid local rate-factor interval.");
        }
        Random random = new Random(seed);
        double[] carrierFactor = new double[source.I];
        for (int i = 0; i < source.I; i++) {
            carrierFactor[i] = uniform(random, factorLower, factorUpper);
        }
        double[][] rates = new double[source.I][source.J];
        for (int j = 0; j < source.J; j++) {
            double laneMean = 0.0;
            int eligibleCount = 0;
            for (int i = 0; i < source.I; i++) {
                if (!source.eligible[i][j]) continue;
                laneMean += source.r[i][j];
                eligibleCount++;
            }
            laneMean /= eligibleCount;
            for (int i = 0; i < source.I; i++) {
                rates[i][j] = source.eligible[i][j]
                        ? laneMean * carrierFactor[i] * uniform(random, localLower, localUpper)
                        : Double.MAX_VALUE;
            }
        }
        double[] penalty = new double[source.I];
        for (int i = 0; i < source.I; i++) {
            penalty[i] = Double.POSITIVE_INFINITY;
            for (int j = 0; j < source.J; j++) {
                if (source.eligible[i][j]) penalty[i] = Math.min(penalty[i], rates[i][j]);
            }
        }
        return new ProcurementParams(List.copyOf(source.carriers), source.J, source.e.clone(),
                source.p.clone(), penalty, copy(source.q), rates, copy(source.eligible),
                source.alpha, source.beta);
    }

    private static double uniform(Random random, double lower, double upper) {
        return lower + (upper - lower) * random.nextDouble();
    }

    static ProcurementParams scaleMqc(ProcurementParams source, double scale) {
        double[] mqc = source.p.clone();
        for (int i = 0; i < source.I; i++) {
            mqc[i] *= scale;
            if (mqc[i] > source.M[i] + 1e-9) {
                throw new IllegalArgumentException("Scaled MQC exceeds carrier capacity at " + i);
            }
        }
        return new ProcurementParams(List.copyOf(source.carriers), source.J, source.e.clone(),
                mqc, source.h.clone(), copy(source.q), copy(source.r), copy(source.eligible),
                source.alpha, source.beta);
    }

    private static Solution solve(ProcurementParams market, List<String> lanes, Sample sample,
                                  CovariateVector query, TRBSVUSolveMethods.Settings settings)
            throws Exception {
        Solution solution = TRBSVUSolveMethods.solve(market, lanes, List.of(sample), query,
                TRBSVUSolveMethods.Method.NOMINAL, 0.0, settings);
        if (!solution.certifiedOptimal) {
            throw new IllegalStateException("Uncertified cardinality diagnostic: "
                    + solution.solverStatus);
        }
        return solution;
    }

    private static ProcurementParams copyWithBounds(ProcurementParams p, int alpha, int beta) {
        return new ProcurementParams(List.copyOf(p.carriers), p.J, p.e.clone(), p.p.clone(),
                p.h.clone(), copy(p.q), copy(p.r), copy(p.eligible), alpha, beta);
    }

    private static double[][] copy(double[][] source) {
        double[][] result = new double[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    private static boolean[][] copy(boolean[][] source) {
        boolean[][] result = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) result[i] = source[i].clone();
        return result;
    }

    private static Sample unitSample(int id, CovariateVector context, double[] demand) {
        LocalDate date = LocalDate.of(2000, 1, 1).plusWeeks(id);
        return new Sample(id, new PeriodData(id, date, date.plusDays(6), demand.clone(),
                0, 0.0, 0.0, 0.0), context.copy(), 1.0);
    }

    private static int selectedCount(double[] y) {
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static String selected(double[] y) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (y[i] <= 0.5) continue;
            if (!result.isEmpty()) result.append('|');
            result.append(i);
        }
        return result.toString();
    }

    private static double sum(double[] values) {
        double result = 0.0;
        for (double value : values) result += value;
        return result;
    }

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>(LANES);
        for (int j = 0; j < LANES; j++) result.add("L" + (j + 1));
        return result;
    }
}
