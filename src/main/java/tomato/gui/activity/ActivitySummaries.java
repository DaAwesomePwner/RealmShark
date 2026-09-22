package tomato.gui.activity;

import packets.packetcapture.logger.ActivityJournal;
import java.util.*;

/** Plain-language observations; unknown fields never turn into successful actions or zeroes. */
public final class ActivitySummaries {
    private ActivitySummaries() { }
    public static String event(ActivityJournal.Entry e) {
        Map<String,Object> v=e.values==null?Collections.emptyMap():e.values;
        String kind=Objects.toString(e.kind,"Unknown activity");
        switch(kind) {
            case "Area entered":return "Entered "+text(e.map,"unknown area")+"; completion not established by entry";
            case "Equipment changed":return slot(v.get("slot"))+": "+item(v.get("before"))+" → "+item(v.get("after"));
            case "Exalt change":return "Class "+value(v.get("classId"))+" · "+text(v.get("stat"),"unknown stat")+": "
                    +value(v.get("before"))+" → "+value(v.get("after"))
                    +(e.visitId==null||e.visitId.isEmpty()?" · Between visits; source unassigned":" · Observed progress; completion not established");
            case "Exalt snapshot":return "Class "+value(v.get("classId"))+" progress snapshot; awaiting local identity, no earned progress inferred";
            case "Exalt baseline":return "Class "+value(v.get("classId"))+" progress baseline; no earned progress inferred";
            case "Resources":return "HP "+value(v.get("hp"))+" · MP "+value(v.get("mp"))+"; sources of changes unknown";
            case "Item / ability request":return "Requested "+item(v.get("slotObject.objectType"))+" · "+text(v.get("slotLabel"),"unknown slot")+"; server acceptance not established";
            case "Party roster":return "Party "+value(v.get("partyId"))+" · "+value(v.get("memberCount"))+" observed members; identity links unverified";
            case "Capture issue":return "Capture issue · "+text(e.detail,"unknown packet")+"; evidence may be incomplete";
            case "Inventory request":return "Inventory change requested; result not paired with this request";
            case "Inventory result":return "Inventory result observed; request linkage unknown";
            default:return kind+" · "+text(e.detail,"No human-readable detail recorded; raw fields available below");
        }
    }
    private static String text(Object value,String fallback) { return value==null||value.toString().trim().isEmpty()?fallback:value.toString(); }
    private static String value(Object v) {
        if(v instanceof Number){Number n=(Number)v;return n.doubleValue()==n.longValue()?Long.toString(n.longValue()):n.toString();}
        return text(v,"Unknown");
    }
    private static String item(Object v) {
        if(!(v instanceof Number))return "Unknown item";
        int id=((Number)v).intValue();return id==-1?"Empty":"Item "+id;
    }
    private static String slot(Object v) {
        if(!(v instanceof Number))return "Unknown equipment slot";
        int n=((Number)v).intValue();return n>=0&&n<4?new String[]{"Weapon","Ability","Armor","Ring"}[n]:"Slot "+n;
    }
}
