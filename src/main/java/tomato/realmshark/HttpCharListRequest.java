package tomato.realmshark;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * HTTP Requests character data from realm servers and converts data to character info.
 */
public class HttpCharListRequest {

    /**
     * Requests character list data from realm servers using the current access token of the logged in user.
     *
     * @param accessToken Access token of the currently logged in user.
     * @return Char list data as XML string.
     */
    public static String getChartList(String accessToken) throws IOException {
        return webRequest(accessToken, "char/list");
    }

    /**
     * Request exalt stats
     *
     * @param accessToken Access token of the currently logged in user.
     * @return Exalt data of all characters
     */
    public static String getPowerUpStats(String accessToken)
        throws IOException {
        return webRequest(accessToken, "account/listPowerUpStats");
    }

    /**
     * Web request packet sent.
     *
     * @return Request info
     */
    public static String webRequest(String accessToken, String requestType)
        throws IOException {
        String encoded = URLEncoder.encode(accessToken, "UTF-8");
        String s1 = "https://www.realmofthemadgod.com/" + requestType + "?";
        String s2 =
            "do_login=true&accessToken=" +
            encoded +
            "&game_net=Unity&play_platform=Unity&game_net_user_id";

        URL obj = new URL(s1 + s2);
        HttpURLConnection con = (HttpURLConnection) obj.openConnection();
        try {
            con.setConnectTimeout(5000);
            con.setReadTimeout(10000);
            con.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
            con.setDoOutput(true);
            con.setRequestMethod("POST");
            try (OutputStream os = con.getOutputStream()) {
                byte[] input = s2.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            int responseCode = con.getResponseCode();
            boolean success = responseCode == HttpURLConnection.HTTP_OK;
            InputStream input = success ? con.getInputStream() : con.getErrorStream();
            if (input == null) return null;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    response.append(line);
                    if (!success) response.append('\n');
                }
                if (success) return response.toString();
                System.err.println("Account metadata request failed: HTTP " + responseCode);
            }
            return null;
        } finally { con.disconnect(); }
    }
}
