package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Helper.basicHelper.InstanceGenerator;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.ReplicationData;
import Test.analysis.synthetic.TRBReviewerR3M3IndependentPathDemandGenerator.Settings;
import Test.analysis.synthetic.TRBReviewerSyntheticProcurementFactory.GeneratedProcurement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reviewer 3, Comment 3: creates one reusable multi-DGP experiment instance.
 *
 * <p>Input: DGP settings, carrier count and a procurement seed. Operation:
 * generate demand paths and scale-comparable concrete procurement parameters
 * once, then persist both through
 * {@link TRBReviewerR3M3SyntheticInstanceIO}. Output: an inspectable directory
 * that later experiment runners can load without redrawing data. No optimization
 * solver is called here.</p>
 */
public final class TRBReviewerR3M3SyntheticInstanceBuilder {
    private TRBReviewerR3M3SyntheticInstanceBuilder() {
    }

    /** Builds and saves one complete instance; the target directory must be empty. */
    public static void buildAndSave(Path outputDirectory,
                                    Settings demandSettings,
                                    int carrierCount,
                                    long procurementSeed) throws IOException {
        if (carrierCount <= 0) throw new IllegalArgumentException("carrierCount must be positive.");
        if (demandSettings == null) throw new IllegalArgumentException("demandSettings is required.");

        Settings frozenSettings = demandSettings.copy();
        ReplicationData demand = TRBReviewerR3M3IndependentPathDemandGenerator.generate(frozenSettings);
        GeneratedProcurement procurement =
                TRBReviewerSyntheticProcurementFactory.generateScaleComparable(
                        demand.baselineDemand, carrierCount, procurementSeed);
        ProcurementParams params = procurement.params;
        TRBReviewerR3M3SyntheticInstanceIO.save(outputDirectory, frozenSettings, demand, params);
        writeProcurementGenerationMetadata(
                outputDirectory.resolve("procurement_generation.properties"),
                procurementSeed,
                carrierCount,
                procurement);
    }

    /**
     * Records the generator seed and all adjustable procurement ranges. The
     * concrete matrices are already stored by the instance IO; this additional
     * file answers R3-5/R4-M38 provenance and permits an independent redraw.
     */
    private static void writeProcurementGenerationMetadata(Path path,
                                                           long procurementSeed,
                                                           int carrierCount,
                                                           GeneratedProcurement generated) throws IOException {
        InstanceGenerator.GenConfig g = generated.generationConfig;
        List<String> lines = new ArrayList<>();
        lines.add("# Reviewer R3-5/R4-M38 procurement-generation provenance");
        lines.add("factory=TRBReviewerSyntheticProcurementFactory.generateScaleComparable");
        lines.add("procurementSeed=" + procurementSeed);
        lines.add("carrierCount=" + carrierCount);
        lines.add("referenceCarrierCount="
                + TRBReviewerSyntheticProcurementFactory.DEFAULT_REFERENCE_CARRIER_COUNT);
        lines.add("carrierScaleFactor=" + Double.toString(generated.carrierScaleFactor));
        lines.add("coverAllLanes=" + g.coverAllLanes);
        lines.add("rBarLow=" + Double.toString(g.rBarLow));
        lines.add("rBarHigh=" + Double.toString(g.rBarHigh));
        lines.add("tauLow=" + Double.toString(g.tauLow));
        lines.add("tauHigh=" + Double.toString(g.tauHigh));
        lines.add("spotMultLow=" + Double.toString(g.spotMultLow));
        lines.add("spotMultHigh=" + Double.toString(g.spotMultHigh));
        lines.add("mqcLow=" + Double.toString(g.mqcLow));
        lines.add("mqcHigh=" + Double.toString(g.mqcHigh));
        lines.add("capacityFactorLow=" + Double.toString(g.capacityFactorLow));
        lines.add("capacityFactorHigh=" + Double.toString(g.capacityFactorHigh));
        lines.add("alphaRatio=" + Double.toString(g.alphaRatio));
        lines.add("betaRatio=" + Double.toString(g.betaRatio));
        lines.add("priceFactorLow=" + Double.toString(g.priceFactorLow));
        lines.add("priceFactorMid=" + Double.toString(g.priceFactorMid));
        lines.add("priceFactorHigh=" + Double.toString(g.priceFactorHigh));
        Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }

    /**
     * Command-line convenience entry point.
     *
     * <p>Usage: {@code <output-directory> [replication-seed] [carrier-count]
     * [procurement-seed]}. Defaults are replication seed 1, 10 carriers and
     * procurement seed 20260808. The remaining DGP and procurement parameters
     * use the documented class defaults.</p>
     */
    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Usage: <output-directory> [replication-seed] [carrier-count] [procurement-seed]");
        }
        Settings settings = new Settings();
        if (args.length >= 2) settings.replicationSeed = Long.parseLong(args[1]);
        int carrierCount = args.length >= 3 ? Integer.parseInt(args[2]) : 10;
        long procurementSeed = args.length >= 4 ? Long.parseLong(args[3]) : 20260808L;

        Path outputDirectory = Path.of(args[0]).toAbsolutePath().normalize();
        buildAndSave(outputDirectory, settings, carrierCount, procurementSeed);
        System.out.println("SYNTHETIC_INSTANCE_SAVED " + outputDirectory);
    }
}
