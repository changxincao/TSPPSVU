package Test.analysis.synthetic;

import Helper.basicHelper.Config;
import Model.DROModel;
import Model.Solution;
import Model.SolverTerminationException;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticInstanceIO.StoredInstance;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Reviewer 3, Comment 4: carrier-count scalability for the paper's
 * neighborhood-search Algorithm 1, with J, S, DGP and all hyperparameters
 * (including kappa) held fixed.
 */
public final class TRBReviewerR3M4CarrierScalability {

    private static final int[] CARRIER_GRID = {10, 20, 30};
    private static final int KAPPA = 2;
    private static final long PROCUREMENT_SEED = 20_260_808L;

    private TRBReviewerR3M4CarrierScalability() {
    }

    /**
     * Usage: {@code <output-root> [seeds] [C_h] [lambda] [threads]
     * [global-time-limit-sec] [verify-I10-by-enumeration]}.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 7) {
            throw new IllegalArgumentException(
                    "Usage: <output-root> [seeds] [C_h] [lambda] [threads] "
                            + "[global-time-limit-sec] [verify-I10-by-enumeration]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        double cH = args.length > 2 ? Double.parseDouble(args[2]) : 1.0;
        double lambda = args.length > 3 ? Double.parseDouble(args[3]) : 1.0;
        int threads = args.length > 4 ? Integer.parseInt(args[4]) : 4;
        int timeLimit = args.length > 5 ? Integer.parseInt(args[5]) : 3600;
        boolean verifyI10 = args.length <= 6 || Boolean.parseBoolean(args[6]);
        if (seeds <= 0 || !(cH > 0.0) || !(lambda > 0.0)
                || threads <= 0 || timeLimit <= 0) {
            throw new IllegalArgumentException("Seeds, C_h, lambda, threads and time limit must be positive.");
        }

        Files.createDirectories(root);
        Path summary = root.resolve("carrier_scalability.csv");
        if (Files.exists(summary)) {
            throw new IllegalArgumentException("Refusing to overwrite existing summary: " + summary);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(summary, StandardCharsets.UTF_8)) {
            writer.write("seed,I,J,S,kappa,C_h,lambda,status,certifiedOptimal,objective,bestBound,relativeGap,"
                    + "optimizerTimeSec,nodeCount,iterations,cuts,candidates,selectedCount,"
                    + "enumerationObjective,enumerationTimeSec,objectiveDifference,yMatchesEnumeration,error");
            writer.newLine();
            for (int seed = 1; seed <= seeds; seed++) {
                for (int carriers : CARRIER_GRID) {
                    runOne(writer, root, seed, carriers, cH, lambda,
                            threads, timeLimit, verifyI10 && carriers == 10);
                    writer.flush();
                }
            }
        }
    }

    private static void runOne(BufferedWriter writer,
                               Path root,
                               int seed,
                               int carriers,
                               double cH,
                               double lambda,
                               int threads,
                               int timeLimit,
                               boolean enumerate) throws Exception {
        Path instanceDirectory = root.resolve(String.format(Locale.US,
                "I%02d_seed%04d", carriers, seed)).resolve("instance");
        if (!Files.isRegularFile(instanceDirectory.resolve("manifest.properties"))) {
            Settings settings = new Settings();
            settings.laneCount = 23;
            settings.trainingSampleCount = 50;
            settings.oosSampleCount = 1;
            settings.observedLagPeriods = 3;
            settings.innovationDistribution = InnovationDistribution.LOGNORMAL;
            settings.innovationCv = 0.30;
            settings.replicationSeed = seed;
            TRBReviewerR3M3SyntheticInstanceBuilder.buildAndSave(
                    instanceDirectory, settings, carriers, PROCUREMENT_SEED);
        }

        StoredInstance stored = TRBReviewerR3M3SyntheticInstanceIO.load(instanceDirectory);
        Config lbbdConfig = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                Method.RCSAA_LBBD_SEARCH, stored.settings.observedLagPeriods,
                cH, lambda, threads, timeLimit);
        lbbdConfig.rcsaaSearchNeighborhoodRadius = KAPPA;
        Solution lbbd = null;
        Solution exact = null;
        String error = "";
        long solveStart = System.nanoTime();
        try {
            var prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                    stored.demandData, stored.procurementParams, lbbdConfig);
            lbbd = new DROModel().solve(prepared.solveData, lbbdConfig);
        } catch (SolverTerminationException termination) {
            lbbd = terminatedSolution(termination, solveStart);
            error = "search:" + safeMessage(termination);
        } catch (Throwable failure) {
            error = "search:" + failure.getClass().getSimpleName() + ":" + safeMessage(failure);
        }

        if (enumerate) {
            try {
                Config enumerateConfig = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                        Method.RCSAA_ENUMERATE, stored.settings.observedLagPeriods,
                        cH, lambda, threads, timeLimit);
                var enumerateInput = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                        stored.demandData, stored.procurementParams, enumerateConfig);
                exact = new DROModel().solve(enumerateInput.solveData, enumerateConfig);
            } catch (Throwable failure) {
                error = appendError(error, "enumeration:" + failure.getClass().getSimpleName()
                        + ":" + safeMessage(failure));
            }
        }
        writeRow(writer, seed, carriers, cH, lambda, lbbd, exact, error);
    }

    private static void writeRow(BufferedWriter writer,
                                 int seed,
                                 int carriers,
                                 double cH,
                                 double lambda,
                                 Solution solution,
                                 Solution enumeration,
                                 String error) throws Exception {
        String status = solution == null ? "FAILED" : solution.solverStatus;
        double objectiveDifference = solution != null && enumeration != null
                ? solution.objValue - enumeration.objValue : Double.NaN;
        String yMatch = solution != null && solution.y != null
                && enumeration != null && enumeration.y != null
                ? String.valueOf(sameY(solution.y, enumeration.y)) : "NA";
        writer.write(String.format(Locale.US,
                "%d,%d,23,50,%d,%.17g,%.17g,%s,%s,%.17g,%.17g,%.17g,%.9f,%d,%d,%d,%d,%d,"
                        + "%.17g,%.9f,%.17g,%s,%s%n",
                seed, carriers, KAPPA, cH, lambda, csv(status),
                solution != null && solution.certifiedOptimal,
                value(solution, Field.OBJECTIVE), value(solution, Field.BOUND),
                value(solution, Field.GAP), value(solution, Field.TIME),
                longValue(solution, Field.NODES), intValue(solution, Field.ITERATIONS),
                intValue(solution, Field.CUTS), longValue(solution, Field.CANDIDATES),
                selectedCount(solution),
                value(enumeration, Field.OBJECTIVE), value(enumeration, Field.TIME),
                objectiveDifference, yMatch, csv(error)));
    }

    private enum Field { OBJECTIVE, BOUND, GAP, TIME, NODES, ITERATIONS, CUTS, CANDIDATES }

    private static double value(Solution solution, Field field) {
        if (solution == null) return Double.NaN;
        return switch (field) {
            case OBJECTIVE -> solution.objValue;
            case BOUND -> solution.bestBound;
            case GAP -> solution.relativeGap;
            case TIME -> solution.solveTimeSec;
            default -> throw new IllegalArgumentException("Not a double field: " + field);
        };
    }

    private static long longValue(Solution solution, Field field) {
        if (solution == null) return -1L;
        return switch (field) {
            case NODES -> solution.nodeCount;
            case CANDIDATES -> solution.candidateCount;
            default -> throw new IllegalArgumentException("Not a long field: " + field);
        };
    }

    private static int intValue(Solution solution, Field field) {
        if (solution == null) return -1;
        return switch (field) {
            case ITERATIONS -> solution.iterationCount;
            case CUTS -> solution.cutCount;
            default -> throw new IllegalArgumentException("Not an int field: " + field);
        };
    }

    private static int selectedCount(Solution solution) {
        if (solution == null || solution.y == null) return -1;
        int count = 0;
        for (double value : solution.y) if (value > 0.5) count++;
        return count;
    }

    private static boolean sameY(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if ((a[i] > 0.5) != (b[i] > 0.5)) return false;
        }
        return true;
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) return "";
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static Solution terminatedSolution(SolverTerminationException termination, long solveStart) {
        Solution solution = new Solution(Double.NaN, null,
                (System.nanoTime() - solveStart) / 1.0e9);
        solution.solverStatus = "NO_INCUMBENT:" + termination.solverStatus;
        solution.bestBound = termination.bestBound;
        solution.relativeGap = termination.relativeGap;
        solution.nodeCount = termination.nodeCount;
        return solution;
    }

    private static String appendError(String current, String addition) {
        return current == null || current.isBlank() ? addition : current + " | " + addition;
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
