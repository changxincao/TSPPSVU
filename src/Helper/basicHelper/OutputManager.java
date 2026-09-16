package Helper.basicHelper;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import Basic.Data;
import Basic.Sample;
import Helper.basicHelper.InstanceGenerator.GenConfig;
import Model.DROBenders;
import Model.SAAModel;
import Model.Solution;
import Model.SolveMode;

import java.io.BufferedWriter;
import java.util.Locale;

public class OutputManager {
    public final Path root;
    public final Path logsDir;
    public final Path resultsDir;

    public OutputManager(Path inputFile) throws IOException {
        String base = stripExt(inputFile.getFileName().toString());
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path outRoot = buildOutRoot(new GenConfig());
        this.root = outRoot.resolve(base + "_" + ts);
        this.logsDir = root.resolve("logs");
        this.resultsDir = root.resolve("results");
        Files.createDirectories(logsDir);
        Files.createDirectories(resultsDir);
    }

    public OutputManager(String tag, int sth, Config cfg,GenConfig genCfg ) throws IOException {
        // sth 表示当前正在测试的第 sth 个 sample(trial)

        String fill = "fillMiss_" + cfg.fillMissingDates;
        String stand = "stand_" + cfg.standardizeTheta;
        String k1 = "k1Lag_" + cfg.k1LagPeriods;
        String kernel = "kernel_" + (cfg.kernelType == null ? "NA" : cfg.kernelType.name());

        // SAA 时 C 没意义，目录用 NA，避免出现“同一个配置不同 C 但跑的是同一个 SAA”
        String cStr = (cfg.solveMode == SolveMode.SAA ? "C_NA" : ("C_" + fmtC(cfg.C_h)));
        String lamStr = (cfg.solveMode == SolveMode.RCSAA ? ("lambda_" + fmtC(cfg.lambda)) : "lambda_NA");

        this.root = buildOutRoot(genCfg).resolve(Paths.get(
                "details",
                tag,
                fill,
                stand,
                k1,
                kernel,
                cStr,
                lamStr,
                cfg.solveMode.name(),
                String.valueOf(sth)
        ));

        this.logsDir = root.resolve("logs");
        this.resultsDir = root.resolve("results");
        Files.createDirectories(logsDir);
        Files.createDirectories(resultsDir);
    }
    
    

    private static String fmtC(double c) {
        if (!Double.isFinite(c)) return "NA";
        if (c >= 1000) return String.format(Locale.US, "%.0f", c);
        if (c >= 10) return String.format(Locale.US, "%.2f", c);
        return String.format(Locale.US, "%.4f", c);
    }

    private static Path buildOutRoot(GenConfig genCfg) {
        GenConfig g = (genCfg == null ? new GenConfig() : genCfg);
        String rootName = "out_[" + g.spotMultLow + "," + g.spotMultHigh + "]"
                + String.format(Locale.US, "(%.1f,%.1f,%.1f)",
                g.priceFactorLow, g.priceFactorMid, g.priceFactorHigh);
        return Paths.get(rootName);
    }

    private static String stripExt(String s) {
        int p = s.lastIndexOf('.');
        return (p >= 0 ? s.substring(0, p) : s);
    }

    public static void writeWeights(Path outFile, Data data) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("sampleId,periodIndex,startDate,endDate,weight\n");
            for (Sample s : data.samples) {
                bw.write(s.id + "," + s.period.tIndex + "," + s.period.startDate + "," + s.period.endDate + "," + s.weight + "\n");
            }
        }
    }

    public static void writeY(Path outFile, Data data, Solution sol) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("carrierIndex,carrierName,y\n");
            for (int i = 0; i < data.params.I; i++) {
                bw.write(i + "," + data.params.carriers.get(i) + "," + sol.y[i] + "\n");
            }
        }
    }

    // 原版 summary：保留
    public static void writeSummary(Path outFile, Solution sol, int numSamples) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("objValue,solveTimeSec,numSamples\n");
            bw.write(sol.objValue + "," + sol.solveTimeSec + "," + numSamples + "\n");
        }
    }

    // NEW：summary 增强版（BatchRunner 会调用这个）
    public static void writeSummary(Path outFile, Solution sol, int numSamples, double realized,
                                    Object diagObj) throws IOException {
        // 这里 diagObj 是 BatchRunner.TrialDiag（避免跨 package 引入内部类）
        // 直接用反射读字段，保持你项目结构不大改
        double ess = Double.NaN, top1 = Double.NaN, top5 = Double.NaN, corrTD = Double.NaN;
        try {
            ess = (double) diagObj.getClass().getDeclaredField("ess").get(diagObj);
            top1 = (double) diagObj.getClass().getDeclaredField("top1W").get(diagObj);
            top5 = (double) diagObj.getClass().getDeclaredField("top5Wsum").get(diagObj);
            corrTD = (double) diagObj.getClass().getDeclaredField("corrThetaDemand").get(diagObj);
        } catch (Exception ignore) {}

        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("objValue,realizedCost,solveTimeSec,numSamples,ESS,top1W,top5Wsum,corrThetaDemand\n");
            bw.write(String.format(Locale.US,
                    "%.10f,%.10f,%.6f,%d,%.10f,%.10f,%.10f,%.10f%n",
                    sol.objValue, realized, sol.solveTimeSec, numSamples,
                    ess, top1, top5, corrTD
            ));
        }
    }

    // NEW：写 CPLEX gap/bound/status
    public static void writeCplexStats(Path outFile,
                                       String status,
                                       double bestBound,
                                       double mipGap,
                                       long nodes,
                                       double timeSec) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("status,bestBound,mipGap,nodes,solveTimeSec\n");
            bw.write(String.format(Locale.US,
                    "%s,%.10f,%.10f,%d,%.6f%n",
                    (status == null ? "" : status),
                    bestBound,
                    mipGap,
                    nodes,
                    timeSec
            ));
        }
    }

    public static void writeY(Path outFile, Data data, DROBenders.Solution sol) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("carrierIndex,carrierName,y\n");
            for (int i = 0; i < data.params.I; i++) {
                bw.write(i + "," + data.params.carriers.get(i) + "," + sol.y[i] + "\n");
            }
        }
    }

    public static void writeSummary(Path outFile, DROBenders.Solution sol, int numSamples) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
            bw.write("objValue,solveTimeSec,numSamples,iterations,numCuts\n");
            bw.write(sol.objValue + "," + sol.solveTimeSec + "," + numSamples + "," + sol.iterations + "," + sol.cutsAdded + "\n");
        }
    }
    

	public static void writeOOSLaneCosts(Path outFile, java.util.List<String> lanes, double[] dTest,
			double[] laneTransportQty, double[] laneSpotQty, double[] laneTransportCost, double[] laneSpotCost)
			throws IOException {
		int J = 0;
		if (laneTransportCost != null)
			J = laneTransportCost.length;
		else if (laneSpotCost != null)
			J = laneSpotCost.length;
		else if (dTest != null)
			J = dTest.length;
		else if (lanes != null)
			J = lanes.size();

		try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
			bw.write("laneIndex,laneName,demand,transportQty,spotQty,transportCost,spotCost,totalLaneCost\n");
			for (int j = 0; j < J; j++) {
				String laneName = (lanes != null && j < lanes.size()) ? lanes.get(j) : ("lane_" + j);
				double dem = (dTest != null && j < dTest.length) ? dTest[j] : Double.NaN;

				double tq = (laneTransportQty != null && j < laneTransportQty.length) ? laneTransportQty[j]
						: Double.NaN;
				double sq = (laneSpotQty != null && j < laneSpotQty.length) ? laneSpotQty[j] : Double.NaN;

				double tc = (laneTransportCost != null && j < laneTransportCost.length) ? laneTransportCost[j]
						: Double.NaN;
				double sc = (laneSpotCost != null && j < laneSpotCost.length) ? laneSpotCost[j] : Double.NaN;

				double tot = (Double.isFinite(tc) ? tc : 0.0) + (Double.isFinite(sc) ? sc : 0.0);

				bw.write(String.format(Locale.US, "%d,%s,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f%n", j, laneName, dem, tq,
						sq, tc, sc, tot));
			}
		}
	}

	public static void writeOOSCarrierPenaltyCosts(Path outFile, Data data, double[] y, double[] carrierAssignedQty,
			double[] carrierPenaltyQty, double[] carrierPenaltyCost) throws IOException {
		int I = (data == null || data.params == null) ? 0 : data.params.I;

		try (BufferedWriter bw = Files.newBufferedWriter(outFile)) {
			bw.write("carrierIndex,carrierName,y,assignedQty,penaltyQty,penaltyCost\n");
			for (int i = 0; i < I; i++) {
				String name = data.params.carriers.get(i);
				double yi = (y != null && i < y.length) ? y[i] : Double.NaN;

				double aq = (carrierAssignedQty != null && i < carrierAssignedQty.length) ? carrierAssignedQty[i]
						: Double.NaN;
				double uq = (carrierPenaltyQty != null && i < carrierPenaltyQty.length) ? carrierPenaltyQty[i]
						: Double.NaN;
				double pc = (carrierPenaltyCost != null && i < carrierPenaltyCost.length) ? carrierPenaltyCost[i]
						: Double.NaN;

				bw.write(String.format(Locale.US, "%d,%s,%.10f,%.10f,%.10f,%.10f%n", i, name, yi, aq, uq, pc));
			}
		}
	}

}
