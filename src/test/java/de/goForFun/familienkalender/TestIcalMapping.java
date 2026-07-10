package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import net.fortuna.ical4j.data.ParserException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class TestIcalMapping {

    @Test
    public void testParseIcalMapsAllFields() throws ParserException, IOException {
        InputStream is = getClass().getResourceAsStream("/Familien-Pinnwand.ics");

        // Januar 2026 laden, dann 24.01. abfragen
        CalendarParser parser = new CalendarParser(is, LocalDate.of(2026, 1, 24));
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 1, 24));

        assertThat(events).hasSize(2);

        Event partyEvent = events.stream()
                .filter(e -> "Feier Innenstadt".equals(e.summary()))
                .findFirst()
                .orElseThrow();

        assertThat(partyEvent.startTime()).isEqualTo(LocalDateTime.of(2026, 1, 24, 19, 0));
        assertThat(partyEvent.endTime()).isEqualTo(LocalDateTime.of(2026, 1, 24, 23, 59));
        assertThat(partyEvent.color()).isEqualTo("#D50000");
        assertThat(partyEvent.participants()).containsExactlyInAnyOrder("max@example.com", "lisa@example.com");

        Event schwimmkursEvent = events.stream()
                .filter(e -> "Schwimmkurs".equals(e.summary()))
                .findFirst()
                .orElseThrow();

        assertThat(schwimmkursEvent.startTime()).isNotNull();
        assertThat(schwimmkursEvent.endTime()).isNotNull();
        assertThat(schwimmkursEvent.color()).isEqualTo("#7986CB");
    }

    @Test
    public void testParseIcalEventWithoutColor() throws ParserException, IOException {
        InputStream is = getClass().getResourceAsStream("/Familien-Pinnwand.ics");

        // Februar 2026 laden, dann 08.02. abfragen
        CalendarParser parser = new CalendarParser(is, LocalDate.of(2026, 2, 8));
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 2, 8));

        assertThat(events).hasSizeGreaterThanOrEqualTo(1);

        Event wellnessEvent = events.stream()
                .filter(e -> "Wellness".equals(e.summary()))
                .findFirst()
                .orElseThrow();

        assertThat(wellnessEvent.startTime()).isEqualTo(LocalDateTime.of(2026, 2, 8, 16, 30));
        assertThat(wellnessEvent.endTime()).isEqualTo(LocalDateTime.of(2026, 2, 8, 22, 30));
        assertThat(wellnessEvent.color()).isNull();
        assertThat(wellnessEvent.participants()).containsExactly("lisa@example.com");
    }

    @Test
    public void testParseIcalDateOnlyEvent() throws ParserException, IOException {
        InputStream is = getClass().getResourceAsStream("/Familien-Pinnwand.ics");

        // Januar 2026 laden, dann 15.01. abfragen
        CalendarParser parser = new CalendarParser(is, LocalDate.of(2026, 1, 15));
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 1, 15));

        assertThat(events).hasSize(1);

        Event event = events.getFirst();
        assertThat(event.summary()).isNotNull();
        assertThat(event.startTime()).isNotNull();
        assertThat(event.endTime()).isNotNull();
    }
}
