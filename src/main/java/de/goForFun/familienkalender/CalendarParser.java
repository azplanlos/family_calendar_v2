package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.IcalMapping;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.component.VEvent;
import org.mapstruct.factory.Mappers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Lädt und parst einen iCal-Feed und hält alle Events eines Monats im Speicher.
 * Berücksichtigt wiederkehrende Events (RRULE) durch Expansion über den gesamten Monat.
 */
public class CalendarParser {

    private final IcalMapping mapper = Mappers.getMapper(IcalMapping.class);
    private final List<Event> monthEvents;
    private final YearMonth month;

    /**
     * Erstellt einen CalendarParser, der den Feed von der URI lädt und alle Events
     * im Monat des übergebenen Datums expandiert und im Speicher hält.
     *
     * @param feedUri URI des iCal-Feeds
     * @param referenceDate Referenzdatum – der Monat dieses Datums wird geladen
     */
    public CalendarParser(URI feedUri, LocalDate referenceDate) throws IOException, ParserException {
        try (InputStream inputStream = feedUri.toURL().openStream()) {
            this.month = YearMonth.from(referenceDate);
            this.monthEvents = parseMonth(inputStream, this.month);
        }
    }

    /**
     * Erstellt einen CalendarParser aus einem InputStream (z.B. für Tests).
     *
     * @param inputStream InputStream mit iCal-Daten
     * @param referenceDate Referenzdatum – der Monat dieses Datums wird geladen
     */
    public CalendarParser(InputStream inputStream, LocalDate referenceDate) throws IOException, ParserException {
        this.month = YearMonth.from(referenceDate);
        this.monthEvents = parseMonth(inputStream, this.month);
    }

    /**
     * Gibt alle Events zurück, die an einem bestimmten Tag stattfinden.
     */
    public List<Event> getEventsForDay(LocalDate day) {
        return getEventsForRange(day, day);
    }

    /**
     * Gibt alle Events zurück, die in der angegebenen Range (inklusive Start und Ende) stattfinden.
     */
    public List<Event> getEventsForRange(LocalDate from, LocalDate to) {
        ZonedDateTime rangeStart = from.atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime rangeEnd = to.plusDays(1).atStartOfDay(ZoneId.systemDefault());

        return monthEvents.stream()
                .filter(event -> eventOverlapsRange(event, rangeStart, rangeEnd))
                .sorted(Comparator.comparing(Event::startTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private boolean eventOverlapsRange(Event event, ZonedDateTime rangeStart, ZonedDateTime rangeEnd) {
        if (event.startTime() == null) {
            return false;
        }
        ZonedDateTime eventStart = event.startTime().atZone(ZoneId.systemDefault());
        ZonedDateTime eventEnd = event.endTime() != null
                ? event.endTime().atZone(ZoneId.systemDefault())
                : eventStart.plusHours(1);
        // Event überlappt Range wenn: eventStart < rangeEnd UND eventEnd > rangeStart
        return eventStart.isBefore(rangeEnd) && eventEnd.isAfter(rangeStart);
    }

    @SuppressWarnings("unchecked")
    private List<Event> parseMonth(InputStream inputStream, YearMonth month) throws IOException, ParserException {
        CalendarBuilder builder = new CalendarBuilder();
        Calendar calendar = builder.build(inputStream);

        ZonedDateTime monthStart = month.atDay(1).atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault());
        Period<ZonedDateTime> monthPeriod = new Period<>(monthStart, Duration.between(monthStart, monthEnd));

        List<Event> events = new ArrayList<>();

        for (var component : calendar.getComponents(Component.VEVENT)) {
            VEvent vEvent = (VEvent) component;
            // calculateRecurrenceSet kann Period<LocalDate> (ganztägig) oder Period<ZonedDateTime> liefern
            Set<?> occurrences = vEvent.calculateRecurrenceSet(monthPeriod);

            for (Object obj : occurrences) {
                Period<?> occurrence = (Period<?>) obj;
                Event baseEvent = mapper.mapVeventEvent(vEvent);

                LocalDateTime occurrenceStart;
                LocalDateTime occurrenceEnd;

                Object start = occurrence.getStart();
                Object end = occurrence.getEnd();

                if (start instanceof ZonedDateTime zdt) {
                    occurrenceStart = zdt.toLocalDateTime();
                    occurrenceEnd = (end instanceof ZonedDateTime zdtEnd)
                            ? zdtEnd.toLocalDateTime()
                            : zdt.plusHours(1).toLocalDateTime();
                } else if (start instanceof LocalDate ld) {
                    // Ganztägiges Event: Start um Mitternacht, Ende am nächsten Tag (oder angegebenes Ende)
                    occurrenceStart = ld.atStartOfDay();
                    occurrenceEnd = (end instanceof LocalDate ldEnd)
                            ? ldEnd.atStartOfDay()
                            : ld.plusDays(1).atStartOfDay();
                } else {
                    // Fallback: überspringen
                    continue;
                }

                events.add(new Event(
                        baseEvent.participants(),
                        occurrenceStart,
                        occurrenceEnd,
                        baseEvent.summary(),
                        baseEvent.color()
                ));
            }
        }

        return events;
    }
}
