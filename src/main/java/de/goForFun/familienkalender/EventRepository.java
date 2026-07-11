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
 * Zentrale Event-Verwaltung: Sammelt Events aus verschiedenen Quellen (iCal-Feed, Schulkalender, Ferien, Feiertage)
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
        this(feedUri, null, null, referenceDate, holidayProvider);
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
        this(feedUri, schoolFeedUri, null, referenceDate, holidayProvider);
    }

    /**
     * Erstellt ein EventRepository aus einem iCal-Feed, optionalem Schulkalender-Feed,
     * optionalem Ferien-Feed und optionalem HolidayProvider.
     *
     * @param feedUri             URI des Familien-iCal-Feeds
     * @param schoolFeedUri       URI des Schulkalender-Feeds (kann null sein)
     * @param vacationFeedUri     URI des Ferien-Feeds (kann null sein, z.B. schulferien.org)
     * @param referenceDate       Referenzdatum – der Monat dieses Datums wird geladen
     * @param holidayProvider     optionaler HolidayProvider (kann null sein)
     */
    public EventRepository(URI feedUri, URI schoolFeedUri, URI vacationFeedUri, LocalDate referenceDate, HolidayProvider holidayProvider) throws IOException, ParserException {
        YearMonth month = YearMonth.from(referenceDate);
        IcalParser icalParser = new IcalParser();
        List<Event> events = new ArrayList<>(icalParser.parse(feedUri, month, EventSource.CALENDAR));
        if (schoolFeedUri != null) {
            events.addAll(icalParser.parse(schoolFeedUri, month, EventSource.SCHOOL));
        }
        if (vacationFeedUri != null) {
            events.addAll(icalParser.parse(vacationFeedUri, month, EventSource.VACATION));
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
        this(inputStream, null, null, referenceDate, holidayProvider);
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
        this(inputStream, schoolInputStream, null, referenceDate, holidayProvider);
    }

    /**
     * Erstellt ein EventRepository aus InputStreams (z.B. für Tests).
     *
     * @param inputStream           InputStream mit Familien-iCal-Daten
     * @param schoolInputStream     InputStream mit Schulkalender-Daten (kann null sein)
     * @param vacationInputStream   InputStream mit Ferien-Daten (kann null sein)
     * @param referenceDate         Referenzdatum – der Monat dieses Datums wird geladen
     * @param holidayProvider       optionaler HolidayProvider (kann null sein)
     */
    public EventRepository(InputStream inputStream, InputStream schoolInputStream, InputStream vacationInputStream, LocalDate referenceDate, HolidayProvider holidayProvider) throws IOException, ParserException {
        YearMonth month = YearMonth.from(referenceDate);
        IcalParser icalParser = new IcalParser();
        List<Event> events = new ArrayList<>(icalParser.parse(inputStream, month, EventSource.CALENDAR));
        if (schoolInputStream != null) {
            events.addAll(icalParser.parse(schoolInputStream, month, EventSource.SCHOOL));
        }
        if (vacationInputStream != null) {
            events.addAll(icalParser.parse(vacationInputStream, month, EventSource.VACATION));
        }
        if (holidayProvider != null) {
            events.addAll(holidayProvider.getHolidaysForRange(month.atDay(1), month.atEndOfMonth()));
        }
        this.allEvents = Collections.unmodifiableList(events);
    }

    /**
     * Gibt alle Events zurück, die an einem bestimmten Tag stattfinden.
     * Events vom Vortag, die über Mitternacht hinausgehen und am angefragten Tag
     * länger als bis 08:00 dauern, werden mit Startzeit 00:00 des Tages angezeigt.
     * Events vom Vortag, die vor 08:00 enden, werden nicht angezeigt.
     */
    public List<Event> getEventsForDay(LocalDate day) {
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime threshold = day.atTime(8, 0);

        List<Event> result = new ArrayList<>();

        for (Event event : getEventsForRange(day, day)) {
            if (event.startTime() == null) continue;
            LocalDate eventStartDate = event.startTime().toLocalDate();

            if (eventStartDate.isBefore(day) && !isAllDayEvent(event)) {
                // Übernacht-Event: nur aufnehmen wenn es nach 08:00 am Tag endet
                if (event.endTime() != null && event.endTime().isAfter(threshold)) {
                    result.add(new Event(
                            event.participants(),
                            dayStart,
                            event.endTime(),
                            event.summary(),
                            event.color(),
                            event.source()
                    ));
                }
                // Sonst: nicht anzeigen (endet vor 08:00)
            } else {
                result.add(event);
            }
        }

        return result.stream()
                .sorted(Comparator.comparing(Event::startTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Prüft ob ein Event ganztägig ist (startet um 00:00, endet um 00:00).
     */
    private boolean isAllDayEvent(Event event) {
        if (event.startTime() == null || event.endTime() == null) return false;
        return event.startTime().getHour() == 0 && event.startTime().getMinute() == 0
                && event.endTime().getHour() == 0 && event.endTime().getMinute() == 0;
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

    /**
     * Gibt eine alphabetisch sortierte Liste aller bekannten Teilnehmer (Personen) zurück,
     * extrahiert aus allen Events im Repository. Alphabetische Sortierung garantiert eine
     * konsistente Reihenfolge unabhängig davon, in welcher Reihenfolge Events geladen wurden.
     */
    public List<String> getAllParticipants() {
        return allEvents.stream()
                .map(Event::participants)
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .sorted()
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
