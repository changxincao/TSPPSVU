package Test.analysis.synthetic;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** No-solver check for completion-marker parsing by the staging adapter. */
public final class TRBSVURcsaaStageMetadataSelfCheck {
    public static void main(String[] args) throws Exception {
        var reader = TRBSVURcsaaStagedGridMain.class.getDeclaredMethod("metadata", Path.class);
        reader.setAccessible(true);
        Path fixture = Files.createTempFile("rcsaa-stage-metadata-", ".txt");
        try {
            Files.writeString(fixture, "protocol=abc\nqueryCount=40\nincompleteQueries=\n\nartifactManifestSha256=def\n");
            @SuppressWarnings("unchecked")
            var values = (Map<String, String>) reader.invoke(null, fixture);
            if (!"abc".equals(values.get("protocol")) || !"def".equals(values.get("artifactManifestSha256"))
                    || !"".equals(values.get("incompleteQueries")))
                throw new AssertionError("Completion-marker fields changed");
            for (String invalid : new String[]{"protocol=abc\nprotocol=def\n", "protocol=abc\nbroken\n"}) {
                Files.writeString(fixture, invalid);
                try {
                    reader.invoke(null, fixture);
                    throw new AssertionError("Invalid metadata accepted");
                } catch (InvocationTargetException expected) {
                    if (!(expected.getCause() instanceof IllegalStateException)) throw expected;
                }
            }
            System.out.println("PASS: blank lines accepted; empty values retained; duplicate/malformed fields rejected.");
        } finally {
            Files.deleteIfExists(fixture);
        }
    }
}
