package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.Sample;

import java.util.ArrayList;
import java.util.List;

/** Experiment 1 weighting rules; no solver or demand generator is hidden here. */
public final class TRBSVUScenarioWeights {
    public enum Kernel { EXPONENTIAL, GAUSSIAN, EPANECHNIKOV, TRIANGULAR }

    private TRBSVUScenarioWeights() { }

    public static List<Sample> equal(List<Sample> source) {
        if (source.isEmpty()) throw new IllegalArgumentException("Empty training pool.");
        double[] weights = new double[source.size()];
        java.util.Arrays.fill(weights, 1.0 / source.size());
        return copyWithWeights(source, weights, false);
    }

    public static List<Sample> recent(List<Sample> source, double fraction) {
        if (source.isEmpty() || !(fraction > 0.0 && fraction <= 1.0))
            throw new IllegalArgumentException("Invalid history retention ratio.");
        int count = (int) Math.ceil(source.size() * fraction);
        return equal(source.subList(source.size() - count, source.size()));
    }

    public static List<Sample> arithmeticMean(List<Sample> source) {
        if (source.isEmpty()) throw new IllegalArgumentException("Empty training pool.");
        int lanes = source.get(0).demand().length;
        double[] mean = new double[lanes];
        for (Sample sample : source) {
            if (sample.demand().length != lanes) throw new IllegalArgumentException("Lane mismatch.");
            for (int j = 0; j < lanes; j++) mean[j] += sample.demand()[j] / source.size();
        }
        PeriodData original = source.get(source.size() - 1).period;
        PeriodData period = new PeriodData(original.tIndex, original.startDate, original.endDate,
                mean, 0, 0.0, 0.0, 0.0);
        return List.of(new Sample(-1, period, source.get(source.size() - 1).theta.copy(), 1.0));
    }

    /** B/n^(1/(l+4)); empty means zero support, never an equal-weight fallback. */
    public static List<Sample> kernel(List<Sample> source, CovariateVector query,
                                       Kernel family, double bandwidthConstant) {
        if (source.isEmpty() || query == null || family == null || !(bandwidthConstant > 0.0))
            throw new IllegalArgumentException("Invalid kernel input.");
        int dimension = query.dim();
        double bandwidth = bandwidthConstant / Math.pow(source.size(), 1.0 / (dimension + 4.0));
        double[] weights = new double[source.size()];
        double maxLog = Double.NEGATIVE_INFINITY;
        for (int s = 0; s < source.size(); s++) {
            double distance = euclidean(source.get(s).theta, query) / bandwidth;
            switch (family) {
                case EXPONENTIAL -> weights[s] = -distance;
                case GAUSSIAN -> weights[s] = -0.5 * distance * distance;
                case EPANECHNIKOV -> weights[s] = distance < 1.0 ? 1.0 - distance * distance : 0.0;
                case TRIANGULAR -> weights[s] = Math.max(0.0, 1.0 - distance);
            }
            if (family == Kernel.EXPONENTIAL || family == Kernel.GAUSSIAN)
                maxLog = Math.max(maxLog, weights[s]);
        }
        if (family == Kernel.EXPONENTIAL || family == Kernel.GAUSSIAN) {
            for (int s = 0; s < weights.length; s++) weights[s] = Math.exp(weights[s] - maxLog);
        }
        double total = 0.0;
        for (double weight : weights) total += weight;
        if (!(total > 0.0) || !Double.isFinite(total)) return List.of();
        for (int s = 0; s < weights.length; s++) weights[s] /= total;
        return copyWithWeights(source, weights, false);
    }

    /** Copy weights and metadata so method runs cannot mutate the shared case. */
    public static List<Sample> copyWithWeights(List<Sample> source, double[] weights,
                                                boolean pruneZeros) {
        if (source.size() != weights.length) throw new IllegalArgumentException("Weight mismatch.");
        List<Sample> result = new ArrayList<>(source.size());
        double sum = 0.0;
        for (int s = 0; s < source.size(); s++) {
            double weight = weights[s];
            if (!Double.isFinite(weight) || weight < 0.0) throw new IllegalArgumentException("Invalid weight.");
            if (pruneZeros && weight == 0.0) continue;
            Sample old = source.get(s);
            result.add(new Sample(old.id, old.period, old.theta.copy(), weight));
            sum += weight;
        }
        if (!(sum > 0.0)) throw new IllegalArgumentException("No positive scenario weight.");
        for (Sample sample : result) sample.weight /= sum;
        return result;
    }

    private static double euclidean(CovariateVector a, CovariateVector b) {
        if (a.dim() != b.dim()) throw new IllegalArgumentException("Context dimension mismatch.");
        double sum = 0.0;
        for (int k = 0; k < a.dim(); k++) {
            double difference = a.values()[k] - b.values()[k];
            sum += difference * difference;
        }
        return Math.sqrt(sum);
    }
}
