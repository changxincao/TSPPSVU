package Test;

import Basic.CovariateVector;
import Basic.Data;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator.GenConfig;
import Helper.basicHelper.OutputManager;
import Helper.calculateHelper.*;
import Model.DROModel;
import Model.Solution;
import Model.SolveMode;
import Test.BatchRunner.RecourseEvaluator;
import Test.BatchRunner.TrialDiag;
import ilog.concert.*;
import ilog.cplex.IloCplex;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class DROBatchRunner {

    public static void run(String tag,
                           List<String> lanes,
                           ProcurementParams params,
                           Config cfg,
                           ExperimentBatches batches,
                           Path outDir,
                           GlobalSummaryCollector collector,
                           GlobalTrialCollector trialCollector,
                           GenConfig genCfg) throws Exception {

        if (batches == null || batches.size() == 0) throw new IllegalArgumentException("Empty experiment batches.");
        Files.createDirectories(outDir);

        Path trialsCsv = outDir.resolve("trials_" + tag + ".csv");
        Path summaryTxt = outDir.resolve("summary_" + tag + ".txt");
        Path summaryStatsCsv = outDir.resolve("summary_" + tag + "_stats.csv");

        // ============ (A) per-trial CSV header ============
        // 注意：不新增任何列。expectedObj 在下面会写成“基于解 y 的经验期望成本（按权重）”
       
        try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv)) {
            bw.write(String.join(",",
                    // config keys (mimic global_summary.csv)
                    "tag", "solveMode", "fillMissingDates", "standardizeTheta", "k1Lag", "kernelType", "bandwidthH",
                    "C_h", "lambda",

                    // trial keys
                    "trialId", "testIdx", "trainSize",

                    // existing metrics
                    "expectedObj", "modelObj", "realizedObj", "solveTimeSec", "selectedCount",
                    "oosTransportCost", "oosSpotCost", "oosPenaltyCost",
                    "sumW", "sumW2", "ESS", "top1W", "top5Wsum", "maxW_over_meanW",
                    "thetaDist_mean", "thetaDist_median", "thetaDist_min", "thetaDist_max",
                    "demandDist_mean", "demandDist_median", "demandDist_min", "demandDist_max",
                    "corrW_thetaDist", "corrW_demandDist", "corrTheta_demandDist",

                    // NEW: y info
                    "yBinary", "selectedCarriers", "sampleWeights"));
            bw.newLine();
        }
        KernelFunction kernel = WeightCalculator.buildKernel(cfg);
        WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
        EuclideanDistance distMetric = new EuclideanDistance();
        DROModel droModel = new DROModel();

        // lists across trials (for summary quantiles)
        // lists across trials (for summary quantiles)
        List<Double> expectedList = new ArrayList<>();
        List<Double> modelObjList = new ArrayList<>();
        List<Double> realizedList = new ArrayList<>();
        List<Double> timeList = new ArrayList<>();

        List<Double> essList = new ArrayList<>();
        List<Double> corrWThetaList = new ArrayList<>();
        List<Double> corrWDemandList = new ArrayList<>();
        List<Double> corrThetaDemandList = new ArrayList<>();

        // （可选）也把 trial 里的距离均值收集起来做分位数
        List<Double> thetaDistMeanList = new ArrayList<>();
        List<Double> demandDistMeanList = new ArrayList<>();

        //样本外成本
        List<Double>  transportList = new ArrayList<>();
        List<Double>  spotList = new ArrayList<>();
        List<Double>  penaltyList = new ArrayList<>();

        System.out.println("Batch Size: " + batches.size());

        for (int t = 0; t < batches.size(); t++) {
            // ---- Output manager (per trial dir) ----
            OutputManager out = new OutputManager(tag, t, cfg, genCfg);

            List<Sample> trainRaw = batches.trainSets.get(t);
            CovariateVector thetaNowRaw = batches.thetaNowList.get(t);
            Sample test = batches.testSamples.get(t);
            int testIdx = batches.testIndex.get(t);

            System.out.println("第" + t + "个batch迭代,in sample数量为:" + trainRaw.size());

            // ---- deep copy training set (IMPORTANT) ----
            // 目的：避免 WeightCalculator / 标准化修改原始样本对象，影响后续 trial
            List<Sample> train = deepCopySamples(trainRaw);
            CovariateVector thetaNow = new CovariateVector(thetaNowRaw.values().clone());

            int thetaDim = (train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length);

            // ---- standardize ONLY using training set ----
            // 注意：只用训练集 fit scaler，避免 data leakage
            if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
                StandardScaler scaler = new StandardScaler();
                scaler.fit(train, thetaDim);

                for (Sample s : train) {
                    s.theta = new CovariateVector(scaler.transform(s.theta.values()));
                }
                thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
            }

            // ---- kernel weights on training set ----
            // 这里会把权重写回 train.sample.weight（通常会归一化，但仍用 weightedMean 做稳健处理）
            wc.computeKernelWeights(train, thetaNow, cfg);
            boolean skipThisTrial = false;
            for (Sample s : train) {
                if (Double.isNaN(s.weight) || Double.isInfinite(s.weight) || s.weight <= 0.0) {
                    skipThisTrial = true;
                    break;
                }
            }
            if (skipThisTrial) {
                // WeightCalculator applies the shared formal floor and
                // renormalization. Reaching this branch now means that invariant
                // was violated; never silently shrink the paired trial set.
                throw new IllegalStateException(
                        "Non-positive/invalid kernel weight at trial=" + t
                                + ", testIdx=" + testIdx + ", C_h=" + cfg.C_h
                                + ", kernel=" + cfg.kernelType);
            }
            
            double[] dTest = test.demand().clone();

            // ---- trial diagnostics (ESS/dist/corr) ----
            // 用 train 的权重/距离/与 test 的需求距离做诊断（筛参用）
            TrialDiag diag = TrialDiag.compute(train, thetaNow, dTest, distMetric);

            // ---- build data & solve DRO/RCSAA master ----
            Data data = new Data(lanes, train, thetaNow, params);

            System.out.println("开始求解模型");
            long st = System.nanoTime();
            Solution sol = droModel.solve(data, cfg);
            long ed = System.nanoTime();
            double solveTimeSec = (ed - st) / 1e9;

            // ---- realized OOS cost (test demand) ----
            RecourseEvaluator.RecourseEval rec = RecourseEvaluator.evaluate(
                    params, sol.y, dTest, cfg.enforceDemandEquality);
            double realized = rec.objValue;
            // ------------------------------------------------------------------
            // [CHANGED] expectedObj 的定义：
            // 不再用 sol.objValue（那是 DRO/RCSAA 的鲁棒目标值，含稳健项），
            // 而是用解出来的 y，在训练集上按权重计算的经验期望 recourse 成本：
            // expectedObj = Σ_w (pi_w * Q(y, d^w)) / Σ_w pi_w
            // 这样 expectedObj 与 SAA/CSAA 的“经验期望”口径一致，更便于横向比较。
            // ------------------------------------------------------------------
            double droObj = sol.objValue; // [NEW COMMENT] 保留作 debug，不写入 trialsCsv（不改 header）
            double[] qTrain = empiricalRecourseCosts(
                    params, train, sol.y, cfg.enforceDemandEquality);           // Q(y, d^w) for each training sample
            double expected = weightedMean(qTrain, train);                      // 经验期望（按权重）
            // double empVar = weightedVariance(qTrain, train);                 // 如需检查方差，可打开（不输出，不新增列）

            int selectedCount = countSelected(sol.y);

            StringBuilder yBinSb = new StringBuilder();
            yBinSb.append("[");
            for (int i = 0; i < sol.y.length; i++) {
                yBinSb.append(sol.y[i] > 0.5 ? 1 : 0);
                if (i < sol.y.length - 1) {
                    yBinSb.append(",");
                }
            }
            yBinSb.append("]");
            String yBinary = yBinSb.toString();

            StringBuilder selSb = new StringBuilder();
            selSb.append("{");
            boolean first = true;
            for (int i = 0; i < sol.y.length; i++) {
                if (sol.y[i] > 0.5) {
                    if (!first) {
                        selSb.append(",");
                    }
                    selSb.append(i);
                    first = false;
                }
            }
            selSb.append("}");
            String selectedCarriers = selSb.toString();
            String sampleWeights = buildSampleWeightsString(train);
            String yBinaryQ = "\"" + yBinary.replace("\"", "\"\"") + "\"";
            String selectedCarriersQ = "\"" + selectedCarriers.replace("\"", "\"\"") + "\"";
            String sampleWeightsQ = "\"" + sampleWeights.replace("\"", "\"\"") + "\"";

            double lambdaVal = (cfg.solveMode == SolveMode.RCSAA ? cfg.lambda : Double.NaN);

            // collect lists
            expectedList.add(expected);
            modelObjList.add(droObj);
            realizedList.add(realized);
            timeList.add(solveTimeSec);

            essList.add(diag.ess);
            corrWThetaList.add(diag.corrWTheta);
            corrWDemandList.add(diag.corrWDemand);
            corrThetaDemandList.add(diag.corrThetaDemand);

            thetaDistMeanList.add(diag.thetaMean);
            demandDistMeanList.add(diag.demMean);
            transportList.add(rec.transportTotalCost);

            spotList.add(rec.spotTotalCost);

            penaltyList.add(rec.penaltyTotalCost);
            // append per-trial record (header 不变，只是 expectedObj 的数值口径改变)
         // append per-trial record
            try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv, java.nio.file.StandardOpenOption.APPEND)) {
                bw.write(String.format(Locale.US,
                        "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%.6f," +
                                "%d,%d,%d," +
                                "%.10f,%.10f,%.10f,%.6f,%d," +
                                "%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f," +
                                "%s,%s,%s%n",
                        (tag == null ? "" : tag),
                        (cfg.solveMode == null ? "" : cfg.solveMode.name()),
                        String.valueOf(cfg.fillMissingDates),
                        String.valueOf(cfg.standardizeTheta),
                        cfg.k1LagPeriods,
                        (cfg.kernelType == null ? "" : cfg.kernelType.name()),
                        cfg.bandwidthH,
                        cfg.C_h,
                        lambdaVal,

                        t, testIdx, train.size(),
                        expected, droObj, realized, solveTimeSec, selectedCount,

                        rec.transportTotalCost, rec.spotTotalCost, rec.penaltyTotalCost,

                        diag.sumW, diag.sumW2, diag.ess, diag.top1W, diag.top5Wsum, diag.maxOverMean,
                        diag.thetaMean, diag.thetaMedian, diag.thetaMin, diag.thetaMax,
                        diag.demMean, diag.demMedian, diag.demMin, diag.demMax,
                        diag.corrWTheta, diag.corrWDemand, diag.corrThetaDemand,
                        yBinaryQ, selectedCarriersQ, sampleWeightsQ
                ));
            }
            if (trialCollector != null) {
                trialCollector.append(
                        tag,
                        cfg.solveMode,
                        cfg.fillMissingDates,
                        cfg.standardizeTheta,
                        cfg.k1LagPeriods,
                        cfg.kernelType,
                        cfg.bandwidthH,
                        cfg.C_h,
                        lambdaVal,

                        t,
                        testIdx,
                        train.size(),

                        expected,
                        droObj,
                        realized,
                        solveTimeSec,
                        selectedCount,

                        rec.transportTotalCost,
                        rec.spotTotalCost,
                        rec.penaltyTotalCost,

                        diag.sumW,
                        diag.sumW2,
                        diag.ess,
                        diag.top1W,
                        diag.top5Wsum,
                        diag.maxOverMean,

                        diag.thetaMean,
                        diag.thetaMedian,
                        diag.thetaMin,
                        diag.thetaMax,

                        diag.demMean,
                        diag.demMedian,
                        diag.demMin,
                        diag.demMax,

                        diag.corrWTheta,
                        diag.corrWDemand,
                        diag.corrThetaDemand,

                        yBinary,
                        selectedCarriers
                );
            }

//            System.out.println("trial=" + t
//                    + " testIdx=" + testIdx
//                    + " train=" + train.size()
//                    + " expectedObj(empExp)=" + fmt(expected)
//                    + " droObj(debug)=" + fmt(droObj) // [NEW COMMENT] 仅打印方便核对
//                    + " realized=" + fmt(realized)
//                    + " ESS=" + fmt(diag.ess)
//                    + " corr(thetaDist,demandDist)=" + fmt(diag.corrThetaDemand)
//                    + " sel=" + selectedCount
//                    + " time=" + fmt(solveTimeSec) + "s");

            // per-trial outputs
            OutputManager.writeWeights(out.resultsDir.resolve("weights.csv"), data);
            OutputManager.writeY(out.resultsDir.resolve("y.csv"), data, sol);
            OutputManager.writeSummary(out.resultsDir.resolve("summary.csv"), sol, train.size(), realized, diag);

            OutputManager.writeOOSLaneCosts(out.resultsDir.resolve("oos_lane_costs.csv"),
                    lanes, dTest, rec.laneTransportQty, rec.laneSpotQty, rec.laneTransportCost, rec.laneSpotCost);
            OutputManager.writeOOSCarrierPenaltyCosts(out.resultsDir.resolve("oos_carrier_penalty.csv"),
                    data, sol.y, rec.carrierAssignedQty, rec.carrierPenaltyQty, rec.carrierPenaltyCost);
        }

        // ============ (B) summary stats across trials ============
        SummaryStats expStats = SummaryStats.of(expectedList);
        SummaryStats modelObjStats = SummaryStats.of(modelObjList);
        SummaryStats reaStats = SummaryStats.of(realizedList);
        SummaryStats timeStats = SummaryStats.of(timeList);

        SummaryStats essStats = SummaryStats.of(essList);
        SummaryStats corrWThetaStats = SummaryStats.of(corrWThetaList);
        SummaryStats corrWDemandStats = SummaryStats.of(corrWDemandList);
        SummaryStats corrThetaDemandStats = SummaryStats.of(corrThetaDemandList);

        SummaryStats thetaDistMeanStats = SummaryStats.of(thetaDistMeanList);
        SummaryStats demandDistMeanStats = SummaryStats.of(demandDistMeanList);
        SummaryStats transportStats = SummaryStats.of(transportList);
        SummaryStats spotStats = SummaryStats.of(spotList);
        SummaryStats penaltyStats = SummaryStats.of(penaltyList);
        // ---- human-readable txt ----
        try (BufferedWriter bw = Files.newBufferedWriter(summaryTxt)) {
            bw.write("tag=" + tag + "\n");
            bw.write("solveMode=" + cfg.solveMode + "\n");
            bw.write("trials=" + batches.size() + "\n");
            bw.write("fillMissingDates=" + cfg.fillMissingDates + "\n");
            bw.write("standardizeTheta=" + cfg.standardizeTheta + "\n");
            bw.write("k1LagPeriods=" + cfg.k1LagPeriods + "\n");
            bw.write("W=" + batches.size() + " (numTrials)\n");
            bw.write("kernelType=" + cfg.kernelType + ", bandwidthH=" + cfg.bandwidthH + "\n");
            bw.write("C_h=" + cfg.C_h + "\n\n");

            // [NEW COMMENT] 这里的 Expected objective 对应 trialsCsv 的 expectedObj（empirical expected cost）
            bw.write("[Expected objective]\n" + expStats.pretty() + "\n\n");
            bw.write("[Model objective]\n" + modelObjStats.pretty() + "\n\n");
            bw.write("[Realized OOS cost]\n" + reaStats.pretty() + "\n\n");
            bw.write("[Solve time (sec)]\n" + timeStats.pretty() + "\n\n");

            bw.write("[ESS]\n" + essStats.pretty() + "\n\n");
            bw.write("[Corr(w, thetaDist)]\n" + corrWThetaStats.pretty() + "\n\n");
            bw.write("[Corr(w, demandDist)]\n" + corrWDemandStats.pretty() + "\n\n");
            bw.write("[Corr(thetaDist, demandDist)]\n" + corrThetaDemandStats.pretty() + "\n\n");

            bw.write("[thetaDist_mean across trials]\n" + thetaDistMeanStats.pretty() + "\n\n");
            bw.write("[demandDist_mean across trials]\n" + demandDistMeanStats.pretty() + "\n");
            bw.write("[OOS transport cost]\n" + transportStats.pretty() + "\n\n");
            bw.write("[OOS spot cost]\n" + spotStats.pretty() + "\n\n");
            bw.write("[OOS penalty cost]\n" + penaltyStats.pretty() + "\n\n");

        }

        // ---- machine-readable one-row summary CSV ----
        try (BufferedWriter bw = Files.newBufferedWriter(summaryStatsCsv)) {
            bw.write(String.join(",",
                    // config keys
                    "tag","solveMode","fillMissingDates","standardizeTheta","k1LagPeriods","kernelType","bandwidthH","C_h",
                    // meta
                    "nTrials",
                    // expected
                    "exp_mean","exp_std","exp_min","exp_p20","exp_p50","exp_p80","exp_p95","exp_max",
                    // model objective
                    "model_mean","model_std","model_min","model_p20","model_p50","model_p80","model_p95","model_max",
                    // realized
                    "real_mean","real_std","real_min","real_p20","real_p50","real_p80","real_p95","real_max",

                    // NEW: OOS cost breakdown stats
                    "transport_mean","transport_std","transport_min","transport_p20","transport_p50","transport_p80","transport_p95","transport_max",
                    "spot_mean","spot_std","spot_min","spot_p20","spot_p50","spot_p80","spot_p95","spot_max",
                    "penalty_mean","penalty_std","penalty_min","penalty_p20","penalty_p50","penalty_p80","penalty_p95","penalty_max",

                    // time
                    "time_mean","time_std","time_min","time_p20","time_p50","time_p80","time_p95","time_max",
                    // ESS
                    "ess_mean","ess_std","ess_min","ess_p20","ess_p50","ess_p80","ess_p95","ess_max",
                    // corr(thetaDist,demandDist)
                    "corrTD_mean","corrTD_std","corrTD_min","corrTD_p20","corrTD_p50","corrTD_p80","corrTD_p95","corrTD_max",
                    // corr(w,thetaDist) & corr(w,demandDist)
                    "corrWT_mean","corrWT_std","corrWT_min","corrWT_p20","corrWT_p50","corrWT_p80","corrWT_p95","corrWT_max",
                    "corrWD_mean","corrWD_std","corrWD_min","corrWD_p20","corrWD_p50","corrWD_p80","corrWD_p95","corrWD_max",
                    // distance means across trials
                    "thetaDistMean_mean","thetaDistMean_p50","demandDistMean_mean","demandDistMean_p50"
            ));
            bw.newLine();

            bw.write(String.format(Locale.US,
                    "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%d," +
                            // expected
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            // model objective
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            // realized
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +

                            // NEW: transport / spot / penalty
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +

                            // time
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            // ess
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            // corrTD
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            // corrWT
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            // corrWD
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            // dist means
                            "%.10f,%.10f,%.10f,%.10f%n",
                    tag,
                    (cfg.solveMode == null ? "" : cfg.solveMode.name()),
                    String.valueOf(cfg.fillMissingDates),
                    String.valueOf(cfg.standardizeTheta),
                    cfg.k1LagPeriods,
                    (cfg.kernelType == null ? "" : cfg.kernelType.name()),
                    cfg.bandwidthH,
                    cfg.C_h,
                    batches.size(),

                    // expected
                    expStats.mean, expStats.std, expStats.min, expStats.p20, expStats.p50, expStats.p80, expStats.p95, expStats.max,
                    // model objective
                    modelObjStats.mean, modelObjStats.std, modelObjStats.min, modelObjStats.p20, modelObjStats.p50, modelObjStats.p80, modelObjStats.p95, modelObjStats.max,
                    // realized
                    reaStats.mean, reaStats.std, reaStats.min, reaStats.p20, reaStats.p50, reaStats.p80, reaStats.p95, reaStats.max,

                    // NEW: transport
                    transportStats.mean, transportStats.std, transportStats.min, transportStats.p20, transportStats.p50, transportStats.p80, transportStats.p95, transportStats.max,
                    // NEW: spot
                    spotStats.mean, spotStats.std, spotStats.min, spotStats.p20, spotStats.p50, spotStats.p80, spotStats.p95, spotStats.max,
                    // NEW: penalty
                    penaltyStats.mean, penaltyStats.std, penaltyStats.min, penaltyStats.p20, penaltyStats.p50, penaltyStats.p80, penaltyStats.p95, penaltyStats.max,

                    // time
                    timeStats.mean, timeStats.std, timeStats.min, timeStats.p20, timeStats.p50, timeStats.p80, timeStats.p95, timeStats.max,
                    // ess
                    essStats.mean, essStats.std, essStats.min, essStats.p20, essStats.p50, essStats.p80, essStats.p95, essStats.max,

                    // corrTD
                    corrThetaDemandStats.mean, corrThetaDemandStats.std, corrThetaDemandStats.min, corrThetaDemandStats.p20,
                    corrThetaDemandStats.p50, corrThetaDemandStats.p80, corrThetaDemandStats.p95, corrThetaDemandStats.max,

                    // corrWT
                    corrWThetaStats.mean, corrWThetaStats.std, corrWThetaStats.min, corrWThetaStats.p20,
                    corrWThetaStats.p50, corrWThetaStats.p80, corrWThetaStats.p95, corrWThetaStats.max,

                    // corrWD
                    corrWDemandStats.mean, corrWDemandStats.std, corrWDemandStats.min, corrWDemandStats.p20,
                    corrWDemandStats.p50, corrWDemandStats.p80, corrWDemandStats.p95, corrWDemandStats.max,

                    // dist means
                    thetaDistMeanStats.mean, thetaDistMeanStats.p50,
                    demandDistMeanStats.mean, demandDistMeanStats.p50
            ));
        }


        // ---- Global summary (cross-config) ----
        if (collector != null) {
        	collector.append(
        	        tag,
        	        cfg.solveMode,
        	        cfg.fillMissingDates,
        	        cfg.standardizeTheta,
        	        cfg.k1LagPeriods,
        	        cfg.kernelType,
        	        cfg.bandwidthH,
        	        cfg.C_h,
        	        (cfg.solveMode == SolveMode.RCSAA ? cfg.lambda : Double.NaN),
        	        batches.size(),
        	        new GlobalSummaryCollector.Stats(expStats.n, expStats.mean, expStats.std, expStats.min, expStats.max,
        	                expStats.p20, expStats.p50, expStats.p80, expStats.p95),
        	        new GlobalSummaryCollector.Stats(modelObjStats.n, modelObjStats.mean, modelObjStats.std, modelObjStats.min, modelObjStats.max,
        	                modelObjStats.p20, modelObjStats.p50, modelObjStats.p80, modelObjStats.p95),
        	        new GlobalSummaryCollector.Stats(reaStats.n, reaStats.mean, reaStats.std, reaStats.min, reaStats.max,
        	                reaStats.p20, reaStats.p50, reaStats.p80, reaStats.p95),
        	        new GlobalSummaryCollector.Stats(essStats.n, essStats.mean, essStats.std, essStats.min, essStats.max,
        	                essStats.p20, essStats.p50, essStats.p80, essStats.p95),
        	        new GlobalSummaryCollector.Stats(corrThetaDemandStats.n, corrThetaDemandStats.mean, corrThetaDemandStats.std,
        	                corrThetaDemandStats.min, corrThetaDemandStats.max,
        	                corrThetaDemandStats.p20, corrThetaDemandStats.p50, corrThetaDemandStats.p80, corrThetaDemandStats.p95),

        	        transportStats.mean,
        	        spotStats.mean,
        	        penaltyStats.mean,

        	        timeStats.mean
        	);

        }
    }

    // ============ empirical recourse costs Q(y, d^w) per training sample ============
    private static double[] empiricalRecourseCosts(ProcurementParams params,
                                                   List<Sample> train,
                                                   double[] y,
                                                   boolean enforceDemandEquality) throws IloException {
        double[] vals = new double[train.size()];
        int id = 0;
        for (Sample s : train) {
            double q = RecourseEvaluator.evaluate(
                    params, y, s.demand().clone(), enforceDemandEquality).objValue;
            vals[id++] = q;
        }
        return vals;
    }

    // ===== weighted mean (robust to weights not summing to 1) =====
    private static double weightedMean(double[] vals, List<Sample> train) {
        double sumW = 0.0;
        double sum = 0.0;
        for (int i = 0; i < train.size(); i++) {
            double w = train.get(i).weight;
            if (!Double.isFinite(w)) continue;
            sumW += w;
            sum += w * vals[i];
        }
        return (sumW > 0.0) ? (sum / sumW) : Double.NaN;
    }

    // ===== weighted variance = E[X^2] - (E[X])^2 =====
    private static double weightedVariance(double[] vals, List<Sample> train) {
        double sumW = 0.0;
        double sum1 = 0.0;
        double sum2 = 0.0;
        for (int i = 0; i < train.size(); i++) {
            double w = train.get(i).weight;
            if (!Double.isFinite(w)) continue;
            double x = vals[i];
            sumW += w;
            sum1 += w * x;
            sum2 += w * x * x;
        }
        if (!(sumW > 0.0)) return Double.NaN;
        double mean = sum1 / sumW;
        double ex2 = sum2 / sumW;
        double var = ex2 - mean * mean;
        return (var < 0 && var > -1e-12) ? 0.0 : var;
    }

    // ============ utilities ============
    private static String fmt(double v) { return String.format(Locale.US, "%.6f", v); }

    private static String buildSampleWeightsString(List<Sample> samples) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < samples.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "%.10f", samples.get(i).weight));
        }
        sb.append("]");
        return sb.toString();
    }

    private static int countSelected(double[] y) {
        int c = 0;
        for (double v : y) if (v > 0.5) c++;
        return c;
    }

    private static List<Sample> deepCopySamples(List<Sample> src) {
        List<Sample> out = new ArrayList<>(src.size());
        for (Sample s : src) {
            CovariateVector thetaCopy = new CovariateVector(s.theta.values().clone());
            Sample c = new Sample(s.id, s.period, thetaCopy, s.weight);
            out.add(c);
        }
        return out;
    }

    // ============ stats ============
    private static class SummaryStats {
        final int n;
        final double mean, std, min, max, p20, p50, p80, p95;

        private SummaryStats(int n, double mean, double std, double min, double max,
                             double p20, double p50, double p80, double p95) {
            this.n = n; this.mean = mean; this.std = std; this.min = min; this.max = max;
            this.p20 = p20; this.p50 = p50; this.p80 = p80; this.p95 = p95;
        }

        static SummaryStats of(List<Double> xs) {
            if (xs == null || xs.isEmpty()) return new SummaryStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN);
            double[] a = xs.stream().mapToDouble(Double::doubleValue).toArray();
            int n = a.length;
            double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            for (double v : a) { sum += v; min = Math.min(min, v); max = Math.max(max, v); }
            double mean = sum / n;
            double var = 0;
            for (double v : a) { double d = v - mean; var += d * d; }
            double std = Math.sqrt(var / Math.max(1, n - 1));
            Arrays.sort(a);
            double p20 = q(a, 0.20), p50 = q(a, 0.50), p80 = q(a, 0.80), p95 = q(a, 0.95);
            return new SummaryStats(n, mean, std, min, max, p20, p50, p80, p95);
        }

        private static double q(double[] s, double q) {
            if (s.length == 1) return s[0];
            double pos = q * (s.length - 1);
            int lo = (int) Math.floor(pos), hi = (int) Math.ceil(pos);
            if (lo == hi) return s[lo];
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

   
}
