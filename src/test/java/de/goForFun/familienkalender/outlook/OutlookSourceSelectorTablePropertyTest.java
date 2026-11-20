package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.Calendar;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Feature: outlook-graph-source, Property 1: Quellenauswahl folgt der Entscheidungstabelle.
 *
 * <p><b>Validates: Requirements 1.1, 1.2, 1.4, 2.1, 2.4, 2.5, 3.12, 4.14, 10.1, 10.7, 12.3, 12.4,
 * 13.1, 13.2, 13.3, 13.11</b>
 */
class OutlookSourceSelectorTablePropertyTest {

    enum GraphState { CONFIGURED, NOT_CONFIGURED, PARTIAL_CLIENT, PARTIAL_PARAM }

    enum GraphOutcome { SUCCESS, GRAPH_EXCEPTION, RUNTIME_EXCEPTION }

    enum IcsOutcome { SUCCESS, ICS_EXCEPTION, RUNTIME_EXCEPTION }

    private static final QueryWindow WINDOW =
            new QueryWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
    private static final URI ICS_URL = URI.create("https://example.invalid/outlook.ics");

    @Provide
    Arbitrary<GraphErrorCategory> categories() {
        return Arbitraries.of(GraphErrorCategory.class);
    }

    @Provide
    Arbitrary<String> icsMessages() {
        return Arbitraries.strings().alpha().numeric().withChars(' ', ':', '(', ')')
                .ofMinLength(1).ofMaxLength(40).filter(s -> !s.isBlank());
    }

    private static GraphConfig config(GraphState state) {
        return switch (state) {
            case CONFIGURED -> new GraphConfig("client", "/param");
            case NOT_CONFIGURED -> new GraphConfig("", "  ");
            case PARTIAL_CLIENT -> new GraphConfig("client", "");
            case PARTIAL_PARAM -> new GraphConfig(null, "/param");
        };
    }

    @Property(tries = 300)
    void selectionFollowsDecisionTable(
            @ForAll GraphState state,
            @ForAll GraphOutcome graphOutcome,
            @ForAll("categories") GraphErrorCategory category,
            @ForAll boolean icsUrlValid,
            @ForAll IcsOutcome icsOutcome,
            @ForAll("icsMessages") String icsMessage) {

        Calendar graphCalendar = new Calendar();
        Calendar icsCalendar = new Calendar();
        FakeGraphSource graph = new FakeGraphSource().returning(graphCalendar);
        switch (graphOutcome) {
            case GRAPH_EXCEPTION -> graph.failingWith(new GraphException(category));
            case RUNTIME_EXCEPTION -> graph.failingWithRuntime(new IllegalStateException("boom"));
            case SUCCESS -> { }
        }
        FakeIcsFetcher ics = new FakeIcsFetcher().returning(icsCalendar);
        switch (icsOutcome) {
            case ICS_EXCEPTION -> ics.failingWith(new IcsFetchException(icsMessage));
            case RUNTIME_EXCEPTION -> ics.failingWithRuntime(new IllegalStateException("boom"));
            case SUCCESS -> { }
        }

        URI url = icsUrlValid ? ICS_URL : null;
        OutlookSourceSelector selector = new OutlookSourceSelector(
                config(state), () -> graph, url, ics, new RecordingLog());

        OutlookLoad result = selector.load(WINDOW);

        boolean configured = state == GraphState.CONFIGURED;
        boolean graphFailed = configured && graphOutcome != GraphOutcome.SUCCESS;
        boolean graphSucceeded = configured && !graphFailed;
        boolean icsExpected = !graphSucceeded && icsUrlValid;

        // Aufrufzähler
        assertEquals(configured ? 1 : 0, graph.callCount(), "GraphSource nur bei CONFIGURED");
        assertEquals(icsExpected ? 1 : 0, ics.callCount(), "ICS-Fetcher nur bei Fallback mit gültiger URL");
        if (icsExpected) {
            assertEquals(ICS_URL, ics.calls().get(0));
        }

        // Ergebnis gemäß Tabellenzeile
        if (graphSucceeded) { // Zeile 1
            OutlookLoad.Loaded loaded = assertInstanceOf(OutlookLoad.Loaded.class, result);
            assertEquals(OutlookLoad.Origin.GRAPH, loaded.origin());
            assertSame(graphCalendar, loaded.calendar());
        } else if (icsExpected) { // Zeilen 2, 3, 5, 6
            if (icsOutcome == IcsOutcome.SUCCESS) {
                OutlookLoad.Loaded loaded = assertInstanceOf(OutlookLoad.Loaded.class, result);
                assertEquals(OutlookLoad.Origin.ICS, loaded.origin());
                assertSame(icsCalendar, loaded.calendar());
            } else {
                OutlookLoad.Unavailable unavailable = assertInstanceOf(OutlookLoad.Unavailable.class, result);
                if (icsOutcome == IcsOutcome.ICS_EXCEPTION) {
                    assertEquals(icsMessage, unavailable.detail());
                } else {
                    org.junit.jupiter.api.Assertions.assertFalse(unavailable.detail().isBlank());
                }
            }
        } else if (graphFailed) { // Zeile 4
            OutlookLoad.Unavailable unavailable = assertInstanceOf(OutlookLoad.Unavailable.class, result);
            GraphErrorCategory expected = graphOutcome == GraphOutcome.GRAPH_EXCEPTION
                    ? category : GraphErrorCategory.RESPONSE_UNPARSABLE;
            assertEquals("Graph nicht verfügbar (" + expected.displayName() + ")", unavailable.detail());
        } else { // Zeile 7
            assertInstanceOf(OutlookLoad.NotConfigured.class, result);
        }
    }
}
