package de.goForFun.familienkalender.anonfeed;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import de.goForFun.familienkalender.IcalParser;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Lambda Function URL Handler – liefert den anonymisierten iCal-Feed an FamilyWall.
 *
 * <p>Verantwortung: HTTP-Orchestrierung, Authentifizierung und Fehler→Status-Mapping. Die
 * eigentliche Transformation liegt im {@link FeedAnonymizer}, der Abruf/Parsing in
 * {@link #fetchAndParse(URI)} (Verdrahtung in Task 4.2). Dieser Handler greift <b>nicht</b> auf
 * S3 zu und wirft keine Ausnahmen an die Lambda-Laufzeit weiter – jeder Fehler wird lokal auf
 * einen HTTP-Status abgebildet (Requirement 5.2, 5.3, 5.4).
 *
 * <p>Authentifizierung über den Query-Parameter {@code token} (nicht über HTTP-Header), weil
 * FamilyWall-ICS-Abonnements keine eigenen Header setzen können (Design: Auth-Abweichung).
 *
 * <p>Antwort:
 * <ul>
 *   <li>200 + {@code Content-Type: text/calendar; charset=utf-8} bei Erfolg (Requirement 1.2)</li>
 *   <li>401 bei fehlendem/leerem/falschem/mehrfachem {@code token} (Requirement 7.1, 7.2, 7.4)</li>
 *   <li>500 bei fehlendem/ungültigem {@code ANON_SOURCE_FEED} (Requirement 1.4)</li>
 *   <li>502 wenn die Quelle nicht in 10 s erreichbar oder nicht parsebar ist (Requirement 1.5)</li>
 * </ul>
 */
public class ServeAnonymizedFeed implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_TYPE_ICAL = "text/calendar; charset=utf-8";
    private static final String CONTENT_TYPE_TEXT = "text/plain; charset=utf-8";

    /** Query-Parameter, der das API_Secret trägt (Requirement 7.1). */
    private static final String TOKEN_PARAM = "token";

    /**
     * Signalisiert einen nicht verarbeitbaren Quellfeed → 502 (Requirement 1.5).
     *
     * <p>Paket-sichtbar, damit Tests, die {@link #fetchAndParse(URI)} überschreiben, den
     * 502-Pfad (Timeout/unparsebar) ohne echten Netzwerkzugriff auslösen können.
     */
    static final class FeedUnavailableException extends Exception {
        FeedUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    /**
     * Lädt die Laufzeit-Konfiguration. Default liest die {@code ANON_*}-Umgebungsvariablen über
     * {@link AnonConfig#fromEnv()}.
     *
     * <p>Als paket-sichtbarer Seam ausgelegt: Da Unit-Tests keine echten Umgebungsvariablen
     * setzen können, überschreiben sie diese Methode, um eine per {@link AnonConfig#fromMap(Map)}
     * gebaute Konfiguration einzuspeisen. Das externe Verhalten im Deployment bleibt unverändert.
     */
    AnonConfig loadConfig() {
        return AnonConfig.fromEnv();
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        AnonConfig config = loadConfig();

        // 1. Authentifizierung (Requirement 7.1–7.4)
        if (!isAuthorized(event, config)) {
            context.getLogger().log("WARN: Unauthorized request to anonymized feed");
            return plainText(401, "Unauthorized");
        }

        // 2. Quellfeed-Konfiguration validieren (Requirement 1.4 → 500)
        URI sourceFeed = config.sourceFeed();
        if (sourceFeed == null) {
            context.getLogger().log("ERROR: ANON_SOURCE_FEED fehlt oder ist keine gültige http(s)-URL");
            return plainText(500, "Konfiguration des Quellfeeds (ANON_SOURCE_FEED) fehlt oder ist ungültig");
        }

        // 3. Abruf + Transformation; Netzwerk-/Parsefehler → 502 (Requirement 1.5)
        try {
            Calendar source = fetchAndParse(sourceFeed);
            Calendar anonymized = anonymizer.anonymize(source, config, Instant.now());
            String body = serialize(anonymized);

            Map<String, String> headers = new HashMap<>();
            headers.put(CONTENT_TYPE_HEADER, CONTENT_TYPE_ICAL);

            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(200)
                    .withHeaders(headers)
                    .withBody(body)
                    .build();

        } catch (FeedUnavailableException e) {
            context.getLogger().log("ERROR: Outlook_Source_Feed nicht verarbeitbar: " + e.getMessage());
            return plainText(502, "Outlook_Source_Feed konnte nicht abgerufen oder gelesen werden");
        } catch (RuntimeException e) {
            // Fehlerisolation: keine Ausnahme an die Laufzeit weiterwerfen (Requirement 5.4).
            context.getLogger().log("ERROR: Unerwarteter Fehler bei der Anonymisierung: " + e);
            return plainText(502, "Outlook_Source_Feed konnte nicht verarbeitet werden");
        }
    }

    /**
     * Prüft die Autorisierung über den {@code token}-Query-Parameter.
     *
     * <p>Regeln (Requirement 7.1–7.4):
     * <ul>
     *   <li>Kein konfiguriertes Secret → offener Zugriff (7.3).</li>
     *   <li>{@code token} mehrfach vorhanden → 401 (7.4). Da
     *       {@link APIGatewayV2HTTPEvent#getQueryStringParameters()} Duplikate zu einem Wert
     *       zusammenfasst, wird der Roh-Query-String ausgewertet, um Mehrfachvorkommen zu
     *       erkennen.</li>
     *   <li>{@code token} fehlt/leer/≠ Secret → 401 (7.1, 7.2); zeichengenauer Vergleich.</li>
     * </ul>
     */
    boolean isAuthorized(APIGatewayV2HTTPEvent event, AnonConfig config) {
        if (config.apiSecret().isEmpty()) {
            // Kein Secret konfiguriert = offener Zugriff (Requirement 7.3)
            return true;
        }
        String secret = config.apiSecret().get();

        // Mehrfaches token → 401 (Requirement 7.4)
        if (countTokenOccurrences(event.getRawQueryString()) > 1) {
            return false;
        }

        Map<String, String> params = event.getQueryStringParameters();
        if (params == null) {
            return false;
        }
        String token = params.get(TOKEN_PARAM);
        if (token == null || token.isEmpty()) {
            return false;
        }
        // Zeichengenauer, groß-/kleinschreibungsrelevanter Vergleich (Requirement 7.1)
        return secret.equals(token);
    }

    /**
     * Zählt die Vorkommen des {@code token}-Parameters im Roh-Query-String. Robust gegenüber
     * fehlendem/leerem Query-String; wertet nur die Parameternamen aus (nicht deren Werte).
     */
    private int countTokenOccurrences(String rawQueryString) {
        if (rawQueryString == null || rawQueryString.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String pair : rawQueryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = (eq >= 0) ? pair.substring(0, eq) : pair;
            if (TOKEN_PARAM.equals(name)) {
                count++;
            }
        }
        return count;
    }

    /** Connect- und Read-Timeout für den Abruf des Quellfeeds (Requirement 1.5). */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Lädt den Quellfeed via HTTP und parst ihn zu einem ical4j-{@link Calendar}.
     *
     * <p>Der {@link HttpClient} nutzt einen {@code connectTimeout} von 10 s, die
     * {@link HttpRequest} zusätzlich einen Request-{@code timeout} von 10 s als Read-Timeout.
     * Der Antwort-Body wird – analog {@link IcalParser} – über einen ical4j
     * {@link CalendarBuilder} geparst. Netzwerk-/Parsefehler ({@link IOException},
     * {@link InterruptedException}, {@link ParserException}) sowie HTTP-Fehlerstatus werden als
     * {@link FeedUnavailableException} gekapselt, sodass der Handler sie auf 502 abbildet
     * (Requirement 1.5).
     *
     * @param sourceFeed validierte http/https-URL des Outlook_Source_Feed
     * @return geparster Quell-Kalender
     * @throws FeedUnavailableException wenn der Feed nicht abrufbar oder nicht parsebar ist
     */
    Calendar fetchAndParse(URI sourceFeed) throws FeedUnavailableException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(FETCH_TIMEOUT)
                .build();

        HttpRequest request = HttpRequest.newBuilder(sourceFeed)
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new FeedUnavailableException(
                        "Outlook_Source_Feed antwortete mit HTTP-Status " + status, null);
            }

            try (InputStream body = response.body()) {
                return new CalendarBuilder().build(body);
            }
        } catch (IOException | ParserException e) {
            throw new FeedUnavailableException(
                    "Outlook_Source_Feed konnte nicht abgerufen oder geparst werden", e);
        } catch (InterruptedException e) {
            // Interrupt-Status wiederherstellen, bevor als 502 behandelt wird.
            Thread.currentThread().interrupt();
            throw new FeedUnavailableException(
                    "Abruf des Outlook_Source_Feed wurde unterbrochen", e);
        }
    }

    /**
     * Serialisiert einen {@link Calendar} als RFC-5545-konformen iCal-Text (UTF-8).
     */
    private String serialize(Calendar calendar) throws FeedUnavailableException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            new CalendarOutputter().output(calendar, out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FeedUnavailableException("Serialisierung des Feeds fehlgeschlagen", e);
        }
    }

    /** Baut eine knappe Klartext-Fehlerantwort ohne iCal (Design: Error Handling). */
    private APIGatewayV2HTTPResponse plainText(int statusCode, String message) {
        Map<String, String> headers = new HashMap<>();
        headers.put(CONTENT_TYPE_HEADER, CONTENT_TYPE_TEXT);
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(headers)
                .withBody(message)
                .build();
    }
}
