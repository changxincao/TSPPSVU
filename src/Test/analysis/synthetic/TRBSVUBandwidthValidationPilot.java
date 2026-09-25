package Test.analysis.synthetic;

import Model.RCSAASolverVariant;
import Test.analysis.synthetic.TRBSVUExperiment1Runner.KernelValidation;
import Test.analysis.synthetic.TRBSVUScenarioWeights.Kernel;
import Test.analysis.synthetic.TRBSVUSolveMethods.Settings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

/** Validation-only pilot for bandwidth-grid refinement; never runs final solves or OOS evaluation. */
public final class TRBSVUBandwidthValidationPilot {
    private TRBSVUBandwidthValidationPilot() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 6)
            throw new IllegalArgumentException("Usage: <instance.tsv> <output.tsv> <kernel> "
                    + "<comma-separated-B> <solverThreads> <limitSeconds>");
        Path instanceFile = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Kernel kernel = Kernel.valueOf(args[2].trim().toUpperCase(Locale.ROOT));
        double[] candidates = Arrays.stream(args[3].split(","))
                .map(String::trim).mapToDouble(Double::parseDouble).toArray();
        int threads = Integer.parseInt(args[4]);
        int limitSeconds = Integer.parseInt(args[5]);
        TRBSVUSyntheticCase instance = TRBSVUSyntheticCaseIO.loadText(instanceFile);
        Settings settings = new Settings(threads, limitSeconds, 1e-4,
                RCSAASolverVariant.LBBD_PRIMAL_EXACT, false, true);
        TRBSVUExperiment1Runner runner = new TRBSVUExperiment1Runner(settings,
                (training, query, seed, minLeaf) -> {
                    throw new UnsupportedOperationException("RF is not used by the bandwidth pilot.");
                }, TRBSVUFormalProtocol.VALIDATION_ORIGINS);
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        if (!Files.exists(output))
            Files.writeString(output, "kernel\tbandwidth\tvalid\tvalidation_mean\tvalidation_sd\torigins\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        for (double candidate : candidates) {
            if (alreadyRecorded(output, kernel, candidate)) {
                System.out.printf(Locale.ROOT, "SKIP kernel=%s B=%.17g already recorded%n", kernel, candidate);
                continue;
            }
            KernelValidation result = runner.validateKernelBandwidth(instance, kernel, candidate);
            String row = String.format(Locale.ROOT, "%s\t%.17g\t%s\t%.17g\t%.17g\t%d%n",
                    kernel, candidate, result.valid(), result.mean(),
                    result.sampleStandardDeviation(), result.details().size());
            Files.writeString(output, row, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.print("RESULT " + row);
        }
    }

    private static boolean alreadyRecorded(Path output, Kernel kernel, double candidate) throws Exception {
        if (!Files.exists(output)) return false;
        for (String line : Files.readAllLines(output, StandardCharsets.UTF_8)) {
            String[] fields = line.split("\\t", -1);
            if (fields.length >= 2 && fields[0].equals(kernel.name())) {
                try {
                    if (Double.compare(Double.parseDouble(fields[1]), candidate) == 0) return true;
                } catch (NumberFormatException ignored) {
                    // Header row.
                }
            }
        }
        return false;
    }
}
