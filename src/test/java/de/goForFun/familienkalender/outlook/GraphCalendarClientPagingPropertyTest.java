package de.goForFun.familienkalender.outlook;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 11: Paging liefert alle Events in Reihenfolge und
 * begrenzt auf 50 Seiten.
 *
 * <p><b>Validates: Requirements 4.1, 4.4, 4.5, 4.6, 4.9, 6.1</b>
 */
class GraphCalendarClientPagingPropertyTest extends GraphArbitraries {

    private static final String TOKEN = "tok-abc123";
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final QueryWindow WINDOW = new QueryWindow(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));

    @Property(tries = 200)
    void pagingReturnsAllEventsInOrderAndLimitsTo50Pages(
            @ForAll("graphPages") List<JsonNode> pages) {
        ScriptedGraphHttp http = new ScriptedGraphHttp();
        for (JsonNode page : pages) {
            http.enqueue(200, page.toString());
        }
        MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
        TimeBudget budget = TimeBudget.start(clock, Duration.ofSeconds(15));
        GraphCalendarClient client = new GraphCalendarClient(http, ZONE);

        if (pages.size() <= GraphCalendarClient.MAX_PAGES) {
            List<JsonNode> result;
            try {
                result = client.fetchAll(WINDOW, TOKEN, budget);
            } catch (GraphException e) {
                throw new AssertionError("unerwarteter Fehler: " + e.getMessage(), e);
            }
            List<JsonNode> expected = new ArrayList<>();
            pages.forEach(p -> p.get("value").forEach(expected::add));
            assertEquals(expected, result);
            assertEquals(pages.size(), http.callCount());
        } else {
            GraphException e = assertThrows(GraphException.class,
                    () -> client.fetchAll(WINDOW, TOKEN, budget));
            assertEquals(GraphErrorCategory.PAGE_LIMIT, e.category());
            assertEquals(GraphCalendarClient.MAX_PAGES, http.callCount());
        }

        List<ScriptedGraphHttp.Recorded> calls = http.calls();
        URI initial = GraphCalendarClient.initialUri(WINDOW);
        assertEquals(initial, calls.get(0).uri());
        for (int i = 0; i < calls.size(); i++) {
            HttpCall call = calls.get(i).call();
            assertEquals("GET", call.method());
            assertEquals("Bearer " + TOKEN, call.headers().get("Authorization"));
            assertEquals("outlook.timezone=\"" + ZONE.getId() + "\"", call.headers().get("Prefer"));
            if (i > 0) {
                // nextLink der Vorseite unverändert übernommen
                assertEquals(pages.get(i - 1).get("@odata.nextLink").asString(),
                        call.uri().toString());
            }
        }

        String query = initial.getRawQuery();
        assertTrue(query.contains("$select=" + GraphCalendarClient.SELECT));
        assertTrue(query.contains("$top=100"));
        assertEquals(14, GraphCalendarClient.SELECT.split(",").length);
        assertFalse(query.contains("$top=100&$top"));
    }
}
