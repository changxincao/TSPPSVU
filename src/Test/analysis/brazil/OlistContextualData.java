package Test.analysis.brazil;

import Basic.CovariateVector;
import Basic.ProcurementParams;
import Basic.PeriodData;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.StandardScaler;
import Test.BatchRunner;
import Test.analysis.synthetic.TRBSVUProcurementGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** Real weekly demands and past-demand contexts; no synthetic demand or test resampling. */
public final class OlistContextualData {
    public static final Path DEFAULT_INPUT = Path.of("analysis/巴西数据分析/新版_purchase时间/输入/"
            + "聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
    public static final int HISTORY = 50, VALIDATION_ORIGINS = 15, VALIDATION_TRAINING = 35;
    public static final int MAX_LAG = 3, FIRST_TEST = HISTORY + MAX_LAG;
    public static final boolean INCLUDE_TREND = Boolean.getBoolean("olist.includeTrend");
    public final WeeklyWideLoader.Result weekly;
    public final double[] baselineDemand;
    public final ProcurementParams market;
    public final long marketSeed;

    public OlistContextualData(Path input, long marketSeed) throws Exception {
        this(input, marketSeed, 0);
    }

    /** Legacy market, with exactly one parameter replacement: h_i=min eligible r_ij. */
    public static OlistContextualData legacyMin(Path input, long seed, int carriers) throws Exception {
        if (carriers != 10 && carriers != 15) throw new IllegalArgumentException("Legacy pilot requires 10 or 15 carriers");
        return new OlistContextualData(input, seed, carriers);
    }

    private OlistContextualData(Path input, long marketSeed, int legacyCarriers) throws Exception {
        this.marketSeed = marketSeed;
        // The legacy loader substitutes zero for malformed numbers. Do not silently do that here.
        List<String> lines = Files.readAllLines(input);
        String[] header = lines.get(0).replace("\ufeff", "").split(",", -1);
        if (!header[0].equals("weekIndex")) throw new IllegalArgumentException("Expected weekly wide CSV.");
        int row = 0;
        for (String line : lines.subList(1, lines.size())) {
            if (line.isBlank()) continue;
            String[] values = line.split(",", -1);
            if (values.length != header.length || Double.parseDouble(values[0]) != row)
                throw new IllegalArgumentException("Nonsequential week or column mismatch at " + row);
            for (int j = 1; j < values.length; j++) {
                double value = Double.parseDouble(values[j]);
                if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid demand.");
            }
            row++;
        }
        weekly = WeeklyWideLoader.load(input);
        if (row <= FIRST_TEST) throw new IllegalArgumentException("Too few weeks for Olist rolling evaluation.");
        baselineDemand = new double[weekly.laneNames.size()];
        for (var period : weekly.periods)
            for (int j = 0; j < baselineDemand.length; j++)
                baselineDemand[j] += period.demandSum[j] / weekly.periods.size();
        if (legacyCarriers > 0) {
            Config cfg = new Config(); cfg.seed = Math.toIntExact(marketSeed);
            ProcurementParams original = InstanceGenerator.generate(legacyCarriers, baselineDemand,
                    new InstanceGenerator.GenConfig(), cfg);
            double[] minPenalty = new double[original.I];
            for (int i = 0; i < original.I; i++) {
                minPenalty[i] = Double.POSITIVE_INFINITY;
                for (int j = 0; j < original.J; j++) if (original.eligible[i][j])
                    minPenalty[i] = Math.min(minPenalty[i], original.r[i][j]);
            }
            market = new ProcurementParams(original.carriers, original.J, original.e, original.p,
                    minPenalty, original.q, original.r, original.eligible, original.alpha, original.beta);
        } else {
            ProcurementParams original = TRBSVUProcurementGenerator.generate(15, baselineDemand, marketSeed);
            // Current Medium/Moderate pilot uses 80% upper selection, not the factory's 70% default.
            market = new ProcurementParams(original.carriers, original.J, original.e, original.p,
                    original.h, original.q, original.r, original.eligible, original.alpha, 12);
        }
    }

    private OlistContextualData(WeeklyWideLoader.Result weekly, double[] baseline,
                                ProcurementParams market, long seed) {
        this.weekly = weekly;
        this.baselineDemand = baseline;
        this.market = market;
        this.marketSeed = seed;
    }

    /** Complete readable input: saved numerical market is used, not regenerated remotely. */
    public void saveSnapshot(Path file) throws Exception {
        StringBuilder out = new StringBuilder("OLIST_SNAPSHOT_V1\t" + market.I + "\t" + market.J
                + "\t" + weekly.periods.size() + "\t" + market.alpha + "\t" + market.beta + "\t" + marketSeed + "\n");
        for (int j = 0; j < market.J; j++) out.append("LANE\t").append(j).append("\t-1\t")
                .append(weekly.laneNames.get(j)).append('\t').append(baselineDemand[j]).append('\t').append(market.e[j]).append('\n');
        for (int i = 0; i < market.I; i++) {
            out.append("CARRIER\t").append(i).append("\t-1\t").append(market.p[i]).append('\t')
                    .append(market.h[i]).append('\t').append(market.M[i]).append('\n');
            for (int j = 0; j < market.J; j++) out.append("PAIR\t").append(i).append('\t').append(j)
                    .append('\t').append(market.eligible[i][j]).append('\t').append(market.q[i][j]).append('\t')
                    .append(market.eligible[i][j] ? market.r[i][j] : "NA").append('\n');
        }
        for (var period : weekly.periods) {
            out.append("DEMAND\t").append(period.tIndex).append("\t-1");
            for (double demand : period.demandSum) out.append('\t').append(demand);
            out.append('\n');
        }
        OlistContextualRunner.atomic(file, out.toString());
    }

    public static OlistContextualData loadSnapshot(Path file) throws Exception {
        List<String> rows = Files.readAllLines(file);
        String[] header = rows.get(0).split("\t");
        if (header.length != 7 || !header[0].equals("OLIST_SNAPSHOT_V1"))
            throw new IllegalArgumentException("Unsupported Olist snapshot: " + file);
        int carriers = Integer.parseInt(header[1]), lanes = Integer.parseInt(header[2]), weeks = Integer.parseInt(header[3]);
        int alpha = Integer.parseInt(header[4]), beta = Integer.parseInt(header[5]);
        long seed = Long.parseLong(header[6]);
        if (carriers < 1 || lanes < 1 || weeks <= FIRST_TEST || alpha < 1 || alpha > beta || beta > carriers)
            throw new IllegalArgumentException("Unexpected frozen market dimensions/bounds.");
        String[] names = new String[lanes];
        double[] baseline = new double[lanes], spot = new double[lanes];
        double[] mqc = new double[carriers], penalty = new double[carriers], totalCapacity = new double[carriers];
        double[][] rate = new double[carriers][lanes], capacity = new double[carriers][lanes];
        boolean[][] eligible = new boolean[carriers][lanes], seenPair = new boolean[carriers][lanes];
        boolean[] seenCarrier = new boolean[carriers];
        PeriodData[] periods = new PeriodData[weeks];
        for (String row : rows.subList(1, rows.size())) {
            String[] f = row.split("\t", -1);
            int i = Integer.parseInt(f[1]), j = Integer.parseInt(f[2]);
            switch (f[0]) {
                case "LANE" -> {
                    if (f.length != 6 || names[i] != null) throw new IllegalArgumentException("Duplicate/bad lane.");
                    names[i] = f[3]; baseline[i] = finite(f[4]); spot[i] = finite(f[5]);
                }
                case "CARRIER" -> {
                    if (f.length != 6 || seenCarrier[i]) throw new IllegalArgumentException("Duplicate/bad carrier.");
                    seenCarrier[i] = true; mqc[i] = finite(f[3]); penalty[i] = finite(f[4]); totalCapacity[i] = finite(f[5]);
                }
                case "PAIR" -> {
                    if (f.length != 6 || seenPair[i][j] || !(f[3].equals("true") || f[3].equals("false")))
                        throw new IllegalArgumentException("Duplicate/bad pair.");
                    seenPair[i][j] = true; eligible[i][j] = Boolean.parseBoolean(f[3]);
                    capacity[i][j] = finite(f[4]); rate[i][j] = eligible[i][j] ? finite(f[5]) : 0;
                    if (!eligible[i][j] && (capacity[i][j] != 0 || !f[5].equals("NA")))
                        throw new IllegalArgumentException("Ineligible pair has nonzero data.");
                }
                case "DEMAND" -> {
                    if (f.length != lanes + 3 || periods[i] != null) throw new IllegalArgumentException("Duplicate/bad week.");
                    double[] demand = new double[lanes];
                    for (int k = 0; k < lanes; k++) demand[k] = finite(f[k + 3]);
                    var date = java.time.LocalDate.of(2000, 1, 1).plusDays(7L * i);
                    periods[i] = new PeriodData(i, date, date.plusDays(6), demand, 0, 0, 0, 0);
                }
                default -> throw new IllegalArgumentException("Unknown snapshot row: " + f[0]);
            }
        }
        if (Arrays.stream(names).anyMatch(Objects::isNull) || Arrays.stream(periods).anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Missing lanes/weeks.");
        for (int i = 0; i < carriers; i++) {
            if (!seenCarrier[i]) throw new IllegalArgumentException("Missing carrier.");
            for (int j = 0; j < lanes; j++) if (!seenPair[i][j]) throw new IllegalArgumentException("Missing pair.");
        }
        List<String> carrierNames = new ArrayList<>();
        for (int i = 0; i < carriers; i++) carrierNames.add("C" + (i + 1));
        ProcurementParams market = new ProcurementParams(carrierNames, lanes, spot, mqc, penalty, capacity, rate, eligible, alpha, beta);
        for (int i = 0; i < carriers; i++) if (Math.abs(market.M[i] - totalCapacity[i]) > 1e-10 * Math.max(1, totalCapacity[i]))
            throw new IllegalArgumentException("Total capacity disagrees with pairs.");
        for (int j = 0; j < lanes; j++) {
            double mean = 0;
            for (PeriodData period : periods) mean += period.demandSum[j] / weeks;
            if (Math.abs(mean - baseline[j]) > 1e-10 * Math.max(1, baseline[j]))
                throw new IllegalArgumentException("Baseline is not full-period mean.");
        }
        return new OlistContextualData(new WeeklyWideLoader.Result(List.of(names), List.of(periods)), baseline, market, seed);
    }

    private static double finite(String text) {
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid numeric snapshot value.");
        return value;
    }

    public List<Sample> samples(int lag) {
        if (lag < 1 || lag > MAX_LAG) throw new IllegalArgumentException("Lag must be 1, 2 or 3.");
        Config config = new Config();
        config.k1LagPeriods = lag;
        config.demandAgg = false;
        config.lagDemandAsShare = false;
        config.featureFlags.includeLagDemand = true;
        config.featureFlags.includeHolidayCount = false;
        config.featureFlags.includeFreightIndex = false;
        config.featureFlags.includeConsumptionIndex = false;
        config.featureFlags.includeWEIIndex = false;
        List<Sample> samples = SampleBuilder.buildFromPeriods(weekly.periods, weekly.laneNames, config).samples;
        if (INCLUDE_TREND) for (Sample sample : samples) {
            double[] context = Arrays.copyOf(sample.theta.values(), sample.theta.dim() + 1);
            // Calendar information known before demand occurs; never reset time per rolling window.
            // Training-max scaling makes t and t/104 exactly equivalent up to floating-point rounding.
            context[context.length - 1] = sample.period.tIndex + 1.0;
            sample.theta = new CovariateVector(context);
        }
        return samples;
    }

    public record Window(List<Sample> training, Sample target, int startWeek, int endWeek) { }
    public Window window(int targetWeek, int lag, int size) {
        List<Sample> samples = samples(lag);
        int targetIndex = targetWeek - lag;
        if (size < 1 || targetIndex < size || targetIndex >= samples.size())
            throw new IllegalArgumentException("Insufficient preceding history for week " + targetWeek);
        List<Sample> training = BatchRunner.deepCopySamples(samples.subList(targetIndex - size, targetIndex));
        return new Window(training, samples.get(targetIndex), targetWeek - size, targetWeek - 1);
    }

    public record Scaled(List<Sample> training, CovariateVector query, double[] maxima) { }
    public static Scaled scale(Window window) {
        List<Sample> training = BatchRunner.deepCopySamples(window.training());
        int dimension = window.target().theta.dim();
        double[] maxima = new double[dimension];
        for (Sample sample : training)
            for (int k = 0; k < dimension; k++)
                maxima[k] = Math.max(maxima[k], Math.abs(sample.theta.values()[k]));
        for (int k = 0; k < dimension; k++) if (maxima[k] < 1e-12) maxima[k] = 1.0;
        StandardScaler scaler = new StandardScaler(StandardScaler.Mode.TRAINING_MAX);
        scaler.fit(training, dimension);
        for (Sample sample : training)
            sample.theta = new CovariateVector(scaler.transform(sample.theta.values()));
        return new Scaled(training,
                new CovariateVector(scaler.transform(window.target().theta.values())), maxima);
    }
}
