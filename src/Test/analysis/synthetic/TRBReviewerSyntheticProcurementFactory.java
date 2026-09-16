package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Helper.basicHelper.Config;
import Helper.basicHelper.InstanceGenerator;

/**
 * Procurement-parameter factory for R3-4 and R4-M33--M35 scale experiments.
 *
 * <p><strong>Input.</strong> The generated demand baseline, carrier count and
 * a frozen random seed. <strong>Operation.</strong> Per-carrier lane capacity
 * and MQC factors are multiplied by {@code referenceCarrierCount / I}. This
 * keeps aggregate market capacity and MQC tightness approximately comparable
 * when {@code I} changes, rather than making a 30-carrier instance mechanically
 * three times looser than a 10-carrier instance. <strong>Output.</strong> The
 * ordinary {@link ProcurementParams} consumed by the current solvers. This
 * class generates data only and does not run an optimization model.</p>
 */
public final class TRBReviewerSyntheticProcurementFactory {

    public static final int DEFAULT_REFERENCE_CARRIER_COUNT = 10;

    private TRBReviewerSyntheticProcurementFactory() {
    }

    /**
     * Generates a full-coverage, scale-comparable procurement instance using
     * the project's existing pricing and spot-cost rules.
     */
    public static GeneratedProcurement generateScaleComparable(double[] baselineDemand,
                                                               int carrierCount,
                                                               long procurementSeed) {
        return generateScaleComparable(
                baselineDemand,
                carrierCount,
                DEFAULT_REFERENCE_CARRIER_COUNT,
                procurementSeed);
    }

    public static GeneratedProcurement generateScaleComparable(double[] baselineDemand,
                                                               int carrierCount,
                                                               int referenceCarrierCount,
                                                               long procurementSeed) {
        if (baselineDemand == null || baselineDemand.length == 0) {
            throw new IllegalArgumentException("baselineDemand is required.");
        }
        if (carrierCount <= 0 || referenceCarrierCount <= 0) {
            throw new IllegalArgumentException("Carrier counts must be positive.");
        }

        double scale = (double) referenceCarrierCount / carrierCount;
        InstanceGenerator.GenConfig generation = new InstanceGenerator.GenConfig();
        generation.coverAllLanes = true;
        generation.capacityFactorLow = 0.30 * scale;
        generation.capacityFactorHigh = 0.50 * scale;
        generation.mqcLow = 0.10 * scale;
        generation.mqcHigh = 0.20 * scale;

        Config instanceConfig = new Config();
        instanceConfig.seed = Math.toIntExact(procurementSeed);
        instanceConfig.enforceDemandEquality = true;
        ProcurementParams params = InstanceGenerator.generate(
                carrierCount, baselineDemand, generation, instanceConfig);
        return new GeneratedProcurement(params, generation, instanceConfig, scale);
    }

    /** Generated parameters plus the exact settings that created them. */
    public static final class GeneratedProcurement {
        public final ProcurementParams params;
        public final InstanceGenerator.GenConfig generationConfig;
        public final Config instanceConfig;
        public final double carrierScaleFactor;

        private GeneratedProcurement(ProcurementParams params,
                                     InstanceGenerator.GenConfig generationConfig,
                                     Config instanceConfig,
                                     double carrierScaleFactor) {
            this.params = params;
            this.generationConfig = generationConfig;
            this.instanceConfig = instanceConfig;
            this.carrierScaleFactor = carrierScaleFactor;
        }
    }
}
