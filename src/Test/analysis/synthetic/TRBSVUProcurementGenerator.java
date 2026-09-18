package Test.analysis.synthetic;

import Basic.ProcurementParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Fixed procurement market for one synthetic replication in Experiments 1--2. */
public final class TRBSVUProcurementGenerator {
    private TRBSVUProcurementGenerator() {
    }

    public static ProcurementParams generate(int carrierCount, double[] typicalDemand, long seed) {
        if (carrierCount < 6 || typicalDemand == null || typicalDemand.length == 0) {
            throw new IllegalArgumentException("At least six carriers and one lane are required.");
        }
        int lanes = typicalDemand.length;
        for (double value : typicalDemand) {
            if (!(value > 0.0) || !Double.isFinite(value)) {
                throw new IllegalArgumentException("Typical lane demand must be finite and positive.");
            }
        }
        Random random = new Random(seed);
        boolean[][] eligible = randomEligibility(carrierCount, typicalDemand.length, random);
        return buildMarket(typicalDemand, random, eligible);
    }

    public static ProcurementParams generateWithHomeGroupCoverage(
            int carrierCount, double[] typicalDemand, long seed,
            int[] laneGroup, int groupCount, double homeCoverage) {
        if (carrierCount < 6 || typicalDemand == null || typicalDemand.length == 0
                || laneGroup == null || laneGroup.length != typicalDemand.length) {
            throw new IllegalArgumentException(
                    "Carriers, positive lane demands and one group per lane are required.");
        }
        if (groupCount <= 1 || !(homeCoverage > 0.5 && homeCoverage <= 1.0)) {
            throw new IllegalArgumentException(
                    "At least two groups and home coverage above 0.5 are required.");
        }
        for (int j = 0; j < typicalDemand.length; j++) {
            if (!(typicalDemand[j] > 0.0) || !Double.isFinite(typicalDemand[j])
                    || laneGroup[j] < 0 || laneGroup[j] >= groupCount) {
                throw new IllegalArgumentException("Invalid demand or lane group.");
            }
        }
        Random random = new Random(seed);
        boolean[][] eligible = homeGroupEligibility(
                carrierCount, typicalDemand.length, laneGroup, groupCount,
                homeCoverage, random);
        return buildMarket(typicalDemand, random, eligible);
    }

    private static boolean[][] randomEligibility(int carrierCount, int lanes, Random random) {
        boolean[][] eligible = new boolean[carrierCount][lanes];
        int lanesPerCarrier = Math.max(1, (int) Math.round(0.50 * lanes));
        int[] laneCoverage = new int[lanes];
        for (int i = 0; i < carrierCount; i++) {
            List<Integer> shuffled = new ArrayList<>(lanes);
            for (int j = 0; j < lanes; j++) shuffled.add(j);
            Collections.shuffle(shuffled, random);
            for (int k = 0; k < lanesPerCarrier; k++) {
                int j = shuffled.get(k);
                eligible[i][j] = true;
                laneCoverage[j]++;
            }
        }
        repairUncoveredLanes(eligible, laneCoverage, random);
        return eligible;
    }

    private static boolean[][] homeGroupEligibility(
            int carrierCount, int lanes, int[] laneGroup, int groupCount,
            double homeCoverage, Random random) {
        int lanesPerCarrier = Math.max(1, (int) Math.round(0.50 * lanes));
        List<Integer> homeGroups = new ArrayList<>(carrierCount);
        for (int i = 0; i < carrierCount; i++) homeGroups.add(i % groupCount);
        Collections.shuffle(homeGroups, random);
        boolean[][] eligible = new boolean[carrierCount][lanes];
        int[] laneCoverage = new int[lanes];
        for (int i = 0; i < carrierCount; i++) {
            int home = homeGroups.get(i);
            List<Integer> homeLanes = new ArrayList<>();
            List<Integer> otherLanes = new ArrayList<>();
            for (int j = 0; j < lanes; j++) {
                (laneGroup[j] == home ? homeLanes : otherLanes).add(j);
            }
            Collections.shuffle(homeLanes, random);
            Collections.shuffle(otherLanes, random);
            int homeCount = Math.min(homeLanes.size(),
                    (int) Math.round(homeCoverage * homeLanes.size()));
            homeCount = Math.max(0, Math.min(homeCount, lanesPerCarrier));
            int otherCount = lanesPerCarrier - homeCount;
            if (otherCount > otherLanes.size()) {
                otherCount = otherLanes.size();
                homeCount = lanesPerCarrier - otherCount;
            }
            for (int k = 0; k < homeCount; k++) {
                int j = homeLanes.get(k);
                eligible[i][j] = true;
                laneCoverage[j]++;
            }
            for (int k = 0; k < otherCount; k++) {
                int j = otherLanes.get(k);
                eligible[i][j] = true;
                laneCoverage[j]++;
            }
        }
        repairUncoveredLanes(eligible, laneCoverage, random);
        return eligible;
    }

    private static void repairUncoveredLanes(
            boolean[][] eligible, int[] laneCoverage, Random random) {
        int carrierCount = eligible.length;
        int lanes = laneCoverage.length;
        // Preserve exactly 50% coverage per carrier while ensuring that every lane
        // has at least one eligible carrier.
        for (int j = 0; j < lanes; j++) {
            if (laneCoverage[j] > 0) continue;
            List<Integer> carriers = new ArrayList<>(carrierCount);
            for (int i = 0; i < carrierCount; i++) carriers.add(i);
            Collections.shuffle(carriers, random);
            boolean repaired = false;
            for (int i : carriers) {
                List<Integer> donors = new ArrayList<>();
                for (int k = 0; k < lanes; k++)
                    if (eligible[i][k] && laneCoverage[k] > 1) donors.add(k);
                if (donors.isEmpty()) continue;
                int donor = donors.get(random.nextInt(donors.size()));
                eligible[i][donor] = false;
                eligible[i][j] = true;
                laneCoverage[donor]--;
                laneCoverage[j]++;
                repaired = true;
                break;
            }
            if (!repaired) throw new IllegalStateException("Cannot construct 50% lane coverage.");
        }
    }

    private static ProcurementParams buildMarket(
            double[] typicalDemand, Random random, boolean[][] eligible) {
        int carrierCount = eligible.length;
        int lanes = typicalDemand.length;
        double[][] rates = new double[carrierCount][lanes];
        double[][] capacities = new double[carrierCount][lanes];
        double[] spot = new double[lanes];
        for (int j = 0; j < lanes; j++) {
            double laneRate = uniform(random, 20.0, 100.0);
            double spread = uniform(random, 0.05, 0.30);
            List<Integer> carriers = new ArrayList<>();
            for (int i = 0; i < carrierCount; i++) {
                if (eligible[i][j]) carriers.add(i);
            }
            Collections.shuffle(carriers, random);
            double rateSum = 0.0;
            for (int k = 0; k < carriers.size(); k++) {
                int i = carriers.get(k);
                double multiplier = new double[] {0.5, 1.0, 1.5}[3 * k / carriers.size()];
                rates[i][j] = laneRate * multiplier * uniform(random, 1.0 - spread, 1.0 + spread);
                capacities[i][j] = typicalDemand[j] * uniform(random, 0.3, 0.5);
                rateSum += rates[i][j];
            }
            spot[j] = uniform(random, 1.5, 2.5) * rateSum / carriers.size();
        }

        double[] mqc = new double[carrierCount];
        double[] penalty = new double[carrierCount];
        for (int i = 0; i < carrierCount; i++) {
            double eligibleDemand = 0.0;
            double minimumRate = Double.POSITIVE_INFINITY;
            for (int j = 0; j < lanes; j++) {
                if (!eligible[i][j]) continue;
                eligibleDemand += typicalDemand[j];
                minimumRate = Math.min(minimumRate, rates[i][j]);
            }
            mqc[i] = eligibleDemand * uniform(random, 0.1, 0.2);
            penalty[i] = minimumRate;
        }
        List<String> carrierNames = new ArrayList<>(carrierCount);
        for (int i = 0; i < carrierCount; i++) carrierNames.add("C" + (i + 1));
        return new ProcurementParams(carrierNames, lanes, spot, mqc, penalty,
                capacities, rates, eligible,
                (int) Math.ceil(0.1 * carrierCount),
                (int) Math.ceil(0.7 * carrierCount));
    }

    private static double uniform(Random random, double lower, double upper) {
        return lower + (upper - lower) * random.nextDouble();
    }
}
