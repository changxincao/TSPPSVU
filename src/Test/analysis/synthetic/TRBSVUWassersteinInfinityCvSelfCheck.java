package Test.analysis.synthetic;

import java.util.Arrays;

/** No solver calls: candidate selection and the CV/final scheduling contract. */
public final class TRBSVUWassersteinInfinityCvSelfCheck {
    public static void main(String[] args) {
        double[] means = new double[TRBSVUWassersteinInfinityCvMain.RADII.length];
        double[] sds = means.clone();
        Arrays.fill(means, 10); Arrays.fill(sds, 2);
        require(TRBSVUWassersteinInfinityCvMain.selectRadius(means, sds) == .0001, "Exact tie must keep smallest");
        sds[3] = 1;
        require(TRBSVUWassersteinInfinityCvMain.selectRadius(means, sds) == .001, "SD tie-break");
        means[4] = 9;
        require(TRBSVUWassersteinInfinityCvMain.selectRadius(means, sds) == .0025, "Mean first");
        means[0] = Double.NaN;
        boolean rejected = false;
        try { TRBSVUWassersteinInfinityCvMain.selectRadius(means, sds); }
        catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "Incomplete candidates must not trigger final queries");
        for (int t = 50; t < 75; t++) require(t-50 >= 0 && t-1 < t, "Rolling window excludes realized demand");
        require(Arrays.equals(TRBSVUWassersteinInfinityCvMain.SMALL_RADII,
                new double[]{.0001, .00025, .0005, .001, .0025, .005}), "Small grid changed");
        if (TRBSVUWassersteinInfinityCvMain.EXTENDED) {
            boolean capped = TRBSVUWassersteinInfinityCvMain.CAPPED;
            means[0] = 10; means[means.length - 1] = 8;
            require(TRBSVUWassersteinInfinityCvMain.selectRadius(means, sds) == (capped ? .1 : .5),
                    "Extended candidate selection");
            require(TRBSVUWassersteinInfinityCvMain.RADII.length == (capped ? 10 : 12), "Extended grid size");
            if (capped) for (double radius : TRBSVUWassersteinInfinityCvMain.RADII)
                require(radius <= .1, "Cancelled radius reintroduced");
        }
        System.out.println("LINF_CV_SELF_CHECK_PASS: mean/SD/smaller-radius selection; incomplete-score rejection; rolling origins; radii="
                + TRBSVUWassersteinInfinityCvMain.RADII.length);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
