package Test.analysis.synthetic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Opt-in, method-specific stop receipt shared by the new moment batch workers. */
final class TRBSVUMomentBatchStop {
    private TRBSVUMomentBatchStop() { }

    static Path marker(String method) {
        String root = System.getProperty("trb.svu.momentStopDirectory", "");
        return root.isBlank() ? null : Path.of(root).resolve(method + ".stop.txt");
    }

    static boolean stopped(String method) {
        Path file = marker(method);
        return file != null && Files.isRegularFile(file);
    }

    static void requireRunning(String method) {
        if (stopped(method)) throw new IllegalStateException("Moment batch stopped: " + marker(method));
    }

    static void stop(String method, String reason) throws Exception {
        Path file = marker(method);
        if (file == null) return;
        Files.createDirectories(file.getParent());
        try {
            Files.writeString(file, "time=" + java.time.Instant.now() + "\nmethod=" + method
                    + "\nreason=" + reason + "\n", StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException alreadyRecorded) {
            // Preserve the first cause; other in-flight workers finish their current solve.
        }
    }
}
