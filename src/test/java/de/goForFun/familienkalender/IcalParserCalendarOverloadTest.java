package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Calendar-Überladung liefert für die Graph-Fixture dasselbe wie der Stream-Pfad.
 */
class IcalParserCalendarOverloadTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2027, 2, 28);

    private static byte[] fixtureIcs() throws Exception {
        try (InputStream in = IcalParserCalendarOverloadTest.class
                .getResourceAsStream("/graph-calendarview-sample.expected.ics")) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n").trim();
            String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n"
                    + body.replace("\n", "\r\n") + "\r\nEND:VCALENDAR\r\n";
            return ics.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Test
    void calendarOverload_returnsSameAsStreamPath() throws Exception {
        byte[] ics = fixtureIcs();
        IcalParser parser = new IcalParser();

        List<Event> fromStream = parser.parse(new ByteArrayInputStream(ics), FROM, TO, EventSource.CALENDAR);
        Calendar calendar = new CalendarBuilder().build(new ByteArrayInputStream(ics));
        List<Event> fromCalendar = parser.parse(calendar, FROM, TO, EventSource.CALENDAR);

        assertThat(fromStream).hasSize(4);
        assertThat(fromCalendar).isEqualTo(fromStream);
        assertThat(fromCalendar).allMatch(e -> e.source() == EventSource.CALENDAR);
    }

    @Test
    void utcDtstart_isNotDropped_andConvertedToAppZone() throws Exception {
        String ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:utc-1\r\nDTSTAMP:20261101T000000Z\r\n"
                + "DTSTART:20261124T080000Z\r\nDTEND:20261124T090000Z\r\nSUMMARY:UTC-Termin\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
        IcalParser parser = new IcalParser();

        List<Event> fromStream = parser.parse(
                new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8)), FROM, TO, EventSource.CALENDAR);
        Calendar calendar = new CalendarBuilder().build(new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8)));
        List<Event> fromCalendar = parser.parse(calendar, FROM, TO, EventSource.CALENDAR);

        assertThat(fromStream).hasSize(1);
        assertThat(fromCalendar).isEqualTo(fromStream);
        java.time.ZonedDateTime expected = java.time.ZonedDateTime.of(2026, 11, 24, 8, 0, 0, 0, java.time.ZoneOffset.UTC)
                .withZoneSameInstant(AppTimeZone.get());
        assertThat(fromStream.get(0).startTime()).isEqualTo(expected.toLocalDateTime());
        assertThat(fromStream.get(0).endTime()).isEqualTo(expected.plusHours(1).toLocalDateTime());
    }
}
