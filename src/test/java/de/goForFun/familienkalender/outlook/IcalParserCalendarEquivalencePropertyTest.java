package de.goForFun.familienkalender.outlook;

import de.goForFun.familienkalender.IcalParser;
import de.goForFun.familienkalender.AppTimeZone;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Feature: outlook-graph-source, Property 25: Der Device-Parser liest den Graph-Kalender
 * äquivalent zur Serialisierung.
 *
 * <p><b>Validates: Requirements 13.5</b>
 */
class IcalParserCalendarEquivalencePropertyTest extends GraphArbitraries {

    /** Geräte-Fenster [from, to] (inklusive), 1 bis 150 Tage, rund um die generierten Event-Zeiten. */
    record Window(LocalDate from, LocalDate to) {
    }

    @Provide
    Arbitrary<Window> windows() {
        Arbitrary<LocalDate> from = Arbitraries.integers().between(0, 5 * 365)
                .map(d -> LocalDate.of(2024, 1, 1).plusDays(d));
        Arbitrary<Integer> length = Arbitraries.integers().between(0, 149);
        return Combinators.combine(from, length).as((f, l) -> new Window(f, f.plusDays(l)));
    }

    @Property(tries = 200)
    void calendarOverloadEqualsParsingSerializedCalendar(
            @ForAll("graphEventLists") List<JsonNode> events,
            @ForAll("windows") Window window) throws Exception {
        Calendar calendar = GraphCalendarMapper.toCalendar(events, AppTimeZone.get()).calendar();
        IcalParser parser = new IcalParser();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CalendarOutputter().output(calendar, out);

        List<Event> fromStream = parser.parse(new ByteArrayInputStream(out.toByteArray()),
                window.from(), window.to(), EventSource.CALENDAR);
        List<Event> fromCalendar = parser.parse(calendar, window.from(), window.to(), EventSource.CALENDAR);

        assertEquals(fromStream, fromCalendar);
    }
}
