package tomato.gui.security;

import com.google.gson.*;
import org.junit.Test;
import packets.data.StatData;
import packets.data.enums.StatType;
import tomato.backend.data.Entity;
import tomato.backend.data.InspectSnapshot;
import static org.junit.Assert.*;

public class InspectEvidenceTest {
    @Test public void absentModeAndEquipmentStayUnknownThroughInheritedExportAndPartialStats() {
        Entity entity = new Entity(null, 4, 0); entity.markPlayerIdentity();
        entity.baseStats = new int[]{0, -1, -1, -1, -1, -1, -1, -1};
        Player player = new Player(entity);
        assertTrue(Player.modeDescription(entity).contains("Seasonal: Not captured"));
        assertEquals(1, player.capturedStatCount());
        assertEquals(-1, player.statsMaxed());
        assertTrue(player.statsDescription().contains("HP: 0"));
        JsonObject json = JsonParser.parseString(player.toString()).getAsJsonObject();
        assertTrue(json.get("seasonal").isJsonNull());
        assertTrue(json.get("crucible").isJsonNull());
        assertTrue(json.getAsJsonObject("equipment").get("weaponid").isJsonNull());
        StatData seasonal = new StatData(); seasonal.statValue = 0; entity.stat.set(StatType.SEASONAL, seasonal);
        StatData crucible = new StatData(); crucible.stringStatValue = ""; entity.stat.set(StatType.CRUCIBLE_STAT, crucible);
        StatData empty = new StatData(); empty.statValue = -1; entity.stat.set(StatType.INVENTORY_0_STAT, empty); player.updateInv();
        json = JsonParser.parseString(player.toString()).getAsJsonObject();
        assertFalse(json.get("seasonal").getAsBoolean()); assertFalse(json.get("crucible").getAsBoolean());
        assertEquals(-1, json.getAsJsonObject("equipment").get("weaponid").getAsInt());
        SecurityFilter requirement = new SecurityFilter(); requirement.statMaxed[0] = true;
        assertTrue(requirement.parsePlayer(player).isUnderReqs);
    }

    @Test public void detachedInspectionCannotClaimItsConstructionTimeAsHistoricalCaptureTime() {
        Entity source = new Entity(null, 8, 0); source.markPlayerIdentity();
        InspectSnapshot recorded = new InspectSnapshot(source, 1234);
        String text = ParsePanelGUI.detachedDetails(recorded);
        assertTrue(text.contains("Recorded build/change time: Not captured"));
        assertTrue(text.contains("Source session/run not supplied"));
        assertTrue(text.contains("Not captured"));
        InspectSnapshot restored = new Gson().fromJson(new Gson().toJson(recorded), InspectSnapshot.class);
        assertEquals(1234, restored.observedAt());
        JsonObject legacy = JsonParser.parseString(new Gson().toJson(recorded)).getAsJsonObject();
        legacy.remove("observedAt");
        assertEquals(0, new Gson().fromJson(legacy, InspectSnapshot.class).observedAt());
    }
}
