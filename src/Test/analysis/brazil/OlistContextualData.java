package Test.analysis.brazil;

import Basic.CovariateVector;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.StandardScaler;
import Test.BatchRunner;
import Test.analysis.synthetic.TRBSVUProcurementGenerator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Real weekly demands and past-demand contexts; no synthetic demand or test resampling. */
public final class OlistContextualData {
    public static final Path DEFAULT_INPUT = Path.of("analysis/巴西数据分析/新版_purchase时间/输入/"
            + "聚合需求表_日度与周度/按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");
    public static final int HISTORY = 50, VALIDATION_ORIGINS = 15, VALIDATION_TRAINING = 35;
    public static final int MAX_LAG = 3, FIRST_TEST = HISTORY + MAX_LAG;
    public final WeeklyWideLoader.Result weekly;
    public final double[] baselineDemand;
    public final ProcurementParams market;

    public OlistContextualData(Path input, long marketSeed) throws Exception {
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
        ProcurementParams original = TRBSVUProcurementGenerator.generate(15, baselineDemand, marketSeed);
        // Current Medium/Moderate pilot uses 80% upper selection, not the factory's 70% default.
        market = new ProcurementParams(original.carriers, original.J, original.e, original.p,
                original.h, original.q, original.r, original.eligible, original.alpha, 12);
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
        return SampleBuilder.buildFromPeriods(weekly.periods, weekly.laneNames, config).samples;
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
