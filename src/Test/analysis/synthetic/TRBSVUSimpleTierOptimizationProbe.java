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

/** Fixed-parameter optimization smoke for the simple three-level positive DGP. */
public final class TRBSVUSimpleTierOptimizationProbe {
    private static final int CARRIERS = 12;
    private static final int LANES = 20;
    private static final int HISTORY = 60;
    private static final int OOS = 500;
    private static final double LAMBDA = 0.5;

    private TRBSVUSimpleTierOptimizationProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: <output-directory> [replications]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        int replications = args.length == 2 ? Integer.parseInt(args[1]) : 3;
        if (replications <= 0) throw new IllegalArgumentException("Replications must be positive.");
        Files.createDirectories(output);
        Settings settings = new Settings(1, 600, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<String> rows = new ArrayList<>();
        rows.add("replication\tmethod\tbandwidth\tlambda\tstatus\tcertified\tobjective\tgap"
                + "\tsolve_sec\tselected_count\tselected\toos_mean\toos_sd\toos_q95"
                + "\toos_cvar95\toos_max\tspot_share\tmqc_penalty");
        SplittableRandom seeds = new SplittableRandom(20260917L);
        for (int replication = 1; replication <= replications; replication++) {
            TRBSVUSyntheticCase.Seeds paired = new TRBSVUSyntheticCase.Seeds(
                    seeds.nextLong(), seeds.nextLong(), seeds.nextLong(),
                    seeds.nextLong(), seeds.nextLong());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    LANES, HISTORY, paired.demandParameters(), 1.0,
                    ContextStructure.DENSE_WIDE_POSITIVE, BaseStructure.THREE_LEVEL_WIDE);
            Replication demand = TRBSVUSyntheticDemandGenerator.generate(parameters,
                    Distribution.NORMAL, Volatility.LOW, OOS, paired.contexts(),
                    paired.historicalNoise(), paired.oosNoise());
            ProcurementParams market = TRBSVUProcurementGenerator.generate(
                    CARRIERS, parameters.typicalDemand(), paired.procurement());
            TRBSVUSyntheticCase instance = new TRBSVUSyntheticCase(market, laneNames(),
                    demand.history, demand.testContext, demand.oos, paired);
            TRBSVUSyntheticCaseIO.saveText(instance, output.resolve(String.format(
                    Locale.ROOT, "rep%02d.instance.tsv", replication)));
            run(rows, replication, "D", Double.NaN, 0.0, instance,
                    TRBSVUScenarioWeights.arithmeticMean(instance.history), Method.NOMINAL, settings);
            run(rows, replication, "SAA", Double.NaN, 0.0, instance,
                    TRBSVUScenarioWeights.equal(instance.history), Method.NOMINAL, settings);
            for (double bandwidth : new double[] {0.1, 0.5, 1.0, 3.0}) {
                List<Sample> contextual = TRBSVUScenarioWeights.kernel(instance.history,
                        instance.testContext, TRBSVUScenarioWeights.Kernel.EXPONENTIAL, bandwidth);
                run(rows, replication, "CSAA", bandwidth, 0.0, instance,
                        contextual, Method.NOMINAL, settings);
                if (bandwidth == 0.5 || bandwidth == 1.0) {
                    run(rows, replication, "C_CHI2", bandwidth, LAMBDA, instance,
                            contextual, Method.CHI_SQUARED, settings);
                }
                if (bandwidth == 0.5) {
                    run(rows, replication, "RCSAA", bandwidth, LAMBDA, instance,
                            contextual, Method.RCSAA, settings);
                }
            }
            Files.write(output.resolve("optimization_probe.tsv"), rows, StandardCharsets.UTF_8);
        }
        rows.forEach(System.out::println);
    }

    private static void run(List<String> rows, int replication, String name, double bandwidth,
                            double lambda, TRBSVUSyntheticCase instance, List<Sample> weighted,
                            Method method, Settings settings) throws Exception {
        Solution solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                weighted, instance.testContext, method, lambda, settings);
        Oos oos = TRBSVUSolveMethods.evaluate(instance.params, solution.y, instance.oos);
        rows.add(String.format(Locale.ROOT,
                "%d\t%s\t%s\t%.6f\t%s\t%s\t%.10f\t%.10g\t%.6f\t%d\t%s"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                replication, name, Double.isNaN(bandwidth) ? "NA"
                        : String.format(Locale.ROOT, "%.1f", bandwidth), lambda,
                solution.solverStatus, solution.certifiedOptimal, solution.objValue,
                solution.relativeGap, solution.solveTimeSec, selectedCount(solution.y),
                selected(solution.y), oos.mean(), oos.standardDeviation(), oos.q95(),
                oos.cvar95(), oos.maximum(), oos.spotShare(), oos.meanPenalty()));
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
