package tomato.realmshark;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import util.PreferencesStore;
import static org.junit.Assert.*;
import static tomato.realmshark.AlertRules.*;

public class AlertRulesTest {
    private final Map<String, String> memory = new HashMap<>();
    private final AlertRules service = new AlertRules(memory::get, (key, value) -> {
        memory.put(key, value); return CompletableFuture.completedFuture(PreferencesStore.SaveResult.saved(1));
    });
    @Test public void legacyNumericSubstringAndQuotedTokensSurviveMigrationAndRestart() {
        List<String> item = Arrays.asList("42", "Blade");
        Snapshot before = service.snapshot(Domain.ITEM, item);
        assertTrue(before.matchItem(142, null).matched);
        assertTrue(before.matchItem(8, "Blade").matched);
        service.save(before, before.rules);
        AlertRules restarted = new AlertRules(memory::get, (key, value) -> { throw new AssertionError("Read must not save"); });
        assertTrue(restarted.matchItem(Collections.emptyList(), 142, null).matched);
        assertTrue(restarted.matchItem(Collections.emptyList(), 8, "Blade").matched);
        for (String value : Arrays.asList("\"help\"", "\"two words\"", "\"\"", "[rare]")) {
            Snapshot chat = new AlertRules(key -> null, (k, v) -> null).snapshot(Domain.CHAT, Collections.singletonList(value));
            for (String sample : Arrays.asList("help", "help!", "help\tme", "HELP me", "two words", "a  b", "a [rare] drop")) {
                boolean expected = value.startsWith("\"") && value.endsWith("\"")
                    ? Arrays.asList(sample.toLowerCase().split(" ")).contains(value.substring(1, value.length() - 1).toLowerCase())
                    : sample.toLowerCase().contains(value.toLowerCase());
                assertEquals(value + " / " + sample, expected, chat.matchChat(sample).matched);
            }
        }
    }
    @Test public void explicitModesDoNotConflateNamesNumericIdsOrObjectInstances() {
        Snapshot items = service.snapshot(Domain.ITEM, Collections.emptyList()).withRules(Arrays.asList(Rule.of(Mode.ITEM_ID, "42")));
        assertTrue(items.matchItem(42, null).matched); assertFalse(items.matchItem(142, "42").matched);
        items = items.withRules(Arrays.asList(Rule.of(Mode.NAME_CONTAINS, "42")));
        assertFalse(items.matchItem(42, null).matched); assertTrue(items.matchItem(142, "Blade42").matched);
        Snapshot entity = service.snapshot(Domain.ENTITY, Arrays.asList("0042", "+42"));
        assertFalse(entity.matchEntityType(42).matched);
        entity = entity.withRules(Arrays.asList(Rule.of(Mode.ENTITY_TYPE, "45076")));
        assertTrue(entity.matchEntityType(45076).matched); assertFalse(entity.matchEntityType(42).matched);
        Snapshot chat = service.snapshot(Domain.CHAT, Collections.emptyList()).withRules(Arrays.asList(Rule.of(Mode.SPACE_TOKEN, "help")));
        assertTrue(chat.matchChat("HELP now").matched); assertFalse(chat.matchChat("help!").matched);
        assertTrue(chat.matchChat("help!").explanation.contains("Punctuation"));
        assertFalse(chat.matchChat("help\nnow").matched);
    }
    @Test public void emptyTypedRulesOverrideLegacyAndMalformedFutureFormatsAreNeverRewritten() {
        Snapshot empty = service.snapshot(Domain.ITEM, Arrays.asList("42")); service.save(empty, Collections.emptyList());
        assertFalse(service.matchItem(Arrays.asList("42"), 42, null).matched);
        for (String raw : Arrays.asList("", "{bad", "{\"version\":2,\"rules\":[]}", "{\"version\":1.5,\"rules\":[]}", "null")) {
            memory.put(Domain.ITEM.key(), raw);
            Snapshot invalid = service.snapshot(Domain.ITEM, Arrays.asList("42"));
            assertFalse(invalid.editable()); assertFalse(invalid.matchItem(42, null).matched);
            try { service.save(invalid, Collections.emptyList()); fail(); } catch (IllegalStateException expected) { }
            assertEquals(raw, memory.get(Domain.ITEM.key()));
        }
    }
    @Test public void unknownRowsAndFieldsRoundTripAlongsideEditsAndInvalidKnownRulesStayInactive() {
        String raw = "{\"version\":1,\"future\":{\"x\":7},\"rules\":[{\"mode\":\"ITEM_ID\",\"value\":\"42\",\"extra\":true},"
            + "{\"mode\":\"FUTURE\",\"value\":\"x\"},null,{\"mode\":\"ITEM_ID\",\"value\":\"bad\"}]}";
        memory.put(Domain.ITEM.key(), raw); Snapshot snapshot = service.snapshot(Domain.ITEM, Collections.emptyList());
        assertTrue(snapshot.editable()); assertEquals(4, snapshot.rules.size());
        List<Rule> rules = new ArrayList<>(snapshot.rules); rules.set(0, rules.get(0).edited(Mode.ITEM_ID, "43"));
        service.save(snapshot, rules);
        JsonObject saved = JsonParser.parseString(memory.get(Domain.ITEM.key())).getAsJsonObject();
        assertEquals(7, saved.getAsJsonObject("future").get("x").getAsInt());
        assertTrue(saved.getAsJsonArray("rules").get(0).getAsJsonObject().get("extra").getAsBoolean());
        assertEquals(JsonParser.parseString(raw).getAsJsonObject().getAsJsonArray("rules").get(1), saved.getAsJsonArray("rules").get(1));
        assertTrue(saved.getAsJsonArray("rules").get(2).isJsonNull());
        assertFalse(service.matchItem(Collections.emptyList(), 42, null).matched);
        assertTrue(service.matchItem(Collections.emptyList(), 43, null).matched);
    }
    @Test public void staleSameCategoryEditsAreRejectedButOtherCategoriesDoNotConflict() {
        Snapshot first = service.snapshot(Domain.ITEM, Arrays.asList("1")), stale = service.snapshot(Domain.ITEM, Arrays.asList("1"));
        Snapshot chat = service.snapshot(Domain.CHAT, Collections.emptyList());
        Submission submitted = service.save(first, Arrays.asList(Rule.of(Mode.ITEM_ID, "42")));
        service.save(chat, Arrays.asList(Rule.of(Mode.TEXT_CONTAINS, "hi")));
        assertTrue(service.isCurrent(submitted.active));
        try { service.save(stale, stale.rules); fail(); } catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("another editor")); }
    }
    @Test public void failedDiskSaveKeepsActiveMemoryAndRetryUsesTheNewRevision() {
        CompletableFuture<PreferencesStore.SaveResult> future = new CompletableFuture<>();
        AlertRules rules = new AlertRules(memory::get, (key, value) -> { memory.put(key, value); return future; });
        Snapshot original = rules.snapshot(Domain.ITEM, Collections.emptyList());
        Submission submission = rules.save(original, Arrays.asList(Rule.of(Mode.ITEM_ID, "42")));
        assertTrue(rules.matchItem(Collections.emptyList(), 42, null).matched);
        assertFalse(submission.completion.toCompletableFuture().isDone());
        future.complete(PreferencesStore.SaveResult.failed(1, new java.io.IOException("denied")));
        assertTrue(rules.matchItem(Collections.emptyList(), 42, null).matched);
        assertTrue(rules.isCurrent(submission.active));
        rules.save(submission.active, submission.active.rules);
    }
    @Test public void unicodeRulesRoundTripThroughLegacyPlatformCharsetAndNewCaseFoldingIsLocaleIndependent() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            Snapshot draft = service.snapshot(Domain.CHAT, Collections.emptyList());
            service.save(draft, Arrays.asList(Rule.of(Mode.TEXT_CONTAINS, "雪 🦈"), Rule.of(Mode.TEXT_CONTAINS, "ITEM")));
            String encoded = memory.get(Domain.CHAT.key());
            assertTrue(encoded.chars().allMatch(c -> c < 128));
            memory.put(Domain.CHAT.key(), new String(encoded.getBytes(java.nio.charset.Charset.forName("windows-1252")), java.nio.charset.Charset.forName("windows-1252")));
            assertTrue(service.matchChat(Collections.emptyList(), "雪 🦈").matched);
            assertTrue(service.matchChat(Collections.emptyList(), "item").matched);
        } finally { Locale.setDefault(previous); }
    }
}
