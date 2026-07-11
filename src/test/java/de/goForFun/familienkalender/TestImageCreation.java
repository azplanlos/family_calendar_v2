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
                new Event(List.of(), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Tag der Deutschen Einheit", null, EventSource.HOLIDAY),
                new Event(List.of("Anna Schmidt"), now.withHour(0).withMinute(0), now.plusDays(1).withHour(0).withMinute(0), "Dummy-Ereignis", null, EventSource.CALENDAR),
                new Event(List.of("Anna Schmidt"), now.withHour(20).withMinute(0), now.withHour(21).withMinute(0), "Vertical-core", null, EventSource.CALENDAR),
                new Event(List.of("Andreas Neu"), now.withHour(20).withMinute(0), now.withHour(22).withMinute(0), "Montagsturnier", null, EventSource.CALENDAR),
                new Event(List.of("Anna Schmidt", "Andreas Neu"), now.withHour(16).withMinute(30), now.withHour(17).withMinute(30), "Elternabend", null, EventSource.CALENDAR),
                new Event(List.of("Anna Schmidt", "Andreas Neu", "Lena Müller"), now.withHour(18).withMinute(0), now.withHour(19).withMinute(0), "Familienessen", null, EventSource.CALENDAR)
        );
        List<Event> tomorrowEvents = List.of(
                new Event(List.of(), now.plusDays(1).withHour(0).withMinute(0), now.plusDays(2).withHour(0).withMinute(0), "Allerheiligen", null, EventSource.HOLIDAY),
                new Event(List.of("Anna Schmidt"), now.plusDays(1).withHour(0).withMinute(0), now.plusDays(2).withHour(0).withMinute(0), "Dummy-Ereignis", null, EventSource.CALENDAR),
                new Event(List.of("Anna Schmidt"), now.plusDays(1).withHour(20).withMinute(0), now.plusDays(1).withHour(21).withMinute(0), "Vertical-core", null, EventSource.CALENDAR),
                new Event(List.of("Andreas Neu"), now.plusDays(1).withHour(20).withMinute(0), now.plusDays(1).withHour(22).withMinute(0), "Montagsturnier", null, EventSource.CALENDAR),
                new Event(List.of("Lena Müller", "Andreas Neu"), now.plusDays(1).withHour(14).withMinute(0), now.plusDays(1).withHour(15).withMinute(0), "Arzttermin", null, EventSource.CALENDAR),
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
