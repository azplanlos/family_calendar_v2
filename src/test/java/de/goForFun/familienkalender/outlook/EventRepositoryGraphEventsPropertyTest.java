package de.goForFun.familienkalender.outlook;

import de.goForFun.familienkalender.AppTimeZone;
import de.goForFun.familienkalender.EventRepository;
import de.goForFun.familienkalender.model.Event;
import net.fortuna.ical4j.model.Calendar;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 26: Das Device zeigt genau die Graph-Events des Fensters,
 * mit unverändertem Titel.
 *
 * <p>Der Hauptfeed ist leer; alle Events liegen im Geräte-Fenster (November/Dezember 2026,
 * Referenzdatum 10.11.2026). Erwartet werden alle nicht stornierten, auswertbaren Events
 * (sensitivity, categories und showAs spielen keine Rolle) mit {@code subject} als Titel.
 *
 * <p><b>Validates: Requirements 13.5, 13.6, 13.7, 13.8, 13.20</b>
 */
class EventRepositoryGraphEventsPropertyTest extends GraphArbitraries {

    private static final LocalDate REF = LocalDate.of(2026, 11, 10);
    private static final LocalDate WINDOW_FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate WINDOW_TO = LocalDate.of(2026, 12, 31);

    private static URI emptyFeed;

    private static synchronized URI emptyFeed() throws Exception {
        if (emptyFeed == null) {
            Path p = Files.createTempFile("empty-main-feed", ".ics");
            p.toFile().deleteOnExit();
            Files.writeString(p, "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nEND:VCALENDAR\r\n",
                    StandardCharsets.UTF_8);
            emptyFeed = p.toUri();
        }
        return emptyFeed;
    }

    /** Verschiebt Start und Ende des Events auf einen Tag zwischen 6.11. und 15.12.2026. */
    private static void moveIntoWindow(JsonNode node, int index) {
        if (!(node instanceof ObjectNode event)) {
            return;
        }
        LocalDate target = LocalDate.of(2026, 11, 6).plusDays((index * 7L) % 40);
        long delta;
        try {
            LocalDateTime start = LocalDateTime.parse(event.path("start").path("dateTime").asString(""));
            delta = ChronoUnit.DAYS.between(start.toLocalDate(), target);
        } catch (RuntimeException ex) {
            return; // Start fehlt: Event wird ohnehin ausgelassen
        }
        for (String field : List.of("start", "end")) {
            JsonNode t = event.path(field);
            if (t instanceof ObjectNode obj && obj.path("dateTime").isString()) {
                LocalDateTime shifted = LocalDateTime.parse(obj.get("dateTime").asString()).plusDays(delta);
                obj.put("dateTime", shifted.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS")));
            }
        }
    }

    @Property(tries = 200)
    void deviceShowsExactlyTheGraphEventsOfTheWindow(
            @ForAll("graphEventLists") List<JsonNode> events) throws Exception {
        for (int i = 0; i < events.size(); i++) {
            moveIntoWindow(events.get(i), i);
        }

        List<String> expectedTitles = new ArrayList<>();
        for (JsonNode e : events) {
            if (e.path("isCancelled").asBoolean(false)) {
                continue;
            }
            if (GraphCalendarMapper.map(e, AppTimeZone.get()).isPresent()) {
                // Der Device-Parser (IcalMapping.mapSummary) trimmt umgebenden Leerraum, damit
                // In-Memory-Kalender identisch zum Stream-Parser gelesen werden (Property 25).
                expectedTitles.add(e.path("subject").isString() ? e.get("subject").asString().trim() : "");
            }
        }

        Calendar calendar = GraphCalendarMapper.toCalendar(events, AppTimeZone.get()).calendar();
        EventRepository repo = new EventRepository(emptyFeed(), null, null,
                w -> new OutlookLoad.Loaded(calendar, OutlookLoad.Origin.GRAPH), "Papa", REF, null);

        assertTrue(repo.getErrors().isEmpty(), "unerwartete Fehler: " + repo.getErrors());
        List<String> actualTitles = new ArrayList<>(
                repo.getEventsForRange(WINDOW_FROM, WINDOW_TO).stream().map(Event::summary).toList());

        expectedTitles.sort(null);
        actualTitles.sort(null);
        assertEquals(expectedTitles, actualTitles);
    }
}
