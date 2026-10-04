package Test.analysis.synthetic;

import Basic.CovariateVector;
import Basic.ProcurementParams;
import Basic.Sample;
import java.nio.file.Path;
import java.util.List;

/** Tiny RSOME/MOSEK deployment check, not a formal experiment. */
public final class TRBSVUMomentRuntimeSelfCheck {
    public static void main(String[] args) throws Exception {
        var params = new ProcurementParams(List.of("C0"), 1, new double[]{5},
                new double[]{0}, new double[]{1}, new double[][]{{20}},
                new double[][]{{1}}, new boolean[][]{{true}}, 1, 1);
        var history = List.of(sample(0, 10), sample(1, 12));
        var settings = new TRBSVUSolveMethods.Settings(4, 300, 1e-4,
                Model.RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true, true);
        var solver = new TRBSVUPcmSolver(TRBSVUExperiment2Runner.momentPython(),
                Path.of("analysis/trb_svu/solve_pcm.py"));
        for (boolean coupled : new boolean[]{false, true}) {
            var result = solver.solve(params, history, 1, settings, true, coupled);
            if (!result.certifiedOptimal || Math.abs(result.objValue - 11) > 0.001
                    || result.y[0] < 0.5 || result.modelVariableCount < 1
                    || result.modelConstraintCount < 1 || !Double.isFinite(result.bestBound))
                throw new AssertionError("Moment runtime fixture failed: coupled=" + coupled);
        }
        System.out.println("MOMENT_RUNTIME_SELF_CHECK_PASS");
    }

    private static Sample sample(int id, double demand) {
        var date = java.time.LocalDate.of(2020, 1, 1).plusDays(id);
        return new Sample(id, new Basic.PeriodData(id, date, date, new double[]{demand}, 0, 0, 0, 0),
                new CovariateVector(new double[]{0.5, 1, 0.5, 0.5}), 0.5);
    }
}
