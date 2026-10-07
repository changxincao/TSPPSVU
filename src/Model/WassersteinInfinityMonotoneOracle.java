package Model;

import Basic.ProcurementParams;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloObjective;
import ilog.cplex.IloCplex;
import java.util.TreeSet;

/** Exact scaled-L-infinity W1 separation when fixed-y recourse is nondecreasing.
 * At distance t the dominating point is min(upper, nominal+t*scale).
 * Between its saturation breakpoints Q(y,d(t))-eta*t is convex, hence its
 * maximum is attained at a breakpoint. Each candidate uses a continuous LP.
 */
final class WassersteinInfinityMonotoneOracle {
    private WassersteinInfinityMonotoneOracle() { }

    static void requireMonotone(WassersteinBoxInput input) {
        if (input.groundNorm != WassersteinBoxInput.GroundNorm.L_INFINITY)
            throw new IllegalArgumentException("L-infinity ground norm required.");
        ProcurementParams p = input.params;
        for (int j = 0; j < p.J; j++)
            if (!Double.isFinite(p.e[j]) || p.e[j] < 0)
                throw new IllegalArgumentException("Nonnegative finite spot costs required.");
        for (int i = 0; i < p.I; i++) {
            if (!Double.isFinite(p.h[i]) || p.h[i] < 0)
                throw new IllegalArgumentException("Nonnegative finite MQC penalty required.");
            for (int j = 0; j < p.J; j++) if (p.eligible[i][j]) {
                if (!Double.isFinite(p.r[i][j]) || p.r[i][j] < p.h[i])
                    throw new IllegalArgumentException("Monotone oracle requires h_i <= r_ij: " + i + "/" + j);
            }
        }
    }

    static WassersteinBoxOracle.Result solve(WassersteinBoxInput input, int sample,
            double[] y, double eta, int threads, double budgetSeconds) throws Exception {
        requireMonotone(input);
        ProcurementParams p = input.params;
        if (y == null || y.length != p.I || !Double.isFinite(eta) || eta < -1e-9)
            throw new IllegalArgumentException("Invalid carrier decision or eta.");
        for (double value : y) if (!Double.isFinite(value)
                || Math.min(Math.abs(value), Math.abs(value - 1)) > 1e-6)
            throw new IllegalArgumentException("Binary carrier decision required.");
        long start = System.nanoTime();
        TreeSet<Double> times = new TreeSet<>();
        times.add(0.0);
        for (int j = 0; j < p.J; j++)
            times.add(Math.max(0, (input.upper[j] - input.demand[sample][j]) / input.scale[j]));
        IloCplex cplex = new IloCplex();
        try {
            cplex.setOut(null);
            cplex.setParam(IloCplex.Param.Threads, threads);
            IloNumVar[] a = new IloNumVar[p.J], b = new IloNumVar[p.I], g = new IloNumVar[p.I];
            IloNumVar[][] sigma = new IloNumVar[p.I][p.J];
            for (int j = 0; j < p.J; j++) a[j] = cplex.numVar(0, p.e[j], "a_" + j);
            for (int i = 0; i < p.I; i++) {
                b[i] = cplex.numVar(0, p.h[i], "b_" + i);
                double bound = 0;
                for (int j = 0; j < p.J; j++) if (p.eligible[i][j])
                    bound = Math.max(bound, Math.max(0, p.e[j] + p.h[i] - p.r[i][j]));
                g[i] = cplex.numVar(0, bound, "g_" + i);
                for (int j = 0; j < p.J; j++) if (p.eligible[i][j]) {
                    sigma[i][j] = cplex.numVar(0,
                            Math.max(0, p.e[j] + p.h[i] - p.r[i][j]), "s_" + i + "_" + j);
                    IloLinearNumExpr lhs = cplex.linearNumExpr();
                    lhs.addTerm(1, a[j]); lhs.addTerm(1, b[i]);
                    lhs.addTerm(-1, g[i]); lhs.addTerm(-1, sigma[i][j]);
                    cplex.addLe(lhs, p.r[i][j]);
                }
            }
            IloObjective objective = cplex.addMaximize();
            WassersteinBoxOracle.Result best = null;
            double optimizerSeconds = 0;
            for (double t : times) {
                double[] d = new double[p.J];
                IloLinearNumExpr expression = cplex.linearNumExpr();
                for (int j = 0; j < p.J; j++) {
                    d[j] = Math.min(input.upper[j], input.demand[sample][j] + t * input.scale[j]);
                    expression.addTerm(d[j], a[j]);
                }
                double distance = input.distance(sample, d);
                expression.setConstant(-Math.max(0, eta) * distance);
                for (int i = 0; i < p.I; i++) {
                    if (y[i] > .5) {
                        expression.addTerm(p.p[i], b[i]); expression.addTerm(-p.M[i], g[i]);
                    }
                    for (int j = 0; j < p.J; j++) if (p.eligible[i][j])
                        expression.addTerm(-p.q[i][j], sigma[i][j]);
                }
                objective.setExpr(expression);
                double remaining = budgetSeconds - (System.nanoTime() - start) / 1e9;
                if (remaining <= 0) throw new WassersteinBoxOracle.TimeLimitException("Linf oracle budget exhausted");
                if (Double.isFinite(remaining)) cplex.setParam(IloCplex.Param.TimeLimit, remaining);
                long clock = System.nanoTime();
                boolean solved = cplex.solve();
                optimizerSeconds += (System.nanoTime() - clock) / 1e9;
                if (!solved || cplex.getStatus() != IloCplex.Status.Optimal) {
                    if (String.valueOf(cplex.getCplexStatus()).contains("TimeLim"))
                        throw new WassersteinBoxOracle.TimeLimitException("Linf candidate LP timed out");
                    throw new IllegalStateException("Linf candidate LP failed: " + cplex.getStatus());
                }
                double value = cplex.getObjValue();
                if (best == null || value > best.value()) {
                    double[] alpha = cplex.getValues(a), coefficient = new double[p.I];
                    double constant = 0, affineConstant = 0;
                    for (int i = 0; i < p.I; i++) {
                        coefficient[i] = p.p[i] * cplex.getValue(b[i]) - p.M[i] * cplex.getValue(g[i]);
                        for (int j = 0; j < p.J; j++) if (p.eligible[i][j])
                            constant -= p.q[i][j] * cplex.getValue(sigma[i][j]);
                    }
                    affineConstant = constant;
                    for (int j = 0; j < p.J; j++) affineConstant += alpha[j] * d[j];
                    best = new WassersteinBoxOracle.Result(value, alpha, coefficient, constant,
                            d, affineConstant, -distance, 0, value);
                }
            }
            // Every candidate LP must finish before the maximum is a valid oracle bound.
            return new WassersteinBoxOracle.Result(best.value(), best.alpha(), best.yCoefficient(),
                    best.dualConstant(), best.worstDemand(), best.affineConstant(), best.etaCoefficient(),
                    optimizerSeconds, best.value());
        } finally { cplex.end(); }
    }
}
