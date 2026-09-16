package Helper.calculateHelper;
import java.util.List;

import Basic.CovariateVector;
import Basic.Sample;
import Helper.basicHelper.Config;
import Model.SolveMode;

public class WeightCalculator {
    /** Shared formal-experiment floor; applied before the final normalization. */
    public static final double FORMAL_WEIGHT_FLOOR = 1e-8;

    private final KernelFunction kernel;
    private final DistanceMetric distanceMetric;

    public WeightCalculator(KernelFunction kernel, DistanceMetric distanceMetric) {
        this.kernel = kernel;
        this.distanceMetric = distanceMetric;
    }

    public void computeKernelWeights(List<Sample> samples,
                                     CovariateVector thetaNow,
                                     Config cfg) {
        
    	if(cfg.solveMode==SolveMode.SAA) {
    		 for (int idx = 0; idx < samples.size(); idx++) {
    	            samples.get(idx).weight = 1.0 / samples.size();
    	        }
    		 return;
    	}
    	
    	        // 当前实现不是直接把 C_h 当成 exp(-C_h * dist) 里的乘子来用，
        // 而是先把 C_h 转成带宽 h，再按 exp(-dist / h) 这一类形式计算核值。
        // 因此这里的方向要特别注意：
        // - C_h 越小 => 带宽 h 越小；
        // - 带宽越小 => 权重越尖、越集中；
        // - 权重越尖 => 越容易出现数值下溢、0 权重、NaN / Inf 等退化情况。
    	cfg.bandwidthH =cfg.C_h/Math.pow(samples.size(), 1.0/(4+thetaNow.dim()));
        if (!Double.isFinite(cfg.bandwidthH) || cfg.bandwidthH <= 0.0) {
            throw new IllegalStateException("Invalid bandwidthH=" + cfg.bandwidthH + " for C_h=" + cfg.C_h);
        }

        double[] now = thetaNow.values();
        double[] logNumer = new double[samples.size()];
        double maxLog = Double.NEGATIVE_INFINITY;

        for (int idx = 0; idx < samples.size(); idx++) {
            Sample s = samples.get(idx);
            double dist = distanceMetric.distance(now, s.theta.values());
            double logVal = stableLogKernelValue(dist, cfg.bandwidthH, cfg);
            logNumer[idx] = logVal;
            if (logVal > maxLog) maxLog = logVal;
        }

        if (!Double.isFinite(maxLog)) {
            throw new IllegalStateException("All kernel log-weights are non-finite. C_h=" + cfg.C_h
                    + ", bandwidthH=" + cfg.bandwidthH);
        }

        double denom = 0.0;
        double[] numer = new double[samples.size()];
        for (int idx = 0; idx < samples.size(); idx++) {
            double shifted = logNumer[idx] - maxLog;
            double val = Math.exp(shifted);
            numer[idx] = val;
            denom += val;
        }

        if (!Double.isFinite(denom) || denom <= 0.0) {
            throw new IllegalStateException("Kernel weight denominator invalid. denom=" + denom
                    + ", C_h=" + cfg.C_h + ", bandwidthH=" + cfg.bandwidthH);
        }

        double sumWeights = 0.0;
        for (int idx = 0; idx < samples.size(); idx++) {
	        	double w = Math.max(numer[idx] / denom, FORMAL_WEIGHT_FLOOR);
            if (!Double.isFinite(w) || w < 0.0) {
                throw new IllegalStateException("Invalid normalized weight=" + w
                        + " at idx=" + idx + ", C_h=" + cfg.C_h + ", bandwidthH=" + cfg.bandwidthH);
            }
            samples.get(idx).weight = w;
            sumWeights += w;
        }

        if (!Double.isFinite(sumWeights) || sumWeights <= 0.0) {
            throw new IllegalStateException("Normalized weight sum invalid. sum=" + sumWeights
                    + ", C_h=" + cfg.C_h + ", bandwidthH=" + cfg.bandwidthH);
        }
        for (Sample s : samples) {
            s.weight /= sumWeights;
        }
    }

    private double stableLogKernelValue(double distance, double h, Config cfg) {
        double z = Math.abs(distance / h);
        return switch (cfg.kernelType) {
            case GAUSSIAN -> -0.5 * z * z;
            case EXPONENTIAL -> -z;
        };
    }

    public static KernelFunction buildKernel(Config cfg) {
        return switch (cfg.kernelType) {
            case GAUSSIAN -> new GaussianKernel();
            case EXPONENTIAL -> new ExponentialKernel();
        };
    }
}

