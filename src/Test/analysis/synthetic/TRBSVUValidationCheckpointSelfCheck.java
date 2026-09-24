package Test.analysis.synthetic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Fast human-readable checkpoint round-trip gate; does not solve a model. */
public final class TRBSVUValidationCheckpointSelfCheck {
    private TRBSVUValidationCheckpointSelfCheck() { }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("trb_validation_checkpoint_");
        try {
            TRBSVUValidationCheckpoint checkpoint = new TRBSVUValidationCheckpoint(
                    directory, "instance-hash", "protocol-hash");
            TRBSVUValidationTrace expected = new TRBSVUValidationTrace(
                    "CSAA-Epa", 0.5, 73, 3, 72, 0.5,
                    70, 18, 7.25, 123.5, "Optimal", 123.5,
                    0.0, 1.75, 1.25, true, new double[]{1.0, 0.0, 1.0}, 130.25);
            checkpoint.save(expected);
            TRBSVUValidationTrace actual = checkpoint.load("CSAA-Epa", 0.5, 73)
                    .orElseThrow(() -> new AssertionError("Checkpoint was not restored."));
            if (!expected.method().equals(actual.method())
                || Double.doubleToLongBits(expected.candidateParameter())
                        != Double.doubleToLongBits(actual.candidateParameter())
                || expected.origin() != actual.origin()
                || expected.trainingStart() != actual.trainingStart()
                || expected.trainingEnd() != actual.trainingEnd()
                || Double.doubleToLongBits(expected.effectiveContextBandwidth())
                        != Double.doubleToLongBits(actual.effectiveContextBandwidth())
                || expected.scenarioCount() != actual.scenarioCount()
                || expected.positiveWeightCount() != actual.positiveWeightCount()
                || Double.doubleToLongBits(expected.effectiveSampleSize())
                        != Double.doubleToLongBits(actual.effectiveSampleSize())
                || Double.doubleToLongBits(expected.trainingObjective())
                        != Double.doubleToLongBits(actual.trainingObjective())
                || !expected.solverStatus().equals(actual.solverStatus())
                || Double.doubleToLongBits(expected.bestBound())
                        != Double.doubleToLongBits(actual.bestBound())
                || Double.doubleToLongBits(expected.relativeGap())
                        != Double.doubleToLongBits(actual.relativeGap())
                || Double.doubleToLongBits(expected.solveTimeSec())
                        != Double.doubleToLongBits(actual.solveTimeSec())
                || Double.doubleToLongBits(expected.optimizerTimeSec())
                        != Double.doubleToLongBits(actual.optimizerTimeSec())
                || expected.certifiedOptimal() != actual.certifiedOptimal()
                || !Arrays.equals(expected.decision(), actual.decision())
                || Double.doubleToLongBits(expected.realizedValidationCost())
                        != Double.doubleToLongBits(actual.realizedValidationCost()))
                throw new AssertionError("Checkpoint round trip changed data.");
            if (checkpoint.load("CSAA-Epa", 1.0, 73).isPresent())
                throw new AssertionError("Different candidate unexpectedly reused a checkpoint.");
            if (new TRBSVUValidationCheckpoint(directory, "different-instance", "protocol-hash")
                    .load("CSAA-Epa", 0.5, 73).isPresent())
                throw new AssertionError("Different instance fingerprint was reused.");
            TRBSVUValidationCheckpoint updated = new TRBSVUValidationCheckpoint(
                    directory, "instance-hash", "different-protocol");
            if (updated.load("CSAA-Epa", 0.5, 73).isPresent())
                throw new AssertionError("Different protocol fingerprint was reused.");
            updated.save(expected);
            if (updated.load("CSAA-Epa", 0.5, 73).isEmpty())
                throw new AssertionError("Stale checkpoint was not replaced by the new protocol.");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
        System.out.println("TRBSVUValidationCheckpointSelfCheck PASS");
    }
}
