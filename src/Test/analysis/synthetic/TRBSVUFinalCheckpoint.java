package Test.analysis.synthetic;

import Model.Solution;
import Test.analysis.synthetic.TRBSVUSolveMethods.Oos;
import Test.analysis.synthetic.TRBSVUSolveMethods.OosDraw;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Atomic, human-readable recovery files for one completed final method solve and OOS evaluation. */
public final class TRBSVUFinalCheckpoint {
    private static final String FORMAT = "TRBSVU_FINAL_CHECKPOINT_V3";
    private final Path solveDirectory;
    private final Path oosDirectory;
    private final String instanceSha256;
    private final String protocolFingerprint;
    private final int replication;
    private final String experiment;

    public TRBSVUFinalCheckpoint(Path solveDirectory, Path oosDirectory,
                                 String instanceSha256, String protocolFingerprint,
                                 int replication, String experiment) throws Exception {
        this.solveDirectory = solveDirectory;
        this.oosDirectory = oosDirectory;
        this.instanceSha256 = instanceSha256;
        this.protocolFingerprint = protocolFingerprint;
        this.replication = replication;
        this.experiment = experiment;
        Files.createDirectories(solveDirectory);
        Files.createDirectories(oosDirectory);
    }

    public Optional<Solution> load(String method, double parameter) throws Exception {
        Path file = solveFile(method);
        if (!Files.exists(file)) return Optional.empty();
        Map<String, String> values = read(file);
        if (!FORMAT.equals(values.get("format")))
            throw new IllegalStateException("Final checkpoint metadata mismatch: " + file);
        if (!instanceSha256.equals(required(values, "instanceSha256"))
                || !protocolFingerprint.equals(required(values, "protocolFingerprint"))
                || replication != integer(values, "replication")
                || !experiment.equals(required(values, "experiment"))
                || !method.equals(required(values, "method"))
                || Double.doubleToLongBits(parameter) != Double.doubleToLongBits(
                        Double.parseDouble(required(values, "selectedParameter"))))
            return Optional.empty();
        Solution solution = new Solution();
        solution.objValue = number(values, "objective");
        solution.y = decision(required(values, "decision"));
        solution.solveTimeSec = number(values, "solveTimeSec");
        solution.optimizerTimeSec = number(values, "optimizerTimeSec");
        solution.solverStatus = required(values, "solverStatus");
        solution.bestBound = number(values, "bestBound");
        solution.relativeGap = number(values, "relativeGap");
        solution.nodeCount = Long.parseLong(required(values, "nodeCount"));
        solution.iterationCount = integer(values, "iterationCount");
        solution.cutCount = integer(values, "cutCount");
        solution.candidateCount = Long.parseLong(required(values, "candidateCount"));
        String certified = required(values, "certifiedOptimal");
        if (!"true".equals(certified) && !"false".equals(certified))
            throw new IllegalStateException("Invalid certifiedOptimal in " + file);
        solution.certifiedOptimal = Boolean.parseBoolean(certified);
        solution.wassersteinRadius = number(values, "wassersteinRadius");
        solution.wassersteinEta = number(values, "wassersteinEta");
        solution.wassersteinInitialPointCount = integer(values, "wassersteinInitialPointCount");
        solution.wassersteinGeneratedCutCount = integer(values, "wassersteinGeneratedCutCount");
        solution.wassersteinTotalPointCount = integer(values, "wassersteinTotalPointCount");
        solution.wassersteinBoxUpper = decision(required(values, "wassersteinBoxUpper"));
        solution.wassersteinDistanceScale = decision(required(values, "wassersteinDistanceScale"));
        solution.modelVariableCount = integer(values, "modelVariableCount");
        solution.modelConstraintCount = integer(values, "modelConstraintCount");
        solution.modelConeCount = integer(values, "modelConeCount");
        return Optional.of(solution);
    }

    public void save(String method, double parameter, Solution solution) throws Exception {
        Path target = solveFile(method);
        Path temporary = Files.createTempFile(solveDirectory, "pending_", ".checkpoint");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                write(out, "format", FORMAT);
                write(out, "instanceSha256", instanceSha256);
                write(out, "protocolFingerprint", protocolFingerprint);
                write(out, "replication", replication);
                write(out, "experiment", experiment);
                write(out, "method", method);
                write(out, "selectedParameter", parameter);
                write(out, "objective", solution.objValue);
                write(out, "solveTimeSec", solution.solveTimeSec);
                write(out, "optimizerTimeSec", solution.optimizerTimeSec);
                write(out, "solverStatus", solution.solverStatus);
                write(out, "bestBound", solution.bestBound);
                write(out, "relativeGap", solution.relativeGap);
                write(out, "nodeCount", solution.nodeCount);
                write(out, "iterationCount", solution.iterationCount);
                write(out, "cutCount", solution.cutCount);
                write(out, "candidateCount", solution.candidateCount);
                write(out, "certifiedOptimal", solution.certifiedOptimal);
                write(out, "wassersteinRadius", solution.wassersteinRadius);
                write(out, "wassersteinEta", solution.wassersteinEta);
                write(out, "wassersteinInitialPointCount", solution.wassersteinInitialPointCount);
                write(out, "wassersteinGeneratedCutCount", solution.wassersteinGeneratedCutCount);
                write(out, "wassersteinTotalPointCount", solution.wassersteinTotalPointCount);
                write(out, "wassersteinBoxUpper", decision(solution.wassersteinBoxUpper));
                write(out, "wassersteinDistanceScale", decision(solution.wassersteinDistanceScale));
                write(out, "modelVariableCount", solution.modelVariableCount);
                write(out, "modelConstraintCount", solution.modelConstraintCount);
                write(out, "modelConeCount", solution.modelConeCount);
                write(out, "decision", decision(solution.y));
            }
            replace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void saveOos(String method, Oos summary, List<OosDraw> draws) throws Exception {
        saveOos(method, summary, draws, null);
    }

    public void saveOos(String method, Oos summary, List<OosDraw> draws,
                        Solution solution) throws Exception {
        String safe = safe(method);
        Path summaryTarget = oosDirectory.resolve(safe + "_summary.csv");
        Path drawsTarget = oosDirectory.resolve(safe + "_draws.csv");
        Path summaryTemporary = Files.createTempFile(oosDirectory, "pending_summary_", ".csv");
        Path drawsTemporary = Files.createTempFile(oosDirectory, "pending_draws_", ".csv");
        try {
            Map<String, Solution> solveMetadata = solution == null
                    ? Map.of() : Map.of(method, solution);
            TRBSVUResultWriter.writeOosSummary(summaryTemporary, replication, experiment,
                    Map.of(method, summary), solveMetadata);
            TRBSVUResultWriter.writeOosDetails(drawsTemporary, replication, experiment,
                    Map.of(method, draws), solveMetadata);
            replace(summaryTemporary, summaryTarget);
            replace(drawsTemporary, drawsTarget);
        } finally {
            Files.deleteIfExists(summaryTemporary);
            Files.deleteIfExists(drawsTemporary);
        }
    }

    private Path solveFile(String method) {
        return solveDirectory.resolve(safe(method) + ".checkpoint");
    }

    private static String safe(String method) {
        return method.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static void replace(Path source, Path target) throws Exception {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, String> read(Path file) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                int separator = line.indexOf('=');
                if (separator <= 0) throw new IllegalStateException("Invalid checkpoint line: " + line);
                if (values.put(line.substring(0, separator), line.substring(separator + 1)) != null)
                    throw new IllegalStateException("Duplicate checkpoint key: " + line);
            }
        }
        return values;
    }

    private static void write(BufferedWriter out, String key, Object value) throws Exception {
        out.write(key + "=" + value);
        out.newLine();
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) throw new IllegalStateException("Missing checkpoint key: " + key);
        return value;
    }

    private static int integer(Map<String, String> values, String key) {
        return Integer.parseInt(required(values, key));
    }

    private static double number(Map<String, String> values, String key) {
        return Double.parseDouble(required(values, key));
    }

    private static String decision(double[] values) {
        if (values == null) return "NA";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) text.append(',');
            text.append(values[i]);
        }
        return text.toString();
    }

    private static double[] decision(String text) {
        if ("NA".equals(text)) return null;
        if (text.isEmpty()) return new double[0];
        String[] fields = text.split(",", -1);
        double[] values = new double[fields.length];
        for (int i = 0; i < fields.length; i++) values[i] = Double.parseDouble(fields[i]);
        return values;
    }
}
