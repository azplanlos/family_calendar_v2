package de.goForFun.familienkalender;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.RenderData;
import de.goForFun.familienkalender.model.WeatherDay;
import net.fortuna.ical4j.data.ParserException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AWS Lambda Handler – orchestriert Kalender-Download, Parsing und Bilderstellung.
 * Klassenname und Package dürfen nicht geändert werden (Serverless-Deployment).
 */
public class CreateKalenderImage implements RequestHandler<ScheduledEvent, Void> {

    private static final String AWS_REGION = System.getenv("AWS_REGION");
    private static final String CALENDAR_FEED = System.getenv("CALENDAR_FEED");

    private final ImageRenderer imageRenderer = new ImageRenderer();

    @Override
    public Void handleRequest(ScheduledEvent input, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("EVENT TYPE: " + input.getClass());

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDate today = now.toLocalDate();

            // Kalender-Feed laden und alle Events des aktuellen Monats parsen
            CalendarParser calendarParser = new CalendarParser(URI.create(CALENDAR_FEED), today);
            List<Event> todayEvents = calendarParser.getEventsForDay(today);
            List<Event> tomorrowEvents = calendarParser.getEventsForDay(today.plusDays(1));

            // TODO: Wetterdaten aus externer API laden
            List<WeatherDay> weatherDays = List.of(
                    new WeatherDay("●", "-4 / -1"),
                    new WeatherDay("✻", "-6 / -1"),
                    new WeatherDay("☁", "-7 / -1")
            );

            RenderData renderData = new RenderData(now, todayEvents, tomorrowEvents, weatherDays);

            try (S3Client s3Client = S3Client.builder().region(Region.of(AWS_REGION)).build();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                imageRenderer.renderImage(outputStream, renderData);
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket("familienkalender")
                                .key("calendar.png")
                                .build(),
                        RequestBody.fromBytes(outputStream.toByteArray())
                );
            }
        } catch (IOException | ParserException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
