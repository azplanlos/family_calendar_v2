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
    private final List<String> errors;

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
     * Einzelne Feed-Fehler werden abgefangen – das Repository liefert dann die Events
     * der erfolgreich geladenen Feeds und sammelt die Fehlermeldungen in {@link #getErrors()}.
     *
     * @param feedUri             URI des Familien-iCal-Feeds
     * @param schoolFeedUri       URI des Schulkalender-Feeds (kann null sein)
     * @param vacationFeedUri     URI des Ferien-Feeds (kann null sein, z.B. schulferien.org)
     * @param referenceDate       Referenzdatum – der Monat dieses Datums wird geladen
     * @param holidayProvider     optionaler HolidayProvider (kann null sein)
     */
    public EventRepository(URI feedUri, URI schoolFeedUri, URI vacationFeedUri, LocalDate referenceDate, HolidayProvider holidayProvider) throws IOException, ParserException {
        IcalParser icalParser = new IcalParser();
        List<Event> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Ladebereich berechnen: aktueller Monat + Folgemonat, damit Monatswechsel
        // sowohl in der Tagesübersicht (Morgen) als auch im 5-Wochen-Kalenderraster abgedeckt sind.
        LocalDate rangeStart = YearMonth.from(referenceDate).atDay(1);
        LocalDate rangeEnd = YearMonth.from(referenceDate).plusMonths(1).atEndOfMonth();

        // Hauptkalender-Feed laden
        try {
            events.addAll(icalParser.parse(feedUri, rangeStart, rangeEnd, EventSource.CALENDAR));
        } catch (IOException | ParserException e) {
            errors.add("Kalender-Feed: " + e.getMessage());
        }

        // Schulkalender-Feed laden
        if (schoolFeedUri != null) {
            try {
                events.addAll(icalParser.parse(schoolFeedUri, rangeStart, rangeEnd, EventSource.SCHOOL));
            } catch (IOException | ParserException e) {
                errors.add("Schulkalender: " + e.getMessage());
            }
        }

        // Ferien-Feed laden
        if (vacationFeedUri != null) {
            try {
                events.addAll(icalParser.parse(vacationFeedUri, rangeStart, rangeEnd, EventSource.VACATION));
            } catch (IOException | ParserException e) {
                errors.add("Ferien-Feed: " + e.getMessage());
            }
        }

        // Feiertage laden
        if (holidayProvider != null) {
            try {
                events.addAll(holidayProvider.getHolidaysForRange(rangeStart, rangeEnd));
            } catch (Exception e) {
                errors.add("Feiertage: " + e.getMessage());
            }
        }

        this.allEvents = Collections.unmodifiableList(assignParticipantsToUnassigned(events));
        this.errors = Collections.unmodifiableList(errors);
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
     * Einzelne Feed-Fehler werden abgefangen – das Repository liefert dann die Events
     * der erfolgreich geladenen Feeds und sammelt die Fehlermeldungen in {@link #getErrors()}.
     *
     * @param inputStream           InputStream mit Familien-iCal-Daten
     * @param schoolInputStream     InputStream mit Schulkalender-Daten (kann null sein)
     * @param vacationInputStream   InputStream mit Ferien-Daten (kann null sein)
     * @param referenceDate         Referenzdatum – der Monat dieses Datums wird geladen
     * @param holidayProvider       optionaler HolidayProvider (kann null sein)
     */
    public EventRepository(InputStream inputStream, InputStream schoolInputStream, InputStream vacationInputStream, LocalDate referenceDate, HolidayProvider holidayProvider) throws IOException, ParserException {
        IcalParser icalParser = new IcalParser();
        List<Event> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Ladebereich berechnen: aktueller Monat + Folgemonat, damit Monatswechsel
        // sowohl in der Tagesübersicht (Morgen) als auch im 5-Wochen-Kalenderraster abgedeckt sind.
        LocalDate rangeStart = YearMonth.from(referenceDate).atDay(1);
        LocalDate rangeEnd = YearMonth.from(referenceDate).plusMonths(1).atEndOfMonth();

        // Hauptkalender-Feed laden
        try {
            events.addAll(icalParser.parse(inputStream, rangeStart, rangeEnd, EventSource.CALENDAR));
        } catch (IOException | ParserException e) {
            errors.add("Kalender-Feed: " + e.getMessage());
        }

        // Schulkalender-Feed laden
        if (schoolInputStream != null) {
            try {
                events.addAll(icalParser.parse(schoolInputStream, rangeStart, rangeEnd, EventSource.SCHOOL));
            } catch (IOException | ParserException e) {
                errors.add("Schulkalender: " + e.getMessage());
            }
        }

        // Ferien-Feed laden
        if (vacationInputStream != null) {
            try {
                events.addAll(icalParser.parse(vacationInputStream, rangeStart, rangeEnd, EventSource.VACATION));
            } catch (IOException | ParserException e) {
                errors.add("Ferien-Feed: " + e.getMessage());
            }
        }

        // Feiertage laden
        if (holidayProvider != null) {
            try {
                events.addAll(holidayProvider.getHolidaysForRange(rangeStart, rangeEnd));
            } catch (Exception e) {
                errors.add("Feiertage: " + e.getMessage());
            }
        }

        this.allEvents = Collections.unmodifiableList(assignParticipantsToUnassigned(events));
        this.errors = Collections.unmodifiableList(errors);
    }

    /**
     * Gibt alle Events zurück, die an einem bestimmten Tag stattfinden.
     *
     * Mehrtägige getimte Events (z.B. 15.07. 10:00 – 18.07. 09:00) werden wie folgt behandelt:
     * - Starttag (15.07.): mit Startzeit anzeigen (10:00)
     * - Zwischentage (16./17.07.): als ganztägig rendern (00:00–00:00), da der Tag komplett enthalten ist
     * - Endtag (18.07.): mit Startzeit 00:00 und Endzeit anzeigen (09:00)
     *
     * Eintägige Übernacht-Events (enden vor nächstem Mitternacht):
     * - Nur anzeigen wenn sie nach 08:00 am Folgetag enden
     */
    public List<Event> getEventsForDay(LocalDate day) {
        LocalDateTime dayStart = day.atStartOfDay();
        LocalDateTime dayEnd = day.plusDays(1).atStartOfDay();
        LocalDateTime threshold = day.atTime(8, 0);

        List<Event> result = new ArrayList<>();

        for (Event event : getEventsForRange(day, day)) {
            if (event.startTime() == null) continue;

            // Bereits ganztägige Events (00:00/00:00) unverändert übernehmen
            if (isAllDayEvent(event)) {
                result.add(event);
                continue;
            }

            LocalDate eventStartDate = event.startTime().toLocalDate();
            LocalDate eventEndDate = event.endTime() != null ? event.endTime().toLocalDate() : eventStartDate;

            // Prüfen ob das Event mehrtägig ist (mehr als 1 Kalendertag)
            boolean isMultiDay = event.endTime() != null && eventEndDate.isAfter(eventStartDate);

            if (!isMultiDay) {
                // Eintägiges Event: normal anzeigen
                result.add(event);
            } else if (eventStartDate.equals(day)) {
                // Starttag eines mehrtägigen Events: mit Original-Startzeit anzeigen
                result.add(event);
            } else if (eventEndDate.equals(day)) {
                // Endtag eines mehrtägigen Events: von 00:00 bis Endzeit anzeigen
                if (event.endTime().isAfter(dayStart)) {
                    result.add(new Event(
                            event.participants(),
                            dayStart,
                            event.endTime(),
                            event.summary(),
                            event.color(),
                            event.source(),
                            event.url()
                    ));
                }
            } else if (eventStartDate.isBefore(day) && eventEndDate.isAfter(day)) {
                // Zwischentag: Tag ist komplett enthalten → als ganztägig rendern (00:00–00:00)
                result.add(new Event(
                        event.participants(),
                        dayStart,
                        dayEnd,
                        event.summary(),
                        event.color(),
                        event.source(),
                        event.url()
                ));
            } else if (eventStartDate.isBefore(day)) {
                // Sonstige Übernacht-Events: nur anzeigen wenn nach 08:00 endend
                if (event.endTime() != null && event.endTime().isAfter(threshold)) {
                    result.add(new Event(
                            event.participants(),
                            dayStart,
                            event.endTime(),
                            event.summary(),
                            event.color(),
                            event.source(),
                            event.url()
                    ));
                }
            }
        }

        return result.stream()
                .sorted(Comparator.comparing(event -> event.startTime(), Comparator.nullsLast(Comparator.naturalOrder())))
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
                .sorted(Comparator.comparing(event -> event.startTime(), Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /**
     * Gibt eine alphabetisch sortierte Liste aller bekannten Teilnehmer (Personen) zurück,
     * extrahiert aus allen Events im Repository. Alphabetische Sortierung garantiert eine
     * konsistente Reihenfolge unabhängig davon, in welcher Reihenfolge Events geladen wurden.
     */
    public List<String> getAllParticipants() {
        return allEvents.stream()
                .map(event -> event.participants())
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Gibt die Liste der Fehlermeldungen zurück, die beim Laden der Feeds aufgetreten sind.
     * Leer wenn alle Feeds erfolgreich geladen wurden.
     */
    public List<String> getErrors() {
        return errors;
    }

    /**
     * Weist Events ohne Teilnehmer alle bekannten Familienmitglieder zu.
     * Die Familienmitglieder werden aus den Events ermittelt, die bereits Teilnehmer haben.
     * Diese Zuweisung gilt nur für Events aus dem Familienkalender (CALENDAR),
     * nicht für Schulkalender, Ferien oder Feiertage.
     */
    private List<Event> assignParticipantsToUnassigned(List<Event> events) {
        List<String> allParticipants = events.stream()
                .map(event -> event.participants())
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .sorted()
                .toList();

        if (allParticipants.isEmpty()) {
            return events;
        }

        return events.stream()
                .<Event>map(event -> {
                    if (EventSource.CALENDAR.equals(event.source())
                            && (event.participants() == null || event.participants().isEmpty())) {
                        return new Event(
                                allParticipants,
                                event.startTime(),
                                event.endTime(),
                                event.summary(),
                                event.color(),
                                event.source(),
                                event.url()
                        );
                    }
                    return event;
                })
                .collect(java.util.stream.Collectors.toList());
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
