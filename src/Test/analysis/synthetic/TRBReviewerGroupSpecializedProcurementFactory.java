package Test.analysis.synthetic;

import Basic.ProcurementParams;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Small mechanism-screening factory with transparent lane-group specialization.
 * It is separate from the formal reviewer factory and does not change any
 * solver or model code.
 */
public final class TRBReviewerGroupSpecializedProcurementFactory {
    private TRBReviewerGroupSpecializedProcurementFactory() {
    }

    /** A moderate long-tail baseline: neither near-uniform nor Olist top-1 dominated. */
    public static double[] moderateBaseline(int laneCount,
                                            double totalDemand,
                                            double rankExponent,
                                            long seed) {
        if (laneCount <= 0 || !(totalDemand > 0.0)
                || rankExponent < 0.0 || rankExponent > 1.5) {
            throw new IllegalArgumentException("Invalid baseline settings.");
        }
        List<Integer> order = new ArrayList<>(laneCount);
        for (int j = 0; j < laneCount; j++) order.add(j);
        Collections.shuffle(order, new Random(mix64(seed)));

        double[] baseline = new double[laneCount];
        double normalizer = 0.0;
        for (int rank = 1; rank <= laneCount; rank++) normalizer += Math.pow(rank, -rankExponent);
        for (int rank = 1; rank <= laneCount; rank++) {
            baseline[order.get(rank - 1)] = totalDemand
                    * Math.pow(rank, -rankExponent) / normalizer;
        }
        return baseline;
    }

    /** Balanced, cost-independent lane groups fixed by one seed. */
    public static int[] balancedLaneGroups(int laneCount, int groupCount, long seed) {
        if (laneCount < groupCount || groupCount < 2) {
            throw new IllegalArgumentException("Invalid lane/group count.");
        }
        List<Integer> lanes = new ArrayList<>(laneCount);
        for (int j = 0; j < laneCount; j++) lanes.add(j);
        Collections.shuffle(lanes, new Random(mix64(seed)));
        int[] groups = new int[laneCount];
        for (int position = 0; position < laneCount; position++) {
            groups[lanes.get(position)] = position % groupCount;
        }
        return groups;
    }

    /**
     * Diagnostic upper-bound grouping: lanes are balanced across carrier
     * clusters using their cheapest available rate.  This intentionally reads
     * procurement costs and must not be used as formal evidence.
     */
    public static int[] costAwareLaneGroups(ProcurementParams params,
                                            int groupCount,
                                            long seed) {
        if (params == null || groupCount < 2 || groupCount > params.I) {
            throw new IllegalArgumentException("Invalid cost-aware grouping request.");
        }
        List<Integer> lanes = new ArrayList<>(params.J);
        for (int j = 0; j < params.J; j++) lanes.add(j);
        Collections.shuffle(lanes, new Random(mix64(seed)));
        int maximumGroupSize = (int) Math.ceil((double) params.J / groupCount);
        int[] size = new int[groupCount];
        int[] groups = new int[params.J];

        for (int lane : lanes) {
            List<Integer> candidates = new ArrayList<>(groupCount);
            for (int group = 0; group < groupCount; group++) candidates.add(group);
            candidates.sort(Comparator.comparingDouble(group -> clusterRate(params, lane, group, groupCount)));
            int selected = -1;
            for (int group : candidates) {
                if (size[group] < maximumGroupSize) {
                    selected = group;
                    break;
                }
            }
            if (selected < 0) throw new IllegalStateException("Unable to balance cost-aware lane groups.");
            groups[lane] = selected;
            size[selected]++;
        }
        return groups;
    }

    /** Generates a carrier market with a transparent home-group advantage. */
    public static ProcurementParams generate(double[] baselineDemand,
                                             int[] laneGroups,
                                             int carrierCount,
                                             long seed) {
        if (baselineDemand == null || laneGroups == null
                || baselineDemand.length != laneGroups.length || carrierCount <= 0) {
            throw new IllegalArgumentException("Invalid group-specialized procurement input.");
        }
        int I = carrierCount;
        int J = baselineDemand.length;
        int groupCount = 1 + Arrays.stream(laneGroups).max().orElseThrow();
        Random random = new Random(mix64(seed));

        int[] homeGroup = new int[I];
        for (int i = 0; i < I; i++) homeGroup[i] = i % groupCount;
        boolean[][] eligible = new boolean[I][J];
        for (int i = 0; i < I; i++) {
            for (int j = 0; j < J; j++) {
                eligible[i][j] = homeGroup[i] == laneGroups[j] || random.nextDouble() < 0.67;
            }
        }
        ensureMinimumCoverage(eligible, laneGroups, homeGroup, 3);

        double[][] rates = new double[I][J];
        double[][] capacity = new double[I][J];
        double marketCapacityRatio = 1.65;
        for (int j = 0; j < J; j++) {
            double laneBaseRate = uniform(random, 60.0, 90.0);
            double[] rawCapacity = new double[I];
            double rawSum = 0.0;
            for (int i = 0; i < I; i++) {
                if (!eligible[i][j]) continue;
                boolean home = homeGroup[i] == laneGroups[j];
                rates[i][j] = laneBaseRate
                        * (home ? uniform(random, 0.72, 0.82) : uniform(random, 0.98, 1.18));
                rawCapacity[i] = home ? uniform(random, 1.10, 1.40) : uniform(random, 0.75, 1.05);
                rawSum += rawCapacity[i];
            }
            for (int i = 0; i < I; i++) {
                if (eligible[i][j]) {
                    capacity[i][j] = marketCapacityRatio * baselineDemand[j]
                            * rawCapacity[i] / rawSum;
                }
            }
        }

        double[] mqc = new double[I];
        double[] penalty = new double[I];
        for (int i = 0; i < I; i++) {
            double totalCapacity = 0.0;
            double capacityWeightedRate = 0.0;
            for (int j = 0; j < J; j++) {
                if (!eligible[i][j]) continue;
                totalCapacity += capacity[i][j];
                capacityWeightedRate += capacity[i][j] * rates[i][j];
            }
            mqc[i] = uniform(random, 0.75, 0.90) * totalCapacity;
            penalty[i] = capacityWeightedRate / totalCapacity;
        }

        double[] spot = new double[J];
        for (int j = 0; j < J; j++) {
            List<Double> availableRates = new ArrayList<>();
            for (int i = 0; i < I; i++) if (eligible[i][j]) availableRates.add(rates[i][j]);
            Collections.sort(availableRates);
            double median = availableRates.get(availableRates.size() / 2);
            spot[j] = uniform(random, 1.70, 1.90) * median;
        }

        List<String> carriers = new ArrayList<>(I);
        for (int i = 0; i < I; i++) carriers.add("G" + homeGroup[i] + "_C" + (i + 1));
        int alpha = 1;
        int beta = Math.max(alpha, (int) Math.ceil(0.7 * I));
        return new ProcurementParams(carriers, J, spot, mqc, penalty,
                capacity, rates, eligible, alpha, beta);
    }

    private static void ensureMinimumCoverage(boolean[][] eligible,
                                              int[] laneGroups,
                                              int[] homeGroup,
                                              int minimum) {
        int I = eligible.length;
        int J = eligible[0].length;
        for (int j = 0; j < J; j++) {
            int count = 0;
            for (int i = 0; i < I; i++) if (eligible[i][j]) count++;
            if (count >= minimum) continue;
            for (int i = 0; i < I && count < minimum; i++) {
                if (!eligible[i][j] && homeGroup[i] == laneGroups[j]) {
                    eligible[i][j] = true;
                    count++;
                }
            }
            for (int i = 0; i < I && count < minimum; i++) {
                if (!eligible[i][j]) {
                    eligible[i][j] = true;
                    count++;
                }
            }
        }
    }

    private static double clusterRate(ProcurementParams params,
                                      int lane,
                                      int group,
                                      int groupCount) {
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < params.I; i++) {
            if (i % groupCount == group && params.eligible[i][lane]) {
                best = Math.min(best, params.r[i][lane]);
            }
        }
        return best;
    }

    private static double uniform(Random random, double lower, double upper) {
        return lower + (upper - lower) * random.nextDouble();
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}
