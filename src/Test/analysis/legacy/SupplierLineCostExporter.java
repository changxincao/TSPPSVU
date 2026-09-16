package Test.analysis.legacy;

import Basic.PeriodData;
import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.CsvHistoryLoader;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.calculateHelper.KernelType;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 供应商服务线路成本导出器。
 *
 * 这个类用于把当前实例中“每个供应商可服务哪些线路、这些线路的合同价从低到高如何排序”导出成表。
 * 除了合同成本排序外，还会补充每条 line 的全时段总需求、需求占比和需求排名，
 * 用来回答“低成本线路是否同时也是高需求线路”这类问题。
 */
public class SupplierLineCostExporter {
    private static final Locale US = Locale.US;

    public static void main(String[] args) throws Exception {
        Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
        Path outFile = Paths.get(args.length > 1 ? args[1] : "./analysis/供应商服务线路成本从低到高.csv");
        int numCarriers = (args.length > 2 ? Integer.parseInt(args[2]) : 15);

        Files.createDirectories(outFile.toAbsolutePath().getParent());

        Config cfg = buildBaseConfig();
        InstanceGenerator.GenConfig genCfg = buildGenConfig();

        CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);
        SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfg);
        double[] dBase = buildBaselineDemand(br);
        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);
        DemandStats demandStats = buildDemandStats(hist);

        writeSupplierLineCostOrder(outFile, params, hist.laneNames, demandStats);
        System.out.println("[DONE] " + outFile.toAbsolutePath());
    }

    private static Config buildBaseConfig() {
        Config cfg = new Config();
        cfg.fillMissingDates = true;
        cfg.aggregationDays = 7;
        cfg.k1LagPeriods = 2;
        cfg.kernelType = KernelType.EXPONENTIAL;
        cfg.standardizeTheta = true;
        cfg.bandwidthH = 1.0;

        cfg.featureFlags.includeLagDemand = true;
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

    private static void writeSupplierLineCostOrder(Path outFile, ProcurementParams params, List<String> laneNames,
                                                   DemandStats demandStats) throws Exception {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("供应商,供应商索引,线路排名,lineIdx,lineName,运输成本r_ij,line总需求,line需求占总量比,line需求排名(总量降序)");
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
                    double lineDemand = demandStats.lineTotalDemand[lc.lineIdx];
                    double lineShare = demandStats.lineDemandShare[lc.lineIdx];
                    int lineRank = demandStats.lineDemandRank[lc.lineIdx];
                    bw.write(String.format(US, "%s,%d,%d,%d,%s,%.10f,%.10f,%.10f,%d%n",
                            params.carriers.get(i), i, k + 1, lc.lineIdx, csvSafe(lc.lineName), lc.cost,
                            lineDemand, lineShare, lineRank));
                }
            }
        }
    }

    private static DemandStats buildDemandStats(CsvHistoryLoader.HistoryLoadResult hist) {
        int jSize = hist.laneNames.size();
        double[] totalDemand = new double[jSize];
        for (Basic.HistoricalDay d : hist.days) {
            for (int j = 0; j < jSize; j++) {
                totalDemand[j] += d.laneDemand[j];
            }
        }
        double grand = 0.0;
        for (double v : totalDemand) grand += v;

        Integer[] order = new Integer[jSize];
        for (int j = 0; j < jSize; j++) order[j] = j;
        java.util.Arrays.sort(order, Comparator.comparingDouble((Integer j) -> totalDemand[j]).reversed());

        int[] rank = new int[jSize];
        for (int r = 0; r < jSize; r++) rank[order[r]] = r + 1;

        double[] share = new double[jSize];
        for (int j = 0; j < jSize; j++) {
            share[j] = (grand > 1e-12 ? totalDemand[j] / grand : 0.0);
        }
        return new DemandStats(totalDemand, share, rank);
    }

    private static String csvSafe(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("|")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
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

    private static class DemandStats {
        final double[] lineTotalDemand;
        final double[] lineDemandShare;
        final int[] lineDemandRank;

        DemandStats(double[] lineTotalDemand, double[] lineDemandShare, int[] lineDemandRank) {
            this.lineTotalDemand = lineTotalDemand;
            this.lineDemandShare = lineDemandShare;
            this.lineDemandRank = lineDemandRank;
        }
    }
}

