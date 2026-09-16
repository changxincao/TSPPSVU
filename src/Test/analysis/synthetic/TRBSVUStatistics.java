package Test.analysis.synthetic;

import java.util.Arrays;

/** Shared empirical statistics for validation and out-of-sample costs. */
final class TRBSVUStatistics {
    record Summary(double mean, double sampleStandardDeviation, double q95,
                   double cvar95, double maximum) { }

    private TRBSVUStatistics() { }

    static Summary summarize(double[] observations) {
        if (observations == null || observations.length == 0)
            throw new IllegalArgumentException("Empty observations.");
        double sum = 0.0;
        for (double value : observations) {
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite observation.");
            sum += value;
        }
        double mean = sum / observations.length;
        double squared = 0.0;
        for (double value : observations) squared += (value - mean) * (value - mean);

        double[] sorted = observations.clone();
        Arrays.sort(sorted);
        double position = 0.95 * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double q95 = lower == upper ? sorted[lower]
                : sorted[lower] * (upper - position) + sorted[upper] * (position - lower);

        // Exact empirical upper-tail mass: for N=1000 this is the largest 50 costs.
        double tailMass = 0.05 * sorted.length;
        int whole = (int) Math.floor(tailMass);
        double fractional = tailMass - whole;
        double tailSum = 0.0;
        for (int k = 0; k < whole; k++) tailSum += sorted[sorted.length - 1 - k];
        if (fractional > 0.0) tailSum += fractional * sorted[sorted.length - 1 - whole];
        double cvar95 = tailSum / tailMass;

        double sd = observations.length > 1
                ? Math.sqrt(squared / (observations.length - 1)) : 0.0;
        return new Summary(mean, sd, q95, cvar95, sorted[sorted.length - 1]);
    }

    /** Lowest mean, then lowest sample SD, then lowest numeric parameter. */
    static boolean better(double mean, double sd, double parameter,
                          double incumbentMean, double incumbentSd,
                          double incumbentParameter) {
        int meanOrder = Double.compare(mean, incumbentMean);
        if (meanOrder != 0) return meanOrder < 0;
        int sdOrder = Double.compare(sd, incumbentSd);
        if (sdOrder != 0) return sdOrder < 0;
        return Double.compare(parameter, incumbentParameter) < 0;
    }
}
