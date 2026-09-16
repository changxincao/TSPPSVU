package Test;

import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarType;
import ilog.cplex.IloCplex;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import Basic.CovariateVector;
import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator.GenConfig;
import Helper.basicHelper.OutputManager;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.SAAModel;
import Model.Solution;
import Model.SolveMode;

public class BatchRunner {

    public static void run(String tag,
            List<String> lanes,
            ProcurementParams params,
            Config cfg,
            ExperimentBatches batches,
            Path outDir,
            GlobalSummaryCollector collector,
            GlobalTrialCollector trialCollector,
            GenConfig genCfg) throws Exception {

        System.out.println("Batch Size:" + batches.size());
        if (batches == null || batches.size() == 0) {
            throw new IllegalArgumentException("Empty experiment batches.");
        }
        Files.createDirectories(outDir);

        Path trialsCsv = outDir.resolve("trials_" + tag + ".csv");
        Path singleSampleCsv = outDir.resolve("trials_" + tag + "_single_sample_det.csv");
        Path summaryTxt = outDir.resolve("summary_" + tag + ".txt");
        Path summaryStatsCsv = outDir.resolve("summary_" + tag + "_stats.csv");

        // ============ (A) per-trial CSV header (mimic global + keep existing)
        // ============
        try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv)) {
            bw.write(String.join(",",
                    // config keys (mimic global_summary.csv)
                    "tag", "solveMode", "fillMissingDates", "standardizeTheta", "k1Lag", "kernelType", "bandwidthH",
                    "C_h", "lambda",

                    // trial keys
                    "trialId", "testIdx", "trainSize",

                    // existing metrics
                    "expectedObj", "realizedObj", "solveTimeSec", "selectedCount",
                    "oosTransportCost", "oosSpotCost", "oosPenaltyCost",
                    "sumW", "sumW2", "ESS", "top1W", "top5Wsum", "maxW_over_meanW",
                    "thetaDist_mean", "thetaDist_median", "thetaDist_min", "thetaDist_max",
                    "demandDist_mean", "demandDist_median", "demandDist_min", "demandDist_max",
                    "corrW_thetaDist", "corrW_demandDist", "corrTheta_demandDist",

                    // NEW: y info
                    "yBinary", "selectedCarriers", "sampleWeights"));
            bw.newLine();
        }

        if (cfg.enableTrialSingleSampleDeterministicEval && cfg.solveMode == SolveMode.SAA) {
            initSingleSampleCsv(singleSampleCsv);
        }

        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        EuclideanDistance distMetric = new EuclideanDistance();
        SAAModel model = new SAAModel();

        // lists across trials (for summary quantiles)
        List<Double> expectedList = new ArrayList<>();
        List<Double> realizedList = new ArrayList<>();
        List<Double> timeList = new ArrayList<>();

        List<Double> essList = new ArrayList<>();
        List<Double> corrWThetaList = new ArrayList<>();
        List<Double> corrWDemandList = new ArrayList<>();
        List<Double> corrThetaDemandList = new ArrayList<>();

        List<Double> thetaDistMeanList = new ArrayList<>();
        List<Double> demandDistMeanList = new ArrayList<>();

        // OOS cost components
        List<Double> transportList = new ArrayList<>();
        List<Double> spotList = new ArrayList<>();
        List<Double> penaltyList = new ArrayList<>();

        for (int t = 0; t < batches.size(); t++) {

            // ---- Output manager (per trial dir) ----
            OutputManager out = new OutputManager(tag, t, cfg, genCfg);

            List<Sample> trainRaw = batches.trainSets.get(t);
            CovariateVector thetaNowRaw = batches.thetaNowList.get(t);
            Sample test = batches.testSamples.get(t);
            int testIdx = batches.testIndex.get(t);

            System.out.println("第" + t + "个batch迭代,in sample数量为:" + trainRaw.size());

            // ---- deep copy training set (IMPORTANT) ----
            List<Sample> train = deepCopySamples(trainRaw);
            CovariateVector thetaNow = new CovariateVector(thetaNowRaw.values().clone());

            int thetaDim = (train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length);

            // ---- standardize ONLY using training set ----
            if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
                StandardScaler scaler = new StandardScaler(cfg.thetaScaling);
                scaler.fit(train, thetaDim);

                for (Sample s : train) {
                    s.theta = new CovariateVector(scaler.transform(s.theta.values()));
                }
                thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
            }

            // ---- weights ----
            if (cfg.solveMode == SolveMode.SAA) {
                double eq = 1.0 / Math.max(1, train.size());
                for (Sample s : train)
                    s.weight = eq;
            } else {
                wc.computeKernelWeights(train, thetaNow, cfg);
            }

            boolean isNan = false;
            for (Sample s : train) {
                if (Double.isNaN(s.weight)) {
                    isNan = true;
                }
            }
            if (isNan) {
                return;
            }

            double[] dTest = test.demand().clone();

            // ---- trial diagnostics (ESS/dist/corr) ----
            TrialDiag diag = TrialDiag.compute(train, thetaNow, dTest, distMetric);

            // ---- solve ----
            System.out.println("开始求解模型");
            Data data = new Data(lanes, train, thetaNow, params);

            // long st = System.nanoTime();
            // Solution sol = model.solve(data, cfg, out);
            // long ed = System.nanoTime();
            // double solveTimeSec = (ed - st) / 1e9;

            // RecourseEvaluator.RecourseEval rec = RecourseEvaluator.evaluate(params, sol.y, dTest);
            // double realized = rec.objValue;
            // double expected = sol.objValue;
            // int selectedCount = countSelected(sol.y);

            // // ---- y strings (NEW) ----
            // StringBuilder yBinSb = new StringBuilder();
            // yBinSb.append("[");
            // for (int i = 0; i < sol.y.length; i++) {
            //     yBinSb.append(sol.y[i] > 0.5 ? 1 : 0);
            //     if (i < sol.y.length - 1)
            //         yBinSb.append(",");
            // }
            // yBinSb.append("]");
            // String yBinary = yBinSb.toString();

            // StringBuilder selSb = new StringBuilder();
            // selSb.append("{");
            // boolean first = true;
            // for (int i = 0; i < sol.y.length; i++) {
            //     if (sol.y[i] > 0.5) {
            //         if (!first)
            //             selSb.append(",");
            //         selSb.append(i);
            //         first = false;
            //     }
            // }
            // selSb.append("}");
            // String selectedCarriers = selSb.toString();
            // String sampleWeights = buildSampleWeightsString(train);

            // // csv-quote for string columns (avoid comma issues)
            // String yBinaryQ = "\"" + yBinary.replace("\"", "\"\"") + "\"";
            // String selectedCarriersQ = "\"" + selectedCarriers.replace("\"", "\"\"") + "\"";
            // String sampleWeightsQ = "\"" + sampleWeights.replace("\"", "\"\"") + "\"";

            double lambdaVal = (cfg.solveMode == SolveMode.RCSAA ? cfg.lambda : Double.NaN);

            if (cfg.enableTrialSingleSampleDeterministicEval && cfg.solveMode == SolveMode.SAA) {
                appendSingleSampleDeterministicRows(
                        singleSampleCsv,
                        tag,
                        cfg,
                        params,
                        lanes,
                        trainRaw,
                        dTest,
                        t,
                        testIdx);
            }

            // collect lists
            // expectedList.add(expected);
            // realizedList.add(realized);
            // timeList.add(solveTimeSec);

            // essList.add(diag.ess);
            // corrWThetaList.add(diag.corrWTheta);
            // corrWDemandList.add(diag.corrWDemand);
            // corrThetaDemandList.add(diag.corrThetaDemand);

            // thetaDistMeanList.add(diag.thetaMean);
            // demandDistMeanList.add(diag.demMean);

            // transportList.add(rec.transportTotalCost);
            // spotList.add(rec.spotTotalCost);
            // penaltyList.add(rec.penaltyTotalCost);

            // // append per-trial record (NOW includes config keys + y)
            // try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv, java.nio.file.StandardOpenOption.APPEND)) {
            //     bw.write(String.format(Locale.US,
            //             // config keys
            //             "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%.6f," +
            //             // trial keys
            //                     "%d,%d,%d," +
            //                     // metrics
            //                     "%.10f,%.10f,%.6f,%d," +
            //                     "%.10f,%.10f,%.10f," +
            //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
            //                     "%.10f,%.10f,%.10f,%.10f," +
            //                     "%.10f,%.10f,%.10f,%.10f," +
            //                     "%.10f,%.10f,%.10f," +
            //                     // y
            //                     "%s,%s,%s%n",
            //             safe(tag),
            //             (cfg.solveMode == null ? "" : cfg.solveMode.name()),
            //             String.valueOf(cfg.fillMissingDates),
            //             String.valueOf(cfg.standardizeTheta),
            //             cfg.k1LagPeriods,
            //             (cfg.kernelType == null ? "" : cfg.kernelType.name()),
            //             cfg.bandwidthH,
            //             cfg.C_h,
            //             lambdaVal,

            //             t, testIdx, train.size(),

            //             expected, realized, solveTimeSec, selectedCount,

            //             rec.transportTotalCost, rec.spotTotalCost, rec.penaltyTotalCost,

            //             diag.sumW, diag.sumW2, diag.ess, diag.top1W, diag.top5Wsum, diag.maxOverMean,
            //             diag.thetaMean, diag.thetaMedian, diag.thetaMin, diag.thetaMax,
            //             diag.demMean, diag.demMedian, diag.demMin, diag.demMax,
            //             diag.corrWTheta, diag.corrWDemand, diag.corrThetaDemand,

            //             yBinaryQ, selectedCarriersQ, sampleWeightsQ));
            // }

            // // NEW: append to global trials table (cross-config)
            // if (trialCollector != null) {
            //     trialCollector.append(
            //             tag,
            //             cfg.solveMode,
            //             cfg.fillMissingDates,
            //             cfg.standardizeTheta,
            //             cfg.k1LagPeriods,
            //             cfg.kernelType,
            //             cfg.bandwidthH,
            //             cfg.C_h,
            //             lambdaVal,

            //             t,
            //             testIdx,
            //             train.size(),

            //             expected,
            //             realized,
            //             solveTimeSec,
            //             selectedCount,

            //             rec.transportTotalCost,
            //             rec.spotTotalCost,
            //             rec.penaltyTotalCost,

            //             diag.sumW,
            //             diag.sumW2,
            //             diag.ess,
            //             diag.top1W,
            //             diag.top5Wsum,
            //             diag.maxOverMean,

            //             diag.thetaMean,
            //             diag.thetaMedian,
            //             diag.thetaMin,
            //             diag.thetaMax,

            //             diag.demMean,
            //             diag.demMedian,
            //             diag.demMin,
            //             diag.demMax,

            //             diag.corrWTheta,
            //             diag.corrWDemand,
            //             diag.corrThetaDemand,

            //             yBinary,
            //             selectedCarriers);
            // }

            // System.out.println("trial=" + t
            //         + " testIdx=" + testIdx
            //         + " train=" + train.size()
            //         + " expObj=" + fmt(expected)
            //         + " realized=" + fmt(realized)
            //         + " ESS=" + fmt(diag.ess)
            //         + " corr(thetaDist,demandDist)=" + fmt(diag.corrThetaDemand)
            //         + " sel=" + selectedCount
            //         + " time=" + fmt(solveTimeSec) + "s");

            // // per-trial outputs (unchanged)
            // OutputManager.writeWeights(out.resultsDir.resolve("weights.csv"), data);
            // OutputManager.writeY(out.resultsDir.resolve("y.csv"), data, sol);
            // OutputManager.writeSummary(out.resultsDir.resolve("summary.csv"), sol, train.size(), realized, diag);

            // OutputManager.writeOOSLaneCosts(out.resultsDir.resolve("oos_lane_costs.csv"),
            //         lanes, dTest, rec.laneTransportQty, rec.laneSpotQty, rec.laneTransportCost, rec.laneSpotCost);
            // OutputManager.writeOOSCarrierPenaltyCosts(out.resultsDir.resolve("oos_carrier_penalty.csv"),
            //         data, sol.y, rec.carrierAssignedQty, rec.carrierPenaltyQty, rec.carrierPenaltyCost);
        }

        // ============ (B) summary stats across trials ============
        // SummaryStats expStats = SummaryStats.of(expectedList);
        // SummaryStats reaStats = SummaryStats.of(realizedList);
        // SummaryStats timeStats = SummaryStats.of(timeList);

        // SummaryStats essStats = SummaryStats.of(essList);
        // SummaryStats corrWThetaStats = SummaryStats.of(corrWThetaList);
        // SummaryStats corrWDemandStats = SummaryStats.of(corrWDemandList);
        // SummaryStats corrThetaDemandStats = SummaryStats.of(corrThetaDemandList);

        // SummaryStats thetaDistMeanStats = SummaryStats.of(thetaDistMeanList);
        // SummaryStats demandDistMeanStats = SummaryStats.of(demandDistMeanList);

        // SummaryStats transportStats = SummaryStats.of(transportList);
        // SummaryStats spotStats = SummaryStats.of(spotList);
        // SummaryStats penaltyStats = SummaryStats.of(penaltyList);

        // // ---- human-readable txt (unchanged) ----
        // try (BufferedWriter bw = Files.newBufferedWriter(summaryTxt)) {
        //     bw.write("tag=" + tag + "\n");
        //     bw.write("solveMode=" + cfg.solveMode + "\n");
        //     bw.write("trials=" + batches.size() + "\n");
        //     bw.write("fillMissingDates=" + cfg.fillMissingDates + "\n");
        //     bw.write("standardizeTheta=" + cfg.standardizeTheta + "\n");
        //     bw.write("k1LagPeriods=" + cfg.k1LagPeriods + "\n");
        //     bw.write("W=" + batches.size() + " (numTrials)\n");
        //     bw.write("kernelType=" + cfg.kernelType + ", bandwidthH=" + cfg.bandwidthH + "\n");
        //     bw.write("C_h=" + cfg.C_h + "\n\n");

        //     bw.write("[Expected objective]\n" + expStats.pretty() + "\n\n");
        //     bw.write("[Realized OOS cost]\n" + reaStats.pretty() + "\n\n");
        //     bw.write("[Solve time (sec)]\n" + timeStats.pretty() + "\n\n");

        //     bw.write("[ESS]\n" + essStats.pretty() + "\n\n");
        //     bw.write("[Corr(w, thetaDist)]\n" + corrWThetaStats.pretty() + "\n\n");
        //     bw.write("[Corr(w, demandDist)]\n" + corrWDemandStats.pretty() + "\n\n");
        //     bw.write("[Corr(thetaDist, demandDist)]\n" + corrThetaDemandStats.pretty() + "\n\n");

        //     bw.write("[thetaDist_mean across trials]\n" + thetaDistMeanStats.pretty() + "\n\n");
        //     bw.write("[demandDist_mean across trials]\n" + demandDistMeanStats.pretty() + "\n");

        //     bw.write("[OOS transport cost]\n" + transportStats.pretty() + "\n\n");
        //     bw.write("[OOS spot cost]\n" + spotStats.pretty() + "\n\n");
        //     bw.write("[OOS penalty cost]\n" + penaltyStats.pretty() + "\n\n");
        // }

        // // ---- machine-readable one-row summary CSV (unchanged) ----
        // try (BufferedWriter bw = Files.newBufferedWriter(summaryStatsCsv)) {
        //     bw.write(String.join(",",
        //             // config keys
        //             "tag", "solveMode", "fillMissingDates", "standardizeTheta", "k1LagPeriods", "kernelType",
        //             "bandwidthH", "C_h",
        //             // meta
        //             "nTrials",
        //             // expected
        //             "exp_mean", "exp_std", "exp_min", "exp_p20", "exp_p50", "exp_p80", "exp_p95", "exp_max",
        //             // realized
        //             "real_mean", "real_std", "real_min", "real_p20", "real_p50", "real_p80", "real_p95", "real_max",

        //             // cost breakdown stats
        //             "transport_mean", "transport_std", "transport_min", "transport_p20", "transport_p50",
        //             "transport_p80", "transport_p95", "transport_max",
        //             "spot_mean", "spot_std", "spot_min", "spot_p20", "spot_p50", "spot_p80", "spot_p95", "spot_max",
        //             "penalty_mean", "penalty_std", "penalty_min", "penalty_p20", "penalty_p50", "penalty_p80",
        //             "penalty_p95", "penalty_max",

        //             // time
        //             "time_mean", "time_std", "time_min", "time_p20", "time_p50", "time_p80", "time_p95", "time_max",
        //             // ESS
        //             "ess_mean", "ess_std", "ess_min", "ess_p20", "ess_p50", "ess_p80", "ess_p95", "ess_max",
        //             // corr(thetaDist,demandDist)
        //             "corrTD_mean", "corrTD_std", "corrTD_min", "corrTD_p20", "corrTD_p50", "corrTD_p80", "corrTD_p95",
        //             "corrTD_max",
        //             // corr(w,thetaDist) & corr(w,demandDist)
        //             "corrWT_mean", "corrWT_std", "corrWT_min", "corrWT_p20", "corrWT_p50", "corrWT_p80", "corrWT_p95",
        //             "corrWT_max",
        //             "corrWD_mean", "corrWD_std", "corrWD_min", "corrWD_p20", "corrWD_p50", "corrWD_p80", "corrWD_p95",
        //             "corrWD_max",
        //             // distance means across trials
        //             "thetaDistMean_mean", "thetaDistMean_p50", "demandDistMean_mean", "demandDistMean_p50"));
        //     bw.newLine();

        //     bw.write(String.format(Locale.US,
        //             "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%d," +
        //             // expected
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
        //                     // realized
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +

        //                     // transport / spot / penalty
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +

        //                     // time
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
        //                     // ess
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
        //                     // corrTD
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
        //                     // corrWT
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
        //                     // corrWD
        //                     "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
        //                     // dist means
        //                     "%.10f,%.10f,%.10f,%.10f%n",
        //             safe(tag),
        //             (cfg.solveMode == null ? "" : cfg.solveMode.name()),
        //             String.valueOf(cfg.fillMissingDates),
        //             String.valueOf(cfg.standardizeTheta),
        //             cfg.k1LagPeriods,
        //             (cfg.kernelType == null ? "" : cfg.kernelType.name()),
        //             cfg.bandwidthH,
        //             cfg.C_h,
        //             batches.size(),

        //             // expected
        //             expStats.mean, expStats.std, expStats.min, expStats.p20, expStats.p50, expStats.p80, expStats.p95,
        //             expStats.max,
        //             // realized
        //             reaStats.mean, reaStats.std, reaStats.min, reaStats.p20, reaStats.p50, reaStats.p80, reaStats.p95,
        //             reaStats.max,

        //             // transport
        //             transportStats.mean, transportStats.std, transportStats.min, transportStats.p20, transportStats.p50,
        //             transportStats.p80, transportStats.p95, transportStats.max,
        //             // spot
        //             spotStats.mean, spotStats.std, spotStats.min, spotStats.p20, spotStats.p50, spotStats.p80,
        //             spotStats.p95, spotStats.max,
        //             // penalty
        //             penaltyStats.mean, penaltyStats.std, penaltyStats.min, penaltyStats.p20, penaltyStats.p50,
        //             penaltyStats.p80, penaltyStats.p95, penaltyStats.max,

        //             // time
        //             timeStats.mean, timeStats.std, timeStats.min, timeStats.p20, timeStats.p50, timeStats.p80,
        //             timeStats.p95, timeStats.max,
        //             // ess
        //             essStats.mean, essStats.std, essStats.min, essStats.p20, essStats.p50, essStats.p80, essStats.p95,
        //             essStats.max,

        //             // corrTD
        //             corrThetaDemandStats.mean, corrThetaDemandStats.std, corrThetaDemandStats.min,
        //             corrThetaDemandStats.p20,
        //             corrThetaDemandStats.p50, corrThetaDemandStats.p80, corrThetaDemandStats.p95,
        //             corrThetaDemandStats.max,

        //             // corrWT
        //             corrWThetaStats.mean, corrWThetaStats.std, corrWThetaStats.min, corrWThetaStats.p20,
        //             corrWThetaStats.p50, corrWThetaStats.p80, corrWThetaStats.p95, corrWThetaStats.max,

        //             // corrWD
        //             corrWDemandStats.mean, corrWDemandStats.std, corrWDemandStats.min, corrWDemandStats.p20,
        //             corrWDemandStats.p50, corrWDemandStats.p80, corrWDemandStats.p95, corrWDemandStats.max,

        //             // dist means
        //             thetaDistMeanStats.mean, thetaDistMeanStats.p50,
        //             demandDistMeanStats.mean, demandDistMeanStats.p50));
        // }

        // // ---- Global summary (cross-config) (unchanged) ----
        // if (collector != null) {
        //     collector.append(
        //             tag,
        //             cfg.solveMode,
        //             cfg.fillMissingDates,
        //             cfg.standardizeTheta,
        //             cfg.k1LagPeriods,
        //             cfg.kernelType,
        //             cfg.bandwidthH,
        //             cfg.C_h,
        //             (cfg.solveMode == SolveMode.RCSAA ? cfg.lambda : Double.NaN),
        //             batches.size(),
        //             new GlobalSummaryCollector.Stats(expStats.n, expStats.mean, expStats.std, expStats.min,
        //                     expStats.max,
        //                     expStats.p20, expStats.p50, expStats.p80, expStats.p95),
        //             new GlobalSummaryCollector.Stats(reaStats.n, reaStats.mean, reaStats.std, reaStats.min,
        //                     reaStats.max,
        //                     reaStats.p20, reaStats.p50, reaStats.p80, reaStats.p95),
        //             new GlobalSummaryCollector.Stats(essStats.n, essStats.mean, essStats.std, essStats.min,
        //                     essStats.max,
        //                     essStats.p20, essStats.p50, essStats.p80, essStats.p95),
        //             new GlobalSummaryCollector.Stats(corrThetaDemandStats.n, corrThetaDemandStats.mean,
        //                     corrThetaDemandStats.std,
        //                     corrThetaDemandStats.min, corrThetaDemandStats.max,
        //                     corrThetaDemandStats.p20, corrThetaDemandStats.p50, corrThetaDemandStats.p80,
        //                     corrThetaDemandStats.p95),

        //             transportStats.mean,
        //             spotStats.mean,
        //             penaltyStats.mean,

        //             timeStats.mean);
        // }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static void initSingleSampleCsv(Path csv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
            bw.write(String.join(",",
                    "tag", "solveMode", "fillMissingDates", "standardizeTheta", "k1Lag", "kernelType", "bandwidthH",
                    "C_h",
                    "trialId", "testIdx", "trainSize",
                    "samplePos", "sampleId",
                    "expectedObj", "realizedObj", "solveTimeSec", "selectedCount",
                    "oosTransportCost", "oosSpotCost", "oosPenaltyCost",
                    "yBinary", "selectedCarriers"));
            bw.newLine();
        }
    }

    private static void appendSingleSampleDeterministicRows(
            Path csv,
            String tag,
            Config cfg,
            ProcurementParams params,
            List<String> lanes,
            List<Sample> trainRaw,
            double[] dTest,
            int trialId,
            int testIdx) throws Exception {

        if (trainRaw == null || trainRaw.isEmpty()) {
            return;
        }

        SAAModel model = new SAAModel();

        try (BufferedWriter bw = Files.newBufferedWriter(csv, java.nio.file.StandardOpenOption.APPEND)) {
            for (int k = 0; k < trainRaw.size(); k++) {
                Sample s0 = trainRaw.get(k);

                List<Sample> singleTrain = new ArrayList<>(1);
                CovariateVector thetaCopy = new CovariateVector(s0.theta.values().clone());
                singleTrain.add(new Sample(s0.id, s0.period, thetaCopy, 1.0));
                CovariateVector thetaNow = new CovariateVector(s0.theta.values().clone());
                Data singleData = new Data(lanes, singleTrain, thetaNow, params);

                long st = System.nanoTime();
                Solution sol = model.solve(singleData, cfg, null);
                long ed = System.nanoTime();
                double solveTimeSec = (ed - st) / 1e9;

                RecourseEvaluator.RecourseEval rec = RecourseEvaluator.evaluate(params, sol.y, dTest);
                double expected = sol.objValue;
                double realized = rec.objValue;
                int selectedCount = countSelected(sol.y);

                String yBinary = buildYBinaryString(sol.y);
                String selectedCarriers = buildSelectedCarriersString(sol.y);
                String yBinaryQ = "\"" + yBinary.replace("\"", "\"\"") + "\"";
                String selectedCarriersQ = "\"" + selectedCarriers.replace("\"", "\"\"") + "\"";

                bw.write(String.format(Locale.US,
                        "%s,%s,%s,%s,%d,%s,%.6f,%.6f," +
                                "%d,%d,%d," +
                                "%d,%s," +
                                "%.10f,%.10f,%.6f,%d," +
                                "%.10f,%.10f,%.10f," +
                                "%s,%s%n",
                        safe(tag),
                        (cfg.solveMode == null ? "" : cfg.solveMode.name()),
                        String.valueOf(cfg.fillMissingDates),
                        String.valueOf(cfg.standardizeTheta),
                        cfg.k1LagPeriods,
                        (cfg.kernelType == null ? "" : cfg.kernelType.name()),
                        cfg.bandwidthH,
                        cfg.C_h,

                        trialId, testIdx, trainRaw.size(),
                        k, safe(String.valueOf(s0.id)),

                        expected, realized, solveTimeSec, selectedCount,
                        rec.transportTotalCost, rec.spotTotalCost, rec.penaltyTotalCost,
                        yBinaryQ, selectedCarriersQ));
            }
        }
    }

    // ====================== NEW: Trial diagnostics ======================

    public static class TrialDiag {
        double sumW, sumW2, ess, top1W, top5Wsum, maxOverMean;

        double thetaMean, thetaMedian, thetaMin, thetaMax;
        double demMean, demMedian, demMin, demMax;

        double corrWTheta, corrWDemand, corrThetaDemand;

        static TrialDiag compute(List<Sample> train,
                CovariateVector thetaNow,
                double[] dTest,
                EuclideanDistance distMetric) {

            TrialDiag td = new TrialDiag();
            int n = train.size();
            if (n == 0)
                return td;

            // normalize weights for diagnostics
            double sum = 0.0;
            double maxW = Double.NEGATIVE_INFINITY;
            for (Sample s : train) {
                double w = s.weight;
                if (!Double.isFinite(w))
                    continue;
                sum += w;
                maxW = Math.max(maxW, w);
            }
            td.sumW = sum;
            double meanW = sum / Math.max(1, n);
            td.maxOverMean = (meanW > 0 ? maxW / meanW : Double.NaN);

            List<Double> wNorm = new ArrayList<>(n);
            if (sum > 0 && Double.isFinite(sum)) {
                for (Sample s : train) {
                    double w = s.weight;
                    if (!Double.isFinite(w))
                        w = 0.0;
                    wNorm.add(w / sum);
                }
            } else {
                double eq = 1.0 / Math.max(1, n);
                for (int i = 0; i < n; i++)
                    wNorm.add(eq);
            }

            double sumW2 = 0.0;
            for (double wn : wNorm)
                sumW2 += wn * wn;
            td.sumW2 = sumW2;
            td.ess = (sumW2 > 0 ? 1.0 / sumW2 : Double.NaN);

            List<Double> sorted = new ArrayList<>(wNorm);
            sorted.sort(Comparator.reverseOrder());
            td.top1W = (sorted.isEmpty() ? Double.NaN : sorted.get(0));
            td.top5Wsum = 0.0;
            for (int i = 0; i < Math.min(5, sorted.size()); i++)
                td.top5Wsum += sorted.get(i);

            List<Double> thetaDist = new ArrayList<>(n);
            List<Double> demandDist = new ArrayList<>(n);

            for (Sample s : train) {
                double dt = distMetric.distance(s.theta.values(), thetaNow.values());
                thetaDist.add(dt);

                double dd = l2DemandDistance(s.demand(), dTest);
                demandDist.add(dd);
            }

            td.thetaMean = mean(thetaDist);
            td.thetaMedian = median(thetaDist);
            td.thetaMin = min(thetaDist);
            td.thetaMax = max(thetaDist);

            td.demMean = mean(demandDist);
            td.demMedian = median(demandDist);
            td.demMin = min(demandDist);
            td.demMax = max(demandDist);

            td.corrWTheta = pearson(wNorm, thetaDist);
            td.corrWDemand = pearson(wNorm, demandDist);
            td.corrThetaDemand = pearson(thetaDist, demandDist);

            return td;
        }

        private static double l2DemandDistance(double[] a, double[] b) {
            int m = Math.min(a.length, b.length);
            double s = 0.0;
            for (int i = 0; i < m; i++) {
                double d = a[i] - b[i];
                s += d * d;
            }
            return Math.sqrt(s);
        }

        private static double mean(List<Double> xs) {
            if (xs == null || xs.isEmpty())
                return Double.NaN;
            double s = 0.0;
            int c = 0;
            for (double v : xs) {
                if (!Double.isFinite(v))
                    continue;
                s += v;
                c++;
            }
            return (c == 0 ? Double.NaN : s / c);
        }

        private static double median(List<Double> xs) {
            if (xs == null || xs.isEmpty())
                return Double.NaN;
            List<Double> a = new ArrayList<>();
            for (double v : xs)
                if (Double.isFinite(v))
                    a.add(v);
            if (a.isEmpty())
                return Double.NaN;
            a.sort(Double::compareTo);
            int n = a.size();
            if (n % 2 == 1)
                return a.get(n / 2);
            return 0.5 * (a.get(n / 2 - 1) + a.get(n / 2));
        }

        private static double min(List<Double> xs) {
            double m = Double.POSITIVE_INFINITY;
            boolean ok = false;
            for (double v : xs) {
                if (!Double.isFinite(v))
                    continue;
                m = Math.min(m, v);
                ok = true;
            }
            return ok ? m : Double.NaN;
        }

        private static double max(List<Double> xs) {
            double m = Double.NEGATIVE_INFINITY;
            boolean ok = false;
            for (double v : xs) {
                if (!Double.isFinite(v))
                    continue;
                m = Math.max(m, v);
                ok = true;
            }
            return ok ? m : Double.NaN;
        }

        private static double pearson(List<Double> xs, List<Double> ys) {
            int n = Math.min(xs.size(), ys.size());
            if (n < 2)
                return Double.NaN;

            double sx = 0, sy = 0;
            int c = 0;
            for (int i = 0; i < n; i++) {
                double x = xs.get(i), y = ys.get(i);
                if (!Double.isFinite(x) || !Double.isFinite(y))
                    continue;
                sx += x;
                sy += y;
                c++;
            }
            if (c < 2)
                return Double.NaN;
            double mx = sx / c, my = sy / c;

            double vx = 0, vy = 0, cov = 0;
            for (int i = 0; i < n; i++) {
                double x = xs.get(i), y = ys.get(i);
                if (!Double.isFinite(x) || !Double.isFinite(y))
                    continue;
                double dx = x - mx, dy = y - my;
                vx += dx * dx;
                vy += dy * dy;
                cov += dx * dy;
            }
            if (vx <= 1e-18 || vy <= 1e-18)
                return Double.NaN;
            return cov / Math.sqrt(vx * vy);
        }
    }

    // ====================== utilities ======================

    private static String fmt(double v) {
        return String.format(Locale.US, "%.6f", v);
    }

    private static String buildYBinaryString(double[] y) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < y.length; i++) {
            sb.append(y[i] > 0.5 ? 1 : 0);
            if (i < y.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String buildSelectedCarriersString(double[] y) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (int i = 0; i < y.length; i++) {
            if (y[i] > 0.5) {
                if (!first) {
                    sb.append(",");
                }
                sb.append(i);
                first = false;
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String buildSampleWeightsString(List<Sample> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(String.format(Locale.US, "%.10f", samples.get(i).weight));
        }
        sb.append("]");
        return sb.toString();
    }

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y)
            if (v > 0.5)
                c++;
        return c;
    }

    public static List<Sample> deepCopySamples(List<Sample> src) {
        List<Sample> out = new ArrayList<>(src.size());
        for (Sample s : src) {
            CovariateVector thetaCopy = new CovariateVector(s.theta.values().clone());
            Sample c = new Sample(s.id, s.period, thetaCopy, s.weight);
            out.add(c);
        }
        return out;
    }

    // ====================== stats ======================

    private static class SummaryStats {
        final int n;
        final double mean, std, min, max, p20, p50, p80, p95;

        private SummaryStats(int n, double mean, double std, double min, double max,
                double p20, double p50, double p80, double p95) {
            this.n = n;
            this.mean = mean;
            this.std = std;
            this.min = min;
            this.max = max;
            this.p20 = p20;
            this.p50 = p50;
            this.p80 = p80;
            this.p95 = p95;
        }

        static SummaryStats of(List<Double> xs) {
            if (xs == null || xs.isEmpty()) {
                return new SummaryStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }
            double[] a = xs.stream().filter(Double::isFinite).mapToDouble(Double::doubleValue).toArray();
            int n = a.length;
            if (n == 0) {
                return new SummaryStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            }

            double sum = 0;
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double v : a) {
                sum += v;
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            double mean = sum / n;

            double var = 0;
            for (double v : a) {
                double d = v - mean;
                var += d * d;
            }
            double std = Math.sqrt(var / Math.max(1, n - 1));

            Arrays.sort(a);
            double p20 = q(a, 0.20);
            double p50 = q(a, 0.50);
            double p80 = q(a, 0.80);
            double p95 = q(a, 0.95);

            return new SummaryStats(n, mean, std, min, max, p20, p50, p80, p95);
        }

        private static double q(double[] s, double q) {
            if (s.length == 1)
                return s[0];
            double pos = q * (s.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            if (lo == hi)
                return s[lo];
            double w = pos - lo;
            return s[lo] * (1 - w) + s[hi] * w;
        }

        String pretty() {
            return "n=" + n
                    + "\nmean=" + mean
                    + "\nstd=" + std
                    + "\nmin=" + min
                    + "\nP20=" + p20
                    + "\nP50=" + p50
                    + "\nP80=" + p80
                    + "\nP95=" + p95
                    + "\nmax=" + max;
        }
    }

    // ====================== OOS recourse evaluator ======================

    /**
     * Solve second-stage LP with fixed y and realized demand dTest.
     * Uses your model structure:
     * min Σ r_ij x_ij + Σ e_j s_j + Σ h_i u_i
     * s.t. Σ_i x_ij + s_j {= or >=} d_j
     * p_i y_i - u_i <= Σ_j x_ij <= M_i y_i
     * 0 <= x_ij <= q_ij (and 0 if ineligible)
     * s_j >= 0, u_i >= 0
     */
    public static class RecourseEvaluator {

        public static class RecourseEval {
            public double objValue;
            public double transportTotalCost;
            public double spotTotalCost;
            public double penaltyTotalCost;

            public double[] laneTransportQty; // Σ_i x_ij
            public double[] laneSpotQty; // s_j
            public double[] laneTransportCost; // Σ_i r_ij x_ij
            public double[] laneSpotCost; // e_j s_j

            public double[] carrierAssignedQty; // Σ_j x_ij
            public double[] carrierPenaltyQty; // u_i
            public double[] carrierPenaltyCost; // h_i u_i
        }

        /** Historical compatibility overload: retain the original >= convention. */
        static RecourseEval evaluate(ProcurementParams params, double[] y, double[] dTest) throws IloException {
            return evaluate(params, y, dTest, false);
        }

        /**
         * Evaluate fixed-y recourse under the same demand relation used by the
         * first-stage model.  New TRB reviewer runners pass
         * {@code cfg.enforceDemandEquality}; old callers keep the historical
         * three-argument >= behavior.
         */
        public static RecourseEval evaluate(ProcurementParams params,
                                     double[] y,
                                     double[] dTest,
                                     boolean enforceDemandEquality) throws IloException {
            int I = params.I, J = params.J;

            RecourseEval out = new RecourseEval();
            out.laneTransportQty = new double[J];
            out.laneSpotQty = new double[J];
            out.laneTransportCost = new double[J];
            out.laneSpotCost = new double[J];
            out.carrierAssignedQty = new double[I];
            out.carrierPenaltyQty = new double[I];
            out.carrierPenaltyCost = new double[I];

            try (IloCplex cplex = new IloCplex()) {
                cplex.setOut(null);

                IloNumVar[][] x = new IloNumVar[I][J];
                for (int i = 0; i < I; i++) {
                    for (int j = 0; j < J; j++) {
                        double ub = params.eligible[i][j] ? params.q[i][j] : 0.0;
                        x[i][j] = cplex.numVar(0.0, ub, IloNumVarType.Float, "x_" + i + "_" + j);
                    }
                }

                IloNumVar[] s = new IloNumVar[J];
                for (int j = 0; j < J; j++)
                    s[j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float, "s_" + j);

                IloNumVar[] u = new IloNumVar[I];
                for (int i = 0; i < I; i++)
                    u[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float, "u_" + i);

                // objective
                IloLinearNumExpr obj = cplex.linearNumExpr();
                for (int i = 0; i < I; i++) {
                    for (int j = 0; j < J; j++) {
                        if (params.eligible[i][j])
                            obj.addTerm(params.r[i][j], x[i][j]);
                    }
                }
                for (int j = 0; j < J; j++)
                    obj.addTerm(params.e[j], s[j]);
                for (int i = 0; i < I; i++)
                    obj.addTerm(params.h[i], u[i]);
                cplex.addMinimize(obj);

                // demand constraints
                for (int j = 0; j < J; j++) {
                    IloLinearNumExpr lhs = cplex.linearNumExpr();
                    for (int i = 0; i < I; i++)
                        lhs.addTerm(1.0, x[i][j]);
                    lhs.addTerm(1.0, s[j]);
                    if (enforceDemandEquality) {
                        cplex.addEq(lhs, dTest[j], "demand_" + j);
                    } else {
                        cplex.addGe(lhs, dTest[j], "demand_" + j);
                    }
                }

                // MQC + upper
                for (int i = 0; i < I; i++) {
                    IloLinearNumExpr sumX = cplex.linearNumExpr();
                    for (int j = 0; j < J; j++)
                        sumX.addTerm(1.0, x[i][j]);

                    double yi = (y[i] > 0.5) ? 1.0 : 0.0;

                    IloLinearNumExpr left = cplex.linearNumExpr(params.p[i] * yi);
                    left.addTerm(-1.0, u[i]);
                    cplex.addLe(left, sumX, "mqcL_" + i);

                    cplex.addLe(sumX, params.M[i] * yi, "mqcU_" + i);
                }

                if (!cplex.solve()) {
                    out.objValue = Double.POSITIVE_INFINITY;
                    out.transportTotalCost = Double.NaN;
                    out.spotTotalCost = Double.NaN;
                    out.penaltyTotalCost = Double.NaN;
                    return out;
                }

                // extract + breakdown
                double transportTotal = 0.0, spotTotal = 0.0, penaltyTotal = 0.0;

                for (int j = 0; j < J; j++) {
                    double sumXj = 0.0;
                    double costXj = 0.0;
                    for (int i = 0; i < I; i++) {
                        double xv = cplex.getValue(x[i][j]);
                        sumXj += xv;
                        if (params.eligible[i][j])
                            costXj += params.r[i][j] * xv;
                        out.carrierAssignedQty[i] += xv;
                    }
                    double sv = cplex.getValue(s[j]);

                    out.laneTransportQty[j] = sumXj;
                    out.laneSpotQty[j] = sv;

                    out.laneTransportCost[j] = costXj;
                    out.laneSpotCost[j] = params.e[j] * sv;

                    transportTotal += out.laneTransportCost[j];
                    spotTotal += out.laneSpotCost[j];
                }

                for (int i = 0; i < I; i++) {
                    double uv = cplex.getValue(u[i]);
                    out.carrierPenaltyQty[i] = uv;
                    out.carrierPenaltyCost[i] = params.h[i] * uv;
                    penaltyTotal += out.carrierPenaltyCost[i];
                }

                out.objValue = cplex.getObjValue();
                out.transportTotalCost = transportTotal;
                out.spotTotalCost = spotTotal;
                out.penaltyTotalCost = penaltyTotal;

                return out;
            }
        }
    }

}
