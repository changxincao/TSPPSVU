package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Model.DROModel;
import Model.SAAModel;
import Model.SecondStageEvaluator;
import Model.Solution;
import Model.SolveMode;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSolveBridge.PreparedInput;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One-replication solve interface for Reviewer 3, Comment 3 and the synthetic
 * part of R4-M35.
 *
 * <p><strong>Input.</strong> A frozen synthetic replication, the procurement
 * parameters saved with it, and an already selected model configuration. This
 * class does not tune {@code k}, {@code C_h} or {@code lambda} on OOS data.</p>
 *
 * <p><strong>Operation.</strong> It solves the first-stage model exactly once
 * for the query context and then fixes that decision while evaluating every
 * draw from the same conditional OOS set. This is intentionally different
 * from a rolling runner: the 1,000 OOS rows are parallel next-period outcomes,
 * not 1,000 periods on which the model is re-solved.</p>
 *
 * <p><strong>Output.</strong> {@code oos_costs.csv} contains paired draw-level
 * cost components; {@code solve_summary.csv} contains solve time, selected
 * carriers and mean/tail OOS statistics. Running different methods on the same
 * saved instance therefore preserves exact OOS pairing for R4-M9--M11/M32.</p>
 */
public final class TRBReviewerR3M3SyntheticMainSolve {

    private TRBReviewerR3M3SyntheticMainSolve() {
    }

    /**
     * Executes one method on one frozen instance.
     *
     * <p>{@code MeanDeterministic}, {@code SAA}, and {@code CSAA} use the
     * current {@link SAAModel}. {@code RCSAA} uses the current
     * {@link DROModel}; its exact implementation is selected by
     * {@code config.rcsaaSolverVariant}. Consequently an extensive DRO run and
     * an exact RCSAA run must have different labels and configurations, even
     * though both enter through {@code DROModel} in the existing project.</p>
     */
    public static Result run(String methodLabel,
                             ReplicationData replication,
                             ProcurementParams params,
                             Config config,
                             Path outputDirectory) throws Exception {
        if (methodLabel == null || methodLabel.isBlank()) {
            throw new IllegalArgumentException("methodLabel is required.");
        }
        requireEmptyOutputDirectory(outputDirectory);

        long totalStart = System.nanoTime();
        long preparationStart = totalStart;
        PreparedInput prepared = config.solveMode == SolveMode.MeanDeterministic
                ? TRBReviewerR3M3SyntheticSolveBridge.prepareMeanDeterministic(
                        replication, params, config)
                : TRBReviewerR3M3SyntheticSolveBridge.prepare(replication, params, config);
        double preparationSeconds = (System.nanoTime() - preparationStart) / 1.0e9;

        long optimizerStart = System.nanoTime();
        Solution solution;
        if (config.solveMode == SolveMode.MeanDeterministic
                || config.solveMode == SolveMode.SAA
                || config.solveMode == SolveMode.CSAA) {
            solution = new SAAModel().solve(prepared.solveData, config, null);
        } else if (config.solveMode == SolveMode.RCSAA) {
            solution = new DROModel().solve(prepared.solveData, config);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported formal synthetic solve mode: " + config.solveMode);
        }
        double optimizerSeconds = (System.nanoTime() - optimizerStart) / 1.0e9;

        long oosStart = System.nanoTime();
        List<CostRow> rows = evaluateOos(params, solution.y, prepared.oosSamples,
                config.enforceDemandEquality);
        double oosEvaluationSeconds = (System.nanoTime() - oosStart) / 1.0e9;
        double totalEvaluationSeconds = (System.nanoTime() - totalStart) / 1.0e9;
        writeOosRows(outputDirectory.resolve("oos_costs.csv"), methodLabel, rows);

        Result result = summarize(methodLabel, config, solution,
                preparationSeconds, optimizerSeconds, oosEvaluationSeconds,
                totalEvaluationSeconds, rows);
        writeSummary(outputDirectory.resolve("solve_summary.csv"), result);
        return result;
    }

    private static void requireEmptyOutputDirectory(Path outputDirectory) throws Exception {
        if (Files.exists(outputDirectory)) {
            try (var entries = Files.list(outputDirectory)) {
                if (entries.findAny().isPresent()) {
                    throw new IllegalArgumentException(
                            "Synthetic result directory must be empty: " + outputDirectory);
                }
            }
        } else {
            Files.createDirectories(outputDirectory);
        }
    }

    private static List<CostRow> evaluateOos(ProcurementParams params,
                                             double[] y,
                                             List<Sample> oosSamples,
                                             boolean enforceDemandEquality) throws Exception {
        List<CostRow> rows = new ArrayList<>(oosSamples.size());
        for (int draw = 0; draw < oosSamples.size(); draw++) {
            SecondStageEvaluator.Result evaluated = SecondStageEvaluator.evaluate(
                    params, y, oosSamples.get(draw).demand(), enforceDemandEquality);
            rows.add(new CostRow(
                    draw,
                    evaluated.objective,
                    evaluated.transportCost,
                    evaluated.spotCost,
                    evaluated.penaltyCost,
                    evaluated.mqcShortfallQuantity));
        }
        return rows;
    }

    private static Result summarize(String methodLabel,
                                    Config config,
                                    Solution solution,
                                    double preparationSeconds,
                                    double optimizerSeconds,
                                    double oosEvaluationSeconds,
                                    double totalEvaluationSeconds,
                                    List<CostRow> rows) {
        double[] costs = new double[rows.size()];
        double transport = 0.0;
        double spot = 0.0;
        double penalty = 0.0;
        for (int i = 0; i < rows.size(); i++) {
            CostRow row = rows.get(i);
            costs[i] = row.total;
            transport += row.transport;
            spot += row.spot;
            penalty += row.penalty;
        }
        Arrays.sort(costs);

        double mean = mean(costs);
        double standardDeviation = populationStandardDeviation(costs, mean);
        double q50 = quantile(costs, 0.50);
        double q90 = quantile(costs, 0.90);
        double q95 = quantile(costs, 0.95);
        double cvar95 = tailMean(costs, q95);
        int selected = 0;
        for (double value : solution.y) if (value > 0.5) selected++;

        return new Result(
                methodLabel,
                config.solveMode,
                config.solveMode == SolveMode.RCSAA && config.rcsaaSolverVariant != null
                        ? config.rcsaaSolverVariant.name() : "",
                config.solveMode == SolveMode.CSAA || config.solveMode == SolveMode.RCSAA
                        ? config.C_h : Double.NaN,
                config.solveMode == SolveMode.RCSAA ? config.lambda : Double.NaN,
                config.knnNeighbors,
                solution.objValue,
                preparationSeconds,
                optimizerSeconds,
                oosEvaluationSeconds,
                totalEvaluationSeconds,
                solution.solverStatus,
                solution.certifiedOptimal,
                solution.bestBound,
                solution.relativeGap,
                solution.nodeCount,
                solution.iterationCount,
                solution.cutCount,
                solution.candidateCount,
                selected,
                yBinary(solution.y),
                rows.size(),
                mean,
                standardDeviation,
                q50,
                q90,
                q95,
                cvar95,
                transport / rows.size(),
                spot / rows.size(),
                penalty / rows.size());
    }

    private static void writeOosRows(Path path,
                                     String methodLabel,
                                     List<CostRow> rows) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("method,drawId,totalCost,transportCost,spotCost,penaltyCost,mqcShortfallQuantity");
            writer.newLine();
            for (CostRow row : rows) {
                writer.write(String.format(Locale.US, "%s,%d,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                        csv(methodLabel),
                        row.draw,
                        row.total,
                        row.transport,
                        row.spot,
                        row.penalty,
                        row.mqcShortfallQuantity));
            }
        }
    }

    private static void writeSummary(Path path, Result result) throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("method,solveMode,solverVariant,C_h,lambda,knnNeighbors,modelObjective,"
                    + "preparationTimeSec,optimizerTimeSec,oosEvaluationTimeSec,totalEvaluationTimeSec,"
                    + "solverStatus,certifiedOptimal,bestBound,relativeGap,nodeCount,iterationCount,cutCount,candidateCount,"
                    + "selectedCount,yBinary,oosDraws,meanOosCost,sdOosCost,q50OosCost,q90OosCost,"
                    + "q95OosCost,cvar95OosCost,meanTransportCost,meanSpotCost,meanPenaltyCost");
            writer.newLine();
            writer.write(String.format(Locale.US,
                    "%s,%s,%s,%.17g,%.17g,%d,%.17g,%.9f,%.9f,%.9f,%.9f,"
                            + "%s,%s,%.17g,%.17g,%d,%d,%d,%d,%d,%s,%d,"
                            + "%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g%n",
                    csv(result.methodLabel),
                    result.solveMode,
                    csv(result.solverVariant),
                    result.cH,
                    result.lambda,
                    result.knnNeighbors,
                    result.modelObjective,
                    result.preparationTimeSeconds,
                    result.optimizerTimeSeconds,
                    result.oosEvaluationTimeSeconds,
                    result.totalEvaluationTimeSeconds,
                    csv(result.solverStatus),
                    result.certifiedOptimal,
                    result.bestBound,
                    result.relativeGap,
                    result.nodeCount,
                    result.iterationCount,
                    result.cutCount,
                    result.candidateCount,
                    result.selectedCount,
                    csv(result.yBinary),
                    result.oosDraws,
                    result.meanOosCost,
                    result.sdOosCost,
                    result.q50OosCost,
                    result.q90OosCost,
                    result.q95OosCost,
                    result.cvar95OosCost,
                    result.meanTransportCost,
                    result.meanSpotCost,
                    result.meanPenaltyCost));
        }
    }

    private static double mean(double[] values) {
        double sum = 0.0;
        for (double value : values) sum += value;
        return sum / values.length;
    }

    private static double populationStandardDeviation(double[] values, double mean) {
        double sumSquares = 0.0;
        for (double value : values) {
            double difference = value - mean;
            sumSquares += difference * difference;
        }
        return Math.sqrt(sumSquares / Math.max(1, values.length));
    }

    private static double quantile(double[] sortedValues, double probability) {
        if (sortedValues.length == 1) return sortedValues[0];
        double position = probability * (sortedValues.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sortedValues[lower];
        double fraction = position - lower;
        return sortedValues[lower] * (1.0 - fraction) + sortedValues[upper] * fraction;
    }

    private static double tailMean(double[] sortedValues, double threshold) {
        double sum = 0.0;
        int count = 0;
        for (double value : sortedValues) {
            if (value >= threshold) {
                sum += value;
                count++;
            }
        }
        return sum / count;
    }

    private static String yBinary(double[] y) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < y.length; i++) {
            if (i > 0) builder.append(',');
            builder.append(y[i] > 0.5 ? '1' : '0');
        }
        return builder.append(']').toString();
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static final class CostRow {
        final int draw;
        final double total;
        final double transport;
        final double spot;
        final double penalty;
        final double mqcShortfallQuantity;

        CostRow(int draw, double total, double transport, double spot, double penalty,
                double mqcShortfallQuantity) {
            this.draw = draw;
            this.total = total;
            this.transport = transport;
            this.spot = spot;
            this.penalty = penalty;
            this.mqcShortfallQuantity = mqcShortfallQuantity;
        }
    }

    /** Immutable summary returned to higher-level multi-seed experiment code. */
    public static final class Result {
        public final String methodLabel;
        public final SolveMode solveMode;
        public final String solverVariant;
        public final double cH;
        public final double lambda;
        public final int knnNeighbors;
        public final double modelObjective;
        public final double preparationTimeSeconds;
        public final double optimizerTimeSeconds;
        public final double oosEvaluationTimeSeconds;
        public final double totalEvaluationTimeSeconds;
        public final String solverStatus;
        public final boolean certifiedOptimal;
        public final double bestBound;
        public final double relativeGap;
        public final long nodeCount;
        public final int iterationCount;
        public final int cutCount;
        public final long candidateCount;
        public final int selectedCount;
        public final String yBinary;
        public final int oosDraws;
        public final double meanOosCost;
        public final double sdOosCost;
        public final double q50OosCost;
        public final double q90OosCost;
        public final double q95OosCost;
        public final double cvar95OosCost;
        public final double meanTransportCost;
        public final double meanSpotCost;
        public final double meanPenaltyCost;

        private Result(String methodLabel,
                       SolveMode solveMode,
                       String solverVariant,
                       double cH,
                       double lambda,
                       int knnNeighbors,
                       double modelObjective,
                       double preparationTimeSeconds,
                       double optimizerTimeSeconds,
                       double oosEvaluationTimeSeconds,
                       double totalEvaluationTimeSeconds,
                       String solverStatus,
                       boolean certifiedOptimal,
                       double bestBound,
                       double relativeGap,
                       long nodeCount,
                       int iterationCount,
                       int cutCount,
                       long candidateCount,
                       int selectedCount,
                       String yBinary,
                       int oosDraws,
                       double meanOosCost,
                       double sdOosCost,
                       double q50OosCost,
                       double q90OosCost,
                       double q95OosCost,
                       double cvar95OosCost,
                       double meanTransportCost,
                       double meanSpotCost,
                       double meanPenaltyCost) {
            this.methodLabel = methodLabel;
            this.solveMode = solveMode;
            this.solverVariant = solverVariant;
            this.cH = cH;
            this.lambda = lambda;
            this.knnNeighbors = knnNeighbors;
            this.modelObjective = modelObjective;
            this.preparationTimeSeconds = preparationTimeSeconds;
            this.optimizerTimeSeconds = optimizerTimeSeconds;
            this.oosEvaluationTimeSeconds = oosEvaluationTimeSeconds;
            this.totalEvaluationTimeSeconds = totalEvaluationTimeSeconds;
            this.solverStatus = solverStatus;
            this.certifiedOptimal = certifiedOptimal;
            this.bestBound = bestBound;
            this.relativeGap = relativeGap;
            this.nodeCount = nodeCount;
            this.iterationCount = iterationCount;
            this.cutCount = cutCount;
            this.candidateCount = candidateCount;
            this.selectedCount = selectedCount;
            this.yBinary = yBinary;
            this.oosDraws = oosDraws;
            this.meanOosCost = meanOosCost;
            this.sdOosCost = sdOosCost;
            this.q50OosCost = q50OosCost;
            this.q90OosCost = q90OosCost;
            this.q95OosCost = q95OosCost;
            this.cvar95OosCost = cvar95OosCost;
            this.meanTransportCost = meanTransportCost;
            this.meanSpotCost = meanSpotCost;
            this.meanPenaltyCost = meanPenaltyCost;
        }
    }
}
