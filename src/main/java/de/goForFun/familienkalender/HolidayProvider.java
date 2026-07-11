package de.goForFun.familienkalender;

import de.focus_shift.jollyday.core.Holiday;
import de.focus_shift.jollyday.core.HolidayManager;
import de.focus_shift.jollyday.core.ManagerParameters;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;

import java.time.LocalDate;
import java.time.Year;
import java.util.List;
import java.util.Set;

/**
 * Liefert gesetzliche Feiertage als ganztägige Events.
 * Land und Bundesland werden per Environment-Variable konfiguriert (Default: DE / BY).
 */
public class HolidayProvider {

    private final HolidayManager holidayManager;
    private final String[] subdivisions;

    /**
     * Erstellt einen HolidayProvider mit dem angegebenen Land und Bundesland.
     *
     * @param countryCode ISO 3166-1 alpha-2 Ländercode (z.B. "de")
     * @param stateCode   Bundesland/Region-Code (z.B. "by" für Bayern), kann null/leer sein
     */
    public HolidayProvider(String countryCode, String stateCode) {
        this.holidayManager = HolidayManager.getInstance(
                ManagerParameters.create(countryCode.toLowerCase())
        );
        this.subdivisions = (stateCode != null && !stateCode.isBlank())
                ? new String[]{stateCode.toLowerCase()}
                : new String[0];
    }

    /**
     * Gibt alle Feiertage zurück, die an dem angegebenen Tag liegen.
     */
    public List<Event> getHolidaysForDay(LocalDate day) {
        return getHolidaysForRange(day, day);
    }

    /**
     * Gibt alle Feiertage im angegebenen Zeitraum (inklusive) als ganztägige Events zurück.
     */
    public List<Event> getHolidaysForRange(LocalDate from, LocalDate to) {
        Set<Holiday> holidays;
        if (from.getYear() == to.getYear()) {
            holidays = holidayManager.getHolidays(Year.of(from.getYear()), subdivisions);
        } else {
            // Bei Jahreswechsel beide Jahre abfragen
            holidays = new java.util.HashSet<>(
                    holidayManager.getHolidays(Year.of(from.getYear()), subdivisions)
            );
            holidays.addAll(holidayManager.getHolidays(Year.of(to.getYear()), subdivisions));
        }

        return holidays.stream()
                .filter(h -> !h.getDate().isBefore(from) && !h.getDate().isAfter(to))
                .map(this::toEvent)
                .toList();
    }

    private Event toEvent(Holiday holiday) {
        LocalDate date = holiday.getDate();
        return new Event(
                List.of(),
                date.atStartOfDay(),
                date.plusDays(1).atStartOfDay(),
                holiday.getDescription(),
                null,
                EventSource.HOLIDAY
        );
    }
}
