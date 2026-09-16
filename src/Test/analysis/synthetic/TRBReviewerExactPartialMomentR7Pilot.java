package Test.analysis.synthetic;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Model.ExactMeanMadDROSolver;
import Model.ExactPartialMomentDROSolver;
import Model.SAAModel;
import Model.SecondStageEvaluator;
import Model.Solution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSolveBridge.PreparedInput;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One-query R7 pilot for the exact unrestricted-recourse partial-moment DRO. */
public final class TRBReviewerExactPartialMomentR7Pilot {
    private static final int DEFAULT_I = 10;
    private static final int DEFAULT_J = 23;
    private static final int OOS_DRAWS = 200;
    private static final long TRAINING_SEED = 1_000_101L;
    private static final long QUERY_SEED = 2_101_001L;

    private TRBReviewerExactPartialMomentR7Pilot() {
    }

    /** Usage: {@code <empty-output-dir> [threads] [time-limit-sec] [max-iterations] [I] [J] [tolerance]}. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 7) {
            throw new IllegalArgumentException(
                    "Usage: <empty-output-dir> [threads] [time-limit-sec] "
                            + "[max-iterations] [I] [J] [tolerance]");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 1;
        int timeLimitSeconds = args.length > 2 ? Integer.parseInt(args[2]) : 300;
        int maxIterations = args.length > 3 ? Integer.parseInt(args[3]) : 100;
        int carrierCount = args.length > 4 ? Integer.parseInt(args[4]) : DEFAULT_I;
        int laneCount = args.length > 5 ? Integer.parseInt(args[5]) : DEFAULT_J;
        double tolerance = args.length > 6 ? Double.parseDouble(args[6]) : 1e-5;
        requireEmpty(root);
        Files.createDirectories(root);

        double[] baseline = TRBReviewerGroupSpecializedProcurementFactory.moderateBaseline(
                laneCount, 100.0 * laneCount, 0.65, 20260809L);
        int[] groups = TRBReviewerGroupSpecializedProcurementFactory.balancedLaneGroups(
                laneCount, Math.min(3, laneCount), 20260810L);
        Config procurementConfig = new Config();
        procurementConfig.seed = 0;
        ProcurementParams fullMarket = InstanceGenerator.generate(
                carrierCount, baseline, new InstanceGenerator.GenConfig(), procurementConfig);
        ProcurementParams params = TRBReviewerR7CoverageMqcGridExperiment.withNestedCoverage(
                fullMarket, baseline, 1.0, 1.0, "min", false);

        ReplicationData replication = TRBReviewerDecisionRelevantDemandGenerator.generate(
                TRBReviewerR7CoverageMqcGridExperiment.r7Settings(OOS_DRAWS),
                baseline, groups, TRAINING_SEED, QUERY_SEED);
        log1pContexts(replication);
        Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                Method.CSAA, 3, 1.0, 1.0, threads, timeLimitSeconds);
        config.maxBendersIter = maxIterations;
        config.tol = tolerance;
        PreparedInput prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                replication, params, config);
        Data data = prepared.solveData;

        MomentInput moments = weightedMoments(data.samples, baseline);
        long csaaStarted = System.nanoTime();
        Solution csaa = new SAAModel().solve(data, config, null);
        csaa.solveTimeSec = (System.nanoTime() - csaaStarted) / 1.0e9;
        boolean runMarginalVariance = Boolean.parseBoolean(
                System.getProperty("trb.moment.runMarginalVariance", "true"));
        ExactPartialMomentDROSolver.Result moment = runMarginalVariance
                ? new ExactPartialMomentDROSolver().solve(params, moments.mean,
                        moments.marginalVariance, moments.upper, config)
                : null;
        ExactMeanMadDROSolver.Result meanMad = new ExactMeanMadDROSolver().solve(
                params, new double[laneCount], moments.mean, moments.mad,
                moments.upper, config);
        if (moment != null && moment.solution.certifiedOptimal
                && moment.solution.objValue
                + 1e-5 * Math.max(1.0, Math.abs(csaa.objValue))
                < csaa.objValue) {
            throw new IllegalStateException(
                    "Moment robust objective is below CSAA although the NW empirical distribution is feasible.");
        }

        List<Row> rows = new ArrayList<>();
        rows.add(row("CSAA", csaa, params, prepared.oosSamples));
        if (moment != null) {
            rows.add(row("EXACT_MARGINAL_VARIANCE", moment.solution,
                    params, prepared.oosSamples));
        }
        rows.add(row("EXACT_MEAN_MAD", meanMad.solution(),
                params, prepared.oosSamples));
        writeSummary(root.resolve("summary.csv"), rows);
        if (moment != null) Files.writeString(root.resolve("certificate.txt"), String.format(Locale.US,
                "status=%s%ncertified=%s%nobjective=%.12f%n"
                        + "selected=%s%niterations=%d%ncuts=%d%n"
                        + "finalViolation=%.12g%nseparationStatus=%s%n"
                        + "separationGlobalGap=%.12g%n"
                        + "generatedSupportLowerBound=%.12f%n"
                        + "generatedSupportRelativeGap=%.12g%n"
                        + "supportPoints=%d%npositiveAtoms=%d%n"
                        + "probabilityResidual=%.12g%nmaximumMeanResidual=%.12g%n"
                        + "maximumMarginalVarianceViolation=%.12g%n"
                        + "aggregateVarianceViolation=%.12g%nworstDemand=%s%n",
                moment.solution.solverStatus, moment.solution.certifiedOptimal,
                moment.solution.objValue, Arrays.toString(moment.solution.y),
                moment.separationSolves, moment.cuts, moment.finalViolation,
                moment.separationStatus, moment.separationGlobalGap,
                moment.generatedSupportLowerBound,
                moment.generatedSupportRelativeGap,
                moment.generatedSupportPoints, moment.positiveProbabilityAtoms,
                moment.probabilityResidual, moment.maximumMeanResidual,
                moment.maximumMarginalVarianceViolation,
                moment.aggregateVarianceViolation,
                Arrays.toString(moment.worstDemand)), StandardCharsets.UTF_8);
        Files.writeString(root.resolve("mean_mad_certificate.txt"), String.format(Locale.US,
                "status=%s%ncertified=%s%nobjective=%.12f%nselected=%s%n"
                        + "atoms=%d%nprobabilitySum=%.12f%neffectiveMad=%s%n"
                        + "attainedMean=%s%nattainedMad=%s%n",
                meanMad.solution().solverStatus,
                meanMad.solution().certifiedOptimal,
                meanMad.solution().objValue,
                Arrays.toString(meanMad.solution().y),
                meanMad.distribution().probability.length,
                Arrays.stream(meanMad.distribution().probability).sum(),
                Arrays.toString(meanMad.distribution().effectiveMad),
                Arrays.toString(meanMad.distribution().attainedMean),
                Arrays.toString(meanMad.distribution().attainedMad)),
                StandardCharsets.UTF_8);
        Files.write(root.resolve("configuration.txt"), List.of(
                "purpose=one-query marginal-variance and Mean-MAD R7 pilot; not final manuscript evidence",
                "baseline=TRB-MQC-COVERAGE-BASELINE-20260813",
                "I=" + carrierCount + ",J=" + laneCount + ",S="
                        + data.samples.size() + ",OOS=" + OOS_DRAWS,
                "trainingSeed=" + TRAINING_SEED + ",querySeed=" + QUERY_SEED,
                "coverage=1.0,mqcScale=1.0,h=min,demandBalance=equality",
                "kernel=exponential,C_h=1.0,lag=3,standardizeTheta=true",
                "ambiguityA=exact NW-weighted mean plus upper marginal centered second moments; "
                        + "no cross-lane or aggregate moment; box support",
                "ambiguityB=exact mean plus componentwise MAD; box support; "
                        + "Long-Qi-Zhang supermodular reduction",
                "varianceInflation=1.0 (uncalibrated pilot)",
                "supportUpper=1.5*trainingMaximum (at least the weighted mean)",
                "recourse=unrestricted LP; no affine decision rule",
                "threads=" + threads + ",separationTimeLimitSec=" + timeLimitSeconds,
                "maxIterations=" + maxIterations,
                "runMarginalVariance=" + runMarginalVariance,
                "relativeTolerance=" + tolerance,
                "mean=" + Arrays.toString(moments.mean),
                "marginalVarianceUpper=" + Arrays.toString(moments.marginalVariance),
                "madUpper=" + Arrays.toString(moments.mad),
                "supportUpper=" + Arrays.toString(moments.upper)),
                StandardCharsets.UTF_8);
    }

    private static MomentInput weightedMoments(List<Sample> samples, double[] baseline) {
        double sumWeight = 0.0;
        for (Sample sample : samples) {
            if (!Double.isFinite(sample.weight) || sample.weight < 0.0) {
                throw new IllegalArgumentException("Invalid sample weight " + sample.weight);
            }
            sumWeight += sample.weight;
        }
        if (!(sumWeight > 0.0)) {
            throw new IllegalArgumentException("Sample weights must have positive sum.");
        }
        double[] mean = new double[baseline.length];
        for (Sample sample : samples) {
            double weight = sample.weight / sumWeight;
            for (int j = 0; j < mean.length; j++) {
                mean[j] += weight * sample.demand()[j];
            }
        }
        double[] variance = new double[baseline.length];
        double[] mad = new double[baseline.length];
        double[] maximum = new double[baseline.length];
        for (Sample sample : samples) {
            double weight = sample.weight / sumWeight;
            for (int j = 0; j < mean.length; j++) {
                double deviation = sample.demand()[j] - mean[j];
                variance[j] += weight * deviation * deviation;
                mad[j] += weight * Math.abs(deviation);
                maximum[j] = Math.max(maximum[j], sample.demand()[j]);
            }
        }
        for (int j = 0; j < variance.length; j++) {
            variance[j] = Math.max(variance[j], 1e-10 * Math.max(1.0, mean[j] * mean[j]));
        }
        double[] upper = new double[baseline.length];
        for (int j = 0; j < upper.length; j++) {
            upper[j] = Math.max(mean[j], 1.5 * maximum[j]);
        }
        return new MomentInput(mean, variance, mad, upper);
    }

    private static Row row(String method,
                           Solution solution,
                           ProcurementParams params,
                           List<Sample> oos) throws Exception {
        double[] costs = new double[oos.size()];
        double transport = 0.0;
        double spot = 0.0;
        double penalty = 0.0;
        for (int draw = 0; draw < oos.size(); draw++) {
            SecondStageEvaluator.Result value = SecondStageEvaluator.evaluate(
                    params, solution.y, oos.get(draw).demand(), true);
            costs[draw] = value.objective;
            transport += value.transportCost;
            spot += value.spotCost;
            penalty += value.penaltyCost;
        }
        Arrays.sort(costs);
        double mean = Arrays.stream(costs).average().orElseThrow();
        double variance = 0.0;
        for (double cost : costs) variance += (cost - mean) * (cost - mean);
        double q95 = quantile(costs, 0.95);
        double cvar95 = Arrays.stream(costs).filter(cost -> cost >= q95)
                .average().orElseThrow();
        return new Row(method, solution.objValue, binary(solution.y), mean,
                Math.sqrt(variance / costs.length), q95, cvar95,
                transport / costs.length, spot / costs.length,
                penalty / costs.length, solution.solveTimeSec,
                solution.solverStatus, solution.certifiedOptimal,
                solution.relativeGap);
    }

    private static void writeSummary(Path path, List<Row> rows) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("method,modelObjective,yBinary,mean,sd,q95,cvar95,transport,spot,penalty,"
                    + "solveTimeSec,status,certified,gap");
            out.newLine();
            for (Row row : rows) {
                out.write(String.format(Locale.US,
                        "%s,%.12f,%s,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,"
                                + "%.6f,%s,%s,%.12g%n",
                        row.method, row.objective, row.yBinary, row.mean, row.sd,
                        row.q95, row.cvar95, row.transport, row.spot, row.penalty,
                        row.seconds, row.status, row.certified, row.gap));
            }
        }
    }

    private static void requireEmpty(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output directory must be empty: " + root);
            }
        }
    }

    private static void log1pContexts(ReplicationData demand) {
        for (Sample sample : demand.trainingSamples) log1p(sample.theta.values());
        log1p(demand.thetaNow.values());
        for (Sample sample : demand.oosSamples) log1p(sample.theta.values());
    }

    private static void log1p(double[] values) {
        for (int index = 0; index < values.length; index++) {
            values[index] = Math.log1p(values[index]);
        }
    }

    private static String binary(double[] y) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < y.length; i++) {
            if (i > 0) out.append(';');
            out.append(y[i] > 0.5 ? 1 : 0);
        }
        return out.append(']').toString();
    }

    private static double quantile(double[] sorted, double probability) {
        double position = probability * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
    }

    private record MomentInput(double[] mean,
                               double[] marginalVariance,
                               double[] mad,
                               double[] upper) {
    }

    private record Row(String method,
                       double objective,
                       String yBinary,
                       double mean,
                       double sd,
                       double q95,
                       double cvar95,
                       double transport,
                       double spot,
                       double penalty,
                       double seconds,
                       String status,
                       boolean certified,
                       double gap) {
    }
}
