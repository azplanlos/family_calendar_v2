package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
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
 * Reine iCal-Parsing-Logik: Lädt einen iCal-Feed und expandiert wiederkehrende Events
 * für einen gegebenen Monat. Gibt eine flache Liste von Events zurück.
 */
public class IcalParser {

    private final IcalMapping mapper = Mappers.getMapper(IcalMapping.class);

    /**
     * Lädt den iCal-Feed von einer URI und parst alle Events im Monat des Referenzdatums.
     */
    public List<Event> parse(URI feedUri, YearMonth month) throws IOException, ParserException {
        return parse(feedUri, month, EventSource.CALENDAR);
    }

    /**
     * Lädt den iCal-Feed von einer URI und parst alle Events im Monat des Referenzdatums.
     *
     * @param feedUri URI des iCal-Feeds
     * @param month   der zu ladende Monat
     * @param source  die EventSource, die den geparsten Events zugewiesen wird
     */
    public List<Event> parse(URI feedUri, YearMonth month, EventSource source) throws IOException, ParserException {
        try (InputStream inputStream = feedUri.toURL().openStream()) {
            return parse(inputStream, month, source);
        }
    }

    /**
     * Lädt den iCal-Feed von einer URI und parst alle Events im angegebenen Datumsbereich.
     *
     * @param feedUri URI des iCal-Feeds
     * @param from    Startdatum (inklusive)
     * @param to      Enddatum (inklusive)
     * @param source  die EventSource, die den geparsten Events zugewiesen wird
     */
    public List<Event> parse(URI feedUri, LocalDate from, LocalDate to, EventSource source) throws IOException, ParserException {
        try (InputStream inputStream = feedUri.toURL().openStream()) {
            return parse(inputStream, from, to, source);
        }
    }

    /**
     * Parst einen iCal-InputStream und expandiert alle Events im angegebenen Monat.
     */
    public List<Event> parse(InputStream inputStream, YearMonth month) throws IOException, ParserException {
        return parse(inputStream, month, EventSource.CALENDAR);
    }

    /**
     * Parst einen iCal-InputStream und expandiert alle Events im angegebenen Datumsbereich.
     *
     * @param inputStream InputStream mit iCal-Daten
     * @param from        Startdatum (inklusive)
     * @param to          Enddatum (inklusive)
     * @param source      die EventSource, die den geparsten Events zugewiesen wird
     */
    public List<Event> parse(InputStream inputStream, LocalDate from, LocalDate to, EventSource source) throws IOException, ParserException {
        ZonedDateTime rangeStart = from.atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime rangeEnd = to.plusDays(1).atStartOfDay(ZoneId.systemDefault());
        return parseForPeriod(inputStream, rangeStart, rangeEnd, source);
    }

    /**
     * Parst einen iCal-InputStream und expandiert alle Events im angegebenen Monat.
     *
     * @param inputStream InputStream mit iCal-Daten
     * @param month       der zu ladende Monat
     * @param source      die EventSource, die den geparsten Events zugewiesen wird
     */
    @SuppressWarnings("unchecked")
    public List<Event> parse(InputStream inputStream, YearMonth month, EventSource source) throws IOException, ParserException {
        ZonedDateTime monthStart = month.atDay(1).atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault());
        return parseForPeriod(inputStream, monthStart, monthEnd, source);
    }

    /**
     * Interne Methode: Parst einen iCal-InputStream und expandiert alle Events im angegebenen Zeitraum.
     */
    @SuppressWarnings("unchecked")
    private List<Event> parseForPeriod(InputStream inputStream, ZonedDateTime periodStart, ZonedDateTime periodEnd, EventSource source) throws IOException, ParserException {
        CalendarBuilder builder = new CalendarBuilder();
        Calendar calendar = builder.build(inputStream);

        Period<ZonedDateTime> monthPeriod = new Period<>(periodStart, Duration.between(periodStart, periodEnd));

        List<Event> events = new ArrayList<>();

        for (var component : calendar.getComponents(Component.VEVENT)) {
            VEvent vEvent = (VEvent) component;
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
                    occurrenceStart = ld.atStartOfDay();
                    occurrenceEnd = (end instanceof LocalDate ldEnd)
                            ? ldEnd.atStartOfDay()
                            : ld.plusDays(1).atStartOfDay();
                } else {
                    continue;
                }

                events.add(new Event(
                        baseEvent.participants(),
                        occurrenceStart,
                        occurrenceEnd,
                        baseEvent.summary(),
                        baseEvent.color(),
                        source,
                        baseEvent.url()
                ));
            }
        }

        return events;
    }
}
