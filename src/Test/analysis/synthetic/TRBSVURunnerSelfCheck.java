package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Sample;
import Helper.calculateHelper.StandardScaler;
import Model.RCSAASolverVariant;
import Model.Solution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;
import Test.analysis.synthetic.TRBSVUSolveMethods.Method;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Deliberately small checks; never launches the 15x50 formal grid. */
public final class TRBSVURunnerSelfCheck {
    private TRBSVURunnerSelfCheck() { }

    public static void main(String[] args) throws Exception {
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCase.generate(7, 4, 100, 5,
                Distribution.NORMAL, Volatility.LOW,
                new TRBSVUSyntheticCase.Seeds(11, 13, 17, 19, 23));
        List<Sample> history = instance.history;
        StandardScaler zScaler = new StandardScaler(StandardScaler.Mode.Z_SCORE);
        zScaler.fit(history, history.get(0).theta.dim());
        double[] zMean = new double[history.get(0).theta.dim()];
        for (Sample sample : history) {
            double[] transformed = zScaler.transform(sample.theta.values());
            for (int k = 0; k < transformed.length; k++) zMean[k] += transformed[k] / history.size();
        }
        for (double value : zMean)
            require(Math.abs(value) < 1e-10, "Z-score context scaling no longer centers training data.");
        StandardScaler maxScaler = new StandardScaler(StandardScaler.Mode.TRAINING_MAX);
        maxScaler.fit(history, history.get(0).theta.dim());
        for (Sample sample : history) {
            for (double value : maxScaler.transform(sample.theta.values()))
                require(Math.abs(value) <= 1.0 + 1e-12,
                        "Training-max context scaling exceeded unit range.");
        }
        Path snapshotDirectory = Files.createTempDirectory(Path.of("tmp"), "trb_svu_case_check_");
        Path snapshot = snapshotDirectory.resolve("instance.tsv");
        try {
            TRBSVUSyntheticCaseIO.saveText(instance, snapshot);
            TRBSVUSyntheticCase restored = TRBSVUSyntheticCaseIO.loadText(snapshot);
            require(Arrays.equals(instance.testContext.values(), restored.testContext.values())
                    && Arrays.equals(instance.params.p, restored.params.p)
                    && Arrays.equals(instance.history.get(99).demand(), restored.history.get(99).demand())
                    && Arrays.equals(instance.oos.get(4).demand(), restored.oos.get(4).demand()),
                    "Saved case differs from loaded case.");
        } finally {
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(snapshotDirectory);
        }
        double original = history.get(0).weight;
        require(Arrays.equals(TRBSVUExperiment1Runner.RETENTION,
                        new double[]{0.4, 0.6, 0.8, 1.0}),
                "Experiment 1 retention grid drifted.");
        List<Sample> equal = TRBSVUScenarioWeights.equal(history);
        List<Sample> recent = TRBSVUScenarioWeights.recent(history, 0.3);
        List<Sample> mean = TRBSVUScenarioWeights.arithmeticMean(history);
        require(equal.size() == 100 && recent.size() == 30 && mean.size() == 1,
                "Nominal scenario construction failed.");
        require(history.get(0).weight == original, "Shared history was mutated.");
        TRBSVUPcmSolver.Moments pcmOne = TRBSVUPcmSolver.moments(equal, instance.params.J, 1.0);
        TRBSVUPcmSolver.Moments pcmTwo = TRBSVUPcmSolver.moments(equal, instance.params.J, 2.0);
        require(Arrays.equals(pcmOne.mean(), pcmTwo.mean())
                        && Arrays.equals(pcmOne.upper(), pcmTwo.upper()),
                "PCM kappa changed the empirical mean or support.");
        for (int j = 0; j < instance.params.J; j++)
            require(Math.abs(pcmTwo.variance()[j] - 4.0 * pcmOne.variance()[j]) < 1e-8,
                    "PCM marginal variance did not scale by kappa squared.");
        require(Math.abs(pcmTwo.totalVariance() - 4.0 * pcmOne.totalVariance()) < 1e-8,
                "PCM total-demand variance did not scale by kappa squared.");
        for (TRBSVUScenarioWeights.Kernel kernel : TRBSVUScenarioWeights.Kernel.values()) {
            List<Sample> weighted = TRBSVUScenarioWeights.kernel(history,
                    instance.testContext, kernel, 10);
            require(!weighted.isEmpty(), "Kernel has no support: " + kernel);
            require(Math.abs(weighted.stream().mapToDouble(s -> s.weight).sum() - 1) < 1e-10,
                    "Kernel weights do not sum to one.");
            verifyKernelFormula(history, instance.testContext, kernel, 10, weighted);
        }
        TRBSVUForestWeights forest = new TRBSVUForestWeights("python",
                Path.of("analysis", "trb_svu", "rf_leaf_weights.py"), 8);
        List<Sample> rf = forest.weights(history, instance.testContext, 41);
        require(rf.size() == 100 && Math.abs(rf.stream().mapToDouble(s -> s.weight).sum() - 1) < 1e-10,
                "RF weight construction failed.");
        Settings settings = new Settings(1, 60, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_SEARCH, true, false);
        Solution d = TRBSVUSolveMethods.solve(instance.params, instance.lanes, mean,
                instance.testContext, Method.NOMINAL, 0.0, settings);
        require(d.y != null && d.y.length == instance.params.I, "Nominal solution missing.");
        double realized = TRBSVUSolveMethods.realizedCost(instance.params, d.y,
                instance.oos.get(0).demand());
        require(Double.isFinite(realized), "Equality OOS evaluation failed.");
        if (args.length > 0 && "robust".equals(args[0])) {
            List<Sample> three = TRBSVUScenarioWeights.equal(history.subList(0, 3));
            Solution empirical = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                    three, instance.testContext, Method.NOMINAL, 0.0, settings);
            Solution zeroRadius = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                    three, instance.testContext, Method.WASSERSTEIN, 0.0, settings);
            require(zeroRadius.certifiedOptimal
                            && Math.abs(zeroRadius.objValue - empirical.objValue)
                            <= 1e-6 * Math.max(1.0, Math.abs(empirical.objValue)),
                    "Zero-radius W1 does not reproduce its empirical reference model.");
            for (Method method : List.of(Method.CHI_SQUARED, Method.RCSAA, Method.WASSERSTEIN)) {
                Solution robust = TRBSVUSolveMethods.solve(instance.params, instance.lanes,
                        three, instance.testContext, method, 0.1, settings);
                require(robust.y != null && robust.y.length == instance.params.I
                        && Double.isFinite(robust.objValue), "Robust method failed: " + method);
                System.out.println(method + " status=" + robust.solverStatus
                        + " gap=" + robust.relativeGap + " objective=" + robust.objValue);
            }
        }
        if (args.length > 0 && ("pcm".equals(args[0]) || "pcm-grid".equals(args[0]))) {
            TRBSVUPcmSolver solver = new TRBSVUPcmSolver(
                    Path.of(".venv-rsome", "Scripts", "python.exe"),
                    Path.of("analysis", "trb_svu", "solve_pcm.py"));
            double previous = Double.NEGATIVE_INFINITY;
            double[] grid = "pcm-grid".equals(args[0])
                    ? new double[]{1, 1.25, 1.5, 2} : new double[]{1.25};
            for (double kappa : grid) {
                Solution pcm = solver.solve(instance.params, equal.subList(0, 5), kappa, settings);
                require(pcm.y != null && pcm.y.length == instance.params.I
                                && pcm.certifiedOptimal && Double.isFinite(pcm.objValue),
                        "Lifted-affine PCM solve failed.");
                require(pcm.objValue + 1e-6 >= previous,
                        "PCM objective decreased when the ambiguity set expanded.");
                previous = pcm.objValue;
                System.out.println("PCM kappa=" + kappa + " status=" + pcm.solverStatus
                        + " objective=" + pcm.objValue);
            }
        }
        if (args.length > 0 && ("experiment1".equals(args[0])
                || "experiment2".equals(args[0]))) {
            Path checkpointDirectory = Files.createTempDirectory(Path.of("tmp"),
                    "trb_svu_checkpoint_check_");
            Path finalCheckpointDirectory = Files.createTempDirectory(Path.of("tmp"),
                    "trb_svu_final_checkpoint_check_");
            TRBSVUExperiment1Runner experiment1 = new TRBSVUExperiment1Runner(
                    settings, forest, 1, new TRBSVUValidationCheckpoint(
                            checkpointDirectory, "self-check-instance", "self-check-experiment1"),
                    new TRBSVUFinalCheckpoint(finalCheckpointDirectory.resolve("solve"),
                            finalCheckpointDirectory.resolve("oos"), "self-check-instance",
                            "self-check-experiment1", 0, "1"));
            TRBSVUExperiment1Runner.Result result = experiment1.run(instance);
            require(result.decisions().size() == 8 && result.oos().size() == 8
                    && result.selectedContextual() != null,
                    "Experiment 1 runner did not return all eight methods.");
            try (var checkpoints = Files.list(checkpointDirectory)) {
                require(checkpoints.filter(Files::isRegularFile).count()
                                == result.validationDetails().size(),
                        "One-origin validation results were not checkpointed one-for-one.");
            }
            try (var checkpoints = Files.list(finalCheckpointDirectory.resolve("solve"))) {
                require(checkpoints.filter(Files::isRegularFile).count() == result.decisions().size(),
                        "Final solves were not checkpointed one-for-one.");
            }
            try (var checkpoints = Files.list(finalCheckpointDirectory.resolve("oos"))) {
                require(checkpoints.filter(Files::isRegularFile).count() == 2L * result.oos().size(),
                        "OOS results were not checkpointed one method at a time.");
            }
            TRBSVUExperiment1Runner.Result resumed = experiment1.run(instance);
            require(sameValidationTraces(result.validationDetails(), resumed.validationDetails()),
                    "Experiment 1 checkpoint recovery changed validation traces.");
            Path output = Files.createTempDirectory(Path.of("tmp"), "trb_svu_output_check_");
            try {
                TRBSVUResultWriter.writeInstance(output.resolve("instance"), instance);
                TRBSVUResultWriter.writeValidationSummary(output.resolve("validation_summary.csv"),
                        0, "1", result.validationCurve(), Map.of(
                                "Tuned-SAA", result.retention(),
                                "CSAA-Exp", result.bandwidth().get(TRBSVUScenarioWeights.Kernel.EXPONENTIAL),
                                "CSAA-Gau", result.bandwidth().get(TRBSVUScenarioWeights.Kernel.GAUSSIAN),
                                "CSAA-Epa", result.bandwidth().get(TRBSVUScenarioWeights.Kernel.EPANECHNIKOV),
                                "CSAA-Tri", result.bandwidth().get(TRBSVUScenarioWeights.Kernel.TRIANGULAR)),
                        result.validationDetails());
                TRBSVUResultWriter.writeContextualChoice(output.resolve("selected_context.csv"),
                        0, result.selectedContextual());
                TRBSVUResultWriter.writeValidationDetails(output.resolve("validation_details.csv"),
                        0, "1", instance.params, result.validationDetails());
                TRBSVUResultWriter.writeFinalSolves(output.resolve("final_solves.csv"),
                        0, "1", instance.params, result.decisions(), result.validationCost(),
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), result.finalWeights());
                TRBSVUResultWriter.writeFinalWeights(output.resolve("final_weights.csv"),
                        0, "1", result.finalWeights());
                TRBSVUResultWriter.writeOosSummary(output.resolve("oos_summary.csv"),
                        0, "1", result.oos(), result.decisions());
                TRBSVUResultWriter.writeOosDetails(output.resolve("oos_draws.csv"),
                        0, "1", result.oosDetails(), result.decisions());
                require(Files.size(output.resolve("validation_details.csv")) > 0
                                && Files.size(output.resolve("selected_context.csv")) > 0
                                && Files.size(output.resolve("final_solves.csv")) > 0
                                && Files.size(output.resolve("oos_draws.csv")) > 0,
                        "Layered result files were not written.");
                require(Files.readAllLines(output.resolve("validation_details.csv")).size()
                                == result.validationDetails().size() + 1,
                        "Validation detail CSV row count is wrong.");
                require(Files.readAllLines(output.resolve("final_solves.csv")).size()
                                == result.decisions().size() + 1,
                        "Final solve CSV row count is wrong.");
                int oosRows = result.oosDetails().values().stream().mapToInt(List::size).sum();
                require(Files.readAllLines(output.resolve("oos_draws.csv")).size() == oosRows + 1,
                        "OOS detail CSV row count is wrong.");
                require(Files.readAllLines(output.resolve("oos_summary.csv")).size()
                                == result.oos().size() + 1,
                        "OOS summary CSV row count is wrong.");
                String oosHeader = Files.readAllLines(output.resolve("oos_summary.csv")).get(0);
                require(oosHeader.contains("solve_status")
                                && oosHeader.contains("certified_optimal")
                                && oosHeader.contains("solve_gap"),
                        "OOS summary is missing solve-certificate metadata.");
                verifyOosAggregation(result);
            } finally {
                try (var paths = Files.walk(output)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                }
            }
            try (var paths = Files.walk(checkpointDirectory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
            try (var paths = Files.walk(finalCheckpointDirectory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
            System.out.println("Experiment 1 mini-run methods=" + result.decisions().keySet()
                    + " chosen=" + result.selectedContextual());
            if ("experiment2".equals(args[0])) {
                Path secondCheckpoints = Files.createTempDirectory(Path.of("tmp"),
                        "trb_svu_experiment2_checkpoint_check_");
                Path secondFinalCheckpoints = Files.createTempDirectory(Path.of("tmp"),
                        "trb_svu_experiment2_final_checkpoint_check_");
                TRBSVUExperiment2Runner.Result second = new TRBSVUExperiment2Runner(
                        settings, experiment1, 1, new double[]{0.1}, new double[]{0.05},
                         new TRBSVUValidationCheckpoint(secondCheckpoints,
                                "self-check-instance", "self-check-experiment2"),
                         new TRBSVUFinalCheckpoint(secondFinalCheckpoints.resolve("solve"),
                                 secondFinalCheckpoints.resolve("oos"), "self-check-instance",
                                 "self-check-experiment2", 0, "2"))
                        .run(instance, result.selectedContextual());
                require(second.decisions().size() == 6 && second.oos().size() == 6,
                        "Experiment 2 runner did not return the six formal robust methods.");
                try (var checkpoints = Files.list(secondCheckpoints)) {
                    require(checkpoints.filter(Files::isRegularFile).count()
                                    == second.validationDetails().size(),
                            "Experiment 2 validation results were not checkpointed one-for-one.");
                }
                try (var checkpoints = Files.list(secondFinalCheckpoints.resolve("solve"))) {
                    require(checkpoints.filter(Files::isRegularFile).count()
                                    == second.decisions().size(),
                            "Experiment 2 final solves were not checkpointed one-for-one.");
                }
                try (var checkpoints = Files.list(secondFinalCheckpoints.resolve("oos"))) {
                    require(checkpoints.filter(Files::isRegularFile).count()
                                    == 2L * second.oos().size(),
                            "Experiment 2 OOS results were not checkpointed one method at a time.");
                }
                try (var paths = Files.walk(secondFinalCheckpoints)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                }
                try (var paths = Files.walk(secondCheckpoints)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(path);
                }
                System.out.println("Experiment 2 mini-run methods=" + second.decisions().keySet());
            }
        }
        if (args.length > 0 && "w1-time-limit".equals(args[0])) {
            Settings shortLimit = new Settings(1, 1, 1e-4,
                    RCSAASolverVariant.LBBD_PRIMAL_SEARCH, true, false);
            long started = System.nanoTime();
            Solution limited = null;
            boolean stoppedWithoutIncumbent = false;
            try {
                limited = TRBSVUSolveMethods.solve(instance.params, instance.lanes, equal,
                        instance.testContext, Method.WASSERSTEIN, 0.05, shortLimit);
            } catch (IllegalStateException ex) {
                stoppedWithoutIncumbent = ex.getMessage().contains("did not produce an incumbent");
                if (!stoppedWithoutIncumbent) throw ex;
            }
            double elapsed = (System.nanoTime() - started) / 1.0e9;
            require(stoppedWithoutIncumbent || (!limited.certifiedOptimal
                            && "TIME_LIMIT_W1_CCG".equals(limited.solverStatus)),
                    "W1 did not expose the global time-limit status.");
            require(elapsed < 5.0, "W1 global one-second limit was not respected: " + elapsed);
            System.out.println("W1 global-limit check status="
                    + (limited == null ? "NO_INCUMBENT" : limited.solverStatus)
                    + " elapsed=" + elapsed);
        }
        System.out.println("PASS small runner inputs: equal/recent/mean, four kernels, RF, "
                + "nominal equality solve and equality OOS recourse.");
    }

    private static void verifyKernelFormula(List<Sample> source, CovariateVector query,
                                            TRBSVUScenarioWeights.Kernel kernel,
                                            double bandwidthConstant, List<Sample> actual) {
        double bandwidth = bandwidthConstant
                / Math.pow(source.size(), 1.0 / (query.dim() + 4.0));
        double[] expected = new double[source.size()];
        double total = 0.0;
        for (int s = 0; s < source.size(); s++) {
            double squared = 0.0;
            for (int k = 0; k < query.dim(); k++) {
                double difference = source.get(s).theta.values()[k] - query.values()[k];
                squared += difference * difference;
            }
            double u = Math.sqrt(squared) / bandwidth;
            expected[s] = switch (kernel) {
                case EXPONENTIAL -> Math.exp(-u);
                case GAUSSIAN -> Math.exp(-0.5 * u * u);
                case EPANECHNIKOV -> u < 1.0 ? 1.0 - u * u : 0.0;
                case TRIANGULAR -> Math.max(0.0, 1.0 - u);
            };
            total += expected[s];
        }
        require(total > 0.0, "Manual kernel check has empty support: " + kernel);
        for (int s = 0; s < source.size(); s++)
            require(Math.abs(expected[s] / total - actual.get(s).weight) < 1e-10,
                    "Kernel formula mismatch for " + kernel + " at sample " + s);
    }

    private static void verifyOosAggregation(TRBSVUExperiment1Runner.Result result) {
        for (var entry : result.oosDetails().entrySet()) {
            List<TRBSVUSolveMethods.OosDraw> draws = entry.getValue();
            TRBSVUSolveMethods.Oos summary = result.oos().get(entry.getKey());
            double mean = draws.stream().mapToDouble(TRBSVUSolveMethods.OosDraw::totalCost)
                    .average().orElseThrow();
            double maximum = draws.stream().mapToDouble(TRBSVUSolveMethods.OosDraw::totalCost)
                    .max().orElseThrow();
            double[] costs = draws.stream().mapToDouble(TRBSVUSolveMethods.OosDraw::totalCost)
                    .toArray();
            TRBSVUStatistics.Summary statistics = TRBSVUStatistics.summarize(costs);
            double transport = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::transportCost).average().orElseThrow();
            double spotCost = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::spotCost).average().orElseThrow();
            double penalty = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::mqcPenalty).average().orElseThrow();
            double contracted = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::contractedQuantity).average().orElseThrow();
            double spotQuantity = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::spotQuantity).average().orElseThrow();
            double shortfall = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::mqcShortfallQuantity).average().orElseThrow();
            double totalSpot = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::spotQuantity).sum();
            double totalDemand = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::totalDemand).sum();
            double capacityUtilization = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::capacityUtilization).average().orElseThrow();
            double meanDrawSpotShare = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::spotShare).average().orElseThrow();
            double meanLaneCapacityUtilization = draws.stream().mapToDouble(
                    TRBSVUSolveMethods.OosDraw::meanLaneCapacityUtilization).average().orElseThrow();
            require(Math.abs(mean - summary.mean()) < 1e-9
                            && Math.abs(maximum - summary.maximum()) < 1e-9
                            && Math.abs(statistics.sampleStandardDeviation()
                                    - summary.standardDeviation()) < 1e-9
                            && Math.abs(statistics.q95() - summary.q95()) < 1e-9
                            && Math.abs(statistics.cvar95() - summary.cvar95()) < 1e-9
                            && Math.abs(transport - summary.meanTransportCost()) < 1e-9
                            && Math.abs(spotCost - summary.meanSpotCost()) < 1e-9
                            && Math.abs(penalty - summary.meanPenalty()) < 1e-9
                            && Math.abs(contracted - summary.meanContractedQuantity()) < 1e-9
                            && Math.abs(spotQuantity - summary.meanSpotQuantity()) < 1e-9
                            && Math.abs(shortfall - summary.meanMqcShortfallQuantity()) < 1e-9
                            && Math.abs(totalSpot / totalDemand - summary.spotShare()) < 1e-9
                            && Math.abs(meanDrawSpotShare - summary.meanDrawSpotShare()) < 1e-9
                            && Math.abs(capacityUtilization - summary.capacityUtilization()) < 1e-9
                            && Math.abs(meanLaneCapacityUtilization
                                    - summary.meanLaneCapacityUtilization()) < 1e-9,
                    "OOS summary cannot be reconstructed from details for " + entry.getKey());
        }
    }

    private static boolean sameValidationTraces(List<TRBSVUValidationTrace> left,
                                                List<TRBSVUValidationTrace> right) {
        if (left.size() != right.size()) return false;
        for (int k = 0; k < left.size(); k++) {
            TRBSVUValidationTrace a = left.get(k), b = right.get(k);
            if (!a.method().equals(b.method())
                    || Double.doubleToLongBits(a.candidateParameter())
                            != Double.doubleToLongBits(b.candidateParameter())
                    || a.origin() != b.origin()
                    || a.trainingStart() != b.trainingStart()
                    || a.trainingEnd() != b.trainingEnd()
                    || Double.doubleToLongBits(a.realizedValidationCost())
                            != Double.doubleToLongBits(b.realizedValidationCost())
                    || !Arrays.equals(a.decision(), b.decision())) return false;
        }
        return true;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
