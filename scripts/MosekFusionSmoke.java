import mosek.fusion.Domain;
import mosek.fusion.Expr;
import mosek.fusion.Model;
import mosek.fusion.ObjectiveSense;

/** Tiny native/Java/license check; does not use any experimental data. */
public final class MosekFusionSmoke {
    public static void main(String[] args) throws Exception {
        try (Model model = new Model("deployment-license-smoke")) {
            var x = model.variable("x", 1, Domain.integral(Domain.greaterThan(1.0)));
            var t = model.variable("t", 1, Domain.greaterThan(0.0));
            model.constraint(Expr.vstack(t, x), Domain.inQCone());
            model.objective(ObjectiveSense.Minimize, Expr.sum(t));
            model.setSolverParam("numThreads", 1);
            model.solve();
            double objective = model.primalObjValue();
            if (!Double.isFinite(objective) || Math.abs(objective - 1.0) > 1e-6)
                throw new IllegalStateException("Unexpected smoke objective: " + objective);
            System.out.println("MOSEK_FUSION_MISOCP_NATIVE_LICENSE_PASS objective=" + objective);
        }
    }
}
