package Test.analysis.synthetic;

import Basic.Sample;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Diagnostic only: conditional train/evaluation split at one fixed query. */
public final class TRBSVUOracleConditionalSplit {
    private TRBSVUOracleConditionalSplit() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: <source-directory> <output-directory>");
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        Files.createDirectories(output);
        for (int replication = 1; replication <= 3; replication++) {
            String stem = String.format("rep%02d_grouped_centered", replication);
            TRBSVUSyntheticCase original = TRBSVUSyntheticCaseIO.loadText(
                    source.resolve(stem + ".instance.tsv"));
            int split = original.oos.size() / 2;
            List<Sample> evaluation = original.oos.subList(split, original.oos.size());
            TRBSVUSyntheticCase marginal = new TRBSVUSyntheticCase(original.params,
                    original.lanes, original.history, original.testContext, evaluation, original.seeds);
            List<Sample> conditional = equalWeights(original.oos.subList(0, split));
            TRBSVUSyntheticCase oracle = new TRBSVUSyntheticCase(original.params,
                    original.lanes, conditional, original.testContext, evaluation, original.seeds);
            TRBSVUSyntheticCaseIO.saveText(marginal, output.resolve(stem + "_marginal.instance.tsv"));
            TRBSVUSyntheticCaseIO.saveText(oracle, output.resolve(stem + "_oracle.instance.tsv"));
        }
    }

    private static List<Sample> equalWeights(List<Sample> source) {
        List<Sample> result = new ArrayList<>(source.size());
        for (Sample sample : source) {
            result.add(new Sample(sample.id, sample.period, sample.theta.copy(), 1.0 / source.size()));
        }
        return result;
    }
}
