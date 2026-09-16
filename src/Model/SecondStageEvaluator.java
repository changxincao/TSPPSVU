package Model;

import Basic.ProcurementParams;
import ilog.concert.IloException;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarType;
import ilog.cplex.IloCplex;

/** Evaluates realized second-stage cost for a fixed first-stage selection. */
public final class SecondStageEvaluator {

    private SecondStageEvaluator() {
    }

    public static Result evaluate(ProcurementParams p,
                                  double[] y,
                                  double[] demand,
                                  boolean enforceDemandEquality) throws IloException {
        int I = p.I;
        int J = p.J;

        try (IloCplex cplex = new IloCplex()) {
            cplex.setOut(null);

            IloNumVar[][] x = new IloNumVar[I][J];
            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    double ub = p.eligible[i][j] ? p.q[i][j] : 0.0;
                    x[i][j] = cplex.numVar(0.0, ub, IloNumVarType.Float, "x_" + i + "_" + j);
                }
            }

            IloNumVar[] s = new IloNumVar[J];
            for (int j = 0; j < J; j++) {
                s[j] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float, "s_" + j);
            }
            IloNumVar[] u = new IloNumVar[I];
            for (int i = 0; i < I; i++) {
                u[i] = cplex.numVar(0.0, Double.POSITIVE_INFINITY, IloNumVarType.Float, "u_" + i);
            }

            IloLinearNumExpr objective = cplex.linearNumExpr();
            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (p.eligible[i][j]) objective.addTerm(p.r[i][j], x[i][j]);
                }
            }
            for (int j = 0; j < J; j++) objective.addTerm(p.e[j], s[j]);
            for (int i = 0; i < I; i++) objective.addTerm(p.h[i], u[i]);
            cplex.addMinimize(objective);

            for (int j = 0; j < J; j++) {
                IloLinearNumExpr served = cplex.linearNumExpr();
                for (int i = 0; i < I; i++) served.addTerm(1.0, x[i][j]);
                served.addTerm(1.0, s[j]);
                if (enforceDemandEquality) {
                    cplex.addEq(served, demand[j], "demand_" + j);
                } else {
                    cplex.addGe(served, demand[j], "demand_" + j);
                }
            }

            for (int i = 0; i < I; i++) {
                IloLinearNumExpr assigned = cplex.linearNumExpr();
                for (int j = 0; j < J; j++) assigned.addTerm(1.0, x[i][j]);
                double yi = y[i] > 0.5 ? 1.0 : 0.0;

                IloLinearNumExpr mqcLower = cplex.linearNumExpr(p.p[i] * yi);
                mqcLower.addTerm(-1.0, u[i]);
                cplex.addLe(mqcLower, assigned, "mqc_lower_" + i);
                cplex.addLe(assigned, p.M[i] * yi, "capacity_" + i);
            }

            if (!cplex.solve()) {
                throw new IllegalStateException("Second-stage evaluation failed: " + cplex.getCplexStatus());
            }

            Result out = new Result();
            out.objective = cplex.getObjValue();
            out.repairRemovalCost = new double[I];
            for (int i = 0; i < I; i++) {
                for (int j = 0; j < J; j++) {
                    if (p.eligible[i][j]) out.transportCost += p.r[i][j] * cplex.getValue(x[i][j]);
                    if (p.eligible[i][j]) out.repairRemovalCost[i] +=
                            (p.e[j] - p.r[i][j]) * cplex.getValue(x[i][j]);
                }
                double shortfall = cplex.getValue(u[i]);
                out.mqcShortfallQuantity += shortfall;
                out.penaltyCost += p.h[i] * shortfall;
                out.repairRemovalCost[i] -= p.h[i] * shortfall;
            }
            for (int j = 0; j < J; j++) out.spotCost += p.e[j] * cplex.getValue(s[j]);
            return out;
        }
    }

    public static final class Result {
        public double objective;
        public double transportCost;
        public double spotCost;
        public double penaltyCost;
        public double mqcShortfallQuantity;
        // Signed cost change when removing carrier i and sending its flow to spot.
        public double[] repairRemovalCost;
    }
}
