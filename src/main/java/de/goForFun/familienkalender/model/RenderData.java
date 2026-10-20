package de.goForFun.familienkalender.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Alle Daten, die der ImageRenderer zum Zeichnen benötigt.
 *
 * @param now              aktueller Zeitpunkt (für Header und Footer)
 * @param todayEvents      Events für heute
 * @param tomorrowEvents   Events für morgen
 * @param weatherDays      Wettervorhersage (bis zu 3 Tage)
 * @param calendarEvents   alle Events im 5-Wochen-Bereich des Monatskalenders (für Indikator-Balken)
 * @param participants     geordnete Liste aller bekannten Familienmitglieder (für Indikator-Balken-Reihenfolge)
 * @param errors           Fehlermeldungen (z.B. nicht erreichbare Kalender-Feeds), leer wenn alles OK
 * @param custodySchedule  Tag-zu-Betreuung-Zuordnung des Wechselmodell-Kalenders; NEU; leer = Feature inaktiv/übersprungen
 */
public record RenderData(
        LocalDateTime now,
        List<Event> todayEvents,
        List<Event> tomorrowEvents,
        List<WeatherDay> weatherDays,
        List<Event> calendarEvents,
        List<String> participants,
        List<String> errors,
        Map<LocalDate, CareDay> custodySchedule
) {
    /**
     * Konstruktor mit errors, ohne custodySchedule (abwärtskompatibel).
     */
    public RenderData(LocalDateTime now, List<Event> todayEvents, List<Event> tomorrowEvents,
                      List<WeatherDay> weatherDays, List<Event> calendarEvents,
                      List<String> participants, List<String> errors) {
        this(now, todayEvents, tomorrowEvents, weatherDays, calendarEvents, participants, errors, Map.of());
    }

    /**
     * Kompakter Konstruktor ohne errors-Liste und ohne custodySchedule (abwärtskompatibel).
     */
    public RenderData(LocalDateTime now, List<Event> todayEvents, List<Event> tomorrowEvents,
                      List<WeatherDay> weatherDays, List<Event> calendarEvents, List<String> participants) {
        this(now, todayEvents, tomorrowEvents, weatherDays, calendarEvents, participants, List.of(), Map.of());
    }
}
