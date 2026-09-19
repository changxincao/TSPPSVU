package Test.analysis.synthetic;

import Basic.ProcurementParams;

import java.util.Arrays;

/** Generation-only regression check for the optional lane-rate perturbation. */
public final class TRBSVULocalRateBalanceSelfCheck {
    private TRBSVULocalRateBalanceSelfCheck() { }

    public static void main(String[] args) {
        double[] typicalDemand = new double[20];
        Arrays.fill(typicalDemand, 100.0);
        ProcurementParams source = TRBSVUProcurementGenerator.generate(15, typicalDemand, 37L);
        ProcurementParams old = TRBSVUSingleSampleCardinalityDiagnostic
                .persistentCarrierRates(source, 41L, 0.7, 1.3);
        ProcurementParams same = TRBSVUSingleSampleCardinalityDiagnostic
                .persistentCarrierRates(source, 41L, 0.7, 1.3, 0.9, 1.1);
        ProcurementParams varied = TRBSVUSingleSampleCardinalityDiagnostic
                .persistentCarrierRates(source, 41L, 0.7, 1.3, 0.7, 1.3);
        boolean rateChanged = false;
        require(Arrays.equals(old.e, varied.e) && Arrays.equals(old.p, varied.p),
                "Spot rates or MQC quantities changed.");
        for (int i = 0; i < source.I; i++) {
            require(Arrays.equals(old.r[i], same.r[i]) && old.h[i] == same.h[i],
                    "The original rate-generator signature changed its output.");
            require(Arrays.equals(old.q[i], varied.q[i])
                            && Arrays.equals(old.eligible[i], varied.eligible[i]),
                    "Capacity or coverage changed with lane-rate perturbation.");
            double minimum = Double.POSITIVE_INFINITY;
            for (int j = 0; j < source.J; j++) {
                if (!varied.eligible[i][j]) continue;
                minimum = Math.min(minimum, varied.r[i][j]);
                if (old.r[i][j] != varied.r[i][j]) rateChanged = true;
            }
            require(varied.h[i] == minimum, "MQC penalty is not the eligible minimum rate.");
        }
        require(rateChanged, "The lane-rate perturbation did not change any offer.");
        System.out.println("PASS local-rate perturbation: old rates reproduced; market"
                + " coverage, capacity, spot, and MQC quantity unchanged.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
