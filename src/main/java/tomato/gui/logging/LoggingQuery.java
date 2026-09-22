package tomato.gui.logging;

import packets.data.enums.StatType;
import packets.packetcapture.logger.DiscoveryCatalog;
import packets.packetcapture.logger.DiscoveryLog;
import java.util.*;

/** Predicates over retained diagnostic evidence, never a query over saved activity history. */
final class LoggingQuery {
    String text = "", packet = "", outcome = "", fieldPath = "", captureRun = "";
    Integer stat, object;
    Long area;
    boolean changed, observed, issues;

    LoggingQuery copy() {
        LoggingQuery q = new LoggingQuery();
        q.text=text; q.packet=packet; q.outcome=outcome; q.fieldPath=fieldPath; q.captureRun=captureRun;
        q.stat=stat; q.object=object; q.area=area; q.changed=changed; q.observed=observed; q.issues=issues;
        return q;
    }

    boolean matches(Object source, String visibleText) {
        if (source instanceof DiscoveryLog.Event) {
            DiscoveryLog.Event e = (DiscoveryLog.Event)source;
            if (!captureRun.isEmpty() && !captureRun.equals(e.runId)) return false;
            if (!packet.isEmpty() && !packet.equals(e.packet) || area != null && area != e.area
                    || !outcome.isEmpty() && !outcome.equals(e.outcome)) return false;
            // All delta facets must be satisfied by the SAME retained delta.
            if (stat != null || object != null || changed) {
                boolean found = false;
                for (DiscoveryLog.Delta d : e.statChanges) if (matchesDelta(d)) { found=true; break; }
                if (!found) return false;
            }
            return contains(visibleText + " " + eventText(e));
        }
        if (source instanceof DiscoveryLog.PacketRow) {
            DiscoveryLog.PacketRow p = (DiscoveryLog.PacketRow)source;
            if (!packet.isEmpty() && !packet.equals(p.name) || issues && p.failures + p.trailing == 0
                    || !outcome.isEmpty() && !outcome.equals(p.lastOutcome)) return false;
        } else if (source instanceof DiscoveryLog.StatRow) {
            if (stat != null && stat != ((DiscoveryLog.StatRow)source).id) return false;
        } else if (source instanceof DiscoveryCatalog.SchemaField) {
            DiscoveryCatalog.SchemaField f = (DiscoveryCatalog.SchemaField)source;
            if (!packet.isEmpty() && !packet.equals(f.packet) || !fieldPath.isEmpty() && !fieldPath.equals(f.path)) return false;
        } else if (source instanceof String) { // Defined, unobserved packet.
            if (observed || issues || !outcome.isEmpty() || !packet.isEmpty() && !packet.equals(source)) return false;
        }
        return contains(visibleText);
    }

    boolean matchesDelta(DiscoveryLog.Delta d) {
        return (stat == null || stat == d.statId) && (object == null || object == d.objectId)
            && (!changed || isChanged(d));
    }

    private boolean contains(String value) { return value.toLowerCase(Locale.ROOT).contains(text.trim().toLowerCase(Locale.ROOT)); }

    static boolean isChanged(DiscoveryLog.Delta d) {
        return d.previousValue != null && (d.previousValue != d.value
            || d.previousSecondary != null && d.previousSecondary != d.secondary);
    }

    static String statName(int id) {
        StatType type = StatType.byOrdinal(id);
        return id == 114 ? "OWNER_LINK_CANDIDATE (unverified)" : type == null ? "UNMAPPED_STAT_" + id : type.name();
    }

    static String eventText(DiscoveryLog.Event e) {
        StringBuilder text = new StringBuilder(e.packet).append(' ').append(e.timestamp).append(' ')
            .append(e.outcome).append(' ').append(e.values);
        for (DiscoveryLog.Delta d : e.statChanges) text.append('\n').append(deltaText(d));
        return text.toString();
    }

    static String deltaText(DiscoveryLog.Delta d) {
        return "Object " + d.objectId + " · " + statName(d.statId) + " [" + d.statId + "] · "
            + (d.previousValue == null ? "Initial observation" : d.previousValue + " →") + " " + d.value
            + " · secondary " + (d.previousSecondary == null ? "unknown" : d.previousSecondary) + " → " + d.secondary
            + " · " + (isChanged(d) ? "Changed" : "No prior retained value");
    }

    static String fieldKey(DiscoveryCatalog.SchemaField field) { return field.packet + "|" + field.path; }

    /** Only offer paths actually present in the catalog; derived/diagnostic keys need not be schema fields. */
    static List<String> fieldPaths(DiscoveryLog.Event event, List<DiscoveryCatalog.SchemaField> catalog) {
        Set<String> candidates = new LinkedHashSet<>(event.values.keySet());
        if (!event.statChanges.isEmpty()) {
            String prefix = "NEWTICK".equals(event.packet) ? "status[].stats[]."
                : "UPDATE".equals(event.packet) ? "newObjects[].status.stats[]." : null;
            if (prefix != null) candidates.addAll(Arrays.asList(prefix + "statTypeNum", prefix + "statValue", prefix + "statValueTwo"));
        }
        List<String> result = new ArrayList<>();
        for (DiscoveryCatalog.SchemaField field : catalog)
            if (event.packet.equals(field.packet) && candidates.contains(field.path)) result.add(field.path);
        Collections.sort(result);
        return result;
    }

    Map<String,Object> metadata() {
        Map<String,Object> values = new LinkedHashMap<>();
        values.put("literalSearch", text); values.put("packetEquals", packet); values.put("statIdEquals", stat);
        values.put("objectIdEquals", object); values.put("diagnosticAreaEquals", area); values.put("outcomeEquals", outcome);
        values.put("changedValuesOnly", changed); values.put("observedPacketsOnly", observed);
        values.put("packetIssuesOnly", issues); values.put("fieldPathEquals", fieldPath);
        values.put("captureRunEquals", captureRun);
        return values;
    }
}
