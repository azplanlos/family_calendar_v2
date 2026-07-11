package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
import net.fortuna.ical4j.data.ParserException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests für EventRepository – insbesondere Tageswechsel-Logik und VACATION-Integration.
 */
public class TestEventRepository {

    /**
     * Ein Event das um 22:00 startet und am nächsten Tag um 10:00 endet (>08:00)
     * soll am nächsten Tag mit Startzeit 00:00 angezeigt werden.
     */
    @Test
    void testOvernightEvent_endingAfter8_showsOnNextDay() throws IOException, ParserException {
        String ics = createIcsWithEvent("LAN-Party",
                "20260210T220000", "20260211T100000");
        InputStream stream = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8));

        EventRepository repo = new EventRepository(stream, LocalDate.of(2026, 2, 11), null);
        List<Event> events = repo.getEventsForDay(LocalDate.of(2026, 2, 11));

        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.summary()).isEqualTo("LAN-Party");
        // Startzeit muss 00:00 am 11.02. sein
        assertThat(event.startTime()).isEqualTo(LocalDateTime.of(2026, 2, 11, 0, 0));
        // Endzeit bleibt original
        assertThat(event.endTime()).isEqualTo(LocalDateTime.of(2026, 2, 11, 10, 0));
    }

    /**
     * Ein Event das um 23:00 startet und am nächsten Tag um 02:00 endet (<08:00)
     * soll am nächsten Tag NICHT angezeigt werden.
     */
    @Test
    void testOvernightEvent_endingBefore8_notShownOnNextDay() throws IOException, ParserException {
        String ics = createIcsWithEvent("Kurzer Besuch",
                "20260210T230000", "20260211T020000");
        InputStream stream = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8));

        EventRepository repo = new EventRepository(stream, LocalDate.of(2026, 2, 11), null);
        List<Event> events = repo.getEventsForDay(LocalDate.of(2026, 2, 11));

        assertThat(events).isEmpty();
    }

    /**
     * Ein Event das um 22:00 startet und am nächsten Tag um 10:00 endet
     * soll am selben Tag (Starttag) normal mit Original-Startzeit angezeigt werden.
     */
    @Test
    void testOvernightEvent_showsNormallyOnStartDay() throws IOException, ParserException {
        String ics = createIcsWithEvent("LAN-Party",
                "20260210T220000", "20260211T100000");
        InputStream stream = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8));

        EventRepository repo = new EventRepository(stream, LocalDate.of(2026, 2, 10), null);
        List<Event> events = repo.getEventsForDay(LocalDate.of(2026, 2, 10));

        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.summary()).isEqualTo("LAN-Party");
        assertThat(event.startTime()).isEqualTo(LocalDateTime.of(2026, 2, 10, 22, 0));
    }

    /**
     * Ganztägige Events werden NICHT als Übernacht-Events behandelt.
     */
    @Test
    void testAllDayEvent_notTreatedAsOvernight() throws IOException, ParserException {
        String ics = createIcsWithAllDayEvent("Sommerferien",
                "20260210", "20260215");
        InputStream stream = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8));

        EventRepository repo = new EventRepository(stream, LocalDate.of(2026, 2, 11), null);
        List<Event> events = repo.getEventsForDay(LocalDate.of(2026, 2, 11));

        // Ganztägiges Event wird normal angezeigt (nicht mit 00:00 Start transformiert)
        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.summary()).isEqualTo("Sommerferien");
        // Bleibt ganztägig (00:00 Start, 00:00 Ende)
        assertThat(event.startTime().getHour()).isEqualTo(0);
        assertThat(event.startTime().getMinute()).isEqualTo(0);
    }

    /**
     * Ein Event das am selben Tag startet und vor 08:00 endet soll NORMAL gerendert werden.
     * Die 8-Uhr-Logik gilt NUR für Übernacht-Events vom Vortag, nicht für Same-Day-Events.
     */
    @Test
    void testSameDayEvent_endingBefore8_isStillShown() throws IOException, ParserException {
        String ics = createIcsWithEvent("Frühschicht",
                "20260210T050000", "20260210T070000");
        InputStream stream = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8));

        EventRepository repo = new EventRepository(stream, LocalDate.of(2026, 2, 10), null);
        List<Event> events = repo.getEventsForDay(LocalDate.of(2026, 2, 10));

        // Event muss angezeigt werden – die 8-Uhr-Filterung gilt nur für Übernacht-Events
        assertThat(events).hasSize(1);
        Event event = events.get(0);
        assertThat(event.summary()).isEqualTo("Frühschicht");
        assertThat(event.startTime()).isEqualTo(LocalDateTime.of(2026, 2, 10, 5, 0));
        assertThat(event.endTime()).isEqualTo(LocalDateTime.of(2026, 2, 10, 7, 0));
    }

    /**
     * Testet dass VACATION-Events korrekt mit EventSource.VACATION geladen werden.
     */
    @Test
    void testVacationFeed_parsedWithCorrectSource() throws IOException, ParserException {
        String familyIcs = createIcsWithEvent("Meeting", "20260210T090000", "20260210T100000");
        String vacationIcs = createIcsWithAllDayEvent("Pfingstferien", "20260209", "20260215");

        InputStream familyStream = new ByteArrayInputStream(familyIcs.getBytes(StandardCharsets.UTF_8));
        InputStream vacationStream = new ByteArrayInputStream(vacationIcs.getBytes(StandardCharsets.UTF_8));

        EventRepository repo = new EventRepository(familyStream, null, vacationStream,
                LocalDate.of(2026, 2, 10), null);
        List<Event> events = repo.getEventsForDay(LocalDate.of(2026, 2, 10));

        assertThat(events).hasSize(2);
        assertThat(events).extracting(Event::source)
                .containsExactlyInAnyOrder(EventSource.CALENDAR, EventSource.VACATION);
        assertThat(events).extracting(Event::summary)
                .containsExactlyInAnyOrder("Meeting", "Pfingstferien");
    }

    // ========== Helper Methods ==========

    private String createIcsWithEvent(String summary, String dtStart, String dtEnd) {
        return "BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:-//Test//Test//EN\r\n" +
                "BEGIN:VTIMEZONE\r\n" +
                "TZID:Europe/Berlin\r\n" +
                "BEGIN:STANDARD\r\n" +
                "DTSTART:19701025T030000\r\n" +
                "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=10\r\n" +
                "TZOFFSETFROM:+0200\r\n" +
                "TZOFFSETTO:+0100\r\n" +
                "END:STANDARD\r\n" +
                "BEGIN:DAYLIGHT\r\n" +
                "DTSTART:19700329T020000\r\n" +
                "RRULE:FREQ=YEARLY;BYDAY=-1SU;BYMONTH=3\r\n" +
                "TZOFFSETFROM:+0100\r\n" +
                "TZOFFSETTO:+0200\r\n" +
                "END:DAYLIGHT\r\n" +
                "END:VTIMEZONE\r\n" +
                "BEGIN:VEVENT\r\n" +
                "DTSTART;TZID=Europe/Berlin:" + dtStart + "\r\n" +
                "DTEND;TZID=Europe/Berlin:" + dtEnd + "\r\n" +
                "SUMMARY:" + summary + "\r\n" +
                "UID:" + summary.hashCode() + "@test\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR\r\n";
    }

    private String createIcsWithAllDayEvent(String summary, String dtStart, String dtEnd) {
        return "BEGIN:VCALENDAR\r\n" +
                "VERSION:2.0\r\n" +
                "PRODID:-//Test//Test//EN\r\n" +
                "BEGIN:VEVENT\r\n" +
                "DTSTART;VALUE=DATE:" + dtStart + "\r\n" +
                "DTEND;VALUE=DATE:" + dtEnd + "\r\n" +
                "SUMMARY:" + summary + "\r\n" +
                "UID:" + summary.hashCode() + "@test\r\n" +
                "END:VEVENT\r\n" +
                "END:VCALENDAR\r\n";
    }
}
