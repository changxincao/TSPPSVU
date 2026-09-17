package Test.analysis.synthetic;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Atomic, human-readable checkpoint for one method/candidate/validation-origin solve. */
public final class TRBSVUValidationCheckpoint {
    private static final String FORMAT = "TRBSVU_VALIDATION_CHECKPOINT_V2";
    private final Path directory;
    private final String instanceSha256;
    private final String protocolFingerprint;

    public TRBSVUValidationCheckpoint(Path directory, String instanceSha256,
                                      String protocolFingerprint) throws Exception {
        if (instanceSha256 == null || instanceSha256.isBlank()
                || protocolFingerprint == null || protocolFingerprint.isBlank())
            throw new IllegalArgumentException("Checkpoint fingerprints are required.");
        this.directory = directory;
        this.instanceSha256 = instanceSha256;
        this.protocolFingerprint = protocolFingerprint;
        Files.createDirectories(directory);
    }

    public Optional<TRBSVUValidationTrace> load(String method, double candidate, int origin)
            throws Exception {
        Path file = file(method, candidate, origin);
        if (!Files.exists(file)) return Optional.empty();
        Map<String, String> values = read(file);
        try {
            if (!FORMAT.equals(values.get("format")))
                throw new IllegalStateException("Unknown validation checkpoint: " + file);
            if (!instanceSha256.equals(required(values, "instanceSha256")))
                return Optional.empty();
            if (!protocolFingerprint.equals(required(values, "protocolFingerprint")))
                return Optional.empty();
            String storedMethod = required(values, "method");
            double storedCandidate = Double.parseDouble(required(values, "candidate"));
            int storedOrigin = Integer.parseInt(required(values, "origin"));
            if (!storedMethod.equals(method)
                    || Double.doubleToLongBits(storedCandidate) != Double.doubleToLongBits(candidate)
                    || storedOrigin != origin)
                throw new IllegalStateException("Validation checkpoint key mismatch: " + file);
            int trainingStart = integer(values, "trainingStart");
            int trainingEnd = integer(values, "trainingEnd");
            double effectiveB = number(values, "effectiveContextBandwidth");
            int scenarios = integer(values, "scenarioCount");
            int positive = integer(values, "positiveWeightCount");
            double ess = number(values, "effectiveSampleSize");
            double objective = number(values, "trainingObjective");
            String status = required(values, "solverStatus");
            double bound = number(values, "bestBound");
            double gap = number(values, "relativeGap");
            double seconds = number(values, "solveTimeSec");
            String certifiedText = required(values, "certifiedOptimal");
            if (!"true".equals(certifiedText) && !"false".equals(certifiedText))
                throw new IllegalStateException("Invalid certifiedOptimal value: " + certifiedText);
            boolean certified = "true".equals(certifiedText);
            double[] decision = parseDecision(required(values, "decision"));
            double realized = number(values, "realizedValidationCost");
            return Optional.of(new TRBSVUValidationTrace(storedMethod, storedCandidate,
                    storedOrigin, trainingStart, trainingEnd, effectiveB, scenarios, positive,
                    ess, objective, status, bound, gap, seconds, certified, decision, realized));
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Invalid validation checkpoint: " + file, ex);
        }
    }

    public void save(TRBSVUValidationTrace trace) throws Exception {
        Path target = file(trace.method(), trace.candidateParameter(), trace.origin());
        Path temporary = Files.createTempFile(directory, "pending_", ".checkpoint");
        try {
            try (BufferedWriter out = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                write(out, "format", FORMAT);
                write(out, "instanceSha256", instanceSha256);
                write(out, "protocolFingerprint", protocolFingerprint);
                write(out, "method", trace.method());
                write(out, "candidate", Double.toString(trace.candidateParameter()));
                write(out, "origin", trace.origin());
                write(out, "trainingStart", trace.trainingStart());
                write(out, "trainingEnd", trace.trainingEnd());
                write(out, "effectiveContextBandwidth", trace.effectiveContextBandwidth());
                write(out, "scenarioCount", trace.scenarioCount());
                write(out, "positiveWeightCount", trace.positiveWeightCount());
                write(out, "effectiveSampleSize", trace.effectiveSampleSize());
                write(out, "trainingObjective", trace.trainingObjective());
                write(out, "solverStatus", trace.solverStatus());
                write(out, "bestBound", trace.bestBound());
                write(out, "relativeGap", trace.relativeGap());
                write(out, "solveTimeSec", trace.solveTimeSec());
                write(out, "certifiedOptimal", trace.certifiedOptimal());
                write(out, "decision", decision(trace.decision()));
                write(out, "realizedValidationCost", trace.realizedValidationCost());
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path file(String method, double candidate, int origin) {
        String safe = method.replaceAll("[^A-Za-z0-9_-]", "_");
        return directory.resolve(safe + "_p" + Long.toUnsignedString(
                Double.doubleToLongBits(candidate), 16) + "_o" + origin + ".checkpoint");
    }

    private static Map<String, String> read(Path file) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                int separator = line.indexOf('=');
                if (separator <= 0) throw new IllegalStateException("Invalid checkpoint line: " + line);
                String key = line.substring(0, separator);
                String value = line.substring(separator + 1);
                if (values.put(key, value) != null)
                    throw new IllegalStateException("Duplicate checkpoint key: " + key);
            }
        }
        return values;
    }

    private static void write(BufferedWriter out, String key, Object value) throws Exception {
        String text = String.valueOf(value);
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Checkpoint value contains a line break: " + key);
        out.write(key);
        out.write('=');
        out.write(text);
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
        StringBuilder result = new StringBuilder();
        for (int k = 0; k < values.length; k++) {
            if (k > 0) result.append(',');
            result.append(Double.toString(values[k]));
        }
        return result.toString();
    }

    private static double[] parseDecision(String text) {
        if ("NA".equals(text)) return null;
        if (text.isEmpty()) return new double[0];
        String[] fields = text.split(",", -1);
        double[] values = new double[fields.length];
        for (int k = 0; k < fields.length; k++) values[k] = Double.parseDouble(fields[k]);
        return values;
    }
}
