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
    private static final double BANDWIDTH = Double.parseDouble(
            System.getProperty("trb.timing.bandwidth", "5.0"));
    private static final double LAMBDA = Double.parseDouble(
            System.getProperty("trb.timing.lambda", "0.5"));
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
        if (!(BANDWIDTH > 0.0)) throw new IllegalArgumentException("Invalid bandwidth: " + BANDWIDTH);
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(instanceFile);
        if (instance.params.I != TRBSVUFormalProtocol.CARRIERS
                || instance.params.J != TRBSVUFormalProtocol.LANES
                || instance.history.size() != TRBSVUFormalProtocol.HISTORY_PERIODS
                || instance.oos.size() != TRBSVUFormalProtocol.OOS_DRAWS) {
            throw new IllegalArgumentException("Current-scale timing requires I="
                    + TRBSVUFormalProtocol.CARRIERS + ", J=" + TRBSVUFormalProtocol.LANES
                    + ", S=" + TRBSVUFormalProtocol.HISTORY_PERIODS + ", OOS="
                    + TRBSVUFormalProtocol.OOS_DRAWS + ".");
        }
        int betaOverride = Integer.getInteger("trb.timing.betaOverride", instance.params.beta);
        double capacityScale = Double.parseDouble(System.getProperty("trb.timing.capacityScale", "1.0"));
        double laneCapacityRatio = Double.parseDouble(
                System.getProperty("trb.timing.laneCapacityRatio", "NaN"));
        double mqcCapacityRatio = Double.parseDouble(
                System.getProperty("trb.timing.mqcCapacityRatio", "NaN"));
        if (!(capacityScale > 0.0)) throw new IllegalArgumentException("Invalid capacity scale: " + capacityScale);
        if (Double.isFinite(laneCapacityRatio) && !(laneCapacityRatio > 0.0))
            throw new IllegalArgumentException("Invalid lane capacity ratio: " + laneCapacityRatio);
        if (Double.isFinite(mqcCapacityRatio)
                && (!(mqcCapacityRatio > 0.0) || mqcCapacityRatio > 1.0))
            throw new IllegalArgumentException("Invalid MQC/capacity ratio: " + mqcCapacityRatio);
        if (Double.isFinite(laneCapacityRatio) && capacityScale != 1.0)
            throw new IllegalArgumentException("Use either capacityScale or laneCapacityRatio, not both.");
        if (betaOverride != instance.params.beta || capacityScale != 1.0
                || Double.isFinite(laneCapacityRatio) || Double.isFinite(mqcCapacityRatio)) {
            if (betaOverride < instance.params.alpha || betaOverride > instance.params.I)
                throw new IllegalArgumentException("Invalid beta override: " + betaOverride);
            ProcurementParams source = instance.params;
            double[][] scaledCapacity = new double[source.I][source.J];
            if (Double.isFinite(laneCapacityRatio)) {
                double[] typical = readTypicalDemand(instanceFile.resolveSibling("dgp_parameters.csv"), source.J);
                for (int j = 0; j < source.J; j++) {
                    double current = 0.0;
                    for (int i = 0; i < source.I; i++) current += source.q[i][j];
                    if (!(current > 0.0)) throw new IllegalArgumentException("Zero capacity at lane " + (j + 1));
                    double factor = laneCapacityRatio * typical[j] / current;
                    for (int i = 0; i < source.I; i++) scaledCapacity[i][j] = factor * source.q[i][j];
                }
            } else {
                for (int i = 0; i < source.I; i++) {
                    for (int j = 0; j < source.J; j++)
                        scaledCapacity[i][j] = capacityScale * source.q[i][j];
                }
            }
            double[] adjustedMqc = source.p.clone();
            if (Double.isFinite(mqcCapacityRatio)) {
                for (int i = 0; i < source.I; i++) {
                    double carrierCapacity = 0.0;
                    for (int j = 0; j < source.J; j++) carrierCapacity += scaledCapacity[i][j];
                    adjustedMqc[i] = mqcCapacityRatio * carrierCapacity;
                }
            }
            ProcurementParams adjusted = new ProcurementParams(source.carriers, source.J,
                    source.e.clone(), adjustedMqc, source.h.clone(), scaledCapacity,
                    copy(source.r), copy(source.eligible),
                    source.alpha, betaOverride);
            for (int i = 0; i < adjusted.I; i++) {
                if (adjusted.p[i] > adjusted.M[i] + 1e-9)
                    throw new IllegalArgumentException("Capacity scale makes MQC exceed capacity for carrier " + (i + 1));
            }
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
            TRBSVUSolveMethods.Oos oos = TRBSVUSolveMethods.evaluate(instance.params, solution.y, instance.oos);
            write(output, String.format(Locale.ROOT,
                    "method\tstatus\tcertified\tobjective\tbest_bound\tgap\tmodel_and_solve_seconds\twall_seconds\tselected\tselected_indices\talpha\tbeta\tcapacity_mode\tcapacity_scale\tlane_capacity_ratio\tmqc_capacity_ratio\tthreads\tlimit_seconds\tparameter\toos_mean\toos_sd\toos_q95\toos_cvar95\toos_max\ttransport_cost\tspot_cost\tpenalty_cost\tspot_share\tcapacity_utilization\tmean_lane_capacity_utilization%n"
                            + "%s\t%s\t%s\t%.17g\t%.17g\t%.17g\t%.6f\t%.6f\t%d\t%s\t%d\t%d\t%s\t%.6f\t%.6f\t%.6f\t%d\t%d\t%s\t%.17g\t%.17g\t%.17g\t%.17g\t%.17g\t%.17g\t%.17g\t%.17g\t%.17g\t%.17g\t%.17g%n",
                    name, solution.solverStatus, solution.certifiedOptimal, solution.objValue,
                    solution.bestBound, solution.relativeGap, solution.solveTimeSec, wall,
                    selected(solution.y), selectedIndices(solution.y), instance.params.alpha,
                    instance.params.beta, Double.isFinite(laneCapacityRatio) ? "LANE_TOTAL" : "PAIR_SCALE",
                    capacityScale, laneCapacityRatio, mqcCapacityRatio,
                    threads, limitSeconds, parameter(name),
                    oos.mean(), oos.standardDeviation(), oos.q95(), oos.cvar95(), oos.maximum(),
                    oos.meanTransportCost(), oos.meanSpotCost(), oos.meanPenalty(), oos.spotShare(),
                    oos.capacityUtilization(), oos.meanLaneCapacityUtilization()));
        } catch (Throwable failure) {
            double wall = (System.nanoTime() - started) / 1.0e9;
            write(output, "method\tstatus\tcertified\tobjective\tbest_bound\tgap\tmodel_and_solve_seconds\twall_seconds\tselected\tcapacity_mode\tcapacity_scale\tlane_capacity_ratio\tmqc_capacity_ratio\tthreads\tlimit_seconds\tparameter\terror\n"
                    + name + "\tFAILED\tfalse\tNaN\tNaN\tNaN\tNaN\t" + wall
                    + "\t0\t" + (Double.isFinite(laneCapacityRatio) ? "LANE_TOTAL" : "PAIR_SCALE")
                    + "\t" + capacityScale + "\t" + laneCapacityRatio + "\t" + mqcCapacityRatio
                    + "\t" + threads + "\t" + limitSeconds + "\t" + parameter(name)
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
                    .weights(instance.history, instance.testContext,
                            TRBSVUExperiment1Runner.forestSeed(instance, instance.history)), settings);
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

    private static double[] readTypicalDemand(Path file, int lanes) throws Exception {
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (rows.size() != lanes + 1)
            throw new IllegalArgumentException("Unexpected DGP parameter row count: " + file);
        String[] header = rows.get(0).split(",", -1);
        int column = -1;
        for (int k = 0; k < header.length; k++) {
            if (header[k].equals("typical_demand")) column = k;
        }
        if (column < 0) throw new IllegalArgumentException("Missing typical_demand column: " + file);
        double[] result = new double[lanes];
        for (int row = 1; row < rows.size(); row++) {
            String[] fields = rows.get(row).split(",", -1);
            int lane = Integer.parseInt(fields[0]);
            result[lane] = Double.parseDouble(fields[column]);
        }
        for (int j = 0; j < lanes; j++) {
            if (!(result[j] > 0.0)) throw new IllegalArgumentException("Invalid typical demand at lane " + (j + 1));
        }
        return result;
    }
}
