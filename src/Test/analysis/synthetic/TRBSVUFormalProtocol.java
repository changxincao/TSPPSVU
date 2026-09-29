package Test.analysis.synthetic;

import java.util.HashSet;
import java.util.Set;
import java.util.SplittableRandom;

/** Frozen dimensions and validation split for the formal synthetic experiments. */
final class TRBSVUFormalProtocol {
    static final String EXPERIMENT12_VERSION = "TRBSVU_EXP12_V9";
    static final int CARRIERS = 15;
    static final int LANES = 50;
    static final int HISTORY_PERIODS = 75;
    static final int OOS_DRAWS = 1000;
    static final int VALIDATION_TRAINING_PERIODS = 50;
    static final int VALIDATION_ORIGINS = HISTORY_PERIODS - VALIDATION_TRAINING_PERIODS;
    static final int BASELINE_REPLICATIONS = 10;
    static final int RANDOM_QUERIES = 40;
    static final int HIGH_R_QUERIES = 0;

    /** Returns the {@code replication}-th unique case seed in [1, 999999]. */
    static long caseSeed(long batchSeed, int replication) {
        if (replication < 0) throw new IllegalArgumentException("Negative replication index.");
        SplittableRandom random = new SplittableRandom(batchSeed);
        Set<Long> used = new HashSet<>();
        int accepted = -1;
        long candidate;
        do {
            candidate = random.nextLong(1L, 1_000_000L);
            if (used.add(candidate)) accepted++;
        } while (accepted < replication);
        return candidate;
    }

    static TRBSVUSyntheticCase.Seeds seeds(long batchSeed, int replication) {
        SplittableRandom random = new SplittableRandom(caseSeed(batchSeed, replication));
        return new TRBSVUSyntheticCase.Seeds(
                random.nextLong(), random.nextLong(), random.nextLong(),
                random.nextLong(), random.nextLong());
    }

    private TRBSVUFormalProtocol() { }
}
