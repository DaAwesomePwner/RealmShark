package tomato.gui.stats.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.TreeMap;
import tomato.gui.stats.Fame;
import tomato.gui.stats.data.MapFameData;

/**
 * Represents a fame tracking session that can be saved and loaded
 */
public class FameSession {

    private String sessionName;
    private long createdTimestamp;
    private long lastModifiedTimestamp;
    private HashMap<Integer, List<Fame>> characterFameData;
    private HashMap<Integer, List<MapFameData>> characterMapFameData;
    private HashMap<Integer, String> characterClassNames;
    private String description;
    private boolean readOnly;
    private ArchiveProvenance archiveProvenance;
    /** Optional per-sample exact visit associations (archive projections only). Null when none was recorded. */
    private HashMap<Integer, List<SampleVisit>> sampleVisits;

    /** A fame sample's recorded visit, copied from {@code AppHistory.FameSample}; never inferred. */
    public static final class SampleVisit {
        public final long time; public final String visitSession, visitId, map;
        public SampleVisit(long time, tomato.history.link.VisitRef visit, String map) {
            this.time = time; visitSession = visit.sessionId; visitId = visit.visitId; this.map = map;
        }
        public tomato.history.link.VisitRef visit() {
            return visitSession == null || visitId == null || visitSession.isEmpty() || visitId.isEmpty() ? null : new tomato.history.link.VisitRef(visitSession, visitId);
        }
        public String label() { return (map == null || map.isEmpty() ? "Map not captured" : map) + " · run " + visitSession + "/" + visitId; }
    }
    /** Recorded sample visits for a character, oldest first; empty for legacy histories. */
    public List<SampleVisit> sampleVisits(Integer character) {
        List<SampleVisit> values = sampleVisits == null ? null : sampleVisits.get(character);
        return values == null ? Collections.<SampleVisit>emptyList() : Collections.unmodifiableList(values);
    }
    /** Archive projections only: record one sample's exact visit. */
    public void addSampleVisit(int character, SampleVisit visit) {
        if (sampleVisits == null) sampleVisits = new HashMap<>();
        sampleVisits.computeIfAbsent(character, k -> new ArrayList<>()).add(visit);
    }

    /** View-generation metadata is separate from the original session's dates. */
    public static final class ArchiveProvenance {
        public final String kind, revision, sourceSession;
        public final long generatedTimestamp, sourceRecords;
        private ArchiveProvenance(String kind,String revision,String sourceSession,long sourceRecords){
            this.kind=kind;this.revision=revision;this.sourceSession=sourceSession;this.sourceRecords=sourceRecords;generatedTimestamp=System.currentTimeMillis();
        }
    }
    public ArchiveProvenance getArchiveProvenance(){return archiveProvenance;}
    /** Input is the detached object decoded from the pin, not a live/manual session. */
    public static FameSession pinnedSnapshot(FameSession snapshot,String revision,String sourceSession,long created,long modified){
        snapshot.createdTimestamp=created;snapshot.lastModifiedTimestamp=modified;
        snapshot.archiveProvenance=new ArchiveProvenance("PINNED_SNAPSHOT",revision,sourceSession,1);return snapshot;
    }
    public static FameSession archiveProjection(String name,String revision,String sourceSession,long sourceRecords){
        FameSession result=new FameSession(name);result.createdTimestamp=0;result.lastModifiedTimestamp=0;
        result.description="Synthesized from pinned fame sources; historical creation and modification dates are not inferred.";
        result.readOnly=true;result.archiveProvenance=new ArchiveProvenance("SYNTHESIZED",revision,sourceSession,sourceRecords);return result;
    }

    /** Streaming chronology, reusable without retaining every archive observation. */
    public static final class Chronology {
        private long firstTime=Long.MAX_VALUE,lastTime=Long.MIN_VALUE;
        private double first,last;
        private long dated,undated;
        public void add(long time,double value){
            if(time<=0){undated++;return;}dated++;
            if(time<=firstTime){firstTime=time;first=value;}if(time>=lastTime){lastTime=time;last=value;}
        }
        public long datedCount(){return dated;}
        public long undatedCount(){return undated;}
        public Long firstTime(){return dated==0?null:firstTime;}
        public Long lastTime(){return dated==0?null:lastTime;}
        public Double firstFame(){return dated==0?null:first;}
        public Double lastFame(){return dated==0?null:last;}
        public Double gain(){return dated==0||undated>0?null:last-first;}
        public Long elapsed(){return dated==0||undated>0||firstTime==lastTime?null:lastTime-firstTime;}
        public String explanation(){return dated+" dated observations; "+undated+" undated observations. Endpoints use positive timestamps only. "
            +(undated>0?"Gain and elapsed interval unavailable: included undated observations cannot be placed in chronology."
            :dated==0?"Gain and elapsed interval unavailable: no dated samples."
            :firstTime==lastTime?"One distinct dated timestamp: zero is a same-sample delta, not measured session growth; elapsed interval unavailable."
            :"Gain and elapsed interval use the first and last dated samples; gaps are not reconstructed.");}
    }
    public Chronology chronology(Integer character){Chronology result=new Chronology();for(Fame sample:samples(character))result.add(sample.getTime(),sample.getFame());return result;}
    public ArrayList<Fame> datedSamples(Integer character){
        ArrayList<Fame> result=new ArrayList<>();TreeMap<Long,Fame> pinned=new TreeMap<>();
        for(Fame sample:samples(character))if(sample.getTime()>0){if(archiveProvenance==null)result.add(sample);else pinned.put(sample.getTime(),sample);}
        if(archiveProvenance!=null)result.addAll(pinned.values());else result.sort(Comparator.comparingLong(Fame::getTime));return result;
    }
    private List<Fame> samples(Integer character){List<Fame> samples=characterFameData==null?null:characterFameData.get(character);return samples==null?Collections.emptyList():samples;}

    public FameSession() {
        this.sessionName = "Unnamed Session";
        this.createdTimestamp = System.currentTimeMillis();
        this.lastModifiedTimestamp = this.createdTimestamp;
        this.characterFameData = new HashMap<>();
        this.characterMapFameData = new HashMap<>();
        this.characterClassNames = new HashMap<>();
        this.description = "";
        this.readOnly = false;
    }

    public FameSession(String sessionName) {
        this();
        this.sessionName = sessionName;
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setSessionName(String sessionName) {
        this.sessionName = sessionName;
        updateModifiedTimestamp();
    }

    public long getCreatedTimestamp() {
        return createdTimestamp;
    }

    public long getLastModifiedTimestamp() {
        return lastModifiedTimestamp;
    }

    public HashMap<Integer, List<Fame>> getCharacterFameData() {
        return characterFameData;
    }

    public void setCharacterFameData(
        HashMap<Integer, List<Fame>> characterFameData
    ) {
        this.characterFameData = characterFameData;
        updateModifiedTimestamp();
    }

    public HashMap<Integer, List<MapFameData>> getCharacterMapFameData() {
        return characterMapFameData;
    }

    public HashMap<Integer, String> getCharacterClassNames() {
        return characterClassNames;
    }

    public void setCharacterClassNames(
        HashMap<Integer, String> characterClassNames
    ) {
        this.characterClassNames = characterClassNames;
        updateModifiedTimestamp();
    }

    public void setCharacterMapFameData(
        HashMap<Integer, List<MapFameData>> characterMapFameData
    ) {
        this.characterMapFameData = characterMapFameData;
        updateModifiedTimestamp();
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        checkReadOnly();
        this.description = description;
        updateModifiedTimestamp();
    }

    public void addCharacterData(int characterId, List<Fame> fameData) {
        checkReadOnly();
        characterFameData.put(characterId, new ArrayList<>(fameData));
        updateModifiedTimestamp();
    }

    public void addCharacterData(
        int characterId,
        String className,
        List<Fame> fameData
    ) {
        checkReadOnly();
        characterFameData.put(characterId, new ArrayList<>(fameData));
        characterClassNames.put(characterId, className);
        updateModifiedTimestamp();
    }

    public void addCharacterMapData(
        int characterId,
        List<MapFameData> mapFameData
    ) {
        checkReadOnly();
        characterMapFameData.put(characterId, new ArrayList<>(mapFameData));
        updateModifiedTimestamp();
    }

    public void addCharacterMapData(
        int characterId,
        String className,
        List<MapFameData> mapFameData
    ) {
        checkReadOnly();
        characterMapFameData.put(characterId, new ArrayList<>(mapFameData));
        characterClassNames.put(characterId, className);
        updateModifiedTimestamp();
    }

    public List<Fame> getCharacterData(int characterId) {
        return characterFameData.get(characterId);
    }

    public boolean hasCharacterData(int characterId) {
        return characterFameData.containsKey(characterId);
    }

    public void removeCharacterData(int characterId) {
        checkReadOnly();
        characterFameData.remove(characterId);
        characterMapFameData.remove(characterId);
        characterClassNames.remove(characterId);
        updateModifiedTimestamp();
    }

    public void clearAllData() {
        checkReadOnly();
        characterFameData.clear();
        characterMapFameData.clear();
        characterClassNames.clear();
        updateModifiedTimestamp();
    }

    private void updateModifiedTimestamp() {
        checkReadOnly();
        this.lastModifiedTimestamp = System.currentTimeMillis();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    private void checkReadOnly() {
        if (readOnly) {
            throw new IllegalStateException("Cannot modify read-only session");
        }
    }

    @Override
    public String toString() {
        return (
            "FameSession{" +
            "sessionName='" +
            sessionName +
            '\'' +
            ", createdTimestamp=" +
            createdTimestamp +
            ", lastModifiedTimestamp=" +
            lastModifiedTimestamp +
            ", characterCount=" +
            characterFameData.size() +
            ", mapDataCount=" +
            characterMapFameData.size() +
            ", classNamesCount=" +
            characterClassNames.size() +
            ", description='" +
            description +
            '\'' +
            ", readOnly=" +
            readOnly +
            '}'
        );
    }
}
