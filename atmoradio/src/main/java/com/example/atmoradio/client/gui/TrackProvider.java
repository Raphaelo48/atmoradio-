package com.example.atmoradio.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Абстракция поиска треков. Подключите сюда легальный сервис, к которому у вас есть доступ
 * (Jamendo API с ключом, Free Music Archive, собственный backend-прокси и т.д.).
 *
 * Пример ниже использует публичный iTunes Search API (не требует ключа, бесплатный, легальный)
 * как ДЕМОНСТРАЦИЮ механизма: он отдаёт 30-секундные превью треков прямой HTTP-ссылкой на m4a/mp3 -
 * этого достаточно, чтобы показать весь пайплайн поиск -> стрим -> 3D звук в деле.
 * Для полноформатных треков замените searchItunes(...) на вызов вашего сервиса.
 */
public interface TrackProvider {

    record Track(String title, String artist, String streamUrl) {}

    List<Track> search(String query) throws Exception;

    static TrackProvider itunesPreview() {
        return query -> {
            HttpClient client = HttpClient.newHttpClient();
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://itunes.apple.com/search?media=music&limit=20&term=" + encoded))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            List<Track> results = new ArrayList<>();
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray items = root.getAsJsonArray("results");
            for (int i = 0; i < items.size(); i++) {
                JsonObject obj = items.get(i).getAsJsonObject();
                if (!obj.has("previewUrl")) continue;
                String title = obj.has("trackName") ? obj.get("trackName").getAsString() : "Unknown";
                String artist = obj.has("artistName") ? obj.get("artistName").getAsString() : "";
                String url = obj.get("previewUrl").getAsString();
                results.add(new Track(title, artist, url));
            }
            return results;
        };
    }
}
