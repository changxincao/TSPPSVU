package Basic;

import java.util.List;


/**
 * All parameters needed by the two-stage procurement model.
 * - lanes: indexed by j=0..J-1
 * - carriers: indexed by i=0..I-1
 */
public class ProcurementParams {
    public final List<String> carriers; // carrier names, size I
    public final int I;
    public final int J;

    // lane-level
    public final double[] e; // spot cost e_j, size J

    // carrier-level
    public final double[] p; // MQC threshold p_i, size I
    public final double[] h; // MQC penalty h_i, size I
    public final double[] M; // big-M total cap M_i, size I (derived from q and eligible)

    // carrier-lane
    public final double[][] q;         // q_ij, size I x J
    public final double[][] r;         // r_ij, size I x J
    public final boolean[][] eligible; // whether carrier i can serve lane j

    // first-stage bounds (number of selected carriers)
    public final int alpha;
    public final int beta;

    public ProcurementParams(List<String> carriers,
                             int J,
                             double[] e,
                             double[] p,
                             double[] h,
                             double[][] q,
                             double[][] r,
                             boolean[][] eligible,
                             int alpha,
                             int beta) {
        this.carriers = carriers;
        this.I = carriers.size();
        this.J = J;

        this.e = e;
        this.p = p;
        this.h = h;

        this.q = q;
        this.r = r;
        this.eligible = eligible;

        this.alpha = alpha;
        this.beta = beta;

        // derive M_i from q and eligible
        this.M = computeBigM(q, eligible);

        validate();
    }

    /** M_i = sum_j q_ij for eligible lanes. */
    private static double[] computeBigM(double[][] q, boolean[][] eligible) {
        int I = q.length;
        int J = (I == 0 ? 0 : q[0].length);
        double[] M = new double[I];
        for (int i = 0; i < I; i++) {
            double sum = 0.0;
            for (int j = 0; j < J; j++) {
                if (eligible[i][j]) sum += q[i][j];
            }
            M[i] = sum;
        }
        return M;
    }

    /** Basic dimension & sanity checks. */
    public void validate() {
    	
        if (J <= 0 || I <= 0) throw new IllegalArgumentException("I and J must be positive.");

        if (e == null || e.length != J) throw new IllegalArgumentException("e size mismatch.");
        if (p == null || p.length != I) throw new IllegalArgumentException("p size mismatch.");
        if (h == null || h.length != I) throw new IllegalArgumentException("h size mismatch.");
        if (M == null || M.length != I) throw new IllegalArgumentException("M size mismatch.");

        checkMatrix(r, I, J, "r");
        checkMatrix(q, I, J, "q");
        checkBoolMatrix(eligible, I, J, "eligible");

        if (alpha < 1 || alpha > I) throw new IllegalArgumentException("alpha out of range.");
        if (beta < 1 || beta > I) throw new IllegalArgumentException("beta out of range.");
        if (alpha > beta) throw new IllegalArgumentException("alpha cannot exceed beta.");

        for (int j = 0; j < J; j++) {
            if (e[j] < 0) throw new IllegalArgumentException("e[j] must be nonnegative.");
        }
        for (int i = 0; i < I; i++) {
            if (p[i] < 0) throw new IllegalArgumentException("p[i] must be nonnegative.");
            if (h[i] < 0) throw new IllegalArgumentException("h[i] must be nonnegative.");
            if (M[i] < 0) throw new IllegalArgumentException("M[i] must be nonnegative.");

            // optional: if a lane is not eligible, force q=0 and r=0
            for (int j = 0; j < J; j++) {
                if (!eligible[i][j]) {
                    if (q[i][j] != 0.0) q[i][j] = 0.0;
                    if (r[i][j] != 0.0) r[i][j] = Double.MAX_VALUE;
                }
            }
        }
    }

    private static void checkMatrix(double[][] a, int I, int J, String name) {
        if (a == null || a.length != I) throw new IllegalArgumentException(name + " row mismatch.");
        for (int i = 0; i < I; i++) {
            if (a[i] == null || a[i].length != J) throw new IllegalArgumentException(name + " col mismatch at row " + i);
        }
    }

    private static void checkBoolMatrix(boolean[][] a, int I, int J, String name) {
        if (a == null || a.length != I) throw new IllegalArgumentException(name + " row mismatch.");
        for (int i = 0; i < I; i++) {
            if (a[i] == null || a[i].length != J) throw new IllegalArgumentException(name + " col mismatch at row " + i);
        }
    }
}

