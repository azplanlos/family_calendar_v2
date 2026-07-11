package de.goForFun.familienkalender;

import com.google.common.base.Suppliers;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
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

    Supplier<Font> terminalBoldFont = Suppliers.memoize(() -> {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, Objects.requireNonNull(this.getClass().getResource("/fonts/SourceCodePro-Bold.ttf")).openStream());
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

        if (events.isEmpty()) {
            drawNoEventsPlaceholder(graphics, x, DAY_EVENTS_Y_START + 25, width);
            return;
        }

        int eventY = DAY_EVENTS_Y_START + 25;

        for (Event event : events) {
            if (isAllDay(event)) {
                drawAllDayEvent(graphics, event, x, eventY, width);
                eventY += 30;
            } else {
                String time = event.startTime() != null
                        ? event.startTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                        : "";
                List<String> participants = event.participants() != null ? event.participants() : List.of();
                drawTimedEvent(graphics, time, participants, event.summary(), event.color(), event.source(), x, eventY, width);
                eventY += 30;
            }
        }
    }

    /**
     * Zeichnet einen großen Smiley und "Keine Termine" als Platzhalter,
     * wenn an einem Tag keine Events vorhanden sind.
     */
    private void drawNoEventsPlaceholder(Graphics2D graphics, int x, int y, int width) {
        // Smiley-Größe und Position (zentriert in der Spalte)
        int smileyDiameter = 100;
        int smileyX = x + (width - smileyDiameter) / 2;
        int smileyY = y + 20;
        int centerX = smileyX + smileyDiameter / 2;
        int centerY = smileyY + smileyDiameter / 2;

        // Kreis (Gesicht)
        graphics.setColor(COLOR_BLACK);
        Stroke oldStroke = graphics.getStroke();
        graphics.setStroke(new BasicStroke(3));
        graphics.drawOval(smileyX, smileyY, smileyDiameter, smileyDiameter);
        graphics.setStroke(oldStroke);

        // Augen (zwei gefüllte Kreise)
        int eyeRadius = 6;
        int eyeOffsetX = 18;
        int eyeOffsetY = 15;
        graphics.fillOval(centerX - eyeOffsetX - eyeRadius, centerY - eyeOffsetY - eyeRadius, eyeRadius * 2, eyeRadius * 2);
        graphics.fillOval(centerX + eyeOffsetX - eyeRadius, centerY - eyeOffsetY - eyeRadius, eyeRadius * 2, eyeRadius * 2);

        // Mund (Bogen nach oben = lächeln)
        graphics.setStroke(new BasicStroke(3));
        int mouthWidth = 50;
        int mouthHeight = 30;
        graphics.drawArc(centerX - mouthWidth / 2, centerY - 5, mouthWidth, mouthHeight, 200, 140);
        graphics.setStroke(oldStroke);

        // Text "Keine Termine" zentriert unterhalb des Smileys
        int textY = smileyY + smileyDiameter + 30;
        FontHelper.drawString(graphics, "Keine Termine", Aligment.CENTER, titleFont.get(), 18, COLOR_BLACK, x, textY, width, 20);
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

    /**
     * Prüft ob die iCal-Farbe eines Events als "rot" zu interpretieren ist.
     * Erkennt den Farbnamen "red" (case-insensitive) sowie gängige Rot-Hex-Codes.
     */
    private boolean isRedColor(String color) {
        if (color == null || color.isBlank()) {
            return false;
        }
        String normalized = color.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("red")) {
            return true;
        }
        // Hex-Codes: Rot-Dominanz über 40%-Regel
        if (normalized.startsWith("#") && normalized.length() >= 7) {
            try {
                int r = Integer.parseInt(normalized.substring(1, 3), 16);
                int g = Integer.parseInt(normalized.substring(3, 5), 16);
                int b = Integer.parseInt(normalized.substring(5, 7), 16);
                // R muss mindestens 80 sein (sonst fast-schwarz) und G/B dürfen
                // maximal 40% von R betragen – erkennt auch dunkle Rottöne sicher,
                // schließt aber Orange (#FF8C00) und ähnliche aus.
                return r >= 80 && g < (r * 0.4) && b < (r * 0.4);
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    private void drawAllDayEvent(Graphics2D graphics, Event event, int x, int y, int width) {
        String title = event.summary();
        if (event.source() == EventSource.SCHOOL) {
            // Schulkalender-Event: dunkelgrau gedithert (50% schwarz), schwarze Schrift
            graphics.setColor(COLOR_WHITE);
            graphics.fillRect(x, y, width, 25);
            fillDithered(graphics, x, y, width, 25, COLOR_BLACK);
            graphics.setColor(COLOR_BLACK);
            graphics.drawRect(x, y, width, 25);
            FontHelper.drawString(graphics, title, Aligment.CENTER, titleFont.get(), 13, COLOR_WHITE, x, y + 17, width, 25);
        } else if (isRedColor(event.color())) {
            // Rot-markiertes Event: roter Hintergrund, schwarze Schrift
            graphics.setColor(COLOR_RED);
            graphics.fillRect(x, y, width, 25);
            FontHelper.drawString(graphics, title, Aligment.CENTER, titleFont.get(), 13, COLOR_BLACK, x, y + 17, width, 25);
        } else {
            // Andere Events: schwarzer Rahmen, schwarze Schrift auf weißem Grund
            graphics.setColor(COLOR_WHITE);
            graphics.fillRect(x, y, width, 25);
            graphics.setColor(COLOR_BLACK);
            graphics.drawRect(x, y, width, 25);
            FontHelper.drawString(graphics, title, Aligment.CENTER, titleFont.get(), 13, COLOR_BLACK, x, y + 17, width, 25);
        }
    }

    private void drawTimedEvent(Graphics2D graphics, String time, List<String> participants, String title, String color, EventSource source, int x, int y, int width) {
        int badgeDiameter = 24;
        int badgeOverlap = 8; // Überlappung in Pixel
        int timeWidth = 50;
        boolean isRed = isRedColor(color);
        boolean isSchool = source == EventSource.SCHOOL;

        // Time (rot bei rot-markierten Events)
        Color timeColor = isRed ? COLOR_RED : COLOR_BLACK;
        FontHelper.drawString(graphics, time, Aligment.LEFT, terminalFont.get(), 13, timeColor, x, y + 17, timeWidth, 15);

        // Draw badges for each participant (overlapping, last on top)
        int firstBadgeX = x + timeWidth + 1;
        int badgeX = firstBadgeX;
        int badgeY = y + 2;
        int frameHeight = badgeDiameter;
        int frameY = badgeY;

        // Calculate where the last badge ends
        int badgeCount = 0;
        if (isSchool) {
            badgeCount++; // Schul-Badge zählt als erstes Badge
        }
        for (String participant : participants) {
            if (getInitials(participant).isEmpty()) continue;
            badgeCount++;
        }
        int lastBadgeRightX = firstBadgeX + (badgeCount > 0 ? (badgeCount - 1) * (badgeDiameter - badgeOverlap) + badgeDiameter : 0);

        // Frame starts at the right-center of the last badge:
        // horizontal = right edge of last badge minus half diameter (= center of last badge)
        // This way the badge's right half overlaps into the frame and edges are flush
        int lastBadgeX = firstBadgeX + (badgeCount > 0 ? (badgeCount - 1) * (badgeDiameter - badgeOverlap) : 0);
        int frameLeftX = lastBadgeX + badgeDiameter / 2;

        // Title starts after the last badge with a small gap
        int titleX = lastBadgeRightX + 3;
        int titleWidth = x + width - titleX;
        int frameRightX = x + width;
        int frameWidth = frameRightX - frameLeftX;

        // Draw frame first (behind badges)
        if (isSchool) {
            // Schulkalender-Event: dunkelgrau gedithert (50% schwarz) im Frame-Bereich
            graphics.setColor(COLOR_WHITE);
            graphics.fillRect(frameLeftX, frameY, frameWidth, frameHeight);
            fillDithered(graphics, frameLeftX, frameY, frameWidth, frameHeight, COLOR_BLACK);
            graphics.setColor(COLOR_BLACK);
            graphics.drawRect(frameLeftX, frameY, frameWidth, frameHeight);
        } else if (isRed) {
            // Rot-markiertes Event: roter Hintergrund im Frame-Bereich
            graphics.setColor(COLOR_RED);
            graphics.fillRect(frameLeftX, frameY, frameWidth, frameHeight);
        } else {
            // Normales Event: schwarzer Rahmen auf weißem Grund
            graphics.setColor(COLOR_BLACK);
            graphics.drawRect(frameLeftX, frameY, frameWidth, frameHeight);
        }

        // Draw badges on top of frame (overlapping each other)
        badgeX = firstBadgeX;
        if (isSchool) {
            // Schulkalender-Badge: weißer Kreis mit schwarzem Rand + Schul-Icon
            drawSchoolBadge(graphics, badgeX, badgeY, badgeDiameter);
            badgeX += badgeDiameter - badgeOverlap;
        }
        for (String participant : participants) {
            String initials = getInitials(participant);
            if (initials.isEmpty()) continue;

            // Circle badge (immer schwarz, unabhängig von Event-Farbe)
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
            Color titleColor = isSchool ? COLOR_WHITE : COLOR_BLACK;
            FontHelper.drawString(graphics, title, Aligment.LEFT, terminalFont.get(), 13, titleColor, titleX + 4, frameY + 16, titleWidth - 8, frameHeight);
        }
    }

    // ========== SCHOOL BADGE ==========

    /**
     * Zeichnet eine Schul-Badge: weißer Kreis mit schwarzem Rand und einem kleinen
     * aufgeschlagenen Buch-Icon in der Mitte.
     */
    private void drawSchoolBadge(Graphics2D graphics, int bx, int by, int diameter) {
        // Weißer Kreis als Hintergrund
        graphics.setColor(COLOR_WHITE);
        graphics.fillOval(bx, by, diameter, diameter);
        // Schwarzer Rand – 2px breit für durchgehende Linie auf e-ink
        graphics.setColor(COLOR_BLACK);
        Stroke oldStroke = graphics.getStroke();
        graphics.setStroke(new BasicStroke(2));
        graphics.drawOval(bx + 1, by + 1, diameter - 2, diameter - 2);
        graphics.setStroke(oldStroke);

        // Offenes Buch-Icon zentriert im Badge (ca. 14x10 px)
        int cx = bx + diameter / 2; // Mitte des Badges
        int cy = by + diameter / 2;

        // Buchrücken (vertikale Linie in der Mitte)
        graphics.setColor(COLOR_BLACK);
        graphics.drawLine(cx, cy - 4, cx, cy + 4);

        // Linke Buchseite (leicht nach links geneigtes Trapez)
        int[] leftX = {cx - 1, cx - 6, cx - 6, cx - 1};
        int[] leftY = {cy - 4, cy - 3, cy + 3, cy + 4};
        graphics.drawPolygon(leftX, leftY, 4);

        // Rechte Buchseite (leicht nach rechts geneigtes Trapez)
        int[] rightX = {cx + 1, cx + 6, cx + 6, cx + 1};
        int[] rightY = {cy - 4, cy - 3, cy + 3, cy + 4};
        graphics.drawPolygon(rightX, rightY, 4);

        // "Zeilen" auf den Seiten (je 2 kurze Striche)
        graphics.drawLine(cx - 5, cy - 1, cx - 2, cy - 1);
        graphics.drawLine(cx - 5, cy + 1, cx - 2, cy + 1);
        graphics.drawLine(cx + 2, cy - 1, cx + 5, cy - 1);
        graphics.drawLine(cx + 2, cy + 1, cx + 5, cy + 1);
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
        // Track today's cell position for red highlight border
        int todayCellX = -1, todayCellY = -1;

        while (cursor.isBefore(endDate)) {
            // Check if we entered a new month
            if (cursor.getMonthValue() != currentMonth) {
                // Draw month header
                String monthYear = cursor.getMonth().getDisplayName(TextStyle.FULL, Locale.GERMAN) + " " + cursor.getYear();
                FontHelper.drawString(graphics, monthYear, Aligment.RIGHT, titleFont.get(), 12, COLOR_BLACK, x, currentY + 12, gridWidth, headerHeight);
                currentY += headerHeight + 2;
                currentMonth = cursor.getMonthValue();
            }

            // Track the top-left of this month's grid section
            int sectionStartY = currentY;
            int sectionRows = 0;
            // Track which columns are occupied per row (for partial rows at month boundaries)
            java.util.List<int[]> rowColRanges = new java.util.ArrayList<>();

            // Render all rows for this month
            while (cursor.isBefore(endDate) && cursor.getMonthValue() == currentMonth) {
                LocalDate rowMonday = cursor.with(DayOfWeek.MONDAY);
                int startCol = cursor.getDayOfWeek().getValue() - 1; // 0=Mon
                int endCol = startCol; // will track last occupied column (inclusive)

                // Draw cell content for this row (backgrounds, text)
                for (int col = startCol; col < 7; col++) {
                    LocalDate day = rowMonday.plusDays(col);
                    if (!day.isBefore(endDate)) break;
                    if (day.getMonthValue() != currentMonth) break;

                    int cellX = x + col * cellWidth;
                    int cellY = currentY;

                    // Weekend background: light dithered gray pattern
                    if (col >= 5 && !day.equals(today)) {
                        fillDitheredLight(graphics, cellX + 1, cellY + 1, cellWidth - 1, cellHeight - 1, COLOR_BLACK);
                    }

                    // Highlight today: red dithered background, red text
                    if (day.equals(today)) {
                        fillDithered(graphics, cellX + 1, cellY + 1, cellWidth - 1, cellHeight - 1, COLOR_RED);
                        FontHelper.drawString(graphics, String.valueOf(day.getDayOfMonth()), Aligment.CENTER, terminalBoldFont.get(), 12, COLOR_RED, cellX, cellY + (cellHeight / 2) + 6, cellWidth, cellHeight);
                        todayCellX = cellX;
                        todayCellY = cellY;
                    } else {
                        FontHelper.drawString(graphics, String.valueOf(day.getDayOfMonth()), Aligment.CENTER, terminalBoldFont.get(), 12, COLOR_BLACK, cellX, cellY + (cellHeight / 2) + 6, cellWidth, cellHeight);
                    }

                    endCol = col;
                    cursor = day.plusDays(1);
                }

                rowColRanges.add(new int[]{startCol, endCol});
                currentY += cellHeight;
                sectionRows++;

                // Advance cursor to next Monday if needed
                if (cursor.isBefore(endDate) && cursor.getMonthValue() == currentMonth) {
                    if (cursor.getDayOfWeek() != DayOfWeek.MONDAY) {
                        cursor = cursor.with(DayOfWeek.MONDAY).plusWeeks(1);
                    }
                }
            }

            // Draw grid lines only around occupied cells
            graphics.setColor(COLOR_BLACK);
            for (int row = 0; row < sectionRows; row++) {
                int[] range = rowColRanges.get(row);
                int firstCol = range[0];
                int lastCol = range[1];
                int rowY = sectionStartY + row * cellHeight;

                // Draw individual cell borders for occupied cells
                for (int col = firstCol; col <= lastCol; col++) {
                    int cellX = x + col * cellWidth;
                    int cellY = rowY;

                    // Top edge
                    graphics.drawLine(cellX, cellY, cellX + cellWidth, cellY);
                    // Bottom edge
                    graphics.drawLine(cellX, cellY + cellHeight, cellX + cellWidth, cellY + cellHeight);
                    // Left edge (only for first occupied cell or if previous cell is unoccupied)
                    if (col == firstCol) {
                        graphics.drawLine(cellX, cellY, cellX, cellY + cellHeight);
                    }
                    // Right edge
                    graphics.drawLine(cellX + cellWidth, cellY, cellX + cellWidth, cellY + cellHeight);
                }
            }
        }

        // Draw today's red border on top of the black grid lines (overwrites black with red)
        if (todayCellX >= 0) {
            graphics.setColor(COLOR_RED);
            // Top edge
            graphics.drawLine(todayCellX, todayCellY, todayCellX + cellWidth, todayCellY);
            // Bottom edge
            graphics.drawLine(todayCellX, todayCellY + cellHeight, todayCellX + cellWidth, todayCellY + cellHeight);
            // Left edge
            graphics.drawLine(todayCellX, todayCellY, todayCellX, todayCellY + cellHeight);
            // Right edge
            graphics.drawLine(todayCellX + cellWidth, todayCellY, todayCellX + cellWidth, todayCellY + cellHeight);
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

    /**
     * Füllt einen Bereich mit einem leichten Raster-Pattern (~25% Dichte).
     * Erzeugt auf dem e-ink Display einen helleren Grauton als fillDithered.
     */
    private void fillDitheredLight(Graphics2D graphics, int x, int y, int width, int height, Color color) {
        graphics.setColor(color);
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) {
                if ((px % 2 == 0) && (py % 2 == 0)) {
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
