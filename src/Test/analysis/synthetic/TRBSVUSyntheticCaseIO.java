package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Lossless case I/O; human-readable TSV is primary and binary is legacy-compatible. */
public final class TRBSVUSyntheticCaseIO {
    private static final String TEXT_HEADER = "TRBSVU_INSTANCE_TSV_V1";
    private static final int MAGIC = 0x54535655;
    private static final int VERSION = 1;

    private TRBSVUSyntheticCaseIO() { }

    /** Human-readable, lossless, single-file snapshot. */
    public static void saveText(TRBSVUSyntheticCase instance, Path file) throws IOException {
        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(TEXT_HEADER);
            out.newLine();
            var s = instance.seeds;
            row(out, "SEEDS", s.demandParameters(), s.procurement(), s.contexts(),
                    s.historicalNoise(), s.oosNoise());
            ProcurementParams p = instance.params;
            row(out, "DIMENSIONS", p.I, p.J, p.alpha, p.beta);
            for (int j = 0; j < p.J; j++)
                row(out, "LANE", j, escape(instance.lanes.get(j)), p.e[j]);
            for (int i = 0; i < p.I; i++) {
                row(out, "CARRIER", i, escape(p.carriers.get(i)), p.p[i], p.h[i]);
                for (int j = 0; j < p.J; j++)
                    row(out, "PAIR", i, j, p.eligible[i][j], p.q[i][j], p.r[i][j]);
            }
            row(out, "TEST_CONTEXT", vector(instance.testContext.values()));
            writeTextSamples(out, "HISTORY", instance.history);
            writeTextSamples(out, "OOS", instance.oos);
            row(out, "END");
        }
    }

    public static TRBSVUSyntheticCase loadText(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !TEXT_HEADER.equals(lines.get(0)))
            throw new IOException("Unknown human-readable synthetic-case format: " + file);
        TRBSVUSyntheticCase.Seeds seeds = null;
        int carrierCount = -1, laneCount = -1, alpha = -1, beta = -1;
        List<String> carrierNames = null, laneNames = null;
        double[] spot = null, mqc = null, penalty = null;
        double[][] capacity = null, rate = null;
        boolean[][] eligible = null;
        boolean[][] pairSeen = null;
        double[] testContext = null;
        List<Sample> history = new ArrayList<>(), oos = new ArrayList<>();
        boolean ended = false;
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.isBlank()) continue;
            if (ended) throw new IOException("Record after END at line " + (lineNumber + 1));
            String[] f = line.split("\\t", -1);
            try {
                switch (f[0]) {
                    case "SEEDS" -> {
                        requireFields(f, 6);
                        if (seeds != null) throw new IOException("Duplicate SEEDS record");
                        seeds = new TRBSVUSyntheticCase.Seeds(longValue(f[1]), longValue(f[2]),
                                longValue(f[3]), longValue(f[4]), longValue(f[5]));
                    }
                    case "DIMENSIONS" -> {
                        requireFields(f, 5);
                        if (carrierNames != null) throw new IOException("Duplicate DIMENSIONS record");
                        carrierCount = bounded(intValue(f[1]), 1, 1000, "carriers");
                        laneCount = bounded(intValue(f[2]), 1, 10000, "lanes");
                        alpha = intValue(f[3]);
                        beta = intValue(f[4]);
                        carrierNames = new ArrayList<>(java.util.Collections.nCopies(carrierCount, null));
                        laneNames = new ArrayList<>(java.util.Collections.nCopies(laneCount, null));
                        spot = new double[laneCount];
                        mqc = new double[carrierCount];
                        penalty = new double[carrierCount];
                        capacity = new double[carrierCount][laneCount];
                        rate = new double[carrierCount][laneCount];
                        eligible = new boolean[carrierCount][laneCount];
                        pairSeen = new boolean[carrierCount][laneCount];
                    }
                    case "LANE" -> {
                        requireAllocated(laneNames, "DIMENSIONS must precede LANE");
                        requireFields(f, 4);
                        int j = bounded(intValue(f[1]), 0, laneCount - 1, "lane index");
                        if (laneNames.get(j) != null) throw new IOException("Duplicate lane " + j);
                        laneNames.set(j, unescape(f[2]));
                        spot[j] = doubleValue(f[3]);
                    }
                    case "CARRIER" -> {
                        requireAllocated(carrierNames, "DIMENSIONS must precede CARRIER");
                        requireFields(f, 5);
                        int i = bounded(intValue(f[1]), 0, carrierCount - 1, "carrier index");
                        if (carrierNames.get(i) != null) throw new IOException("Duplicate carrier " + i);
                        carrierNames.set(i, unescape(f[2]));
                        mqc[i] = doubleValue(f[3]);
                        penalty[i] = doubleValue(f[4]);
                    }
                    case "PAIR" -> {
                        requireAllocated(capacity, "DIMENSIONS must precede PAIR");
                        requireFields(f, 6);
                        int i = bounded(intValue(f[1]), 0, carrierCount - 1, "carrier index");
                        int j = bounded(intValue(f[2]), 0, laneCount - 1, "lane index");
                        if (pairSeen[i][j]) throw new IOException("Duplicate pair " + i + "," + j);
                        if (!"true".equals(f[3]) && !"false".equals(f[3]))
                            throw new IOException("Invalid eligible value " + f[3]);
                        pairSeen[i][j] = true;
                        eligible[i][j] = "true".equals(f[3]);
                        capacity[i][j] = doubleValue(f[4]);
                        rate[i][j] = doubleValue(f[5]);
                    }
                    case "TEST_CONTEXT" -> {
                        requireFields(f, 2);
                        if (testContext != null) throw new IOException("Duplicate TEST_CONTEXT record");
                        testContext = parseVector(f[1]);
                    }
                    case "SAMPLE" -> {
                        requireFields(f, 13);
                        List<Sample> target = switch (f[1]) {
                            case "HISTORY" -> history;
                            case "OOS" -> oos;
                            default -> throw new IOException("Unknown sample group " + f[1]);
                        };
                        double[] theta = parseVector(f[11]);
                        double[] demand = parseVector(f[12]);
                        if (laneCount < 0 || demand.length != laneCount)
                            throw new IOException("Demand dimension mismatch");
                        if (testContext == null || theta.length != testContext.length)
                            throw new IOException("Context dimension mismatch");
                        PeriodData period = new PeriodData(intValue(f[3]), LocalDate.parse(f[4]),
                                LocalDate.parse(f[5]), demand, intValue(f[6]), doubleValue(f[7]),
                                doubleValue(f[8]), doubleValue(f[9]));
                        target.add(new Sample(intValue(f[2]), period,
                                new CovariateVector(theta), doubleValue(f[10])));
                    }
                    case "END" -> {
                        requireFields(f, 1);
                        ended = true;
                    }
                    default -> throw new IOException("Unknown record type " + f[0]);
                }
            } catch (RuntimeException ex) {
                throw new IOException("Invalid instance text at line " + (lineNumber + 1), ex);
            }
        }
        if (!ended || seeds == null || carrierNames == null || testContext == null
                || carrierNames.contains(null) || laneNames.contains(null)
                || history.isEmpty() || oos.isEmpty())
            throw new IOException("Incomplete human-readable synthetic case: " + file);
        for (int i = 0; i < carrierCount; i++)
            for (int j = 0; j < laneCount; j++)
                if (!pairSeen[i][j])
                    throw new IOException("Missing carrier-lane pair " + i + "," + j);
        ProcurementParams params = new ProcurementParams(carrierNames, laneCount, spot,
                mqc, penalty, capacity, rate, eligible, alpha, beta);
        return new TRBSVUSyntheticCase(params, laneNames, history,
                new CovariateVector(testContext), oos, seeds);
    }

    /** CREATE_NEW prevents an accidental rerun from overwriting a frozen case. */
    public static void save(TRBSVUSyntheticCase instance, Path file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(file,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            var seeds = instance.seeds;
            out.writeLong(seeds.demandParameters());
            out.writeLong(seeds.procurement());
            out.writeLong(seeds.contexts());
            out.writeLong(seeds.historicalNoise());
            out.writeLong(seeds.oosNoise());
            ProcurementParams p = instance.params;
            out.writeInt(p.I);
            out.writeInt(p.J);
            out.writeInt(p.alpha);
            out.writeInt(p.beta);
            for (String name : p.carriers) out.writeUTF(name);
            for (String name : instance.lanes) out.writeUTF(name);
            for (int j = 0; j < p.J; j++) out.writeDouble(p.e[j]);
            for (int i = 0; i < p.I; i++) {
                out.writeDouble(p.p[i]);
                out.writeDouble(p.h[i]);
                for (int j = 0; j < p.J; j++) {
                    out.writeBoolean(p.eligible[i][j]);
                    out.writeDouble(p.q[i][j]);
                    out.writeDouble(p.r[i][j]);
                }
            }
            writeVector(out, instance.testContext.values());
            writeSamples(out, instance.history);
            writeSamples(out, instance.oos);
        }
    }

    public static TRBSVUSyntheticCase load(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION)
                throw new IOException("Unknown synthetic-case format.");
            var seeds = new TRBSVUSyntheticCase.Seeds(in.readLong(), in.readLong(),
                    in.readLong(), in.readLong(), in.readLong());
            int carriers = bounded(in.readInt(), 1, 1000, "carriers");
            int lanes = bounded(in.readInt(), 1, 10000, "lanes");
            int alpha = in.readInt(), beta = in.readInt();
            List<String> carrierNames = new ArrayList<>(carriers);
            List<String> laneNames = new ArrayList<>(lanes);
            for (int i = 0; i < carriers; i++) carrierNames.add(in.readUTF());
            for (int j = 0; j < lanes; j++) laneNames.add(in.readUTF());
            double[] spot = new double[lanes], mqc = new double[carriers], penalty = new double[carriers];
            double[][] capacity = new double[carriers][lanes], rate = new double[carriers][lanes];
            boolean[][] eligible = new boolean[carriers][lanes];
            for (int j = 0; j < lanes; j++) spot[j] = in.readDouble();
            for (int i = 0; i < carriers; i++) {
                mqc[i] = in.readDouble();
                penalty[i] = in.readDouble();
                for (int j = 0; j < lanes; j++) {
                    eligible[i][j] = in.readBoolean();
                    capacity[i][j] = in.readDouble();
                    rate[i][j] = in.readDouble();
                }
            }
            ProcurementParams params = new ProcurementParams(carrierNames, lanes, spot,
                    mqc, penalty, capacity, rate, eligible, alpha, beta);
            CovariateVector query = new CovariateVector(readVector(in, 10000));
            List<Sample> history = readSamples(in, lanes, query.dim());
            List<Sample> oos = readSamples(in, lanes, query.dim());
            if (in.read() != -1) throw new IOException("Unexpected trailing synthetic-case bytes.");
            return new TRBSVUSyntheticCase(params, laneNames, history, query, oos, seeds);
        }
    }

    private static void writeSamples(DataOutputStream out, List<Sample> samples) throws IOException {
        out.writeInt(samples.size());
        for (Sample sample : samples) {
            PeriodData p = sample.period;
            out.writeInt(sample.id);
            out.writeInt(p.tIndex);
            out.writeLong(p.startDate.toEpochDay());
            out.writeLong(p.endDate.toEpochDay());
            out.writeInt(p.holidayCount);
            out.writeDouble(p.avgFreightIndex);
            out.writeDouble(p.avgConsumptionIndex);
            out.writeDouble(p.avgWEIIndex);
            out.writeDouble(sample.weight);
            writeVector(out, sample.theta.values());
            writeVector(out, sample.demand());
        }
    }

    private static void writeTextSamples(BufferedWriter out, String group, List<Sample> samples)
            throws IOException {
        for (Sample sample : samples) {
            PeriodData p = sample.period;
            row(out, "SAMPLE", group, sample.id, p.tIndex, p.startDate, p.endDate,
                    p.holidayCount, p.avgFreightIndex, p.avgConsumptionIndex, p.avgWEIIndex,
                    sample.weight, vector(sample.theta.values()), vector(sample.demand()));
        }
    }

    private static void row(BufferedWriter out, Object... fields) throws IOException {
        for (int k = 0; k < fields.length; k++) {
            if (k > 0) out.write('\t');
            out.write(String.valueOf(fields[k]));
        }
        out.newLine();
    }

    private static String vector(double[] values) {
        StringBuilder result = new StringBuilder();
        for (int k = 0; k < values.length; k++) {
            if (k > 0) result.append(',');
            result.append(Double.toString(values[k]));
        }
        return result.toString();
    }

    private static double[] parseVector(String text) {
        if (text.isEmpty()) return new double[0];
        String[] fields = text.split(",", -1);
        double[] values = new double[fields.length];
        for (int k = 0; k < fields.length; k++) values[k] = doubleValue(fields[k]);
        return values;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\t", "\\t")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private static String unescape(String text) throws IOException {
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int k = 0; k < text.length(); k++) {
            char c = text.charAt(k);
            if (!escaped) {
                if (c == '\\') escaped = true;
                else result.append(c);
            } else {
                result.append(switch (c) {
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    case 'n' -> '\n';
                    case '\\' -> '\\';
                    default -> throw new IOException("Invalid escaped name");
                });
                escaped = false;
            }
        }
        if (escaped) throw new IOException("Incomplete escaped name");
        return result.toString();
    }

    private static int intValue(String value) { return Integer.parseInt(value); }
    private static long longValue(String value) { return Long.parseLong(value); }
    private static double doubleValue(String value) { return Double.parseDouble(value); }

    private static void requireFields(String[] fields, int count) throws IOException {
        if (fields.length != count) throw new IOException("Expected " + count
                + " fields, found " + fields.length);
    }

    private static void requireAllocated(Object value, String message) throws IOException {
        if (value == null) throw new IOException(message);
    }

    private static List<Sample> readSamples(DataInputStream in, int lanes, int context)
            throws IOException {
        int count = bounded(in.readInt(), 1, 1_000_000, "sample count");
        List<Sample> samples = new ArrayList<>(count);
        for (int s = 0; s < count; s++) {
            int id = in.readInt(), t = in.readInt();
            LocalDate start = LocalDate.ofEpochDay(in.readLong());
            LocalDate end = LocalDate.ofEpochDay(in.readLong());
            int holidays = in.readInt();
            double freight = in.readDouble(), consumption = in.readDouble(), wei = in.readDouble();
            double weight = in.readDouble();
            double[] x = readVector(in, context);
            double[] demand = readVector(in, lanes);
            if (x.length != context || demand.length != lanes)
                throw new IOException("Synthetic-case sample dimension mismatch.");
            PeriodData period = new PeriodData(t, start, end, demand, holidays,
                    freight, consumption, wei);
            samples.add(new Sample(id, period, new CovariateVector(x), weight));
        }
        return samples;
    }

    private static void writeVector(DataOutputStream out, double[] values) throws IOException {
        out.writeInt(values.length);
        for (double value : values) out.writeDouble(value);
    }

    private static double[] readVector(DataInputStream in, int maxLength) throws IOException {
        int length = bounded(in.readInt(), 0, maxLength, "vector length");
        double[] values = new double[length];
        for (int k = 0; k < length; k++) values[k] = in.readDouble();
        return values;
    }

    private static int bounded(int value, int minimum, int maximum, String label) throws IOException {
        if (value < minimum || value > maximum) throw new IOException("Invalid " + label + ": " + value);
        return value;
    }
}
