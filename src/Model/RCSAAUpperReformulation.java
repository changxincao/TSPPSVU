package Model;

import Basic.ProcurementParams;
import Basic.Sample;
import java.util.ArrayList;
import java.util.List;
import mosek.fusion.*;

/** Only the z <= Q side of RCSAA_latest_exact_reformulations.pdf (15),(42).
 * Assumes nonnegative costs/demand and unlimited spot, as in current recourse.
 * No assumption h <= r. The unchanged primal master uses q*y, whereas the
 * PDF dual uses q; these agree at binary y due to total capacity M*y.
 */
final class RCSAAUpperReformulation {
    static RCSAADecompositionSupport.ScenarioCut repair(ProcurementParams p,
            double[] d, double[] center, int w, boolean equality) throws Exception {
        SecondStageEvaluator.Result r = SecondStageEvaluator.evaluate(p, center, d, equality);
        double constant = r.objective;
        double[] coef = new double[p.I];
        for (int i = 0; i < p.I; i++) {
            if (center[i] > 0.5) {
                constant += r.repairRemovalCost[i];
                coef[i] = -r.repairRemovalCost[i];
            } else coef[i] = p.h[i] * p.p[i];
        }
        return new RCSAADecompositionSupport.ScenarioCut(w, r.objective,
                constant, coef, RCSAADecompositionSupport.scenarioUpperBound(p, d));
    }

    static void addRepair(Model m, Variable y, Variable z,
            RCSAADecompositionSupport.ScenarioCut cut, String name) {
        m.constraint(name, Expr.sub(z.index(cut.scenarioIndex),
                Expr.add(cut.constantPart, Expr.dot(cut.yCoeff, y))), Domain.lessThan(0.0));
    }

    static void addCompact(Model m, Variable y, Variable z, ProcurementParams p,
            List<Sample> samples, boolean equality) {
        for (int w = 0; w < samples.size(); w++) {
            String tag = "dual_" + w + "_";
            Variable a = m.variable(tag + "a", p.J,
                    equality ? Domain.unbounded() : Domain.greaterThan(0.0));
            m.constraint(tag + "aUb", a, Domain.lessThan(p.e));
            List<Expression> obj = new ArrayList<>();
            obj.add(Expr.dot(samples.get(w).demand(), a));
            for (int i = 0; i < p.I; i++) {
                double bound = 0.0;
                for (int j = 0; j < p.J; j++) if (p.eligible[i][j])
                    bound = Math.max(bound, p.e[j] + p.h[i] - p.r[i][j]);
                Variable b = m.variable(tag + "b" + i, 1, Domain.inRange(0.0, p.h[i]));
                Variable g = m.variable(tag + "g" + i, 1, Domain.inRange(0.0, bound));
                Variable yb = product(m, tag + "yb" + i, y.index(i), b, p.h[i]);
                Variable yg = product(m, tag + "yg" + i, y.index(i), g, bound);
                obj.add(Expr.mul(p.p[i], yb.index(0)));
                obj.add(Expr.mul(-p.M[i], yg.index(0)));
                for (int j = 0; j < p.J; j++) if (p.eligible[i][j]) {
                    Variable sigma = m.variable(tag + "s" + i + "_" + j, 1, Domain.greaterThan(0.0));
                    m.constraint(tag + "feas" + i + "_" + j,
                            Expr.sub(Expr.add(a.index(j), b), Expr.add(g, sigma)),
                            Domain.lessThan(p.r[i][j]));
                    obj.add(Expr.mul(-p.q[i][j], sigma.index(0)));
                }
            }
            m.constraint(tag + "upper", Expr.sub(z.index(w),
                    Expr.add(obj.toArray(new Expression[0]))), Domain.lessThan(0.0));
        }
    }

    private static Variable product(Model m, String name, Variable y, Variable v, double upper) {
        Variable t = m.variable(name, 1, Domain.greaterThan(0.0));
        m.constraint(name + "_on", Expr.sub(t, Expr.mul(upper, y)), Domain.lessThan(0.0));
        m.constraint(name + "_ub", Expr.sub(t, v), Domain.lessThan(0.0));
        m.constraint(name + "_lb", Expr.sub(Expr.sub(t, v), Expr.mul(upper, y)),
                Domain.greaterThan(-upper));
        return t;
    }

    /** On y=1, absorb gamma into sigma; -M*gamma-sum(q*sigma)
     * is unchanged because M=sum(q). On y=0, beta=sigma=0 and alpha<=e
     * makes the switched constraint redundant. Bounds preserve a dual optimum.
     * This is not applicable to an independent carrier capacity M<sum(q).
     */
    static void addSwitched(Model m, Variable y, Variable z, ProcurementParams p,
            List<Sample> samples, boolean equality) {
        for (int i = 0; i < p.I; i++) {
            double sum = 0;
            for (int j = 0; j < p.J; j++) if (p.eligible[i][j]) sum += p.q[i][j];
            if (Math.abs(sum-p.M[i]) > 1e-9*Math.max(1.0,sum))
                throw new IllegalArgumentException("Switched dual requires M=sum eligible q");
        }
        for (int w = 0; w < samples.size(); w++) {
            String tag = "sw_" + w + "_";
            Variable a = m.variable(tag+"a", p.J,
                    equality ? Domain.unbounded() : Domain.greaterThan(0.0));
            m.constraint(tag+"aUb", a, Domain.lessThan(p.e));
            List<Expression> obj = new ArrayList<>();
            obj.add(Expr.dot(samples.get(w).demand(), a));
            for (int i = 0; i < p.I; i++) {
                Variable b = m.variable(tag+"b"+i, 1, Domain.greaterThan(0.0));
                m.constraint(tag+"bOn"+i, Expr.sub(b,Expr.mul(p.h[i],y.index(i))), Domain.lessThan(0.0));
                obj.add(Expr.mul(p.p[i], b.index(0)));
                for (int j = 0; j < p.J; j++) if (p.eligible[i][j]) {
                    double off = Math.max(0.0,p.e[j]-p.r[i][j]);
                    double ub = Math.max(0.0,p.e[j]+p.h[i]-p.r[i][j]);
                    Variable sigma = m.variable(tag+"s"+i+"_"+j,1,Domain.greaterThan(0.0));
                    m.constraint(tag+"sOn"+i+"_"+j,
                            Expr.sub(sigma,Expr.mul(ub,y.index(i))),Domain.lessThan(0.0));
                    m.constraint(tag+"feas"+i+"_"+j,
                            Expr.add(Expr.sub(Expr.add(a.index(j),b),sigma),Expr.mul(off,y.index(i))),
                            Domain.lessThan(p.r[i][j]+off));
                    obj.add(Expr.mul(-p.q[i][j],sigma.index(0)));
                }
            }
            m.constraint(tag+"upper",Expr.sub(z.index(w),Expr.add(obj.toArray(new Expression[0]))),Domain.lessThan(0.0));
        }
    }
}
