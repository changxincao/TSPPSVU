package Test.analysis.brazil;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.basicHelper.WeeklyWideLoader;
import Model.RCSAASolverVariant;
import Model.SolveMode;
import Test.DROBatchRunner;
import Test.ExperimentBatches;
import Test.ExperimentBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Minimal runner for one RCSAA solver variant on a small parameter set.
 * It is intended for quick confirmation runs rather than full-batch aggregation.
 */
public class BrazilOlistSingleRCSAAVariantRunner {

    public static void main(String[] args) throws Exception {
        Path weeklyCsv = Paths.get(args[0]);
        int numCarriers = Integer.parseInt(args[1]);
        int w = Integer.parseInt(args[2]);
        int k1 = Integer.parseInt(args[3]);
        Path outRoot = Paths.get(args[4]);
        double cH = Double.parseDouble(args[5]);
        double lambda = Double.parseDouble(args[6]);
        RCSAASolverVariant variant = RCSAASolverVariant.valueOf(args[7]);
        int searchRadius = (args.length > 8 ? Integer.parseInt(args[8]) : 2);

        Files.createDirectories(outRoot);

        WeeklyWideLoader.Result loaded = WeeklyWideLoader.load(weeklyCsv);
        List<String> lanes = loaded.laneNames;

        Config cfg = buildConfig(k1, cH, lambda);
        cfg.rcsaaSolverVariant = variant;
        cfg.rcsaaSearchNeighborhoodRadius = searchRadius;

        SampleBuilder.BuildResult br = SampleBuilder.buildFromPeriods(loaded.periods, lanes, cfg);
        double[] dBase = buildBaselineDemand(br);
        InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);

        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, w);
        if (rolling.size() == 0) {
            throw new IllegalStateException("No rolling trials. Check W/k1 and data length.");
        }

        GlobalTrialCollector trialCollector = new GlobalTrialCollector(outRoot);
        GlobalSummaryCollector summaryCollector = new GlobalSummaryCollector(outRoot);

        String tag = buildTag(variant, w, k1, lambda, searchRadius);
        Path variantOut = outRoot.resolve(variant.name());

        System.out.println("variant=" + variant + ", out=" + variantOut.toAbsolutePath());
        DROBatchRunner.run(tag, lanes, params, cfg, rolling, variantOut, summaryCollector, trialCollector, genCfg);
        System.out.println("done: " + variant + " -> " + variantOut.toAbsolutePath());
    }

    private static Config buildConfig(int k1, double cH, double lambda) {
        Config cfg = new Config();
        cfg.fillMissingDates = false;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = k1;
        cfg.demandAgg = false;
        cfg.lagDemandAsShare = false;
        cfg.featureFlags.includeLagDemand = true;
        cfg.featureFlags.includeHolidayCount = false;
        cfg.featureFlags.includeFreightIndex = false;
        cfg.featureFlags.includeConsumptionIndex = false;
        cfg.featureFlags.includeWEIIndex = false;
        cfg.standardizeTheta = true;
        cfg.kernelType = Helper.calculateHelper.KernelType.EXPONENTIAL;
        cfg.C_h = cH;
        cfg.lambda = lambda;
        cfg.solveMode = SolveMode.RCSAA;
        cfg.threads = 4;
        cfg.timeLimitSeconds = 3600;
        cfg.seed = 0;
        return cfg;
    }

    private static double[] buildBaselineDemand(SampleBuilder.BuildResult br) {
        int jSize = br.periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (var p : br.periods) {
            for (int j = 0; j < jSize; j++) {
                sum[j] += p.demandSum[j];
            }
        }
        double denom = Math.max(1, br.periods.size());
        for (int j = 0; j < jSize; j++) {
            sum[j] /= denom;
        }
        return sum;
    }

    private static String buildTag(RCSAASolverVariant variant, int w, int k1, double lambda, int radius) {
        return switch (variant) {
            case DRO_EXTENSIVE -> String.format(Locale.US,
                    "宸磋タOlist23OD_RCSAA_DRO_EXTENSIVE_W%d_k1=%d_rawDemand_lambda%.2f",
                    w, k1, lambda);
            case LBBD_EXACT -> String.format(Locale.US,
                    "宸磋タOlist23OD_RCSAA_LBBD_EXACT_W%d_k1=%d_rawDemand_lambda%.2f",
                    w, k1, lambda);
            case LBBD_SEARCH -> String.format(Locale.US,
                    "巴西Olist23OD_RCSAA_LBBD_SEARCH_W%d_k1=%d_rawDemand_lambda%.2f_r%d",
                    w, k1, lambda, radius);
            case LBBD_PRIMAL_EXACT -> String.format(Locale.US,
                    "巴西Olist23OD_RCSAA_LBBD_PRIMAL_EXACT_W%d_k1=%d_rawDemand_lambda%.2f",
                    w, k1, lambda);
            case LBBD_PRIMAL_SEARCH -> String.format(Locale.US,
                    "巴西Olist23OD_RCSAA_LBBD_PRIMAL_SEARCH_W%d_k1=%d_rawDemand_lambda%.2f_r%d",
                    w, k1, lambda, radius);
            case ENUMERATE_EXACT -> String.format(Locale.US,
                    "宸磋タOlist23OD_RCSAA_ENUMERATE_EXACT_W%d_k1=%d_rawDemand_lambda%.2f",
                    w, k1, lambda);
            default -> throw new IllegalArgumentException("Unsupported single-variant runner variant: " + variant);
        };
    }
}
