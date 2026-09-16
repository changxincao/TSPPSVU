package Test;

/**
 * Reviewer 4, Comment 30 mechanism test.
 *
 * <p>For each Olist rolling week, solves exact RCSAA and a risk-neutral CSAA
 * using the same context weights but constrained to select exactly the same
 * number of carriers as RCSAA. This isolates carrier composition from the
 * mechanical effect of selecting more carriers. The experiment uses the
 * current 100%-coverage, MQC-scale-1, h=min center setting and writes to an
 * independent directory.</p>
 */
public final class BrazilOlistR4M30MatchedCardinalityExperiment {

    private BrazilOlistR4M30MatchedCardinalityExperiment() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("trb.olist.outputRoot",
                "analysis/TRB_reviewer_revision/305_r4_m30_matched_cardinality_olist_hmin_20260814");
        System.setProperty("trb.olist.penalty", "min");
        System.clearProperty("trb.olist.normalizeCoverageCapacity");
        BrazilOlistRollingMinHCoverageMqcExperiment.main(new String[]{
                "1.0", "1.0", "RCSAA,CSAA_MATCHED_RCSAA_COUNT", "0", "51"
        });
    }
}
