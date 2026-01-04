package de.goForFun.familienkalender;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.ScheduledEvent;
import com.google.common.base.Suppliers;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

public class CreateKalenderImage implements RequestHandler<ScheduledEvent, Void> {

    Supplier<Font> titleFont = Suppliers.memoize(() -> {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(this.getClass().getResource("/fonts/TitilliumWeb-Bold.ttf")).openStream());
        } catch (FontFormatException | IOException e) {
            throw new RuntimeException(e);
        }
    });

    @Override
    public Void handleRequest(ScheduledEvent input, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("EVENT TYPE: " + input.getClass());
        return null;
    }

    void createImage() throws IOException {
        BufferedImage image = new BufferedImage(800, 480, BufferedImage.TYPE_BYTE_BINARY,
                new IndexColorModel(2, 3, new byte[]{(byte) 255, 0, (byte) 255}, new byte[]{(byte) 255, 0, 0}, new byte[]{(byte) 255, 0, 0}, 0));
        Graphics graphics = image.getGraphics();
        drawHeader(LocalDateTime.now(), graphics);
        ImageIO.write(image, "png", new File("test.png"));
    }

    private void drawHeader(LocalDateTime dt, Graphics graphics) {
        graphics.setColor(Color.BLACK);
        graphics.setFont(titleFont.get().deriveFont(26.0f));
        graphics.drawString(dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), 0, 26);
        graphics.fillRect(0, 28, 800, 3);
    }
}
