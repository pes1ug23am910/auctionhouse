import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.StackTrace;

/** Actual RecordingFile regression fixtures, invoked by the Python contracts. */
class ExporterProbe {
    @Name("jdk.CPULoad") @StackTrace(false)
    static class Allowed extends Event {
        float jvmUser = .2f;
        String unrelatedPrivateField = "fixture-value-must-not-appear";
    }
    @Name("fixture.Unapproved") @StackTrace(false)
    static class Unapproved extends Event { String value = "fixture-value-must-not-appear"; }

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]);
        switch (args[0]) {
            case "privacy" -> {
                Path recording = directory.resolve("privacy.jfr"), output = directory.resolve("privacy.jsonl");
                try (Recording r = new Recording()) {
                    r.enable(Allowed.class);
                    r.start();
                    new Allowed().commit();
                    r.stop();
                    r.dump(recording);
                }
                ProfileEvents.main(new String[]{recording.toString(), output.toString()});
                String text = Files.readString(output);
                if (!text.contains("jvmUser") || text.contains("fixture-value-must-not-appear")
                    || text.contains("unrelatedPrivateField") || text.contains("classLoader")) throw new AssertionError("Projection privacy failure");
            }
            case "unapproved" -> {
                Path recording = directory.resolve("unapproved.jfr"), output = directory.resolve("unapproved.jsonl");
                try (Recording r = new Recording()) {
                    r.enable(Unapproved.class);
                    r.start();
                    new Unapproved().commit();
                    r.stop();
                    r.dump(recording);
                }
                try {
                    ProfileEvents.main(new String[]{recording.toString(), output.toString()});
                    throw new AssertionError("Unapproved event was exported");
                } catch (IllegalArgumentException expected) {
                    if (!Files.readString(output).isEmpty()) throw new AssertionError("Rejected payload was written");
                }
            }
            case "size" -> {
                StringWriter writer = new StringWriter();
                long bytes = ProfileEvents.appendBounded(writer, "é", 0, 3);
                if (bytes != 2) throw new AssertionError("Bound must count UTF-8 bytes");
                try {
                    ProfileEvents.appendBounded(writer, "é", bytes, 3);
                    throw new AssertionError("Size limit was ignored");
                } catch (IllegalStateException expected) {
                    if (!writer.toString().equals("é")) throw new AssertionError("Overflow was partially written");
                }
            }
            default -> throw new IllegalArgumentException("Unknown fixture");
        }
    }
}
