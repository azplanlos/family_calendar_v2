package de.goForFun.familienkalender.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * **Feature: outlook-graph-source, Property 10: Rotationsentscheidung und Reihenfolge**
 *
 * <p><b>Validates: Requirements 3.6, 3.7, 3.8, 3.16, 10.6, 10.8</b>
 */
class GraphTokenRotationPropertyTest {

    private static final String PARAM = "/fam/token";
    private static final GraphConfig CFG = new GraphConfig("client-1", PARAM);

    private static Arbitrary<String> ws() {
        return Arbitraries.of("", " ", "  ", "\t", "\n", " \t ");
    }

    private static Arbitrary<String> core() {
        return Arbitraries.of("a", "tok-1", "tok-2", "A.b_c-9", "x");
    }

    /** Gelesener Token: nach Trimmen nie leer. */
    @Provide
    Arbitrary<String> readTokens() {
        return Combinators.combine(ws(), core(), ws()).as((a, b, c) -> a + b + c);
    }

    /** Zurückgegebener Token: {@code null}, leer, Leerraum, oder Wert mit Leerraumvarianten. */
    @Provide
    Arbitrary<String> returnedTokens() {
        return Arbitraries.oneOf(
                Arbitraries.just((String) null),
                ws(),
                Combinators.combine(ws(), core(), ws()).as((a, b, c) -> a + b + c));
    }

    private static String json(String returned) {
        StringBuilder sb = new StringBuilder("{\"access_token\":\"AT\"");
        if (returned != null) {
            sb.append(",\"refresh_token\":\"")
                    .append(returned.replace("\t", "\\t").replace("\n", "\\n"))
                    .append('"');
        }
        return sb.append('}').toString();
    }

    private static boolean expected(String read, String returned) {
        return returned != null && !returned.trim().isEmpty() && !returned.trim().equals(read.trim());
    }

    private record Run(FakeRefreshTokenStore store, ScriptedGraphHttp http, RecordingLog log, String result) { }

    private static Run run(String read, String returned, GraphException writeFailure) throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
        ScriptedGraphHttp http = new ScriptedGraphHttp(clock);
        RecordingLog log = new RecordingLog();
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, read);
        if (writeFailure != null) {
            store.failWrites(writeFailure);
        }
        http.enqueue(200, json(returned));
        String result = new GraphTokenService(CFG, store, http, log)
                .acquire(TimeBudget.start(clock, Duration.ofSeconds(15)));
        return new Run(store, http, log, result);
    }

    @Property(tries = 300)
    void shouldPersistMatchesRule(@ForAll("readTokens") String read, @ForAll("returnedTokens") String returned) {
        assertThat(GraphTokenService.shouldPersist(read, returned)).isEqualTo(expected(read, returned));
    }

    @Property(tries = 300)
    void writesAtMostOnceOnlyWhenRotatedAndNeverElsewhere(
            @ForAll("readTokens") String read, @ForAll("returnedTokens") String returned) throws Exception {
        Run r = run(read, returned, null);
        assertThat(r.result()).isEqualTo("AT");
        // Schreiben passiert vor Rückgabe von acquire, also vor jedem calendarView-Request;
        // der Token-Tausch ist der einzige HTTP-Aufruf des Service.
        assertThat(r.http().callCount()).isEqualTo(1);
        assertThat(r.store().reads()).hasSize(1);
        if (expected(read, returned)) {
            assertThat(r.store().writes()).hasSize(1);
            assertThat(r.store().writes().get(0).parameterName()).isEqualTo(PARAM);
            assertThat(r.store().writes().get(0).value()).isEqualTo(returned.trim());
        } else {
            assertThat(r.store().writes()).isEmpty();
            assertThat(r.store().stored(PARAM)).isEqualTo(read);
        }
        assertThat(r.log().all()).isEmpty();
    }

    @Property(tries = 200)
    void writeFailureDoesNotChangeResult(
            @ForAll("readTokens") String read, @ForAll("returnedTokens") String returned) throws Exception {
        Run ok = run(read, returned, null);
        Run failing = run(read, returned, new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
        assertThat(failing.result()).isEqualTo(ok.result());
        assertThat(failing.store().writes()).hasSameSizeAs(ok.store().writes()).hasSizeLessThanOrEqualTo(1);
        assertThat(failing.log().warnings()).hasSize(expected(read, returned) ? 1 : 0);
        assertThat(failing.log().errors()).isEmpty();
    }

    @Property(tries = 100)
    void failedExchangeNeverWrites(@ForAll("readTokens") String read) {
        MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
        ScriptedGraphHttp http = new ScriptedGraphHttp(clock);
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, read);
        http.enqueue(400, "{\"error\":\"invalid_grant\",\"refresh_token\":\"leak\"}");
        GraphTokenService service = new GraphTokenService(CFG, store, http, new RecordingLog());
        org.junit.jupiter.api.Assertions.assertThrows(GraphException.class,
                () -> service.acquire(TimeBudget.start(clock, Duration.ofSeconds(15))));
        assertThat(store.writes()).isEmpty();
    }
}
