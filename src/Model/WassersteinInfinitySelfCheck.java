package Model;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Native regression: dual separation vs independent primal recourse; CCG vs transport LP. */
public final class WassersteinInfinitySelfCheck {
    public static void main(String[] args) throws Exception {
        ProcurementParams p = new ProcurementParams(List.of("a", "b"), 2,
                new double[]{8, 11}, new double[]{3, 4}, new double[]{2, 3},
                new double[][]{{3, 2}, {1, 4}}, new double[][]{{2, 6}, {5, 3}},
                new boolean[][]{{true, true}, {true, true}}, 1, 2);
        double[][] nominal = {{1, 2}, {4, 3}};
        double[] lower = {1, 1}, upper = {6, 7}, scale = {4, 8}, probability = {.3, .7};
        var input = new WassersteinBoxInput(p, nominal, probability, lower, upper, scale,
                .12, WassersteinBoxInput.GroundNorm.L_INFINITY);
        var l1 = new WassersteinBoxInput(p, nominal, probability, lower, upper, scale, .12);
        close(input.distance(0, new double[]{3, 6}), .5, "Linf distance");
        close(l1.distance(0, new double[]{3, 6}), 1, "Default L1 distance changed");
        close(input.lipschitzBound(), 120, "Linf eta bound must use sum");
        close(l1.lipschitzBound(), 88, "Default L1 eta bound changed");
        double[][] choices = {{1, 0}, {0, 1}, {1, 1}};
        int checked = 0;
        for (double[] y : choices) for (int s = 0; s < 2; s++) {
            List<double[]> points = candidates(input, s);
            for (double eta : new double[]{0, 15, 120}) {
                double expected = Double.NEGATIVE_INFINITY;
                for (double[] d : points)
                    expected = Math.max(expected, primal(p, y, d) - eta * input.distance(s, d));
                var result = WassersteinInfinityMonotoneOracle.solve(input, s, y, eta, 1, 30);
                close(result.value(), expected, "Oracle vs independent primal");
                close(primal(p, y, result.worstDemand()) + eta * result.etaCoefficient(),
                        result.value(), "Returned worst point inconsistent");
                close(result.upperBound(), expected, "Oracle bound");
                // Includes downward/off-path points; monotone path must dominate them.
                for (double[] d : new double[][]{{1, 1}, {2, 6}, {5, 2}, {6, 7}})
                    if (primal(p, y, d) - eta * input.distance(s, d) > expected + 1e-7)
                        throw new AssertionError("Off-path point beats oracle");
                checked++;
            }
        }
        Config config = new Config();
        config.enforceDemandEquality = true; config.threads = 1;
        config.timeLimitSeconds = 60; config.tol = 1e-4;
        for (double radius : new double[]{0, .12, 1}) {
            var testInput = new WassersteinBoxInput(p, nominal, probability, lower, upper, scale,
                    radius, WassersteinBoxInput.GroundNorm.L_INFINITY);
            double expected = Double.POSITIVE_INFINITY;
            for (double[] y : choices) expected = Math.min(expected, transport(testInput, y));
            Solution result = new ContextualWassersteinBoxCcgSolver().solve(testInput, config).solution();
            close(result.objValue, expected, "CCG vs exhaustive-y transport LP");
            close(transport(testInput, result.y), expected, "Returned y not globally optimal");
            if (!result.certifiedOptimal) throw new AssertionError("Tiny model not certified");
        }
        boolean timedOut = false;
        try { WassersteinInfinityMonotoneOracle.solve(input, 0, choices[0], 0, 1, 1e-12); }
        catch (WassersteinBoxOracle.TimeLimitException expected) { timedOut = true; }
        if (!timedOut) throw new AssertionError("Incomplete candidate pass accepted");
        p.h[0] = 3;
        boolean rejected = false;
        try { new ContextualWassersteinBoxCcgSolver().solve(input, config); }
        catch (IllegalArgumentException expected) { rejected = true; }
        if (!rejected) throw new AssertionError("h>r incorrectly accepted");
        System.out.println("WassersteinInfinitySelfCheck PASS oracleCases=" + checked
                + " globalRadiusCases=3 monotonicityGuard=true incompletePassGuard=true");
    }

    private static List<double[]> candidates(WassersteinBoxInput in, int s) {
        TreeSet<Double> times = new TreeSet<>(); times.add(0.0);
        for (int j = 0; j < in.params.J; j++) times.add((in.upper[j] - in.demand[s][j]) / in.scale[j]);
        List<double[]> points = new ArrayList<>();
        for (double t : times) {
            double[] point = new double[in.params.J];
            for (int j = 0; j < point.length; j++)
                point[j] = Math.min(in.upper[j], in.demand[s][j] + t * in.scale[j]);
            points.add(point);
        }
        return points;
    }

    private static double primal(ProcurementParams p, double[] y, double[] d) throws Exception {
        IloCplex c = new IloCplex();
        try {
            c.setOut(null); c.setParam(IloCplex.Param.Threads, 1);
            IloNumVar[][] x = new IloNumVar[p.I][p.J];
            IloNumVar[] spot = c.numVarArray(p.J, 0, Double.POSITIVE_INFINITY);
            IloNumVar[] shortfall = c.numVarArray(p.I, 0, Double.POSITIVE_INFINITY);
            IloLinearNumExpr objective = c.linearNumExpr();
            for (int i = 0; i < p.I; i++) {
                IloLinearNumExpr flow = c.linearNumExpr();
                for (int j = 0; j < p.J; j++) if (p.eligible[i][j]) {
                    x[i][j] = c.numVar(0, p.q[i][j] * y[i]);
                    flow.addTerm(1, x[i][j]); objective.addTerm(p.r[i][j], x[i][j]);
                }
                c.addLe(flow, p.M[i] * y[i]);
                c.addGe(c.sum(flow, shortfall[i]), p.p[i] * y[i]);
                objective.addTerm(p.h[i], shortfall[i]);
            }
            for (int j = 0; j < p.J; j++) {
                IloLinearNumExpr demand = c.linearNumExpr(); demand.addTerm(1, spot[j]);
                for (int i = 0; i < p.I; i++) if (x[i][j] != null) demand.addTerm(1, x[i][j]);
                c.addEq(demand, d[j]); objective.addTerm(p.e[j], spot[j]);
            }
            c.addMinimize(objective);
            if (!c.solve() || c.getStatus() != IloCplex.Status.Optimal) throw new AssertionError("Primal failed");
            return c.getObjValue();
        } finally { c.end(); }
    }

    private static double transport(WassersteinBoxInput input, double[] y) throws Exception {
        IloCplex c = new IloCplex();
        try {
            c.setOut(null); c.setParam(IloCplex.Param.Threads, 1);
            IloLinearNumExpr objective = c.linearNumExpr(), distance = c.linearNumExpr();
            for (int s = 0; s < input.sampleCount(); s++) {
                IloLinearNumExpr mass = c.linearNumExpr();
                for (double[] point : candidates(input, s)) {
                    IloNumVar amount = c.numVar(0, input.probability[s]);
                    mass.addTerm(1, amount);
                    objective.addTerm(primal(input.params, y, point), amount);
                    distance.addTerm(input.distance(s, point), amount);
                }
                c.addEq(mass, input.probability[s]);
            }
            c.addLe(distance, input.radius); c.addMaximize(objective);
            if (!c.solve() || c.getStatus() != IloCplex.Status.Optimal) throw new AssertionError("Transport failed");
            return c.getObjValue();
        } finally { c.end(); }
    }
    private static void close(double actual, double expected, String label) {
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > 1e-6 * Math.max(1, Math.abs(expected)))
            throw new AssertionError(label + ": " + actual + " vs " + expected);
    }
}
