package Test.analysis.synthetic;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Model.ContextualWassersteinBoxCcgSolver;
import Model.SAAModel;
import Model.SecondStageEvaluator;
import Model.Solution;
import Model.WassersteinBoxInput;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticMainSolve.Result;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.Method;
import Test.analysis.synthetic.TRBReviewerR3M3SyntheticSolveBridge.PreparedInput;
import Test.analysis.synthetic.TRBReviewerSyntheticProcurementFactory.GeneratedProcurement;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Small common-instance comparison of CSAA, RCSAA, modified-chi-square DRO,
 * and exact bounded-box W1-DRO.  It also exports the exact contextual scenario
 * probabilities consumed by the Java solvers for the companion RSOME
 * mean-MAD/PCM pilot.
 *
 * <p>Market 1 is an explicitly labelled pilot-calibration market.  Its held-out
 * conditional draws select one value from each small grid.  Market 2 is then
 * solved once with the frozen choices.  This is a mechanism/capability pilot,
 * not a paper-ready hyperparameter-selection protocol.</p>
 */
public final class TRBReviewerAlternativeAmbiguityPilot {
    private static final int I = 6;
    private static final int J = 5;
    private static final int S = 30;
    private static final int OOS = 300;
    private static final int THREADS = 1;
    private static final int TIME_LIMIT_SECONDS = 300;
    private static final double[] C_H_GRID = {0.5, 1.0, 2.0};
    private static final double[] LAMBDA_GRID = {0.1, 1.0, 10.0};
    private static final double[] W1_RADIUS_GRID = {0.0, 0.10, 0.25};

    private TRBReviewerAlternativeAmbiguityPilot() {
    }

    /** Usage: {@code <empty-output-directory>}. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: <empty-output-directory>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        requireEmpty(root);
        Files.createDirectories(root);

        Path market1 = buildMarket(root, 1);
        double selectedCH = calibrateBandwidth(market1);
        exportPreparedInput(market1, selectedCH);
        double selectedRcsaaLambda = calibrateLambda(
                market1, Method.RCSAA_ENUMERATE, selectedCH);
        double selectedDroLambda = calibrateLambda(
                market1, Method.DRO, selectedCH);
        double selectedW1Radius = calibrateWasserstein(market1, selectedCH);

        Path market2 = buildMarket(root, 2);
        exportPreparedInput(market2, selectedCH);
        runMain(market2, "evaluation/csaa", Method.CSAA, selectedCH, 1.0);
        runMain(market2, "evaluation/rcsaa", Method.RCSAA_ENUMERATE,
                selectedCH, selectedRcsaaLambda);
        runMain(market2, "evaluation/chi_square_dro", Method.DRO,
                selectedCH, selectedDroLambda);
        runWasserstein(market2, "evaluation/wasserstein", selectedCH,
                selectedW1Radius);
        // Keep a tiny frozen-parameter sensitivity on the confirmation market.
        // These rows diagnose whether robustness changes the decision; they do
        // not re-select any parameter on market 2.
        for (double lambda : LAMBDA_GRID) {
            runMain(market2, "evaluation_grid/rcsaa_lambda_" + tag(lambda),
                    Method.RCSAA_ENUMERATE, selectedCH, lambda);
            runMain(market2, "evaluation_grid/chi_square_dro_lambda_" + tag(lambda),
                    Method.DRO, selectedCH, lambda);
        }
        for (double multiplier : W1_RADIUS_GRID) {
            runWasserstein(market2,
                    "evaluation_grid/wasserstein_radius_" + tag(multiplier),
                    selectedCH, multiplier);
        }

        Properties choices = new Properties();
        choices.setProperty("selected_C_h", Double.toString(selectedCH));
        choices.setProperty("selected_RCSAA_lambda", Double.toString(selectedRcsaaLambda));
        choices.setProperty("selected_chi_square_DRO_lambda", Double.toString(selectedDroLambda));
        choices.setProperty("selected_W1_radius_multiplier", Double.toString(selectedW1Radius));
        choices.setProperty("selection_rule", "minimum mean OOS cost on market_1 pilot calibration draws");
        choices.setProperty("market_2_role", "untouched confirmation market");
        try (BufferedWriter out = Files.newBufferedWriter(
                root.resolve("java_selected_parameters.properties"), StandardCharsets.UTF_8)) {
            choices.store(out, "Small alternative-ambiguity pilot choices");
        }
        writeProtocol(root.resolve("protocol.txt"), selectedCH, selectedRcsaaLambda,
                selectedDroLambda, selectedW1Radius);
        System.out.println("ALTERNATIVE_AMBIGUITY_JAVA_PILOT_COMPLETE " + root);
    }

    private static Path buildMarket(Path root, int market) throws Exception {
        Path marketRoot = root.resolve("market_" + market);
        Path instance = marketRoot.resolve("instance");
        Files.createDirectories(marketRoot);

        Settings settings = new Settings();
        settings.laneCount = J;
        settings.trainingSampleCount = S;
        settings.observedLagPeriods = 3;
        settings.warmupPeriods = 50;
        settings.oosSampleCount = OOS;
        settings.meanDemandPerLane = 100.0;
        settings.innovationCv = 0.30;
        settings.baselineSeed = 2026082800L + market;
        settings.replicationSeed = 2800L + market;
        settings.validate();

        ReplicationData demand = TRBReviewerR3M3IndependentPathDemandGenerator.generate(settings);
        GeneratedProcurement generated =
                TRBReviewerSyntheticProcurementFactory.generateScaleComparable(
                        demand.baselineDemand, I, 2026082800L + market);
        ProcurementParams params = TRBReviewerR7CoverageMqcGridExperiment.withNestedCoverage(
                generated.params, demand.baselineDemand, 1.0, 1.0, "min", false);
        TRBReviewerR3M3SyntheticInstanceIO.save(instance, settings, demand, params);
        Files.writeString(marketRoot.resolve("market_role.txt"),
                market == 1
                        ? "pilot calibration market; its OOS draws are used only for this rough screen\n"
                        : "untouched confirmation market; no parameter is selected on its OOS draws\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        return marketRoot;
    }

    private static double calibrateBandwidth(Path marketRoot) throws Exception {
        double best = Double.NaN;
        double bestMean = Double.POSITIVE_INFINITY;
        for (double cH : C_H_GRID) {
            Result result = runMain(marketRoot, "calibration/c_h_" + tag(cH),
                    Method.CSAA, cH, 1.0);
            if (result.meanOosCost < bestMean) {
                bestMean = result.meanOosCost;
                best = cH;
            }
        }
        return best;
    }

    private static double calibrateLambda(Path marketRoot,
                                          Method method,
                                          double cH) throws Exception {
        double best = Double.NaN;
        double bestMean = Double.POSITIVE_INFINITY;
        for (double lambda : LAMBDA_GRID) {
            Result result = runMain(marketRoot,
                    "calibration/" + method.name().toLowerCase(Locale.ROOT)
                            + "_lambda_" + tag(lambda), method, cH, lambda);
            requireCertified(method.name(), lambda, result.certifiedOptimal,
                    result.solverStatus, result.relativeGap);
            if (result.meanOosCost < bestMean) {
                bestMean = result.meanOosCost;
                best = lambda;
            }
        }
        return best;
    }

    private static Result runMain(Path marketRoot,
                                  String relativeOutput,
                                  Method method,
                                  double cH,
                                  double lambda) throws Exception {
        return TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.runSaved(
                marketRoot.resolve("instance"), method,
                marketRoot.resolve(relativeOutput), cH, lambda,
                THREADS, TIME_LIMIT_SECONDS);
    }

    private static double calibrateWasserstein(Path marketRoot, double cH) throws Exception {
        double best = Double.NaN;
        double bestMean = Double.POSITIVE_INFINITY;
        for (double multiplier : W1_RADIUS_GRID) {
            W1Row row = runWasserstein(marketRoot,
                    "calibration/wasserstein_radius_" + tag(multiplier), cH, multiplier);
            if (row.mean < bestMean) {
                bestMean = row.mean;
                best = multiplier;
            }
        }
        return best;
    }

    private static W1Row runWasserstein(Path marketRoot,
                                        String relativeOutput,
                                        double cH,
                                        double radiusMultiplier) throws Exception {
        var stored = TRBReviewerR3M3SyntheticInstanceIO.load(marketRoot.resolve("instance"));
        Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                Method.CSAA, stored.settings.observedLagPeriods, cH, 1.0,
                THREADS, TIME_LIMIT_SECONDS);
        config.maxBendersIter = 500;
        config.tol = Math.min(config.tol, 1e-6);
        PreparedInput prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                stored.demandData, stored.procurementParams, config);
        Data data = prepared.solveData;
        double[] upper = boxUpper(data.samples, stored.procurementParams.J);
        double[] scale = trainingScale(data.samples, stored.demandData.baselineDemand);
        double dispersion = weightedDispersion(data.samples, scale);
        double radius = radiusMultiplier * dispersion;
        WassersteinBoxInput input = WassersteinBoxInput.fromData(data, upper, scale, radius);

        long start = System.nanoTime();
        Solution solution;
        double eta;
        if (radius == 0.0) {
            solution = new SAAModel().solve(data, config, null);
            eta = input.lipschitzBound();
        } else {
            ContextualWassersteinBoxCcgSolver.Result solved =
                    new ContextualWassersteinBoxCcgSolver().solve(input, config);
            solution = solved.solution();
            eta = solved.eta();
            requireCertified("W1", radiusMultiplier, solution.certifiedOptimal,
                    solution.solverStatus, solution.relativeGap);
        }
        double seconds = (System.nanoTime() - start) / 1.0e9;
        W1Row row = evaluateW1(solution, eta, radiusMultiplier, radius, dispersion,
                seconds, stored.procurementParams, prepared.oosSamples);
        Path output = marketRoot.resolve(relativeOutput);
        Files.createDirectories(output);
        writeW1(output.resolve("solve_summary.csv"), row);
        return row;
    }

    private static void exportPreparedInput(Path marketRoot, double cH) throws Exception {
        var stored = TRBReviewerR3M3SyntheticInstanceIO.load(marketRoot.resolve("instance"));
        Config config = TRBReviewerR3M3SyntheticSavedInstanceSolveRunner.config(
                Method.CSAA, stored.settings.observedLagPeriods, cH, 1.0,
                THREADS, TIME_LIMIT_SECONDS);
        PreparedInput prepared = TRBReviewerR3M3SyntheticSolveBridge.prepare(
                stored.demandData, stored.procurementParams, config);
        Path path = marketRoot.resolve("prepared_contextual_samples.csv");
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("sampleId,probability");
            for (int j = 0; j < stored.procurementParams.J; j++) out.write(",demand_" + j);
            out.newLine();
            for (Sample sample : prepared.solveData.samples) {
                out.write(Integer.toString(sample.id));
                out.write("," + Double.toString(sample.weight));
                for (double demand : sample.demand()) out.write("," + Double.toString(demand));
                out.newLine();
            }
        }
        double sum = prepared.solveData.samples.stream().mapToDouble(s -> s.weight).sum();
        if (Math.abs(sum - 1.0) > 1e-10) {
            throw new IllegalStateException("Exported contextual probabilities sum to " + sum);
        }
    }

    private static W1Row evaluateW1(Solution solution,
                                    double eta,
                                    double radiusMultiplier,
                                    double radius,
                                    double dispersion,
                                    double solveSeconds,
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
        double cvar95 = Arrays.stream(costs).filter(value -> value >= q95)
                .average().orElseThrow();
        int selected = 0;
        for (double value : solution.y) if (value > 0.5) selected++;
        return new W1Row(radiusMultiplier, dispersion, radius, eta,
                solution.objValue, selected, binary(solution.y), mean,
                Math.sqrt(variance / costs.length), q95, cvar95,
                transport / costs.length, spot / costs.length,
                penalty / costs.length, solveSeconds, solution.solverStatus,
                solution.certifiedOptimal, solution.relativeGap);
    }

    private static double[] boxUpper(List<Sample> samples, int laneCount) {
        double[] upper = new double[laneCount];
        for (Sample sample : samples) {
            for (int j = 0; j < laneCount; j++) {
                upper[j] = Math.max(upper[j], sample.demand()[j]);
            }
        }
        for (int j = 0; j < laneCount; j++) upper[j] *= 1.5;
        return upper;
    }

    private static double[] trainingScale(List<Sample> samples, double[] baseline) {
        double[] mean = new double[baseline.length];
        for (Sample sample : samples) {
            for (int j = 0; j < mean.length; j++) mean[j] += sample.demand()[j];
        }
        for (int j = 0; j < mean.length; j++) mean[j] /= samples.size();
        double[] scale = new double[mean.length];
        for (Sample sample : samples) {
            for (int j = 0; j < mean.length; j++) {
                double difference = sample.demand()[j] - mean[j];
                scale[j] += difference * difference;
            }
        }
        for (int j = 0; j < mean.length; j++) {
            scale[j] = Math.sqrt(scale[j] / samples.size());
            scale[j] = Math.max(scale[j], Math.max(0.05 * mean[j], 0.01 * baseline[j]));
        }
        return scale;
    }

    private static double weightedDispersion(List<Sample> samples, double[] scale) {
        double[] mean = new double[scale.length];
        for (Sample sample : samples) {
            for (int j = 0; j < mean.length; j++) mean[j] += sample.weight * sample.demand()[j];
        }
        double value = 0.0;
        for (Sample sample : samples) {
            double distance = 0.0;
            for (int j = 0; j < mean.length; j++) {
                distance += Math.abs(sample.demand()[j] - mean[j]) / scale[j];
            }
            value += sample.weight * distance;
        }
        return value;
    }

    private static void writeW1(Path path, W1Row row) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            out.write("method,radiusMultiplier,trainingDispersion,radius,eta,modelObjective,"
                    + "selectedCount,yBinary,meanOosCost,sdOosCost,q95OosCost,cvar95OosCost,"
                    + "meanTransportCost,meanSpotCost,meanPenaltyCost,optimizerTimeSec,status,certified,gap");
            out.newLine();
            out.write(String.format(Locale.US,
                    "W1,%.8f,%.12f,%.12f,%.12f,%.12f,%d,\"%s\","
                            + "%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.12f,%.6f,%s,%s,%.12g%n",
                    row.radiusMultiplier, row.dispersion, row.radius, row.eta,
                    row.modelObjective, row.selectedCount, row.yBinary,
                    row.mean, row.sd, row.q95, row.cvar95, row.transport,
                    row.spot, row.penalty, row.solveSeconds, row.status,
                    row.certified, row.gap));
        }
    }

    private static void writeProtocol(Path path,
                                      double cH,
                                      double rcsaaLambda,
                                      double droLambda,
                                      double w1Radius) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("Purpose=small common-instance capability and mechanism pilot; not paper-ready evidence");
        lines.add("Markets=2 (market_1 calibration, market_2 untouched confirmation)");
        lines.add("I=" + I + ",J=" + J + ",S=" + S + ",OOS=" + OOS);
        lines.add("DGP=independent-path conditional lognormal synthetic generator; CV=0.30; lag=3");
        lines.add("Procurement=100% coverage; original MQC quantity scale; h=min eligible r; demand equality");
        lines.add("C_h_grid=" + Arrays.toString(C_H_GRID) + "; selected=" + cH);
        lines.add("lambda_grid=" + Arrays.toString(LAMBDA_GRID)
                + "; selected_RCSAA=" + rcsaaLambda + "; selected_chi_square_DRO=" + droLambda);
        lines.add("W1=scaled L1; box upper=1.5*lane training maximum; radius_multiplier_grid="
                + Arrays.toString(W1_RADIUS_GRID) + "; selected=" + w1Radius);
        lines.add("Selection criterion=minimum mean OOS cost on market_1 only");
        lines.add("Market_2 grid=diagnostic sensitivity only; it does not retune the selected parameters");
        lines.add("Moment companion=RSOME lifted-LDR Mean-MAD and PCM; "
                + "conditional NW and unconditional equal-weight empirical moments; "
                + "fixed inflation=1.0; no moment tuning");
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void requireCertified(String method,
                                         double parameter,
                                         boolean certified,
                                         String status,
                                         double gap) {
        if (!certified) {
            throw new IllegalStateException(method + " parameter=" + parameter
                    + " is not certified: status=" + status + ", gap=" + gap);
        }
    }

    private static double quantile(double[] sorted, double probability) {
        double position = probability * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) return sorted[lower];
        double fraction = position - lower;
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction;
    }

    private static String binary(double[] y) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < y.length; i++) {
            if (i > 0) out.append(',');
            out.append(y[i] > 0.5 ? '1' : '0');
        }
        return out.append(']').toString();
    }

    private static String tag(double value) {
        return Double.toString(value).replace('-', 'm').replace('.', 'p');
    }

    private static void requireEmpty(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException("Output directory must be empty: " + root);
            }
        }
    }

    private record W1Row(double radiusMultiplier,
                         double dispersion,
                         double radius,
                         double eta,
                         double modelObjective,
                         int selectedCount,
                         String yBinary,
                         double mean,
                         double sd,
                         double q95,
                         double cvar95,
                         double transport,
                         double spot,
                         double penalty,
                         double solveSeconds,
                         String status,
                         boolean certified,
                         double gap) {
    }
}
