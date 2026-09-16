package Model;

/** Solver stopped without a readable incumbent; preserves reportable bounds. */
public final class SolverTerminationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final String solverStatus;
    public final double bestBound;
    public final double relativeGap;
    public final long nodeCount;

    public SolverTerminationException(String solverStatus,
                                      double bestBound,
                                      double relativeGap,
                                      long nodeCount) {
        super("Solver stopped without a feasible incumbent. status=" + solverStatus
                + ", bestBound=" + bestBound + ", relativeGap=" + relativeGap
                + ", nodes=" + nodeCount);
        this.solverStatus = solverStatus;
        this.bestBound = bestBound;
        this.relativeGap = relativeGap;
        this.nodeCount = nodeCount;
    }
}
