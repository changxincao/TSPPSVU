package Test.analysis.synthetic;

import Basic.Sample;
import Basic.ProcurementParams;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Isolated one-method timing probe for a frozen full-scale synthetic case. */
public final class TRBSVUCurrentScaleTimingMain {
    private static final double RETENTION = 0.8;
    private static final double BANDWIDTH = 5.0;
    private static final double LAMBDA = 0.5;
    private static final double W1_RADIUS = 0.1;
    private static final double PCM_KAPPA = 1.5;

    private TRBSVUCurrentScaleTimingMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 5)
            throw new IllegalArgumentException("Usage: <instance.tsv> <method> <output.tsv> <threads> <limitSec>");
        Path instanceFile = Path.of(args[0]).toAbsolutePath().normalize();
        String name = args[1];
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        int threads = Integer.parseInt(args[3]);
        int limitSeconds = Integer.parseInt(args[4]);
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(instanceFile);
        int betaOverride = Integer.getInteger("trb.timing.betaOverride", instance.params.beta);
        if (betaOverride != instance.params.beta) {
            if (betaOverride < instance.params.alpha || betaOverride > instance.params.I)
                throw new IllegalArgumentException("Invalid beta override: " + betaOverride);
            ProcurementParams source = instance.params;
            ProcurementParams adjusted = new ProcurementParams(source.carriers, source.J,
                    source.e, source.p, source.h, source.q, source.r, source.eligible,
                    source.alpha, betaOverride);
            instance = new TRBSVUSyntheticCase(adjusted, instance.lanes, instance.history,
                    instance.testContext, instance.oos, instance.seeds);
        }
        Settings settings = new Settings(threads, limitSeconds, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        List<Sample> unconditional = TRBSVUScenarioWeights.equal(instance.history);
        List<Sample> contextual = TRBSVUScenarioWeights.kernel(instance.history,
                instance.testContext, Kernel.EXPONENTIAL, BANDWIDTH);
        if (contextual.isEmpty()) throw new IllegalStateException("B=5 exponential kernel has no support.");

        long started = System.nanoTime();
        try {
            Solution solution = solve(name, instance, unconditional, contextual, settings);
            double wall = (System.nanoTime() - started) / 1.0e9;
            write(output, String.format(Locale.ROOT,
                    "method\tstatus\tcertified\tobjective\tbest_bound\tgap\tmodel_and_solve_seconds\twall_seconds\tselected\tselected_indices\talpha\tbeta\tthreads\tlimit_seconds\tparameter%n"
                            + "%s\t%s\t%s\t%.17g\t%.17g\t%.17g\t%.6f\t%.6f\t%d\t%s\t%d\t%d\t%d\t%d\t%s%n",
                    name, solution.solverStatus, solution.certifiedOptimal, solution.objValue,
                    solution.bestBound, solution.relativeGap, solution.solveTimeSec, wall,
                    selected(solution.y), selectedIndices(solution.y), instance.params.alpha,
                    instance.params.beta, threads, limitSeconds, parameter(name)));
        } catch (Throwable failure) {
            double wall = (System.nanoTime() - started) / 1.0e9;
            write(output, "method\tstatus\tcertified\tobjective\tbest_bound\tgap\tmodel_and_solve_seconds\twall_seconds\tselected\tthreads\tlimit_seconds\tparameter\terror\n"
                    + name + "\tFAILED\tfalse\tNaN\tNaN\tNaN\tNaN\t" + wall
                    + "\t0\t" + threads + "\t" + limitSeconds + "\t" + parameter(name)
                    + "\t" + failure.toString().replace('\t', ' ').replace('\n', ' ') + "\n");
            throw failure;
        }
    }

    private static Solution solve(String name, TRBSVUSyntheticCase instance,
                                  List<Sample> unconditional, List<Sample> contextual,
                                  Settings settings) throws Exception {
        return switch (name) {
            case "D" -> TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                    TRBSVUScenarioWeights.arithmeticMean(instance.history), instance.testContext,
                    Method.NOMINAL, 0.0, settings);
            case "SAA" -> nominal(instance, unconditional, settings);
            case "TUNED_SAA" -> nominal(instance,
                    TRBSVUScenarioWeights.recent(instance.history, RETENTION), settings);
            case "CSAA_EXP" -> nominal(instance, contextual, settings);
            case "RF_CSAA" -> nominal(instance, new TRBSVUForestWeights(
                    Path.of(".venv-rsome", "Scripts", "python.exe").toString(),
                    Path.of("analysis", "trb_svu", "rf_leaf_weights.py"))
                    .weights(instance.history, instance.testContext, instance.seeds.contexts() + 100L), settings);
            case "RSAA" -> robust(instance, unconditional, Method.RCSAA, LAMBDA, settings);
            case "RCSAA" -> robust(instance, contextual, Method.RCSAA, LAMBDA, settings);
            case "U_CHI2" -> robust(instance, unconditional, Method.CHI_SQUARED, LAMBDA, settings);
            case "C_CHI2" -> robust(instance, contextual, Method.CHI_SQUARED, LAMBDA, settings);
            case "U_W1" -> robust(instance, unconditional, Method.WASSERSTEIN, W1_RADIUS, settings);
            case "C_W1" -> robust(instance, contextual, Method.WASSERSTEIN, W1_RADIUS, settings);
            case "U_PCM" -> pcm(instance, unconditional, settings);
            case "C_PCM" -> pcm(instance, contextual, settings);
            case "U_PCM_DEMAND_ONLY" -> pcm(instance, unconditional, settings, false);
            default -> throw new IllegalArgumentException("Unknown timing method: " + name);
        };
    }

    private static Solution nominal(TRBSVUSyntheticCase instance, List<Sample> samples,
                                    Settings settings) throws Exception {
        return TRBSVUSolveMethods.solve(instance.params, instance.lanes, samples,
                instance.testContext, Method.NOMINAL, 0.0, settings);
    }

    private static Solution robust(TRBSVUSyntheticCase instance, List<Sample> samples,
                                   Method method, double parameter, Settings settings) throws Exception {
        return TRBSVUSolveMethods.solve(instance.params, instance.lanes, samples,
                instance.testContext, method, parameter, settings);
    }

    private static Solution pcm(TRBSVUSyntheticCase instance, List<Sample> samples,
                                Settings settings) throws Exception {
        return pcm(instance, samples, settings, true);
    }

    private static Solution pcm(TRBSVUSyntheticCase instance, List<Sample> samples,
                                Settings settings, boolean adaptToLift) throws Exception {
        return new TRBSVUPcmSolver(Path.of(".venv-rsome", "Scripts", "python.exe"),
                Path.of("analysis", "trb_svu", "solve_pcm.py"))
                .solve(instance.params, samples, PCM_KAPPA, settings, adaptToLift);
    }

    private static String parameter(String method) {
        return switch (method) {
            case "TUNED_SAA" -> "retention=" + RETENTION;
            case "CSAA_EXP", "RF_CSAA" -> method.equals("CSAA_EXP")
                    ? "kernel=EXPONENTIAL;B=" + BANDWIDTH : "trees=500";
            case "RSAA", "RCSAA", "U_CHI2", "C_CHI2" -> "lambda=" + LAMBDA;
            case "U_W1", "C_W1" -> "epsilon=" + W1_RADIUS;
            case "U_PCM", "C_PCM" -> "kappa=" + PCM_KAPPA + ";policy=lifted_affine";
            case "U_PCM_DEMAND_ONLY" -> "kappa=" + PCM_KAPPA + ";policy=demand_affine";
            default -> "none";
        };
    }

    private static int selected(double[] y) {
        if (y == null) return 0;
        int count = 0;
        for (double value : y) if (value > 0.5) count++;
        return count;
    }

    private static String selectedIndices(double[] y) {
        if (y == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < y.length; i++) {
            if (y[i] <= 0.5) continue;
            if (!out.isEmpty()) out.append(',');
            out.append(i + 1);
        }
        return out.toString();
    }

    private static void write(Path output, String content) throws Exception {
        Files.createDirectories(output.getParent());
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }
}
