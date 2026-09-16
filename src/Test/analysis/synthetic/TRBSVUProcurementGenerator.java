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
        boolean[][] eligible = new boolean[carrierCount][lanes];
        for (int i = 0; i < carrierCount; i++) {
            int count = Math.max(1, (int) Math.round(lanes * uniform(random, 0.4, 0.7)));
            List<Integer> shuffled = new ArrayList<>(lanes);
            for (int j = 0; j < lanes; j++) shuffled.add(j);
            Collections.shuffle(shuffled, random);
            for (int k = 0; k < count; k++) eligible[i][shuffled.get(k)] = true;
        }
        for (int j = 0; j < lanes; j++) {
            List<Integer> missing = new ArrayList<>();
            int covered = 0;
            for (int i = 0; i < carrierCount; i++) {
                if (eligible[i][j]) covered++;
                else missing.add(i);
            }
            Collections.shuffle(missing, random);
            for (int k = 0; covered < 6; k++, covered++) {
                eligible[missing.get(k)][j] = true;
            }
        }

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
