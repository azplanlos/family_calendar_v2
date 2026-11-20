package de.goForFun.familienkalender.outlook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: outlook-graph-source, Property 9: Token-Beschaffung klassifiziert alle Antworten korrekt
 * und lässt SSM bei Fehlern unverändert.
 *
 * <p><b>Validates: Requirements 3.4, 3.5, 3.9, 3.10, 3.11, 12.5, 12.6</b>
 */
class GraphTokenAcquirePropertyTest extends GraphArbitraries {

    private static final String PARAM = "/fam/token";
    private static final GraphConfig CFG = new GraphConfig("client-1", PARAM);

    enum Transport { RESPONSE, NETWORK, TIMEOUT }

    @Provide
    Arbitrary<Transport> transports() {
        return Arbitraries.of(Transport.RESPONSE, Transport.RESPONSE, Transport.RESPONSE,
                Transport.NETWORK, Transport.TIMEOUT);
    }

    @Property(tries = 300)
    void acquireClassifiesAllCombinations(
            @ForAll("ssmResults") SsmResult ssm,
            @ForAll("tokenResponses") TokenResponse response,
            @ForAll("transports") Transport transport,
            @ForAll("canaryStrings") String canary) {

        String secret = canary + "RT" + ssm.value(); // eindeutig erkennbarer Refresh-Token
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
        ScriptedGraphHttp http = new ScriptedGraphHttp(clock);
        RecordingLog log = new RecordingLog();
        FakeRefreshTokenStore store = new FakeRefreshTokenStore();
        switch (ssm.kind()) {
            case VALUE -> store.put(PARAM, secret);
            case BLANK, EMPTY -> store.put(PARAM, ssm.value());
            case MISSING -> { /* Parameter nicht vorhanden */ }
            case ERROR -> store.failReads(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
        }
        String effectiveBody = response.body().startsWith("{") && response.body().endsWith("}")
                ? response.body().substring(0, response.body().length() - 1)
                        + ",\"error_description\":\"" + canary + "\"}"
                : response.body();
        if (transport == Transport.RESPONSE) {
            http.enqueue(response.status(), effectiveBody);
        } else {
            http.enqueue(new GraphException(transport == Transport.NETWORK
                    ? GraphErrorCategory.NETWORK : GraphErrorCategory.TIMEOUT));
        }

        GraphTokenService service = new GraphTokenService(CFG, store, http, log);
        String access = null;
        GraphException failure = null;
        try {
            access = service.acquire(TimeBudget.start(clock, Duration.ofSeconds(15)));
        } catch (GraphException e) {
            failure = e;
        }

        // höchstens ein Token-Request pro Lauf
        assertTrue(http.callCount() <= 1);

        if (!ssm.isUsable()) {
            // SSM-Leerwert/-Fehler: SSM_UNAVAILABLE, kein Token-Request, kein Write
            assertEquals(0, http.callCount());
            assertEquals(GraphErrorCategory.SSM_UNAVAILABLE, failure.category());
            assertTrue(store.writes().isEmpty());
            return;
        }
        assertEquals(1, http.callCount());

        if (transport != Transport.RESPONSE) {
            assertEquals(transport == Transport.NETWORK
                    ? GraphErrorCategory.NETWORK : GraphErrorCategory.TIMEOUT, failure.category());
            assertTrue(store.writes().isEmpty());
            return;
        }

        boolean invalidGrant = effectiveBody.contains("\"error\":\"invalid_grant\"");
        boolean jsonObject = effectiveBody.startsWith("{") && effectiveBody.endsWith("}");
        boolean hasAccess = jsonObject && effectiveBody.contains("\"access_token\":\"")
                && !effectiveBody.contains("\"access_token\":\"\"");

        if (response.status() == 400 || response.status() == 401 || invalidGrant) {
            assertEquals(GraphErrorCategory.REFRESH_TOKEN_INVALID, failure.category());
        } else if (response.status() != 200) {
            assertEquals(GraphErrorCategory.TOKEN_EXCHANGE_FAILED, failure.category());
            assertEquals(response.status(), failure.httpStatus().getAsInt());
        } else if (!jsonObject) {
            assertEquals(GraphErrorCategory.RESPONSE_UNPARSABLE, failure.category());
        } else if (!hasAccess) {
            assertEquals(GraphErrorCategory.TOKEN_EXCHANGE_FAILED, failure.category());
        } else {
            assertEquals(null, failure);
            String expected = effectiveBody.replaceAll("(?s).*\"access_token\":\"([^\"]*)\".*", "$1");
            assertEquals(expected, access);
            // Write nur bei neuem, nicht leerem Refresh-Token; sonst unverändert
            String stored = store.stored(PARAM);
            assertTrue(store.writes().size() <= 1);
            if (store.writes().isEmpty()) {
                assertEquals(secret, stored);
            }
            return;
        }

        // jeder Fehler: kein SSM-Write, SSM unverändert, keine Body-/Token-Lecks
        assertTrue(store.writes().isEmpty());
        assertEquals(secret, store.stored(PARAM));
        assertFalse(failure.getMessage().contains(canary));
        assertFalse(failure.getMessage().contains(canary));
        assertTrue(log.all().stream().noneMatch(l -> l.contains(canary)));
    }

    @Property(tries = 200)
    void classifyMatchesTable(@ForAll("tokenResponses") TokenResponse r) {
        boolean invalidGrant = r.body().contains("\"error\":\"invalid_grant\"");
        try {
            GraphTokenService.TokenOutcome o = GraphTokenService.classify(r.status(), r.body());
            assertEquals(200, r.status());
            assertFalse(invalidGrant);
            assertFalse(o.accessToken().isBlank());
        } catch (GraphException e) {
            if (r.status() == 400 || r.status() == 401 || invalidGrant) {
                assertEquals(GraphErrorCategory.REFRESH_TOKEN_INVALID, e.category());
            } else if (r.status() != 200) {
                assertEquals(GraphErrorCategory.TOKEN_EXCHANGE_FAILED, e.category());
            } else {
                assertTrue(e.category() == GraphErrorCategory.RESPONSE_UNPARSABLE
                        || e.category() == GraphErrorCategory.TOKEN_EXCHANGE_FAILED);
            }
        }
    }
}
