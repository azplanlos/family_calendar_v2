package de.goForFun.familienkalender.anonfeed;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import net.fortuna.ical4j.model.Calendar;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beispiel-Tests für das HTTP-Verhalten von {@link ServeAnonymizedFeed} (Task 4.3).
 *
 * <p>Der Handler wird mit einem <em>injizierten</em> Fetch getestet: Die Testklasse überschreibt
 * den paket-sichtbaren Seam {@link ServeAnonymizedFeed#loadConfig()} (um ohne echte
 * Umgebungsvariablen eine {@link AnonConfig} per {@link AnonConfig#fromMap(Map)} einzuspeisen) und
 * {@link ServeAnonymizedFeed#fetchAndParse(URI)} (um ohne echten Netzwerkzugriff entweder die reale
 * Fixture als geparsten Quell-Kalender zu liefern oder einen Fehler zu simulieren).
 *
 * <p>Abgedeckte Fälle:
 * <ul>
 *   <li>200 + {@code Content-Type: text/calendar; charset=utf-8} bei erfolgreicher Auslieferung
 *       (Requirement 1.2).</li>
 *   <li>500 bei fehlendem/ungültigem {@code ANON_SOURCE_FEED} (Requirement 1.4).</li>
 *   <li>502 bei Timeout/unparsebarem Quellfeed (Requirement 1.5).</li>
 *   <li>Auth: Secret gesetzt → korrektes {@code token} 200; fehlend/leer/falsch/mehrfach 401
 *       (Requirements 7.1, 7.2, 7.4); kein Secret → 200 ohne {@code token} (Requirement 7.3).</li>
 * </ul>
 *
 * <p>Validates: Requirements 1.2, 1.4, 1.5, 7.1, 7.2, 7.3, 7.4.
 */
class ServeAnonymizedFeedTest {

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String CONTENT_TYPE_ICAL = "text/calendar; charset=utf-8";
    private static final String SECRET = "s3cr3t";

    // ==================================================================
    // Test-Handler mit injiziertem Config- und Fetch-Seam
    // ==================================================================

    /**
     * Handler-Variante, die {@link #loadConfig()} und {@link #fetchAndParse(URI)} überschreibt.
     * Je nach {@code fetchMode} liefert der Fetch die Fixture (Erfolg) oder wirft (502).
     */
    private static final class TestHandler extends ServeAnonymizedFeed {

        enum FetchMode { FIXTURE, THROW_UNAVAILABLE }

        private final AnonConfig injectedConfig;
        private final FetchMode fetchMode;

        TestHandler(AnonConfig injectedConfig, FetchMode fetchMode) {
            this.injectedConfig = injectedConfig;
            this.fetchMode = fetchMode;
        }

        @Override
        AnonConfig loadConfig() {
            return injectedConfig;
        }

        @Override
        Calendar fetchAndParse(URI sourceFeed) throws FeedUnavailableException {
            if (fetchMode == FetchMode.THROW_UNAVAILABLE) {
                throw new FeedUnavailableException("Simulierter Timeout/Parsefehler", null);
            }
            return CalendarArbitraries.loadFixture();
        }
    }

    /** Baut eine Config über {@link AnonConfig#fromMap(Map)} aus den gegebenen Schlüssel/Wert-Paaren. */
    private static AnonConfig config(String... keyValues) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            env.put(keyValues[i], keyValues[i + 1]);
        }
        return AnonConfig.fromMap(env);
    }

    private static APIGatewayV2HTTPEvent eventWithToken(String rawQuery, Map<String, String> queryParams) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawQueryString(rawQuery)
                .withQueryStringParameters(queryParams)
                .build();
    }

    private static APIGatewayV2HTTPEvent eventWithoutQuery() {
        return APIGatewayV2HTTPEvent.builder().build();
    }

    // ==================================================================
    // Erfolgs- und Fehlerstatus (1.2, 1.4, 1.5)
    // ==================================================================

    @Nested
    class StatusMapping {

        @Test
        void erfolg_liefert200UndIcalContentType() {
            // Kein Secret → offener Zugriff; gültiger Quellfeed; Fetch liefert Fixture.
            TestHandler handler = new TestHandler(
                    config("ANON_SOURCE_FEED", "https://example.com/source.ics"),
                    TestHandler.FetchMode.FIXTURE);

            APIGatewayV2HTTPResponse response =
                    handler.handleRequest(eventWithoutQuery(), new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getHeaders()).containsEntry(CONTENT_TYPE_HEADER, CONTENT_TYPE_ICAL);
            // Body ist ein iCal-Feed.
            assertThat(response.getBody()).contains("BEGIN:VCALENDAR").contains("END:VCALENDAR");
        }

        @Test
        void fehlenderSourceFeed_liefert500() {
            // ANON_SOURCE_FEED fehlt → config.sourceFeed() == null → 500 (Requirement 1.4).
            TestHandler handler = new TestHandler(config(), TestHandler.FetchMode.FIXTURE);

            APIGatewayV2HTTPResponse response =
                    handler.handleRequest(eventWithoutQuery(), new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(500);
            assertThat(response.getBody()).contains("ANON_SOURCE_FEED");
        }

        @Test
        void ungueltigerSourceFeed_liefert500() {
            // Nicht-http(s)-URL → sourceFeed null → 500 (Requirement 1.4).
            TestHandler handler = new TestHandler(
                    config("ANON_SOURCE_FEED", "ftp://example.com/feed.ics"),
                    TestHandler.FetchMode.FIXTURE);

            APIGatewayV2HTTPResponse response =
                    handler.handleRequest(eventWithoutQuery(), new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(500);
        }

        @Test
        void nichtVerarbeitbarerQuellfeed_liefert502() {
            // Fetch wirft FeedUnavailableException (Timeout/unparsebar) → 502 (Requirement 1.5).
            TestHandler handler = new TestHandler(
                    config("ANON_SOURCE_FEED", "https://example.com/source.ics"),
                    TestHandler.FetchMode.THROW_UNAVAILABLE);

            APIGatewayV2HTTPResponse response =
                    handler.handleRequest(eventWithoutQuery(), new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(502);
            assertThat(response.getBody()).doesNotContain("BEGIN:VCALENDAR");
        }
    }

    // ==================================================================
    // Authentifizierung (7.1, 7.2, 7.3, 7.4)
    // ==================================================================

    @Nested
    class Authentication {

        private TestHandler handlerWithSecret() {
            return new TestHandler(
                    config("ANON_SOURCE_FEED", "https://example.com/source.ics",
                            "ANON_API_SECRET", SECRET),
                    TestHandler.FetchMode.FIXTURE);
        }

        @Test
        void secretGesetzt_korrektesToken_liefert200() {
            APIGatewayV2HTTPResponse response = handlerWithSecret().handleRequest(
                    eventWithToken("token=" + SECRET, Map.of("token", SECRET)),
                    new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getHeaders()).containsEntry(CONTENT_TYPE_HEADER, CONTENT_TYPE_ICAL);
        }

        @Test
        void secretGesetzt_tokenFehlt_liefert401() {
            APIGatewayV2HTTPResponse response = handlerWithSecret().handleRequest(
                    eventWithoutQuery(), new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(401);
            assertThat(response.getBody()).doesNotContain("BEGIN:VCALENDAR");
        }

        @Test
        void secretGesetzt_tokenLeer_liefert401() {
            Map<String, String> params = new HashMap<>();
            params.put("token", "");
            APIGatewayV2HTTPResponse response = handlerWithSecret().handleRequest(
                    eventWithToken("token=", params), new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(401);
        }

        @Test
        void secretGesetzt_tokenFalsch_liefert401() {
            APIGatewayV2HTTPResponse response = handlerWithSecret().handleRequest(
                    eventWithToken("token=falsch", Map.of("token", "falsch")),
                    new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(401);
        }

        @Test
        void secretGesetzt_tokenFalscheGrossschreibung_liefert401() {
            // Zeichengenauer Vergleich (Requirement 7.1): "S3CR3T" != "s3cr3t".
            APIGatewayV2HTTPResponse response = handlerWithSecret().handleRequest(
                    eventWithToken("token=S3CR3T", Map.of("token", "S3CR3T")),
                    new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(401);
        }

        @Test
        void secretGesetzt_tokenMehrfach_liefert401() {
            // Mehrfaches token → 401 (Requirement 7.4). getQueryStringParameters fasst Duplikate
            // zu einem Wert zusammen; der Roh-Query-String enthält beide Vorkommen.
            APIGatewayV2HTTPResponse response = handlerWithSecret().handleRequest(
                    eventWithToken("token=" + SECRET + "&token=" + SECRET, Map.of("token", SECRET)),
                    new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(401);
        }

        @Test
        void keinSecret_ohneToken_liefert200() {
            // Kein Secret konfiguriert → offener Zugriff (Requirement 7.3).
            TestHandler handler = new TestHandler(
                    config("ANON_SOURCE_FEED", "https://example.com/source.ics"),
                    TestHandler.FetchMode.FIXTURE);

            APIGatewayV2HTTPResponse response =
                    handler.handleRequest(eventWithoutQuery(), new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getHeaders()).containsEntry(CONTENT_TYPE_HEADER, CONTENT_TYPE_ICAL);
        }

        @Test
        void keinSecret_mitBeliebigemToken_liefert200() {
            // Offener Zugriff ignoriert einen vorhandenen token-Parameter (Requirement 7.3).
            TestHandler handler = new TestHandler(
                    config("ANON_SOURCE_FEED", "https://example.com/source.ics"),
                    TestHandler.FetchMode.FIXTURE);

            APIGatewayV2HTTPResponse response = handler.handleRequest(
                    eventWithToken("token=irrelevant", Map.of("token", "irrelevant")),
                    new FakeContext());

            assertThat(response.getStatusCode()).isEqualTo(200);
        }
    }

    // ==================================================================
    // Minimaler Context-/Logger-Stub
    // ==================================================================

    /** Verwirft Log-Ausgaben; der Handler ruft nur {@link Context#getLogger()}. */
    private static final class FakeContext implements Context {

        private final LambdaLogger logger = new LambdaLogger() {
            @Override
            public void log(String message) {
                // bewusst leer
            }

            @Override
            public void log(byte[] message) {
                // bewusst leer
            }
        };

        @Override
        public String getAwsRequestId() {
            return "test-request-id";
        }

        @Override
        public String getLogGroupName() {
            return "test-log-group";
        }

        @Override
        public String getLogStreamName() {
            return "test-log-stream";
        }

        @Override
        public String getFunctionName() {
            return "ServeAnonymizedFeed";
        }

        @Override
        public String getFunctionVersion() {
            return "$LATEST";
        }

        @Override
        public String getInvokedFunctionArn() {
            return "arn:aws:lambda:eu-central-1:000000000000:function:ServeAnonymizedFeed";
        }

        @Override
        public CognitoIdentity getIdentity() {
            return null;
        }

        @Override
        public ClientContext getClientContext() {
            return null;
        }

        @Override
        public int getRemainingTimeInMillis() {
            return 30_000;
        }

        @Override
        public int getMemoryLimitInMB() {
            return 512;
        }

        @Override
        public LambdaLogger getLogger() {
            return logger;
        }
    }
}
