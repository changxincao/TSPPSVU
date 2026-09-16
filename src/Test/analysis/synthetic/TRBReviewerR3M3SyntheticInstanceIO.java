package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.InnovationDistribution;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Reviewer 3, Comment 3: persistent, inspectable synthetic experiment instance.
 *
 * <p>Input: one DGP {@link Settings}, its generated {@link ReplicationData}, and
 * optionally the concrete {@link ProcurementParams}. Operation: write/read only
 * deterministic UTF-8 properties and CSV files. Output: a complete in-memory
 * instance with the same double values and scenario metadata. This class never
 * invokes an optimization solver.</p>
 */
public final class TRBReviewerR3M3SyntheticInstanceIO {
    private static final String FORMAT_VERSION = "1";
    private static final String MANIFEST = "manifest.properties";

    private TRBReviewerR3M3SyntheticInstanceIO() {
    }

    /** Loaded demand instance and optional, already materialized procurement parameters. */
    public static final class StoredInstance {
        public final Settings settings;
        public final ReplicationData demandData;
        public final ProcurementParams procurementParams;

        private StoredInstance(Settings settings,
                               ReplicationData demandData,
                               ProcurementParams procurementParams) {
            this.settings = settings.copy();
            this.demandData = demandData;
            this.procurementParams = procurementParams;
        }

        public boolean hasProcurementParams() {
            return procurementParams != null;
        }
    }

    /**
     * Saves one Reviewer 3 Comment 3 instance into a new or empty directory.
     * Existing nonempty directories are rejected to avoid mixing two instances.
     */
    public static void save(Path directory,
                            Settings settings,
                            ReplicationData demandData,
                            ProcurementParams procurementParams) throws IOException {
        requireEmptyDirectory(directory);
        validateDemandData(settings, demandData);
        if (procurementParams != null && procurementParams.J != settings.laneCount) {
            throw new IllegalArgumentException("Procurement lane count differs from demand lane count.");
        }

        writeManifest(directory.resolve(MANIFEST), settings, demandData, procurementParams != null);
        writeNames(directory.resolve("lanes.csv"), "lane", demandData.laneNames);
        writeIndexedVector(directory.resolve("baseline_demand.csv"), "baselineDemand", demandData.baselineDemand);
        writeSamples(directory.resolve("training_samples.csv"), demandData.trainingSamples,
                settings.laneCount, demandData.thetaNow.dim());
        writeIndexedVector(directory.resolve("theta_now.csv"), "theta", demandData.thetaNow.values());
        writeSamples(directory.resolve("oos_samples.csv"), demandData.oosSamples,
                settings.laneCount, demandData.thetaNow.dim());
        writeIndexedVector(directory.resolve("conditional_mean.csv"), "conditionalMean",
                demandData.conditionalMean);
        writeHistory(directory.resolve("query_history.csv"), demandData.queryHistoryLatestFirst);

        if (procurementParams != null) {
            writeProcurement(directory, procurementParams);
        }
    }

    /**
     * Reads the files written by {@link #save(Path, Settings, ReplicationData, ProcurementParams)}.
     * No random number is redrawn and no solver is called.
     */
    public static StoredInstance load(Path directory) throws IOException {
        Properties manifest = loadProperties(directory.resolve(MANIFEST));
        require(FORMAT_VERSION.equals(manifest.getProperty("formatVersion")),
                "Unsupported formatVersion: " + manifest.getProperty("formatVersion"));

        Settings settings = readSettings(manifest);
        int thetaDim = getInt(manifest, "thetaDimension");
        int historyLagCount = getInt(manifest, "queryHistoryLagCount");

        List<String> lanes = readNames(directory.resolve("lanes.csv"), "lane");
        double[] baseline = readIndexedVector(directory.resolve("baseline_demand.csv"),
                "baselineDemand", settings.laneCount);
        List<Sample> training = readSamples(directory.resolve("training_samples.csv"),
                settings.laneCount, thetaDim, settings.trainingSampleCount);
        double[] theta = readIndexedVector(directory.resolve("theta_now.csv"), "theta", thetaDim);
        List<Sample> oos = readSamples(directory.resolve("oos_samples.csv"),
                settings.laneCount, thetaDim, settings.oosSampleCount);
        double[] conditionalMean = readIndexedVector(directory.resolve("conditional_mean.csv"),
                "conditionalMean", settings.laneCount);
        double[][] history = readHistory(directory.resolve("query_history.csv"),
                historyLagCount, settings.laneCount);

        ReplicationData demandData = new ReplicationData(
                lanes,
                baseline,
                training,
                new CovariateVector(theta),
                oos,
                conditionalMean,
                history);
        validateDemandData(settings, demandData);

        boolean hasProcurement = getBoolean(manifest, "hasProcurementParams");
        ProcurementParams params = hasProcurement ? readProcurement(directory, lanes) : null;
        return new StoredInstance(settings, demandData, params);
    }

    private static void requireEmptyDirectory(Path directory) throws IOException {
        if (Files.exists(directory) && !Files.isDirectory(directory)) {
            throw new IOException("Instance path is not a directory: " + directory);
        }
        Files.createDirectories(directory);
        try (Stream<Path> entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) {
                throw new IOException("Refusing to overwrite nonempty instance directory: " + directory);
            }
        }
    }

    private static void validateDemandData(Settings settings, ReplicationData data) {
        require(data != null, "demandData is required.");
        require(data.laneNames.size() == settings.laneCount, "laneNames size mismatch.");
        require(data.baselineDemand.length == settings.laneCount, "baselineDemand size mismatch.");
        require(data.trainingSamples.size() == settings.trainingSampleCount,
                "training sample count mismatch.");
        require(data.oosSamples.size() == settings.oosSampleCount, "OOS sample count mismatch.");
        require(data.thetaNow.dim() == settings.laneCount * settings.observedLagPeriods,
                "thetaNow dimension mismatch.");
        require(data.conditionalMean.length == settings.laneCount,
                "conditionalMean size mismatch.");
        require(data.queryHistoryLatestFirst.length == 3, "True query history must contain three lags.");
        for (double[] lag : data.queryHistoryLatestFirst) {
            require(lag.length == settings.laneCount, "query history lane count mismatch.");
        }
        validateSamples(data.trainingSamples, settings.laneCount, data.thetaNow.dim(), "training");
        validateSamples(data.oosSamples, settings.laneCount, data.thetaNow.dim(), "OOS");
    }

    private static void validateSamples(List<Sample> samples, int laneCount, int thetaDim, String label) {
        for (Sample sample : samples) {
            require(sample != null && sample.period != null && sample.theta != null,
                    label + " sample contains null fields.");
            require(sample.demand().length == laneCount, label + " demand dimension mismatch.");
            require(sample.theta.dim() == thetaDim, label + " theta dimension mismatch.");
        }
    }

    private static void writeManifest(Path path,
                                      Settings s,
                                      ReplicationData data,
                                      boolean hasProcurement) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("# Reviewer 3 Comment 3 synthetic instance; deterministic UTF-8 format");
        lines.add("formatVersion=" + FORMAT_VERSION);
        lines.add("hasProcurementParams=" + hasProcurement);
        lines.add("thetaDimension=" + data.thetaNow.dim());
        lines.add("queryHistoryLagCount=" + data.queryHistoryLatestFirst.length);
        lines.add("laneCount=" + s.laneCount);
        lines.add("trainingSampleCount=" + s.trainingSampleCount);
        lines.add("observedLagPeriods=" + s.observedLagPeriods);
        lines.add("warmupPeriods=" + s.warmupPeriods);
        lines.add("oosSampleCount=" + s.oosSampleCount);
        lines.add("meanDemandPerLane=" + Double.toString(s.meanDemandPerLane));
        lines.add("laneScaleLower=" + Double.toString(s.laneScaleLower));
        lines.add("laneScaleUpper=" + Double.toString(s.laneScaleUpper));
        lines.add("innovationCv=" + Double.toString(s.innovationCv));
        lines.add("latentCrossLaneCorrelation=" + Double.toString(s.latentCrossLaneCorrelation));
        lines.add("longRunWeight=" + Double.toString(s.longRunWeight));
        lines.add("globalHistoryWeight=" + Double.toString(s.globalHistoryWeight));
        lines.add("laneHistoryWeight=" + Double.toString(s.laneHistoryWeight));
        lines.add("innovationDistribution=" + s.innovationDistribution.name());
        lines.add("baselineSeed=" + s.baselineSeed);
        lines.add("replicationSeed=" + s.replicationSeed);
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static Settings readSettings(Properties p) throws IOException {
        Settings s = new Settings();
        s.laneCount = getInt(p, "laneCount");
        s.trainingSampleCount = getInt(p, "trainingSampleCount");
        s.observedLagPeriods = getInt(p, "observedLagPeriods");
        s.warmupPeriods = getInt(p, "warmupPeriods");
        s.oosSampleCount = getInt(p, "oosSampleCount");
        s.meanDemandPerLane = getDouble(p, "meanDemandPerLane");
        s.laneScaleLower = getDouble(p, "laneScaleLower");
        s.laneScaleUpper = getDouble(p, "laneScaleUpper");
        s.innovationCv = getDouble(p, "innovationCv");
        s.latentCrossLaneCorrelation = getDouble(p, "latentCrossLaneCorrelation");
        if (p.getProperty("longRunWeight") != null) {
            s.longRunWeight = getDouble(p, "longRunWeight");
            s.globalHistoryWeight = getDouble(p, "globalHistoryWeight");
            s.laneHistoryWeight = getDouble(p, "laneHistoryWeight");
        }
        try {
        s.innovationDistribution = InnovationDistribution.valueOf(required(p, "innovationDistribution"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid innovationDistribution.", e);
        }
        s.baselineSeed = getLong(p, "baselineSeed");
        s.replicationSeed = getLong(p, "replicationSeed");
        s.validate();
        return s;
    }

    private static void writeSamples(Path path,
                                     List<Sample> samples,
                                     int laneCount,
                                     int thetaDim) throws IOException {
        try (BufferedWriter out = writer(path)) {
            List<String> header = new ArrayList<>();
            addAll(header, "id", "weight", "tIndex", "startDate", "endDate", "holidayCount",
                    "avgFreightIndex", "avgConsumptionIndex", "avgWEIIndex");
            for (int j = 0; j < laneCount; j++) header.add("demand_" + (j + 1));
            for (int k = 0; k < thetaDim; k++) header.add("theta_" + (k + 1));
            writeCsvRow(out, header);

            for (Sample sample : samples) {
                PeriodData p = sample.period;
                List<String> row = new ArrayList<>(header.size());
                addAll(row,
                        Integer.toString(sample.id),
                        Double.toString(sample.weight),
                        Integer.toString(p.tIndex),
                        p.startDate.toString(),
                        p.endDate.toString(),
                        Integer.toString(p.holidayCount),
                        Double.toString(p.avgFreightIndex),
                        Double.toString(p.avgConsumptionIndex),
                        Double.toString(p.avgWEIIndex));
                for (double value : sample.demand()) row.add(Double.toString(value));
                for (double value : sample.theta.values()) row.add(Double.toString(value));
                writeCsvRow(out, row);
            }
        }
    }

    private static List<Sample> readSamples(Path path,
                                            int laneCount,
                                            int thetaDim,
                                            int expectedRows) throws IOException {
        int expectedColumns = 9 + laneCount + thetaDim;
        List<Sample> samples = new ArrayList<>(expectedRows);
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = readCsvRow(in, path);
            requireIo(header.size() == expectedColumns, "Unexpected sample header width in " + path);
            String line;
            int lineNumber = 1;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) continue;
                List<String> row = parseCsvLine(line);
                requireIo(row.size() == expectedColumns,
                        "Unexpected sample width at " + path + ":" + lineNumber);
                int c = 0;
                int id = parseInt(row.get(c++), path, lineNumber);
                double weight = parseDouble(row.get(c++), path, lineNumber);
                int tIndex = parseInt(row.get(c++), path, lineNumber);
                LocalDate start = parseDate(row.get(c++), path, lineNumber);
                LocalDate end = parseDate(row.get(c++), path, lineNumber);
                int holidays = parseInt(row.get(c++), path, lineNumber);
                double freight = parseDouble(row.get(c++), path, lineNumber);
                double consumption = parseDouble(row.get(c++), path, lineNumber);
                double wei = parseDouble(row.get(c++), path, lineNumber);
                double[] demand = new double[laneCount];
                for (int j = 0; j < laneCount; j++) demand[j] = parseDouble(row.get(c++), path, lineNumber);
                double[] theta = new double[thetaDim];
                for (int k = 0; k < thetaDim; k++) theta[k] = parseDouble(row.get(c++), path, lineNumber);
                PeriodData period = new PeriodData(tIndex, start, end, demand, holidays,
                        freight, consumption, wei);
                samples.add(new Sample(id, period, new CovariateVector(theta), weight));
            }
        }
        requireIo(samples.size() == expectedRows,
                "Expected " + expectedRows + " samples but read " + samples.size() + " from " + path);
        return samples;
    }

    private static void writeHistory(Path path, double[][] history) throws IOException {
        try (BufferedWriter out = writer(path)) {
            writeCsvRow(out, List.of("lagLatestFirst", "laneIndex", "demand"));
            for (int lag = 0; lag < history.length; lag++) {
                for (int j = 0; j < history[lag].length; j++) {
                    writeCsvRow(out, List.of(
                            Integer.toString(lag),
                            Integer.toString(j),
                            Double.toString(history[lag][j])));
                }
            }
        }
    }

    private static double[][] readHistory(Path path, int lagCount, int laneCount) throws IOException {
        double[][] history = new double[lagCount][laneCount];
        boolean[][] seen = new boolean[lagCount][laneCount];
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            readCsvRow(in, path);
            String line;
            int lineNumber = 1;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) continue;
                List<String> row = parseCsvLine(line);
                requireIo(row.size() == 3, "Unexpected history width at " + path + ":" + lineNumber);
                int lag = parseInt(row.get(0), path, lineNumber);
                int lane = parseInt(row.get(1), path, lineNumber);
                requireIo(lag >= 0 && lag < lagCount && lane >= 0 && lane < laneCount,
                        "History index out of range at " + path + ":" + lineNumber);
                requireIo(!seen[lag][lane], "Duplicate history cell at " + path + ":" + lineNumber);
                history[lag][lane] = parseDouble(row.get(2), path, lineNumber);
                seen[lag][lane] = true;
            }
        }
        for (int lag = 0; lag < lagCount; lag++) {
            for (int j = 0; j < laneCount; j++) {
                requireIo(seen[lag][j], "Missing history cell lag=" + lag + ", lane=" + j);
            }
        }
        return history;
    }

    private static void writeProcurement(Path directory, ProcurementParams p) throws IOException {
        List<String> props = List.of(
                "# Concrete procurement parameters; M is checked after reconstruction",
                "I=" + p.I,
                "J=" + p.J,
                "alpha=" + p.alpha,
                "beta=" + p.beta);
        Files.write(directory.resolve("procurement.properties"), props, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);

        try (BufferedWriter out = writer(directory.resolve("carriers.csv"))) {
            writeCsvRow(out, List.of("index", "carrier", "p", "h", "M"));
            for (int i = 0; i < p.I; i++) {
                writeCsvRow(out, List.of(Integer.toString(i), p.carriers.get(i),
                        Double.toString(p.p[i]), Double.toString(p.h[i]), Double.toString(p.M[i])));
            }
        }
        writeIndexedVector(directory.resolve("spot_cost.csv"), "e", p.e);
        try (BufferedWriter out = writer(directory.resolve("carrier_lane_params.csv"))) {
            writeCsvRow(out, List.of("carrierIndex", "laneIndex", "eligible", "q", "r"));
            for (int i = 0; i < p.I; i++) {
                for (int j = 0; j < p.J; j++) {
                    writeCsvRow(out, List.of(Integer.toString(i), Integer.toString(j),
                            Boolean.toString(p.eligible[i][j]), Double.toString(p.q[i][j]),
                            Double.toString(p.r[i][j])));
                }
            }
        }
    }

    private static ProcurementParams readProcurement(Path directory, List<String> lanes) throws IOException {
        Properties props = loadProperties(directory.resolve("procurement.properties"));
        int I = getInt(props, "I");
        int J = getInt(props, "J");
        int alpha = getInt(props, "alpha");
        int beta = getInt(props, "beta");
        requireIo(J == lanes.size(), "Procurement J differs from lanes.csv.");

        List<String> carriers = new ArrayList<>(I);
        double[] p = new double[I];
        double[] h = new double[I];
        double[] persistedM = new double[I];
        try (BufferedReader in = Files.newBufferedReader(directory.resolve("carriers.csv"), StandardCharsets.UTF_8)) {
            readCsvRow(in, directory.resolve("carriers.csv"));
            for (int i = 0; i < I; i++) {
                List<String> row = readCsvRow(in, directory.resolve("carriers.csv"));
                requireIo(row.size() == 5 && parseInt(row.get(0), directory, i + 2) == i,
                        "Invalid carriers.csv row " + (i + 2));
                carriers.add(row.get(1));
                p[i] = parseDouble(row.get(2), directory, i + 2);
                h[i] = parseDouble(row.get(3), directory, i + 2);
                persistedM[i] = parseDouble(row.get(4), directory, i + 2);
            }
            requireIo(in.readLine() == null, "Unexpected extra carrier rows.");
        }

        double[] e = readIndexedVector(directory.resolve("spot_cost.csv"), "e", J);
        double[][] q = new double[I][J];
        double[][] r = new double[I][J];
        boolean[][] eligible = new boolean[I][J];
        boolean[][] seen = new boolean[I][J];
        Path matrixPath = directory.resolve("carrier_lane_params.csv");
        try (BufferedReader in = Files.newBufferedReader(matrixPath, StandardCharsets.UTF_8)) {
            readCsvRow(in, matrixPath);
            String line;
            int lineNumber = 1;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) continue;
                List<String> row = parseCsvLine(line);
                requireIo(row.size() == 5, "Invalid carrier-lane row at line " + lineNumber);
                int i = parseInt(row.get(0), matrixPath, lineNumber);
                int j = parseInt(row.get(1), matrixPath, lineNumber);
                requireIo(i >= 0 && i < I && j >= 0 && j < J && !seen[i][j],
                        "Invalid or duplicate carrier-lane index at line " + lineNumber);
                eligible[i][j] = parseBoolean(row.get(2), matrixPath, lineNumber);
                q[i][j] = parseDouble(row.get(3), matrixPath, lineNumber);
                r[i][j] = parseDouble(row.get(4), matrixPath, lineNumber);
                seen[i][j] = true;
            }
        }
        for (int i = 0; i < I; i++) {
            for (int j = 0; j < J; j++) requireIo(seen[i][j], "Missing carrier-lane cell.");
        }

        ProcurementParams out = new ProcurementParams(carriers, J, e, p, h, q, r, eligible, alpha, beta);
        for (int i = 0; i < I; i++) {
            requireIo(sameDouble(out.M[i], persistedM[i]), "Persisted M differs at carrier " + i);
        }
        return out;
    }

    private static void writeNames(Path path, String valueHeader, List<String> names) throws IOException {
        try (BufferedWriter out = writer(path)) {
            writeCsvRow(out, List.of("index", valueHeader));
            for (int i = 0; i < names.size(); i++) {
                writeCsvRow(out, List.of(Integer.toString(i), names.get(i)));
            }
        }
    }

    private static List<String> readNames(Path path, String valueHeader) throws IOException {
        List<String> out = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = readCsvRow(in, path);
            requireIo(header.equals(List.of("index", valueHeader)), "Unexpected names header in " + path);
            String line;
            int lineNumber = 1;
            while ((line = in.readLine()) != null) {
                lineNumber++;
                if (line.isEmpty()) continue;
                List<String> row = parseCsvLine(line);
                requireIo(row.size() == 2 && parseInt(row.get(0), path, lineNumber) == out.size(),
                        "Invalid name index at " + path + ":" + lineNumber);
                out.add(row.get(1));
            }
        }
        return out;
    }

    private static void writeIndexedVector(Path path, String valueHeader, double[] values) throws IOException {
        try (BufferedWriter out = writer(path)) {
            writeCsvRow(out, List.of("index", valueHeader));
            for (int i = 0; i < values.length; i++) {
                writeCsvRow(out, List.of(Integer.toString(i), Double.toString(values[i])));
            }
        }
    }

    private static double[] readIndexedVector(Path path, String valueHeader, int expectedSize) throws IOException {
        double[] out = new double[expectedSize];
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            List<String> header = readCsvRow(in, path);
            requireIo(header.equals(List.of("index", valueHeader)), "Unexpected vector header in " + path);
            for (int i = 0; i < expectedSize; i++) {
                List<String> row = readCsvRow(in, path);
                requireIo(row.size() == 2 && parseInt(row.get(0), path, i + 2) == i,
                        "Invalid vector index at " + path + ":" + (i + 2));
                out[i] = parseDouble(row.get(1), path, i + 2);
            }
            requireIo(in.readLine() == null, "Unexpected extra vector rows in " + path);
        }
        return out;
    }

    private static BufferedWriter writer(Path path) throws IOException {
        return Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    private static void writeCsvRow(BufferedWriter out, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) out.write(',');
            String value = fields.get(i);
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IOException("Embedded newlines are not supported in instance names.");
            }
            if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0) {
                out.write('"');
                out.write(value.replace("\"", "\"\""));
                out.write('"');
            } else {
                out.write(value);
            }
        }
        out.newLine();
    }

    private static List<String> readCsvRow(BufferedReader in, Path path) throws IOException {
        String line = in.readLine();
        if (line == null) throw new IOException("Unexpected end of CSV: " + path);
        return parseCsvLine(line);
    }

    private static List<String> parseCsvLine(String line) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(ch);
                }
            } else if (ch == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else if (ch == '"' && current.length() == 0) {
                quoted = true;
            } else {
                current.append(ch);
            }
        }
        if (quoted) throw new IOException("Unclosed CSV quote.");
        fields.add(current.toString());
        return fields;
    }

    private static Properties loadProperties(Path path) throws IOException {
        Properties p = new Properties();
        try (BufferedReader in = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            p.load(in);
        }
        return p;
    }

    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null) throw new IOException("Missing property: " + key);
        return value;
    }

    private static int getInt(Properties p, String key) throws IOException {
        try {
            return Integer.parseInt(required(p, key));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer property: " + key, e);
        }
    }

    private static long getLong(Properties p, String key) throws IOException {
        try {
            return Long.parseLong(required(p, key));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid long property: " + key, e);
        }
    }

    private static double getDouble(Properties p, String key) throws IOException {
        try {
            return Double.parseDouble(required(p, key));
        } catch (NumberFormatException e) {
            throw new IOException("Invalid double property: " + key, e);
        }
    }

    private static boolean getBoolean(Properties p, String key) throws IOException {
        return parseBoolean(required(p, key), Path.of(MANIFEST), 0);
    }

    private static int parseInt(String value, Path path, int line) throws IOException {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid integer at " + path + ":" + line, e);
        }
    }

    private static double parseDouble(String value, Path path, int line) throws IOException {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid double at " + path + ":" + line, e);
        }
    }

    private static LocalDate parseDate(String value, Path path, int line) throws IOException {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            throw new IOException("Invalid date at " + path + ":" + line, e);
        }
    }

    private static boolean parseBoolean(String value, Path path, int line) throws IOException {
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw new IOException("Invalid boolean at " + path + ":" + line);
    }

    private static void addAll(List<String> out, String... values) {
        for (String value : values) out.add(value);
    }

    private static boolean sameDouble(double a, double b) {
        return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static void requireIo(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
}
