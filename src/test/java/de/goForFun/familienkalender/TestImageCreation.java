package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
import de.goForFun.familienkalender.model.RenderData;
import de.goForFun.familienkalender.model.WeatherDay;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;

public class TestImageCreation {

    @Test
    public void testImageCreation() throws IOException {
        LocalDateTime now = LocalDateTime.now();

        List<Event> todayEvents = List.of(
                // Feiertag (kein color) – schwarzer Rahmen
                new Event(List.of(), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Tag der Deutschen Einheit", null, EventSource.HOLIDAY),
                // Ganztägig mit rotem iCal-Color – roter Balken, schwarze Schrift
                new Event(List.of("Anna Schmidt"), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Urlaub Anna", "red", EventSource.CALENDAR),
                // Ganztägig ohne Farbe – schwarzer Rahmen
                new Event(List.of("Andreas Neu"), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Homeoffice", null, EventSource.CALENDAR),
                // Schulkalender: Timed Event – dunkelgrau gedithert
                new Event(List.of(), now.withHour(8).withMinute(0), now.withHour(11).withMinute(30), "Bundesjugendspiele", null, EventSource.SCHOOL),
                // Timed Event mit Rot (Hex) – roter Hintergrund im Frame
                new Event(List.of("Anna Schmidt"), now.withHour(14).withMinute(0), now.withHour(15).withMinute(0), "Arzt (wichtig!)", "#CC0000", EventSource.CALENDAR),
                // Schulkalender: Timed Event – dunkelgrau gedithert
                new Event(List.of(), now.withHour(9).withMinute(30), now.withHour(9).withMinute(50), "Pausenverkauf 2b", null, EventSource.SCHOOL),
                // Timed Event ohne Farbe – normaler schwarzer Rahmen
                new Event(List.of("Andreas Neu"), now.withHour(20).withMinute(0), now.withHour(22).withMinute(0), "Montagsturnier", null, EventSource.CALENDAR),
                // Timed Event mit blauer Farbe – wird wie normal (kein Rot) gerendert
                new Event(List.of("Anna Schmidt", "Andreas Neu"), now.withHour(16).withMinute(30), now.withHour(17).withMinute(30), "Elternabend", "#0000FF", EventSource.CALENDAR),
                // Timed Event mit Rot (Name) – roter Hintergrund im Frame
                new Event(List.of("Anna Schmidt", "Andreas Neu", "Lena Müller"), now.withHour(18).withMinute(0), now.withHour(19).withMinute(0), "Familienessen", "red", EventSource.CALENDAR)
        );
        List<Event> tomorrowEvents = List.of(
                // Feiertag – schwarzer Rahmen
                new Event(List.of(), now.plusDays(1).withHour(0).withMinute(0), now.plusDays(2).withHour(0).withMinute(0), "Allerheiligen", null, EventSource.HOLIDAY),
                // Ganztägig mit Rot-Hex – roter Balken
                new Event(List.of("Lena Müller"), now.plusDays(1).withHour(0).withMinute(0), now.plusDays(2).withHour(0).withMinute(0), "Klassenfahrt", "#FF0000", EventSource.CALENDAR),
                // Schulkalender: Ganztägig – dunkelgrau gedithert
                new Event(List.of(), now.plusDays(1).withHour(0).withMinute(0), now.plusDays(2).withHour(0).withMinute(0), "Schulfest", null, EventSource.SCHOOL),
                // Ganztägig mit grüner Farbe – schwarzer Rahmen (kein Rot)
                new Event(List.of("Anna Schmidt"), now.plusDays(1).withHour(0).withMinute(0), now.plusDays(2).withHour(0).withMinute(0), "Yoga-Kurs", "#00CC00", EventSource.CALENDAR),
                // Schulkalender: Timed Event – dunkelgrau gedithert
                new Event(List.of(), now.plusDays(1).withHour(8).withMinute(0), now.plusDays(1).withHour(9).withMinute(30), "Klassenfotos", null, EventSource.SCHOOL),
                // Timed mit Rot
                new Event(List.of("Andreas Neu"), now.plusDays(1).withHour(9).withMinute(0), now.plusDays(1).withHour(10).withMinute(0), "Deadline Projekt", "red", EventSource.CALENDAR),
                // Timed ohne Farbe
                new Event(List.of("Lena Müller", "Andreas Neu"), now.plusDays(1).withHour(14).withMinute(0), now.plusDays(1).withHour(15).withMinute(0), "Arzttermin", null, EventSource.CALENDAR),
                // Timed ohne Farbe
                new Event(List.of("Anna Schmidt", "Andreas Neu", "Lena Müller"), now.plusDays(1).withHour(19).withMinute(0), now.plusDays(1).withHour(20).withMinute(0), "Spieleabend", null, EventSource.CALENDAR)
        );
        List<WeatherDay> weatherDays = List.of(
                new WeatherDay("●", "-4 / -1"),
                new WeatherDay("✻", "-6 / -1"),
                new WeatherDay("☁", "-7 / -1")
        );

        RenderData renderData = new RenderData(now, todayEvents, tomorrowEvents, weatherDays);

        ImageRenderer imageRenderer = new ImageRenderer();
        OutputStream outputStream = new FileOutputStream("test.png");
        imageRenderer.renderImage(outputStream, renderData);
    }
}
