package Test.analysis.synthetic;

import java.nio.file.Files;
import java.nio.file.Path;

/** Native-solver-free regression checks for best-effort RF temporary cleanup. */
public final class TRBSVUForestCleanupSelfCheck {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("rf-cleanup-check-");
        Path blocked = Files.createDirectory(root.resolve("nonempty"));
        Path sentinel = Files.writeString(blocked.resolve("sentinel"), "keep");
        try {
            Path normal = Files.createDirectory(root.resolve("normal"));
            Path input = Files.writeString(normal.resolve("input"), "data");
            TRBSVUForestWeights.cleanupTemporaryFiles(input, normal);
            require(!Files.exists(normal), "Normal cleanup failed");
            Path next = Files.writeString(root.resolve("next"), "data");
            TRBSVUForestWeights.cleanupTemporaryFiles(blocked, next);
            require(Files.exists(sentinel), "Cleanup must not recursively remove retained data");
            require(!Files.exists(next), "Failure must not prevent later cleanup");
            require(validReturn(blocked) == 42, "Cleanup discarded valid result");
            IllegalStateException original = new IllegalStateException("original-fit-error");
            try {
                try { throw original; }
                finally { TRBSVUForestWeights.cleanupTemporaryFiles(blocked); }
            } catch (IllegalStateException actual) {
                require(actual == original, "Cleanup masked original error");
            }
            Thread.currentThread().interrupt();
            TRBSVUForestWeights.cleanupTemporaryFiles(blocked);
            require(Thread.currentThread().isInterrupted(), "Interrupt flag lost");
            Thread.interrupted();
            System.out.println("PASS RF cleanup: normal removal, retained directory, continuation, "
                    + "valid return, original exception, interrupt preservation");
        } finally {
            Thread.interrupted();
            Files.deleteIfExists(sentinel);
            Files.deleteIfExists(blocked);
            Files.deleteIfExists(root);
        }
    }

    private static int validReturn(Path blocked) {
        try { return 42; }
        finally { TRBSVUForestWeights.cleanupTemporaryFiles(blocked); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
