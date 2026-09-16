package Helper.calculateHelper;

import Basic.CovariateVector;
import Basic.Sample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Assigns equal probability to the K training contexts nearest to a query. */
public final class KNearestNeighborWeightCalculator {
    private final EuclideanDistance distanceMetric;

    public KNearestNeighborWeightCalculator(EuclideanDistance distanceMetric) {
        if (distanceMetric == null) throw new IllegalArgumentException("distanceMetric is required.");
        this.distanceMetric = distanceMetric;
    }

    public void computeWeights(List<Sample> samples,
                               CovariateVector thetaNow,
                               int neighbors) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("kNN requires at least one training sample.");
        }
        if (thetaNow == null) throw new IllegalArgumentException("thetaNow is required.");
        if (neighbors <= 0 || neighbors > samples.size()) {
            throw new IllegalArgumentException(
                    "kNN neighbors must be in [1," + samples.size() + "]; got " + neighbors + ".");
        }

        double[] query = thetaNow.values();
        List<IndexedDistance> distances = new ArrayList<>(samples.size());
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            if (sample.theta.dim() != query.length) {
                throw new IllegalArgumentException(
                        "kNN theta dimension mismatch at sample " + index + ".");
            }
            double distance = distanceMetric.distance(query, sample.theta.values());
            if (!Double.isFinite(distance) || distance < 0.0) {
                throw new IllegalArgumentException(
                        "Invalid kNN distance at sample " + index + ": " + distance + ".");
            }
            distances.add(new IndexedDistance(index, distance));
            sample.weight = 0.0;
        }

        distances.sort(Comparator.comparingDouble(IndexedDistance::distance)
                .thenComparingInt(IndexedDistance::index));
        double weight = 1.0 / neighbors;
        for (int rank = 0; rank < neighbors; rank++) {
            samples.get(distances.get(rank).index()).weight = weight;
        }
    }

    private record IndexedDistance(int index, double distance) {
    }
}
