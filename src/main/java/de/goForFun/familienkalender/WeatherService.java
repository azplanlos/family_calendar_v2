package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.WeatherDay;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ruft die Wettervorhersage über die OpenWeatherMap OneCall API 3.0 ab.
 * Liefert bis zu 3 Tage mit Icon-Code und Min/Max-Temperatur.
 */
public class WeatherService {

    private static final String BASE_URL = "https://api.openweathermap.org/data/3.0/onecall";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String apiKey;
    private final String lat;
    private final String lon;

    public WeatherService(String apiKey, String lat, String lon) {
        this.apiKey = apiKey;
        this.lat = lat;
        this.lon = lon;
    }

    /**
     * Holt die Wettervorhersage für die nächsten 3 Tage.
     * Gibt eine leere Liste zurück, wenn die API nicht erreichbar ist oder Daten fehlen.
     */
    public List<WeatherDay> fetchForecast() {
        List<WeatherDay> result = new ArrayList<>();

        if (apiKey == null || apiKey.isBlank() || lat == null || lon == null) {
            return result;
        }

        try {
            String url = BASE_URL
                    + "?lat=" + lat
                    + "&lon=" + lon
                    + "&exclude=minutely,hourly,current,alerts"
                    + "&units=metric"
                    + "&appid=" + apiKey;

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return result;
            }

            String body = response.body();
            result = parseDailyForecast(body);

        } catch (IOException | InterruptedException e) {
            // API nicht erreichbar – leere Liste zurückgeben
            Thread.currentThread().interrupt();
        }

        return result;
    }

    /**
     * Parst die JSON-Antwort minimal ohne externe JSON-Bibliothek.
     * Extrahiert aus dem "daily"-Array die ersten 3 Tage mit icon, temp.min, temp.max.
     */
    List<WeatherDay> parseDailyForecast(String json) {
        List<WeatherDay> days = new ArrayList<>();

        // Finde das "daily" Array
        int dailyIdx = json.indexOf("\"daily\"");
        if (dailyIdx == -1) {
            return days;
        }

        // Finde den Start des Arrays
        int arrayStart = json.indexOf('[', dailyIdx);
        if (arrayStart == -1) {
            return days;
        }

        // Extrahiere einzelne Tages-Objekte (top-level objects im daily array)
        int depth = 0;
        int objStart = -1;
        List<String> dailyObjects = new ArrayList<>();

        for (int i = arrayStart; i < json.length() && dailyObjects.size() < 3; i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 1) {
                    objStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 1 && objStart != -1) {
                    dailyObjects.add(json.substring(objStart, i + 1));
                    objStart = -1;
                }
            } else if (c == '[') {
                if (depth == 0) depth = 1; else depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) break;
            }
        }

        // Pattern für relevante Felder
        Pattern iconPattern = Pattern.compile("\"icon\"\\s*:\\s*\"([^\"]+)\"");
        Pattern minTempPattern = Pattern.compile("\"min\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)");
        Pattern maxTempPattern = Pattern.compile("\"max\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)");

        for (String dayJson : dailyObjects) {
            String iconCode = extractFirst(iconPattern, dayJson);
            String minTempStr = extractFirst(minTempPattern, dayJson);
            String maxTempStr = extractFirst(maxTempPattern, dayJson);

            if (iconCode != null && minTempStr != null && maxTempStr != null) {
                // Nacht-Icons auf Tag-Variante normalisieren (wir zeigen nur Tages-Icons)
                if (iconCode.endsWith("n")) {
                    iconCode = iconCode.substring(0, iconCode.length() - 1) + "d";
                }
                int minTemp = (int) Math.round(Double.parseDouble(minTempStr));
                int maxTemp = (int) Math.round(Double.parseDouble(maxTempStr));
                days.add(new WeatherDay(iconCode, minTemp, maxTemp));
            }
        }

        return days;
    }

    private String extractFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
}
