package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Replication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Mechanism probe for the simple three-level DGP.  It changes only capacity
 * tightness and demand volatility; rates, eligibility, MQC, contexts, random
 * streams, and DGP coefficients remain paired within each replication.
 */
public final class TRBSVUSimpleTierCapacityVolatilityProbe {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int HISTORY = 60;
    private static final int OOS = 500;
    private static final double BANDWIDTH = 0.5;
    private static final double[] CHI2_LAMBDAS = {0.5, 5.0};
    private static final double[] CAPACITY_SCALES = {1.0, 0.75, 0.60};

    private TRBSVUSimpleTierCapacityVolatilityProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: <output-directory> [replications] [data|nominal|chi2|all]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int replications = args.length >= 2 ? Integer.parseInt(args[1]) : 3;
        String mode = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "nominal";
        if (!mode.equals("data") && !mode.equals("nominal")
                && !mode.equals("chi2") && !mode.equals("all")) {
            throw new IllegalArgumentException("Mode must be data, nominal, chi2, or all.");
        }
        boolean runNominal = mode.equals("nominal") || mode.equals("all");
        boolean runChi2 = mode.equals("chi2") || mode.equals("all");
        if (replications <= 0) throw new IllegalArgumentException("Replications must be positive.");
        Files.createDirectories(output);

        Settings settings = new Settings(1, 600, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<String> rows = new ArrayList<>();
        rows.add("replication\tvolatility\tcapacity_scale\tmethod\tlambda\tstatus\tcertified"
                + "\tobjective\tgap\tsolve_sec\tselected_count\tselected\toos_mean\toos_sd"
                + "\toos_q95\toos_cvar95\toos_max\tspot_share\tmqc_penalty\tcapacity_utilization");
        List<String> demandRows = new ArrayList<>();
        demandRows.add("replication\tvolatility\toos_total_mean\toos_total_cv"
                + "\tmean_lane_cv\tmin_lane_cv\tmax_lane_cv");

        SplittableRandom seeds = new SplittableRandom(20260917L);
        for (int replication = 1; replication <= replications; replication++) {
            TRBSVUSyntheticCase.Seeds paired = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(),
                    seeds.nextLong(), seeds.nextLong());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, HISTORY, paired.demandParameters(), 1.0,
                    ContextStructure.DENSE_WIDE_POSITIVE, BaseStructure.THREE_LEVEL_WIDE);
            ProcurementParams originalMarket = TRBSVUProcurementGenerator.generate(
                    CARRIERS, parameters.typicalDemand(), paired.procurement());

            for (Volatility volatility : Volatility.values()) {
                // Lognormal preserves the specified conditional mean at all three CV levels.
                Replication demand = TRBSVUSyntheticDemandGenerator.generate(parameters,
                        Distribution.LOGNORMAL, volatility, OOS, paired.contexts(),
                        paired.historicalNoise(), paired.oosNoise());
                demandRows.add(demandDiagnostics(replication, volatility, demand.oos));
                Files.write(output.resolve("demand_diagnostics.tsv"), demandRows,
                        StandardCharsets.UTF_8);
                for (double capacityScale : CAPACITY_SCALES) {
                    ProcurementParams market = scaleCapacity(originalMarket, capacityScale);
                    TRBSVUSyntheticCase instance = new TRBSVUSyntheticCase(market, laneNames(),
                            demand.history, demand.testContext, demand.oos, paired);
                    List<Sample> contextual = TRBSVUScenarioWeights.kernel(instance.history,
                            instance.testContext, TRBSVUScenarioWeights.Kernel.EXPONENTIAL,
                            BANDWIDTH);
                    if (runNominal) {
                        run(rows, replication, volatility, capacityScale, "D", 0.0, instance,
                                TRBSVUScenarioWeights.arithmeticMean(instance.history),
                                Method.NOMINAL, settings);
                        run(rows, replication, volatility, capacityScale, "SAA", 0.0, instance,
                                TRBSVUScenarioWeights.equal(instance.history), Method.NOMINAL, settings);
                        run(rows, replication, volatility, capacityScale, "CSAA", 0.0, instance,
                                contextual, Method.NOMINAL, settings);
                    }
                    if (runChi2) {
                        for (double lambda : CHI2_LAMBDAS) {
                            run(rows, replication, volatility, capacityScale,
                                    "C_CHI2", lambda, instance, contextual,
                                    Method.CHI_SQUARED, settings);
                        }
                    }
                    Files.write(output.resolve("capacity_volatility_probe.tsv"), rows,
                            StandardCharsets.UTF_8);
                }
            }
        }
        rows.forEach(System.out::println);
    }

    private static String demandDiagnostics(int replication, Volatility volatility,
                                            List<Sample> oos) {
        int lanes = oos.get(0).demand().length;
        double[] totals = new double[oos.size()];
        double[][] byLane = new double[lanes][oos.size()];
        for (int s = 0; s < oos.size(); s++) {
            double[] demand = oos.get(s).demand();
            for (int j = 0; j < lanes; j++) {
                totals[s] += demand[j];
                byLane[j][s] = demand[j];
            }
        }
        double meanLaneCv = 0.0, minLaneCv = Double.POSITIVE_INFINITY,
                maxLaneCv = Double.NEGATIVE_INFINITY;
        for (double[] lane : byLane) {
            double cv = cv(lane);
            meanLaneCv += cv / lanes;
            minLaneCv = Math.min(minLaneCv, cv);
            maxLaneCv = Math.max(maxLaneCv, cv);
        }
        return String.format(Locale.ROOT, "%d\t%s\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                replication, volatility, mean(totals), cv(totals), meanLaneCv,
                minLaneCv, maxLaneCv);
    }

    private static double cv(double[] values) {
        double mean = mean(values), sum = 0.0;
        for (double value : values) {
            double difference = value - mean;
            sum += difference * difference;
        }
        return Math.sqrt(sum / (values.length - 1.0)) / mean;
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static ProcurementParams scaleCapacity(ProcurementParams source, double scale) {
        double[][] capacity = new double[source.I][source.J];
        double[][] rate = new double[source.I][source.J];
        boolean[][] eligible = new boolean[source.I][source.J];
        for (int i = 0; i < source.I; i++) {
            for (int j = 0; j < source.J; j++) {
                capacity[i][j] = scale * source.q[i][j];
                rate[i][j] = source.r[i][j];
                eligible[i][j] = source.eligible[i][j];
            }
            if (source.p[i] > scale * source.M[i] + 1e-9) {
                throw new IllegalArgumentException("Capacity scale makes MQC exceed capacity for carrier " + i);
            }
        }
        return new ProcurementParams(source.carriers, source.J, source.e.clone(), source.p.clone(),
                source.h.clone(), capacity, rate, eligible, source.alpha, source.beta);
    }

    private static void run(List<String> rows, int replication, Volatility volatility,
                            double capacityScale, String name, double lambda,
                            TRBSVUSyntheticCase instance,
                            List<Sample> weighted, Method method, Settings settings) throws Exception {
        Solution solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                weighted, instance.testContext, method, lambda, settings);
        Oos oos = TRBSVUSolveMethods.evaluate(instance.params, solution.y, instance.oos);
        rows.add(String.format(Locale.ROOT,
                "%d\t%s\t%.2f\t%s\t%.2f\t%s\t%s\t%.10f\t%.10g\t%.6f\t%d\t%s"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                replication, volatility, capacityScale, name, lambda, solution.solverStatus,
                solution.certifiedOptimal, solution.objValue, solution.relativeGap,
                solution.solveTimeSec, selectedCount(solution.y), selected(solution.y),
                oos.mean(), oos.standardDeviation(), oos.q95(), oos.cvar95(), oos.maximum(),
                oos.spotShare(), oos.meanPenalty(), oos.capacityUtilization()));
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

    private static List<String> laneNames() {
        List<String> result = new ArrayList<>(LANES);
        for (int j = 0; j < LANES; j++) result.add("L" + (j + 1));
        return result;
    }
}
