package Test;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Helper.calculateHelper.KernelType;
import Model.SolveMode;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared, package-local setup for TRB reviewer experiments.
 *
 * <p><strong>Reviewer contract.</strong> Inputs are a weekly-wide demand file
 * and an explicitly generated procurement instance. The returned Config always
 * enables the corrected constraint-(6) equality. Historical experiments that
 * need the old >= interpretation must build their own Config explicitly.</p>
 */
final class TRBReviewerExperimentSupport {

    static final Path DEFAULT_WEEKLY_CSV = Paths.get(
            "analysis/巴西数据分析/新版_purchase时间/输入/聚合需求表_日度与周度/"
                    + "按purchase时间_五大区23OD_周度宽表_10供应商实验输入.csv");

    private TRBReviewerExperimentSupport() {
    }

    static Prepared prepare(Path weeklyCsv,
                            int numCarriers,
                            int k,
                            double cH,
                            KernelType kernel,
                            boolean standardize,
                            SolveMode solveMode) throws Exception {
        WeeklyWideLoader.Result weekly = WeeklyWideLoader.load(weeklyCsv);
        Config cfg = baseConfig(k, cH, kernel, standardize, solveMode);
        SampleBuilder.BuildResult built = SampleBuilder.buildFromPeriods(weekly.periods, weekly.laneNames, cfg);

        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(
                numCarriers, baselineDemand(weekly.periods), genCfg, cfg);
        return new Prepared(weekly, built, genCfg, params, cfg);
    }

    static Config baseConfig(int k,
                             double cH,
                             KernelType kernel,
                             boolean standardize,
                             SolveMode solveMode) {
        Config cfg = new Config();
        cfg.fillMissingDates = false;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = k;
        cfg.demandAgg = false;
        cfg.lagDemandAsShare = false;

        cfg.featureFlags.includeLagDemand = true;
        cfg.featureFlags.includeHolidayCount = false;
        cfg.featureFlags.includeFreightIndex = false;
        cfg.featureFlags.includeConsumptionIndex = false;
        cfg.featureFlags.includeWEIIndex = false;

        cfg.standardizeTheta = standardize;
        cfg.kernelType = kernel;
        cfg.C_h = cH;
        cfg.solveMode = solveMode;
        // All new TRB reviewer experiments use the corrected interpretation of
        // constraint (6): transported volume plus spot volume equals demand.
        cfg.enforceDemandEquality = true;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        cfg.writeCplexLogToFile = false;
        return cfg;
    }

    static ExperimentBatches retainTestIndicesAtLeast(ExperimentBatches source,
                                                       int minimumTestIndex,
                                                       int maxTrials) {
        List<List<Basic.Sample>> trainSets = new ArrayList<>();
        List<Basic.CovariateVector> thetaNow = new ArrayList<>();
        List<Basic.Sample> testSamples = new ArrayList<>();
        List<Integer> testIndices = new ArrayList<>();

        for (int i = 0; i < source.size() && testIndices.size() < maxTrials; i++) {
            if (source.testIndex.get(i) < minimumTestIndex) continue;
            trainSets.add(source.trainSets.get(i));
            thetaNow.add(source.thetaNowList.get(i));
            testSamples.add(source.testSamples.get(i));
            testIndices.add(source.testIndex.get(i));
        }
        return new ExperimentBatches(source.mode, trainSets, thetaNow, testSamples, testIndices);
    }

    static double[] baselineDemand(List<PeriodData> periods) {
        if (periods.isEmpty()) throw new IllegalArgumentException("No weekly periods.");
        int laneCount = periods.get(0).demandSum.length;
        double[] mean = new double[laneCount];
        for (PeriodData period : periods) {
            for (int j = 0; j < laneCount; j++) mean[j] += period.demandSum[j];
        }
        for (int j = 0; j < laneCount; j++) mean[j] /= periods.size();
        return mean;
    }

    static final class Prepared {
        final WeeklyWideLoader.Result weekly;
        final SampleBuilder.BuildResult built;
        final InstanceGenerator.GenConfig genCfg;
        final ProcurementParams params;
        final Config cfg;

        Prepared(WeeklyWideLoader.Result weekly,
                 SampleBuilder.BuildResult built,
                 InstanceGenerator.GenConfig genCfg,
                 ProcurementParams params,
                 Config cfg) {
            this.weekly = weekly;
            this.built = built;
            this.genCfg = genCfg;
            this.params = params;
            this.cfg = cfg;
        }
    }
}
