package de.goForFun.familienkalender.outlook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class GraphCalendarClientTest {

    private static final QueryWindow WINDOW = new QueryWindow(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));

    @Test
    void rejectedNextLink_neverReachesGraphHttp() {
        String[] badLinks = {
            "https://evil.example.com/v1.0/me/calendarView?$skip=1",
            "http://graph.microsoft.com/v1.0/me/calendarView?$skip=1",
            "https://graph.microsoft.com.evil.example/v1.0/me/calendarView",
            "https://login.microsoftonline.com/common/oauth2/v2.0/token",
            "not a uri"
        };
        for (String link : badLinks) {
            MutableClock clock = new MutableClock(Instant.parse("2026-06-01T00:00:00Z"));
            ScriptedGraphHttp http = new ScriptedGraphHttp(clock);
            http.enqueue(200, "{\"value\":[],\"@odata.nextLink\":\"" + link + "\"}");
            GraphCalendarClient client = new GraphCalendarClient(http, ZoneId.of("Europe/Berlin"));
            GraphException e = assertThrows(GraphException.class, () -> client.fetchAll(
                    WINDOW, "tok", TimeBudget.start(clock, Duration.ofSeconds(15))), link);
            assertEquals(GraphErrorCategory.RESPONSE_UNPARSABLE, e.category(), link);
            assertEquals(1, http.callCount(), "nur die erste Seite darf angefragt werden: " + link);
        }
    }
}
