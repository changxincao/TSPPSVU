package Model;

import java.util.Arrays;
import java.util.List;

import Basic.ProcurementParams;
import Basic.Sample;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;

final class RCSAADecompositionSupport {
    // Public DROModel preprocessing removes exact zeros. Positive weights below
    // 1e-8 are floored here defensively before normalization.
    static final double FORMAL_WEIGHT_FLOOR = 1e-8;

    /**
     * 单个场景 w 下由对偶子问题返回的信息。
     * Q(y,d^w) 被写成 affine 函数族的最大值，这里保存当前极点对应的一条 affine 面。
     */
    static final class ScenarioCut {
        final int scenarioIndex;
        final double qValue;
        final double constantPart;
        final double[] yCoeff;
        final double scenarioUpperBound;

        ScenarioCut(int scenarioIndex, double qValue, double constantPart, double[] yCoeff, double scenarioUpperBound) {
            this.scenarioIndex = scenarioIndex;
            this.qValue = qValue;
            this.constantPart = constantPart;
            this.yCoeff = yCoeff;
            this.scenarioUpperBound = scenarioUpperBound;
        }

        double minAffineLowerBound() {
            // 在 y_i ∈ {0,1} 上，这条 affine 面的最小可能值。
            // 用它配合场景上界构造 incumbent-specific upper cut 的 M_w。
            double bound = constantPart;
            for (double c : yCoeff) {
                bound += Math.min(0.0, c);
            }
            return bound;
        }

        double incumbentMw() {
            // 对任意 y，要求
            //   Q_w(y) <= g_k(y) + M_w * Delta(y, y*)
            // 这里先用“场景统一上界 - 当前 affine 面全局下界”的保守方式取 M_w。
            double mw = scenarioUpperBound - minAffineLowerBound();
            if (!Double.isFinite(mw) || mw < 0.0) {
                throw new IllegalStateException("Invalid incumbent M_w=" + mw + " for scenario=" + scenarioIndex);
            }
            return mw;
        }
    }

    static final class SearchState {
        double bestUpperBound = Double.POSITIVE_INFINITY;
        double[] bestY = null;
    }

    private RCSAADecompositionSupport() {
    }

    static double[] validateAndNormalizeWeights(List<Sample> samples) {
        // 这里统一做两件事：
        // 1) 检查权重是否合法；
        // 2) Reject exact zeros that should have been removed at the adapter;
        // 3) floor positive weights at 1e-8 and renormalize.
        double[] pi = new double[samples.size()];
        double sum = 0.0;
        for (int w = 0; w < samples.size(); w++) {
            double pw = samples.get(w).weight;
            if (!Double.isFinite(pw) || pw < 0.0) {
                throw new IllegalStateException("Invalid RCSAA sample weight at idx=" + w + ": " + pw);
            }
            if (pw == 0.0)
                throw new IllegalStateException("Zero RCSAA weight must be pruned before solving. idx=" + w);
            pi[w] = Math.max(pw, FORMAL_WEIGHT_FLOOR);
            sum += pi[w];
        }
        if (!(sum > 0.0)) {
            throw new IllegalStateException("RCSAA weights sum to zero.");
        }
        for (int w = 0; w < pi.length; w++) {
            pi[w] /= sum;
        }
        return pi;
    }

    static double evaluateObjective(double[] qValues, double[] pi, double lambda) {
        // 原始 RCSAA 目标：条件均值 + lambda * 条件标准差
        double mean = 0.0;
        for (int w = 0; w < qValues.length; w++) {
            mean += pi[w] * qValues[w];
        }
        double variance = 0.0;
        for (int w = 0; w < qValues.length; w++) {
            double diff = qValues[w] - mean;
            variance += pi[w] * diff * diff;
        }
        return mean + lambda * Math.sqrt(Math.max(0.0, variance));
    }

    static double scenarioUpperBound(ProcurementParams p, double[] d) {
        // 这里要的是一个“对任意 y 都成立”的 Q_w 上界，供 upper cut 的 M_w 使用。
        // 这里构造的是一个显式可行解的成本上界：
        // 1) 服务部分：所有需求都交给 spot market，即 x=0, s_j=d_j。
        //    这对任意一阶段选择 y 都可行，所以 spot-only cost 一定是 Q_w(y) 的上界。
        // 2) MQC 罚金部分：若某个被选中的供应商完全没有分配货量，则最多产生 h_i * p_i 的罚金。
        //    又因为一阶段有 sum_i y_i <= beta，所以同时产生罚金的供应商个数最多只有 beta 个。
        //    因此罚金上界只需取最大的 beta 个 h_i * p_i 之和。
        // 最终得到：
        //   UB_w = sum_j e_j d_j + sum_{largest beta carriers} h_i p_i
        // 这个界比“所有供应商罚金全加”更紧，也比按 lane 取最大服务单价更合理。
        double spotOnlyServiceCost = 0.0;
        for (int j = 0; j < p.J; j++) {
            spotOnlyServiceCost += p.e[j] * d[j];
        }

        double[] maxPenaltyByCarrier = new double[p.I];
        for (int i = 0; i < p.I; i++) {
            maxPenaltyByCarrier[i] = p.h[i] * p.p[i];
        }
        Arrays.sort(maxPenaltyByCarrier);

        double topBetaPenalty = 0.0;
        int take = Math.min(p.beta, maxPenaltyByCarrier.length);
        for (int k = 0; k < take; k++) {
            topBetaPenalty += maxPenaltyByCarrier[maxPenaltyByCarrier.length - 1 - k];
        }
        return spotOnlyServiceCost + topBetaPenalty;
    }

    static ScenarioCut solveScenarioCut(ProcurementParams p,
                                        double[] d,
                                        double[] yVal,
                                        int scenarioIndex,
                                        boolean enforceDemandEquality) throws Exception {
        // 这里直接复用 DROBenders 里同一套 recourse dual。
        // 给定一阶段 y 后，求 Q(y,d^w) 以及对应的对偶极点，用于生成 cut。
        int I = p.I;
        int J = p.J;

        IloCplex cplex = new IloCplex();
        try {
            cplex.setOut(null);

            IloNumVar[] alpha = new IloNumVar[J];
            for (int j = 0; j < J; j++) {
                double lowerBound = enforceDemandEquality ? Double.NEGATIVE_INFINITY : 0.0;
                alpha[j] = cplex.numVar(lowerBound, Double.POSITIVE_INFINITY, "a_" + j);
            }

            IloNumVar[] beta = new IloNumVar[I];
            IloNumVar[] gamma = new IloNumVar[I];
            for (int i = 0; i < I; i++) {
                beta[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "b_" + i);
                gamma[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "g_" + i);
            }

            IloNumVar[][] sigma = new IloNumVar[I][J];
            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (!p.eligible[i][j]) {
                        continue;
                    }
                    sigma[i][j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, "s_" + i + "_" + j);
                }
            }

            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (!p.eligible[i][j]) {
                        continue;
                    }
                    IloLinearNumExpr lhs = cplex.linearNumExpr();
                    lhs.addTerm(1.0, alpha[j]);
                    lhs.addTerm(1.0, beta[i]);
                    lhs.addTerm(-1.0, gamma[i]);
                    lhs.addTerm(-1.0, sigma[i][j]);
                    cplex.addLe(lhs, p.r[i][j], "c_x_" + i + "_" + j);
                }
            }

            for (int j = 0; j < J; j++) {
                cplex.addLe(alpha[j], p.e[j], "c_s_" + j);
            }
            for (int i = 0; i < I; i++) {
                cplex.addLe(beta[i], p.h[i], "c_u_" + i);
            }

            IloLinearNumExpr obj = cplex.linearNumExpr();
            for (int j = 0; j < J; j++) {
                obj.addTerm(d[j], alpha[j]);
            }
            for (int i = 0; i < I; i++) {
                double yi = yVal[i] > 0.5 ? 1.0 : 0.0;
                obj.addTerm(p.p[i] * yi, beta[i]);
                obj.addTerm(-p.M[i] * yi, gamma[i]);
            }
            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (!p.eligible[i][j]) {
                        continue;
                    }
                    obj.addTerm(-p.q[i][j], sigma[i][j]);
                }
            }
            cplex.addMaximize(obj);

            if (!cplex.solve()) {
                throw new IllegalStateException("RCSAA dual subproblem failed. Status=" + cplex.getCplexStatus());
            }

            double q = cplex.getObjValue();
            double constant = 0.0;
            double[] yCoeff = new double[I];

            for (int j = 0; j < J; j++) {
                constant += d[j] * cplex.getValue(alpha[j]);
            }

            for (int i = 0; i < I; i++) {
                double betaVal = cplex.getValue(beta[i]);
                double gammaVal = cplex.getValue(gamma[i]);
                yCoeff[i] = p.p[i] * betaVal - p.M[i] * gammaVal;
            }

            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (!p.eligible[i][j]) {
                        continue;
                    }
                    constant -= p.q[i][j] * cplex.getValue(sigma[i][j]);
                }
            }
            // 返回的 affine 面形如：
            //   g_k(y) = constantPart + sum_i yCoeff_i * y_i
            return new ScenarioCut(scenarioIndex, q, constant, yCoeff, scenarioUpperBound(p, d));
        } finally {
            cplex.end();
        }
    }
}
