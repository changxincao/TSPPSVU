package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.Data;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KNearestNeighborWeightCalculator;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.SolveMode;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adapter from the Reviewer 3, Comment 3 synthetic DGP to the current model
 * input ({@link Data}).
 *
 * <p><strong>Reviewer operation.</strong> The input is one saved or freshly
 * generated replication containing independent training pairs, one query
 * context and conditional OOS demands.  This class copies the training data,
 * fits any scaler on the training set only, assigns the method-specific
 * weights, and returns the unchanged {@code Data} object expected by
 * {@code SAAModel} or {@code DROModel}.  It does not solve either model.</p>
 *
 * <p><strong>Output.</strong> {@link PreparedInput#solveData} is the input to
 * the current optimization code. {@link PreparedInput#oosSamples} must only be
 * used after the first-stage decision has been fixed; the optimizer must never
 * see those demands.</p>
 */
public final class TRBReviewerR3M3SyntheticSolveBridge {

    private TRBReviewerR3M3SyntheticSolveBridge() {
    }

    /**
     * Prepares SAA, CSAA or RCSAA/DRO input under the corrected equality model.
     *
     * <p>SAA receives equal weights. Contextual methods receive either kNN or
     * kernel weights after training-only standardization. The method deliberately rejects
     * deterministic modes because their scenario construction is different;
     * use {@link #prepareMeanDeterministic(ReplicationData, ProcurementParams,
     * Config)} for the deterministic benchmark.</p>
     */
    public static PreparedInput prepare(ReplicationData replication,
                                        ProcurementParams params,
                                        Config config) {
        validateCommon(replication, params, config);
        if (config.solveMode != SolveMode.SAA
                && config.solveMode != SolveMode.CSAA
                && config.solveMode != SolveMode.RCSAA) {
            throw new IllegalArgumentException(
                    "prepare requires solveMode in {SAA, CSAA, RCSAA}; got " + config.solveMode);
        }

        List<Sample> training = copySamples(replication.trainingSamples);
        CovariateVector thetaNow = replication.thetaNow.copy();
        thetaNow = standardizeUsingTrainingOnly(training, thetaNow, config);

        if (config.solveMode == SolveMode.SAA) {
            setEqualWeights(training);
        } else if (config.knnNeighbors > 0) {
            KNearestNeighborWeightCalculator calculator =
                    new KNearestNeighborWeightCalculator(new EuclideanDistance());
            calculator.computeWeights(training, thetaNow, config.knnNeighbors);
        } else {
            WeightCalculator calculator = new WeightCalculator(
                    WeightCalculator.buildKernel(config), new EuclideanDistance());
            calculator.computeKernelWeights(training, thetaNow, config);
        }
        validateWeights(training);

        Data data = new Data(replication.laneNames, training, thetaNow, params);
        return new PreparedInput(data, copySamples(replication.oosSamples), replication.conditionalMean);
    }

    /**
     * Prepares the paper's mean-demand deterministic benchmark.
     *
     * <p><strong>Operation.</strong> It averages only the training responses
     * and creates one weight-one scenario. It does not use the true conditional
     * mean or any OOS draw, because either choice would give the benchmark
     * information unavailable to SAA/CSAA.</p>
     */
    public static PreparedInput prepareMeanDeterministic(ReplicationData replication,
                                                         ProcurementParams params,
                                                         Config config) {
        validateCommon(replication, params, config);
        if (config.solveMode != SolveMode.MeanDeterministic) {
            throw new IllegalArgumentException(
                    "prepareMeanDeterministic requires solveMode=MeanDeterministic.");
        }

        double[] meanDemand = meanTrainingDemand(replication.trainingSamples, params.J);
        PeriodData period = new PeriodData(
                0,
                LocalDate.of(2000, 1, 3),
                LocalDate.of(2000, 1, 9),
                meanDemand,
                0,
                0.0,
                0.0,
                0.0);
        Sample meanScenario = new Sample(0, period, replication.thetaNow.copy(), 1.0);
        Data data = new Data(
                replication.laneNames,
                Collections.singletonList(meanScenario),
                replication.thetaNow.copy(),
                params);
        return new PreparedInput(data, copySamples(replication.oosSamples), replication.conditionalMean);
    }

    private static void validateCommon(ReplicationData replication,
                                       ProcurementParams params,
                                       Config config) {
        if (replication == null) throw new IllegalArgumentException("replication is required.");
        if (params == null) throw new IllegalArgumentException("procurement parameters are required.");
        if (config == null) throw new IllegalArgumentException("config is required.");
        if (!config.enforceDemandEquality) {
            throw new IllegalArgumentException(
                    "Formal TRB synthetic experiments must set enforceDemandEquality=true.");
        }
        if (replication.laneNames.size() != params.J) {
            throw new IllegalArgumentException(
                    "Demand lane count " + replication.laneNames.size()
                            + " does not match procurement J=" + params.J + ".");
        }
        if (replication.trainingSamples.isEmpty()) {
            throw new IllegalArgumentException("Training samples cannot be empty.");
        }
        validateSampleDimensions(replication.trainingSamples, params.J, replication.thetaNow.dim(), "training");
        validateSampleDimensions(replication.oosSamples, params.J, replication.thetaNow.dim(), "OOS");
    }

    private static void validateSampleDimensions(List<Sample> samples,
                                                 int laneCount,
                                                 int thetaDimension,
                                                 String label) {
        if (samples.isEmpty()) throw new IllegalArgumentException(label + " samples cannot be empty.");
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            if (sample.demand().length != laneCount) {
                throw new IllegalArgumentException(
                        label + " demand dimension mismatch at index " + index + ".");
            }
            if (sample.theta.dim() != thetaDimension) {
                throw new IllegalArgumentException(
                        label + " theta dimension mismatch at index " + index + ".");
            }
        }
    }

    private static CovariateVector standardizeUsingTrainingOnly(List<Sample> training,
                                                                CovariateVector thetaNow,
                                                                Config config) {
        if (!config.standardizeTheta || training.size() < 2) return thetaNow;

        StandardScaler scaler = new StandardScaler();
        scaler.fit(training, thetaNow.dim());
        for (Sample sample : training) {
            sample.theta = new CovariateVector(scaler.transform(sample.theta.values()));
        }
        return new CovariateVector(scaler.transform(thetaNow.values()));
    }

    private static void setEqualWeights(List<Sample> samples) {
        double weight = 1.0 / samples.size();
        for (Sample sample : samples) sample.weight = weight;
    }

    private static void validateWeights(List<Sample> samples) {
        double sum = 0.0;
        for (int index = 0; index < samples.size(); index++) {
            double weight = samples.get(index).weight;
            if (!Double.isFinite(weight) || weight < 0.0) {
                throw new IllegalStateException("Invalid scenario weight at index " + index + ": " + weight);
            }
            sum += weight;
        }
        if (!Double.isFinite(sum) || Math.abs(sum - 1.0) > 1e-10) {
            throw new IllegalStateException("Scenario weights do not sum to one: " + sum);
        }
    }

    private static double[] meanTrainingDemand(List<Sample> samples, int laneCount) {
        double[] mean = new double[laneCount];
        for (Sample sample : samples) {
            for (int j = 0; j < laneCount; j++) mean[j] += sample.demand()[j];
        }
        for (int j = 0; j < laneCount; j++) mean[j] /= samples.size();
        return mean;
    }

    private static List<Sample> copySamples(List<Sample> source) {
        List<Sample> copy = new ArrayList<>(source.size());
        for (Sample sample : source) {
            PeriodData period = sample.period;
            PeriodData periodCopy = new PeriodData(
                    period.tIndex,
                    period.startDate,
                    period.endDate,
                    period.demandSum.clone(),
                    period.holidayCount,
                    period.avgFreightIndex,
                    period.avgConsumptionIndex,
                    period.avgWEIIndex);
            copy.add(new Sample(sample.id, periodCopy, sample.theta.copy(), sample.weight));
        }
        return copy;
    }

    /** Prepared solver input plus the held-out conditional OOS sample. */
    public static final class PreparedInput {
        public final Data solveData;
        public final List<Sample> oosSamples;
        public final double[] trueConditionalMean;

        private PreparedInput(Data solveData,
                              List<Sample> oosSamples,
                              double[] trueConditionalMean) {
            this.solveData = solveData;
            this.oosSamples = Collections.unmodifiableList(oosSamples);
            this.trueConditionalMean = trueConditionalMean.clone();
        }
    }
}
