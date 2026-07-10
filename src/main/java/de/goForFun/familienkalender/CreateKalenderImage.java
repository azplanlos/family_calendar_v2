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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * AWS Lambda Handler – orchestriert Kalender-Download, Parsing und Bilderstellung.
 * Lädt das gerenderte Bild als PNG (Vorschau) und als Raw-Bitplane (.bin) auf S3 hoch.
 * Die .bin Datei wird von der ServeCalendarImage Lambda über eine Function URL ausgeliefert.
 */
public class CreateKalenderImage implements RequestHandler<ScheduledEvent, Void> {

    private static final String AWS_REGION = System.getenv("AWS_REGION");
    private static final String CALENDAR_FEED = System.getenv("CALENDAR_FEED");
    private static final String S3_BUCKET = "familienkalender";
    private static final String S3_KEY_PNG = "calendar.png";
    private static final String S3_KEY_BIN = "calendar.bin";

    private final ImageRenderer imageRenderer = new ImageRenderer();
    private final BitplaneExporter bitplaneExporter = new BitplaneExporter();

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

            // Bild rendern
            BufferedImage image = imageRenderer.createImage(renderData);

            try (S3Client s3Client = S3Client.builder().region(Region.of(AWS_REGION)).build()) {

                // PNG exportieren und hochladen (für Debug/Vorschau im Browser)
                ByteArrayOutputStream pngOutputStream = new ByteArrayOutputStream();
                ImageIO.write(image, "png", pngOutputStream);
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(S3_BUCKET)
                                .key(S3_KEY_PNG)
                                .contentType("image/png")
                                .build(),
                        RequestBody.fromBytes(pngOutputStream.toByteArray())
                );
                logger.log("PNG uploaded to s3://" + S3_BUCKET + "/" + S3_KEY_PNG);

                // Raw Bitplane exportieren und hochladen (für ESP32)
                byte[] bitplaneData = bitplaneExporter.export(image);
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket(S3_BUCKET)
                                .key(S3_KEY_BIN)
                                .contentType("application/octet-stream")
                                .build(),
                        RequestBody.fromBytes(bitplaneData)
                );
                logger.log("Bitplane BIN uploaded to s3://" + S3_BUCKET + "/" + S3_KEY_BIN
                        + " (" + bitplaneData.length + " bytes)");
            }
        } catch (IOException | ParserException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
