package Test.analysis.synthetic;

import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Distribution;
import Test.analysis.synthetic.TRBSVUSyntheticDemandGenerator.Volatility;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/** Generates and freezes the 20 Experiment 1/2 baseline cases; never solves a model. */
public final class TRBSVUGenerateBaselineCasesMain {
    private TRBSVUGenerateBaselineCasesMain() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3)
            throw new IllegalArgumentException("Usage: <outputDir> [baseSeed=20260915] [count=20]");
        Path root = Path.of(args[0]);
        long baseSeed = args.length > 1 ? Long.parseLong(args[1]) : 20260915L;
        int count = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        if (count < 1) throw new IllegalArgumentException("Replication count must be positive.");
        Files.createDirectories(root);
        for (int index = 0; index < count; index++) {
            Path instanceDirectory = root.resolve(String.format("rep_%03d", index)).resolve("instance");
            if (Files.exists(instanceDirectory))
                throw new IllegalStateException("Refusing to overwrite existing case: " + instanceDirectory);
            Files.createDirectories(instanceDirectory);
            SplittableRandom random = new SplittableRandom(baseSeed + index);
            TRBSVUSyntheticCase.Seeds seeds = new TRBSVUSyntheticCase.Seeds(
                    random.nextLong(), random.nextLong(), random.nextLong(),
                    random.nextLong(), random.nextLong());
            TRBSVUSyntheticCase.Generated generated = TRBSVUSyntheticCase.generateDetailed(
                    20, 60, 100, 1000, Distribution.NORMAL, Volatility.LOW, seeds);
            Path text = instanceDirectory.resolve("instance.tsv");
            TRBSVUSyntheticCaseIO.saveText(generated.instance(), text);
            TRBSVUResultWriter.writeInstance(instanceDirectory, generated.instance());
            TRBSVUResultWriter.writeDgpParameters(instanceDirectory, generated.demandParameters(),
                    Distribution.NORMAL, Volatility.LOW);
            Files.writeString(instanceDirectory.resolve("manifest.txt"),
                    "baseline=Normal-Low\nI=20\nJ=60\nH=100\nOOS=1000\n"
                            + "baseSeed=" + baseSeed + "\nreplication=" + index + "\n"
                            + "demandParameters=" + seeds.demandParameters() + "\n"
                            + "procurement=" + seeds.procurement() + "\n"
                            + "contexts=" + seeds.contexts() + "\n"
                            + "historicalNoise=" + seeds.historicalNoise() + "\n"
                            + "oosNoise=" + seeds.oosNoise() + "\n",
                    StandardCharsets.UTF_8);
            // Immediate round-trip gate: the frozen binary, not regenerated data, is the solve input.
            TRBSVUSyntheticCase restored = TRBSVUSyntheticCaseIO.loadText(text);
            if (restored.params.I != 20 || restored.params.J != 60
                    || restored.history.size() != 100 || restored.oos.size() != 1000)
                throw new IllegalStateException("Frozen-case round-trip dimension mismatch at rep " + index);
            System.out.println("Generated rep_" + String.format("%03d", index));
        }
    }
}
