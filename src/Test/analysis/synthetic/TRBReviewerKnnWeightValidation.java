package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.Sample;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KNearestNeighborWeightCalculator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Dependency-free regression checks for the reviewer kNN-SAA weights. */
public final class TRBReviewerKnnWeightValidation {
    private TRBReviewerKnnWeightValidation() {
    }

    public static void main(String[] args) {
        KNearestNeighborWeightCalculator calculator =
                new KNearestNeighborWeightCalculator(new EuclideanDistance());

        List<Sample> samples = samples(0.0, 1.0, 2.0);
        calculator.computeWeights(samples, new CovariateVector(new double[]{0.2}), 2);
        require(samples.get(0).weight == 0.5, "nearest sample weight");
        require(samples.get(1).weight == 0.5, "second-nearest sample weight");
        require(samples.get(2).weight == 0.0, "non-neighbor weight");

        samples = samples(0.0, 2.0);
        calculator.computeWeights(samples, new CovariateVector(new double[]{1.0}), 1);
        require(samples.get(0).weight == 1.0, "tie breaks by training index");
        require(samples.get(1).weight == 0.0, "tie excludes later training index");

        samples = samples(0.0, 1.0, 2.0, 3.0);
        calculator.computeWeights(samples, new CovariateVector(new double[]{100.0}), 4);
        for (Sample sample : samples) require(sample.weight == 0.25, "K=S equals SAA");

        boolean rejected = false;
        try {
            calculator.computeWeights(samples, new CovariateVector(new double[]{0.0}), 5);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        require(rejected, "K>S must be rejected");
        System.out.println("TRBReviewerKnnWeightValidation PASSED");
    }

    private static List<Sample> samples(double... thetaValues) {
        List<Sample> samples = new ArrayList<>(thetaValues.length);
        for (int index = 0; index < thetaValues.length; index++) {
            PeriodData period = new PeriodData(index,
                    LocalDate.of(2000, 1, 3).plusWeeks(index),
                    LocalDate.of(2000, 1, 9).plusWeeks(index),
                    new double[]{1.0}, 0, 0.0, 0.0, 0.0);
            samples.add(new Sample(index, period,
                    new CovariateVector(new double[]{thetaValues[index]}), 0.0));
        }
        return samples;
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
