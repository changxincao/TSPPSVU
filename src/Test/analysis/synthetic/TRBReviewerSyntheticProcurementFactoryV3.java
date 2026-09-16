package Test.analysis.synthetic;

import Basic.ProcurementParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Procurement generator used only by the gated v3 synthetic pilot. */
public final class TRBReviewerSyntheticProcurementFactoryV3 {

    private TRBReviewerSyntheticProcurementFactoryV3() {
    }

    public enum Regime {
        BALANCED(2.0, 0.35, 0.55, 1.0),
        CAPACITY_TIGHT(1.6, 0.35, 0.55, 1.0),
        MQC_TIGHT(2.0, 0.55, 0.75, 1.0),
        MQC_HIGH_PENALTY(2.0, 0.55, 0.75, 1.5),
        MQC_VERY_TIGHT(2.0, 0.75, 0.90, 1.0);

        public final double marketCapacityRatio;
        public final double mqcUtilizationLow;
        public final double mqcUtilizationHigh;
        public final double penaltyRateRatio;

        Regime(double marketCapacityRatio,
               double mqcUtilizationLow,
               double mqcUtilizationHigh,
               double penaltyRateRatio) {
            this.marketCapacityRatio = marketCapacityRatio;
            this.mqcUtilizationLow = mqcUtilizationLow;
            this.mqcUtilizationHigh = mqcUtilizationHigh;
            this.penaltyRateRatio = penaltyRateRatio;
        }
    }

    public static Generated generate(double[] baselineDemand,
                                     int carrierCount,
                                     double targetCoverage,
                                     Regime regime,
                                     long seed) {
        if (baselineDemand == null || baselineDemand.length == 0) {
            throw new IllegalArgumentException("baselineDemand is required.");
        }
        if (carrierCount <= 0 || !(targetCoverage > 0.0 && targetCoverage <= 1.0)) {
            throw new IllegalArgumentException("Invalid carrier count or coverage.");
        }

        int I = carrierCount;
        int J = baselineDemand.length;
        int lanesPerCarrier = Math.max(1, Math.min(J, (int) Math.round(targetCoverage * J)));
        boolean[][] eligible = balancedCoverage(I, J, lanesPerCarrier, seed);

        Random economics = new Random(mix64(seed ^ 0x517CC1B727220A95L));
        double[][] r = new double[I][J];
        buildRates(r, eligible, economics);

        double[][] q = new double[I][J];
        for (int j = 0; j < J; j++) {
            double[] raw = new double[I];
            double rawSum = 0.0;
            for (int i = 0; i < I; i++) {
                if (!eligible[i][j]) continue;
                raw[i] = uniform(economics, 0.8, 1.2);
                rawSum += raw[i];
            }
            if (!(rawSum > 0.0)) throw new IllegalStateException("Uncovered lane " + j);
            for (int i = 0; i < I; i++) {
                if (eligible[i][j]) {
                    q[i][j] = regime.marketCapacityRatio * baselineDemand[j] * raw[i] / rawSum;
                }
            }
        }

        double[] p = new double[I];
        double[] h = new double[I];
        for (int i = 0; i < I; i++) {
            double capacity = 0.0;
            double weightedRate = 0.0;
            for (int j = 0; j < J; j++) {
                capacity += q[i][j];
                weightedRate += q[i][j] * r[i][j];
            }
            double eta = uniform(economics,
                    regime.mqcUtilizationLow, regime.mqcUtilizationHigh);
            p[i] = eta * capacity;
            h[i] = regime.penaltyRateRatio * weightedRate / capacity;
        }

        double[] spot = new double[J];
        for (int j = 0; j < J; j++) {
            List<Double> rates = new ArrayList<>();
            for (int i = 0; i < I; i++) if (eligible[i][j]) rates.add(r[i][j]);
            Collections.sort(rates);
            double median = rates.size() % 2 == 1
                    ? rates.get(rates.size() / 2)
                    : 0.5 * (rates.get(rates.size() / 2 - 1) + rates.get(rates.size() / 2));
            spot[j] = uniform(economics, 1.6, 2.0) * median;
        }

        List<String> carriers = new ArrayList<>(I);
        for (int i = 0; i < I; i++) carriers.add("C" + (i + 1));
        int alpha = Math.max(1, (int) Math.ceil(0.1 * I));
        int beta = Math.max(alpha, (int) Math.ceil(0.7 * I));
        ProcurementParams params = new ProcurementParams(
                carriers, J, spot, p, h, q, r, eligible, alpha, beta);
        return new Generated(params, regime, targetCoverage, lanesPerCarrier, seed);
    }

    private static boolean[][] balancedCoverage(int I,
                                                int J,
                                                int lanesPerCarrier,
                                                long seed) {
        List<Integer> laneOrder = new ArrayList<>(J);
        for (int j = 0; j < J; j++) laneOrder.add(j);
        Collections.shuffle(laneOrder, new Random(mix64(seed)));

        boolean[][] eligible = new boolean[I][J];
        for (int i = 0; i < I; i++) {
            int start = (int) Math.floor((double) i * J / I);
            for (int offset = 0; offset < lanesPerCarrier; offset++) {
                eligible[i][laneOrder.get((start + offset) % J)] = true;
            }
        }
        for (int j = 0; j < J; j++) {
            boolean covered = false;
            for (int i = 0; i < I; i++) covered |= eligible[i][j];
            if (!covered) throw new IllegalArgumentException("Coverage leaves lane " + j + " empty.");
        }
        return eligible;
    }

    private static void buildRates(double[][] r,
                                   boolean[][] eligible,
                                   Random random) {
        int I = r.length;
        int J = r[0].length;
        for (int j = 0; j < J; j++) {
            double laneBase = uniform(random, 20.0, 100.0);
            List<Integer> carriers = new ArrayList<>();
            for (int i = 0; i < I; i++) if (eligible[i][j]) carriers.add(i);
            Collections.shuffle(carriers, random);
            for (int k = 0; k < carriers.size(); k++) {
                double group = k < carriers.size() / 3 ? 0.75
                        : k < 2 * carriers.size() / 3 ? 1.0 : 1.25;
                r[carriers.get(k)][j] = laneBase * group * uniform(random, 0.9, 1.1);
            }
        }
    }

    private static double uniform(Random random, double lower, double upper) {
        return lower + (upper - lower) * random.nextDouble();
    }

    private static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    public record Generated(ProcurementParams params,
                            Regime regime,
                            double targetCoverage,
                            int lanesPerCarrier,
                            long seed) {
    }
}
