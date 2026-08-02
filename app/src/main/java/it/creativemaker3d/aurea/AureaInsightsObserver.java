package it.creativemaker3d.aurea;

import android.content.Context;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Lettore in sola lettura degli stati Home Assistant usato da AUREA Insights.
 * Non invia servizi, non modifica entità e non conserva il token.
 */
final class AureaInsightsObserver {
    private final AureaInsightsStore store;
    private String haUrl;
    private String haToken;

    AureaInsightsObserver(Context context, String haUrl, String haToken) {
        store = new AureaInsightsStore(context);
        updateConnection(haUrl, haToken);
    }

    void updateConnection(String haUrl, String haToken) {
        this.haUrl = trimSlash(clean(haUrl));
        this.haToken = clean(haToken);
    }

    AureaInsightsStore.IngestResult poll(String actor) throws Exception {
        if (!store.isEnabled() || store.selectedEntities().isEmpty()) {
            return new AureaInsightsStore.IngestResult(0, false);
        }
        if (haUrl.isEmpty() || haToken.isEmpty()) {
            throw new IllegalStateException("Configurazione Home Assistant incompleta");
        }

        URL endpoint = new URL(haUrl + "/api/states");
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Authorization", "Bearer " + haToken);

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String body = readAll(stream);
        connection.disconnect();

        if (code < 200 || code >= 300) {
            throw new IllegalStateException("HTTP " + code);
        }
        return store.ingestStates(new JSONArray(body), actor);
    }

    private String readAll(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimSlash(String value) {
        String result = clean(value);
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
