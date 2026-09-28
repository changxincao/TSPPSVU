package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextDistribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Paired diagnostic for mechanisms that may make conditional chi-square DRO
 * useful.  It never changes the formal frozen cases or their outputs.
 */
public final class TRBSVUDroMechanismProbe {
    private static final int I = 15;
    private static final int J = 50;
    private static final int H = 75;
    private static final int OOS = 1000;
    private static final double BANDWIDTH = 1.0;
    private static final double[] LAMBDAS = {0.25, 0.5, 1.0};

    private record Cell(String name, Volatility volatility,
                        double loadingLower, double loadingUpper,
                        double localRateHalfwidth) { }

    private static final List<Cell> CELLS = List.of(
            new Cell("LOW_INDEPENDENT", Volatility.LOW, 0.0, 0.0, 0.1),
            new Cell("LOW_BASE_COMMON", Volatility.LOW, 0.1, 0.3, 0.1),
            new Cell("LOW_MODERATE_COMMON", Volatility.LOW, 0.3, 0.5, 0.1),
            new Cell("LOW_STRONG_COMMON", Volatility.LOW, 0.5, 0.7, 0.1),
            new Cell("MEDIUM_INDEPENDENT", Volatility.MEDIUM, 0.0, 0.0, 0.1),
            new Cell("MEDIUM_BASE_COMMON", Volatility.MEDIUM, 0.1, 0.3, 0.1),
            new Cell("MEDIUM_MODERATE_COMMON", Volatility.MEDIUM, 0.3, 0.5, 0.1),
            new Cell("HIGH_INDEPENDENT", Volatility.HIGH, 0.0, 0.0, 0.1),
            new Cell("HIGH_BASE_COMMON", Volatility.HIGH, 0.1, 0.3, 0.1),
            new Cell("HIGH_MODERATE_COMMON", Volatility.HIGH, 0.3, 0.5, 0.1),
            new Cell("LOW_RATE_WIDE", Volatility.LOW, 0.1, 0.3, 0.2),
            new Cell("LOW_JOINT", Volatility.LOW, 0.3, 0.5, 0.2));

    private TRBSVUDroMechanismProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Usage: <output-directory> [replications=1] [queries=3]"
                            + " [base-seed=20260915] [cell-regex=.*]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int replications = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int queryCount = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        long baseSeed = args.length > 3 ? Long.parseLong(args[3]) : 20260915L;
        String cellRegex = args.length > 4 ? args[4] : ".*";
        if (replications < 1 || queryCount < 1) {
            throw new IllegalArgumentException("Replication and query counts must be positive.");
        }
        Files.createDirectories(output);
        List<String> rows = new ArrayList<>();
        rows.add("cell\treplication\tquery\tmethod\tparameter\tstatus\tcertified\tgap"
                + "\tsolve_sec\tess\tselected_count\tselected\ttraining_objective"
                + "\toos_mean\toos_sd\toos_q95\toos_cvar95\toos_max"
                + "\ttransport_cost\tspot_cost\tmqc_penalty\tspot_share"
                + "\tmean_lane_cv\ttotal_demand_cv\tmean_lane_correlation"
                + "\tmean_delta_pct\tsd_delta_pct\tq95_delta_pct\tcvar_delta_pct"
                + "\tselected_count_delta\tcommon_loading_lower\tcommon_loading_upper"
                + "\tlocal_rate_halfwidth\tvolatility"
                + "\tquery_market\tquery_trend\tquery_promotion\tquery_attention");

        int threads = Integer.getInteger("trb.probe.threads", 4);
        int limitSeconds = Integer.getInteger("trb.probe.limitSeconds", 3600);
        Settings settings = new Settings(threads, limitSeconds, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);

        for (int replication = 0; replication < replications; replication++) {
            SplittableRandom random = new SplittableRandom(baseSeed + replication);
            TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                    random.nextLong(), random.nextLong(), random.nextLong(),
                    random.nextLong(), random.nextLong());
            for (Cell cell : CELLS) {
                if (!cell.name().matches(cellRegex)) continue;
                Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                        J, H, seeds.demandParameters(), 10.0,
                        ContextStructure.DENSE_INDEPENDENT_UNIFORM_POSITIVE,
                        BaseStructure.UNIFORM_10_100,
                        cell.loadingLower(), cell.loadingUpper());
                MultiQueryReplication demand =
                        TRBSVUSyntheticDemandGenerator.generateMultiQueryWithLinearTrend(
                                parameters, Distribution.NORMAL, cell.volatility(), queryCount,
                                OOS, seeds.contexts(), seeds.historicalNoise(), seeds.oosNoise(),
                                ContextDistribution.UNIFORM);
                ProcurementParams market = TRBSVUProcurementGenerator.generate(
                        I, parameters.linearTrendTypicalDemand(), seeds.procurement(),
                        cell.localRateHalfwidth());
                List<String> lanes = laneNames();
                for (int query = 0; query < queryCount; query++) {
                    var conditional = demand.queries.get(query);
                    List<Sample> weighted = TRBSVUScenarioWeights.kernel(
                            demand.history, conditional.context, Kernel.TRIANGULAR, BANDWIDTH);
                    if (weighted.isEmpty()) {
                        throw new IllegalStateException("B=1 has no support for "
                                + cell.name() + " query " + query);
                    }
                    DemandDiagnostics diagnostics = diagnostics(conditional.oos);
                    Solution csaa = TRBSVUSolveMethods.solve(market, lanes, weighted,
                            conditional.context, Method.NOMINAL, 0.0, settings);
                    Oos csaaOos = TRBSVUSolveMethods.evaluate(market, csaa.y, conditional.oos);
                    append(rows, cell, replication, query, "CSAA-Tri", 0.0,
                            csaa, csaaOos, csaaOos, diagnostics, weighted,
                            selectedCount(csaa.y), conditional.context.values());
                    checkpoint(output, rows);
                    for (double lambda : LAMBDAS) {
                        Solution robust = TRBSVUSolveMethods.solve(market, lanes, weighted,
                                conditional.context, Method.CHI_SQUARED, lambda, settings);
                        Oos robustOos = TRBSVUSolveMethods.evaluate(
                                market, robust.y, conditional.oos);
                        append(rows, cell, replication, query, "C-Chi2", lambda,
                                robust, robustOos, csaaOos, diagnostics, weighted,
                                selectedCount(csaa.y), conditional.context.values());
                        checkpoint(output, rows);
                    }
                }
            }
        }
    }

    private static void append(List<String> rows, Cell cell, int replication, int query,
                               String method, double parameter, Solution solution, Oos oos,
                               Oos baseline, DemandDiagnostics diagnostics,
                               List<Sample> weighted, int baselineSelectedCount,
                               double[] queryContext) {
        rows.add(String.format(Locale.ROOT,
                "%s\t%d\t%d\t%s\t%.10g\t%s\t%s\t%.10g\t%.6f\t%.10f\t%d\t%s"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f"
                        + "\t%.10f\t%.10f\t%.10f"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f\t%d"
                        + "\t%.4f\t%.4f\t%.4f\t%s"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f",
                cell.name(), replication, query, method, parameter,
                solution.solverStatus, solution.certifiedOptimal, solution.relativeGap,
                solution.solveTimeSec, TRBSVUExperiment1Runner.ess(weighted),
                selectedCount(solution.y), selected(solution.y), solution.objValue,
                oos.mean(), oos.standardDeviation(), oos.q95(), oos.cvar95(), oos.maximum(),
                oos.meanTransportCost(), oos.meanSpotCost(), oos.meanPenalty(), oos.spotShare(),
                diagnostics.meanLaneCv(), diagnostics.totalCv(), diagnostics.meanCorrelation(),
                delta(oos.mean(), baseline.mean()),
                delta(oos.standardDeviation(), baseline.standardDeviation()),
                delta(oos.q95(), baseline.q95()), delta(oos.cvar95(), baseline.cvar95()),
                selectedCount(solution.y) - baselineSelectedCount,
                cell.loadingLower(), cell.loadingUpper(), cell.localRateHalfwidth(),
                cell.volatility(), queryContext[0], queryContext[1],
                queryContext[2], queryContext[3]));
    }

    private static double delta(double value, double baseline) {
        return 100.0 * (value - baseline) / baseline;
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
            if (!result.isEmpty()) result.append(';');
            result.append(i + 1);
        }
        return result.toString();
    }

    private static List<String> laneNames() {
        List<String> lanes = new ArrayList<>(J);
        for (int j = 0; j < J; j++) lanes.add("L" + (j + 1));
        return lanes;
    }

    private static void checkpoint(Path output, List<String> rows) throws Exception {
        Files.write(output.resolve("mechanism_probe.tsv"), rows, StandardCharsets.UTF_8);
    }

    private record DemandDiagnostics(double meanLaneCv, double totalCv,
                                     double meanCorrelation) { }

    private static DemandDiagnostics diagnostics(List<Sample> samples) {
        int n = samples.size();
        double[][] values = new double[J][n];
        double[] total = new double[n];
        for (int s = 0; s < n; s++) {
            double[] demand = samples.get(s).demand();
            for (int j = 0; j < J; j++) {
                values[j][s] = demand[j];
                total[s] += demand[j];
            }
        }
        double[] mean = new double[J];
        double[] sd = new double[J];
        double meanLaneCv = 0.0;
        for (int j = 0; j < J; j++) {
            mean[j] = mean(values[j]);
            sd[j] = sampleSd(values[j], mean[j]);
            meanLaneCv += sd[j] / mean[j] / J;
        }
        double correlation = 0.0;
        int pairs = 0;
        for (int j = 0; j < J; j++) {
            for (int k = j + 1; k < J; k++) {
                if (sd[j] == 0.0 || sd[k] == 0.0) continue;
                double covariance = 0.0;
                for (int s = 0; s < n; s++) {
                    covariance += (values[j][s] - mean[j]) * (values[k][s] - mean[k]);
                }
                correlation += covariance / (n - 1) / sd[j] / sd[k];
                pairs++;
            }
        }
        double totalMean = mean(total);
        return new DemandDiagnostics(meanLaneCv, sampleSd(total, totalMean) / totalMean,
                pairs == 0 ? 0.0 : correlation / pairs);
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static double sampleSd(double[] values, double mean) {
        double sum = 0.0;
        for (double value : values) {
            double difference = value - mean;
            sum += difference * difference;
        }
        return Math.sqrt(sum / (values.length - 1));
    }
}
