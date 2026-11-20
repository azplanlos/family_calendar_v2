package de.goForFun.familienkalender.outlook;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 4: Keine Geheimnisse und keine Termininhalte in
 * Protokoll, Antwort und Fehlerliste.
 *
 * <p>Geprüft werden alle Ausgaben des {@code outlook}-Pakets (Logzeilen, Ausnahmetexte,
 * {@code GraphException.safeMessage()}, {@code OutlookLoad.Unavailable}) gegen Canary-Strings
 * für Refresh-/Access-Token, Client-ID und Termininhalte (subject, bodyPreview, location,
 * Kategorien, Fehler-Bodys).
 *
 * <p><b>Validates: Requirements 2.3, 3.5, 3.15, 5.14, 9.2, 9.8, 9.9, 9.11, 13.15</b>
 */
class NoSecretLeakPropertyTest {

    private static final String PARAM = "/familienkalender/outlook/refresh-token";
    private static final QueryWindow WINDOW = new QueryWindow(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));

    enum SsmMode { OK, READ_GraphException, READ_RUNTIME, WRITE_FAILS }

    enum TokenMode { OK_ROTATED, OK_SAME, INVALID_GRANT_400, UNAUTHORIZED_401, SERVER_500,
        INVALID_GRANT_200, NO_ACCESS_TOKEN, NOT_JSON, NETWORK }

    enum EventsMode { OK, STATUS_403, STATUS_500, NOT_JSON, NO_VALUE, FOREIGN_NEXT_LINK, NETWORK }

    @Provide
    Arbitrary<String> canaries() {
        return GraphArbitraries.canaryStringsArbitrary();
    }

    @Property(tries = 400)
    void noSecretOrEventContentInAnyOutput(
            @ForAll("canaries") String canary,
            @ForAll SsmMode ssmMode,
            @ForAll TokenMode tokenMode,
            @ForAll EventsMode eventsMode,
            @ForAll boolean withIcsFallback) {

        String refreshToken = canary + "RT";
        String newRefreshToken = canary + "NRT";
        String accessToken = canary + "AT";
        String clientId = canary + "CID";

        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        ScriptedGraphHttp http = new ScriptedGraphHttp(clock);
        FakeRefreshTokenStore store = new FakeRefreshTokenStore();
        RecordingLog log = new RecordingLog();
        List<String> outputs = new ArrayList<>();

        switch (ssmMode) {
            case OK, WRITE_FAILS -> store.put(PARAM, refreshToken);
            case READ_GraphException -> store.failReads(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
            case READ_RUNTIME -> store.failReadsWith(new IllegalStateException(refreshToken + " " + clientId));
        }
        if (ssmMode == SsmMode.WRITE_FAILS) {
            store.failWrites(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
        }

        enqueueToken(http, tokenMode, canary, accessToken, newRefreshToken);
        enqueueEvents(http, eventsMode, canary);

        GraphConfig config = new GraphConfig(clientId, PARAM);
        GraphSource graph = new GraphSource(config, store, http, clock, ZoneId.of("Europe/Berlin"), log);

        // 1) direkt: GraphSource -> Ausnahmetexte
        try {
            graph.fetch(WINDOW);
        } catch (GraphException e) {
            collect(outputs, e);
        } catch (RuntimeException e) {
            // unbekannte Ausnahmen fängt der Selector ab; ihre Texte erreichen nie eine Ausgabe
        }

        // 2) über den Selector: Logzeilen und Unavailable-Detail (frischer Lauf, gleiche Skripte)
        FakeRefreshTokenStore store2 = new FakeRefreshTokenStore();
        switch (ssmMode) {
            case OK, WRITE_FAILS -> store2.put(PARAM, refreshToken);
            case READ_GraphException -> store2.failReads(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
            case READ_RUNTIME -> store2.failReadsWith(new IllegalStateException(refreshToken + " " + clientId));
        }
        if (ssmMode == SsmMode.WRITE_FAILS) {
            store2.failWrites(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
        }
        ScriptedGraphHttp http2 = new ScriptedGraphHttp(clock);
        enqueueToken(http2, tokenMode, canary, accessToken, newRefreshToken);
        enqueueEvents(http2, eventsMode, canary);
        FakeIcsFetcher fetcher = new FakeIcsFetcher();
        fetcher.failingWith(new IcsFetchException("ICS kaputt"));
        OutlookSourceSelector selector = new OutlookSourceSelector(config,
                () -> new GraphSource(config, store2, http2, clock, ZoneId.of("Europe/Berlin"), log),
                withIcsFallback ? java.net.URI.create("https://example.invalid/feed.ics") : null,
                fetcher, log);
        OutlookLoad load = selector.load(WINDOW);
        if (load instanceof OutlookLoad.Unavailable u) {
            outputs.add(u.detail());
        }

        outputs.addAll(log.all());
        for (String out : outputs) {
            assertFalse(out.contains(canary), "Leck in Ausgabe: " + out);
        }
    }

    @Property(tries = 200)
    void safeMessageNeverContainsArbitraryRawCodeOrContent(
            @ForAll("canaries") String canary,
            @ForAll GraphErrorCategory category,
            @ForAll boolean withStatus) {
        GraphException e = new GraphException(category, withStatus ? 500 : null, canary + " !");
        assertFalse(e.safeMessage().contains(canary));
        assertFalse(e.toString().contains(canary));
        assertTrue(e.safeMessage().startsWith(category.displayName()));
    }

    // ---------------------------------------------------------------- Hilfen

    private static void collect(List<String> outputs, GraphException e) {
        outputs.add(e.safeMessage());
        outputs.add(String.valueOf(e.getMessage()));
        outputs.add(e.toString());
        outputs.add(String.valueOf(e.getCause()));
    }

    private static void enqueueToken(ScriptedGraphHttp http, TokenMode mode, String canary,
            String access, String newRefresh) {
        switch (mode) {
            case OK_ROTATED -> http.enqueue(200, "{\"access_token\":\"" + access
                    + "\",\"refresh_token\":\"" + newRefresh + "\",\"expires_in\":3600}");
            case OK_SAME -> http.enqueue(200, "{\"access_token\":\"" + access + "\"}");
            case INVALID_GRANT_400 -> http.enqueue(400,
                    "{\"error\":\"invalid_grant\",\"error_description\":\"" + canary + "\"}");
            case UNAUTHORIZED_401 -> http.enqueue(401, "{\"error_description\":\"" + canary + "\"}");
            case SERVER_500 -> http.enqueue(500, canary + " " + access);
            case INVALID_GRANT_200 -> http.enqueue(200,
                    "{\"error\":\"invalid_grant\",\"error_description\":\"" + canary + "\"}");
            case NO_ACCESS_TOKEN -> http.enqueue(200, "{\"refresh_token\":\"" + newRefresh + "\"}");
            case NOT_JSON -> http.enqueue(200, "<html>" + canary + access + "</html>");
            case NETWORK -> http.enqueue(new GraphException(GraphErrorCategory.NETWORK));
        }
    }

    private static void enqueueEvents(ScriptedGraphHttp http, EventsMode mode, String canary) {
        switch (mode) {
            case OK -> http.enqueue(200, "{\"value\":[{\"id\":\"A1\",\"iCalUId\":\"U1\","
                    + "\"subject\":\"" + canary + "S\",\"bodyPreview\":\"" + canary + "B\","
                    + "\"location\":{\"displayName\":\"" + canary + "L\"},"
                    + "\"categories\":[\"" + canary + "C\"],\"isAllDay\":false,\"isCancelled\":false,"
                    + "\"sensitivity\":\"normal\",\"showAs\":\"busy\",\"type\":\"singleInstance\","
                    + "\"start\":{\"dateTime\":\"2026-01-10T10:00:00.0000000\",\"timeZone\":\"Europe/Berlin\"},"
                    + "\"end\":{\"dateTime\":\"2026-01-10T11:00:00.0000000\",\"timeZone\":\"Europe/Berlin\"}}]}");
            case STATUS_403 -> http.enqueue(403,
                    "{\"error\":{\"code\":\"ErrorAccessDenied\",\"message\":\"" + canary + "\"}}");
            case STATUS_500 -> http.enqueue(500, canary);
            case NOT_JSON -> http.enqueue(200, canary);
            case NO_VALUE -> http.enqueue(200, "{\"x\":\"" + canary + "\"}");
            case FOREIGN_NEXT_LINK -> http.enqueue(200, "{\"value\":[],\"@odata.nextLink\":\"https://evil.example/"
                    + canary + "\"}");
            case NETWORK -> http.enqueue(new GraphException(GraphErrorCategory.NETWORK));
        }
    }
}
