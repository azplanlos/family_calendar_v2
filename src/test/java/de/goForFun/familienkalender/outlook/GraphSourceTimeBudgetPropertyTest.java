package de.goForFun.familienkalender.outlook;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 6: Zeitbudget und Einzel-Timeouts.
 *
 * <p>Der Fallback-Teil der Property (10 s unabhängig vom Verbrauch) gehört zu
 * {@code IcsCalendarFetcher} und wird dort geprüft.
 *
 * <p><b>Validates: Requirements 2.6, 2.9, 3.14, 4.10, 13.16</b>
 */
class GraphSourceTimeBudgetPropertyTest {

    private static final String PARAM = "/familienkalender/outlook/refresh-token";
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final long BUDGET_MS = 15_000;
    private static final long CAP_MS = 10_000;
    private static final QueryWindow WINDOW = new QueryWindow(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));

    /** Schrittdauern: SSM lesen, Token-Tausch, optional SSM schreiben, 1 bis 50 Seiten. */
    record Scenario(Instant start, long readMs, long tokenMs, boolean rotate, long writeMs,
            List<Long> pageMs) { }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Long> dur = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(6, Arbitraries.longs().between(0, 300)),
                net.jqwik.api.Tuple.of(2, Arbitraries.longs().between(0, 6_000)),
                net.jqwik.api.Tuple.of(1, Arbitraries.longs().between(0, 14_000)));
        Arbitrary<Instant> start = Arbitraries.longs().between(1_700_000_000L, 1_900_000_000L)
                .map(Instant::ofEpochSecond);
        return Arbitraries.of(true, false).flatMap(rotate ->
                net.jqwik.api.Combinators.combine(start, dur, dur, dur,
                        dur.list().ofMinSize(1).ofMaxSize(50))
                        .as((s, r, t, w, p) -> new Scenario(s, r, t, rotate, w, p)));
    }

    /** Erwartung laut Property 6; {@code null} bedeutet Erfolg. */
    private record Expected(GraphErrorCategory failure, List<Long> timeoutsMs) { }

    private static Expected model(Scenario s) {
        List<Long> timeouts = new ArrayList<>();
        List<Long> steps = new ArrayList<>();
        steps.add(s.readMs());
        steps.add(s.tokenMs());
        int writeIndex = -1;
        if (s.rotate()) {
            writeIndex = steps.size();
            steps.add(s.writeMs());
        }
        steps.addAll(s.pageMs());
        long elapsed = 0;
        for (int i = 0; i < steps.size(); i++) {
            long rest = BUDGET_MS - elapsed;
            if (rest <= 0) {
                if (i == writeIndex) {
                    continue; // Rückschreiben ist best effort, kein Request
                }
                return new Expected(GraphErrorCategory.BUDGET, timeouts);
            }
            long timeout = Math.min(CAP_MS, rest);
            timeouts.add(timeout);
            if (steps.get(i) > timeout) {
                elapsed += timeout;
                if (i == writeIndex) {
                    continue; // Fehler beim Rückschreiben wird nur protokolliert
                }
                return new Expected(GraphErrorCategory.TIMEOUT, timeouts);
            }
            elapsed += steps.get(i);
        }
        return new Expected(null, timeouts);
    }

    private static String pageBody(int index, int total) {
        if (index == total - 1) {
            return "{\"value\":[]}";
        }
        return "{\"value\":[],\"@odata.nextLink\":\"" + GraphEndpoints.CALENDAR_VIEW
                + "?page=" + (index + 1) + "\"}";
    }

    /** Store, der die Uhr vorrückt und Timeouts in die gemeinsame Liste schreibt. */
    private static final class TimedStore implements RefreshTokenStore {
        private final MutableClock clock;
        private final Scenario scenario;
        private final List<Duration> seen;

        TimedStore(MutableClock clock, Scenario scenario, List<Duration> seen) {
            this.clock = clock;
            this.scenario = scenario;
            this.seen = seen;
        }

        @Override
        public String read(String parameterName, Duration timeout) throws GraphException {
            return run(scenario.readMs(), timeout, "old-token");
        }

        @Override
        public void write(String parameterName, String value, Duration timeout)
                throws GraphException {
            run(scenario.writeMs(), timeout, null);
        }

        private String run(long ms, Duration timeout, String result) throws GraphException {
            seen.add(timeout);
            Duration d = Duration.ofMillis(ms);
            if (d.compareTo(timeout) > 0) {
                clock.advance(timeout);
                throw new GraphException(GraphErrorCategory.TIMEOUT);
            }
            clock.advance(d);
            return result;
        }
    }

    /** Leitet an {@link ScriptedGraphHttp} weiter und merkt sich die Timeouts. */
    private static final class RecordingHttp implements GraphHttp {
        private final GraphHttp delegate;
        private final List<Duration> seen;

        RecordingHttp(GraphHttp delegate, List<Duration> seen) {
            this.delegate = delegate;
            this.seen = seen;
        }

        @Override
        public HttpResult send(HttpCall call, Duration timeout) throws GraphException {
            seen.add(timeout);
            return delegate.send(call, timeout);
        }
    }

    private static final class Outcome {
        GraphErrorCategory failure;
        final List<Duration> timeouts = new ArrayList<>();
    }

    private static Outcome run(Scenario s) {
        MutableClock clock = new MutableClock(s.start());
        Outcome out = new Outcome();
        ScriptedGraphHttp scripted = new ScriptedGraphHttp(clock);
        String tokenBody = s.rotate()
                ? "{\"access_token\":\"acc\",\"refresh_token\":\"new-token\"}"
                : "{\"access_token\":\"acc\"}";
        scripted.enqueue(ScriptedGraphHttp.Step.ok(200, tokenBody)
                .taking(Duration.ofMillis(s.tokenMs())));
        int n = s.pageMs().size();
        for (int i = 0; i < n; i++) {
            scripted.enqueue(ScriptedGraphHttp.Step.ok(200, pageBody(i, n))
                    .taking(Duration.ofMillis(s.pageMs().get(i))));
        }
        GraphSource source = new GraphSource(new GraphConfig("client", PARAM),
                new TimedStore(clock, s, out.timeouts), new RecordingHttp(scripted, out.timeouts),
                clock, ZONE, new RecordingLog());
        try {
            source.fetch(WINDOW);
        } catch (GraphException e) {
            out.failure = e.category();
        }
        return out;
    }

    @Property(tries = 300)
    void timeoutsAndBudgetFollowTheModel(@ForAll("scenarios") Scenario s) {
        Expected expected = model(s);
        Outcome actual = run(s);

        assertEquals(expected.failure(), actual.failure);
        List<Long> actualMs = actual.timeouts.stream().map(Duration::toMillis).toList();
        assertEquals(expected.timeoutsMs(), actualMs);
        for (Duration t : actual.timeouts) {
            assertTrue(t.toMillis() > 0 && t.toMillis() <= CAP_MS,
                    "Timeout außerhalb (0, 10 s]: " + t);
        }
    }

    @Property(tries = 100)
    void reachingBudgetBeforeNextStepYieldsBudgetWithoutFurtherRequest(
            @ForAll("startInstants") Instant start) {
        // Lesen 10 s + Token-Tausch 5 s = genau 15 s; die erste Seite darf nicht mehr starten.
        Scenario s = new Scenario(start, 10_000, 5_000, false, 0, List.of(0L));
        Outcome actual = run(s);
        assertEquals(GraphErrorCategory.BUDGET, actual.failure);
        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(5)), actual.timeouts);
    }

    @Provide
    Arbitrary<Instant> startInstants() {
        return Arbitraries.longs().between(1_700_000_000L, 1_900_000_000L)
                .map(Instant::ofEpochSecond);
    }

    @Property(tries = 1)
    void timeBudgetStepTimeoutIsMinOfCapAndRemaining() throws GraphException {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        TimeBudget budget = TimeBudget.start((Clock) clock, Duration.ofSeconds(15));
        assertEquals(Duration.ofSeconds(10), budget.stepTimeout(Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(12));
        assertEquals(Duration.ofSeconds(3), budget.stepTimeout(Duration.ofSeconds(10)));
        clock.advance(Duration.ofSeconds(3));
        GraphException e = assertThrows(GraphException.class,
                () -> budget.stepTimeout(Duration.ofSeconds(10)));
        assertEquals(GraphErrorCategory.BUDGET, e.category());
    }
}
