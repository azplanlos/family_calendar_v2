package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
import net.fortuna.ical4j.data.ParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.*;
import java.util.*;

/**
 * Zentrale Event-Verwaltung: Sammelt Events aus verschiedenen Quellen (iCal-Feed, Schulkalender, Feiertage)
 * und bietet Filterung nach Tag oder Zeitraum.
 */
public class EventRepository {

    private final List<Event> allEvents;

    /**
     * Erstellt ein EventRepository aus einem iCal-Feed (URI) und optionalem HolidayProvider.
     *
     * @param feedUri         URI des iCal-Feeds
     * @param referenceDate   Referenzdatum – der Monat dieses Datums wird geladen
     * @param holidayProvider optionaler HolidayProvider (kann null sein)
     */
    public EventRepository(URI feedUri, LocalDate referenceDate, HolidayProvider holidayProvider) throws IOException, ParserException {
        this(feedUri, null, referenceDate, holidayProvider);
    }

    /**
     * Erstellt ein EventRepository aus einem iCal-Feed, einem optionalen Schulkalender-Feed und optionalem HolidayProvider.
     *
     * @param feedUri             URI des Familien-iCal-Feeds
     * @param schoolFeedUri       URI des Schulkalender-Feeds (kann null sein)
     * @param referenceDate       Referenzdatum – der Monat dieses Datums wird geladen
     * @param holidayProvider     optionaler HolidayProvider (kann null sein)
     */
    public EventRepository(URI feedUri, URI schoolFeedUri, LocalDate referenceDate, HolidayProvider holidayProvider) throws IOException, ParserException {
        YearMonth month = YearMonth.from(referenceDate);
        IcalParser icalParser = new IcalParser();
        List<Event> events = new ArrayList<>(icalParser.parse(feedUri, month, EventSource.CALENDAR));
        if (schoolFeedUri != null) {
            events.addAll(icalParser.parse(schoolFeedUri, month, EventSource.SCHOOL));
        }
        if (holidayProvider != null) {
            events.addAll(holidayProvider.getHolidaysForRange(month.atDay(1), month.atEndOfMonth()));
        }
        this.allEvents = Collections.unmodifiableList(events);
    }

    /**
     * Erstellt ein EventRepository aus einem InputStream (z.B. für Tests).
     *
     * @param inputStream     InputStream mit iCal-Daten
     * @param referenceDate   Referenzdatum – der Monat dieses Datums wird geladen
     * @param holidayProvider optionaler HolidayProvider (kann null sein)
     */
    public EventRepository(InputStream inputStream, LocalDate referenceDate, HolidayProvider holidayProvider) throws IOException, ParserException {
        this(inputStream, null, referenceDate, holidayProvider);
    }

    /**
     * Erstellt ein EventRepository aus InputStreams (z.B. für Tests).
     *
     * @param inputStream         InputStream mit Familien-iCal-Daten
     * @param schoolInputStream   InputStream mit Schulkalender-Daten (kann null sein)
     * @param referenceDate       Referenzdatum – der Monat dieses Datums wird geladen
     * @param holidayProvider     optionaler HolidayProvider (kann null sein)
     */
    public EventRepository(InputStream inputStream, InputStream schoolInputStream, LocalDate referenceDate, HolidayProvider holidayProvider) throws IOException, ParserException {
        YearMonth month = YearMonth.from(referenceDate);
        IcalParser icalParser = new IcalParser();
        List<Event> events = new ArrayList<>(icalParser.parse(inputStream, month, EventSource.CALENDAR));
        if (schoolInputStream != null) {
            events.addAll(icalParser.parse(schoolInputStream, month, EventSource.SCHOOL));
        }
        if (holidayProvider != null) {
            events.addAll(holidayProvider.getHolidaysForRange(month.atDay(1), month.atEndOfMonth()));
        }
        this.allEvents = Collections.unmodifiableList(events);
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

        return allEvents.stream()
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
        return eventStart.isBefore(rangeEnd) && eventEnd.isAfter(rangeStart);
    }
}
