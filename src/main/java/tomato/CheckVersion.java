package tomato;

import com.google.gson.*;
import realmshark.branding.AppIdentity;
import tomato.gui.TomatoGUI;
import tomato.gui.modern.ContentStyle;
import tomato.version.Version;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class CheckVersion {
    private static final String GITHUB_API_URL = "https://api.github.com/repos/X-com/RealmShark/releases";
    private static final AtomicBoolean checking = new AtomicBoolean();

    private static String getLatestVersion() throws IOException {
        URL url = new URL(GITHUB_API_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
            if (conn.getResponseCode() != 200) {
                throw new IOException("Failed : HTTP error code : " + conn.getResponseCode());
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String output;
                while ((output = br.readLine()) != null) sb.append(output);
                return getTomatoVersion(sb.toString());
            }
        } finally { conn.disconnect(); }
    }

    private static String getTomatoVersion(String json) {
        JsonElement e = JsonParser.parseString(json);
        if (e.isJsonArray()) {
            for (JsonElement e2 : e.getAsJsonArray()) {
                String target = null;
                String version = null;
                if (e2.isJsonObject()) {
                    JsonObject o = e2.getAsJsonObject();
                    for (String key : o.keySet()) {
                        JsonElement e3 = o.get(key);
                        if (e3.isJsonPrimitive()) {
                            if (key.equals("tag_name")) {
                                version = e3.getAsString();
                            } else if (key.equals("target_commitish")) {
                                target = e3.getAsString();
                            }
                        }
                    }
                }

                if (target != null && version != null) {
                    if (target.equals("tomato")) return version;
                }
            }
        }

        return "";
    }

    public static boolean isLatestVersion() {
        try {
            String latest = getLatestVersion();
            return latest.isEmpty() || latest.equals(Version.VERSION);
        } catch (IOException | JsonParseException ignored) {
            // If we can't check the version, assume it's the latest to avoid bothering the user
        }
        return true;
    }

    private static void updateMessage() {
        JEditorPane ep = new JEditorPane("text/html", "<html><b>" + AppIdentity.title() + "</b> (custom build)<br><br>"
            + "A different upstream release is available for the tracked baseline.<br>"
            + "Installed upstream baseline: " + Version.VERSION + "<br><br>"
            + "This is an upstream release notice, not an update to this custom " + AppIdentity.NAME + " build.<br>"
            + "Upstream changes must be merged and rebuilt to retain its UI and fixes.<br>"
            + "Replacing this build with the stock upstream JAR would lose those customizations.<br><br>"
            + "<a href='https://github.com/X-com/RealmShark/releases'>View upstream releases</a></html>");
        ep.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
        ep.setFont(ContentStyle.body());
        ep.setOpaque(false);
        ep.addHyperlinkListener(e -> {
            if (e.getEventType().equals(HyperlinkEvent.EventType.ACTIVATED)) {
                try {
                    Desktop.getDesktop().browse(new URI("https://github.com/X-com/RealmShark/releases"));
                } catch (IOException | URISyntaxException ex) {
                    ex.printStackTrace();
                }
            }
        });
        ep.setEditable(false);
        JOptionPane pane = new JOptionPane(ep, JOptionPane.INFORMATION_MESSAGE);
        JDialog dialog = pane.createDialog(TomatoGUI.getFrame(), AppIdentity.NAME + " — Upstream Release Notice");
        AppIdentity.apply(dialog);
        dialog.setModal(false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    public static void checkVersion() {
        if (!checking.compareAndSet(false, true)) return;
        new SwingWorker<Boolean, Void>() {
            @Override protected Boolean doInBackground() { return isLatestVersion(); }
            @Override protected void done() {
                checking.set(false);
                try { if (!get()) updateMessage(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (java.util.concurrent.ExecutionException e) { System.err.println("Version check unavailable."); }
            }
        }.execute();
    }

    public static void main(String[] args) {
        AppIdentity.initialize();
        // The command-line checker has no existing window to keep the JVM alive.
        if (!isLatestVersion()) SwingUtilities.invokeLater(CheckVersion::updateMessage);
    }
}
