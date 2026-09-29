package tomato.gui.stats;

import java.awt.BorderLayout;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import javax.swing.*;
import tomato.gui.history.ArchiveWorkspace;
import tomato.gui.history.ViewStateStore;
import tomato.gui.kit.KitButton;
import tomato.gui.kit.Tokens;
import tomato.gui.modern.ContentStyle;
import tomato.gui.stats.LootQuery.*;
import tomato.gui.stats.session.FameSessionViewer;
import tomato.history.SessionStore;
import tomato.history.archive.ArchiveQuery;

/**
 * Characters › Fame history (user decision 2026-09-29: saved character fame moves from Statistics to an Analyst tab of
 * Characters): a saved-only archive workspace ({@link ArchiveWorkspace#savedOnly}) over the Character fame view that the
 * retired Statistics page offered, unchanged — per saved session and character, the first and last fame reading, the change between them and the
 * observed time, with undated observations counted apart and never ordered as epoch zero — opening on every saved session. No
 * live view: the live Fame Table and the legacy {@code FameSessions} autosave go with Statistics.
 *
 * <p>"Open selected session's full fame graph" ({@code archive-open-fame}) opens the selected row's whole pinned session in the
 * fame session viewer, as Statistics did. {@link #view} adds a header line and "Open fame session file…"
 * ({@code character-fame-open-file}), which opens a saved {@code .fame} file as the Fame Table's Sessions popup did.
 *
 * <p>The workspace starts a read from its constructor, so Characters builds it on the tab's first selection
 * ({@code CharacterPanelGUI.hostFame}) and the app's workspace close reaches it through the tabs' contents, also while the tab is
 * hidden. Its name, {@value #NAME}, keeps its filter row ({@code character-fame-filter-bar}), drawer preference and saved view
 * state ({@code ux.archive.character-fame}) apart from the Loot and Dungeons workspaces.
 */
public final class CharacterFameHistory {
    /** The workspace's name: its component names, filter-bar key and saved view-state key. */
    public static final String NAME = "character-fame";
    /** The one view it offers. */
    public static final Set<View> VIEWS = Collections.unmodifiableSet(EnumSet.of(View.FAME));
    /** The view's header line. */
    public static final String HEADER = "Character fame per saved session: first and last reading, gain and observed time.";
    /** The open-file button's text. */
    public static final String OPEN_FILE = "Open fame session file…";

    private CharacterFameHistory() { }

    /** The first query: the character fame of every saved session. */
    public static ArchiveQuery<Facets, Sort> initialQuery() { return LootQuery.initial(View.FAME, SessionStore.ALL); }

    /** The client: the saved loot queries, rows, drill-downs and exports, offering only Character fame. */
    static LootArchiveClient client(Path scratch) { return new LootArchiveClient(scratch, VIEWS, initialQuery()); }

    /**
     * A new saved-only fame workspace over {@code store}, pinning into {@code scratch} and remembering its view in {@code states}
     * (the app's saved-view states). It starts reading at once: call it on the tab's first selection. EDT.
     */
    public static ArchiveWorkspace<Row, Facets, Sort> workspace(SessionStore store, Path scratch, ViewStateStore states) {
        Objects.requireNonNull(store, "store"); Objects.requireNonNull(scratch, "scratch"); Objects.requireNonNull(states, "states");
        return ArchiveWorkspace.savedOnly(store, NAME, client(scratch), states);
    }

    /**
     * The tab's content: the header line and "Open fame session file…" ({@link FameSessionViewer#openSessionViewer()}) above the
     * workspace, which {@code workspace} builds at once. EDT.
     */
    public static JComponent view(Supplier<? extends ArchiveWorkspace<Row, Facets, Sort>> workspace) {
        return view(workspace, FameSessionViewer::openSessionViewer);
    }

    /** {@link #view(Supplier)} with the open-file action given (tests check the wiring without a file dialog). */
    static JComponent view(Supplier<? extends ArchiveWorkspace<Row, Facets, Sort>> workspace, Runnable openFile) {
        Objects.requireNonNull(workspace, "workspace"); Objects.requireNonNull(openFile, "openFile");
        ArchiveWorkspace<Row, Facets, Sort> built = Objects.requireNonNull(workspace.get(), "fame workspace");
        JTextArea header = ContentStyle.wrappingText(HEADER);
        header.setName(NAME + "-header");
        header.getAccessibleContext().setAccessibleName(HEADER);
        KitButton open = KitButton.ghost(OPEN_FILE);
        open.setName(NAME + "-open-file");
        open.getAccessibleContext().setAccessibleDescription("Opens a saved .fame session file, read-only");
        open.addActionListener(e -> openFile.run());
        JPanel top = new JPanel(new BorderLayout(Tokens.S, 0));
        top.setOpaque(false);
        top.add(header, BorderLayout.CENTER);
        top.add(open, BorderLayout.EAST);
        JPanel view = new JPanel(new BorderLayout(0, Tokens.S));
        view.setName(NAME + "-view");
        view.setOpaque(false);
        view.add(top, BorderLayout.NORTH);
        view.add(built, BorderLayout.CENTER);
        return view;
    }
}
