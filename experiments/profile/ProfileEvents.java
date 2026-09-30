import java.io.BufferedWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/** Streams a small explicit event projection without class-loader/type object expansion. */
class ProfileEvents {
    private static final Set<String> ALLOWED = Set.of(
        "jdk.ExecutionSample", "jdk.NativeMethodSample", "jdk.ThreadPark",
        "jdk.JavaMonitorEnter", "jdk.SocketRead", "jdk.GarbageCollection",
        "jdk.GCPhasePause", "jdk.CPULoad", "jdk.ThreadCPULoad");
    private static final long MAX_BYTES = 64L * 1024 * 1024;

    static String quote(String value) {
        var result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    static String project(RecordedEvent event) {
        String name = event.getEventType().getName();
        if (!ALLOWED.contains(name)) throw new IllegalArgumentException("Unapproved JFR event: " + name);
        var json = new StringBuilder("{\"type\":").append(quote(name)).append(",\"values\":{");
        json.append("\"startTime\":").append(quote(event.getStartTime().toString()));
        json.append(",\"duration\":").append(quote(event.getDuration().toString()));
        for (String field : new String[]{"jvmUser", "jvmSystem", "machineTotal", "user", "system"}) {
            if (event.hasField(field)) {
                double value = ((Number) event.getValue(field)).doubleValue();
                if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite CPU sample");
                json.append(',').append(quote(field)).append(':').append(value);
            }
        }
        RecordedThread thread = event.hasField("sampledThread") ? event.getThread("sampledThread") : event.getThread();
        if (thread != null) json.append(",\"thread\":{\"javaThreadId\":").append(thread.getJavaThreadId()).append('}');
        RecordedStackTrace stack = event.getStackTrace();
        if (stack != null) {
            json.append(",\"stackTrace\":{\"frames\":[");
            int count = 0;
            for (RecordedFrame frame : stack.getFrames()) {
                if (count == 24) break;
                if (count++ > 0) json.append(',');
                json.append("{\"method\":{\"type\":{\"name\":")
                    .append(quote(frame.getMethod().getType().getName()))
                    .append("},\"name\":").append(quote(frame.getMethod().getName())).append("}}");
            }
            json.append("]}");
        }
        return json.append("}}\n").toString();
    }

    static long appendBounded(Writer writer, String line, long written, long limit) throws Exception {
        long next = written + line.getBytes(StandardCharsets.UTF_8).length;
        if (next > limit) throw new IllegalStateException("Selected JFR fields exceed export bound");
        writer.write(line);
        return next;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("recording and fresh output paths required");
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        long bytes = 0, events = 0;
        try (var recording = new RecordingFile(input);
             BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                 java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            while (recording.hasMoreEvents()) {
                String line = project(recording.readEvent());
                bytes = appendBounded(writer, line, bytes, MAX_BYTES);
                events++;
            }
        }
        System.out.println("Projected " + events + " allowlisted events, " + bytes + " bytes");
    }
}
