package Helper.basicHelper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import Basic.CovariateVector;
import Basic.HistoricalDay;
import Basic.PeriodData;
import Basic.Sample;

public class SampleBuilder {

	public static class BuildResult {
		public final List<PeriodData> periods;
		public final List<Sample> samples;
		public final int thetaDim;

		public BuildResult(List<PeriodData> periods, List<Sample> samples, int thetaDim) {
			this.periods = periods;
			this.samples = samples;
			this.thetaDim = thetaDim;
		}
	}

	/**
	 * Build aggregated periods and scenario samples.
	 *
	 * Semantics: - If cfg.fillMissingDates=true, "days" should already be
	 * calendar-continuous (missing dates filled with 0 demand). - If
	 * cfg.fillMissingDates=false, "days" may skip weekends etc.; aggregation is
	 * done by "L consecutive records".
	 *
	 * current* parameters correspond to z^{T+1} (covariates for the next decision
	 * period). You can pass nulls to default to 0.
	 */
	public static BuildResult build(List<HistoricalDay> days, List<String> laneNames, Config cfg) {

		int J = laneNames.size();
		int L = cfg.aggregationDays;

		// 你强调的语义：k1=0 => 不考虑历史需求协变量
		if (cfg.k1LagPeriods <= 0) {
			cfg.featureFlags.includeLagDemand = false;
		}

		int thetaDim = computeThetaDim(J, cfg);

		// 1) aggregate daily data into periods
		List<PeriodData> periods = new ArrayList<>();
		int t = 0;
		for (int start = 0; start + L <= days.size(); start += L) {
			int end = start + L - 1;

			double[] demSum = new double[J];
			int holidayCount = 0;
			double freightSum = 0.0;
			double consSum = 0.0;
			double weiSum = 0.0;

			for (int k = start; k <= end; k++) {
				HistoricalDay d = days.get(k);
				for (int j = 0; j < J; j++)
					demSum[j] +=(int) d.laneDemand[j];
					
				holidayCount += d.holidayFlag; // 0/1
				freightSum += d.freightIndex; // TSIFRGHT
				consSum += d.consumptionIndex; // PCEC96
				weiSum += d.weiIndex; // WEI
			}

			LocalDate sd = days.get(start).date;
			LocalDate ed = days.get(end).date;

			double avgFreight = freightSum / L;
			double avgCons = consSum / L;
			double avgWei = weiSum / L;

			periods.add(new PeriodData(t, sd, ed, demSum, holidayCount, avgFreight, avgCons, avgWei));
			t++;
		}

		// 2) build samples: theta^t = [d^{t-1}..d^{t-k1}, z^t]
		int k1 = cfg.k1LagPeriods;
		List<Sample> samples = new ArrayList<>();

		int id = 0;
		for (int tt = k1; tt < periods.size(); tt++) {
			PeriodData cur = periods.get(tt);
			double[] thetaArr = buildThetaForPeriod(periods, tt, cfg, J, thetaDim);
			samples.add(new Sample(id++, cur, new CovariateVector(thetaArr), 0.0));
		}

		// default uniform weights
		double pw = 1.0 / Math.max(1, samples.size());
		for (Sample s : samples)
			s.weight = pw;

		return new BuildResult(periods, samples, thetaDim);
	}

	/**
	 * Build samples from already-aggregated periods.
	 * This is a minimal incremental entry-point for synthetic/weekly-wide data sources.
	 *
	 * Semantics are the same as {@link #build(List, List, Config)} after the aggregation step:
	 * - theta^t = [d^{t-1}..d^{t-k1}, z^t] controlled by cfg.featureFlags and cfg.k1LagPeriods
	 * - if cfg.lagDemandAsShare==true and demandAgg==false, lag blocks use share instead of raw demand
	 */
	public static BuildResult buildFromPeriods(List<PeriodData> periods, List<String> laneNames, Config cfg) {
		if (periods == null || periods.isEmpty()) {
			return new BuildResult(new ArrayList<>(), new ArrayList<>(), 0);
		}
		int J = laneNames.size();

		if (cfg.k1LagPeriods <= 0) {
			cfg.featureFlags.includeLagDemand = false;
		}

		int thetaDim = computeThetaDim(J, cfg);
		int k1 = cfg.k1LagPeriods;
		List<Sample> samples = new ArrayList<>();

		int id = 0;
		for (int tt = k1; tt < periods.size(); tt++) {
			PeriodData cur = periods.get(tt);
			double[] thetaArr = buildThetaForPeriod(periods, tt, cfg, J, thetaDim);
			samples.add(new Sample(id++, cur, new CovariateVector(thetaArr), 0.0));
		}

		double pw = 1.0 / Math.max(1, samples.size());
		for (Sample s : samples) s.weight = pw;

		return new BuildResult(periods, samples, thetaDim);
	}

	/** theta 的总维度（由特征开关决定），不需要 schema。 */
	private static int computeThetaDim(int J, Config cfg) {
		int dim = 0;

		if (cfg.featureFlags.includeLagDemand && cfg.k1LagPeriods > 0) {
			dim += cfg.k1LagPeriods * (cfg.demandAgg ? 1 : J);
		}
		if (cfg.featureFlags.includeHolidayCount)
			dim += 1;
		if (cfg.featureFlags.includeFreightIndex)
			dim += 1;
		if (cfg.featureFlags.includeConsumptionIndex)
			dim += 1;
		if (cfg.featureFlags.includeWEIIndex)
			dim += 1;

		return dim;
	}

	private static double[] buildThetaForPeriod(List<PeriodData> periods, int tt, Config cfg, int J, int thetaDim) {
		double[] theta = new double[thetaDim];
		int pos = 0;

		// lag demand blocks: d^{tt-1},...,d^{tt-k1}
		if (cfg.featureFlags.includeLagDemand && cfg.k1LagPeriods > 0) {
			for (int lag = 1; lag <= cfg.k1LagPeriods; lag++) {
				if (!cfg.demandAgg) {
					double[] d = periods.get(tt - lag).demandSum;
					if (cfg.lagDemandAsShare) {
						double tot = 0.0;
						for (int j = 0; j < J; j++) tot += d[j];
						if (tot <= 1e-12) tot = 1.0;
						for (int j = 0; j < J; j++) theta[pos++] = d[j] / tot;
					} else {
						for (int j = 0; j < J; j++) theta[pos++] = d[j];
					}
				} else {
					double totalD = 0;
					double[] d = periods.get(tt - lag).demandSum;
					for (double d1 : d) {
						totalD += d1;
					}
					theta[pos++] = totalD;
				}
			}
		}

		// z^t: current period external info
		PeriodData cur = periods.get(tt);
		if (cfg.featureFlags.includeHolidayCount)
			theta[pos++] = (double) cur.holidayCount;
		if(Double.isNaN(cur.holidayCount)) {
			System.out.println();
		}
		if (cfg.featureFlags.includeFreightIndex)
			theta[pos++] = cur.avgFreightIndex;
		if (cfg.featureFlags.includeConsumptionIndex)
			theta[pos++] = cur.avgConsumptionIndex;
		if (cfg.featureFlags.includeWEIIndex)
			theta[pos++] = cur.avgWEIIndex;

		return theta;
	}

	/** how to choose thetaNow from samples (for backtesting) */
	public enum NowPickMode {
		LAST, // use the last sample as now
		RANDOM, // randomly pick one sample as now
		BY_INDEX // pick by a given index in the samples list
	}

	/**
	 * Result of splitting: thetaNow taken from one sample, remaining as training
	 * samples.
	 */
	public static class NowSplitResult {
		public final List<Sample> trainSamples; // copied list (and copied Sample objects)
		public final CovariateVector thetaNow; // copied vector
		public final Sample holdout; // copied holdout sample
		public final int holdoutIndex; // index in the original list

		public NowSplitResult(List<Sample> trainSamples, CovariateVector thetaNow, Sample holdout, int holdoutIndex) {
			this.trainSamples = trainSamples;
			this.thetaNow = thetaNow;
			this.holdout = holdout;
			this.holdoutIndex = holdoutIndex;
		}
	}

	/**
	 * Split thetaNow from an existing samples list WITHOUT mutating the input list.
	 *
	 * Semantics (backtesting): - choose one sample s* as the "current period" - set
	 * thetaNow = s*.theta - remove s* from training sample set
	 *
	 * IMPORTANT: - returns deep copies of Sample objects to avoid later
	 * standardization/weight changes affecting the original samples.
	 */
	public static NowSplitResult splitThetaNowFromSamples(List<Sample> samples, NowPickMode mode,
			Integer indexIfByIndex, Config cfg) {
		if (samples == null || samples.isEmpty()) {
			throw new IllegalArgumentException("samples is empty; cannot split thetaNow.");
		}

		final int n = samples.size();
		int pick;

		switch (mode) {
		case LAST:
			pick = n - 1;
			break;
		case RANDOM:
			// deterministic random
			Random rnd = new Random(cfg.seed);
			pick = rnd.nextInt(n);
			while (pick < cfg.k1LagPeriods) {
				pick = rnd.nextInt(n);
			}
			break;
		case BY_INDEX:
			if (indexIfByIndex == null || indexIfByIndex < cfg.k1LagPeriods) {
				throw new IllegalArgumentException("indexIfByIndex is required for BY_INDEX mode.");
			}
			pick = indexIfByIndex;
			if (pick < 0 || pick >= n) {
				throw new IllegalArgumentException("indexIfByIndex out of range: " + pick + ", n=" + n);
			}
			break;
		default:
			throw new IllegalArgumentException("Unknown NowPickMode: " + mode);
		}

		// deep copy all samples
		List<Sample> copied = new ArrayList<>(n);
		for (Sample s : samples)
			copied.add(copySample(s));

		// remove holdout from copied list
		Sample holdout = copied.remove(pick);

		// thetaNow = holdout.theta (copied)
		CovariateVector thetaNow = copyTheta(holdout.theta);

		return new NowSplitResult(copied, thetaNow, holdout, pick);
	}

	private static Sample copySample(Sample s) {
		// period is immutable in your design, so we can share the same reference safely
		CovariateVector thetaCopy = copyTheta(s.theta);
		Sample c = new Sample(s.id, s.period, thetaCopy, s.weight);
		return c;
	}

	private static CovariateVector copyTheta(CovariateVector theta) {
		double[] v = theta.values();
		return new CovariateVector(Arrays.copyOf(v, v.length));
	}
}
