package Test.analysis.synthetic;

import Basic.Sample;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Fixed-parameter pilot comparing the original and same-mean wider positive loadings. */
public final class TRBSVUWideSameMeanOptimizationProbe {
    private TRBSVUWideSameMeanOptimizationProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4)
            throw new IllegalArgumentException("Usage: <data-directory> <output-directory> "
                    + "[structures] [replications]");
        Path data = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        List<String> structures = args.length == 3
                ? Arrays.asList(args[2].split(","))
                : List.of("dense_proportional", "dense_wide_same_mean");
        if (args.length == 4) structures = Arrays.asList(args[2].split(","));
        int replications = args.length == 4 ? Integer.parseInt(args[3]) : 3;
        if (replications <= 0) throw new IllegalArgumentException("Replications must be positive.");
        Files.createDirectories(output);
        Settings settings = new Settings(1, 600, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<String> rows = new ArrayList<>();
        rows.add("replication\tstructure\tmethod\tobjective\tstatus\tcertified\tgap"
                + "\tsolve_sec\tselected_count\tselected\toos_mean\toos_sd\toos_q95"
                + "\toos_cvar95\toos_max\tspot_share\tmqc_penalty");
        for (int replication = 1; replication <= replications; replication++) {
            for (String structure : structures) {
                Path file = data.resolve(String.format(Locale.ROOT, "rep%02d_%s.instance.tsv",
                        replication, structure));
                TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(file);
                run(rows, replication, structure, "D", instance,
                        TRBSVUScenarioWeights.arithmeticMean(instance.history), settings);
                run(rows, replication, structure, "SAA", instance,
                        TRBSVUScenarioWeights.equal(instance.history), settings);
                for (double bandwidth : new double[] {0.1, 0.5, 1.0, 3.0, 5.0}) {
                    run(rows, replication, structure,
                            String.format(Locale.ROOT, "CSAA_B%.1f", bandwidth), instance,
                            TRBSVUScenarioWeights.kernel(instance.history, instance.testContext,
                                    Kernel.EXPONENTIAL, bandwidth), settings);
                }
            }
        }
        Files.write(output.resolve("optimization_probe.tsv"), rows, StandardCharsets.UTF_8);
        rows.forEach(System.out::println);
    }

    private static void run(List<String> rows, int replication, String structure, String name,
                            TRBSVUSyntheticCase instance, List<Sample> weighted,
                            Settings settings) throws Exception {
        Solution solution = TRBSVUSolveMethods.solve(instance.params, instance.lanes, weighted,
                instance.testContext, Method.NOMINAL, 0.0, settings);
        Oos oos = TRBSVUSolveMethods.evaluate(instance.params, solution.y, instance.oos);
        rows.add(String.format(Locale.ROOT,
                "%d\t%s\t%s\t%.10f\t%s\t%s\t%.10g\t%.6f\t%d\t%s"
                        + "\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f\t%.10f",
                replication, structure, name, solution.objValue, solution.solverStatus,
                solution.certifiedOptimal, solution.relativeGap, solution.solveTimeSec,
                selectedCount(solution.y), selected(solution.y), oos.mean(),
                oos.standardDeviation(), oos.q95(), oos.cvar95(), oos.maximum(),
                oos.spotShare(), oos.meanPenalty()));
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
}
