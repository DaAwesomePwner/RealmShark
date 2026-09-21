package tomato.gui.chat;

import com.google.gson.Gson;
import packets.incoming.TextPacket;
import tomato.backend.data.TomatoData;
import tomato.realmshark.Sound;
import util.Util;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

import java.lang.reflect.Type;

import com.google.gson.reflect.TypeToken;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.nio.charset.StandardCharsets;

public class ChatGUI extends JPanel {
    public static tomato.gui.history.SessionPanel.Loaded history(tomato.history.SessionStore store, String scope, int page, String query) throws IOException {
        tomato.gui.history.HistoryPage<ChatMessage> messages = tomato.gui.history.HistoryPage.read(store, scope, "chat", ChatMessage.class, page, query, ChatMessage::transcript);
        java.util.Map<String,ChatExplorer.Bookmark> bookmarks=new java.util.HashMap<>();
        store.read(tomato.history.SessionStore.ALL,"chat-stars",ChatExplorer.Bookmark.class,(session,bookmark)->{
            ChatExplorer.Bookmark previous=bookmarks.get(bookmark.id);
            if(previous==null||bookmark.changed>=previous.changed)bookmarks.put(bookmark.id,bookmark);
        });
        java.util.Set<String> stars=new java.util.HashSet<>();bookmarks.forEach((id,bookmark)->{if(bookmark.starred)stars.add(id);});
        return new tomato.gui.history.SessionPanel.Loaded(() -> {
            ChatExplorer view = new ChatExplorer(() -> {}, ChatFilters.load(), () -> "Saved chat", false);
            view.loadHistory(messages.values,stars,store);
            return view;
        }, messages.more(), messages.description());
    }

    private static volatile ChatGUI instance;
    private final ChatExplorer explorer;
    private final ChatFilters filters;
    private final ObservedIgnores observedIgnores = new ObservedIgnores();
    public static volatile boolean save;
    private static TomatoData data;

    private final List<String> blockedSpam = new CopyOnWriteArrayList<>();
    private static final String API_URL = "https://api.realmshark.cc/blocked-keywords";
    private static final String BLOCK_FILE = "block.txt";

    public ChatGUI(TomatoData data) {
        this(data, ChatFilters.load(), true);
    }

    ChatGUI(TomatoData data, ChatFilters filters, boolean loadExternalRules) {
        ChatGUI.data = data;
        setLayout(new BorderLayout());

        this.filters = filters;
        explorer = new ChatExplorer(() -> new ChatPingGUI(data, this).open(), filters, observedIgnores::status);
        add(explorer, BorderLayout.CENTER);
        instance = this;
        // Local rules are immediately available; optional remote rules never delay the UI.
        if (loadExternalRules) {
            loadBlockedChatMessageSpamFromFile();
            filters.inherited(blockedSpam);
        }
        if (loadExternalRules && !tomato.Tomato.isPreview()) {
            Thread loader = new Thread(this::loadBlockedSpam, "chat-spam-rules");
            loader.setDaemon(true);
            loader.start();
        }
    }

    private void loadBlockedChatMessageSpamFromFile() {
        File file = new File(BLOCK_FILE);
        if (!file.isFile()) return;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) addBlockedRule(line);
        } catch (IOException e) {
            System.err.println("Could not read local chat spam rules.");
        }
    }

    private void addBlockedRule(String rule) {
        if (rule != null && !ChatFilters.normalize(rule).isEmpty() && !blockedSpam.contains(rule)) {
            blockedSpam.add(rule);
        }
    }

    /**
     * Creates a server request worker to request from server phrases to be blocked by chat. Phrases used by bots.
     */
    private void loadBlockedSpam() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "RealmShark");
            try (Reader in = new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<ArrayList<String>>() {}.getType();
                List<String> blocked = new Gson().fromJson(in, listType);
                if (blocked != null) for (String rule : blocked) addBlockedRule(rule);
            }
        } catch (IOException | com.google.gson.JsonParseException e) {
            System.err.println("Remote chat spam rules unavailable; keeping local rules.");
        } finally {
            if (conn != null) conn.disconnect();
            filters.inherited(blockedSpam);
            SwingUtilities.invokeLater(() -> explorer.refresh(false));
        }
    }

    /** Compatibility entry point for application notices. Notices appear in All and System. */
    public static void appendTextAreaChat(String text) {
        ChatGUI current = instance;
        if (current != null && text != null && !text.isEmpty()) current.explorer.accept(ChatMessage.notice(text));
    }

    /** Clear all channel views consistently; saved log files are untouched. */
    public static void clearTextAreaChat() {
        ChatGUI current = instance;
        if (current != null) current.explorer.clear();
    }

    public static void editFont(Font font) {
        ChatGUI current = instance;
        if (current == null) return;
        if (SwingUtilities.isEventDispatchThread()) current.explorer.editFont(font);
        else SwingUtilities.invokeLater(() -> current.explorer.editFont(font));
    }

    /**
     * Updates chat with chat message.
     *
     * @param p Text packet with chat data.
     */
    public static void updateChat(TextPacket p) {
        if (p == null || p.text == null || p.name == null || p.recipient == null) return;
        ChatGUI current = instance;
        if (current == null) return;
        String localName = data == null || data.player == null ? null : data.player.name();
        ChatMessage message = ChatMessage.from(p, localName).withGameIgnored(current.observedIgnores.matches(p, data));
        current.deliver(p, message);
    }

    /** Filtering is the single gate before every chat-triggered alert. Also used by offline replay tests. */
    void deliver(TextPacket p, ChatMessage message) {
        String ignored = filters.reason(message);
        explorer.accept(message);
        if (!ignored.isEmpty()) {
            if (save) Util.print("chat/chat", message.transcript() + " [Ignored: " + ignored + "]");
            return;
        }
        alert(p, message);
        String response = getString(p);
        if (response != null) explorer.accept(message.hint(response));
        if (save) Util.print("chat/chat", message.transcript());
    }

    void alert(TextPacket p, ChatMessage message) {
        tomato.realmshark.RealmEventAlerts.INSTANCE.accept(p, data == null || data.map == null ? null : data.map.name);
        boolean isPlayer = message.ownMessage;
        boolean pinged = false;
        if (p.recipient.contains("*Guild*")) {
            if (!isPlayer && Sound.guild.isEnabled()) {
                Sound.guild.play();
                pinged = true;
            }
        } else if (p.recipient.contains("*Party*")) {
            if (!isPlayer && Sound.party.isEnabled()) {
                Sound.party.play();
                pinged = true;
            }
        } else if (!p.recipient.trim().isEmpty()) {
            if (message.isIncomingWhisper() && Sound.pm.isEnabled()) {
                Sound.pm.play();
                pinged = true;
            }

        }
        if (data != null && !pinged && !isPlayer && (message.channel != ChatMessage.Channel.PM || message.isIncomingWhisper())) {
            for (String s : data.getChatMessagePings()) {
                if (s == null || s.trim().isEmpty()) continue;
                if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
                    String exactMatch = s.substring(1, s.length() - 1).toLowerCase();
                    for (String m : p.text.toLowerCase().split(" ")) {
                        if (exactMatch.equals(m)) {
                            Sound.keywords.play();
                            break;
                        }
                    }
                } else if (p.text.toLowerCase().contains(s.toLowerCase())) {
                    Sound.keywords.play();
                    break;
                }
            }
        }
    }

    public static void observeAccountList(packets.incoming.AccountListPacket packet) {
        ChatGUI current = instance;
        if (current != null) current.observedIgnores.accept(packet);
    }

    public static void resetObservedIgnores() {
        ChatGUI current = instance;
        if (current != null) current.observedIgnores.reset();
    }

    private static String getString(TextPacket p) {
        String response = null;
        if ("I've been intrigued by folktales from foreign lands recently.".equals(p.text) && "#Village Girl Umi".equals(p.name)) {
            response = "The Happy Prince";
        } else if ("The delicious smells coming from the festival stalls are making me hungry...".equals(p.text) && "#Village Girl Umi".equals(p.name)) {
            response = "Mushroom";
        } else if ("How did you find tonight's performance? It looked extremely fun, I couldn't help cheering you on!".equals(p.text) && "#Village Girl Umi".equals(p.name)) {
            response = "Carosburg";
        }
        return response;
    }

    public void setPingMessages(ArrayList<String> messages) {
        data.savePropList(messages, "chatPingMessages");
    }

    public ArrayList<String> getPingMessages() {
        return data.getChatMessagePings();
    }
}
