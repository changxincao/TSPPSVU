package Model;

/** No solver execution: checks acceptance, raw-bound retention and material failures. */
public final class RCSAABoundDiagnosticsSelfCheck {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        double upper = 1_476_515.5506431174, lower = 1_476_515.5531271286;
        check(RCSAABoundDiagnostics.certificationGap(lower, upper, 1e-4) == 0,
                "Observed roundoff must be accepted");
        check(Double.isNaN(RCSAABoundDiagnostics.certificationGap(101, 100, 1e-4)),
                "Material overlap must not become zero gap");
        check(Double.isNaN(RCSAABoundDiagnostics.certificationGap(100.00001, 100, 1e-4)),
                "Overlap above numerical cap must be rejected");
        check(RCSAABoundDiagnostics.certificationGap(100.000004, 100, 1e-4) == 0,
                "Small overlap accepted");
        check(Double.isNaN(RCSAABoundDiagnostics.certificationGap(100.000004, 100, 1e-10)),
                "Stricter requested tolerance honored");
        check(Double.isNaN(RCSAABoundDiagnostics.certificationGap(Double.NaN, 100, 1e-4)),
                "Unavailable bound stays unavailable");
        check(Double.isNaN(RCSAABoundDiagnostics.certificationGap(100, Double.POSITIVE_INFINITY, 1e-4)),
                "Nonfinite incumbent rejected");
        check(Math.abs(RCSAABoundDiagnostics.certificationGap(95, 100, 1e-4) - 0.05) < 1e-12,
                "Ordinary positive gap unchanged");
        Solution s = new Solution(upper, new double[]{1}, 1);
        s.bestBound = lower; s.certifiedOptimal = true;
        s.solverStatus = "OPTIMAL_RCSAA_SWITCHED_COMPACT:NUMERICAL_BOUND_OVERLAP_WITHIN_TOL";
        check(RCSAABoundDiagnostics.acceptedOverlap(s) && s.bestBound == lower,
                "Accepted status retains raw lower bound");
        s.certifiedOptimal = false;
        check(!RCSAABoundDiagnostics.acceptedOverlap(s), "Do not certify an incomplete master");
        s.certifiedOptimal = true; s.bestBound = upper + 1;
        check(!RCSAABoundDiagnostics.acceptedOverlap(s), "Status cannot bypass magnitude check");
        s.bestBound = lower; s.solverStatus = "TIME_LIMIT";
        check(!RCSAABoundDiagnostics.acceptedOverlap(s), "Do not promote time-limit solutions");
        System.out.println("RCSAA_BOUND_DIAGNOSTICS_SELF_CHECK_PASS");
    }
}
