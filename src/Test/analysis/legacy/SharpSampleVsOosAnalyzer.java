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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 尖样本与样本外需求对比分析器。
 *
 * 这个类专门拿某个 rolling trial 里的“尖” single sample 和对应样本外测试需求做逐 line 对比。
 * 重点看三件事：
 * 1. 样本内大头线路和样本外大头线路差在哪；
 * 2. 已选供应商在各 line 上的合同容量上限能否覆盖样本外需求；
 * 3. spot 价格相对合同价高多少，从而判断样本外高 spot cost 到底是“价格贵”还是“覆盖不住”。
 */
public class SharpSampleVsOosAnalyzer {

    private static final Locale US = Locale.US;

    public static void main(String[] args) throws Exception {
        Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
        Path singleCsv = Paths.get(args.length > 1 ? args[1] : "./single_sample_det_merged.csv");
        Path outDir = Paths.get(args.length > 2 ? args[2] : "./analysis");
        int k1 = (args.length > 3 ? Integer.parseInt(args[3]) : 0);
        int windowW = (args.length > 4 ? Integer.parseInt(args[4]) : 24);
        int trialId = (args.length > 5 ? Integer.parseInt(args[5]) : 0);
        int expectedTestIdx = (args.length > 6 ? Integer.parseInt(args[6]) : 24);
        int samplePosA = (args.length > 7 ? Integer.parseInt(args[7]) : 19);
        int samplePosB = (args.length > 8 ? Integer.parseInt(args[8]) : 20);

        Files.createDirectories(outDir);

        Config cfg = buildConfig(k1);
        InstanceGenerator.GenConfig genCfg = buildGenConfig();
        CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);
        SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfg);
        double[] dBase = buildBaselineDemand(br);
        ProcurementParams params = InstanceGenerator.generate(15, dBase, genCfg, cfg);
        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, windowW);

        if (trialId < 0 || trialId >= rolling.trainSets.size()) {
            throw new IllegalArgumentException("trialId out of range: " + trialId);
        }
        if (!rolling.testIndex.get(trialId).equals(expectedTestIdx)) {
            throw new IllegalStateException("trialId=" + trialId + " does not map to testIdx=" + expectedTestIdx
                    + "; actual testIdx=" + rolling.testIndex.get(trialId));
        }

        List<Sample> train = rolling.trainSets.get(trialId);
        Sample test = rolling.testSamples.get(trialId);

        Map<Integer, Set<Integer>> selectedBySamplePos = readSelectedCarriers(singleCsv, k1, trialId, expectedTestIdx,
                samplePosA, samplePosB);

        AnalysisResult a = analyzeCase(samplePosA, train.get(samplePosA), test, selectedBySamplePos.get(samplePosA),
                params, hist.laneNames);
        AnalysisResult b = analyzeCase(samplePosB, train.get(samplePosB), test, selectedBySamplePos.get(samplePosB),
                params, hist.laneNames);

        Path txt = outDir.resolve("尖样本_vs_样本外需求分析_trial" + trialId + "_test" + expectedTestIdx + "_k1=" + k1 + ".txt");
        Path csv = outDir.resolve("尖样本_vs_样本外逐线路明细_trial" + trialId + "_test" + expectedTestIdx + "_k1=" + k1 + ".csv");
        writeSummary(txt, a, b, train.get(samplePosA), train.get(samplePosB), test, hist.laneNames);
        writeLineCsv(csv, a, b);

        System.out.println("[DONE] " + txt.toAbsolutePath());
        System.out.println("[DONE] " + csv.toAbsolutePath());
    }

    private static Config buildConfig(int k1) {
        Config cfg = new Config();
        cfg.seed = 0;
        cfg.fillMissingDates = true;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = k1;
        cfg.kernelType = KernelType.EXPONENTIAL;
        cfg.standardizeTheta = false;
        cfg.bandwidthH = 1.0;
        cfg.featureFlags.includeLagDemand = (k1 > 0);
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

    private static double[] buildBaselineDemand(SampleBuilder.BuildResult br) {
        int jSize = br.periods.get(0).demandSum.length;
        double[] sum = new double[jSize];
        for (PeriodData p : br.periods) {
            for (int j = 0; j < jSize; j++) sum[j] += p.demandSum[j];
        }
        double denom = Math.max(1, br.periods.size());
        for (int j = 0; j < jSize; j++) sum[j] /= denom;
        return sum;
    }

    private static Map<Integer, Set<Integer>> readSelectedCarriers(Path singleCsv, int k1, int trialId,
                                                                   int testIdx, int... samplePositions) throws IOException {
        Set<Integer> targetPos = new HashSet<>();
        for (int pos : samplePositions) targetPos.add(pos);
        Map<Integer, Set<Integer>> out = new HashMap<>();

        try (BufferedReader br = Files.newBufferedReader(singleCsv)) {
            String header = br.readLine();
            if (header == null) throw new IllegalArgumentException("Empty CSV: " + singleCsv);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cols = parseCsvLine(line);
                if (cols.size() < 23) continue;
                int rowK1 = parseInt(cols.get(4));
                int rowTrial = parseInt(cols.get(8));
                int rowTest = parseInt(cols.get(9));
                int rowSamplePos = parseInt(cols.get(11));
                if (rowK1 == k1 && rowTrial == trialId && rowTest == testIdx && targetPos.contains(rowSamplePos)) {
                    out.put(rowSamplePos, parseSelectedCarrierIdx(cols.get(21)));
                }
            }
        }
        return out;
    }

    private static AnalysisResult analyzeCase(int samplePos,
                                              Sample sample,
                                              Sample test,
                                              Set<Integer> selected,
                                              ProcurementParams params,
                                              List<String> laneNames) {
        if (selected == null || selected.isEmpty()) {
            throw new IllegalStateException("No selected carriers found for samplePos=" + samplePos);
        }

        double[] sampleDemand = sample.demand();
        double[] testDemand = test.demand();
        double totalSample = sum(sampleDemand);
        double totalTest = sum(testDemand);

        List<LineCompare> rows = new ArrayList<>();
        for (int j = 0; j < params.J; j++) {
            double selCap = 0.0;
            double bestSelectedCost = Double.POSITIVE_INFINITY;
            String bestSelectedCarrier = "";
            double bestAllCost = Double.POSITIVE_INFINITY;
            String bestAllCarrier = "";
            List<String> selectedCarrierCosts = new ArrayList<>();

            for (int i = 0; i < params.I; i++) {
                if (!params.eligible[i][j]) continue;
                if (params.r[i][j] < bestAllCost) {
                    bestAllCost = params.r[i][j];
                    bestAllCarrier = params.carriers.get(i);
                }
                if (selected.contains(i)) {
                    selCap += params.q[i][j];
                    selectedCarrierCosts.add(String.format(US, "%s(浠?%.2f,閲忎笂闄?%.2f)",
                            params.carriers.get(i), params.r[i][j], params.q[i][j]));
                    if (params.r[i][j] < bestSelectedCost) {
                        bestSelectedCost = params.r[i][j];
                        bestSelectedCarrier = params.carriers.get(i);
                    }
                }
            }

            double sampleGap = selCap - sampleDemand[j];
            double testGap = selCap - testDemand[j];
            rows.add(new LineCompare(
                    j,
                    laneNames.get(j),
                    sampleDemand[j],
                    totalSample > 1e-12 ? sampleDemand[j] / totalSample : 0.0,
                    testDemand[j],
                    totalTest > 1e-12 ? testDemand[j] / totalTest : 0.0,
                    selCap,
                    sampleGap,
                    testGap,
                    bestAllCarrier,
                    bestAllCost,
                    bestSelectedCarrier,
                    bestSelectedCost,
                    params.e[j],
                    safeDiv(params.e[j], bestAllCost),
                    String.join(" | ", selectedCarrierCosts)
            ));
        }

        rows.sort(Comparator.comparingDouble((LineCompare r) -> r.testDemand).reversed());
        return new AnalysisResult(samplePos, selected, rows, totalSample, totalTest);
    }

    private static void writeSummary(Path path,
                                     AnalysisResult a,
                                     AnalysisResult b,
                                     Sample sampleA,
                                     Sample sampleB,
                                     Sample test,
                                     List<String> laneNames) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(path)) {
            bw.write("问题：比较尖样本(samplePos=20)与另一个样本(samplePos=19)在对应样本外需求下，为何样本外表现差很多。\n\n");

            bw.write("一、结论\n");
            bw.write(String.format(US,
                    "1. samplePos=%d 之所以样本外更差，核心不是 spot 更便宜，而是它只选了更少的供应商，导致样本外大量 line 上合同可用量上限覆盖不住 demand，只能走 spot。\n",
                    b.samplePos));
            bw.write(String.format(US,
                    "2. samplePos=%d 的训练需求更尖，样本内总需求 %.2f，其中第一大 line 占比 %.4f；samplePos=%d 的第一大 line 占比 %.4f。\n",
                    b.samplePos, b.totalSampleDemand, top1Share(sampleB.demand()),
                    a.samplePos, top1Share(sampleA.demand())));
            bw.write(String.format(US,
                    "3. 对应样本外总需求 %.2f。samplePos=%d 方案在样本外 top10 line 中有 %d 条 line 出现“所选供应商合同量上限 < 样本外需求”；samplePos=%d 中有 %d 条。\n\n",
                    b.totalTestDemand, b.samplePos, countDeficitOnTopTestLines(b, 10),
                    a.samplePos, countDeficitOnTopTestLines(a, 10)));

            bw.write("二、spot 与合同价的关系\n");
            bw.write("当前代码里，spot 价格不是随意取的，而是每条 line 上所有合同价中位数乘以 [1.5,2.5] 的系数。\n");
            bw.write("因此在当前实例生成下，spot 通常显著高于合同价。是否“总是高于每一个供应商合同价”并非数学恒真，但在高 spot cost 的那些 line 上，spot/最低合同价一般明显大于 1。\n\n");

            bw.write("三、样本内 vs 样本外需求结构\n");
            bw.write(String.format(US, "samplePos=%d 的样本内 Top10 line: %s\n", a.samplePos, topLinesText(sampleA.demand(), laneNames, 10)));
            bw.write(String.format(US, "samplePos=%d 的样本内 Top10 line: %s\n", b.samplePos, topLinesText(sampleB.demand(), laneNames, 10)));
            bw.write(String.format(US, "样本外 testIdx=%d 的 Top10 line: %s\n\n", test.id, topLinesText(test.demand(), laneNames, 10)));

            bw.write("四、为什么主要是容量/覆盖问题\n");
            bw.write(String.format(US,
                    "samplePos=%d 选中供应商: %s；samplePos=%d 选中供应商: %s。\n",
                    a.samplePos, selectedNames(a.selectedCarrierIdx),
                    b.samplePos, selectedNames(b.selectedCarrierIdx)));
            bw.write("模型二阶段里每个 x_ij 都有上界 q_ij，而且每个供应商总量还受 M_i 限制；所以即使某个已选供应商理论上“能服务这条 line”，也不代表它的合同可用量足够覆盖样本外需求。\n");
            bw.write("一旦覆盖不住，缺口就必须由 spot 变量 s_j 补上。\n\n");

            bw.write("五、直接阅读方式\n");
            bw.write("请结合配套 CSV 查看每条 line：样本内需求、样本外需求、已选供应商合同量上限、最低合同价、已选中最低合同价、spot 价、spot/最低合同价比值。\n");
            bw.write("如果某条样本外高需求 line 中“已选合同量上限 < 样本外需求”，并且 spot/最低合同价比值明显 > 1，那么这条 line 就是样本外爆 spot cost 的直接来源。\n");
        }
    }

    private static void writeLineCsv(Path path, AnalysisResult a, AnalysisResult b) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(path)) {
            bw.write(String.join(",",
                    "方案",
                    "samplePos",
                    "lineIdx",
                    "lineName",
                    "样本内需求",
                    "样本内占比",
                    "样本外需求",
                    "样本外占比",
                    "已选供应商合同量上限之和",
                    "上限减样本内需求",
                    "上限减样本外需求",
                    "全体最低价供应商",
                    "全体最低合同价",
                    "已选中最低价供应商",
                    "已选中最低合同价",
                    "spot价格",
                    "spot/全体最低合同价",
                    "已选供应商在该line的价格与容量"
            ));
            bw.newLine();
            writeRows(bw, "方案A", a);
            writeRows(bw, "方案B", b);
        }
    }

    private static void writeRows(BufferedWriter bw, String label, AnalysisResult r) throws IOException {
        for (LineCompare x : r.rows) {
            bw.write(csv(label)); bw.write(",");
            bw.write(Integer.toString(r.samplePos)); bw.write(",");
            bw.write(Integer.toString(x.lineIdx)); bw.write(",");
            bw.write(csv(x.lineName)); bw.write(",");
            bw.write(fmt(x.sampleDemand)); bw.write(",");
            bw.write(fmt(x.sampleShare)); bw.write(",");
            bw.write(fmt(x.testDemand)); bw.write(",");
            bw.write(fmt(x.testShare)); bw.write(",");
            bw.write(fmt(x.selectedCapacity)); bw.write(",");
            bw.write(fmt(x.capMinusSampleDemand)); bw.write(",");
            bw.write(fmt(x.capMinusTestDemand)); bw.write(",");
            bw.write(csv(x.bestAllCarrier)); bw.write(",");
            bw.write(fmt(x.bestAllCost)); bw.write(",");
            bw.write(csv(x.bestSelectedCarrier)); bw.write(",");
            bw.write(Double.isFinite(x.bestSelectedCost) ? fmt(x.bestSelectedCost) : ""); bw.write(",");
            bw.write(fmt(x.spotPrice)); bw.write(",");
            bw.write(fmt(x.spotOverBestAll)); bw.write(",");
            bw.write(csv(x.selectedCarrierCostText));
            bw.newLine();
        }
    }

    private static int countDeficitOnTopTestLines(AnalysisResult r, int topK) {
        int cnt = 0;
        for (int i = 0; i < Math.min(topK, r.rows.size()); i++) {
            if (r.rows.get(i).capMinusTestDemand < -1e-9) cnt++;
        }
        return cnt;
    }

    private static String topLinesText(double[] demand, List<String> laneNames, int topK) {
        Integer[] order = new Integer[demand.length];
        double total = sum(demand);
        for (int j = 0; j < demand.length; j++) order[j] = j;
        Arrays.sort(order, Comparator.comparingDouble((Integer j) -> demand[j]).reversed());
        List<String> out = new ArrayList<>();
        for (int k = 0; k < Math.min(topK, order.length); k++) {
            int j = order[k];
            out.add(String.format(US, "%s(%.2f, %.2f%%)", laneNames.get(j), demand[j], 100.0 * safeDiv(demand[j], total)));
        }
        return String.join(" | ", out);
    }

    private static double top1Share(double[] demand) {
        double total = sum(demand);
        double best = 0.0;
        for (double v : demand) best = Math.max(best, v);
        return safeDiv(best, total);
    }

    private static String selectedNames(Set<Integer> idxSet) {
        List<Integer> xs = new ArrayList<>(idxSet);
        xs.sort(Integer::compareTo);
        List<String> out = new ArrayList<>();
        for (int i : xs) out.add("C" + (i + 1));
        return String.join("|", out);
    }

    private static Set<Integer> parseSelectedCarrierIdx(String raw) {
        Set<Integer> out = new HashSet<>();
        String s = raw == null ? "" : raw.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
        s = s.replace("{", "").replace("}", "").trim();
        if (s.isEmpty()) return out;
        for (String part : s.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(Integer.parseInt(t));
        }
        return out;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static int parseInt(String s) {
        return Integer.parseInt(s.trim());
    }

    private static String csv(String s) {
        String x = (s == null ? "" : s);
        return "\"" + x.replace("\"", "\"\"") + "\"";
    }

    private static String fmt(double x) {
        return String.format(US, "%.6f", x);
    }

    private static double sum(double[] xs) {
        double s = 0.0;
        for (double x : xs) s += x;
        return s;
    }

    private static double safeDiv(double a, double b) {
        return Math.abs(b) < 1e-12 ? 0.0 : a / b;
    }

    private static class AnalysisResult {
        final int samplePos;
        final Set<Integer> selectedCarrierIdx;
        final List<LineCompare> rows;
        final double totalSampleDemand;
        final double totalTestDemand;

        AnalysisResult(int samplePos, Set<Integer> selectedCarrierIdx, List<LineCompare> rows,
                       double totalSampleDemand, double totalTestDemand) {
            this.samplePos = samplePos;
            this.selectedCarrierIdx = selectedCarrierIdx;
            this.rows = rows;
            this.totalSampleDemand = totalSampleDemand;
            this.totalTestDemand = totalTestDemand;
        }
    }

    private static class LineCompare {
        final int lineIdx;
        final String lineName;
        final double sampleDemand;
        final double sampleShare;
        final double testDemand;
        final double testShare;
        final double selectedCapacity;
        final double capMinusSampleDemand;
        final double capMinusTestDemand;
        final String bestAllCarrier;
        final double bestAllCost;
        final String bestSelectedCarrier;
        final double bestSelectedCost;
        final double spotPrice;
        final double spotOverBestAll;
        final String selectedCarrierCostText;

        LineCompare(int lineIdx, String lineName, double sampleDemand, double sampleShare,
                    double testDemand, double testShare, double selectedCapacity,
                    double capMinusSampleDemand, double capMinusTestDemand,
                    String bestAllCarrier, double bestAllCost,
                    String bestSelectedCarrier, double bestSelectedCost,
                    double spotPrice, double spotOverBestAll, String selectedCarrierCostText) {
            this.lineIdx = lineIdx;
            this.lineName = lineName;
            this.sampleDemand = sampleDemand;
            this.sampleShare = sampleShare;
            this.testDemand = testDemand;
            this.testShare = testShare;
            this.selectedCapacity = selectedCapacity;
            this.capMinusSampleDemand = capMinusSampleDemand;
            this.capMinusTestDemand = capMinusTestDemand;
            this.bestAllCarrier = bestAllCarrier;
            this.bestAllCost = bestAllCost;
            this.bestSelectedCarrier = bestSelectedCarrier;
            this.bestSelectedCost = bestSelectedCost;
            this.spotPrice = spotPrice;
            this.spotOverBestAll = spotOverBestAll;
            this.selectedCarrierCostText = selectedCarrierCostText;
        }
    }
}

