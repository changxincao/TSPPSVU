package Test;

import Model.RCSAASolverVariant;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reviewer 2, Comment 3: complete equality rerun through the existing Table-2
 * rolling pipeline. The data construction, 35/15 nested validation, parameter
 * grids and output code are unchanged; only constraint (6) is switched to
 * equality. The regularized model defaults to the manuscript's DRO
 * approximation; setting JVM property {@code trb.reviewer.equalitySolverVariant}
 * switches only that model while retaining the identical outer pipeline.
 */
public final class TRBReviewerConstraint6EqualityFullExperiment {

    private TRBReviewerConstraint6EqualityFullExperiment() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> old = new LinkedHashMap<>();
        RCSAASolverVariant variant = RCSAASolverVariant.valueOf(System.getProperty(
                "trb.reviewer.equalitySolverVariant", RCSAASolverVariant.DRO_EXTENSIVE.name()));
        set(old, "trb.reviewer.enforceDemandEquality", "true");
        set(old, "trb.reviewer.includeBaselines", "true");
        set(old, "trb.reviewer.rcsaaSolverVariant", variant.name());
        set(old, "trb.reviewer.rcsaaResultLabel",
                variant == RCSAASolverVariant.DRO_EXTENSIVE ? "DRO" : "RCSAA");
        try {
            BrazilOlistAdaptiveCVSolveComparison.main(args);
        } finally {
            for (Map.Entry<String, String> entry : old.entrySet()) {
                if (entry.getValue() == null) {
                    System.clearProperty(entry.getKey());
                } else {
                    System.setProperty(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    private static void set(Map<String, String> old, String key, String value) {
        old.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }
}
