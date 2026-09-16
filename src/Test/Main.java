//package Test;
//
//import java.io.BufferedWriter;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.nio.file.Paths;
//import java.nio.file.StandardOpenOption;
//import java.time.LocalDateTime;
//import java.time.format.DateTimeFormatter;
//import java.util.ArrayList;
//import java.util.Arrays;
//import java.util.List;
//import java.util.Locale;
//
//import Basic.CovariateVector;
//import Basic.Data;
//import Basic.PeriodData;
//import Basic.ProcurementParams;
//import Basic.Sample;
//import Helper.basicHelper.Config;
//import Helper.basicHelper.CsvHistoryLoader;
//import Helper.basicHelper.GlobalSummaryCollector;
//import Helper.basicHelper.InstanceGenerator;
//import Helper.basicHelper.SampleBuilder;
//import Helper.calculateHelper.EuclideanDistance;
//import Helper.calculateHelper.KernelFunction;
//import Helper.calculateHelper.KernelType;
//import Helper.calculateHelper.StandardScaler;
//import Helper.calculateHelper.WeightCalculator;
//import Model.SolveMode;
//
//public class Main {
//
//	public static void main(String[] args) throws Exception {
//		// ---- Input ----
//		Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
//
//		// ---- Config ----
//		Config cfg = new Config();
//		cfg.fillMissingDates = true; // 你说的：是否补齐日历日期
//		cfg.aggregationDays = 7; // 按“连续7天”聚合（fillMissingDates=true 时）
//		cfg.k1LagPeriods = 0;
//		cfg.kernelType = KernelType.EXPONENTIAL;
//		cfg.standardizeTheta = true;
//		cfg.bandwidthH = 1.0;
//		// 关闭温度（你说先废除），开启你要用的三个指标 + holidayCount
//		cfg.featureFlags.includeLagDemand = (cfg.k1LagPeriods > 0);
//		cfg.featureFlags.includeHolidayCount = true;
//		cfg.featureFlags.includeFreightIndex = true; // TSIFRGHT
//		cfg.featureFlags.includeConsumptionIndex = true; // PCEC96
//		cfg.featureFlags.includeWEIIndex = true; // WEI
//		
//		cfg.solveMode = SolveMode.CSAA;
//		List<ConfigGrid> grids = ConfigGrid.buildGrids();
//		Path weightRoot = Paths.get("out", "weightsTest");
//		Files.createDirectories(weightRoot);
//
//		// 所有配置统一一个文件
//		Path wFile = weightRoot.resolve("all_weights4.csv");
//		for (int i=0;i<grids.size();i++) {
//			ConfigGrid g = grids.get(i);
//			cfg.C_h = g.C_h;
//			cfg.fillMissingDates = g.fillingMissData;
//			cfg.standardizeTheta = g.standardize;
//			cfg.k1LagPeriods = g.k1Lag;
//			cfg.featureFlags.includeLagDemand = (cfg.k1LagPeriods > 0);
//		// ---- Load daily history ----
//		CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);
//
//		// ---- Build samples + thetaNow ----
//		// 当前周期(T+1)外生信息：先不管，传 null => 默认 0
//		SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfg);
//		for(Sample sample:br.samples) {
//			System.out.print("Sample:"+sample.id+"\t");
//			System.out.println(Arrays.toString(sample.demand()));
//		}
//		// === pick thetaNow from samples (backtesting) ===
////        SampleBuilder.NowSplitResult split = SampleBuilder.splitThetaNowFromSamples(
////                br.samples,
////                SampleBuilder.NowPickMode.RANDOM,  // or LAST / BY_INDEX
////                br.samples.size()/2,
////                cfg	                              // index if BY_INDEX
////        );
//
////        List<Sample> trainSamples = split.trainSamples;
////        CovariateVector thetaNow = split.thetaNow;
//
//		// ---- Build baseline demand per lane (average per aggregated period) ----
//		double[] dBase = buildBaselineDemand(br);
//		// ---- Generate procurement params (synthetic, based on your rules) ----
//		int numCarriers = 15; // [10,15,20,25] 你可以自己改/循环跑
//
//		InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
//		genCfg.rBarLow = 20.0;
//		genCfg.rBarHigh = 100.0;
//		genCfg.tauLow = 0.05;
//		genCfg.tauHigh = 0.30;
//		genCfg.spotLow = 60.0;
//		genCfg.spotHigh = 120.0;
//		genCfg.mqcLow = 0.1;
//		genCfg.mqcHigh = 0.2;
//		genCfg.alphaRatio = 0.3; // ceil(0.3*I)
//		genCfg.betaRatio = 1.0; // ceil(1.0*I) -> I
//		genCfg.coverAllLanes = true; // 你说先假设全覆盖
//		genCfg.capacityMode = InstanceGenerator.CapacityMode.TIGHT; // 或 LOOSE
//
////        GlobalSummaryCollector collector = new GlobalSummaryCollector(Paths.get("out"));
//        ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg,cfg);
////        int W = 24;
////        int C_hGap=Math.max(1,(int)(cfg.C_h_upper-cfg.C_h_lower)/10);
//
////        ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, W);
////        ExperimentBatches loo = ExperimentBuilder.buildLOO(br.samples);
////        for(int C_h=cfg.C_h_lower;C_h<cfg.C_h_upper;C_h+=C_hGap) {
////        	cfg.C_h=(double)C_h;
//		// SAA
////        	cfg.solveMode=SolveMode.SAA;
////        	 1) Rolling
////            BatchRunner.run("rolling_W10", hist.laneNames, params, cfg, rolling, Paths.get("out/backtest/"+cfg.C_h+"/"+cfg.solveMode.name()),collector);
//
//		// 2) LOO
////            BatchRunner.run("loo", hist.laneNames, params, cfg, loo, Paths.get("out/backtest/"+cfg.C_h+"/"+cfg.solveMode.name()),collector);
//
//		// CSAA
////        	cfg.solveMode=SolveMode.CSAA;
//		// 1) Rolling
////            BatchRunner.run("rolling_W10", hist.laneNames, params, cfg, rolling, Paths.get("out/backtest/"+cfg.C_h+"/"+cfg.solveMode.name()),collector);
//
//		// 2) LOO
////            BatchRunner.run("loo", hist.laneNames, params, cfg, loo, Paths.get("out/backtest/"+cfg.C_h+"/"+cfg.solveMode.name()),collector);
////            
//		// DRO
////            cfg.solveMode=SolveMode.RCSAA;
////            double lambdaGap=(cfg.lambda_upper-cfg.lambda_lower)/20;
////            for(double lambda=cfg.lambda_lower;lambda<cfg.lambda_upper;lambda+=lambdaGap) {
////            	cfg.lambda=lambda;
//		// 1) Rolling
////                DROBatchRunner.run("rolling_W20", hist.laneNames, params, cfg, rolling, Paths.get("out/backtest/"+cfg.C_h+"_"+cfg.lambda+"/"+cfg.solveMode.name()),collector);
//		// 2) LOO
////                DROBatchRunner.run("loo", hist.laneNames, params, cfg, loo, Paths.get("out/backtest/"+cfg.C_h+"_"+cfg.lambda+"/"+cfg.solveMode.name()),collector);
////            }
//		
//			ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, g.selectedNumSmaples);
////			ExperimentBatches loo = ExperimentBuilder.buildLOO(br.samples);
//			KernelFunction kernel = WeightCalculator.buildKernel(cfg);
//			WeightCalculator wc = new WeightCalculator(kernel, new EuclideanDistance());
//			for (ExperimentBatches batch : new ExperimentBatches[] {rolling}) {
//				for (int t = 0; t < batch.size(); t++) {
//					List<Sample> trainRaw = batch.trainSets.get(t);
//					CovariateVector thetaNowRaw = batch.thetaNowList.get(t);
//					Sample test = batch.testSamples.get(t);
//					int testIdx = batch.testIndex.get(t);
//
//					// ---- deep copy training set (IMPORTANT) ----
//					List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
//					if(Double.isNaN(train.get(0).theta.values()[0])){
//						System.out.println();
//					}
//					// thetaNow copy
//					CovariateVector thetaNow = new CovariateVector(thetaNowRaw.values().clone());
//
//					// theta dimension
//					int thetaDim = (train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length);
//
//					// ---- standardize ONLY using training set ----
//					if (cfg.standardizeTheta && !train.isEmpty()) {
//						StandardScaler scaler = new StandardScaler();
//						scaler.fit(train, thetaDim);
//						
//						for (Sample s : train) {
//							s.theta = new CovariateVector(scaler.transform(s.theta.values()));
//						}
//						if(Double.isNaN(train.get(0).theta.values()[0])){
//							System.out.println(Config.count);
//						}
//						thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
//					}
//
//					// ---- kernel weights on training set ----
//					wc.computeKernelWeights(train, thetaNow, cfg);
//					// batchName：rolling / loo（不想判断对象就用一个计数也行）
//					String batchName = batch.mode;
//
//					// 每个配置一个子目录（名字随便，你也可以只用 g 的索引）
//					
//					// 统一写入一个文件：一行 = 一个 trial
//					appendWeightsOneLinePerTrial(wFile, cfg, g, batchName, t, testIdx, train.size(), train);
//				}
//			}
//		}
//
//		//1.24测试
//		// 考虑的参数配置有，协变量是否标准化，是否选择过去一段时间的需求量作为协变量
//		// 是否将原始数据中缺失的天数补齐 选择训练的sample数量（W=10,24,80） cfg.C_h=10400
//		// 测试配置1：协变量标准化 补齐天数 有/无过去需求量作为协变量(2/3个周期) sample量选择10/24/80
////            这个配置下SAA和CSAA完全没区别，求解结果完全一样CSAA的权重都是基本一样的，差距只在小数点后4位数。
//		// 测试配置2：协变量未作标准化，未作补齐。 有/过去需求量作为协变量(2个周期) sample量为10
//		// 这个配置下有明显的区别了 至少结果不完全一样了,猜测是标准化的问题。
//		// 结果不是完全更好，可能就和样本量比较少有关系了？先不管，至少有区别了。
//		// 测试配置3：基于配置2把标准化去掉，此时结果又变成一样的了。那猜测大概率和这个是有关系的.
//		// 测试配置4：基于配置2把filling数据去掉，也是存在不一样的
//		// 那之后做就先不要标准化了，这个标准化看起来意义不大
//		// 测试配置5: 基于配置2, 考虑往期需求作为协变量。
//		// 那么，主要测试就在于，保持标准化关闭，测试不同的需求量作为协变量，测试是否补齐缺失天。
//		// 训练sample暂定为24/28，以及80
//		// ❌ 不能只用指数，只用指数，不做标准化，填充空闲时间结果是一样的。还是得用需求增加一点解释，指数长得还是太相似了。
//		// 这个是因为sample变多以后，计算kernal的时候分子分母太小了，导致tolerance被突破所有权重设置成一样的了。。
//		
//		
//		//按照当前目录下weights的分析，配置的分析如下：
//		//"标准化参数对比：一旦开启标准化，不管其他参数什么样子，不同样本量之间的权重的最大gap基本就是0，即这个东西一旦标准化，CSAA就没啥意义了。
//		//fillingMiss:影响不大，可开可不开
//		//selectedSample：对weight的权重看起来min,max,mean影响不大，10，24，28
//		//rolling/loo：同样区别不大
//		//C_h：影响很大，过于小的时候，可能算出来的weight存在数值问题，全是0，并且明显随着C_h的增大，gap是在变小的。怎么设置是个问题。
////		K1Lag, 设置为0明显weight大家都差不多，非0的时候，不同时间weight的gap都有明显区别，设置多少到是看着没什么影响。"											
//		//综上，最终测试的时候选择不做标准化，做数据补齐。
//		// sample选择量控制为24,80 ,即rolling为24,loo为80,但不同的sample量只是说weight之间的maxgap差距不大，但不知道最终求解结果如何。也可以多弄几个sample测试一下。
//			//尝试16,22,28吧,半年左右的数据
//		//C_h的趋势基本上是越大weight之间的差距越小，即max_gap越小。可尝试测试[100,50000],设置10个点
//		//k1_lag看权重影响并不大,可设置为[1,3,5]试试求解的结果。
//		//lambda只能直接求解测试了
//											
//		
//		// 2026.1.27 前边那个标准化以后weight全都相等还不太对，不是标准化的问题，是C_h的问题。标准化以后数值都比较小，导致C一旦过大，weight基本全都一样了
//		// 而没有标准化的话，由于原始数据较大，C较大就会导致weight出现不一致，重新尝试标准化以后的解看看如何。
//		//进一步的分析见下边的GPT					
//											
//											
//											
//											
//											
//											
//											
//
////        }
//
//	}

//	/**
//	 * baseline demand per lane: average of period demandSum over all aggregated
//	 * periods.
//	 */
//	private static double[] buildBaselineDemand(SampleBuilder.BuildResult br) {
//		if (br.periods.isEmpty())
//			return new double[0];
//		int J = br.periods.get(0).demandSum.length;
//
//		double[] sum = new double[J];
//		for (PeriodData p : br.periods) {
//			for (int j = 0; j < J; j++)
//				sum[j] += p.demandSum[j];
//		}
//		double denom = Math.max(1, br.periods.size());
//		for (int j = 0; j < J; j++)
//			sum[j] /= denom;
//		return sum;
//	}
//
//	private static void appendWeightsOneLinePerTrial(Path outFile, Config cfg, ConfigGrid g, String batchName,
//			int trialId, int testIdx, int trainSize, List<Sample> train) throws Exception {
//		boolean needHeader = (!Files.exists(outFile)) || Files.size(outFile) == 0;
//
//		try (BufferedWriter bw = Files.newBufferedWriter(outFile, StandardOpenOption.CREATE,
//				StandardOpenOption.APPEND)) {
//
//			if (needHeader) {
//				bw.write(String.join(",", "C_h", "fillMissingDates", "standardizeTheta", "k1LagPeriods",
//						"selectedNumSamples", "batch", "trialId", "testIdx", "trainSize", "weights","min","max"));
//				bw.newLine();
//			}
//
//// weights：一个 trial 下所有 sample 放一行，用 ; 分隔
//			StringBuilder sb = new StringBuilder();
//			double min=1;
//			double max=0;
//			for (int k = 0; k < train.size(); k++) {
//				Sample s = train.get(k);
//				min=Math.min(min, s.weight);
//				max=Math.max(max, s.weight);
//				if (k > 0)
//					sb.append(";");
//// 如果你只要 weight：改成 sb.append(String.format(..., s.weight));
//				sb.append(s.id).append(":").append(String.format(Locale.US, "%.10f", s.weight));
//			}
//
//			bw.write(String.format(Locale.US, "%.6f,%s,%s,%d,%d,%s,%d,%d,%d,\"%s\",%.10f,%.10f,%n", cfg.C_h,
//					String.valueOf(cfg.fillMissingDates), String.valueOf(cfg.standardizeTheta), cfg.k1LagPeriods,
//					g.selectedNumSmaples, batchName, trialId, testIdx, trainSize, sb.toString(),min,max));
//		}
//	}
//
//}




package Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import Basic.CovariateVector;
import Basic.Data;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.CsvHistoryLoader;
import Helper.basicHelper.GlobalSummaryCollector;
import Helper.basicHelper.GlobalTrialCollector;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.KernelType;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.SolveMode;

public class Main {

    public static void main(String[] args) throws Exception {
        // ---- Input ----
        Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
        GlobalSummaryCollector collector = null;
        GlobalTrialCollector trialCollector = null;

        // ---- Base Config（会被 grid 覆盖）----
        Config cfg = new Config();
        cfg.fillMissingDates = true;     // 你说先固定 true
        cfg.aggregationDays = 7;         // 按“连续7天”聚合
        cfg.k1LagPeriods = 0;
        cfg.kernelType = KernelType.EXPONENTIAL;
        cfg.standardizeTheta = false;
        cfg.bandwidthH = 1.0;
        // Optional diagnostics switch (SAA only): per-trial, per-sample single-sample deterministic solve + OOS eval CSV
        cfg.enableTrialSingleSampleDeterministicEval = true;

        // 关闭温度（你说先废除），开启三个指标 + holidayCount
        cfg.featureFlags.includeLagDemand = (cfg.k1LagPeriods > 0);
        cfg.featureFlags.includeHolidayCount = true;
        cfg.featureFlags.includeFreightIndex = true;       // TSIFRGHT
        cfg.featureFlags.includeConsumptionIndex = true;   // PCEC96
        cfg.featureFlags.includeWEIIndex = true;           // WEI

        // ---- Grid（按你新的测试计划）----
        List<ConfigGrid> grids = ConfigGrid.buildGrids();

        // SAA：对同一个 (fill, stand, k1, W) 只需要跑一次，避免重复
        Set<String> saaDone = new HashSet<>();

        for (ConfigGrid g : grids) {
            // ===== apply grid config into cfg =====
            cfg.fillMissingDates = true;             // 你说先固定 true（忽略 g.fillingMissData）
            cfg.standardizeTheta = g.standardize;
            cfg.k1LagPeriods = g.k1Lag;
            cfg.C_h = g.C_h;
            cfg.featureFlags.includeLagDemand = (cfg.k1LagPeriods > 0);

            // ---- Load daily history ----
            CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);

            // ---- Build samples ----
            SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfg);

            // ---- Build baseline demand per lane ----
            double[] dBase = buildBaselineDemand(br);

            // ---- Generate procurement params ----
            int numCarriers = 15;

            InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
            genCfg.rBarLow = 20.0;
            genCfg.rBarHigh = 100.0;
            genCfg.tauLow = 0.05;
            genCfg.tauHigh = 0.30;
            genCfg.mqcLow = 0.1;
            genCfg.mqcHigh = 0.2;
            genCfg.coverAllLanes = true;
            genCfg.capacityMode = InstanceGenerator.CapacityMode.TIGHT;

            Path outRoot = Paths.get(
                    "out_[" + genCfg.spotMultLow + "," + genCfg.spotMultHigh + "]"
                            + String.format(Locale.US, "(%.1f,%.1f,%.1f)",
                            genCfg.priceFactorLow, genCfg.priceFactorMid, genCfg.priceFactorHigh)
            );
            if (collector == null) {
                collector = new GlobalSummaryCollector(outRoot);
            }
            if (trialCollector == null) {
                trialCollector = new GlobalTrialCollector(outRoot);
            }

            ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);
            
            //数据特征检验
//            SpotPriceRatioAnalyzer.run(params, null);
//            DemandAnalyzer.analyzeUnweighted(br.samples);
            // ---- Rolling batches with W ----
            ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, g.selectedNumSmaples);
            
            // ===== 输出目录组织：避免覆盖（SAA 不含 C；CSAA 含 C）=====
            Path baseOut = Paths.get(
                    "out_[" + genCfg.spotMultLow + "," + genCfg.spotMultHigh + "]"
                            + String.format(Locale.US, "(%.1f,%.1f,%.1f)",
                            genCfg.priceFactorLow, genCfg.priceFactorMid, genCfg.priceFactorHigh),
                    "backtest",
                    "fillMiss_" + cfg.fillMissingDates,
                    "stand_" + cfg.standardizeTheta,
                    "k1Lag_" + cfg.k1LagPeriods,
                    "W_" + g.selectedNumSmaples,
                    "kernel_" + cfg.kernelType.name()
            );

            // ---------------- SAA：只跑一次 ----------------
            String saaKey = "fill=" + cfg.fillMissingDates
                    + "|stand=" + cfg.standardizeTheta
                    + "|k1=" + cfg.k1LagPeriods
                    + "|W=" + g.selectedNumSmaples
                    + "|kernel=" + cfg.kernelType.name();

            if (!saaDone.contains(saaKey)) {
                cfg.solveMode = SolveMode.SAA;

                System.out.println("[RUN] " + saaKey + " mode=" + cfg.solveMode.name());
                BatchRunner.run(
                        "rolling_W" + g.selectedNumSmaples,
                        hist.laneNames,
                        params,
                        cfg,
                        rolling,
                        baseOut.resolve(cfg.solveMode.name()),
                        collector,
                        trialCollector,
                        genCfg
                );
                saaDone.add(saaKey);
            }

            // // ---------------- CSAA：对每个 C 跑一次 ----------------
            // cfg.solveMode = SolveMode.CSAA;
            // String cStr = fmtC(cfg.C_h);

            // System.out.println("[RUN] " + saaKey + " C=" + cStr + " mode=" + cfg.solveMode.name());
            // BatchRunner.run(
            //         "rolling_W" + g.selectedNumSmaples,
            //         hist.laneNames,
            //         params,
            //         cfg,
            //         rolling,
            //         baseOut.resolve(cfg.solveMode.name()).resolve("C_" + cStr),
            //         collector,
            //         trialCollector,
            //         genCfg
            // );
        }
    }

    private static String fmtC(double c) {
        if (!Double.isFinite(c)) return "NA";
        // 避免目录名出现科学计数法
        if (c >= 1000) return String.format(Locale.US, "%.0f", c);
        if (c >= 10) return String.format(Locale.US, "%.2f", c);
        return String.format(Locale.US, "%.4f", c);
    }

    /**
     * baseline demand per lane: average of period demandSum over all aggregated periods.
     */
    private static double[] buildBaselineDemand(SampleBuilder.BuildResult br) {
        if (br.periods.isEmpty()) return new double[0];
        int J = br.periods.get(0).demandSum.length;

        double[] sum = new double[J];
        for (PeriodData p : br.periods) {
            for (int j = 0; j < J; j++) sum[j] += p.demandSum[j];
        }
        double denom = Math.max(1, br.periods.size());
        for (int j = 0; j < J; j++) sum[j] /= denom;
        return sum;
    }
}

/**
 * Grid 参数配置（按你计划）：
 * - fillMissingDates 固定 true
 * - standardizeTheta 测试 {true,false}
 * - k1 测试 {0,1,2,3,5,7,10}
 * - W 暂定只测试 24（后续你再加 20/28）
 * - C：标准化/不标准化分别给两套量级
 */
class ConfigGrid {
    boolean fillingMissData;
    boolean standardize;
    int k1Lag;
    double C_h;
    int selectedNumSmaples;

    public ConfigGrid(boolean f, boolean s, int k1, double C_h, int sn) {
        this.fillingMissData = f;
        this.standardize = s;
        this.k1Lag = k1;
        this.C_h = C_h;
        this.selectedNumSmaples = sn;
    }

    public static List<ConfigGrid> buildGrids() {
        List<ConfigGrid> grids = new ArrayList<>();

        // 你说：fillMissingDates 先固定 true
        boolean f = true;

        // 你说：standardize 都测
        boolean[] S = new boolean[]{true, false};

        // 你说：k1 测这些
        int[] K1 = new int[]{0, 1, 2, 3, 5, 7, 10};

        // 你说：W 先固定 24（后续要 20/28 直接把这里改成 new int[]{20,24,28}）
        int[] W = new int[]{24};//16,22,28,35 变大以后似乎效果反而变差了，22 28效果看着还行，选择24，半年的数据，感觉也说的通。具体分析见下方的链接

        // C：按是否标准化分两套（更合理的量级，不再用 3/7/15 那种导致全均匀）
        double[] C_STD = new double[]{0.1, 0.2, 0.3, 0.5, 0.7, 1, 1.5, 2, 3, 5, 7, 10, 15, 20};
        double[] C_RAW = new double[]{50, 100, 200, 300, 500, 700, 1000, 1500, 2000, 3000, 5000, 7000, 10000, 20000, 50000};
        
//        double[] CArray1=new double[] {100,350,600,850,1500,5000,10000,30000,70000,100000,500000};//100,350,600,850,1500,5000,10000
//		double[] CArray2=new double[] {0.01,0.05,0.1,0.5,0.6,0.7,0.8,0.9,1,1.1,1.2,13,3,7,15,25,50,100,500};//0.01,0.05,0.1,0.5,1
		//原始的测试值。前边采用的则是基于测试值进一步筛选出来的测试C。基于GPT分析。https://chatgpt.com/g/g-p-69369a277a808191897a9b9941751d73-contextual-optimization/c/697892a1-aba4-832d-9e8e-ad75552bcfaf
        
        
        for (boolean s : S) {
            for (int k1 : K1) {
                for (int w : W) {
                    double[] CArr = (s ? C_STD : C_RAW);
                    for (double c : CArr) {
                        grids.add(new ConfigGrid(f, s, k1, c, w));
                    }
                }
            }
        }
        return grids;
    }
}

