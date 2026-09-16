package Test;

import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

import Basic.CovariateVector;
import Basic.Data;
import Basic.PeriodData;
import Basic.ProcurementParams;
import Basic.Sample;
import Helper.basicHelper.Config;
import Helper.basicHelper.CsvHistoryLoader;
import Helper.basicHelper.InstanceGenerator;
import Helper.basicHelper.SampleBuilder;
import Helper.calculateHelper.EuclideanDistance;
import Helper.calculateHelper.KernelFunction;
import Helper.calculateHelper.StandardScaler;
import Helper.calculateHelper.WeightCalculator;
import Model.SAAModel;
import Model.Solution;
import Model.SolveMode;
import Test.BatchRunner.RecourseEvaluator;

public class DataDiagnostics {

	/**
	 * (1) 每个 sample 当成“唯一确定性场景”（weight=1），解一次模型得到该 sample 下的最优成本。 输出： -
	 * outDir/det_per_sample.csv - outDir/det_per_sample_summary.txt
	 */
	public static void runDeterministicPerSample(List<String> lanes, ProcurementParams params, Config cfg,
			List<Sample> allSamples, Path outDir) throws Exception {
		if (allSamples == null || allSamples.isEmpty())
			throw new IllegalArgumentException("Empty samples.");
		Files.createDirectories(outDir);

		Path csv = outDir.resolve("det_per_sample.csv");
		Path txt = outDir.resolve("det_per_sample_summary.txt");

		SAAModel model = new SAAModel();
		List<Double> objList = new ArrayList<>();
		List<Double> timeList = new ArrayList<>();
		List<Integer> selList = new ArrayList<>();

		try (BufferedWriter bw = Files.newBufferedWriter(csv)) {
			bw.write("idx,sampleId,obj,solveTimeSec,selectedCount,y\n");
			for (int w = 0; w < allSamples.size(); w++) {
				Sample s0 = allSamples.get(w);

				// train = {this sample}, weight=1.0
				List<Sample> train = new ArrayList<>(1);
				CovariateVector thetaCopy = new CovariateVector(s0.theta.values().clone());
				train.add(new Sample(s0.id, s0.period, thetaCopy, 1.0));

				// thetaNow = this sample's theta
				CovariateVector thetaNow = new CovariateVector(s0.theta.values().clone());

				Data data = new Data(lanes, train, thetaNow, params);

				long st = System.nanoTime();
				Solution sol = model.solve(data, cfg, null);
				long ed = System.nanoTime();
				double timeSec = (ed - st) / 1e9;

				double obj = sol.objValue;
				int sel = countSelected(sol.y);

				objList.add(obj);
				timeList.add(timeSec);
				selList.add(sel);

				bw.write(String.format(Locale.US, "%d,%s,%.10f,%.6f,%d,\"%s\"%n", w, safe(s0.id), obj, timeSec, sel,
						Arrays.toString(sol.y)));// 
			}
		}

		SummaryStats objStats = SummaryStats.of(objList);
		SummaryStats timeStats = SummaryStats.of(timeList);
		SummaryStats selStats = SummaryStats.ofInt(selList);

		try (BufferedWriter bw = Files.newBufferedWriter(txt)) {
			bw.write("N=" + allSamples.size() + "\n\n");

			bw.write("[Deterministic objective per sample]\n");
			bw.write(objStats.pretty() + "\n\n");

			bw.write("[Solve time (sec)]\n");
			bw.write(timeStats.pretty() + "\n\n");

			bw.write("[Selected count]\n");
			bw.write(selStats.pretty() + "\n");
		}
	}

	/**
	 * (2) 固定一个 trial（rolling 下的 trialId），对 grids 中每个参数配置： - 应用 cfg (C_h /
	 * standardize / k1Lag / W ...) - 重新 load history -> build samples -> build
	 * instance -> build rolling(W) - 取该 trial 的 train/thetaNow/test -
	 * standardize(可选) -> kernel weights -> 诊断 -> solve -> realized -> 输出一行
	 *
	 * 额外要求： - fillMissingDates 强制 true（你说先固定）
	 */
	public static void runChSweepFixedTrial(Path historyCsv, Config cfg, List<ConfigGrid> grids, int trialId,
			int numCarriers, InstanceGenerator.GenConfig genCfg, Path outCsv) throws Exception {

		// ===== 每列含义（强烈建议你把这段保留在代码里，后处理时一眼可懂）=====
		// trialId : 固定的 rolling trial 编号（你指定的那个 trial）
		// gridId : 当前参数配置在 grids 中的序号
		// C_h : 核函数尺度参数（你实验扫描的核心）
		// fillMissingDates : 是否补齐缺失日期（此处强制 true）
		// standardizeTheta : 是否对 theta 标准化（用 train fit，再变换 train/thetaNow）
		// k1LagPeriods : 是否把过去需求滞后作为协变量，以及滞后期数
		// selectedNumSamples : rolling 的窗口长度 W
		// testIdx : 当前 trial 对应的样本外样本位置（索引）
		// trainSize : 当前 trial 的训练样本数（通常等于 W）

		// minW/maxW/meanW : 权重最小/最大/均值（均值≈1/trainSize 表示很均匀）
		// sumW : 权重和（理论=1；偏离说明数值/归一化异常）
		// sumW2 : 归一化权重平方和 sum(w^2)
		// ESS : Effective Sample Size = 1 / sum(w^2)（越小越“尖峰近邻”，越大越“接近SAA”），接近SAA时值为W，即场景的数量
		// top1W : 最大权重（越大越像近邻/尖峰）
		// top5Wsum : 前5大权重之和（越大说明权重集中在少数样本）
		// maxW_over_meanW : maxW/meanW（尖峰程度；>>1 表示非常集中）
		// entropyW : 权重熵 -sum(w log w)（越大越均匀；越小越尖峰）
		// giniW : 权重不均衡度（0均匀，越大越不均衡）
		// numZeroW : 权重为0的样本数（紧支撑核/数值下溢会导致增多）
		// numTinyW : 介于(0, 1e-15)的极小权重数（接近下溢，ESS常被扭曲）

		// distMin/Max/Mean/Std/Median :
		// 训练样本到 thetaNow 的距离分布（协变量尺度的直观画像）
		// uniqDist_r2/r4/r6 :
		// 距离在四舍五入到2/4/6位小数后的“不同取值数量”（少说明距离离散/分辨率不足）
		// uniqTheta_r2/r4 :
		// theta 向量逐维四舍五入后不同样本数（少说明协变量高度重复/离散）
		// zeroVarDims :
		// 训练集中方差≈0 的协变量维度数量（标准化时会导致除0/NaN风险；也会造成距离失真）
		// dominantDim / dominantAbsZ :
		// thetaNow 相对训练均值偏离最大的维度（z-score 最大的维度及其绝对值）
		// 如果 dominantAbsZ 很大，说明 thetaNow 与训练分布偏离严重，权重可能异常尖峰或退化

		// Ch_hat_mul_med / Ch_hat_mul_mad :
		// 在假设 u_i=exp(-C*d_i) 下，基于(top1 vs topK)的“反推C”估计（median & MAD）
		// 若 Ch_hat_mul_med 与 cfg.C_h 同量级，说明你当前核尺度与数据距离是匹配的；
		// 若偏差巨大，说明 C_h 尺度不对（或核公式不是乘法型）
		// underflowC_mul_threshold :
		// 近似下溢阈值：若 C_h > 745/distMax，则 exp(-C_h*distMax) 可能下溢到 0（double 极限）
		// 这会导致大量 0 权重 / denom≈0 / ESS异常

		// obj : 模型目标值（用当前权重解出的 in-sample 加权目标）
		// realized : 在 test demand 下的 realized cost（你 evaluate 的 out-of-sample 指标）
		// selectedCount : 选择的承运商数量（y>0.5 的个数）
		// solveTimeSec : 求解时间（秒）
		// weights : 训练集中每个样本的权重（id:weight;...）
		// y : 解出来的 y 向量（便于排查解是否几乎不变）
		
		//相关的信息见:https://chatgpt.com/g/g-p-69369a277a808191897a9b9941751d73-contextual-optimization/c/697892a1-aba4-832d-9e8e-ad75552bcfaf
		

		if (grids == null || grids.isEmpty())
			throw new IllegalArgumentException("Empty grids.");
		Files.createDirectories(outCsv.getParent());

		cfg.solveMode = SolveMode.CSAA;

		boolean needHeader = (!Files.exists(outCsv)) || Files.size(outCsv) == 0;

		try (BufferedWriter bw = Files.newBufferedWriter(outCsv, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND)) {

			if (needHeader) {
				bw.write(String.join(",",
						// keys
						"trialId", "gridId", "C_h", "fillMissingDates", "standardizeTheta", "k1LagPeriods",
						"selectedNumSamples", "testIdx", "trainSize",

						// weight basic
						"minW", "maxW", "meanW",

						// ESS + shape
						"sumW", "sumW2", "ESS", "top1W", "top5Wsum", "maxW_over_meanW", "entropyW", "giniW", "numZeroW",
						"numTinyW",

						// distance diagnostics (theta)
						"distMin", "distMax", "distMean", "distStd", "distMedian", "uniqDist_r2", "uniqDist_r4",
						"uniqDist_r6", "uniqTheta_r2", "uniqTheta_r4", "zeroVarDims", "dominantDim", "dominantAbsZ",

						// NEW: covariate usefulness check
						// corr(thetaDist, demandDist) should be positive if thetaDist explains demand
						// difference
						"corrThetaDist_DemDist_Pearson", "corrThetaDist_DemDist_Spearman",
						// corr(weight, demandDist) should be negative if weights emphasize
						// demand-similar samples
						"corrWeight_DemDist_Pearson", "corrWeight_DemDist_Spearman",

						// infer C (two hypotheses) - 保持你原来的列不动
						"Ch_hat_mul_med", "Ch_hat_mul_mad",
						"underflowC_mul_threshold", "underflowC_div_threshold",

						// solve
						"obj", "realized", "selectedCount", "solveTimeSec",

						// raw
						"weights", "y"));
				bw.newLine();
			}

			SAAModel model = new SAAModel();
			EuclideanDistance distMetric = new EuclideanDistance();

			for (int gi = 0; gi < grids.size(); gi++) {
				ConfigGrid g = grids.get(gi);

				// ===== apply grid config into cfg =====
				cfg.C_h = g.C_h;
				cfg.fillMissingDates =g.fillingMissData; // 强制 true
				cfg.standardizeTheta = g.standardize;
				cfg.k1LagPeriods = g.k1Lag;
				cfg.featureFlags.includeLagDemand = (cfg.k1LagPeriods > 0);

				// ===== Load daily history =====
				CsvHistoryLoader.HistoryLoadResult hist = CsvHistoryLoader.load(historyCsv, cfg);

				// ===== Build samples =====
				SampleBuilder.BuildResult br = SampleBuilder.build(hist.days, hist.laneNames, cfg);

				// ===== baseline demand =====
				double[] dBase = buildBaselineDemand(br);

				// ===== Generate procurement params =====
				ProcurementParams params = InstanceGenerator.generate(numCarriers, dBase, genCfg, cfg);

				// ===== Build rolling batches with W = selectedNumSamples =====
				ExperimentBatches rolling = ExperimentBuilder.buildRolling(br.samples, g.selectedNumSmaples);

				if (trialId < 0 || trialId >= rolling.size()) {
					bw.write(String.format(Locale.US, "%d,%d,%.6f,%s,%s,%d,%d,%s,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,,\"\"%n",
							trialId, gi, cfg.C_h, String.valueOf(cfg.fillMissingDates),
							String.valueOf(cfg.standardizeTheta), cfg.k1LagPeriods, g.selectedNumSmaples,
							safe(rolling.mode)));
					continue;
				}

				// ===== Fixed trial data =====
				List<Sample> trainRaw = rolling.trainSets.get(trialId);
				CovariateVector thetaNowRaw = rolling.thetaNowList.get(trialId);
				Sample test = rolling.testSamples.get(trialId);
				int testIdx = rolling.testIndex.get(trialId);
				double[] dTest = test.demand().clone();

				// ===== deep copy train/thetaNow =====
				List<Sample> train = BatchRunner.deepCopySamples(trainRaw);
				CovariateVector thetaNow = new CovariateVector(thetaNowRaw.values().clone());

				int thetaDim = (train.isEmpty() ? thetaNow.values().length : train.get(0).theta.values().length);

				// ===== standardize ONLY using training set =====
				if (cfg.standardizeTheta && !train.isEmpty() && train.size() >= 2) {
					StandardScaler scaler = new StandardScaler();
					scaler.fit(train, thetaDim);
					for (Sample s : train) {
						s.theta = new CovariateVector(scaler.transform(s.theta.values()));
					}
					thetaNow = new CovariateVector(scaler.transform(thetaNow.values()));
				}

				// ===== recompute weights =====
				KernelFunction kernel = WeightCalculator.buildKernel(cfg);
				WeightCalculator wc = new WeightCalculator(kernel, distMetric);
				wc.computeKernelWeights(train, thetaNow, cfg);
				System.out.println("gridId:" + gi);
				if (gi == 127) {
					System.out.println();
				}
				// ===== build diagnostics (NOW includes demand-distance correlations) =====
				WeightDiagnostics wd = buildWeightDiagnostics(train, thetaNow, distMetric, dTest);

				boolean hasNaN = wd.hasNaNWeight;

				// ===== solve (only if weights are valid) =====
				double obj = Double.NaN, realized = Double.NaN, timeSec = Double.NaN;
				int sel = -1;
				String yLine = "";

				if (!hasNaN) {
					Data data = new Data(hist.laneNames, train, thetaNow, params);
					long st = System.nanoTime();
//                    Solution sol = model.solve(data, cfg, null);
					long ed = System.nanoTime();
					timeSec = (ed - st) / 1e9;

					obj = -1;// TODO sol.objValue;
					realized = -1;// TODO RecourseEvaluator.evaluate(params, sol.y, dTest);
					sel = -1;// TODO countSelected(sol.y);
					yLine = Arrays.toString(new double[] { 0 });// Arrays.toString(sol.y);
				}

				// ===== write one row =====
				bw.write(String.format(Locale.US,
						"%d,%d,%.6f,%s,%s,%d,%d,%d,%d," + "%.10f,%.10f,%.10f,"
								+ "%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%d,%d,"
								+ "%.10f,%.10f,%.10f,%.10f,%.10f,%d,%d,%d,%d,%d,%d,%d,%.10f,"
								// NEW correlations
								+ "%.10f,%.10f,%.10f,%.10f,"
								// infer C
								+ "%.10f,%.10f,%.10f,"
								// solve
								+ "%.10f,%.10f,%d,%.6f,"
								// raw
								+ "\"%s\",\"%s\"%n",

						trialId, gi, cfg.C_h, String.valueOf(cfg.fillMissingDates),
						String.valueOf(cfg.standardizeTheta), cfg.k1LagPeriods, g.selectedNumSmaples, testIdx,
						train.size(),

						wd.minW, wd.maxW, wd.meanW,

						wd.sumW, wd.sumW2, wd.ess, wd.top1W, wd.top5Wsum, wd.maxOverMean, wd.entropy, wd.gini,
						wd.numZeroW, wd.numTinyW,

						wd.distMin, wd.distMax, wd.distMean, wd.distStd, wd.distMedian, wd.uniqDistR2, wd.uniqDistR4,
						wd.uniqDistR6, wd.uniqThetaR2, wd.uniqThetaR4, wd.zeroVarDims, wd.dominantDim, wd.dominantAbsZ,

						// NEW: correlation outputs
						wd.corrThetaDistDemDistPearson, wd.corrThetaDistDemDistSpearman, wd.corrWeightDemDistPearson,
						wd.corrWeightDemDistSpearman,

						wd.chHatMulMedian, wd.chHatMulMAD,  wd.underflowCMulThreshold,
						
						obj, realized, sel, timeSec, wd.weightsLine, yLine));
			}
		}
	}

	// ====================== main (Diagnostics Runner) ======================

	public static void main(String[] args) throws Exception {
		Path historyCsv = Paths.get(args.length > 0 ? args[0] : "./fFreight_with_indicators_and_holidays.csv");
		Path outRoot = Paths.get(args.length > 1 ? args[1] : "out_(2,2.5)_(0.6,1.0,2.5)/diagnostics");

		int trialId = (args.length > 2 ? Integer.parseInt(args[2]) : 0);
		int numCarriers = (args.length > 3 ? Integer.parseInt(args[3]) : 15);

		// ===== base cfg =====
		Config cfg = new Config();
		cfg.fillMissingDates = true; // 固定 true
		cfg.aggregationDays = 7;
		cfg.k1LagPeriods = 0;

		cfg.kernelType = Helper.calculateHelper.KernelType.EXPONENTIAL;
		cfg.standardizeTheta = true; // grid 会覆盖
		cfg.bandwidthH = 1.0;

		cfg.featureFlags.includeLagDemand = (cfg.k1LagPeriods > 0);
		cfg.featureFlags.includeHolidayCount = true;
		cfg.featureFlags.includeFreightIndex = true;
		cfg.featureFlags.includeConsumptionIndex = true;
		cfg.featureFlags.includeWEIIndex = true;

		cfg.solveMode = SolveMode.CSAA;

		List<ConfigGrid> grids = ConfigGrid.buildGrids();

		// deterministic only once
		CsvHistoryLoader.HistoryLoadResult hist0 = CsvHistoryLoader.load(historyCsv, cfg);
		SampleBuilder.BuildResult br0 = SampleBuilder.build(hist0.days, hist0.laneNames, cfg);
		double[] dBase0 = buildBaselineDemand(br0);

		InstanceGenerator.GenConfig genCfg = new InstanceGenerator.GenConfig();
		genCfg.rBarLow = 20.0;
		genCfg.rBarHigh = 100.0;
		genCfg.tauLow = 0.05;
		genCfg.tauHigh = 0.30;
		
		genCfg.mqcLow = 0.1;
		genCfg.mqcHigh = 0.2;
		genCfg.alphaRatio = 0.1;
		genCfg.betaRatio = 0.7;
		genCfg.coverAllLanes = true;
		genCfg.capacityMode = InstanceGenerator.CapacityMode.TIGHT;

		ProcurementParams params0 = InstanceGenerator.generate(numCarriers, dBase0, genCfg, cfg);

        Path detDir = outRoot.resolve("det_per_sample_fillMiss(" + cfg.fillMissingDates + ")_numCarriers(" + numCarriers + ")");
        runDeterministicPerSample(hist0.laneNames, params0, cfg, br0.samples, detDir);

//		Path sweepDir = outRoot.resolve("grid_sweep");
//		Files.createDirectories(sweepDir);
//		Path outCsv = sweepDir.resolve("grid_trial_" + trialId + "_numCarriers(" + numCarriers + ")_WEIGHT_DIAG_ALLTRAILS.csv");
//		for(trialId=0;trialId<=35;trialId++)
//			runChSweepFixedTrial(historyCsv, cfg, grids, trialId, numCarriers, genCfg, outCsv);
//
//		System.out.println("[OK] Diagnostics done.");
//        System.out.println("Deterministic outputs: " + detDir.toAbsolutePath());
//		System.out.println("Grid sweep outputs:    " + outCsv.toAbsolutePath());
	}

	// ====================== NEW: diagnostics bundle ======================

	private static class WeightDiagnostics {
		// basic
		boolean hasNaNWeight;
		double minW, maxW, meanW;
		String weightsLine;

		// ESS + shape
		double sumW, sumW2, ess;
		double top1W, top5Wsum;
		double maxOverMean;
		double entropy, gini;
		int numZeroW, numTinyW;

		// distance + discreteness
		double distMin, distMax, distMean, distStd, distMedian;
		int uniqDistR2, uniqDistR4, uniqDistR6;
		int uniqThetaR2, uniqThetaR4;

		// covariate issue hints
		int zeroVarDims;
		int dominantDim;
		double dominantAbsZ;

		// NEW: correlation diagnostics (covariate usefulness)
		double corrThetaDistDemDistPearson;
		double corrThetaDistDemDistSpearman;
		double corrWeightDemDistPearson;
		double corrWeightDemDistSpearman;

		// infer C
		double chHatMulMedian, chHatMulMAD;
		double underflowCMulThreshold;
	}

	private static WeightDiagnostics buildWeightDiagnostics(List<Sample> train, CovariateVector thetaNow,
			EuclideanDistance distMetric, double[] dTest) {
		WeightDiagnostics wd = new WeightDiagnostics();
		if (train == null || train.isEmpty()) {
			wd.hasNaNWeight = true;
			wd.minW = wd.maxW = wd.meanW = Double.NaN;
			wd.weightsLine = "";
			wd.sumW = wd.sumW2 = wd.ess = Double.NaN;
			wd.top1W = wd.top5Wsum = Double.NaN;
			wd.maxOverMean = Double.NaN;
			wd.entropy = wd.gini = Double.NaN;
			wd.numZeroW = wd.numTinyW = 0;
			wd.distMin = wd.distMax = wd.distMean = wd.distStd = wd.distMedian = Double.NaN;
			wd.uniqDistR2 = wd.uniqDistR4 = wd.uniqDistR6 = 0;
			wd.uniqThetaR2 = wd.uniqThetaR4 = 0;
			wd.zeroVarDims = 0;
			wd.dominantDim = -1;
			wd.dominantAbsZ = Double.NaN;

			wd.corrThetaDistDemDistPearson = Double.NaN;
			wd.corrThetaDistDemDistSpearman = Double.NaN;
			wd.corrWeightDemDistPearson = Double.NaN;
			wd.corrWeightDemDistSpearman = Double.NaN;

			wd.chHatMulMedian = wd.chHatMulMAD = Double.NaN;
			wd.underflowCMulThreshold = Double.NaN;
			return wd;
		}

		// ---------- collect weights + weights string ----------
		double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY, sum = 0.0;
		boolean nan = false;

		StringBuilder sb = new StringBuilder();
		for (int k = 0; k < train.size(); k++) {
			Sample s = train.get(k);
			double w = s.weight;
			if (Double.isNaN(w) || Double.isInfinite(w))
				nan = true;

			min = Math.min(min, w);
			max = Math.max(max, w);
			sum += w;

			if (k > 0)
				sb.append(";");
			sb.append(s.id).append(":").append(String.format(Locale.US, "%.10f", w));
		}
		wd.hasNaNWeight = nan;
		wd.minW = min;
		wd.maxW = max;
		wd.meanW = sum / train.size();
		wd.weightsLine = sb.toString();

		// ---------- normalize weights for diagnostics ----------
		double sumW = 0.0;
		for (Sample s : train) {
			double w = s.weight;
			if (!Double.isFinite(w))
				continue;
			sumW += w;
		}
		wd.sumW = sumW;

		int zeroCnt = 0, tinyCnt = 0;
		for (Sample s : train) {
			double w = s.weight;
			if (!Double.isFinite(w))
				continue;
			if (w == 0.0)
				zeroCnt++;
			if (w > 0.0 && w < 1e-15)
				tinyCnt++;
		}
		wd.numZeroW = zeroCnt;
		wd.numTinyW = tinyCnt;

		double sumW2 = 0.0;
		List<Double> wNorm = new ArrayList<>(train.size());
		if (sumW > 0 && Double.isFinite(sumW)) {
			for (Sample s : train) {
				double w = s.weight;
				if (!Double.isFinite(w))
					continue;
				double wn = w / sumW;
				wNorm.add(wn);
				sumW2 += wn * wn;
			}
		} else {
			sumW2 = Double.NaN;
		}
		wd.sumW2 = sumW2;
		wd.ess = (sumW2 > 0 && Double.isFinite(sumW2)) ? (1.0 / sumW2) : Double.NaN;

		List<Double> wSorted = new ArrayList<>(wNorm);
		wSorted.sort(Comparator.reverseOrder());
		wd.top1W = wSorted.isEmpty() ? Double.NaN : wSorted.get(0);
		wd.top5Wsum = 0.0;
		for (int i = 0; i < Math.min(5, wSorted.size()); i++)
			wd.top5Wsum += wSorted.get(i);
		wd.maxOverMean = (wd.meanW != 0 && Double.isFinite(wd.meanW)) ? (wd.maxW / wd.meanW) : Double.NaN;

		double ent = 0.0;
		for (double wn : wNorm) {
			if (wn > 0)
				ent += -wn * Math.log(wn);
		}
		wd.entropy = ent;

		wd.gini = giniOfNormalizedWeights(wNorm);

		// ---------- theta distances ----------
		List<Double> dists = new ArrayList<>(train.size());
		for (Sample s : train) {
			double d = distMetric.distance(s.theta.values(), thetaNow.values());
			dists.add(d);
		}
		wd.distMin = dists.stream().mapToDouble(x -> x).min().orElse(Double.NaN);
		wd.distMax = dists.stream().mapToDouble(x -> x).max().orElse(Double.NaN);
		wd.distMean = dists.stream().mapToDouble(x -> x).average().orElse(Double.NaN);
		wd.distStd = stdOf(dists, wd.distMean);
		wd.distMedian = medianOf(dists);

		wd.uniqDistR2 = uniqueCountByRounding(dists, 2);
		wd.uniqDistR4 = uniqueCountByRounding(dists, 4);
		wd.uniqDistR6 = uniqueCountByRounding(dists, 6);

		wd.uniqThetaR2 = uniqueThetaCount(train, 2);
		wd.uniqThetaR4 = uniqueThetaCount(train, 4);

		// ---------- covariate issue hints ----------
		CovMismatch cm = computeCovMismatch(train, thetaNow);
		wd.zeroVarDims = cm.zeroVarDims;
		wd.dominantDim = cm.dominantDim;
		wd.dominantAbsZ = cm.dominantAbsZ;

		// ---------- NEW: demand-distance correlations ----------
		// demand distance: L1(demand_train, demand_test)
		List<Double> demDists = new ArrayList<>(train.size());
		for (Sample s : train) {
			double[] ds = s.demand();
			demDists.add(l1Distance(ds, dTest));
		}

		// corr(thetaDist, demDist)
		wd.corrThetaDistDemDistPearson = pearsonCorr(dists, demDists);
		wd.corrThetaDistDemDistSpearman = spearmanCorr(dists, demDists);

		// corr(weight, demDist) -- use normalized weights if available
		if (wNorm.size() == demDists.size()) {
			wd.corrWeightDemDistPearson = pearsonCorr(wNorm, demDists);
			wd.corrWeightDemDistSpearman = spearmanCorr(wNorm, demDists);
		} else {
			wd.corrWeightDemDistPearson = Double.NaN;
			wd.corrWeightDemDistSpearman = Double.NaN;
		}
		// ---------- infer C from (w, dist) ----------
		InferCResult ic = inferChFromWeightsAndDistances(train, thetaNow, dists, wNorm);
		wd.chHatMulMedian = ic.mulMedian;
		wd.chHatMulMAD = ic.mulMAD;
		

		double distMax = wd.distMax;
		if (distMax > 0 && Double.isFinite(distMax)) {
			wd.underflowCMulThreshold = 745.0 / distMax; // if exp(-C * d)
					} else {
			wd.underflowCMulThreshold = Double.NaN;
			
		}

		return wd;
	}

	private static double l1Distance(double[] a, double[] b) {
		if (a == null || b == null)
			return Double.NaN;
		int n = Math.min(a.length, b.length);
		if (n == 0)
			return 0.0;
		double s = 0.0;
		for (int i = 0; i < n; i++)
			s += Math.abs(a[i] - b[i]);
		return s;
	}

	private static class CovMismatch {
		int zeroVarDims;
		int dominantDim;
		double dominantAbsZ;
	}

	private static CovMismatch computeCovMismatch(List<Sample> train, CovariateVector thetaNow) {
		CovMismatch cm = new CovMismatch();
		int n = train.size();
		int L = thetaNow.values().length;

		double[] mean = new double[L];
		for (Sample s : train) {
			double[] x = s.theta.values();
			for (int l = 0; l < L; l++)
				mean[l] += x[l];
		}
		for (int l = 0; l < L; l++)
			mean[l] /= Math.max(1, n);

		double[] var = new double[L];
		for (Sample s : train) {
			double[] x = s.theta.values();
			for (int l = 0; l < L; l++) {
				double d = x[l] - mean[l];
				var[l] += d * d;
			}
		}
		for (int l = 0; l < L; l++)
			var[l] /= Math.max(1, n - 1);
		double[] std = new double[L];
		for (int l = 0; l < L; l++)
			std[l] = Math.sqrt(Math.max(0.0, var[l]));

		int zeroVar = 0;
		int dom = -1;
		double domAbsZ = -1;

		double[] now = thetaNow.values();
		for (int l = 0; l < L; l++) {
			if (std[l] < 1e-12) {
				zeroVar++;
				continue;
			}
			double z = Math.abs((now[l] - mean[l]) / std[l]);
			if (z > domAbsZ) {
				domAbsZ = z;
				dom = l;
			}
		}
		cm.zeroVarDims = zeroVar;
		cm.dominantDim = dom;
		cm.dominantAbsZ = (domAbsZ < 0 ? Double.NaN : domAbsZ);
		return cm;
	}

	private static class InferCResult {
		double mulMedian, mulMAD;

	}

	private static InferCResult inferChFromWeightsAndDistances(List<Sample> train, CovariateVector thetaNow, // 这里其实没用到，保留签名避免影响其它调用
			List<Double> dists, List<Double> wNorm) {
		InferCResult res = new InferCResult();
// 默认返回 NaN（推断失败时就是 NaN）
		res.mulMedian = Double.NaN;
		res.mulMAD = Double.NaN;

		if (train == null || dists == null || wNorm == null)
			return res;

		int n = train.size();
		if (n <= 1)
			return res;
		if (dists.size() < n || wNorm.size() < n)
			return res; // 长度不匹配，直接放弃推断

// 只保留有效点：wNorm 有限且 >0，dist 有限
		List<Integer> valid = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			double wi = wNorm.get(i);
			double di = dists.get(i);
			if (Double.isFinite(wi) && wi > 0.0 && Double.isFinite(di)) {
				valid.add(i);
			}
		}
		if (valid.size() < 2)
			return res;

// 按权重从大到小排序（只排序有效点）
		valid.sort((a, b) -> Double.compare(wNorm.get(b), wNorm.get(a)));

		int i0 = valid.get(0);
		double w0 = wNorm.get(i0);
		double d0 = dists.get(i0);

		if (!(Double.isFinite(w0) && w0 > 0.0 && Double.isFinite(d0)))
			return res;

		int K = Math.min(10, valid.size());
		List<Double> cMul = new ArrayList<>();

		for (int kk = 1; kk < K; kk++) {
			int j = valid.get(kk);
			double wj = wNorm.get(j);
			double dj = dists.get(j);

			if (!(Double.isFinite(wj) && wj > 0.0 && Double.isFinite(dj)))
				continue;

			double dd = dj - d0;
			if (!Double.isFinite(dd) || Math.abs(dd) < 1e-12)
				continue;

			double ratio = w0 / wj;
			if (!Double.isFinite(ratio) || ratio <= 0.0)
				continue;

			double logRatio = Math.log(ratio);
			if (!Double.isFinite(logRatio) || Math.abs(logRatio) < 1e-12)
				continue;

// mul 假设：u = exp(-C * d) => log(w0/wj)=C*(dj-d0) => C = logRatio / dd
			double cA = logRatio / dd;
			if (Double.isFinite(cA))
				cMul.add(cA);
		}

		if (cMul.isEmpty())
			return res;

		res.mulMedian = medianOf(cMul);
		res.mulMAD = madOf(cMul, res.mulMedian);
		return res;
	}

	// ====================== correlation helpers ======================

	private static double pearsonCorr(List<Double> xs, List<Double> ys) {
		if (xs == null || ys == null)
			return Double.NaN;
		int n0 = Math.min(xs.size(), ys.size());
		if (n0 < 3)
			return Double.NaN;

		// filter finite pairs
		List<Double> x = new ArrayList<>();
		List<Double> y = new ArrayList<>();
		for (int i = 0; i < n0; i++) {
			double a = xs.get(i);
			double b = ys.get(i);
			if (!Double.isFinite(a) || !Double.isFinite(b))
				continue;
			x.add(a);
			y.add(b);
		}
		int n = x.size();
		if (n < 3)
			return Double.NaN;

		double mx = x.stream().mapToDouble(v -> v).average().orElse(Double.NaN);
		double my = y.stream().mapToDouble(v -> v).average().orElse(Double.NaN);
		if (!Double.isFinite(mx) || !Double.isFinite(my))
			return Double.NaN;

		double sxx = 0.0, syy = 0.0, sxy = 0.0;
		for (int i = 0; i < n; i++) {
			double dx = x.get(i) - mx;
			double dy = y.get(i) - my;
			sxx += dx * dx;
			syy += dy * dy;
			sxy += dx * dy;
		}
		if (sxx <= 0 || syy <= 0)
			return Double.NaN;
		return sxy / Math.sqrt(sxx * syy);
	}

	private static double spearmanCorr(List<Double> xs, List<Double> ys) {
		if (xs == null || ys == null)
			return Double.NaN;
		int n0 = Math.min(xs.size(), ys.size());
		if (n0 < 3)
			return Double.NaN;

		// filter finite pairs
		List<Double> x = new ArrayList<>();
		List<Double> y = new ArrayList<>();
		for (int i = 0; i < n0; i++) {
			double a = xs.get(i);
			double b = ys.get(i);
			if (!Double.isFinite(a) || !Double.isFinite(b))
				continue;
			x.add(a);
			y.add(b);
		}
		int n = x.size();
		if (n < 3)
			return Double.NaN;

		List<Double> rx = rankWithTies(x);
		List<Double> ry = rankWithTies(y);
		return pearsonCorr(rx, ry);
	}

	// average-rank for ties
	private static List<Double> rankWithTies(List<Double> v) {
		int n = v.size();
		List<Integer> idx = new ArrayList<>(n);
		for (int i = 0; i < n; i++)
			idx.add(i);
		idx.sort(Comparator.comparingDouble(v::get));

		List<Double> r = new ArrayList<>(Collections.nCopies(n, 0.0));
		int i = 0;
		while (i < n) {
			int j = i;
			double vi = v.get(idx.get(i));
			while (j + 1 < n && Double.compare(v.get(idx.get(j + 1)), vi) == 0)
				j++;
			// ranks are 1..n
			double avgRank = 0.5 * ((i + 1) + (j + 1));
			for (int k = i; k <= j; k++)
				r.set(idx.get(k), avgRank);
			i = j + 1;
		}
		return r;
	}

	// ====================== small helpers ======================

	private static String safe(Object s) {
		return s == null ? "" : String.valueOf(s);
	}

	private static int countSelected(double[] y) {
		int c = 0;
		for (double v : y)
			if (v > 0.5)
				c++;
		return c;
	}

	private static double stdOf(List<Double> xs, double mean) {
		if (xs == null || xs.size() <= 1)
			return 0.0;
		double var = 0.0;
		for (double v : xs) {
			double d = v - mean;
			var += d * d;
		}
		return Math.sqrt(var / Math.max(1, xs.size() - 1));
	}

	private static double medianOf(List<Double> xs) {
		if (xs == null || xs.isEmpty())
			return Double.NaN;
		List<Double> a = xs.stream().filter(Double::isFinite).sorted().collect(Collectors.toList());
		if (a.isEmpty())
			return Double.NaN;
		int n = a.size();
		if (n % 2 == 1)
			return a.get(n / 2);
		return 0.5 * (a.get(n / 2 - 1) + a.get(n / 2));
	}

	private static double madOf(List<Double> xs, double med) {
		if (xs == null || xs.isEmpty() || !Double.isFinite(med))
			return Double.NaN;
		List<Double> dev = new ArrayList<>();
		for (double v : xs) {
			if (!Double.isFinite(v))
				continue;
			dev.add(Math.abs(v - med));
		}
		return medianOf(dev);
	}

	private static int uniqueCountByRounding(List<Double> xs, int digits) {
		if (xs == null || xs.isEmpty())
			return 0;
		double scale = Math.pow(10, digits);
		HashSet<Long> set = new HashSet<>();
		for (double v : xs) {
			if (!Double.isFinite(v))
				continue;
			long key = Math.round(v * scale);
			set.add(key);
		}
		return set.size();
	}

	private static int uniqueThetaCount(List<Sample> train, int digits) {
		if (train == null || train.isEmpty())
			return 0;
		double scale = Math.pow(10, digits);
		HashSet<String> set = new HashSet<>();
		for (Sample s : train) {
			double[] x = s.theta.values();
			StringBuilder sb = new StringBuilder();
			for (int l = 0; l < x.length; l++) {
				long key = Math.round(x[l] * scale);
				if (l > 0)
					sb.append("|");
				sb.append(key);
			}
			set.add(sb.toString());
		}
		return set.size();
	}

	/**
	 * gini for normalized weights (sum=1, all >=0). G = 2 * sum(i*w_i)/n - (n+1)/n,
	 * with w sorted ascending, i=1..n
	 */
	private static double giniOfNormalizedWeights(List<Double> wNorm) {
		if (wNorm == null || wNorm.isEmpty())
			return Double.NaN;
		List<Double> w = wNorm.stream().filter(x -> Double.isFinite(x) && x >= 0).sorted().collect(Collectors.toList());
		int n = w.size();
		if (n == 0)
			return Double.NaN;

		double sum = w.stream().mapToDouble(x -> x).sum();
		if (!(sum > 0))
			return Double.NaN;

		double acc = 0.0;
		for (int i = 0; i < n; i++) {
			double wi = w.get(i) / sum;
			acc += (i + 1) * wi;
		}
		return (2.0 * acc / n) - ((double) (n + 1) / n);
	}

	/**
	 * baseline demand per lane: average of period demandSum over all aggregated
	 * periods.
	 */
	private static double[] buildBaselineDemand(SampleBuilder.BuildResult br) {
		if (br == null || br.periods == null || br.periods.isEmpty())
			return new double[0];
		int J = br.periods.get(0).demandSum.length;

		double[] sum = new double[J];
		for (PeriodData p : br.periods) {
			for (int j = 0; j < J; j++)
				sum[j] += p.demandSum[j];
		}
		double denom = Math.max(1, br.periods.size());
		for (int j = 0; j < J; j++)
			sum[j] /= denom;
		return sum;
	}

	// ====================== stats helper ======================

	private static class SummaryStats {
		final int n;
		final double mean, std, min, max;

		private SummaryStats(int n, double mean, double std, double min, double max) {
			this.n = n;
			this.mean = mean;
			this.std = std;
			this.min = min;
			this.max = max;
		}

		static SummaryStats of(List<Double> xs) {
			if (xs == null || xs.isEmpty())
				return new SummaryStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
			int n = xs.size();
			double sum = 0, min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
			for (double v : xs) {
				sum += v;
				min = Math.min(min, v);
				max = Math.max(max, v);
			}
			double mean = sum / n;
			double var = 0;
			for (double v : xs) {
				double d = v - mean;
				var += d * d;
			}
			double std = Math.sqrt(var / Math.max(1, n - 1));
			return new SummaryStats(n, mean, std, min, max);
		}

		static SummaryStats ofInt(List<Integer> xs) {
			if (xs == null || xs.isEmpty())
				return new SummaryStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
			List<Double> tmp = new ArrayList<>(xs.size());
			for (int v : xs)
				tmp.add((double) v);
			return of(tmp);
		}

		String pretty() {
			return "n=" + n + "\nmean=" + mean + "\nstd=" + std + "\nmin=" + min + "\nmax=" + max;
		}
	}
}
