package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import net.fortuna.ical4j.data.ParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Abwärtskompatible Fassade – delegiert an {@link EventRepository}.
 * Wird in bestehenden Tests weiterhin verwendet.
 *
 * @deprecated Nutze {@link EventRepository} direkt.
 */
@Deprecated
public class CalendarParser {

    private final EventRepository eventRepository;

    public CalendarParser(URI feedUri, LocalDate referenceDate) throws IOException, ParserException {
        this.eventRepository = new EventRepository(feedUri, referenceDate, null);
    }

    public CalendarParser(InputStream inputStream, LocalDate referenceDate) throws IOException, ParserException {
        this.eventRepository = new EventRepository(inputStream, referenceDate, null);
    }

    public List<Event> getEventsForDay(LocalDate day) {
        return eventRepository.getEventsForDay(day);
    }

    public List<Event> getEventsForRange(LocalDate from, LocalDate to) {
        return eventRepository.getEventsForRange(from, to);
    }
}
