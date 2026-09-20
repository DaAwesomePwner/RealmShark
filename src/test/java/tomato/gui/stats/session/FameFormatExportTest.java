package tomato.gui.stats.session;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import tomato.gui.stats.Fame;
import static org.junit.Assert.*;

public class FameFormatExportTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void fameJsonAndCsvAreByteIdenticalUnderUsAndGermanFormatting() throws Exception {
        Locale previous = Locale.getDefault(Locale.Category.FORMAT);
        TimeZone zone = TimeZone.getDefault();
        FameSession session = new FameSession("Exact exports");
        session.addCharacterData(12345, "Wizard", Arrays.asList(new Fame(1234.125, 1000), new Fame(-2.5, 61000)));
        File json = temp.newFile("sample.fame"), csv = temp.newFile("sample.csv");
        try (FameSessionManager.SessionWriter writer = new FameSessionManager.SessionWriter(
                (file, content) -> Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8)))) {
            byte[] firstJson = null, firstCsv = null;
            for (Locale locale : Arrays.asList(Locale.US, Locale.GERMANY)) {
                Locale.setDefault(Locale.Category.FORMAT, locale);
                TimeZone.setDefault(TimeZone.getTimeZone(locale.equals(Locale.US) ? "UTC" : "Europe/Berlin"));
                assertTrue(writer.save(FameSessionManager.snapshot(session), json, null).get(3, TimeUnit.SECONDS));
                assertTrue(FameSessionManager.exportSessionToCsv(session, csv));
                byte[] jsonBytes = Files.readAllBytes(json.toPath()), csvBytes = Files.readAllBytes(csv.toPath());
                if (firstJson == null) { firstJson = jsonBytes; firstCsv = csvBytes; }
                else { assertArrayEquals(firstJson, jsonBytes); assertArrayEquals(firstCsv, csvBytes); }
            }
            assertTrue(new String(firstCsv, StandardCharsets.UTF_8).contains("12345,1000,1234.125"));
        } finally { Locale.setDefault(Locale.Category.FORMAT, previous); TimeZone.setDefault(zone); }
    }
}
