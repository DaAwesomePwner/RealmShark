package tomato.gui.dps;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import tomato.backend.data.DpsData;

/** Import provenance is local file evidence, never an inferred gameplay/session join. */
public final class EncounterImport {
    public final DpsData data;
    public final String fileName, fingerprint;
    public final long importedAt;
    final EncounterSummary summary;
    private EncounterImport(DpsData data, String fileName, String fingerprint, long importedAt) {
        this.data = data; this.fileName = fileName; this.fingerprint = fingerprint; this.importedAt = importedAt;
        summary = new EncounterSummary(data);
    }
    public static EncounterImport read(Path file) throws IOException, ClassNotFoundException {
        MessageDigest sha;
        try { sha = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        DpsData data;
        try (DigestInputStream bytes = new DigestInputStream(new BufferedInputStream(Files.newInputStream(file)), sha);
             ObjectInputStream input = new ObjectInputStream(bytes)) {
            Object value = input.readObject();
            if (!(value instanceof DpsData)) throw new IOException("This file is not a DPS encounter.");
            data = (DpsData)value;
            if (data.hitList == null || data.deathNotifications == null) throw new IOException("The encounter is incomplete.");
            byte[] rest = new byte[8192]; while (bytes.read(rest) != -1) { /* Include all original bytes in duplicate detection. */ }
        }
        StringBuilder digest = new StringBuilder(); for (byte b : sha.digest()) digest.append(String.format("%02x", b));
        return new EncounterImport(data, file.getFileName().toString(), digest.toString(), System.currentTimeMillis());
    }
}
