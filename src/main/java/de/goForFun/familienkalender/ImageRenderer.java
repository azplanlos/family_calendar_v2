package de.goForFun.familienkalender;

import com.google.common.base.Suppliers;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.RenderData;
import de.goForFun.familienkalender.model.WeatherDay;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.IOException;
import java.io.OutputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Verantwortlich für das Rendern des Kalenderbilds für das e-ink Display.
 * Erzeugt ein 800x480 Pixel Bild mit 3 Farben (transparent/weiß, schwarz, rot).
 */
public class ImageRenderer {

    // Layout constants
    private static final int IMAGE_WIDTH = 800;
    private static final int IMAGE_HEIGHT = 480;
    private static final int HEADER_HEIGHT = 30;

    // Left area: today + tomorrow columns
    private static final int TODAY_COL_X = 10;
    private static final int TODAY_COL_WIDTH = 220;
    private static final int TOMORROW_COL_X = 240;
    private static final int TOMORROW_COL_WIDTH = 220;
    private static final int DAY_EVENTS_Y_START = 60;

    // Right margin (consistent for header, calendar, footer)
    private static final int RIGHT_MARGIN = 10;
    private static final int RIGHT_EDGE = IMAGE_WIDTH - RIGHT_MARGIN; // 790

    // Right area: weather + calendar
    private static final int RIGHT_AREA_X = 480;
    private static final int WEATHER_Y = 50;
    private static final int WEATHER_WIDTH = RIGHT_EDGE - RIGHT_AREA_X;
    private static final int CALENDAR_Y = 155;

    // Colors
    private static final Color COLOR_RED = new Color(180, 30, 30);
    private static final Color COLOR_BLACK = Color.BLACK;
    private static final Color COLOR_WHITE = Color.WHITE;

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

    /**
     * Erstellt das Kalenderbild und schreibt es als PNG in den OutputStream.
     */
    public void renderImage(OutputStream outputStream, RenderData data) throws IOException {
        BufferedImage image = createImage(data);
        ImageIO.write(image, "png", outputStream);
    }

    /**
     * Erstellt das Kalenderbild als BufferedImage (für PNG-Export und Bitplane-Export).
     */
    public BufferedImage createImage(RenderData data) {
        BufferedImage image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_BYTE_BINARY,
                new IndexColorModel(2, 3, new byte[]{(byte) 255, 0, (byte) 255}, new byte[]{(byte) 255, 0, 0}, new byte[]{(byte) 255, 0, 0}, 0));
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        LocalDate today = data.now().toLocalDate();

        drawHeader(data.now(), graphics);
        drawDayColumns(today, data.todayEvents(), data.tomorrowEvents(), graphics);
        drawWeatherForecast(data.weatherDays(), graphics);
        drawMonthCalendar(today, graphics);
        drawFooter(data.now(), graphics);

        graphics.dispose();
        return image;
    }

    // ========== HEADER ==========

    private void drawHeader(LocalDateTime dt, Graphics2D graphics) {
        // Date (top right)
        FontHelper.drawString(graphics, dt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")), Aligment.RIGHT, titleFont.get(), 26, COLOR_BLACK, 0, 26, RIGHT_EDGE, 26);

        // Separator line
        graphics.setColor(COLOR_BLACK);
        graphics.fillRect(0, HEADER_HEIGHT + 2, IMAGE_WIDTH, 3);
    }

    // ========== DAY COLUMNS (Heute / Morgen) ==========

    private void drawDayColumns(LocalDate today, List<Event> todayEvents, List<Event> tomorrowEvents, Graphics2D graphics) {
        drawDayColumn(graphics, today, todayEvents, TODAY_COL_X, TODAY_COL_WIDTH, "Heute");
        drawDayColumn(graphics, today.plusDays(1), tomorrowEvents, TOMORROW_COL_X, TOMORROW_COL_WIDTH, "Morgen");
    }

    private void drawDayColumn(Graphics2D graphics, LocalDate day, List<Event> events, int x, int width, String label) {
        Locale locale = Locale.GERMAN;
        String dayName = day.getDayOfWeek().getDisplayName(TextStyle.FULL, locale);
        String headerText = String.format("%s (%s)", label, dayName);

        // Column header
        FontHelper.drawString(graphics, headerText, Aligment.RIGHT, titleFont.get(), 16, COLOR_BLACK, x, DAY_EVENTS_Y_START, width, 16);

        int eventY = DAY_EVENTS_Y_START + 25;

        for (Event event : events) {
            if (isAllDay(event)) {
                drawAllDayEvent(graphics, event.summary(), x, eventY, width);
                eventY += 30;
            } else {
                String time = event.startTime() != null
                        ? event.startTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                        : "";
                List<String> participants = event.participants() != null ? event.participants() : List.of();
                drawTimedEvent(graphics, time, participants, event.summary(), x, eventY, width);
                eventY += 30;
            }
        }
    }

    private boolean isAllDay(Event event) {
        if (event.startTime() == null || event.endTime() == null) {
            return false;
        }
        // Ein ganztägiges Event startet um 00:00 und endet um 00:00 (nächster Tag)
        return event.startTime().getHour() == 0 && event.startTime().getMinute() == 0
                && event.endTime().getHour() == 0 && event.endTime().getMinute() == 0;
    }

    private String getInitials(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length >= 2) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
        }
        return name.substring(0, Math.min(2, name.length())).toUpperCase();
    }

    private void drawAllDayEvent(Graphics2D graphics, String title, int x, int y, int width) {
        // Red background box
        graphics.setColor(COLOR_RED);
        graphics.fillRect(x, y, width, 25);

        // White text on red background
        FontHelper.drawString(graphics, title, Aligment.CENTER, titleFont.get(), 13, COLOR_WHITE, x, y + 17, width, 25);
    }

    private void drawTimedEvent(Graphics2D graphics, String time, List<String> participants, String title, int x, int y, int width) {
        int badgeDiameter = 24;
        int badgeOverlap = 8; // Überlappung in Pixel
        int timeWidth = 50;

        // Time
        FontHelper.drawString(graphics, time, Aligment.LEFT, terminalFont.get(), 13, COLOR_BLACK, x, y + 17, timeWidth, 15);

        // Draw badges for each participant (overlapping, last on top)
        int firstBadgeX = x + timeWidth + 1;
        int badgeX = firstBadgeX;
        int badgeY = y + 2;
        int frameHeight = badgeDiameter;
        int frameY = badgeY;

        // Frame starts at the center of the first badge
        int frameLeftX = firstBadgeX + badgeDiameter / 2;

        // Calculate where the last badge ends to position the frame right edge
        int badgeCount = 0;
        for (String participant : participants) {
            if (getInitials(participant).isEmpty()) continue;
            badgeCount++;
        }
        int lastBadgeRightX = firstBadgeX + (badgeCount > 0 ? (badgeCount - 1) * (badgeDiameter - badgeOverlap) + badgeDiameter : 0);

        // Title frame extends from center of first badge to end of available width
        int titleX = lastBadgeRightX + 3;
        int titleWidth = x + width - titleX;
        int frameRightX = x + width;
        int frameWidth = frameRightX - frameLeftX;

        // Draw frame first (behind badges)
        graphics.setColor(COLOR_BLACK);
        graphics.drawRect(frameLeftX, frameY, frameWidth, frameHeight);

        // Draw badges on top of frame (overlapping each other)
        badgeX = firstBadgeX;
        for (String participant : participants) {
            String initials = getInitials(participant);
            if (initials.isEmpty()) continue;

            // Circle badge
            graphics.setColor(COLOR_BLACK);
            graphics.fillOval(badgeX, badgeY, badgeDiameter, badgeDiameter);

            // Centered initials using FontMetrics
            Font badgeFont = titleFont.get().deriveFont(11f);
            graphics.setFont(badgeFont);
            FontMetrics fm = graphics.getFontMetrics();
            int textWidth = fm.stringWidth(initials);
            int textX = badgeX + (badgeDiameter - textWidth) / 2;
            int textY = badgeY + (badgeDiameter - fm.getHeight()) / 2 + fm.getAscent();
            graphics.setColor(COLOR_WHITE);
            graphics.drawString(initials, textX, textY);

            badgeX += badgeDiameter - badgeOverlap;
        }

        // Event title inside the frame (after last badge)
        if (titleWidth > 0) {
            FontHelper.drawString(graphics, title, Aligment.LEFT, terminalFont.get(), 13, COLOR_BLACK, titleX + 4, frameY + 16, titleWidth - 8, frameHeight);
        }
    }

    // ========== WEATHER FORECAST ==========

    private void drawWeatherForecast(List<WeatherDay> weatherDays, Graphics2D graphics) {
        int x = RIGHT_AREA_X;
        int y = WEATHER_Y;
        int dayWidth = WEATHER_WIDTH / 3;

        for (int i = 0; i < Math.min(3, weatherDays.size()); i++) {
            WeatherDay weather = weatherDays.get(i);
            int dayX = x + i * dayWidth;

            // Weather icon (large placeholder)
            FontHelper.drawString(graphics, weather.icon(), Aligment.CENTER, titleFont.get(), 36, COLOR_BLACK, dayX, y + 45, dayWidth, 40);

            // Temperature
            FontHelper.drawString(graphics, weather.temperature(), Aligment.CENTER, terminalFont.get(), 12, COLOR_RED, dayX, y + 70, dayWidth, 14);
        }
    }

    // ========== WEEK CALENDAR (5 weeks) ==========

    private void drawMonthCalendar(LocalDate today, Graphics2D graphics) {
        int y = CALENDAR_Y;

        // Calculate cell width so that right edge of grid aligns with RIGHT_EDGE
        int cellWidth = (RIGHT_EDGE - RIGHT_AREA_X) / 7;
        int gridWidth = cellWidth * 7;
        int x = RIGHT_EDGE - gridWidth; // align grid right edge to RIGHT_EDGE

        int cellHeight = 38;
        int headerHeight = 16;

        // Start from Monday of the current week
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);

        // Build the list of days to display: 5 weeks = 35 days
        LocalDate endDate = weekStart.plusWeeks(5); // exclusive

        // Track current Y position (headers consume vertical space)
        int currentY = y;
        int currentMonth = -1;

        LocalDate cursor = weekStart;
        while (cursor.isBefore(endDate)) {
            // Check if we entered a new month
            if (cursor.getMonthValue() != currentMonth) {
                // If we were mid-row in the previous month, advance to next row
                // (this is handled by starting each month section fresh)

                // Draw month header
                String monthYear = cursor.getMonth().getDisplayName(TextStyle.FULL, Locale.GERMAN) + " " + cursor.getYear();
                FontHelper.drawString(graphics, monthYear, Aligment.RIGHT, titleFont.get(), 12, COLOR_BLACK, x, currentY + 12, gridWidth, headerHeight);
                currentY += headerHeight + 2;
                currentMonth = cursor.getMonthValue();
            }

            // Determine how many days to render in this row
            // A row always represents Mon-Sun, but we only show days of the current month
            LocalDate rowMonday = cursor.with(DayOfWeek.MONDAY);
            int startCol = cursor.getDayOfWeek().getValue() - 1; // 0=Mon

            // Draw cells for this row (only days belonging to current month and before endDate)
            for (int col = startCol; col < 7; col++) {
                LocalDate day = rowMonday.plusDays(col);

                // Stop if we've gone past our 5-week window
                if (!day.isBefore(endDate)) break;

                // Stop if we've crossed into the next month (will be handled in next iteration)
                if (day.getMonthValue() != currentMonth) break;

                int cellX = x + col * cellWidth;
                int cellY = currentY;

                // Weekend background: dithered gray pattern (checkerboard)
                if (col >= 5 && !day.equals(today)) {
                    fillDithered(graphics, cellX + 1, cellY + 1, cellWidth - 2, cellHeight - 2, COLOR_BLACK);
                }

                // Highlight today: red border, red dithered background, red text
                if (day.equals(today)) {
                    fillDithered(graphics, cellX + 1, cellY + 1, cellWidth - 2, cellHeight - 2, COLOR_RED);
                    graphics.setColor(COLOR_RED);
                    graphics.drawRect(cellX, cellY, cellWidth - 1, cellHeight - 1);
                    graphics.drawRect(cellX + 1, cellY + 1, cellWidth - 3, cellHeight - 3);
                    FontHelper.drawString(graphics, String.valueOf(day.getDayOfMonth()), Aligment.CENTER, titleFont.get(), 12, COLOR_RED, cellX, cellY + (cellHeight / 2) + 6, cellWidth, cellHeight);
                } else {
                    Color textColor = (col >= 5) ? COLOR_RED : COLOR_BLACK;
                    FontHelper.drawString(graphics, String.valueOf(day.getDayOfMonth()), Aligment.CENTER, terminalFont.get(), 12, textColor, cellX, cellY + (cellHeight / 2) + 6, cellWidth, cellHeight);
                }

                // Cell border
                graphics.setColor(COLOR_BLACK);
                graphics.drawRect(cellX, cellY, cellWidth - 1, cellHeight - 1);

                cursor = day.plusDays(1);
            }

            currentY += cellHeight;

            // If cursor is still in same month but we finished a row, advance cursor to next Monday
            if (cursor.isBefore(endDate) && cursor.getMonthValue() == currentMonth) {
                // cursor is already at the next day after last rendered, which should be next Monday
                // unless month boundary: if cursor went past Sunday, it's already Monday
                if (cursor.getDayOfWeek() != DayOfWeek.MONDAY) {
                    // This shouldn't happen since we iterate full weeks, but safety
                    cursor = cursor.with(DayOfWeek.MONDAY).plusWeeks(1);
                }
            }
        }
    }

    // ========== DITHERING HELPER ==========

    /**
     * Füllt einen Bereich mit einem Schachbrettmuster (50% Dithering) in der angegebenen Farbe.
     * Erzeugt auf dem e-ink Display den Eindruck einer Grauschattierung.
     */
    private void fillDithered(Graphics2D graphics, int x, int y, int width, int height, Color color) {
        graphics.setColor(color);
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) {
                if ((px + py) % 2 == 0) {
                    graphics.fillRect(px, py, 1, 1);
                }
            }
        }
    }

    // ========== FOOTER ==========

    private void drawFooter(LocalDateTime dt, Graphics2D graphics) {
        // Version (bottom left)
        FontHelper.drawString(graphics, "v" + VersionInfo.getVersion(), Aligment.LEFT, terminalFont.get(), 10, COLOR_BLACK, 10, IMAGE_HEIGHT - 12, 200, 10);

        // Last update (bottom right) – rendered in German locale and Europe/Berlin timezone
        ZonedDateTime berlinTime = dt.atZone(ZoneId.of("Europe/Berlin"));
        String formattedTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                .withLocale(Locale.GERMANY)
                .format(berlinTime);
        FontHelper.drawString(graphics, String.format("letzte Aktualisierung: %s", formattedTime), Aligment.RIGHT, terminalFont.get(), 10, COLOR_BLACK, 0, IMAGE_HEIGHT - 12, RIGHT_EDGE, 10);
    }
}
