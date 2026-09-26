package tomato.gui.chat;

import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.swing.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import packets.data.QuestData;
import packets.incoming.QuestFetchResponsePacket;
import tomato.Tomato;
import tomato.backend.TomatoPacketCapture;
import tomato.backend.data.TomatoData;
import tomato.gui.TomatoGUI;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewStateStore;
import tomato.gui.modern.WorkspaceShell;
import tomato.history.*;
import tomato.history.archive.*;
import util.PropertiesManager;
import static org.junit.Assert.*;
import static tomato.gui.activity.SnapshotTestSupport.await;
import static tomato.gui.activity.ActivityArchiveUiTest.edt;

/** Actual shell wiring with synthetic history; no frame, focus, remote rules, or capture. */
public class ShellHookIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private final Map<Field,Object> original = new LinkedHashMap<>();
    private WorkspaceShell shell;
    private TomatoGUI gui;
    private SessionStore store;
    private TomatoData data;
    private String filters, ignoredVisibility, temporaryDirectory;
    private final Map<String,String> archivePreferences = new LinkedHashMap<>();
    private static final String[] MODULES = {"chat", "keypops", "inspect", "statistics", "loot", "runs", "timeline"};

    @Before public void open() throws Exception {
        filters = PropertiesManager.getProperty("chat.filters");
        ignoredVisibility = PropertiesManager.getProperty("chat.showIgnoredPlayers");
        PropertiesManager.setProperties("chat.filters", "{}");
        PropertiesManager.setProperties("chat.showIgnoredPlayers", "false");
        for (String key : archiveKeys()) {
            archivePreferences.put(key, PropertiesManager.getProperty(key));
            PropertiesManager.setProperties(key, "");
        }
        temporaryDirectory = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", temp.newFolder("scratch").getAbsolutePath());
        store = new SessionStore(temp.newFolder().toPath(), true, "synthetic");
        remember(AppHistory.class, "store", store); remember(Tomato.class, "preview", true);
        for (Class<?> type : new Class<?>[]{TomatoGUI.class, ChatGUI.class})
            for (Field field : type.getDeclaredFields()) if (Modifier.isStatic(field.getModifiers()) && !Modifier.isFinal(field.getModifiers())) {
                field.setAccessible(true); original.put(field, field.get(null));
            }
        data = new TomatoData();
        SwingUtilities.invokeAndWait(this::buildShell);
    }
    private void buildShell() { gui = new TomatoGUI(data); shell = (WorkspaceShell)gui.createWorkspace(); }
    private static Set<String> archiveKeys() throws Exception {
        Field field = PropertiesManager.class.getDeclaredField("properties"); field.setAccessible(true);
        Set<String> keys = new HashSet<>(((Properties)field.get(null)).stringPropertyNames());
        keys.removeIf(key -> !key.startsWith("ux.archive.")); return keys;
    }
    private void remember(Class<?> type, String name, Object next) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); original.put(field, field.get(null)); field.set(null, next);
    }
    @After public void close() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (gui != null) gui.closeWorkspace();
            if (shell != null) shell.removeNotify();
            try { for (Map.Entry<Field,Object> entry : original.entrySet()) entry.getKey().set(null, entry.getValue()); }
            catch (IllegalAccessException e) { throw new AssertionError(e); }
            PropertiesManager.setProperties("chat.filters", filters == null ? "{}" : filters);
            PropertiesManager.setProperties("chat.showIgnoredPlayers", ignoredVisibility == null ? "false" : ignoredVisibility);
        });
        for (String key : archiveKeys()) PropertiesManager.setProperties(key, archivePreferences.getOrDefault(key, ""));
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        if (temporaryDirectory != null) System.setProperty("java.io.tmpdir", temporaryDirectory);
        if (store != null) store.close();
    }

    @Test public void shellQuestsReceiveScopedPacketsAndRejectTheLegacyUnscopedBypass() throws Exception {
        data.progression().reset("synthetic-account", "fixture identified");
        QuestData quest = new QuestData(); quest.id = "scoped"; quest.name = "Scoped fixture quest"; quest.description = "Synthetic";
        quest.requirements = new int[0]; quest.rewards = new int[0];
        QuestFetchResponsePacket packet = new QuestFetchResponsePacket(); packet.quests = new QuestData[]{quest};
        new TomatoPacketCapture(data).packetCapture(packet);
        SwingUtilities.invokeAndWait(() -> {
            JTable table = named(shell, "quest-table", JTable.class);
            assertEquals(1, table.getRowCount()); assertEquals("Scoped fixture quest", table.getValueAt(0, 1));
            TomatoGUI.updateQuests(new QuestData[0]);
        });
        SwingUtilities.invokeAndWait(() -> assertEquals(1, named(shell, "quest-table", JTable.class).getRowCount()));
        data.captureStopped();
        SwingUtilities.invokeAndWait(() -> assertTrue(named(shell, "quest-capture-context", JTextArea.class).getText().contains("Stale / unverified")));
    }
    @Test public void planningSearchUsesRealRoutesAndDiscoveryDoesNotToggleCapture() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            tomato.gui.search.ActionRegistry registry = tomato.gui.search.ActionRegistry.application();
            assertFalse(registry.search("font").isEmpty());
            assertFalse(registry.search("history location").isEmpty());
            assertFalse(registry.search("item alert").isEmpty());
            boolean capture = Tomato.isCaptureRunning();
            shell.select(0);
            assertEquals(1, registry.search("plans.characters").size());
            assertEquals(0, shell.getSelectedPage());
            assertTrue(registry.search("plans.characters").get(0).open());
            assertEquals(3, shell.getSelectedPage());
            assertTrue(tomato.gui.route.Navigator.current().back()); assertEquals(0, shell.getSelectedPage());
            assertTrue(registry.search("plans.quests").get(0).open()); assertEquals(5, shell.getSelectedPage());
            assertTrue(tomato.gui.route.Navigator.current().back()); assertEquals(0, shell.getSelectedPage());
            assertEquals(capture, Tomato.isCaptureRunning());
        });
    }

    @Test public void shellQueriedHistorySharesTheLiveChatPolicy() throws Exception {
        ChatMessage message = new ChatMessage(LocalDateTime.of(2026, 9, 1, 12, 0), ChatMessage.Channel.WORLD,
            "IntegrationAnn", "", "IntegrationAnn", "synthetic conversation", "");
        store.append("chat", message); store.flush();
        ArchiveWorkspace<?,?,?> panel = workspace("chat");
        SwingUtilities.invokeAndWait(() -> panel.selectSession(SessionStore.ALL));
        await(() -> !panel.loading() && named(panel, "chat-archive-messages", JTable.class) != null);
        Field field = ChatGUI.class.getDeclaredField("filters"); field.setAccessible(true);
        ChatFilters livePolicy = (ChatFilters)field.get(find(panel, ChatGUI.class));
        SwingUtilities.invokeAndWait(() -> {
            JTable table = named(panel, "chat-archive-messages", JTable.class);
            assertEquals(1, table.getRowCount()); table.setRowSelectionInterval(0, 0);
            button(panel, "Toggle local sender ignore").doClick();
            assertTrue(livePolicy.ignoresPlayer("IntegrationAnn"));
        });
        await(() -> !panel.loading() && panel.displayedPage().matches == 0);
        assertEquals(1, store.read(store.currentId(), "chat", ChatMessage.class).size());
    }

    @Test public void shellRegistersQueriedFactoriesWithFreshLiveDefaultsAndLibraryInEveryWrapper() throws Exception {
        int windows = Window.getWindows().length;
        SwingUtilities.invokeAndWait(() -> {
            Set<Component> wrappers = Collections.newSetFromMap(new IdentityHashMap<>());
            for (String module : MODULES) {
                ArchiveWorkspace<?,?,?> workspace = workspace(module);
                assertTrue(wrappers.add(workspace));
                assertFalse(module, workspace.state().archive);
                assertEquals(module, ArchiveQuery.CURRENT, workspace.state().query.scope());
                assertNull(module, workspace.displayedPage());
                AbstractButton library = button(workspace, "History library…");
                assertNotNull(module, library); assertTrue(library.isEnabled());
                assertTrue(library.isVisible()); assertEquals(1, library.getActionListeners().length);
            }
            assertNotNull(find(workspace("chat"), ChatGUI.class));
            assertNotNull(find(workspace("keypops"), tomato.gui.keypop.KeypopGUI.class));
            assertNotNull(find(workspace("inspect"), tomato.gui.security.SecurityGUI.class));
            assertNotNull(find(workspace("statistics"), tomato.gui.stats.StatisticsGUI.class));
            assertNotNull(find(workspace("loot"), tomato.gui.stats.LootDashboard.class));
            assertNotNull(find(workspace("runs"), tomato.gui.activity.ActivityPanel.class));
            assertNotNull(find(workspace("timeline"), tomato.gui.activity.ActivityPanel.class));
        });
        assertFalse(Tomato.isCaptureRunning()); assertEquals(windows, Window.getWindows().length);
    }

    @Test public void sidebarAndRecreatedShellPreserveIndependentScopesAndNamedPastViews() throws Exception {
        String past;
        try (SessionStore old = new SessionStore(store.directory(), true, "synthetic-past")) {
            past = old.currentId(); old.append("chat", message("Past conversation")); old.flush();
        }
        ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> chat = chatWorkspace();
        SwingUtilities.invokeAndWait(() -> {
            chat.changeQuery(ChatArchiveClient.query().withScope(past).withText("Past conversation"));
            workspace("keypops").selectSession(SessionStore.ALL);
            workspace("loot").showSaved();
        });
        await(() -> !chat.loading() && chat.displayedPage() != null);
        edt(() -> chat.saveNamed("Past review")).toCompletableFuture().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(() -> {
            for (int index : new int[]{1, 8, 10, 0}) named(shell, "nav-" + index, JToggleButton.class).doClick();
            assertEquals(past, chat.state().query.scope()); assertTrue(chat.state().archive);
            assertEquals(SessionStore.ALL, workspace("keypops").state().query.scope());
            assertTrue(workspace("loot").state().archive); assertFalse(workspace("statistics").state().archive);
            assertFalse(workspace("runs").state().archive);
            gui.closeWorkspace(); shell.removeNotify();
        });
        PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS);
        SwingUtilities.invokeAndWait(this::buildShell);
        ArchiveWorkspace<?,?,?> restored = workspace("chat");
        await(() -> !restored.loading() && restored.displayedPage() != null);
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(1, restored.displayedPage().matches); assertEquals(past, restored.state().query.scope());
            assertEquals("Past conversation", restored.state().query.text());
            assertEquals(SessionStore.ALL, workspace("keypops").state().query.scope());
            assertTrue(workspace("loot").state().archive); assertFalse(workspace("statistics").state().archive);
            restored.selectSession(ArchiveQuery.CURRENT); assertFalse(restored.state().archive);
            restored.loadNamed("Past review");
            assertEquals(past, restored.state().query.scope()); assertTrue(restored.state().archive);
            assertEquals(Collections.singletonList("Past review"), ViewStateStore.application().names("chat"));
            TomatoGUI.browseSavedHistory();
            assertEquals(10, shell.getSelectedPage()); assertEquals(SessionStore.ALL, workspace("runs").state().query.scope());
            assertEquals(past, restored.state().query.scope());
        });
    }

    @Test public void shellDisposalReleasesAllReadersButKeepsHistoryOpenForFinalCollectorCheckpoint() throws Exception {
        store.append("chat", message("Before close")); store.flush();
        // Exercise a nested Resources query even before the separately owned DpsGUI registration lands.
        Path nestedScratch = temp.newFolder("nested-scratch").toPath();
        ArchiveWorkspace<?,?,?> nested = edt(() -> {
            ArchiveWorkspace<?,?,?> resources = tomato.gui.activity.ActivityPanel.workspace(store, new JLabel("Live resources"),
                tomato.gui.activity.ActivityPanel.Mode.COMBAT, nestedScratch, ViewStateStore.application());
            JPanel container = new JPanel(new BorderLayout()); container.add(resources);
            named(shell, "dps-tabs", JTabbedPane.class).addTab("Nested lifecycle fixture", container);
            resources.showSaved(); return resources;
        });
        java.util.List<ArchivePage<?>> pages = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> { for (String module : MODULES) workspace(module).showSaved(); });
        await(() -> !nested.loading() && nested.displayedPage() != null && Arrays.stream(MODULES).allMatch(module -> !workspace(module).loading() && workspace(module).displayedPage() != null));
        SwingUtilities.invokeAndWait(() -> {
            for (String module : MODULES) pages.add(workspace(module).displayedPage());
            pages.add(nested.displayedPage());
        });
        try (ArchiveResult.Lease<ChatArchiveClient.Row> held = edt(() -> chatWorkspace().displayedPage().lease())) {
            gui.closeWorkspace(); gui.closeWorkspace();
            for (ArchivePage<?> page : pages) {
                try (ArchiveResult.Lease<?> unexpected = page.lease()) { fail("Disposed workspace retained its result owner"); }
                catch (java.io.IOException expected) { /* Owner closed; existing leases remain valid. */ }
            }
            java.util.List<ChatArchiveClient.Row> rows = new ArrayList<>();
            held.stream(ExportSelection.all(), row -> rows.add(row.value), new Cancellation());
            assertEquals(1, rows.size()); assertEquals("Before close", rows.get(0).message.text);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (hasArchiveScratch(temp.getRoot().toPath()) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse("Closing the shell and final lease releases private pin/result scratch", hasArchiveScratch(temp.getRoot().toPath()));
        store.append("chat", message("After view disposal")); store.flush();
        store.collect("integration-final", () -> store.put("integration-final", "checkpoint", "Final collected value"));
        String session = store.currentId(); Path root = store.directory(); store.close();
        try (SessionStore reader = new SessionStore(root, false, "read-only")) {
            assertEquals(2, reader.read(session, "chat", ChatMessage.class).size());
            assertEquals(Collections.singletonList("Final collected value"), reader.read(session, "integration-final", String.class));
        }
    }

    @Test public void previewQueriesKeepCapturedDataReadOnlyAndAvailabilityUnknownWhileViewStatePersists() throws Exception {
        store.append("chat", message("Preview conversation")); store.flush();
        gui.closeWorkspace(); SwingUtilities.invokeAndWait(() -> shell.removeNotify());
        String session = store.currentId(); Path root = store.directory(); store.close();
        Map<String,String> before = capturedFiles(root.resolve(session));
        store = new SessionStore(root, false, "preview");
        Field field = AppHistory.class.getDeclaredField("store"); field.setAccessible(true); field.set(null, store);
        SwingUtilities.invokeAndWait(this::buildShell);
        SwingUtilities.invokeAndWait(() -> { for (String module : MODULES) workspace(module).selectSession(session); });
        await(() -> Arrays.stream(MODULES).allMatch(module -> !workspace(module).loading() && workspace(module).displayedPage() != null));
        SwingUtilities.invokeAndWait(() -> {
            JTable chat = named(workspace("chat"), "chat-archive-messages", JTable.class);
            chat.setRowSelectionInterval(0, 0); button(workspace("chat"), "Toggle star").doClick();
            assertTrue(named(workspace("chat"), "chat-archive-save-status", JTextArea.class).getText().contains("read-only"));
        });
        AppHistory.append("chat", message("Must not be saved")); store.flush();
        assertEquals(before, capturedFiles(root.resolve(session)));
        for (SessionStore.SessionEntry entry : store.catalog(new Cancellation()))
            for (String module : MODULES) assertEquals(SessionStore.ModuleAvailability.State.UNKNOWN, entry.availability(module).state);
        assertTrue(PropertiesManager.flush().toCompletableFuture().get(5, TimeUnit.SECONDS).isSuccess());
        assertTrue(PropertiesManager.getProperty("ux.archive.chat").contains(session));
    }

    @Test public void missingHistoryStoreRetainsTheExistingLiveViews() throws Exception {
        gui.closeWorkspace(); SwingUtilities.invokeAndWait(() -> shell.removeNotify());
        Field field = AppHistory.class.getDeclaredField("store"); field.setAccessible(true); field.set(null, null);
        SwingUtilities.invokeAndWait(() -> {
            buildShell(); assertNull(find(shell, ArchiveWorkspace.class));
            assertNotNull(find(shell, ChatGUI.class)); assertNotNull(find(shell, tomato.gui.keypop.KeypopGUI.class));
            assertNotNull(find(shell, tomato.gui.security.SecurityGUI.class));
            assertNotNull(find(shell, tomato.gui.stats.StatisticsGUI.class));
            assertNotNull(find(shell, tomato.gui.stats.LootDashboard.class));
            TomatoGUI.browseSavedHistory(); assertEquals(10, shell.getSelectedPage());
        });
    }

    private ArchiveWorkspace<?,?,?> workspace(String module) {
        ArchiveWorkspace<?,?,?> result = named(shell, module + "-session-view", ArchiveWorkspace.class);
        assertNotNull(module + " queried factory must be registered", result); return result;
    }
    @SuppressWarnings("unchecked")
    private ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort> chatWorkspace() {
        return (ArchiveWorkspace<ChatArchiveClient.Row,ChatArchiveClient.Facets,ChatArchiveClient.Sort>) workspace("chat");
    }
    private static ChatMessage message(String text) {
        return new ChatMessage(LocalDateTime.of(2026, 9, 1, 12, 0), ChatMessage.Channel.WORLD, "Ann", "", "Ann", text, "");
    }
    private static Map<String,String> capturedFiles(Path root) throws Exception {
        Map<String,String> contents = new TreeMap<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : (Iterable<Path>)files.filter(Files::isRegularFile)::iterator)
                contents.put(root.relativize(path).toString(), Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
        }
        return contents;
    }
    private static boolean hasArchiveScratch(Path root) throws Exception {
        try (Stream<Path> files = Files.walk(root)) {
            return files.anyMatch(path -> path.getFileName().toString().startsWith("archive-pin-")
                || path.getFileName().toString().startsWith("archive-result-"));
        }
    }
    private static AbstractButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof AbstractButton && text.equals(((AbstractButton)c).getText())) return (AbstractButton)c;
            if (c instanceof Container) { AbstractButton found = button((Container)c, text); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T find(Container root, Class<T> type) { return named(root, null, type); }
    private static <T> T named(Container root, String name, Class<T> type) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && (name == null || name.equals(c.getName()))) return type.cast(c);
            if (c instanceof Container) { T found = named((Container)c, name, type); if (found != null) return found; }
        }
        return null;
    }
}
