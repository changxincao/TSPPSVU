package Test.analysis.synthetic;

import java.util.HashSet;
import java.util.Set;

/** Deterministic range and uniqueness checks for formal case seeds. */
public final class TRBSVUCaseSeedSelfCheck {
    private TRBSVUCaseSeedSelfCheck() { }

    public static void main(String[] args) {
        long batchSeed = 20261020L;
        Set<Long> observed = new HashSet<>();
        for (int replication = 0; replication < 1_000; replication++) {
            long first = TRBSVUFormalProtocol.caseSeed(batchSeed, replication);
            long repeated = TRBSVUFormalProtocol.caseSeed(batchSeed, replication);
            require(first == repeated, "Case seed is not reproducible.");
            require(first >= 1L && first <= 999_999L, "Case seed is outside six digits.");
            require(observed.add(first), "Duplicate case seed: " + first);
        }
        require(TRBSVUFormalProtocol.caseSeed(batchSeed, 1)
                        != TRBSVUFormalProtocol.caseSeed(batchSeed, 0) + 1L,
                "Case seeds unexpectedly follow the old consecutive rule.");
        System.out.println("TRBSVUCaseSeedSelfCheck PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
