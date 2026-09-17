package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.SplittableRandom;

/** Demand-only replication of the beta experiments in Zhang et al. (2014). */
public final class TRBSVULiteratureBetaSaaProbe {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int MAX_TRAINING = 60;
    private static final int OOS = 2_000;
    private static final int[] TRAINING_SIZES = {10, 60};

    private record BetaCase(String name, double alpha, double beta) { }

    private static final List<BetaCase> DISTRIBUTIONS = List.of(
            new BetaCase("BETA_5_5", 5.0, 5.0),
            new BetaCase("BETA_1_1", 1.0, 1.0),
            new BetaCase("BETA_0_5_0_5", 0.5, 0.5),
            new BetaCase("BETA_2_5", 2.0, 5.0),
            new BetaCase("BETA_1_5", 1.0, 5.0));

    private TRBSVULiteratureBetaSaaProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 6) {
            throw new IllegalArgumentException(
                    "Usage: <output-directory> [replications] [capacity-scale] [distribution]"
                            + " [market] [independent|common]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int replications = args.length >= 2 ? Integer.parseInt(args[1]) : 5;
        double capacityScale = args.length >= 3 ? Double.parseDouble(args[2]) : 1.0;
        String distributionFilter = args.length >= 4 ? args[3] : "ALL";
        String marketStyle = args.length >= 5 ? args[4] : "CURRENT";
        boolean commonShock = args.length >= 6 && args[5].equals("common");
        Files.createDirectories(output);
        Settings settings = new Settings(1, 600, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<String> rows = new ArrayList<>();
        rows.add("replication\tdistribution\tcapacity_scale\ttraining_size\tmethod\tstatus\tcertified"
                + "\tobjective\tsolve_sec\tselected_count\tselected\toos_mean\toos_sd"
                + "\toos_q95\toos_cvar95\toos_max\tspot_share\tmqc_penalty");

        SplittableRandom seeds = new SplittableRandom(20260917L);
        CovariateVector noContext = new CovariateVector(new double[4]);
        List<String> lanes = laneNames();
        double[] marketReferenceDemand = new double[LANES];
        java.util.Arrays.fill(marketReferenceDemand, 100.0);
        for (int replication = 1; replication <= replications; replication++) {
            long marketSeed = seeds.nextLong();
            long sampleSeed = seeds.nextLong();
            ProcurementParams rawMarket = marketStyle.equals("BALANCED")
                    ? TRBReviewerSyntheticProcurementFactoryV3.generate(
                            marketReferenceDemand, CARRIERS, 0.50,
                            TRBReviewerSyntheticProcurementFactoryV3.Regime.BALANCED,
                            marketSeed).params()
                    : TRBSVUProcurementGenerator.generate(
                            CARRIERS, marketReferenceDemand, marketSeed);
            ProcurementParams market = scaleCapacity(rawMarket, capacityScale);
            for (BetaCase distribution : DISTRIBUTIONS) {
                if (!distributionFilter.equals("ALL")
                        && !distribution.name.equals(distributionFilter)) continue;
                double[] distributionMean = new double[LANES];
                java.util.Arrays.fill(distributionMean,
                        50.0 + 100.0 * distribution.alpha / (distribution.alpha + distribution.beta));
                Solution deterministic = solve(market, lanes,
                        List.of(sample(-1, distributionMean, noContext, 1.0)), noContext, settings);
                List<Sample> training = draws(distribution, MAX_TRAINING,
                        sampleSeed ^ distribution.name.hashCode(), noContext, commonShock);
                List<Sample> oos = draws(distribution, OOS,
                        (sampleSeed + 1) ^ distribution.name.hashCode(), noContext, commonShock);
                write(rows, replication, distribution.name, capacityScale, 0, "D", deterministic,
                        market, oos);
                for (int trainingSize : TRAINING_SIZES) {
                    Solution saa = solve(market, lanes,
                            TRBSVUScenarioWeights.equal(training.subList(0, trainingSize)),
                            noContext, settings);
                    write(rows, replication, distribution.name, capacityScale,
                            trainingSize, "SAA", saa,
                            market, oos);
                }
                Files.write(output.resolve("beta_saa_results.tsv"), rows,
                        StandardCharsets.UTF_8);
            }
        }
    }

    private static List<Sample> draws(BetaCase distribution, int count, long seed,
                                      CovariateVector context, boolean commonShock) {
        Random random = new Random(seed);
        List<Sample> result = new ArrayList<>(count);
        for (int s = 0; s < count; s++) {
            double[] demand = new double[LANES];
            double shared = commonShock
                    ? beta(random, distribution.alpha, distribution.beta) : Double.NaN;
            for (int j = 0; j < LANES; j++) {
                double realization = commonShock ? shared
                        : beta(random, distribution.alpha, distribution.beta);
                demand[j] = 50.0 + 100.0 * realization;
            }
            result.add(sample(s, demand, context, 1.0 / count));
        }
        return result;
    }

    private static double beta(Random random, double alpha, double beta) {
        double left = gamma(random, alpha);
        double right = gamma(random, beta);
        return left / (left + right);
    }

    private static double gamma(Random random, double shape) {
        if (shape < 1.0) {
            return gamma(random, shape + 1.0) * Math.pow(random.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x = random.nextGaussian();
            double v = 1.0 + c * x;
            if (v <= 0.0) continue;
            v = v * v * v;
            double u = random.nextDouble();
            if (u < 1.0 - 0.0331 * x * x * x * x
                    || Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    private static Sample sample(int id, double[] demand, CovariateVector context,
                                 double weight) {
        LocalDate date = LocalDate.of(2000, 1, 1).plusWeeks(Math.max(0, id));
        PeriodData period = new PeriodData(id, date, date.plusDays(6), demand.clone(),
                0, 0.0, 0.0, 0.0);
        return new Sample(id, period, context.copy(), weight);
    }

    private static Solution solve(ProcurementParams market, List<String> lanes,
                                  List<Sample> samples, CovariateVector context,
                                  Settings settings) throws Exception {
        Solution solution = TRBSVUSolveMethods.solve(market, lanes, samples, context,
                Method.NOMINAL, 0.0, settings);
        if (!solution.certifiedOptimal) {
            throw new IllegalStateException("Uncertified beta SAA probe: " + solution.solverStatus);
        }
        return solution;
    }

    private static void write(List<String> rows, int replication, String distribution,
                              double capacityScale, int trainingSize, String method, Solution solution,
                              ProcurementParams market, List<Sample> oos) throws Exception {
        Oos result = TRBSVUSolveMethods.evaluate(market, solution.y, oos);
        rows.add(String.format(Locale.ROOT,
                "%d\t%s\t%.4f\t%d\t%s\t%s\t%s\t%.10f\t%.6f\t%d\t%s"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                replication, distribution, capacityScale, trainingSize, method,
                solution.solverStatus,
                solution.certifiedOptimal, solution.objValue, solution.solveTimeSec,
                selectedCount(solution.y), selected(solution.y), result.mean(),
                result.standardDeviation(), result.q95(), result.cvar95(), result.maximum(),
                result.spotShare(), result.meanPenalty()));
    }

    private static ProcurementParams scaleCapacity(ProcurementParams source, double scale) {
        if (!(scale > 0.0) || scale > 1.0) {
            throw new IllegalArgumentException("Capacity scale must lie in (0,1].");
        }
        double[][] capacity = new double[source.I][source.J];
        double[][] rates = new double[source.I][source.J];
        boolean[][] eligible = new boolean[source.I][source.J];
        for (int i = 0; i < source.I; i++) {
            double totalCapacity = 0.0;
            for (int j = 0; j < source.J; j++) {
                capacity[i][j] = scale * source.q[i][j];
                rates[i][j] = source.r[i][j];
                eligible[i][j] = source.eligible[i][j];
                totalCapacity += capacity[i][j];
            }
            if (source.p[i] > totalCapacity + 1e-9) {
                throw new IllegalArgumentException(
                        "Capacity scale makes MQC infeasible for carrier " + i);
            }
        }
        return new ProcurementParams(source.carriers, source.J, source.e.clone(),
                source.p.clone(), source.h.clone(), capacity, rates, eligible,
                source.alpha, source.beta);
    }

    private static int selectedCount(double[] selection) {
        int count = 0;
        for (double value : selection) if (value > 0.5) count++;
        return count;
    }

    private static String selected(double[] selection) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < selection.length; i++) {
            if (selection[i] <= 0.5) continue;
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
