package de.goForFun.familienkalender;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.base.Suppliers;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.IcalMapping;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.component.VEvent;
import org.mapstruct.factory.Mappers;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public class CreateKalenderImage implements RequestHandler<ScheduledEvent, Void> {

    private static final String AWS_REGION = System.getenv("AWS_REGION");

    Supplier<Font> titleFont = Suppliers.memoize(() -> {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(this.getClass().getResource("/fonts/TitilliumWeb-Bold.ttf")).openStream());
        } catch (FontFormatException | IOException e) {
            throw new RuntimeException(e);
        }
    });

    Supplier<Font> terminalFont = Suppliers.memoize(() -> {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(this.getClass().getResource("/fonts/SourceCodePro-Regular.ttf")).openStream());
        } catch (FontFormatException | IOException e) {
            throw new RuntimeException(e);
        }
    });


    @Override
    public Void handleRequest(ScheduledEvent input, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("EVENT TYPE: " + input.getClass());
        try {
            try (S3Client s3Client = S3Client.builder().region(Region.of(AWS_REGION)).build();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                createImage(outputStream);
                s3Client.putObject(
                        PutObjectRequest.builder()
                                .bucket("familienkalender")
                                .key("calendar.png")
                                .build(),
                        RequestBody.fromBytes(outputStream.toByteArray())
                );
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return null;
    }

    void createImage(OutputStream outputStream) throws IOException {
        BufferedImage image = new BufferedImage(800, 480, BufferedImage.TYPE_BYTE_BINARY,
                new IndexColorModel(2, 3, new byte[]{(byte) 255, 0, (byte) 255}, new byte[]{(byte) 255, 0, 0}, new byte[]{(byte) 255, 0, 0}, 0));
        Graphics graphics = image.getGraphics();
        drawHeader(LocalDateTime.now(), graphics);

        drawFooter(LocalDateTime.now(), graphics);
        ImageIO.write(image, "png", outputStream);
    }

    void downloadAndParseCalender() {

    }

    List<Event> parseCalender(InputStream inputStream, LocalDate day) throws ParserException, IOException {
        CalendarBuilder builder = new CalendarBuilder();
        Calendar calendar = builder.build(inputStream);
        IcalMapping mapper = Mappers.getMapper(IcalMapping.class);
        return calendar.getComponents(Component.VEVENT).stream()
                .filter(event -> {
                    Set<Period<ZonedDateTime>> eventPeriods = event.calculateRecurrenceSet(new Period<>(day.atStartOfDay().atZone(ZoneId.systemDefault()), Duration.of(1, ChronoUnit.DAYS)));
                    return eventPeriods.stream().anyMatch(p -> p.intersects(new Period<>(day.atStartOfDay().atZone(ZoneId.systemDefault()), Duration.of(1, ChronoUnit.DAYS))));
                }).map(e -> mapper.mapVeventEvent((VEvent) e)).toList();
    }

    private void drawHeader(LocalDateTime dt, Graphics graphics) {
        FontHelper.drawString(graphics, dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), Aligment.RIGHT, titleFont.get(), 26, Color.BLACK, 0, 26, 800, 26);
        graphics.fillRect(0, 28, 800, 3);
    }

    private void drawFooter(LocalDateTime dt, Graphics graphics) {
        FontHelper.drawString(graphics, String.format("letzte Aktualisierung: %s", DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).format(dt)), Aligment.RIGHT, terminalFont.get(), 10, Color.BLACK, 0, 468, 800, 10);
    }

    private void drawTermin() {

    }
}
