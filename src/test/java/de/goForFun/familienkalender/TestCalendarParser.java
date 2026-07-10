package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import net.fortuna.ical4j.data.ParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests für den CalendarParser mit der Test-ICS-Datei (Familien-Pinnwand.ics).
 */
public class TestCalendarParser {

    private CalendarParser parser;

    @BeforeEach
    void setUp() throws IOException, ParserException {
        // Referenzdatum: Februar 2026 – dort liegen einige Events + wiederkehrende
        InputStream icsStream = getClass().getResourceAsStream("/Familien-Pinnwand.ics");
        parser = new CalendarParser(icsStream, LocalDate.of(2026, 2, 1));
    }

    @Test
    void testGetEventsForDay_singleEvent() {
        // 8. Februar 2026: "Wellness" (16:30-22:30) und "Kinderkurs" (10:00-11:30)
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 2, 8));

        assertThat(events).isNotEmpty();
        assertThat(events).extracting(Event::summary)
                .contains("Wellness", "Kinderkurs");
    }

    @Test
    void testGetEventsForDay_recurringWeeklyEvent() {
        // "Schwimmkurs" ist wöchentlich samstags (mit Ausnahmen).
        // 7. Februar 2026 ist ein Samstag – sollte enthalten sein (keine EXDATE für dieses Datum)
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 2, 7));

        assertThat(events).extracting(Event::summary)
                .contains("Schwimmkurs");
    }

    @Test
    void testGetEventsForDay_recurringAllDayEvent() {
        // "Linsen wechseln" ist alle 4 Wochen donnerstags (ganztägig), Start 18.12.2025.
        // 12. Februar 2026 ist ein Donnerstag, 8 Wochen nach dem Start → sollte enthalten sein
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 2, 12));

        assertThat(events).extracting(Event::summary)
                .contains("Linsen wechseln");
    }

    @Test
    void testGetEventsForDay_noEventsOnEmptyDay() {
        // 2. Februar 2026 ist ein Montag – "Sportgruppe Montag" hat EXDATE am 05.01.2026,
        // aber 02.02 sollte enthalten sein
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 2, 2));

        // Sportgruppe Montag sollte da sein
        assertThat(events).extracting(Event::summary)
                .contains("Sportgruppe Montag");
    }

    @Test
    void testGetEventsForDay_musikunterricht() {
        // "Musikunterricht" ist wöchentlich dienstags, Start 16.09.2025.
        // 3. Februar 2026 ist ein Dienstag – sollte enthalten sein (keine EXDATE für dieses Datum)
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 2, 3));

        assertThat(events).extracting(Event::summary)
                .contains("Musikunterricht");
    }

    @Test
    void testGetEventsForRange_entireMonth() {
        // Gesamter Februar 2026
        List<Event> events = parser.getEventsForRange(
                LocalDate.of(2026, 2, 1),
                LocalDate.of(2026, 2, 28)
        );

        // Sollte mehrere Events enthalten (Schwimmkurs samstags, Sportgruppe montags, etc.)
        assertThat(events).hasSizeGreaterThan(5);
    }

    @Test
    void testGetEventsForRange_weekRange() {
        // Eine Woche: 2.–8. Februar 2026
        List<Event> events = parser.getEventsForRange(
                LocalDate.of(2026, 2, 2),
                LocalDate.of(2026, 2, 8)
        );

        // Mindestens: Sportgruppe Mo, Musikunterricht Di, Schwimmkurs Sa, Wellness So, Kinderkurs So
        assertThat(events).hasSizeGreaterThanOrEqualTo(4);
        assertThat(events).extracting(Event::summary)
                .contains("Sportgruppe Montag", "Musikunterricht", "Schwimmkurs");
    }

    @Test
    void testEventsAreSortedByStartTime() {
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 2, 8));

        // Events sollten nach Startzeit sortiert sein
        for (int i = 0; i < events.size() - 1; i++) {
            assertThat(events.get(i).startTime())
                    .isBeforeOrEqualTo(events.get(i + 1).startTime());
        }
    }

    @Test
    void testEventOutsideMonth_notIncluded() {
        // "Feier Innenstadt" ist am 24. Januar 2026 – liegt außerhalb des Februar-Monats
        List<Event> events = parser.getEventsForDay(LocalDate.of(2026, 1, 24));

        // Da wir Februar geladen haben, sollte der Januar-Event nicht gefunden werden
        assertThat(events).extracting(Event::summary)
                .doesNotContain("Feier Innenstadt");
    }
}
