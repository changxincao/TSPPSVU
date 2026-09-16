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
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.StandardScaler;
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

public class DeterministicBatchRunner {

    public static void run(String tag,
                           List<String> lanes,
                           ProcurementParams params,
                           Config cfg,
                           ExperimentBatches batches,
                           Path outDir,
                           GlobalSummaryCollector collector,
                           GlobalTrialCollector trialCollector,
                           GenConfig genCfg) throws Exception {

        if (batches == null || batches.size() == 0) {
            throw new IllegalArgumentException("Empty experiment batches.");
        }
        if (cfg.solveMode != SolveMode.MeanDeterministic && cfg.solveMode != SolveMode.CompleteDeterministic) {
            throw new IllegalArgumentException("DeterministicBatchRunner requires solveMode in {MeanDeterministic, CompleteDeterministic}.");
        }

        System.out.println("Batch Size:" + batches.size());
        Files.createDirectories(outDir);

        Path trialsCsv = outDir.resolve("trials_" + tag + ".csv");
        Path summaryTxt = outDir.resolve("summary_" + tag + ".txt");
        Path summaryStatsCsv = outDir.resolve("summary_" + tag + "_stats.csv");

        // ============ (A) per-trial CSV header (保持与 BatchRunner 一致) ============
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

        EuclideanDistance distMetric = new EuclideanDistance();

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

        // OOS costs
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

            System.out.println("第" + t + "个batch迭代,in sample数量为:" + (trainRaw == null ? 0 : trainRaw.size()));

            // ---- deep copy training set (IMPORTANT) ----
            List<Sample> train = deepCopySamples(trainRaw == null ? Collections.emptyList() : trainRaw);
            CovariateVector thetaNow = new CovariateVector(thetaNowRaw.values().clone());

            int thetaDim = (train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length);

            // ---- standardize ONLY using training set ----
            if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
                StandardScaler scaler = new StandardScaler();
                scaler.fit(train, thetaDim);

                for (Sample s : train) {
                    s.theta = new CovariateVector(scaler.transform(s.theta.values()));
                }
                thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
            }

            // ---- weights: 确定性求解不需要权重；为保持输出/诊断列一致，设置等权 ----
            if (!train.isEmpty()) {
                double eq = 1.0 / train.size();
                for (Sample s : train) s.weight = eq;
            }

            double[] dTest = test.demand().clone();

            // ---- trial diagnostics (ESS/dist/corr) ----
            TrialDiag diag = TrialDiag.compute(train, thetaNow, dTest, distMetric);

            // ---- build data for output writers (不用于求解) ----
            Data data = new Data(lanes, train, thetaNow, params);

            // ---- choose deterministic demand for solving ----
            double[] dDet;
            if (cfg.solveMode == SolveMode.MeanDeterministic) {
                dDet = meanDemand(trainRaw, params.J);
                // 若训练集为空，退化为用 dTest（避免全 0）
                if (dDet == null) dDet = dTest.clone();
            } else { // CompleteDeterministic
                dDet = dTest.clone();
            }

            // ---- solve deterministic MILP ----
            System.out.println("开始求解确定性模型: " + cfg.solveMode);
            long st = System.nanoTime();
            Solution sol = solveDeterministicMILP(params, dDet, cfg.enforceDemandEquality);
            long ed = System.nanoTime();
            double solveTimeSec = (ed - st) / 1e9;

            // ---- realized OOS cost on dTest (与 BatchRunner 一致) ----
            RecourseEvaluator.RecourseEval rec = RecourseEvaluator.evaluate(
                    params, sol.y, dTest, cfg.enforceDemandEquality);
            double realized = rec.objValue;

            // expectedObj：确定性目标值（在 dDet 下）
            double expected = sol.objValue;

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

            // append per-trial record
            try (BufferedWriter bw = Files.newBufferedWriter(trialsCsv, java.nio.file.StandardOpenOption.APPEND)) {
                bw.write(String.format(Locale.US,
                        "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%.6f," +
                                "%d,%d,%d," +
                                "%.10f,%.10f,%.6f,%d," +
                                "%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f,%.10f," +
                                "%.10f,%.10f,%.10f," +
                                "%s,%s,%s%n",
                        safe(tag),
                        (cfg.solveMode == null ? "" : cfg.solveMode.name()),
                        String.valueOf(cfg.fillMissingDates),
                        String.valueOf(cfg.standardizeTheta),
                        cfg.k1LagPeriods,
                        (cfg.kernelType == null ? "" : cfg.kernelType.name()),
                        cfg.bandwidthH,
                        cfg.C_h,
                        lambdaVal,

                        t, testIdx, train.size(),
                        expected, realized, solveTimeSec, selectedCount,

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
                        expected,
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

            // per-trial outputs (保持与 BatchRunner 一致)
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

            bw.write("[Expected objective]\n" + expStats.pretty() + "\n\n");
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

        // ---- machine-readable one-row summary CSV (保持与 BatchRunner 一致) ----
        try (BufferedWriter bw = Files.newBufferedWriter(summaryStatsCsv)) {
            bw.write(String.join(",",
                    "tag","solveMode","fillMissingDates","standardizeTheta","k1LagPeriods","kernelType","bandwidthH","C_h",
                    "nTrials",
                    "exp_mean","exp_std","exp_min","exp_p20","exp_p50","exp_p80","exp_p95","exp_max",
                    "real_mean","real_std","real_min","real_p20","real_p50","real_p80","real_p95","real_max",

                    "transport_mean","transport_std","transport_min","transport_p20","transport_p50","transport_p80","transport_p95","transport_max",
                    "spot_mean","spot_std","spot_min","spot_p20","spot_p50","spot_p80","spot_p95","spot_max",
                    "penalty_mean","penalty_std","penalty_min","penalty_p20","penalty_p50","penalty_p80","penalty_p95","penalty_max",

                    "time_mean","time_std","time_min","time_p20","time_p50","time_p80","time_p95","time_max",
                    "ess_mean","ess_std","ess_min","ess_p20","ess_p50","ess_p80","ess_p95","ess_max",
                    "corrTD_mean","corrTD_std","corrTD_min","corrTD_p20","corrTD_p50","corrTD_p80","corrTD_p95","corrTD_max",
                    "corrWT_mean","corrWT_std","corrWT_min","corrWT_p20","corrWT_p50","corrWT_p80","corrWT_p95","corrWT_max",
                    "corrWD_mean","corrWD_std","corrWD_min","corrWD_p20","corrWD_p50","corrWD_p80","corrWD_p95","corrWD_max",
                    "thetaDistMean_mean","thetaDistMean_p50","demandDistMean_mean","demandDistMean_p50"
            ));
            bw.newLine();

            bw.write(String.format(Locale.US,
                    "%s,%s,%s,%s,%d,%s,%.6f,%.6f,%d," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +

                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +

                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f," +
                            "%.10f,%.10f,%.10f,%.10f%n",
                    safe(tag),
                    (cfg.solveMode == null ? "" : cfg.solveMode.name()),
                    String.valueOf(cfg.fillMissingDates),
                    String.valueOf(cfg.standardizeTheta),
                    cfg.k1LagPeriods,
                    (cfg.kernelType == null ? "" : cfg.kernelType.name()),
                    cfg.bandwidthH,
                    cfg.C_h,
                    batches.size(),

                    expStats.mean, expStats.std, expStats.min, expStats.p20, expStats.p50, expStats.p80, expStats.p95, expStats.max,
                    reaStats.mean, reaStats.std, reaStats.min, reaStats.p20, reaStats.p50, reaStats.p80, reaStats.p95, reaStats.max,

                    transportStats.mean, transportStats.std, transportStats.min, transportStats.p20, transportStats.p50, transportStats.p80, transportStats.p95, transportStats.max,
                    spotStats.mean, spotStats.std, spotStats.min, spotStats.p20, spotStats.p50, spotStats.p80, spotStats.p95, spotStats.max,
                    penaltyStats.mean, penaltyStats.std, penaltyStats.min, penaltyStats.p20, penaltyStats.p50, penaltyStats.p80, penaltyStats.p95, penaltyStats.max,

                    timeStats.mean, timeStats.std, timeStats.min, timeStats.p20, timeStats.p50, timeStats.p80, timeStats.p95, timeStats.max,
                    essStats.mean, essStats.std, essStats.min, essStats.p20, essStats.p50, essStats.p80, essStats.p95, essStats.max,

                    corrThetaDemandStats.mean, corrThetaDemandStats.std, corrThetaDemandStats.min, corrThetaDemandStats.p20,
                    corrThetaDemandStats.p50, corrThetaDemandStats.p80, corrThetaDemandStats.p95, corrThetaDemandStats.max,

                    corrWThetaStats.mean, corrWThetaStats.std, corrWThetaStats.min, corrWThetaStats.p20,
                    corrWThetaStats.p50, corrWThetaStats.p80, corrWThetaStats.p95, corrWThetaStats.max,

                    corrWDemandStats.mean, corrWDemandStats.std, corrWDemandStats.min, corrWDemandStats.p20,
                    corrWDemandStats.p50, corrWDemandStats.p80, corrWDemandStats.p95, corrWDemandStats.max,

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
                    (cfg.solveMode == SolveMode.RCSAA ? cfg.lambda : Double.NaN), // 与原接口对齐
                    batches.size(),
                     new GlobalSummaryCollector.Stats(expStats.n, expStats.mean, expStats.std, expStats.min, expStats.max,
                             expStats.p20, expStats.p50, expStats.p80, expStats.p95),
                     new GlobalSummaryCollector.Stats(expStats.n, expStats.mean, expStats.std, expStats.min, expStats.max,
                             expStats.p20, expStats.p50, expStats.p80, expStats.p95),
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

    // ====================== deterministic MILP solver ======================

    /**
     * Solve deterministic equivalent MILP for a given deterministic demand vector dDet.
     * Variables: y (binary), x, s, u
     * Objective: Σ r_ij x_ij + Σ e_j s_j + Σ h_i u_i
     * Constraints:
     *   Σ_i x_ij + s_j >= d_j
     *   p_i y_i - u_i <= Σ_j x_ij <= M_i y_i
     *   0 <= x_ij <= q_ij (or 0 if ineligible), s_j>=0, u_i>=0
     */
    private static Solution solveDeterministicMILP(ProcurementParams params,
                                                   double[] dDet,
                                                   boolean enforceDemandEquality) throws IloException {
        int I = params.I, J = params.J;

        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);

            IloNumVar[] y = new IloNumVar[I];
            for (int i = 0; i < I; i++) y[i] = cplex.boolVar("y_" + i);

            IloNumVar[][] x = new IloNumVar[I][J];
            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    double ub = params.eligible[i][j] ? params.q[i][j] : 0.0;
                    x[i][j] = cplex.numVar(0.0, ub, IloNumVarType.Float, "x_" + i + "_" + j);
                }
            }

            IloNumVar[] s = new IloNumVar[J];
            for (int j = 0; j < J; j++) s[j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float, "s_" + j);

            IloNumVar[] u = new IloNumVar[I];
            for (int i = 0; i < I; i++) u[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float, "u_" + i);

            // objective
            IloLinearNumExpr obj = cplex.linearNumExpr();
            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (params.eligible[i][j]) obj.addTerm(params.r[i][j], x[i][j]);
                }
            }
            for (int j = 0; j < J; j++) obj.addTerm(params.e[j], s[j]);
            for (int i = 0; i < I; i++) obj.addTerm(params.h[i], u[i]);
            cplex.addMinimize(obj);

            // first-stage selection constraints: alpha <= sum_i y_i <= beta
            IloLinearNumExpr sumY = cplex.linearNumExpr();
            for (int i = 0; i < I; i++) {
                sumY.addTerm(1.0, y[i]);
            }
            cplex.addGe(sumY, params.alpha, "minSelected");
            cplex.addLe(sumY, params.beta, "maxSelected");

            // demand
            for (int j = 0; j < J; j++) {
                IloLinearNumExpr lhs = cplex.linearNumExpr();
                for (int i = 0; i < I; i++) lhs.addTerm(1.0, x[i][j]);
                lhs.addTerm(1.0, s[j]);
                if (enforceDemandEquality) {
                    cplex.addEq(lhs, dDet[j], "demand_" + j);
                } else {
                    cplex.addGe(lhs, dDet[j], "demand_" + j);
                }
            }

            // MQC + upper
            for (int i = 0; i < I; i++) {
                IloLinearNumExpr sumX = cplex.linearNumExpr();
                for (int j = 0; j < J; j++) sumX.addTerm(1.0, x[i][j]);

                // p_i y_i - u_i <= sumX
                IloLinearNumExpr left = cplex.linearNumExpr();
                left.addTerm(params.p[i], y[i]);
                left.addTerm(-1.0, u[i]);
                cplex.addLe(left, sumX, "mqcL_" + i);

                // sumX <= M_i y_i
                IloLinearNumExpr rhs = cplex.linearNumExpr();
                rhs.addTerm(params.M[i], y[i]);
                cplex.addLe(sumX, rhs, "mqcU_" + i);
            }

            Solution sol = new Solution();
            sol.y = new double[I];

            if (!cplex.solve()) {
                sol.objValue = Double.POSITIVE_INFINITY;
                Arrays.fill(sol.y, 0.0);
                return sol;
            }

            sol.objValue = cplex.getObjValue();
            for (int i = 0; i < I; i++) sol.y[i] = cplex.getValue(y[i]);

            return sol;
        }
    }

    // ====================== mean demand ======================

    /** Mean demand over training samples, lane-wise. */
    private static double[] meanDemand(List<Sample> trainRaw, int J) {
        if (trainRaw == null || trainRaw.isEmpty()) return null;
        double[] mean = new double[J];
        int n = 0;
        for (Sample s : trainRaw) {
            double[] d = s.demand();
            if (d == null) continue;
            int m = Math.min(J, d.length);
            for (int j = 0; j < m; j++) mean[j] += d[j];
            n++;
        }
        if (n == 0) return null;
        for (int j = 0; j < J; j++) mean[j] /= n;
        return mean;
    }

    // ====================== utilities ======================

    private static String safe(String s) { return s == null ? "" : s; }

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
        if (y == null) return 0;
        for (double v : y) if (v > 0.5) c++;
        return c;
    }

    private static List<Sample> deepCopySamples(List<Sample> src) {
        List<Sample> out = new ArrayList<>(src == null ? 0 : src.size());
        if (src == null) return out;
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
            if (s.length == 1) return s[0];
            double pos = q * (s.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
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
