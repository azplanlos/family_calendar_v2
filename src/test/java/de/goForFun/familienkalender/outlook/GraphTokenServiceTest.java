package de.goForFun.familienkalender.outlook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GraphTokenServiceTest {

    private static final String PARAM = "/fam/token";
    private static final GraphConfig CFG = new GraphConfig("client-1", PARAM);

    private final MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
    private final ScriptedGraphHttp http = new ScriptedGraphHttp(clock);
    private final RecordingLog log = new RecordingLog();

    private String acquire(FakeRefreshTokenStore store) throws GraphException {
        return new GraphTokenService(CFG, store, http, log)
                .acquire(TimeBudget.start(clock, Duration.ofSeconds(15)));
    }

    private GraphErrorCategory failure(FakeRefreshTokenStore store) {
        return assertThrows(GraphException.class, () -> acquire(store)).category();
    }

    @Test
    void success_sendsExactlyOnePostWithRequiredFields() throws Exception {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "  old-rt \n");
        http.enqueue(200, "{\"access_token\":\"AT\"}");
        assertEquals("AT", acquire(store));
        assertEquals(1, http.callCount());
        HttpCall call = http.calls().get(0).call();
        assertEquals("POST", call.method());
        assertEquals("client_id=client-1&grant_type=refresh_token&refresh_token=old-rt"
                + "&scope=Calendars.Read+offline_access", call.formOrNullBody());
        assertFalse(call.formOrNullBody().contains("client_secret"));
        assertTrue(store.writes().isEmpty());
    }

    @Test
    void emptyAfterTrim_ssmUnavailable_noTokenRequest() {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "   ");
        assertEquals(GraphErrorCategory.SSM_UNAVAILABLE, failure(store));
        assertEquals(0, http.callCount());
    }

    @Test
    void readFailure_noTokenRequest() {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore()
                .failReads(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
        assertEquals(GraphErrorCategory.SSM_UNAVAILABLE, failure(store));
        assertEquals(0, http.callCount());
    }

    @Test
    void rotatedToken_isWrittenBeforeReturn() throws Exception {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
        http.enqueue(200, "{\"access_token\":\"AT\",\"refresh_token\":\"new\"}");
        acquire(store);
        assertEquals(1, store.writes().size());
        assertEquals("new", store.stored(PARAM));
    }

    @Test
    void sameOrEmptyRefreshToken_notWritten() throws Exception {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
        http.enqueue(200, "{\"access_token\":\"AT\",\"refresh_token\":\"old\"}");
        http.enqueue(200, "{\"access_token\":\"AT\",\"refresh_token\":\"\"}");
        acquire(store);
        acquire(store);
        assertTrue(store.writes().isEmpty());
    }

    @Test
    void writeFailure_onlyWarnsWithoutTokenValues() throws Exception {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old-secret")
                .failWrites(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
        http.enqueue(200, "{\"access_token\":\"AT-secret\",\"refresh_token\":\"new-secret\"}");
        assertEquals("AT-secret", acquire(store));
        assertEquals(1, log.warnings().size());
        String line = log.warnings().get(0);
        assertTrue(line.contains(PARAM));
        assertFalse(line.contains("secret"));
    }

    @Test
    void status400_401_invalidGrant_areRefreshTokenInvalid_noWrite() {
        for (String[] c : new String[][] {{"400", "{}"}, {"401", ""}, {"500", "{\"error\":\"invalid_grant\"}"}}) {
            FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
            http.enqueue(Integer.parseInt(c[0]), c[1]);
            assertEquals(GraphErrorCategory.REFRESH_TOKEN_INVALID, failure(store));
            assertTrue(store.writes().isEmpty());
        }
    }

    @Test
    void otherStatus_isTokenExchangeFailedWithStatus() {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
        http.enqueue(503, "oops");
        GraphException e = assertThrows(GraphException.class, () -> acquire(store));
        assertEquals(GraphErrorCategory.TOKEN_EXCHANGE_FAILED, e.category());
        assertEquals(503, e.httpStatus().getAsInt());
    }

    @Test
    void status200_withoutAccessToken_orUnparsable_noWrite() {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
        http.enqueue(200, "{\"refresh_token\":\"new\"}");
        http.enqueue(200, "{\"access_token\":\"\",\"refresh_token\":\"new\"}");
        http.enqueue(200, "not json");
        assertEquals(GraphErrorCategory.TOKEN_EXCHANGE_FAILED, failure(store));
        assertEquals(GraphErrorCategory.TOKEN_EXCHANGE_FAILED, failure(store));
        assertEquals(GraphErrorCategory.RESPONSE_UNPARSABLE, failure(store));
        assertTrue(store.writes().isEmpty());
    }

    @Test
    void networkAndTimeout_propagate_noRetry() {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
        http.enqueue(new GraphException(GraphErrorCategory.NETWORK));
        assertEquals(GraphErrorCategory.NETWORK, failure(store));
        assertEquals(1, http.callCount());
        assertTrue(store.writes().isEmpty());
    }

    @Test
    void twoRuns_twoExchanges() throws Exception {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
        http.enqueue(200, "{\"access_token\":\"A1\"}");
        http.enqueue(200, "{\"access_token\":\"A2\"}");
        assertEquals("A1", acquire(store));
        assertEquals("A2", acquire(store));
        assertEquals(2, http.callCount());
    }

    @Test
    void request_usesTokenUrlAndFormHeaders() throws Exception {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
        http.enqueue(200, "{\"access_token\":\"AT\"}");
        acquire(store);
        HttpCall call = http.calls().get(0).call();
        assertEquals(java.net.URI.create(GraphEndpoints.TOKEN_URL), call.uri());
        assertTrue(GraphEndpoints.isLoginUri(call.uri()));
        assertEquals("application/x-www-form-urlencoded", call.headers().get("Content-Type"));
        assertEquals("application/json", call.headers().get("Accept"));
        assertFalse(call.headers().containsKey("Authorization"));
        assertEquals(PARAM, store.reads().get(0).parameterName());
    }

    @Test
    void secondRun_readsTokenRotatedByFirstRun() throws Exception {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "rt-1");
        http.enqueue(200, "{\"access_token\":\"A1\",\"refresh_token\":\"rt-2\"}");
        http.enqueue(200, "{\"access_token\":\"A2\"}");
        acquire(store);
        acquire(store);
        assertEquals(2, http.callCount());
        assertTrue(http.calls().get(0).call().formOrNullBody().contains("refresh_token=rt-1&"));
        assertTrue(http.calls().get(1).call().formOrNullBody().contains("refresh_token=rt-2&"));
        assertEquals(2, store.reads().size());
    }

    @Test
    void expiredBudget_failsBeforeAnyIo() {
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "old");
        TimeBudget budget = TimeBudget.start(clock, Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        GraphException e = assertThrows(GraphException.class,
                () -> new GraphTokenService(CFG, store, http, log).acquire(budget));
        assertEquals(GraphErrorCategory.BUDGET, e.category());
        assertTrue(store.reads().isEmpty());
    }

    @Test
    void shouldPersist_rules() {
        assertTrue(GraphTokenService.shouldPersist("a", "b"));
        assertFalse(GraphTokenService.shouldPersist("a", "a"));
        assertFalse(GraphTokenService.shouldPersist(" a ", "a"));
        assertFalse(GraphTokenService.shouldPersist("a", " "));
        assertFalse(GraphTokenService.shouldPersist("a", null));
        assertNull(null);
    }
}
