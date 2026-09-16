package Model;

import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;

import java.util.List;

/**
 * Covariate-agnostic input for a weighted empirical 1-Wasserstein model.
 *
 * <p>The center distribution is {@code sum_s probability[s] delta_demand[s]}.
 * Probabilities may be equal (SAA) or supplied by any contextual estimator.
 * They are normalized once here; zero-probability samples remain exactly zero.</p>
 */
public final class WassersteinBoxInput {
    public final ProcurementParams params;
    public final double[][] demand;
    public final double[] probability;
    public final double[] upper;
    public final double[] scale;
    public final double radius;

    public WassersteinBoxInput(ProcurementParams params,
                               double[][] demand,
                               double[] probability,
                               double[] upper,
                               double[] scale,
                               double radius) {
        if (params == null) throw new IllegalArgumentException("params required");
        if (demand == null || demand.length == 0) {
            throw new IllegalArgumentException("At least one demand sample is required.");
        }
        if (probability == null || probability.length != demand.length) {
            throw new IllegalArgumentException("Probability/sample count mismatch.");
        }
        if (upper == null || upper.length != params.J
                || scale == null || scale.length != params.J) {
            throw new IllegalArgumentException("Box/scale dimension mismatch.");
        }
        if (!Double.isFinite(radius) || radius < 0.0) {
            throw new IllegalArgumentException("Invalid Wasserstein radius " + radius);
        }

        this.params = params;
        this.demand = new double[demand.length][params.J];
        this.probability = probability.clone();
        this.upper = upper.clone();
        this.scale = scale.clone();
        this.radius = radius;

        double probabilitySum = 0.0;
        for (int s = 0; s < demand.length; s++) {
            if (demand[s] == null || demand[s].length != params.J) {
                throw new IllegalArgumentException("Demand dimension mismatch at sample " + s);
            }
            if (!Double.isFinite(this.probability[s]) || this.probability[s] < 0.0) {
                throw new IllegalArgumentException("Invalid probability at sample " + s);
            }
            probabilitySum += this.probability[s];
            for (int j = 0; j < params.J; j++) {
                double value = demand[s][j];
                if (!Double.isFinite(value) || value < -1e-9
                        || value > this.upper[j] + 1e-9) {
                    throw new IllegalArgumentException(
                            "Demand outside box at sample/lane " + s + "/" + j);
                }
                this.demand[s][j] = Math.max(0.0, value);
            }
        }
        if (!(probabilitySum > 0.0) || !Double.isFinite(probabilitySum)) {
            throw new IllegalArgumentException("Probabilities must have a positive finite sum.");
        }
        for (int s = 0; s < this.probability.length; s++) {
            this.probability[s] /= probabilitySum;
        }

        for (int j = 0; j < params.J; j++) {
            if (!Double.isFinite(this.upper[j]) || this.upper[j] < 0.0
                    || !Double.isFinite(this.scale[j]) || !(this.scale[j] > 0.0)) {
                throw new IllegalArgumentException("Invalid upper/scale at lane " + j);
            }
        }
    }

    public static WassersteinBoxInput fromData(Data data,
                                                double[] upper,
                                                double[] scale,
                                                double radius) {
        if (data == null) throw new IllegalArgumentException("data required");
        List<Sample> samples = data.samples;
        double[][] demand = new double[samples.size()][];
        double[] probability = new double[samples.size()];
        for (int s = 0; s < samples.size(); s++) {
            demand[s] = samples.get(s).demand();
            probability[s] = samples.get(s).weight;
        }
        return new WassersteinBoxInput(data.params, demand, probability,
                upper, scale, radius);
    }

    public static WassersteinBoxInput equalWeight(ProcurementParams params,
                                                   double[][] demand,
                                                   double[] upper,
                                                   double[] scale,
                                                   double radius) {
        double[] probability = new double[demand.length];
        for (int s = 0; s < probability.length; s++) probability[s] = 1.0;
        return new WassersteinBoxInput(params, demand, probability, upper, scale, radius);
    }

    public int sampleCount() {
        return demand.length;
    }

    public double lipschitzBound() {
        double bound = 0.0;
        for (int j = 0; j < params.J; j++) {
            double slopeMagnitude = Math.max(params.e[j], -alphaLowerBound(j));
            bound = Math.max(bound, slopeMagnitude * scale[j]);
        }
        return bound;
    }

    /** Valid lower bound on a demand-equality dual multiplier for lane {@code j}. */
    public double alphaLowerBound(int j) {
        double lower = 0.0;
        for (int i = 0; i < params.I; i++) {
            if (params.eligible[i][j]) {
                lower = Math.min(lower, params.r[i][j] - params.h[i]);
            }
        }
        return lower;
    }

    public double distance(int sample, double[] point) {
        double value = 0.0;
        for (int j = 0; j < params.J; j++) {
            value += Math.abs(point[j] - demand[sample][j]) / scale[j];
        }
        return value;
    }
}
