package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.BaseStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ConditionalQuery;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.ContextStructure;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.MultiQueryReplication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
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
 * Checks whether the single-sample cardinality mechanism propagates to
 * different D/SAA/CSAA decisions. This is a diagnostic, not a formal experiment.
 */
public final class TRBSVUPersistentRateMqcMethodProbe {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int HISTORY = 60;
    private static final int OOS = 500;
    private static final double BANDWIDTH = 0.5;

    private TRBSVUPersistentRateMqcMethodProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: <output-directory> [replications] [queries] [mqc-scale]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int replications = args.length >= 2 ? Integer.parseInt(args[1]) : 3;
        int queryCount = args.length >= 3 ? Integer.parseInt(args[2]) : 20;
        double mqcScale = args.length >= 4 ? Double.parseDouble(args[3]) : 1.50;
        Files.createDirectories(output);

        Settings settings = new Settings(1, 600, 1e-8,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<String> rows = new ArrayList<>();
        rows.add("market\treplication\tquery\tmethod\tstatus\tcertified\tobjective\tgap"
                + "\tsolve_sec\tess\tselected_count\tselected\tnominal_total"
                + "\toos_mean\toos_sd\toos_q95\toos_cvar95\toos_max\tspot_share"
                + "\tmqc_penalty");

        SplittableRandom seeds = new SplittableRandom(20260917L);
        for (int replication = 1; replication <= replications; replication++) {
            TRBSVUSyntheticCase.Seeds paired = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(),
                    seeds.nextLong(), seeds.nextLong());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, HISTORY, paired.demandParameters(), 1.0,
                    ContextStructure.DENSE_INDEPENDENT_LEVELS,
                    BaseStructure.THREE_LEVEL_WIDE);
            MultiQueryReplication demand = TRBSVUSyntheticDemandGenerator.generateMultiQuery(
                    parameters, Distribution.LOGNORMAL, Volatility.MEDIUM, queryCount, OOS,
                    paired.contexts(), paired.historicalNoise(), paired.oosNoise());
            ProcurementParams current = TRBSVUProcurementGenerator.generate(
                    CARRIERS, parameters.typicalDemand(), paired.procurement());
            ProcurementParams persistent =
                    TRBSVUSingleSampleCardinalityDiagnostic.persistentCarrierRates(current,
                            paired.procurement() ^ 0x5DEECE66DL, 0.7, 1.3);
            ProcurementParams candidate =
                    TRBSVUSingleSampleCardinalityDiagnostic.scaleMqc(persistent, mqcScale);

            runMarket(rows, "CURRENT", replication, parameters, demand, current, settings);
            runMarket(rows, String.format(Locale.ROOT, "PERSISTENT_WIDE_MQC_%.2f", mqcScale),
                    replication,
                    parameters, demand, candidate, settings);
            Files.write(output.resolve("method_probe.tsv"), rows, StandardCharsets.UTF_8);
        }
    }

    private static void runMarket(List<String> rows, String marketName, int replication,
                                  Parameters parameters, MultiQueryReplication demand,
                                  ProcurementParams market, Settings settings) throws Exception {
        List<String> lanes = laneNames();
        ConditionalQuery first = demand.queries.get(0);
        Solution d = solve(market, lanes,
                TRBSVUScenarioWeights.arithmeticMean(demand.history), first, settings);
        Solution saa = solve(market, lanes,
                TRBSVUScenarioWeights.equal(demand.history), first, settings);

        for (int query = 0; query < demand.queries.size(); query++) {
            ConditionalQuery conditional = demand.queries.get(query);
            write(rows, marketName, replication, query, "D", d, 1.0,
                    parameters, conditional, market);
            write(rows, marketName, replication, query, "SAA", saa, HISTORY,
                    parameters, conditional, market);
            List<Sample> contextual = TRBSVUScenarioWeights.kernel(demand.history,
                    conditional.context, TRBSVUScenarioWeights.Kernel.EXPONENTIAL, BANDWIDTH);
            Solution csaa = solve(market, lanes, contextual, conditional, settings);
            write(rows, marketName, replication, query, "CSAA", csaa,
                    TRBSVUExperiment1Runner.ess(contextual),
                    parameters, conditional, market);
        }
    }

    private static Solution solve(ProcurementParams market, List<String> lanes,
                                  List<Sample> samples, ConditionalQuery query,
                                  Settings settings) throws Exception {
        Solution solution = TRBSVUSolveMethods.solve(market, lanes, samples,
                query.context, Method.NOMINAL, 0.0, settings);
        if (!solution.certifiedOptimal) {
            throw new IllegalStateException("Uncertified method probe solve: "
                    + solution.solverStatus);
        }
        return solution;
    }

    private static void write(List<String> rows, String marketName, int replication,
                              int query, String method, Solution solution, double ess,
                              Parameters parameters, ConditionalQuery conditional,
                              ProcurementParams market) throws Exception {
        Oos oos = TRBSVUSolveMethods.evaluate(market, solution.y, conditional.oos);
        double nominalTotal = 0.0;
        for (double value : parameters.nominalDemand(conditional.context)) nominalTotal += value;
        rows.add(String.format(Locale.ROOT,
                "%s\t%d\t%d\t%s\t%s\t%s\t%.10f\t%.10g\t%.6f\t%.10f\t%d\t%s"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                marketName, replication, query, method, solution.solverStatus,
                solution.certifiedOptimal, solution.objValue, solution.relativeGap,
                solution.solveTimeSec, ess, selectedCount(solution.y), selected(solution.y),
                nominalTotal, oos.mean(), oos.standardDeviation(), oos.q95(), oos.cvar95(),
                oos.maximum(), oos.spotShare(), oos.meanPenalty()));
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
