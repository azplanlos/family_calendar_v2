package de.goForFun.familienkalender.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Alle Daten, die der ImageRenderer zum Zeichnen benötigt.
 *
 * @param now            aktueller Zeitpunkt (für Header und Footer)
 * @param todayEvents    Events für heute
 * @param tomorrowEvents Events für morgen
 * @param weatherDays    Wettervorhersage (bis zu 3 Tage)
 */
public record RenderData(
        LocalDateTime now,
        List<Event> todayEvents,
        List<Event> tomorrowEvents,
        List<WeatherDay> weatherDays
) {
}
