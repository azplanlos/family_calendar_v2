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
        try (InputStream inputStream = feedUri.toURL().openStream()) {
            return parse(inputStream, month);
        }
    }

    /**
     * Parst einen iCal-InputStream und expandiert alle Events im angegebenen Monat.
     */
    @SuppressWarnings("unchecked")
    public List<Event> parse(InputStream inputStream, YearMonth month) throws IOException, ParserException {
        CalendarBuilder builder = new CalendarBuilder();
        Calendar calendar = builder.build(inputStream);

        ZonedDateTime monthStart = month.atDay(1).atStartOfDay(ZoneId.systemDefault());
        ZonedDateTime monthEnd = month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault());
        Period<ZonedDateTime> monthPeriod = new Period<>(monthStart, Duration.between(monthStart, monthEnd));

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
                        EventSource.CALENDAR
                ));
            }
        }

        return events;
    }
}
