package Model;

/** Numerical diagnostics only; never changes the stored incumbent or lower bound. */
public final class RCSAABoundDiagnostics {
    private static final double MAX_RELATIVE_OVERLAP = 5e-8;

    private RCSAABoundDiagnostics() { }

    public static boolean boundsConsistent(double lower, double upper, double tolerance) {
        if (!Double.isFinite(lower) || !Double.isFinite(upper)) return false;
        if (lower <= upper) return true;
        return lower - upper <= Math.min(MAX_RELATIVE_OVERLAP, Math.max(0, tolerance))
                * Math.max(1, Math.abs(upper));
    }

    public static double certificationGap(double lower, double upper, double tolerance) {
        if (!boundsConsistent(lower, upper, tolerance)) return Double.NaN;
        return Math.max(0, upper - lower) / Math.max(1e-12, Math.abs(upper));
    }

    public static boolean acceptedOverlap(Solution solution) {
        return solution.certifiedOptimal && solution.solverStatus != null
                && solution.solverStatus.startsWith("OPTIMAL_RCSAA_")
                && solution.solverStatus.contains("NUMERICAL_BOUND_OVERLAP_WITHIN_TOL")
                && solution.bestBound > solution.objValue
                && boundsConsistent(solution.bestBound, solution.objValue, MAX_RELATIVE_OVERLAP);
    }
}
