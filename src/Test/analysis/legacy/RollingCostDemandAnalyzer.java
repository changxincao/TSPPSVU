package Test.analysis.legacy;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.CsvHistoryLoader;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.calculateHelper.KernelType;
import Test.ExperimentBuilder;
import Test.ExperimentBatches;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 滚动试验需求与成本分析器。
 *
 * 这个类不参与模型求解，只负责围绕当前实例生成逻辑和 rolling trial 数据做解释性分析。
 * 主要输出四类结果：
 * 1. 成本生成机制说明：明确 `r_ij`、`e_j`、`q_ij`、`p_i`、`h_i` 的生成口径；
 * 2. 供应商成本相似度：按共同可服务线路比较不同供应商的成本结构相似性；
 * 3. rolling trial 需求大头分析：识别每个 trial 中累计覆盖前 80% 需求的线路，并给出其低价供应商；
 * 4. 全时段需求分布：汇总所有时间上的 line 总需求、占比与累计占比。
 *
 * 这个类的作用是帮助解释“为什么某些供应商稳定出现”，以及“需求大头和低价供应商之间的关系”。
 */
public class RollingCostDemandAnalyzer {

    private static final Locale US = Locale.US;

    public static void main(String[] args) throws Exception {
        Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
        Path outDir = Paths.get(args.length > 1 ? args[1] : "./analysis");
        int windowW = (args.length > 2 ? Integer.parseInt(args[2]) : 24);
        int numCarriers = (args.length > 3 ? Integer.parseInt(args[3]) : 15);
        double topShare = (args.length > 4 ? Double.parseDouble(args[4]) : 0.8);
        int[] k1List = parseK1List(args.length > 5 ? args[5] : "2");
        int maxTrials = (args.length > 6 ? Integer.parseInt(args[6]) : -1);

        if (topShare <= 0 || topShare > 1.0) {
            throw new IllegalArgumentException("topShare must be in (0, 1].");
        }

        Files.createDirectories(outDir);

        Config cfg = buildBaseConfig();
        InstanceGenerator.GenConfig genCfg = buildGenConfig();

        CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);
        if (hist.days == null || hist.days.isEmpty()) {
            throw new IllegalArgumentException("No daily records loaded from: " + historyCsv);
        }

        Path costGenTxt = outDir.resolve("成本生成机制说明.txt");
        Path supplierPairCsv = outDir.resolve("供应商成本相似度_两两.csv");
        Path supplierSummaryCsv = outDir.resolve("供应商成本相似度_汇总.csv");
        Path supplierLineOrderCsv = outDir.resolve("供应商服务线路成本从低到高.csv");
        Path trialHeadCsv = outDir.resolve("滚动试验_需求大头明细.csv");
        Path trialSummaryCsv = outDir.resolve("滚动试验_需求集中汇总.csv");
        Path overallDemandCsv = outDir.resolve("全时段需求分布.csv");
        Path outputGuideTxt = outDir.resolve("输出文件说明.txt");

        writeCostGenerationNotes(costGenTxt, cfg, genCfg);
        writeOverallDemandDistribution(overallDemandCsv, hist);

        boolean wrotePair = false;
        boolean wroteSupplierSummary = false;
        boolean wroteSupplierLineOrder = false;
        boolean wroteHeadHeader = false;
        boolean wroteTrialSummaryHeader = false;

        for (int k1 : k1List) {
            Config cfgK1 = copyConfigWithK1(cfg, k1);

            SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfgK1);
            if (br.samples == null || br.samples.size() <= windowW) {
                System.out.println("[WARN] Skip k1=" + k1 + " because samples.size <= W.");
                continue;
            }

            double[] dBase = buildBaselineDemand(br);
            ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfgK1);

            if (!wrotePair) {
                writeSupplierPairSimilarity(supplierPairCsv, params);
                wrotePair = true;
            }
            if (!wroteSupplierSummary) {
                writeSupplierSimilaritySummary(supplierSummaryCsv, params);
                wroteSupplierSummary = true;
            }
            if (!wroteSupplierLineOrder) {
                writeSupplierLineCostOrder(supplierLineOrderCsv, params, hist.laneNames);
                wroteSupplierLineOrder = true;
            }

            ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, windowW);
            TrialTables tables = buildTrialTables(rolling, hist.laneNames, params, k1, topShare, maxTrials);

            wroteHeadHeader = appendRows(trialHeadCsv, tables.headRows, wroteHeadHeader);
            wroteTrialSummaryHeader = appendRows(trialSummaryCsv, tables.summaryRows, wroteTrialSummaryHeader);

            System.out.println("[OK] k1=" + k1
                    + " trialsUsed=" + (maxTrials <= 0 ? rolling.size() : Math.min(maxTrials, rolling.size()))
                    + " headRows=" + tables.headRows.size());
        }

        writeOutputGuide(outputGuideTxt, maxTrials, topShare, k1List,
                costGenTxt, supplierPairCsv, supplierSummaryCsv, supplierLineOrderCsv,
                overallDemandCsv, trialHeadCsv, trialSummaryCsv);

        System.out.println("[DONE] Analysis outputs:");
        System.out.println("  " + costGenTxt.toAbsolutePath());
        System.out.println("  " + supplierPairCsv.toAbsolutePath());
        System.out.println("  " + supplierSummaryCsv.toAbsolutePath());
        System.out.println("  " + supplierLineOrderCsv.toAbsolutePath());
        System.out.println("  " + overallDemandCsv.toAbsolutePath());
        System.out.println("  " + trialHeadCsv.toAbsolutePath());
        System.out.println("  " + trialSummaryCsv.toAbsolutePath());
        System.out.println("  " + outputGuideTxt.toAbsolutePath());
    }

    private static Config buildBaseConfig() {
        Config cfg = new Config();
        cfg.fillMissingDates = true;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = 0;
        cfg.kernelType = KernelType.EXPONENTIAL;
        cfg.standardizeTheta = true;
        cfg.bandwidthH = 1.0;

        cfg.featureFlags.includeLagDemand = false;
        cfg.featureFlags.includeHolidayCount = true;
        cfg.featureFlags.includeFreightIndex = true;
        cfg.featureFlags.includeConsumptionIndex = true;
        cfg.featureFlags.includeWEIIndex = true;
        return cfg;
    }

    private static InstanceGenerator.GenConfig buildGenConfig() {
        InstanceGenerator.GenConfig g = new InstanceGenerator.GenConfig();
        g.rBarLow = 20.0;
        g.rBarHigh = 100.0;
        g.tauLow = 0.05;
        g.tauHigh = 0.30;
        g.mqcLow = 0.1;
        g.mqcHigh = 0.2;
        g.coverAllLanes = true;
        g.capacityMode = InstanceGenerator.CapacityMode.TIGHT;
        return g;
    }

    private static Config copyConfigWithK1(Config base, int k1) {
        Config c = new Config();
        c.fillMissingDates = base.fillMissingDates;
        c.aggregationDays = base.aggregationDays;
        c.k1LagPeriods = k1;
        c.demandAgg = base.demandAgg;
        c.standardizeTheta = base.standardizeTheta;
        c.thetaScaling = base.thetaScaling;
        c.bandwidthH = base.bandwidthH;
        c.C_h = base.C_h;
        c.kernelType = base.kernelType;
        c.seed = base.seed;

        c.featureFlags.includeLagDemand = (k1 > 0);
        c.featureFlags.includeHolidayCount = base.featureFlags.includeHolidayCount;
        c.featureFlags.includeFreightIndex = base.featureFlags.includeFreightIndex;
        c.featureFlags.includeConsumptionIndex = base.featureFlags.includeConsumptionIndex;
        c.featureFlags.includeWEIIndex = base.featureFlags.includeWEIIndex;
        return c;
    }

    private static int[] parseK1List(String s) {
        String[] parts = s.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    private static double[] buildBaselineDemand(SampleBuilder.BuildResult br) {
        if (br.periods == null || br.periods.isEmpty()) {
            return new double[0];
        }
        int jSize = br.periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : br.periods) {
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

    private static void writeCostGenerationNotes(Path outFile, Config cfg, InstanceGenerator.GenConfig genCfg) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("当前代码中的供应商-线路成本生成机制\n");
            bw.write("1) 每条线路基础价格: rBar_j ~ U[" + genCfg.rBarLow + ", " + genCfg.rBarHigh + "]\n");
            bw.write("2) 每条线路波动系数: tau_j ~ U[" + genCfg.tauLow + ", " + genCfg.tauHigh + "]\n");
            bw.write("3) 每条线路上，供应商随机分成低/中/高三档价格组:\n");
            bw.write("   factor_ij in {" + genCfg.priceFactorLow + ", " + genCfg.priceFactorMid + ", " + genCfg.priceFactorHigh + "}\n");
            bw.write("4) 供应商 i 在线路 j 上的运输成本:\n");
            bw.write("   r_ij ~ U[(rBar_j*factor_ij)*(1-tau_j), (rBar_j*factor_ij)*(1+tau_j)]\n");
            bw.write("5) 若供应商对该线路不可用，ProcurementParams.validate 会将 r_ij 置为 Double.MAX_VALUE\n");
            bw.write("6) 现货价 spot 采用 Baseline-A:\n");
            bw.write("   e_j = kappa_j * median_i(r_ij on lane j), kappa_j ~ U["
                    + genCfg.spotMultLow + ", " + genCfg.spotMultHigh + "]\n");
            bw.write("7) MQC p_i 和 capacity q_ij 单独生成，不改变 r_ij 的抽样公式\n");
            bw.write("配置快照: fillMissingDates=" + cfg.fillMissingDates
                    + ", aggregationDays=" + cfg.aggregationDays
                    + ", seed=" + cfg.seed + "\n");
        }
    }

    private static void writeOverallDemandDistribution(Path outFile, CsvHistoryLoader.HistoryLoadResult hist) throws Exception {
        int jSize = hist.laneNames.size();
        double[] total = new double[jSize];
        for (Basic.HistoricalDay day : hist.days) {
            for (int j = 0; j < jSize; j++) {
                total[j] += day.laneDemand[j];
            }
        }
        double grand = 0.0;
        for (double v : total) grand += v;

        Integer[] order = new Integer[jSize];
        for (int j = 0; j < jSize; j++) order[j] = j;
        Arrays.sort(order, Comparator.comparingDouble((Integer j) -> total[j]).reversed());

        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("排名,lineIdx,lineName,全时段总需求,需求占比,累计占比");
            bw.newLine();
            double cum = 0.0;
            for (int r = 0; r < jSize; r++) {
                int j = order[r];
                double share = (grand > 1e-12 ? total[j] / grand : 0.0);
                cum += share;
                bw.write(String.format(US, "%d,%d,%s,%.10f,%.10f,%.10f%n",
                        r + 1, j, csvSafe(hist.laneNames.get(j)), total[j], share, cum));
            }
        }
    }

    private static void writeSupplierPairSimilarity(Path outFile, ProcurementParams params) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("supplierI,supplierJ,commonLines,unionLines,overlapRatio,pearson,cosine,rmse,meanAbsPctDiff\n");
            for (int i = 0; i < params.I; i++) {
                for (int k = i + 1; k < params.I; k++) {
                    PairMetric m = computePairMetric(params, i, k);
                    bw.write(String.format(US, "%s,%s,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f%n",
                            params.carriers.get(i), params.carriers.get(k),
                            m.commonLines, m.unionLines, m.overlapRatio, m.pearson, m.cosine, m.rmse, m.meanAbsPctDiff));
                }
            }
        }
    }

    private static void writeSupplierSimilaritySummary(Path outFile, ProcurementParams params) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("supplier,avgPearsonToOthers,avgCosineToOthers,avgRmseToOthers,avgMeanAbsPctDiffToOthers\n");
            for (int i = 0; i < params.I; i++) {
                double sumPearson = 0, sumCos = 0, sumRmse = 0, sumPct = 0;
                int cnt = 0;
                for (int k = 0; k < params.I; k++) {
                    if (k == i) continue;
                    PairMetric m = computePairMetric(params, i, k);
                    if (m.commonLines <= 0) continue;
                    sumPearson += m.pearson;
                    sumCos += m.cosine;
                    sumRmse += m.rmse;
                    sumPct += m.meanAbsPctDiff;
                    cnt++;
                }
                double d = Math.max(1, cnt);
                bw.write(String.format(US, "%s,%.10f,%.10f,%.10f,%.10f%n",
                        params.carriers.get(i),
                        sumPearson / d, sumCos / d, sumRmse / d, sumPct / d));
            }
        }
    }

    private static void writeSupplierLineCostOrder(Path outFile, ProcurementParams params, List<String> laneNames) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("供应商,供应商索引,线路排名,lineIdx,lineName,运输成本r_ij");
            bw.newLine();
            for (int i = 0; i < params.I; i++) {
                List<LineCost> rows = new ArrayList<>();
                for (int j = 0; j < params.J; j++) {
                    if (!params.eligible[i][j]) continue;
                    rows.add(new LineCost(j, laneNames.get(j), params.r[i][j]));
                }
                rows.sort(Comparator.comparingDouble(lc -> lc.cost));
                for (int k = 0; k < rows.size(); k++) {
                    LineCost lc = rows.get(k);
                    bw.write(String.format(US, "%s,%d,%d,%d,%s,%.10f%n",
                            params.carriers.get(i), i, k + 1, lc.lineIdx, csvSafe(lc.lineName), lc.cost));
                }
            }
        }
    }

    private static PairMetric computePairMetric(ProcurementParams p, int i, int k) {
        List<Double> xi = new ArrayList<>();
        List<Double> yk = new ArrayList<>();

        int union = 0;
        for (int j = 0; j < p.J; j++) {
            boolean ei = p.eligible[i][j];
            boolean ek = p.eligible[k][j];
            if (ei || ek) union++;
            if (ei && ek) {
                xi.add(p.r[i][j]);
                yk.add(p.r[k][j]);
            }
        }

        int n = xi.size();
        if (n == 0) {
            return new PairMetric(0, union, 0.0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double[] x = toArray(xi);
        double[] y = toArray(yk);
        double pearson = pearson(x, y);
        double cosine = cosine(x, y);
        double rmse = rmse(x, y);
        double mapd = meanAbsPctDiff(x, y);
        return new PairMetric(n, union, (union > 0 ? (double) n / union : 0.0), pearson, cosine, rmse, mapd);
    }

    private static TrialTables buildTrialTables(ExperimentBatches rolling, List<String> laneNames,
                                                ProcurementParams params, int k1Lag, double topShare, int maxTrials) {
        List<String> headRows = new ArrayList<>();
        List<String> summaryRows = new ArrayList<>();

        String headHeader = "k1Lag,trialId,testIdx,trainSize,lineIdx,lineName,meanDemand,share,cumShare,headThreshold,"
                + "bestSupplier,bestCost,top3SuppliersByCost";
        String summaryHeader = "k1Lag,trialId,testIdx,trainSize,totalMeanDemand,top1Share,headLineCount,headDemandShare,"
                + "headLines,headBestSuppliers";
        headRows.add(headHeader);
        summaryRows.add(summaryHeader);

        int tMax = (maxTrials <= 0 ? rolling.size() : Math.min(maxTrials, rolling.size()));
        for (int t = 0; t < tMax; t++) {
            List<Sample> train = rolling.trainSets.get(t);
            int testIdx = rolling.testIndex.get(t);
            int jSize = laneNames.size();

            double[] meanDemand = new double[jSize];
            for (Sample s : train) {
                double[] d = s.demand();
                for (int j = 0; j < jSize; j++) {
                    meanDemand[j] += d[j];
                }
            }
            double denom = Math.max(1, train.size());
            for (int j = 0; j < jSize; j++) {
                meanDemand[j] /= denom;
            }

            double total = 0.0;
            for (double v : meanDemand) total += v;

            Integer[] order = new Integer[jSize];
            for (int j = 0; j < jSize; j++) order[j] = j;
            Arrays.sort(order, Comparator.comparingDouble((Integer j) -> meanDemand[j]).reversed());

            double cum = 0.0;
            int headCount = 0;
            List<String> headLineNames = new ArrayList<>();
            List<String> headBestSuppliers = new ArrayList<>();

            for (int rank = 0; rank < jSize; rank++) {
                int j = order[rank];
                double share = (total > 1e-12 ? meanDemand[j] / total : 0.0);
                double nextCum = cum + share;
                boolean isHead = (rank == 0) || (cum < topShare);
                if (!isHead) break;

                cum = nextCum;
                headCount++;

                BestSupplier best = bestSupplierForLine(params, j);
                String top3 = top3SuppliersForLine(params, j);

                headLineNames.add(laneNames.get(j));
                headBestSuppliers.add(best.supplierName);

                headRows.add(String.format(US,
                        "%d,%d,%d,%d,%d,%s,%.10f,%.10f,%.10f,%.4f,%s,%.10f,%s",
                        k1Lag, t, testIdx, train.size(), j,
                        csvSafe(laneNames.get(j)),
                        meanDemand[j], share, cum, topShare,
                        csvSafe(best.supplierName), best.cost, csvQuote(top3)));
            }

            double top1Share = (jSize > 0 && total > 1e-12 ? meanDemand[order[0]] / total : 0.0);
            summaryRows.add(String.format(US,
                    "%d,%d,%d,%d,%.10f,%.10f,%d,%.10f,%s,%s",
                    k1Lag, t, testIdx, train.size(),
                    total, top1Share, headCount, cum,
                    csvQuote(String.join("|", headLineNames)),
                    csvQuote(String.join("|", headBestSuppliers))));
        }

        return new TrialTables(headRows, summaryRows);
    }

    private static boolean appendRows(Path outFile, List<String> rowsWithHeader, boolean alreadyHasHeader) throws Exception {
        if (rowsWithHeader == null || rowsWithHeader.isEmpty()) return alreadyHasHeader;
        try (BufferedWriter bw = Files.newBufferedWriter(outFile,
                alreadyHasHeader ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.APPEND}
                        : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING})) {
            int start = 0;
            if (alreadyHasHeader) start = 1;
            for (int i = start; i < rowsWithHeader.size(); i++) {
                bw.write(rowsWithHeader.get(i));
                bw.newLine();
            }
        }
        return true;
    }

    private static BestSupplier bestSupplierForLine(ProcurementParams params, int lineJ) {
        double best = Double.POSITIVE_INFINITY;
        int bestI = -1;
        for (int i = 0; i < params.I; i++) {
            if (!params.eligible[i][lineJ]) continue;
            double c = params.r[i][lineJ];
            if (c < best) {
                best = c;
                bestI = i;
            }
        }
        if (bestI < 0) return new BestSupplier("NA", Double.NaN);
        return new BestSupplier(params.carriers.get(bestI), best);
    }

    private static String top3SuppliersForLine(ProcurementParams params, int lineJ) {
        List<CarrierCost> list = new ArrayList<>();
        for (int i = 0; i < params.I; i++) {
            if (!params.eligible[i][lineJ]) continue;
            list.add(new CarrierCost(params.carriers.get(i), params.r[i][lineJ]));
        }
        list.sort(Comparator.comparingDouble(cc -> cc.cost));
        StringBuilder sb = new StringBuilder();
        int lim = Math.min(3, list.size());
        for (int i = 0; i < lim; i++) {
            if (i > 0) sb.append("|");
            sb.append(list.get(i).supplier).append(":")
                    .append(String.format(US, "%.6f", list.get(i).cost));
        }
        return sb.toString();
    }

    private static String csvSafe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("|")) {
            return csvQuote(s);
        }
        return s;
    }

    private static String csvQuote(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static void writeOutputGuide(Path outFile,
                                         int maxTrials,
                                         double topShare,
                                         int[] k1List,
                                         Path costGenTxt,
                                         Path supplierPairCsv,
                                         Path supplierSummaryCsv,
                                         Path supplierLineOrderCsv,
                                         Path overallDemandCsv,
                                         Path trialHeadCsv,
                                         Path trialSummaryCsv) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("输出文件说明\n");
            bw.write("\n");
            bw.write("本次参数\n");
            bw.write("1) rolling trial 个数上限 maxTrials = "
                    + (maxTrials <= 0 ? "全部" : String.valueOf(maxTrials)) + "\n");
            bw.write("2) 需求大头阈值 topShare = " + topShare + "（例如 0.8 表示累计需求占比到 80%）\n");
            bw.write("3) 使用的 k1 列表 = " + Arrays.toString(k1List) + "\n");
            bw.write("\n");

            bw.write("文件1: " + costGenTxt.getFileName() + "\n");
            bw.write("作用: 说明当前代码如何生成供应商-线路成本。\n");
            bw.write("\n");

            bw.write("文件2: " + supplierPairCsv.getFileName() + "\n");
            bw.write("作用: 供应商两两成本相似度。\n");
            bw.write("字段说明:\n");
            bw.write("- commonLines: 两个供应商都可承接的线路数\n");
            bw.write("- unionLines: 至少一个供应商可承接的线路数\n");
            bw.write("- overlapRatio = commonLines / unionLines\n");
            bw.write("- pearson: commonLines 上的皮尔逊相关系数\n");
            bw.write("- cosine: commonLines 上的余弦相似度\n");
            bw.write("- rmse: commonLines 上的成本均方根误差\n");
            bw.write("- meanAbsPctDiff: commonLines 上的平均绝对百分差，分母为 (|x|+|y|)/2\n");
            bw.write("\n");

            bw.write("文件3: " + supplierSummaryCsv.getFileName() + "\n");
            bw.write("作用: 每个供应商相对其他供应商的平均相似度指标。\n");
            bw.write("\n");

            bw.write("文件4: " + overallDemandCsv.getFileName() + "\n");
            bw.write("作用: 全时段总需求分布（按线路排序），可直接看需求大头线路。\n");
            bw.write("关键列: 需求占比、累计占比。\n");
            bw.write("\n");

            bw.write("文件5: " + supplierLineOrderCsv.getFileName() + "\n");
            bw.write("作用: 对每个供应商，将其可服务线路按运输成本 r_ij 从低到高排序。\n");
            bw.write("关键列: 供应商、线路排名、lineName、运输成本 r_ij。\n");
            bw.write("\n");

            bw.write("文件6: " + trialHeadCsv.getFileName() + "\n");
            bw.write("作用: 每个 rolling trial 中的需求大头线路明细，以及该线路最低成本供应商。\n");
            bw.write("head 规则: 按线路平均需求从高到低累计，直到累计占比达到 topShare。\n");
            bw.write("\n");

            bw.write("文件7: " + trialSummaryCsv.getFileName() + "\n");
            bw.write("作用: 每个 rolling trial 的需求集中度汇总。\n");
            bw.write("关键列:\n");
            bw.write("- top1Share: 最大需求线路占比\n");
            bw.write("- headLineCount: 达到 topShare 需要的线路数\n");
            bw.write("- headDemandShare: 这些 head 线路累计占比\n");
        }
    }

    private static double[] toArray(List<Double> xs) {
        double[] a = new double[xs.size()];
        for (int i = 0; i < xs.size(); i++) a[i] = xs.get(i);
        return a;
    }

    private static double pearson(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n < 2) return Double.NaN;
        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) {
            mx += x[i];
            my += y[i];
        }
        mx /= n;
        my /= n;
        double num = 0, vx = 0, vy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - mx;
            double dy = y[i] - my;
            num += dx * dy;
            vx += dx * dx;
            vy += dy * dy;
        }
        if (vx <= 1e-18 || vy <= 1e-18) return Double.NaN;
        return num / Math.sqrt(vx * vy);
    }

    private static double cosine(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n == 0) return Double.NaN;
        double dot = 0, nx = 0, ny = 0;
        for (int i = 0; i < n; i++) {
            dot += x[i] * y[i];
            nx += x[i] * x[i];
            ny += y[i] * y[i];
        }
        if (nx <= 1e-18 || ny <= 1e-18) return Double.NaN;
        return dot / (Math.sqrt(nx) * Math.sqrt(ny));
    }

    private static double rmse(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n == 0) return Double.NaN;
        double s = 0;
        for (int i = 0; i < n; i++) {
            double d = x[i] - y[i];
            s += d * d;
        }
        return Math.sqrt(s / n);
    }

    private static double meanAbsPctDiff(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n == 0) return Double.NaN;
        double s = 0;
        int c = 0;
        for (int i = 0; i < n; i++) {
            double denom = 0.5 * (Math.abs(x[i]) + Math.abs(y[i]));
            if (denom <= 1e-12) continue;
            s += Math.abs(x[i] - y[i]) / denom;
            c++;
        }
        return (c == 0 ? Double.NaN : s / c);
    }

    private static class PairMetric {
        final int commonLines;
        final int unionLines;
        final double overlapRatio;
        final double pearson;
        final double cosine;
        final double rmse;
        final double meanAbsPctDiff;

        PairMetric(int commonLines, int unionLines, double overlapRatio,
                   double pearson, double cosine, double rmse, double meanAbsPctDiff) {
            this.commonLines = commonLines;
            this.unionLines = unionLines;
            this.overlapRatio = overlapRatio;
            this.pearson = pearson;
            this.cosine = cosine;
            this.rmse = rmse;
            this.meanAbsPctDiff = meanAbsPctDiff;
        }
    }

    private static class BestSupplier {
        final String supplierName;
        final double cost;

        BestSupplier(String supplierName, double cost) {
            this.supplierName = supplierName;
            this.cost = cost;
        }
    }

    private static class CarrierCost {
        final String supplier;
        final double cost;

        CarrierCost(String supplier, double cost) {
            this.supplier = supplier;
            this.cost = cost;
        }
    }

    private static class LineCost {
        final int lineIdx;
        final String lineName;
        final double cost;

        LineCost(int lineIdx, String lineName, double cost) {
            this.lineIdx = lineIdx;
            this.lineName = lineName;
            this.cost = cost;
        }
    }

    private static class TrialTables {
        final List<String> headRows;
        final List<String> summaryRows;

        TrialTables(List<String> headRows, List<String> summaryRows) {
            this.headRows = headRows;
            this.summaryRows = summaryRows;
        }
    }
}

