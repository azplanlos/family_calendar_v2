package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
import de.goForFun.familienkalender.model.RenderData;
import de.goForFun.familienkalender.model.WeatherDay;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class TestImageCreation {

    @Test
    public void testImageCreation() throws IOException {
        LocalDateTime now = LocalDateTime.now();

        List<Event> todayEvents = List.of(
                // Ferien (VACATION) – roter Hintergrund, weiße Schrift, höchste Prio
                new Event(List.of(), now.withHour(0).withMinute(0), now.plusDays(3).withHour(0).withMinute(0), "Pfingstferien", null, EventSource.VACATION, null),
                // Feiertag (HOLIDAY) – roter Hintergrund, weiße Schrift
                new Event(List.of(), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Tag der Deutschen Einheit", null, EventSource.HOLIDAY, null),
                // Ganztägig mit rotem iCal-Color – roter Balken, schwarze Schrift
                new Event(List.of("Anna Schmidt"), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Urlaub Anna", "red", EventSource.CALENDAR, null),
                // Ganztägig ohne Farbe – schwarzer Rahmen
                new Event(List.of("Andreas Neu"), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Homeoffice", null, EventSource.CALENDAR, null),
                // Schulkalender: Timed Event – dunkelgrau gedithert
                new Event(List.of(), now.withHour(8).withMinute(0), now.withHour(11).withMinute(30), "Bundesjugendspiele", null, EventSource.SCHOOL, null),
                // Timed Event mit Rot (Hex) – roter Hintergrund im Frame, mit URL
                new Event(List.of("Anna Schmidt"), now.withHour(14).withMinute(0), now.withHour(15).withMinute(0), "Arzt (wichtig!)", "#CC0000", EventSource.CALENDAR, "https://maps.google.com/?q=Arztpraxis+München"),
                // Timed Event ohne Farbe – normaler schwarzer Rahmen, mit URL
                new Event(List.of("Andreas Neu"), now.withHour(20).withMinute(0), now.withHour(22).withMinute(0), "Montagsturnier", null, EventSource.CALENDAR, "https://turnierplan.de/montag"),
                // Timed Event mit blauer Farbe – mit URL (Location-Fallback simuliert)
                new Event(List.of("Anna Schmidt", "Andreas Neu"), now.withHour(16).withMinute(30), now.withHour(17).withMinute(30), "Elternabend", "#0000FF", EventSource.CALENDAR, "https://www.google.com/maps/search/?api=1&query=Grundschule+M%C3%BCnchen"),
                // Timed Event mit Rot (Name) – roter Hintergrund im Frame
                new Event(List.of("Anna Schmidt", "Andreas Neu", "Lena Müller"), now.withHour(18).withMinute(0), now.withHour(19).withMinute(0), "Familienessen", "red", EventSource.CALENDAR, null)
        );
        // Morgen: nur Ferien (spanning) und Feiertag – Smiley wird unter den Events gerendert
        List<Event> tomorrowEvents = List.of(
                // Gleiches Ferien-Event wie heute (spanning)
                new Event(List.of(), now.withHour(0).withMinute(0), now.plusDays(3).withHour(0).withMinute(0), "Pfingstferien", null, EventSource.VACATION, null)
        );
        List<WeatherDay> weatherDays = List.of(
                new WeatherDay("04d", -4, -1),
                new WeatherDay("13d", -6, -1),
                new WeatherDay("03d", -7, -1)
        );

        // Wechselmodell-Demodaten relativ zu now (R7): zeigt Betreuungs-Icons
        // und Monatskalender-Dithering inkl. Wechseltag-Halbzelle.
        LocalDate base = now.toLocalDate().with(DayOfWeek.MONDAY);
        Map<LocalDate, CareDay> demo = new HashMap<>();
        demo.put(base,             CareDay.fullDay(base, CareParty.MANN));
        demo.put(base.plusDays(1), CareDay.fullDay(base.plusDays(1), CareParty.MANN));
        // Wechseltag MANN->FRAU: Mann gibt um 16:00 ab -> Label "(bis 16:00)"
        demo.put(base.plusDays(2), CareDay.switchDay(base.plusDays(2), CareParty.MANN, CareParty.FRAU, LocalTime.of(16, 0)));
        demo.put(base.plusDays(3), CareDay.fullDay(base.plusDays(3), CareParty.FRAU));
        demo.put(base.plusDays(4), CareDay.fullDay(base.plusDays(4), CareParty.FRAU));
        LocalDate today = now.toLocalDate();
        // Wechseltag FRAU->MANN: Mann übernimmt um 16:00 -> Icon = MANN (secondHalf), Label "(ab 16:00)".
        // Bewusst put() (nicht putIfAbsent), damit der Wechseltag einen evtl. zuvor aus dem base-Raster
        // gesetzten Ganztag für heute überschreibt und das Label "(ab 16:00)" im Demo-Bild erscheint.
        demo.put(today,             CareDay.switchDay(today, CareParty.FRAU, CareParty.MANN, LocalTime.of(16, 0)));
        demo.put(today.plusDays(1), CareDay.fullDay(today.plusDays(1), CareParty.FRAU));

        RenderData renderData = buildRenderData(now, todayEvents, tomorrowEvents, weatherDays, demo);

        ImageRenderer imageRenderer = new ImageRenderer();
        OutputStream outputStream = new FileOutputStream("test.png");
        imageRenderer.renderImage(outputStream, renderData);
    }

    @Test
    public void testImageCreation_onlyVacationAndHoliday_showsSmiley() throws IOException {
        LocalDateTime now = LocalDateTime.now();

        // Nur Ferien und Feiertage – Smiley sollte trotzdem erscheinen (unterhalb)
        List<Event> todayEvents = List.of(
                new Event(List.of(), now.withHour(0).withMinute(0), now.plusDays(5).withHour(0).withMinute(0), "Sommerferien", null, EventSource.VACATION, null),
                new Event(List.of(), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Mariä Himmelfahrt", null, EventSource.HOLIDAY, null)
        );
        List<Event> tomorrowEvents = List.of(
                new Event(List.of(), now.withHour(0).withMinute(0), now.plusDays(5).withHour(0).withMinute(0), "Sommerferien", null, EventSource.VACATION, null)
        );
        List<WeatherDay> weatherDays = List.of(
                new WeatherDay("01d", 25, 32),
                new WeatherDay("01d", 24, 30),
                new WeatherDay("03d", 20, 26)
        );

        RenderData renderData = buildRenderData(now, todayEvents, tomorrowEvents, weatherDays);

        ImageRenderer imageRenderer = new ImageRenderer();
        OutputStream outputStream = new FileOutputStream("test_vacation_smiley.png");
        imageRenderer.renderImage(outputStream, renderData);
    }

    @Test
    public void testImageCreation_overnightEvent() throws IOException {
        LocalDateTime now = LocalDateTime.now();

        // Ein Event das gestern um 22:00 startete und heute um 10:00 endet
        // Wird mit 00:00 Startzeit angezeigt
        List<Event> todayEvents = List.of(
                new Event(List.of("Andreas Neu"), now.withHour(0).withMinute(0), now.withHour(10).withMinute(0), "LAN-Party", null, EventSource.CALENDAR, null),
                new Event(List.of("Anna Schmidt"), now.withHour(9).withMinute(0), now.withHour(10).withMinute(30), "Meeting", null, EventSource.CALENDAR, null)
        );
        List<Event> tomorrowEvents = List.of();
        List<WeatherDay> weatherDays = List.of(
                new WeatherDay("03d", 5, 12),
                new WeatherDay("04d", 3, 8),
                new WeatherDay("01d", 7, 14)
        );

        RenderData renderData = buildRenderData(now, todayEvents, tomorrowEvents, weatherDays);

        ImageRenderer imageRenderer = new ImageRenderer();
        OutputStream outputStream = new FileOutputStream("test_overnight.png");
        imageRenderer.renderImage(outputStream, renderData);
    }

    /**
     * Hilfsmethode: Baut RenderData inkl. calendarEvents und participants aus den übergebenen Event-Listen.
     * Verwendet einen leeren Betreuungs-Schedule (Feature inaktiv).
     */
    private RenderData buildRenderData(LocalDateTime now, List<Event> todayEvents, List<Event> tomorrowEvents, List<WeatherDay> weatherDays) {
        return buildRenderData(now, todayEvents, tomorrowEvents, weatherDays, Map.of());
    }

    /**
     * Hilfsmethode: Baut RenderData inkl. calendarEvents, participants und custodySchedule
     * aus den übergebenen Event-Listen.
     */
    private RenderData buildRenderData(LocalDateTime now, List<Event> todayEvents, List<Event> tomorrowEvents,
                                       List<WeatherDay> weatherDays, Map<LocalDate, CareDay> custodySchedule) {
        // Alle Events zusammenführen als calendarEvents (für Test-Zwecke)
        List<Event> calendarEvents = new ArrayList<>();
        calendarEvents.addAll(todayEvents);
        calendarEvents.addAll(tomorrowEvents);

        // Participants extrahieren
        LinkedHashSet<String> participantSet = new LinkedHashSet<>();
        for (Event event : calendarEvents) {
            if (event.participants() != null) {
                event.participants().stream()
                        .filter(Objects::nonNull)
                        .filter(p -> !p.isBlank())
                        .forEach(participantSet::add);
            }
        }
        List<String> participants = List.copyOf(participantSet);

        return new RenderData(now, todayEvents, tomorrowEvents, weatherDays, calendarEvents, participants,
                List.of(), custodySchedule);
    }
}
