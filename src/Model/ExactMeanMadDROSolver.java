package Model;

import Basic.CovariateVector;
import Basic.Data;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exact mean--MAD solver for the current box-supported procurement recourse.
 *
 * <p>Under {@code h_i <= r_ij} on every eligible lane, the demand-equality
 * recourse has the same optimum as its covering form.  The latter has a
 * supermodular value function under the current lane/carrier incidence
 * structure.  Long, Qi and Zhang (2024, Management Science,
 * DOI 10.1287/mnsc.2023.4748) then reduce the fully adaptive mean--MAD DRO to
 * one comonotone distribution with at most {@code 2*J+1} atoms.</p>
 */
public final class ExactMeanMadDROSolver {
    private static final double TOLERANCE = 1e-10;

    public Result solve(ProcurementParams params,
                        double[] lower,
                        double[] mean,
                        double[] madUpper,
                        double[] upper,
                        Config config) throws Exception {
        validateModel(params, lower, mean, madUpper, upper, config);
        Distribution distribution = buildWorstCaseDistribution(
                lower, mean, madUpper, upper);

        List<String> lanes = new ArrayList<>(params.J);
        for (int j = 0; j < params.J; j++) lanes.add("j" + j);
        List<Sample> samples = new ArrayList<>(distribution.probability.length);
        for (int k = 0; k < distribution.probability.length; k++) {
            PeriodData period = new PeriodData(k, null, null,
                    distribution.demand[k].clone(), 0, 0.0, 0.0, 0.0);
            samples.add(new Sample(k, period,
                    new CovariateVector(new double[0]),
                    distribution.probability[k]));
        }

        long started = System.nanoTime();
        Solution solution = new SAAModel().solve(
                new Data(lanes, samples, new CovariateVector(new double[0]), params),
                config, null);
        solution.solveTimeSec = (System.nanoTime() - started) / 1.0e9;
        solution.solverStatus = solution.certifiedOptimal
                ? "OPTIMAL_EXACT_MEAN_MAD"
                : "UNCERTIFIED_MEAN_MAD_EXTENSIVE_FORM";
        return new Result(solution, distribution);
    }

    /** Builds the exact extremal joint law; independent of procurement costs. */
    public static Distribution buildWorstCaseDistribution(double[] lower,
                                                           double[] mean,
                                                           double[] madUpper,
                                                           double[] upper) {
        validateMomentInput(lower, mean, madUpper, upper);
        int dimension = mean.length;
        double[] effectiveMad = new double[dimension];
        double[] lowerProbability = new double[dimension];
        double[] upperProbability = new double[dimension];
        List<Double> breakpoints = new ArrayList<>();
        breakpoints.add(0.0);
        breakpoints.add(1.0);

        for (int j = 0; j < dimension; j++) {
            double left = mean[j] - lower[j];
            double right = upper[j] - mean[j];
            double width = upper[j] - lower[j];
            double maximumMad = width <= TOLERANCE
                    ? 0.0 : 2.0 * left * right / width;
            effectiveMad[j] = Math.min(madUpper[j], maximumMad);
            lowerProbability[j] = left <= TOLERANCE
                    ? 0.0 : effectiveMad[j] / (2.0 * left);
            upperProbability[j] = right <= TOLERANCE
                    ? 0.0 : effectiveMad[j] / (2.0 * right);
            breakpoints.add(clamp01(lowerProbability[j]));
            breakpoints.add(clamp01(1.0 - upperProbability[j]));
        }

        breakpoints.sort(Double::compareTo);
        List<Double> unique = new ArrayList<>();
        for (double value : breakpoints) {
            if (unique.isEmpty()
                    || Math.abs(value - unique.get(unique.size() - 1)) > TOLERANCE) {
                unique.add(value);
            }
        }

        List<double[]> atoms = new ArrayList<>();
        List<Double> probabilities = new ArrayList<>();
        for (int interval = 0; interval + 1 < unique.size(); interval++) {
            double leftEnd = unique.get(interval);
            double rightEnd = unique.get(interval + 1);
            double probability = rightEnd - leftEnd;
            if (probability <= TOLERANCE) continue;
            double uniform = 0.5 * (leftEnd + rightEnd);
            double[] demand = new double[dimension];
            for (int j = 0; j < dimension; j++) {
                if (uniform < lowerProbability[j] - TOLERANCE) {
                    demand[j] = lower[j];
                } else if (uniform < 1.0 - upperProbability[j] - TOLERANCE) {
                    demand[j] = mean[j];
                } else {
                    demand[j] = upper[j];
                }
            }
            int duplicate = findAtom(atoms, demand);
            if (duplicate >= 0) {
                probabilities.set(duplicate,
                        probabilities.get(duplicate) + probability);
            } else {
                atoms.add(demand);
                probabilities.add(probability);
            }
        }

        double[][] demand = atoms.toArray(double[][]::new);
        double[] probability = new double[probabilities.size()];
        for (int k = 0; k < probability.length; k++) {
            probability[k] = probabilities.get(k);
        }
        return new Distribution(demand, probability, effectiveMad,
                attainedMean(demand, probability),
                attainedMad(demand, probability, mean));
    }

    private static void validateModel(ProcurementParams params,
                                      double[] lower,
                                      double[] mean,
                                      double[] madUpper,
                                      double[] upper,
                                      Config config) {
        if (params == null || config == null) {
            throw new IllegalArgumentException("params/config required");
        }
        if (!config.enforceDemandEquality) {
            throw new IllegalArgumentException(
                    "Mean-MAD solver requires demand equality.");
        }
        if (mean.length != params.J) {
            throw new IllegalArgumentException("Moment dimension mismatch.");
        }
        validateMomentInput(lower, mean, madUpper, upper);
        for (int i = 0; i < params.I; i++) {
            if (params.h[i] < -TOLERANCE || params.p[i] < -TOLERANCE
                    || params.M[i] < -TOLERANCE) {
                throw new IllegalArgumentException("Negative carrier parameter at " + i);
            }
            for (int j = 0; j < params.J; j++) {
                if (!params.eligible[i][j]) continue;
                if (params.r[i][j] < -TOLERANCE || params.q[i][j] < -TOLERANCE) {
                    throw new IllegalArgumentException(
                            "Negative rate/capacity at " + i + "/" + j);
                }
                if (params.h[i] > params.r[i][j] + TOLERANCE) {
                    throw new IllegalArgumentException(
                            "Exact Mean-MAD gate requires h_i <= r_ij at "
                                    + i + "/" + j);
                }
            }
        }
        for (int j = 0; j < params.J; j++) {
            if (params.e[j] < -TOLERANCE) {
                throw new IllegalArgumentException("Negative spot rate at lane " + j);
            }
        }
    }

    private static void validateMomentInput(double[] lower,
                                            double[] mean,
                                            double[] madUpper,
                                            double[] upper) {
        if (lower == null || mean == null || madUpper == null || upper == null
                || lower.length == 0 || lower.length != mean.length
                || mean.length != madUpper.length || mean.length != upper.length) {
            throw new IllegalArgumentException("Mean-MAD dimension mismatch.");
        }
        for (int j = 0; j < mean.length; j++) {
            if (!Double.isFinite(lower[j]) || !Double.isFinite(mean[j])
                    || !Double.isFinite(madUpper[j]) || !Double.isFinite(upper[j])
                    || lower[j] < 0.0 || mean[j] < lower[j] - TOLERANCE
                    || mean[j] > upper[j] + TOLERANCE || madUpper[j] < 0.0) {
                throw new IllegalArgumentException("Invalid Mean-MAD input at lane " + j);
            }
        }
    }

    private static int findAtom(List<double[]> atoms, double[] candidate) {
        for (int k = 0; k < atoms.size(); k++) {
            if (Arrays.equals(atoms.get(k), candidate)) return k;
        }
        return -1;
    }

    private static double[] attainedMean(double[][] demand, double[] probability) {
        double[] value = new double[demand[0].length];
        for (int k = 0; k < demand.length; k++) {
            for (int j = 0; j < value.length; j++) {
                value[j] += probability[k] * demand[k][j];
            }
        }
        return value;
    }

    private static double[] attainedMad(double[][] demand,
                                        double[] probability,
                                        double[] mean) {
        double[] value = new double[mean.length];
        for (int k = 0; k < demand.length; k++) {
            for (int j = 0; j < value.length; j++) {
                value[j] += probability[k] * Math.abs(demand[k][j] - mean[j]);
            }
        }
        return value;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public static final class Distribution {
        public final double[][] demand;
        public final double[] probability;
        public final double[] effectiveMad;
        public final double[] attainedMean;
        public final double[] attainedMad;

        private Distribution(double[][] demand,
                             double[] probability,
                             double[] effectiveMad,
                             double[] attainedMean,
                             double[] attainedMad) {
            this.demand = new double[demand.length][];
            for (int k = 0; k < demand.length; k++) {
                this.demand[k] = demand[k].clone();
            }
            this.probability = probability.clone();
            this.effectiveMad = effectiveMad.clone();
            this.attainedMean = attainedMean.clone();
            this.attainedMad = attainedMad.clone();
        }
    }

    public record Result(Solution solution, Distribution distribution) {
    }
}
