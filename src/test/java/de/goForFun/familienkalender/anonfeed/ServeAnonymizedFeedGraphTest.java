package de.goForFun.familienkalender.anonfeed;

import com.amazonaws.services.lambda.runtime.ClientContext;
import com.amazonaws.services.lambda.runtime.CognitoIdentity;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import de.goForFun.familienkalender.outlook.FakeGraphSource;
import de.goForFun.familienkalender.outlook.FakeIcsFetcher;
import de.goForFun.familienkalender.outlook.GraphConfig;
import de.goForFun.familienkalender.outlook.GraphErrorCategory;
import de.goForFun.familienkalender.outlook.GraphException;
import de.goForFun.familienkalender.outlook.IcsFetchException;
import de.goForFun.familienkalender.outlook.OutlookCalendarLoader;
import de.goForFun.familienkalender.outlook.OutlookLog;
import de.goForFun.familienkalender.outlook.OutlookSourceSelector;
import de.goForFun.familienkalender.outlook.RecordingLog;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-Tests für die Graph-Integration in {@link ServeAnonymizedFeed} (Task 10.2).
 *
 * <p>Die Seams {@code loadConfig()}, {@code loadGraphConfig()}, {@code now()} und
 * {@code createLoader(...)} werden überschrieben; als Loader dient der echte
 * {@link OutlookSourceSelector} mit {@link FakeGraphSource} und {@link FakeIcsFetcher}.
 *
 * <p>Validates: Requirements 1.2, 1.5, 1.6, 1.7, 2.4, 2.5, 7.6, 7.7.
 */
class ServeAnonymizedFeedGraphTest {

    private static final String ICAL_TYPE = "text/calendar; charset=utf-8";
    private static final String SECRET = "s3cr3t";
    private static final String ICS_URL = "https://example.com/source.ics";
    private static final GraphConfig CONFIGURED = new GraphConfig("client-id", "/param/token");
    private static final GraphConfig NOT_CONFIGURED = new GraphConfig("", "");

    /** Handler mit überschriebenen Seams und Aufrufzählern. */
    private static class Handler extends ServeAnonymizedFeed {
        final AnonConfig anonConfig;
        final Deque<GraphConfig> graphConfigs = new ArrayDeque<>();
        final FakeGraphSource graph = new FakeGraphSource();
        final FakeIcsFetcher ics = new FakeIcsFetcher();
        final RecordingLog log = new RecordingLog();
        int loadConfigCalls;
        int loadGraphConfigCalls;
        int createLoaderCalls;

        Handler(AnonConfig anonConfig, GraphConfig... configs) {
            this.anonConfig = anonConfig;
            graphConfigs.addAll(List.of(configs));
            graph.returning(CalendarArbitraries.loadFixture());
            ics.returning(CalendarArbitraries.loadFixture());
        }

        @Override
        AnonConfig loadConfig() {
            loadConfigCalls++;
            return anonConfig;
        }

        @Override
        GraphConfig loadGraphConfig() {
            loadGraphConfigCalls++;
            return graphConfigs.size() > 1 ? graphConfigs.poll() : graphConfigs.peek();
        }

        @Override
        Instant now() {
            return Instant.parse("2025-06-10T08:00:00Z");
        }

        @Override
        OutlookCalendarLoader createLoader(AnonConfig cfg, GraphConfig graphCfg, OutlookLog unused) {
            createLoaderCalls++;
            return new OutlookSourceSelector(graphCfg, () -> graph, cfg.sourceFeed(), ics, log);
        }
    }

    private static AnonConfig config(String... keyValues) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            env.put(keyValues[i], keyValues[i + 1]);
        }
        return AnonConfig.fromMap(env);
    }

    private static APIGatewayV2HTTPEvent event() {
        return APIGatewayV2HTTPEvent.builder().build();
    }

    private static APIGatewayV2HTTPEvent eventWithToken(String token) {
        return APIGatewayV2HTTPEvent.builder()
                .withRawQueryString("token=" + token)
                .withQueryStringParameters(Map.of("token", token))
                .build();
    }

    private static APIGatewayV2HTTPResponse call(Handler h) {
        return h.handleRequest(event(), new FakeContext());
    }

    @Test
    void graphErfolg_ohneSourceFeed_liefert200() {
        Handler h = new Handler(config(), CONFIGURED);

        APIGatewayV2HTTPResponse response = call(h);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(response.getBody()).contains("BEGIN:VCALENDAR");
        assertThat(h.graph.callCount()).isEqualTo(1);
        assertThat(h.ics.callCount()).isZero();
    }

    @Test
    void weder_graphNochQuelle_liefert500() {
        Handler h = new Handler(config(), NOT_CONFIGURED);

        APIGatewayV2HTTPResponse response = call(h);

        assertThat(response.getStatusCode()).isEqualTo(500);
        assertThat(response.getBody()).contains("ANON_SOURCE_FEED");
        assertThat(h.graph.callCount()).isZero();
        assertThat(h.ics.callCount()).isZero();
    }

    @Test
    void graphFehler_ohneFallbackUrl_liefert502() {
        Handler h = new Handler(config(), CONFIGURED);
        h.graph.failingWith(new GraphException(GraphErrorCategory.TIMEOUT));

        APIGatewayV2HTTPResponse response = call(h);

        assertThat(response.getStatusCode()).isEqualTo(502);
        assertThat(response.getBody()).doesNotContain("BEGIN:VCALENDAR");
    }

    @Test
    void graphFehler_undIcsFehler_liefert502() {
        Handler h = new Handler(config("ANON_SOURCE_FEED", ICS_URL), CONFIGURED);
        h.graph.failingWith(new GraphException(GraphErrorCategory.NETWORK));
        h.ics.failingWith(new IcsFetchException("ICS kaputt"));

        APIGatewayV2HTTPResponse response = call(h);

        assertThat(response.getStatusCode()).isEqualTo(502);
        assertThat(h.graph.callCount()).isEqualTo(1);
        assertThat(h.ics.callCount()).isEqualTo(1);
    }

    @Test
    void graphFehler_mitIcsFallback_liefert200AusIcs() {
        Handler h = new Handler(config("ANON_SOURCE_FEED", ICS_URL), CONFIGURED);
        h.graph.failingWith(new GraphException(GraphErrorCategory.GRAPH_HTTP, 500, null));

        APIGatewayV2HTTPResponse response = call(h);

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(h.ics.calls()).containsExactly(URI.create(ICS_URL));
    }

    @Test
    void icsFehler_ohneGraph_liefert502() {
        Handler h = new Handler(config("ANON_SOURCE_FEED", ICS_URL), NOT_CONFIGURED);
        h.ics.failingWith(new IcsFetchException("ICS kaputt"));

        assertThat(call(h).getStatusCode()).isEqualTo(502);
        assertThat(h.graph.callCount()).isZero();
    }

    @Test
    void falschesToken_liefert401_ohneJedenWeiterenAufruf() {
        Handler h = new Handler(config("ANON_SOURCE_FEED", ICS_URL, "ANON_API_SECRET", SECRET),
                CONFIGURED);

        APIGatewayV2HTTPResponse response = h.handleRequest(eventWithToken("falsch"), new FakeContext());

        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(h.loadGraphConfigCalls).isZero();
        assertThat(h.createLoaderCalls).isZero();
        assertThat(h.graph.callCount()).isZero();
        assertThat(h.ics.callCount()).isZero();
    }

    @Test
    void korrektesToken_liefert200() {
        Handler h = new Handler(config("ANON_API_SECRET", SECRET), CONFIGURED);

        APIGatewayV2HTTPResponse response = h.handleRequest(eventWithToken(SECRET), new FakeContext());

        assertThat(response.getStatusCode()).isEqualTo(200);
        assertThat(h.graph.callCount()).isEqualTo(1);
    }

    @Test
    void graphKonfigurationUndLoaderWerdenJeAnfrageFrischErzeugt() {
        // Erste Anfrage: Graph konfiguriert; zweite: nicht konfiguriert mit ICS-Fallback.
        Handler h = new Handler(config("ANON_SOURCE_FEED", ICS_URL), CONFIGURED, NOT_CONFIGURED);

        assertThat(call(h).getStatusCode()).isEqualTo(200);
        assertThat(h.graph.callCount()).isEqualTo(1);
        assertThat(h.ics.callCount()).isZero();

        assertThat(call(h).getStatusCode()).isEqualTo(200);
        assertThat(h.graph.callCount()).isEqualTo(1);
        assertThat(h.ics.callCount()).isEqualTo(1);

        assertThat(h.loadGraphConfigCalls).isEqualTo(2);
        assertThat(h.createLoaderCalls).isEqualTo(2);
    }

    @Test
    void fehlerzustandWirdNichtAufFolgeanfrageUebertragen() {
        Handler h = new Handler(config(), CONFIGURED);
        h.graph.failingWith(new GraphException(GraphErrorCategory.TIMEOUT));
        assertThat(call(h).getStatusCode()).isEqualTo(502);

        h.graph.returning(CalendarArbitraries.loadFixture());
        assertThat(call(h).getStatusCode()).isEqualTo(200);
    }

    @Test
    void beideQuellen_liefernIcalContentType() {
        Handler graph = new Handler(config(), CONFIGURED);
        Handler icsOnly = new Handler(config("ANON_SOURCE_FEED", ICS_URL), NOT_CONFIGURED);

        for (Handler h : List.of(graph, icsOnly)) {
            APIGatewayV2HTTPResponse response = call(h);
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getHeaders()).containsEntry("Content-Type", ICAL_TYPE);
        }
    }

    @Test
    void calName_wirdBeiGraphUndIcsGesetzt() {
        Handler graph = new Handler(config("ANON_PARTICIPANT", "Anna"), CONFIGURED);
        Handler icsOnly = new Handler(
                config("ANON_SOURCE_FEED", ICS_URL, "ANON_PARTICIPANT", "Anna"), NOT_CONFIGURED);

        for (Handler h : List.of(graph, icsOnly)) {
            assertThat(call(h).getBody()).contains("X-WR-CALNAME:Anna");
        }
    }

    @Test
    void ohneParticipant_keinCalName() {
        Handler h = new Handler(config(), CONFIGURED);

        assertThat(call(h).getBody()).doesNotContain("X-WR-CALNAME");
    }

    @Test
    void unerwarteteLoaderAusnahme_liefert502StattDurchzureichen() {
        Handler h = new Handler(config(), CONFIGURED) {
            @Override
            OutlookCalendarLoader createLoader(AnonConfig cfg, GraphConfig g, OutlookLog l) {
                return window -> {
                    throw new IllegalStateException("boom");
                };
            }
        };

        assertThat(call(h).getStatusCode()).isEqualTo(502);
    }

    /** Verwirft Log-Ausgaben. */
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

        @Override public String getAwsRequestId() { return "test-request-id"; }
        @Override public String getLogGroupName() { return "test-log-group"; }
        @Override public String getLogStreamName() { return "test-log-stream"; }
        @Override public String getFunctionName() { return "ServeAnonymizedFeed"; }
        @Override public String getFunctionVersion() { return "$LATEST"; }
        @Override public String getInvokedFunctionArn() { return "arn:aws:lambda:eu-central-1:0:function:f"; }
        @Override public CognitoIdentity getIdentity() { return null; }
        @Override public ClientContext getClientContext() { return null; }
        @Override public int getRemainingTimeInMillis() { return 30_000; }
        @Override public int getMemoryLimitInMB() { return 512; }
        @Override public LambdaLogger getLogger() { return logger; }
    }
}
