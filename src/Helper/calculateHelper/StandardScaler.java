package Helper.calculateHelper;

import java.util.List;

import Basic.Sample;

public class StandardScaler {
    public enum Mode { Z_SCORE, TRAINING_MAX }

    private double[] mean;
    private double[] std;
    private final Mode mode;

    public StandardScaler() {
        this(Mode.Z_SCORE);
    }

    public StandardScaler(Mode mode) {
        if (mode == null) throw new IllegalArgumentException("Scaling mode is required.");
        this.mode = mode;
    }

    public void fit(List<Sample> samples, int dim) {
        mean = new double[dim];
        std = new double[dim];

        int n = samples.size();
        if (mode == Mode.TRAINING_MAX) {
            for (Sample s : samples) {
                double[] x = s.theta.values();
                for (int k = 0; k < dim; k++) std[k] = Math.max(std[k], Math.abs(x[k]));
            }
            for (int k = 0; k < dim; k++) {
                mean[k] = 0.0;
                if (std[k] < 1e-12) std[k] = 1.0;
            }
            return;
        }
        for (Sample s : samples) {
            double[] x = s.theta.values();
            for (int k = 0; k < dim; k++) mean[k] += x[k];
        }
        for (int k = 0; k < dim; k++) mean[k] /= Math.max(1, n);

        for (Sample s : samples) {
            double[] x = s.theta.values();
            for (int k = 0; k < dim; k++) {
                double d = x[k] - mean[k];
                std[k] += d * d;
            }
        }
        for (int k = 0; k < dim; k++) {
            std[k] = Math.sqrt(std[k] / Math.max(1, n));
            if (std[k] < 1e-12) std[k] = 1.0; // avoid divide-by-zero 防止一些sample的某一维度全都一样的
        }
    }

    public Mode mode() {
        return mode;
    }

    public double[] transform(double[] x) {
        double[] z = new double[x.length];
        for (int k = 0; k < x.length; k++) {
            z[k] = (x[k] - mean[k]) / std[k];
        }
        return z;
    }
}
