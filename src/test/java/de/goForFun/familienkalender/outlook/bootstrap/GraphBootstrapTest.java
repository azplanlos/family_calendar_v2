package de.goForFun.familienkalender.outlook.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.goForFun.familienkalender.outlook.FakeRefreshTokenStore;
import de.goForFun.familienkalender.outlook.GraphErrorCategory;
import de.goForFun.familienkalender.outlook.GraphException;
import de.goForFun.familienkalender.outlook.MutableClock;
import de.goForFun.familienkalender.outlook.ScriptedGraphHttp;
import de.goForFun.familienkalender.outlook.ScriptedGraphHttp.Recorded;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit-Tests für {@link GraphBootstrap#run}. Validates: Requirements 8.4, 8.5, 8.10 bis 8.15. */
class GraphBootstrapTest {

    private static final String PARAM = "/familienkalender/graph/refresh-token";
    private static final String ACCESS = "ACCESS-SECRET-aaa";
    private static final String REFRESH = "REFRESH-SECRET-bbb";
    private static final String[] ARGS = {"--client-id", "client-123", "--param", PARAM};

    private static final String DEVICE_OK = "{\"device_code\":\"DEVCODE-SECRET\",\"user_code\":\"ABCD-1234\","
            + "\"verification_uri\":\"https://login.microsoft.com/device\",\"expires_in\":900,\"interval\":5}";
    private static final String TOKEN_OK = "{\"access_token\":\"" + ACCESS + "\",\"refresh_token\":\""
            + REFRESH + "\",\"scope\":\"Calendars.Read\",\"expires_in\":3600}";
    private static final String PENDING = "{\"error\":\"authorization_pending\"}";

    private MutableClock clock;
    private ScriptedGraphHttp http;
    private FakeRefreshTokenStore store;
    private List<Duration> sleeps;
    private ByteArrayOutputStream outBytes;
    private ByteArrayOutputStream errBytes;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2025-06-01T10:00:00Z"));
        http = new ScriptedGraphHttp();
        store = new FakeRefreshTokenStore();
        sleeps = new ArrayList<>();
        outBytes = new ByteArrayOutputStream();
        errBytes = new ByteArrayOutputStream();
    }

    private int run() {
        return GraphBootstrap.run(ARGS, Map.of(), http, store, clock,
                d -> {
                    sleeps.add(d);
                    clock.advance(d);
                },
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return outBytes.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private void assertNoSecrets() {
        for (String s : new String[] {out(), err()}) {
            assertFalse(s.contains(ACCESS), s);
            assertFalse(s.contains(REFRESH), s);
            assertFalse(s.contains("DEVCODE-SECRET"), s);
        }
    }

    @Test
    void success_probeBeforeWrite_storesTrimmedRefreshToken_andReportsParameterName() {
        http.enqueue(200, DEVICE_OK).enqueue(400, PENDING).enqueue(200, TOKEN_OK).enqueue(200, "{\"value\":[]}");

        int exit = run();

        assertEquals(0, exit);
        List<Recorded> calls = http.calls();
        assertEquals(4, calls.size());
        assertTrue(calls.get(0).uri().toString().endsWith("/devicecode"));
        assertTrue(calls.get(1).uri().toString().endsWith("/token"));
        assertTrue(calls.get(2).uri().toString().endsWith("/token"));
        Recorded probe = calls.get(3);
        assertEquals("graph.microsoft.com", probe.uri().getHost());
        assertTrue(probe.uri().getPath().endsWith("/me/calendarView"));
        assertEquals("GET", probe.call().method());
        assertEquals("Bearer " + ACCESS, probe.call().headers().get("Authorization"));
        assertEquals(Duration.ofSeconds(10), probe.timeout());

        assertEquals(1, store.writes().size());
        assertEquals(PARAM, store.writes().get(0).parameterName());
        assertEquals(REFRESH, store.writes().get(0).value());
        assertEquals(REFRESH, store.stored(PARAM));
        assertTrue(out().contains(PARAM));
        assertEquals("", err());
        assertNoSecrets();
    }

    @Test
    void console_showsVerificationUrlAndUserCode_notDeviceCode() {
        http.enqueue(200, DEVICE_OK).enqueue(200, TOKEN_OK).enqueue(200, "{}");

        assertEquals(0, run());

        assertTrue(out().contains("https://login.microsoft.com/device"));
        assertTrue(out().contains("ABCD-1234"));
        assertFalse(out().contains("DEVCODE-SECRET"));
    }

    @Test
    void polling_usesIntervalAndSlowDownIncrement() {
        http.enqueue(200, DEVICE_OK).enqueue(400, PENDING).enqueue(400, "{\"error\":\"slow_down\"}")
                .enqueue(200, TOKEN_OK).enqueue(200, "{}");

        assertEquals(0, run());

        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(10)), sleeps);
    }

    @Test
    void probeHttpError_noSsmWrite_exitNonZero() {
        http.enqueue(200, DEVICE_OK).enqueue(200, TOKEN_OK)
                .enqueue(403, "{\"error\":{\"code\":\"ErrorAccessDenied\",\"message\":\"Mailbox Secret\"}}");

        assertNotEquals(0, run());

        assertTrue(store.writes().isEmpty());
        assertNull(store.stored(PARAM));
        assertTrue(err().contains("403"));
        assertTrue(err().contains("ErrorAccessDenied"));
        assertFalse(err().contains("Mailbox Secret"));
        assertFalse(out().contains("gespeichert"));
        assertNoSecrets();
    }

    @Test
    void probeNetworkError_noSsmWrite() {
        http.enqueue(200, DEVICE_OK).enqueue(200, TOKEN_OK)
                .enqueue(new GraphException(GraphErrorCategory.NETWORK));

        assertNotEquals(0, run());

        assertTrue(store.writes().isEmpty());
        assertTrue(err().contains(GraphErrorCategory.NETWORK.displayName()));
        assertNoSecrets();
    }

    @Test
    void probeTimeout_noSsmWrite() {
        http.enqueue(200, DEVICE_OK).enqueue(200, TOKEN_OK)
                .enqueue(new GraphException(GraphErrorCategory.TIMEOUT));

        assertNotEquals(0, run());

        assertTrue(store.writes().isEmpty());
        assertTrue(err().contains(GraphErrorCategory.TIMEOUT.displayName()));
    }

    @Test
    void ssmWriteFailure_exitNonZero_namesParameter_noSuccessMessage() {
        http.enqueue(200, DEVICE_OK).enqueue(200, TOKEN_OK).enqueue(200, "{}");
        store.failWrites(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));

        assertNotEquals(0, run());

        assertEquals(1, http.calls().stream().filter(c -> c.uri().getHost().equals("graph.microsoft.com")).count());
        assertTrue(err().contains(PARAM));
        assertFalse(out().contains("gespeichert"));
        assertNull(store.stored(PARAM));
        assertNoSecrets();
    }

    @Test
    void ssmWriteRuntimeFailure_exitNonZero() {
        http.enqueue(200, DEVICE_OK).enqueue(200, TOKEN_OK).enqueue(200, "{}");
        // RuntimeException aus dem Store darf nicht nach außen dringen.
        int exit = GraphBootstrap.run(ARGS, Map.of(), http,
                new de.goForFun.familienkalender.outlook.RefreshTokenStore() {
                    @Override
                    public String read(String p, Duration t) throws GraphException {
                        throw new GraphException(GraphErrorCategory.SSM_UNAVAILABLE);
                    }

                    @Override
                    public void write(String p, String v, Duration t) {
                        throw new IllegalStateException(v);
                    }
                }, clock, d -> clock.advance(d),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertNotEquals(0, exit);
        assertTrue(err().contains(PARAM));
        assertNoSecrets();
    }

    @Test
    void deviceCodeRequestNetworkError_noFurtherCalls_noWrite() {
        http.enqueue(new GraphException(GraphErrorCategory.NETWORK));

        assertNotEquals(0, run());

        assertEquals(1, http.callCount());
        assertTrue(store.writes().isEmpty());
        assertTrue(err().contains(GraphErrorCategory.NETWORK.displayName()));
    }

    @Test
    void deviceCodeRequestTimeout_noWrite() {
        http.enqueue(new GraphException(GraphErrorCategory.TIMEOUT));

        assertNotEquals(0, run());

        assertTrue(store.writes().isEmpty());
        assertTrue(err().contains(GraphErrorCategory.TIMEOUT.displayName()));
    }

    @Test
    void deviceCodeResponseUnparsable_noWrite() {
        http.enqueue(200, "<html>not json</html>");

        assertNotEquals(0, run());

        assertEquals(1, http.callCount());
        assertTrue(store.writes().isEmpty());
        assertTrue(err().contains("nicht parsebar"));
    }

    @Test
    void tokenResponseUnparsable_noProbe_noWrite() {
        http.enqueue(200, DEVICE_OK).enqueue(200, "garbage{");

        assertNotEquals(0, run());

        assertEquals(2, http.callCount());
        assertTrue(store.writes().isEmpty());
        assertTrue(err().contains("nicht parsebar"));
    }

    @Test
    void pollingNetworkError_noWrite() {
        http.enqueue(200, DEVICE_OK).enqueue(new GraphException(GraphErrorCategory.NETWORK));

        assertNotEquals(0, run());

        assertTrue(store.writes().isEmpty());
        assertEquals(2, http.callCount());
    }

    @Test
    void pollingOtherErrorCode_abortsWithoutWrite() {
        http.enqueue(200, DEVICE_OK).enqueue(400, "{\"error\":\"authorization_declined\"}");

        assertNotEquals(0, run());

        assertEquals(2, http.callCount());
        assertTrue(store.writes().isEmpty());
        assertTrue(err().contains("authorization_declined"));
    }

    @Test
    void deviceCodeExpires_abortsWithoutWrite() {
        String shortLived = DEVICE_OK.replace("\"expires_in\":900", "\"expires_in\":12");
        http.enqueue(200, shortLived).enqueue(400, PENDING).enqueue(400, PENDING).enqueue(400, PENDING);

        assertNotEquals(0, run());

        assertTrue(store.writes().isEmpty());
        assertTrue(err().contains("abgelaufen"));
    }

    @Test
    void missingRefreshToken_noProbe_noWrite() {
        http.enqueue(200, DEVICE_OK)
                .enqueue(200, "{\"access_token\":\"" + ACCESS + "\",\"scope\":\"Calendars.Read\"}");

        assertNotEquals(0, run());

        assertEquals(2, http.callCount());
        assertTrue(store.writes().isEmpty());
        assertNoSecrets();
    }

    @Test
    void scopeWithoutCalendarsRead_noProbe_noWrite() {
        http.enqueue(200, DEVICE_OK).enqueue(200,
                "{\"access_token\":\"" + ACCESS + "\",\"refresh_token\":\"" + REFRESH
                        + "\",\"scope\":\"User.Read\"}");

        assertNotEquals(0, run());

        assertEquals(2, http.callCount());
        assertTrue(store.writes().isEmpty());
        assertNoSecrets();
    }

    @Test
    void realTokenResponseListsOnlyCalendarsRead_isAccepted() {
        // Spike-Fakt: die echte Antwort listet nur Calendars.Read (offline_access fehlt).
        http.enqueue(200, DEVICE_OK).enqueue(200, TOKEN_OK).enqueue(200, "{}");

        assertEquals(0, run());
        assertEquals(1, store.writes().size());
    }

    @Test
    void missingConfig_exitNonZero_noNetwork() {
        int exit = GraphBootstrap.run(new String[0], Map.of(), http, store, clock, d -> { },
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        assertNotEquals(0, exit);
        assertEquals(0, http.callCount());
        assertTrue(store.writes().isEmpty());
        assertFalse(err().isEmpty());
    }
}
