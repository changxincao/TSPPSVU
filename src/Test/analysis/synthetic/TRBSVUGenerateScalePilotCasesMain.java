package Test.analysis.synthetic;

import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/** Generates one frozen Normal-Low timing case for each requested I x J x S size. */
public final class TRBSVUGenerateScalePilotCasesMain {
    private TRBSVUGenerateScalePilotCasesMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4)
            throw new IllegalArgumentException("Usage: <outputRoot> <IxJxS[,IxJxS...]> "
                    + "[baseSeed=20260915] [oosDraws=1000]");
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        long baseSeed = args.length > 2 ? Long.parseLong(args[2]) : 20260915L;
        int oosDraws = args.length > 3 ? Integer.parseInt(args[3]) : 1000;
        if (oosDraws < 1) throw new IllegalArgumentException("OOS draw count must be positive.");
        Files.createDirectories(root);
        String[] sizes = args[1].split(",");
        for (int index = 0; index < sizes.length; index++) {
            String[] fields = sizes[index].trim().toLowerCase().split("x");
            if (fields.length != 3) throw new IllegalArgumentException("Invalid size: " + sizes[index]);
            int carriers = Integer.parseInt(fields[0]);
            int lanes = Integer.parseInt(fields[1]);
            int samples = Integer.parseInt(fields[2]);
            if (carriers < 1 || lanes < 1 || samples < 1)
                throw new IllegalArgumentException("Non-positive size: " + sizes[index]);
            // One common seed tuple makes the two pilot sizes comparable rather than
            // confounding the dimension change with a different random market.
            SplittableRandom random = new SplittableRandom(baseSeed);
            TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                    random.nextLong(), random.nextLong(), random.nextLong(),
                    random.nextLong(), random.nextLong());
            TRBSVUSyntheticCase.Generated generated = TRBSVUSyntheticCase.generateDetailed(
                    carriers, lanes, samples, oosDraws,
                    Distribution.NORMAL, Volatility.LOW, seeds);
            String label = "I" + carriers + "_J" + lanes + "_S" + samples;
            Path directory = root.resolve(label).resolve("rep_000");
            Files.createDirectories(directory);
            Path instance = directory.resolve("instance.tsv");
            if (Files.exists(instance))
                throw new IllegalStateException("Refusing to overwrite frozen case: " + instance);
            TRBSVUSyntheticCaseIO.saveText(generated.instance(), instance);
            TRBSVUResultWriter.writeInstance(directory, generated.instance());
            TRBSVUResultWriter.writeDgpParameters(directory, generated.demandParameters(),
                    Distribution.NORMAL, Volatility.LOW);
            Files.writeString(directory.resolve("manifest.txt"),
                    "purpose=Experiment 1 scale timing pilot\n"
                            + "distribution=NORMAL\nvolatility=LOW\nreplication=0\n"
                            + "I=" + carriers + "\nJ=" + lanes + "\nS=" + samples + "\n"
                            + "OOS=" + oosDraws + "\nbaseSeed=" + baseSeed + "\n"
                            + "seedOffset=0\ndemandParameters=" + seeds.demandParameters()
                            + "\nprocurement=" + seeds.procurement() + "\ncontexts=" + seeds.contexts()
                            + "\nhistoricalNoise=" + seeds.historicalNoise()
                            + "\noosNoise=" + seeds.oosNoise() + "\n",
                    StandardCharsets.UTF_8);
            TRBSVUSyntheticCase restored = TRBSVUSyntheticCaseIO.loadText(instance);
            if (restored.params.I != carriers || restored.params.J != lanes
                    || restored.history.size() != samples || restored.oos.size() != oosDraws)
                throw new IllegalStateException("Round-trip dimension mismatch: " + label);
            System.out.println("Generated and verified " + label + ": " + instance);
        }
    }
}
