package Helper.basicHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import Basic.ProcurementParams;

/**
 * Instance generator for procurement parameters.
 * Input: baseline demand per lane dBase[j] (e.g., average demand per aggregated period).
 */
public class InstanceGenerator {

    public enum CapacityMode {
        TIGHT,   // q_ij ~ U[0.3 dBase_j, 0.5 dBase_j]
        LOOSE    // q_ij ~ U[0.8 dBase_j, 1.0 dBase_j]
    }

    public static class GenConfig {
        // lane base rate rBar_j ~ U[rBarLow, rBarHigh]
        public double rBarLow = 20.0;
        public double rBarHigh = 100.0;

        // dispersion tau_j ~ U[tauLow, tauHigh]
        public double tauLow = 0.05;
        public double tauHigh = 0.30;

        // ---------------------------------------------------------------------
        // (OLD) spot e_j ~ U[spotLow, spotHigh]
        // NOTE: kept for backward compatibility, but the new default uses Baseline-A:
        //       e_j = kappa_j * median_i(r_ij on lane j), where kappa_j ~ U[spotMultLow, spotMultHigh]
        // ---------------------------------------------------------------------
        public double spotLow = 60.0;
        public double spotHigh = 120.0;

        // (NEW) spot multiplier range for Baseline-A
        public double spotMultLow = 1.5;
        public double spotMultHigh = 2.5;

        // MQC p_i ~ U[mqcLow * D_i, mqcHigh * D_i]
        public double mqcLow = 0.1;
        public double mqcHigh = 0.2;

        // Optional explicit lane-capacity factors q_ij ~ U[capacityFactorLow * d_j, capacityFactorHigh * d_j].
        // If either value is NaN, fall back to capacityMode defaults.
        public double capacityFactorLow = Double.NaN;
        public double capacityFactorHigh = Double.NaN;

        // alpha/beta as ratios of |I|
        // (UPDATED DEFAULTS) alpha=0.1, beta=0.7
        public double alphaRatio = 0.1; // ceil(alphaRatio * I)
        public double betaRatio = 0.7;  // ceil(betaRatio * I), then clipped to I

        // coverage
        public boolean coverAllLanes = true;
        public double coverPctLow = 0.1;
        public double coverPctHigh = 0.8;

        public CapacityMode capacityMode = CapacityMode.TIGHT;

        // carrier name prefix
        public String carrierPrefix = "C";

        // ---------------------------------------------------------------------
        // (NEW) lane-wise 3-group pricing to increase r_ij discrimination:
        // For each lane j: randomly split carriers into 3 groups (low/mid/high),
        // and scale rBar_j by the corresponding factor before applying +/- tau_j noise.
        // ---------------------------------------------------------------------
        public double priceFactorLow = 0.6;
        public double priceFactorMid = 1.00;
        public double priceFactorHigh = 1.5;
    }

    public static ProcurementParams generate(int numCarriers, double[] dBase, GenConfig gcfg, Config cfg) {
        int I = numCarriers;
        int J = dBase.length;

        Random rnd = new Random(cfg.seed);

        // carrier names
        List<String> carriers = new ArrayList<>(I);
        for (int i = 0; i < I; i++) carriers.add(gcfg.carrierPrefix + (i + 1));

        // alpha/beta
        int alpha = (int) Math.ceil(gcfg.alphaRatio * I);
        int beta = (int) Math.ceil(gcfg.betaRatio * I);
        alpha = clamp(alpha, 1, I);
        beta = clamp(beta, 1, I);
        if (alpha > beta) alpha = beta;

        // lane base rate + dispersion
        double[] rBar = new double[J];
        double[] tau = new double[J];
        for (int j = 0; j < J; j++) {
            rBar[j] = unif(rnd, gcfg.rBarLow, gcfg.rBarHigh);
            tau[j] = unif(rnd, gcfg.tauLow, gcfg.tauHigh);
        }

        // ---------------------------------------------------------------------
        // (OLD) spot cost generation: e_j ~ U[spotLow, spotHigh]
        // NOTE: requested to keep but comment out; new spot is generated AFTER r_ij is built.
        // ---------------------------------------------------------------------
        // double[] e = new double[J];
        // for (int j = 0; j < J; j++) {
        //     e[j] = unif(rnd, gcfg.spotLow, gcfg.spotHigh);
        // }

        double[][] r = new double[I][J];
        double[][] q = new double[I][J];
        boolean[][] eligible = new boolean[I][J];

        double[] p = new double[I];
        double[] h = new double[I];

        // lane index list
        List<Integer> allLanes = new ArrayList<>(J);
        for (int j = 0; j < J; j++) allLanes.add(j);

        // ---------------------------------------------------------------------
        // (NEW) Pre-build lane-wise price factors for each carrier to increase discrimination.
        // For each lane j: shuffle carriers and split into 3 groups ~ 1/3 each.
        // factor[i][j] is applied to rBar[j] before tau-noise.
        // ---------------------------------------------------------------------
        double[][] laneCarrierFactor = new double[I][J];
        for (int j = 0; j < J; j++) {
            List<Integer> idx = new ArrayList<>(I);
            for (int i = 0; i < I; i++) idx.add(i);
            Collections.shuffle(idx, rnd);

            int nLow = I / 3;
            int nMid = I / 3;
            // remaining go to high group
            for (int k = 0; k < I; k++) {
                int i = idx.get(k);
                if (k < nLow) {
                    laneCarrierFactor[i][j] = gcfg.priceFactorLow;
                } else if (k < nLow + nMid) {
                    laneCarrierFactor[i][j] = gcfg.priceFactorMid;
                } else {
                    laneCarrierFactor[i][j] = gcfg.priceFactorHigh;
                }
            }
        }

        for (int i = 0; i < I; i++) {

            // choose J_i
            List<Integer> Ji;
            if (gcfg.coverAllLanes) {
                Ji = allLanes;
            } else {
                double pct = unif(rnd, gcfg.coverPctLow, gcfg.coverPctHigh);
                int k = (int) Math.round(pct * J);
                k = Math.max(1, Math.min(J, k));

                List<Integer> tmp = new ArrayList<>(allLanes);
                Collections.shuffle(tmp, rnd);
                Ji = tmp.subList(0, k);
            }

            double maxR = 0.0;
            double Di = 0.0;

            for (int idx = 0; idx < Ji.size(); idx++) {
                int j = Ji.get(idx);
                eligible[i][j] = true;

                // -----------------------------------------------------------------
                // (OLD) r_ij generation: symmetric noise around rBar_j
                // r_ij ~ U[rBar_j(1-tau_j), rBar_j(1+tau_j)]
                // -----------------------------------------------------------------
                // double lowR = rBar[j] * (1.0 - tau[j]);
                // double highR = rBar[j] * (1.0 + tau[j]);
                // double rij = unif(rnd, lowR, highR);

                // -----------------------------------------------------------------
                // (NEW) r_ij generation with higher discrimination (3-group pricing):
                // For lane j, carrier i belongs to {low,mid,high} price group,
                // scaling rBar_j by a lane-specific factor before tau-noise.
                // -----------------------------------------------------------------
                double base = rBar[j] * laneCarrierFactor[i][j];
                double lowR = base * (1.0 - tau[j]);
                double highR = base * (1.0 + tau[j]);
                double rij = unif(rnd, lowR, highR);

                r[i][j] = rij;
                if (rij > maxR) maxR = rij;

                // q_ij
                double dj = Math.max(0.0, dBase[j]);
                double qij;
                if (Double.isFinite(gcfg.capacityFactorLow) && Double.isFinite(gcfg.capacityFactorHigh)) {
                    qij = unif(rnd, gcfg.capacityFactorLow * dj, gcfg.capacityFactorHigh * dj);
                } else if (gcfg.capacityMode == CapacityMode.TIGHT) {
                    qij = unif(rnd, 0.3 * dj, 0.5 * dj);
                } else {
                    qij = unif(rnd, 0.8 * dj, 1.0 * dj);
                }
                q[i][j] = qij;

                // D_i = sum_{j in J_i} dBase_j
                Di += dj;
            }

            // p_i ~ U[mqcLow * D_i, mqcHigh * D_i]
            p[i] = unif(rnd, gcfg.mqcLow * Di, gcfg.mqcHigh * Di);

            // h_i = max_{j in J_i} r_ij
            h[i] = maxR;
        }

        // ---------------------------------------------------------------------
        // (NEW) spot cost Baseline-A:
        // e_j = kappa_j * median_i r_ij (over eligible carriers on lane j),
        // where kappa_j ~ U[spotMultLow, spotMultHigh].
        //
        // This avoids the previous issue where spot/contract ratios varied wildly
        // across lanes due to rBar_j heterogeneity.
        // ---------------------------------------------------------------------
        double[] e = new double[J];
        for (int j = 0; j < J; j++) {
            List<Double> rijList = new ArrayList<>();
            for (int i = 0; i < I; i++) {
                if (eligible[i][j]) rijList.add(r[i][j]);
            }

            if (rijList.isEmpty()) {
                // fallback (should be rare if coverAllLanes=true)
                e[j] = unif(rnd, gcfg.spotLow, gcfg.spotHigh);
            } else {
                double med = median(rijList);
                double kappa = unif(rnd, gcfg.spotMultLow, gcfg.spotMultHigh);
                e[j] = kappa * med;
            }
        }

        // M is derived inside ProcurementParams
        return new ProcurementParams(carriers, J, e, p, h, q, r, eligible, alpha, beta);
    }

    private static double unif(Random rnd, double a, double b) {
        if (b < a) { double t = a; a = b; b = t; }
        return a + (b - a) * rnd.nextDouble();
    }

    private static int clamp(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private static double median(List<Double> xs) {
        Collections.sort(xs);
        int n = xs.size();
        if (n == 1) return xs.get(0);
        int mid = n / 2;
        if (n % 2 == 1) return xs.get(mid);
        return 0.5 * (xs.get(mid - 1) + xs.get(mid));
    }
}
