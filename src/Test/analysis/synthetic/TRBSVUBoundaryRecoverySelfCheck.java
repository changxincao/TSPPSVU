package Test.analysis.synthetic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Isolated regression gate: no solver invocations and no experiment inputs modified. */
public final class TRBSVUBoundaryRecoverySelfCheck {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--hold-lock")) {
            try (var lock = TRBSVUWorkerLock.acquire(Path.of(args[1]))) {
                System.out.println("LOCK_HELD");
                System.out.flush();
                System.in.read();
            }
            return;
        }
        Path output = Files.createTempDirectory("trb_boundary_gate_");
        try {
            try (var lock = TRBSVUWorkerLock.acquire(output)) {
                boolean rejected = false;
                try (var duplicate = TRBSVUWorkerLock.acquire(output)) { }
                catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "Duplicate worker accepted");
            }
            Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                    "-cp", System.getProperty("java.class.path"),
                    TRBSVUBoundaryRecoverySelfCheck.class.getName(), "--hold-lock", output.toString()).start();
            try {
                require("LOCK_HELD".equals(child.inputReader().readLine()), "Child failed to acquire lock");
                boolean rejected = false;
                try (var duplicate = TRBSVUWorkerLock.acquire(output)) { }
                catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "Cross-JVM duplicate accepted");
            } finally { child.destroyForcibly(); child.waitFor(); }
            try (var released = TRBSVUWorkerLock.acquire(output)) { }

            Path query = output.resolve("queries/query_000");
            Files.createDirectories(query.resolve("oos"));
            Path draws = query.resolve("oos/draws.csv");
            String header = "method,solve_status,draw_index,total_demand,total_cost\n";
            Files.writeString(draws, header);
            require(!TRBSVUCompletionMarker.oosDrawsComplete(draws), "Header-only OOS accepted");
            StringBuilder rows = new StringBuilder(header);
            for (int index = 0; index < 1000; index++)
                rows.append("C-MM,\"Feasible, time limit\",").append(index).append(",10,20\n");
            Files.writeString(draws, rows);
            require(TRBSVUCompletionMarker.oosDrawsComplete(draws), "Valid quoted CSV rejected");
            Path marker = output.resolve("complete.txt");
            TRBSVUCompletionMarker.writeAtomically(marker, "protocol=gate\n");
            require(TRBSVUCompletionMarker.matches(marker, "protocol=gate"), "Valid integrity seal rejected");
            Files.writeString(draws, rows.toString().replace(",10,20", ",10,21"));
            require(!TRBSVUCompletionMarker.matches(marker, "protocol=gate"), "Same-length corruption reused");
            Files.writeString(draws, rows.append("C-MM,Feasible,999,10,20\n"));
            require(!TRBSVUCompletionMarker.oosDrawsComplete(draws), "Duplicate OOS index accepted");
            Files.writeString(draws, header);
            require(!TRBSVUCompletionMarker.queryArtifactsComplete(output, List.of(0), "oos/draws.csv"),
                    "Header-only artifact treated as complete");

            TRBSVUValidationCheckpoint checkpoint = new TRBSVUValidationCheckpoint(
                    output.resolve("checkpoint"), "instance", "protocol");
            TRBSVUValidationTrace rejected = new TRBSVUValidationTrace("C-MM", 1, 50, 0, 49,
                    1, 50, 50, 50, 10, "Feasible", 9, .1, 12, 10, false, new double[]{1}, Double.NaN);
            checkpoint.save(rejected);
            var restored = checkpoint.load("C-MM", 1, 50).orElseThrow();
            require(!restored.certifiedOptimal() && restored.bestBound() == 9
                    && restored.relativeGap() == .1 && Double.isNaN(restored.realizedValidationCost()),
                    "Rejected moment diagnostics lost");
            String previous = System.getProperty("trb.svu.python");
            try {
                System.setProperty("trb.svu.python", output.resolve("custom-python.exe").toString());
                require(TRBSVUExperiment2Runner.momentPython().equals(output.resolve("custom-python.exe")),
                        "Moment Python property ignored");
            } finally {
                if (previous == null) System.clearProperty("trb.svu.python");
                else System.setProperty("trb.svu.python", previous);
            }
        } finally {
            try (var paths = Files.walk(output)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("TRBSVUBoundaryRecoverySelfCheck PASS: OOS integrity, locks, rejected diagnostics, Python path");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
