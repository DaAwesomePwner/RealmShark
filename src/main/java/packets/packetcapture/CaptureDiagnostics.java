package packets.packetcapture;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

/** Bounded lifecycle diagnostics. Never accepts packet contents or account data. */
public final class CaptureDiagnostics {
    private CaptureDiagnostics() {}

    public static synchronized void record(String event, Throwable failure) {
        StringBuilder line = new StringBuilder(Instant.now().toString()).append(" ").append(event);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            line.append("\n  ").append(cause.getClass().getName());
            String missingClass = missingClassName(cause);
            if (!missingClass.isEmpty()) line.append(" [missing class: ").append(missingClass).append(']');
            for (StackTraceElement frame : cause.getStackTrace()) line.append("\n    at ").append(frame);
        }
        line.append('\n');
        try {
            Path path = Paths.get("logs", "capture-health.log");
            Files.createDirectories(path.getParent());
            if (Files.exists(path) && Files.size(path) > 262144) {
                Files.move(path, path.resolveSibling("capture-health.previous.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(path, line.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            System.err.println("[Capture] Unable to write capture health log: " + e.getClass().getSimpleName());
        }
    }

    // Only class identifiers are safe here; arbitrary exception messages may contain packet data.
    static String missingClassName(Throwable failure) {
        if (!(failure instanceof ClassNotFoundException) && !(failure instanceof NoClassDefFoundError)) return "";
        String message = failure.getMessage();
        return message != null && message.length() <= 240
                && message.matches("[A-Za-z_$][A-Za-z0-9_$]*(?:[./][A-Za-z_$][A-Za-z0-9_$]*)*")
                ? message.replace('/', '.') : "";
    }
}
