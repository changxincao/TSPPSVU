package Test.analysis.synthetic;

import Basic.ProcurementParams;
import Basic.Sample;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Parameters;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Replication;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.SplittableRandom;

/** Generates the six paired DGP cells for Experiment 3; it never solves a model. */
public final class TRBSVUGenerateExperiment3CasesMain {
    private TRBSVUGenerateExperiment3CasesMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4)
            throw new IllegalArgumentException("Usage: <baselineRoot> <outputRoot> "
                    + "[baseSeed=20260915] [replications=10]");
        Path baselineRoot = Path.of(args[0]).toAbsolutePath().normalize();
        Path outputRoot = Path.of(args[1]).toAbsolutePath().normalize();
        long baseSeed = args.length > 2 ? Long.parseLong(args[2]) : 20260915L;
        int replications = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        if (replications < 1) throw new IllegalArgumentException("Replication count must be positive.");
        Files.createDirectories(outputRoot);

        List<String> index = new ArrayList<>();
        index.add("replication,distribution,volatility,baseline_reused,instance_sha256,"
                + "demand_parameter_seed,procurement_seed,context_seed,history_noise_seed,oos_noise_seed,path");
        int generatedCount = 0;
        for (int indexNumber = 0; indexNumber < replications; indexNumber++) {
            SplittableRandom random = new SplittableRandom(baseSeed + indexNumber);
            TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                    random.nextLong(), random.nextLong(), random.nextLong(),
                    random.nextLong(), random.nextLong());
            Parameters parameters = TRBSVUSyntheticDemandGenerator.sampleParameters(
                    60, 100, seeds.demandParameters());
            ProcurementParams market = TRBSVUProcurementGenerator.generate(
                    20, parameters.typicalDemand(), seeds.procurement());
            List<String> lanes = laneNames(60);
            TRBSVUSyntheticCase reference = null;

            for (Distribution distribution : new Distribution[]{Distribution.NORMAL, Distribution.LOGNORMAL}) {
                for (Volatility volatility : new Volatility[]{
                        Volatility.LOW, Volatility.MEDIUM, Volatility.HIGH}) {
                    Replication demand = TRBSVUSyntheticDemandGenerator.generate(parameters,
                            distribution, volatility, 1000, seeds.contexts(),
                            seeds.historicalNoise(), seeds.oosNoise());
                    TRBSVUSyntheticCase expected = new TRBSVUSyntheticCase(market, lanes,
                            demand.history, demand.testContext, demand.oos, seeds);
                    if (reference == null) reference = expected;
                    else verifyPaired(reference, expected);

                    String cell = distribution.name().toLowerCase() + "_"
                            + volatility.name().toLowerCase();
                    Path directory = outputRoot.resolve(cell)
                            .resolve(String.format("rep_%03d", indexNumber));
                    Files.createDirectories(directory);
                    Path target = directory.resolve("instance.tsv");
                    boolean baseline = distribution == Distribution.NORMAL
                            && volatility == Volatility.LOW;
                    if (baseline) {
                        Path source = baselineRoot.resolve(String.format("rep_%03d", indexNumber))
                                .resolve("instance").resolve("instance.tsv");
                        if (!Files.isRegularFile(source))
                            throw new IllegalStateException("Missing Experiment 1 baseline: " + source);
                        verifyExpectedFile(expected, source);
                        writeOrVerifyCopy(source, target);
                    } else {
                        writeOrVerify(expected, target);
                    }
                    TRBSVUResultWriter.writeDgpParameters(directory, parameters,
                            distribution, volatility);
                    String hash = sha256(target);
                    writeAtomically(directory.resolve("manifest.txt"),
                            "experiment=3\nreplication=" + indexNumber + "\n"
                                    + "distribution=" + distribution + "\nvolatility=" + volatility + "\n"
                                    + "I=20\nJ=60\nH=100\nOOS=1000\nbaseSeed=" + baseSeed + "\n"
                                    + "demandParameters=" + seeds.demandParameters() + "\n"
                                    + "procurement=" + seeds.procurement() + "\ncontexts=" + seeds.contexts() + "\n"
                                    + "historicalNoise=" + seeds.historicalNoise() + "\n"
                                    + "oosNoise=" + seeds.oosNoise() + "\n"
                                    + "pairedMarketAndContexts=true\nbaselineReused=" + baseline + "\n"
                                    + "instanceSha256=" + hash + "\n");
                    index.add(indexNumber + "," + distribution + "," + volatility + ","
                            + baseline + "," + hash + "," + seeds.demandParameters() + ","
                            + seeds.procurement() + "," + seeds.contexts() + ","
                            + seeds.historicalNoise() + "," + seeds.oosNoise() + ","
                            + outputRoot.relativize(target).toString().replace('\\', '/'));
                    generatedCount++;
                }
            }
        }
        writeAtomically(outputRoot.resolve("experiment3_cases.csv"),
                String.join(System.lineSeparator(), index) + System.lineSeparator());
        writeAtomically(outputRoot.resolve("README.txt"),
                "Experiment 3 frozen data only; no method result is contained here.\n"
                        + "Cells=Normal/Lognormal x Low/Medium/High; paired replications="
                        + replications + "; cases=" + generatedCount + ".\n"
                        + "Normal-Low is byte-identical to Experiment 1 baseline replications.\n"
                        + "Within each replication all six cells share DGP coefficients, procurement market, "
                        + "historical contexts and final test context.\n");
        System.out.println("EXPERIMENT3_DATA_PASS cases=" + generatedCount
                + " replications=" + replications + " root=" + outputRoot);
    }

    private static List<String> laneNames(int count) {
        List<String> names = new ArrayList<>(count);
        for (int j = 0; j < count; j++) names.add("L" + (j + 1));
        return names;
    }

    private static void verifyPaired(TRBSVUSyntheticCase reference,
                                     TRBSVUSyntheticCase candidate) {
        ProcurementParams a = reference.params, b = candidate.params;
        if (!a.carriers.equals(b.carriers) || a.I != b.I || a.J != b.J
                || a.alpha != b.alpha || a.beta != b.beta
                || !Arrays.equals(a.e, b.e) || !Arrays.equals(a.p, b.p)
                || !Arrays.equals(a.h, b.h) || !Arrays.equals(a.M, b.M)
                || !Arrays.deepEquals(a.q, b.q) || !Arrays.deepEquals(a.r, b.r)
                || !Arrays.deepEquals(a.eligible, b.eligible))
            throw new IllegalStateException("Experiment 3 cells do not share the procurement market.");
        if (!Arrays.equals(reference.testContext.values(), candidate.testContext.values())
                || reference.history.size() != candidate.history.size())
            throw new IllegalStateException("Experiment 3 cells do not share the context path.");
        for (int t = 0; t < reference.history.size(); t++) {
            Sample left = reference.history.get(t), right = candidate.history.get(t);
            if (!Arrays.equals(left.theta.values(), right.theta.values()))
                throw new IllegalStateException("Context-path mismatch at period " + t);
        }
    }

    private static void verifyExpectedFile(TRBSVUSyntheticCase expected, Path file) throws Exception {
        Path temporary = Files.createTempFile("trb_svu_exp3_expected_", ".tsv");
        Files.delete(temporary);
        try {
            TRBSVUSyntheticCaseIO.saveText(expected, temporary);
            if (Files.mismatch(temporary, file) != -1)
                throw new IllegalStateException("Normal-Low no longer matches frozen baseline: " + file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeOrVerify(TRBSVUSyntheticCase expected, Path target) throws Exception {
        if (!Files.exists(target)) {
            TRBSVUSyntheticCaseIO.saveText(expected, target);
            return;
        }
        verifyExpectedFile(expected, target);
    }

    private static void writeOrVerifyCopy(Path source, Path target) throws Exception {
        if (!Files.exists(target)) Files.copy(source, target);
        else if (Files.mismatch(source, target) != -1)
            throw new IllegalStateException("Existing Experiment 3 baseline copy differs: " + target);
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(file)));
    }

    private static void writeAtomically(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "pending_", ".txt");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
