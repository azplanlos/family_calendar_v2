package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.Calendar;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 5: Pro Lauf genau eine Fallback-Warnung mit genau einer
 * bekannten Kategorie.
 *
 * <p><b>Validates: Requirements 2.2, 12.1, 12.2, 12.8, 13.12</b>
 */
class OutlookSourceSelectorLoggingPropertyTest {

    private static final String PARAM = "/familienkalender/outlook/refresh-token";
    private static final String CLIENT_CANARY = "CLIENTIDCANARY0123";
    private static final URI ICS = URI.create("https://example.invalid/feed.ics");
    private static final QueryWindow WINDOW =
            new QueryWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));

    /** Skriptierter Graph-Ausgang: Kategorie oder {@code null} = Erfolg; {@code runtime} = unbekannte Ausnahme. */
    record GraphOutcome(GraphErrorCategory category, boolean runtime) {
    }

    @Provide
    Arbitrary<GraphOutcome> failures() {
        Arbitrary<GraphOutcome> checked = Arbitraries.of(GraphErrorCategory.values())
                .map(c -> new GraphOutcome(c, false));
        Arbitrary<GraphOutcome> unknown = Arbitraries.just(new GraphOutcome(null, true));
        return Arbitraries.oneOf(checked, checked, unknown);
    }

    private static OutlookSourceSelector selector(FakeGraphSource graph, URI ics, FakeIcsFetcher fetcher,
            RecordingLog log) {
        return new OutlookSourceSelector(new GraphConfig(CLIENT_CANARY, PARAM), () -> graph, ics, fetcher, log);
    }

    private static long categoriesMentioned(String line) {
        return Arrays.stream(GraphErrorCategory.values())
                .filter(c -> line.contains(c.displayName())).count();
    }

    @Property
    void exactlyOneWarningWithExactlyOneKnownCategory(
            @ForAll("failures") GraphOutcome outcome,
            @ForAll boolean icsPresent,
            @ForAll boolean icsFails,
            @ForAll boolean icsThrowsRuntime) {
        FakeGraphSource graph = new FakeGraphSource();
        GraphErrorCategory expected;
        if (outcome.runtime()) {
            graph.failingWithRuntime(new IllegalStateException("boom " + CLIENT_CANARY));
            expected = GraphErrorCategory.RESPONSE_UNPARSABLE;
        } else {
            graph.failingWith(new GraphException(outcome.category()));
            expected = outcome.category();
        }
        FakeIcsFetcher fetcher = new FakeIcsFetcher();
        if (icsFails) {
            fetcher.failingWith(new IcsFetchException("ICS kaputt"));
        } else if (icsThrowsRuntime) {
            fetcher.failingWithRuntime(new IllegalStateException("x"));
        }
        RecordingLog log = new RecordingLog();

        selector(graph, icsPresent ? ICS : null, fetcher, log).load(WINDOW);

        assertEquals(1, log.warnings().size(), "genau eine WARN-Zeile");
        String warn = log.warnings().get(0);
        assertEquals(1, categoriesMentioned(warn), "genau eine Kategorie: " + warn);
        assertTrue(warn.contains(expected.displayName()), warn);

        boolean tokenOrSsm = expected == GraphErrorCategory.REFRESH_TOKEN_INVALID
                || expected == GraphErrorCategory.SSM_UNAVAILABLE;
        assertEquals(tokenOrSsm ? 1 : 0, log.errors().size(), "ERROR-Zeilen: " + log.errors());
        if (tokenOrSsm) {
            String prefix = expected == GraphErrorCategory.REFRESH_TOKEN_INVALID
                    ? "Refresh_Token ungültig, Bootstrap erneut ausführen"
                    : "SSM_Parameter nicht verfügbar, Bootstrap erneut ausführen oder Parameter prüfen";
            assertEquals(prefix + " (SSM_Parameter: " + PARAM + ")", log.errors().get(0));
        }
        for (String line : log.all()) {
            assertFalse(line.contains(CLIENT_CANARY), "kein Wert in Protokollzeile: " + line);
            assertFalse(line.contains("boom"), "keine Ausnahmetexte: " + line);
        }
    }

    @Property
    void graphSuccessLogsNothing(@ForAll boolean icsPresent) {
        FakeGraphSource graph = new FakeGraphSource().returning(new Calendar());
        RecordingLog log = new RecordingLog();

        selector(graph, icsPresent ? ICS : null, new FakeIcsFetcher(), log).load(WINDOW);

        assertTrue(log.warnings().isEmpty());
        assertTrue(log.errors().isEmpty());
    }
}
