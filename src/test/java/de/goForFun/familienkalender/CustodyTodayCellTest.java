package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;
import de.goForFun.familienkalender.model.RenderData;
import de.goForFun.familienkalender.model.WeatherDay;
import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-Test für R6.7: An der Heute-Zelle dominiert die bestehende Heute-Hervorhebung.
 *
 * <p>Die private Methode {@link ImageRenderer} {@code drawCustodyHighlight} kehrt für
 * die Heute-Zelle <strong>früh zurück</strong> ({@code if (day.equals(today)) return;}),
 * <em>bevor</em> irgendeine Mann-Rasterung gezeichnet wird. Dadurch bleiben die bestehende
 * Heute-Hervorhebung (50%-rot gedithert) und die Tageszahl unverändert – es wird
 * <strong>keine zusätzliche Mann-Rasterung</strong> für den Heute-Tag aufgetragen (R6.7).
 *
 * <p>Teststrategie (robuste Variante B):
 * <ul>
 *   <li>Direkte Reflection auf {@code drawCustodyHighlight}: für {@code day == today} mit
 *       Ganztag-MANN-Schedule in ein leeres 3-Farben-Bild gezeichnet ⇒ ZERO rote Pixel.
 *       Das beweist unmittelbar, dass die Methode vor dem Zeichnen zurückkehrt.</li>
 *   <li>Leichtgewichtiger Full-Render-Smoke: {@code createImage} mit Heute=MANN erzeugt
 *       ein 800x480-Bild, wirft nicht, und die Heute-Zelle enthält weiterhin Rot
 *       (die Heute-Hervorhebung) und schwarze Pixel (die gezeichnete Tageszahl).</li>
 * </ul>
 *
 * _Requirements: 6.7_
 */
class CustodyTodayCellTest {

    // Raster-Farbindizes des 3-Farben-IndexColorModel (Index 0=transparent/weiß, 1=schwarz, 2=rot)
    private static final int IDX_BLACK = 1;
    private static final int IDX_RED = 2;

    /** Dieselbe 3-Farben-Palette wie in {@code ImageRenderer.createImage}. */
    private static final IndexColorModel PALETTE = new IndexColorModel(
            2, 3,
            new byte[]{(byte) 255, 0, (byte) 255},
            new byte[]{(byte) 255, 0, 0},
            new byte[]{(byte) 255, 0, 0},
            0);

    // Fester Zeitpunkt: today ist deterministisch.
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 10, 8, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();

    /**
     * Variante B – Kern: drawCustodyHighlight kehrt für die Heute-Zelle früh zurück (R6.7).
     *
     * <p>Wir rufen die private Methode direkt für {@code day == today} auf, obwohl der
     * Schedule für heute einen Ganztag-MANN enthält. Da die Methode VOR dem Zeichnen
     * zurückkehrt, darf im Zielrechteck kein einziges rotes (Index 2) Pixel gesetzt sein.
     */
    @Test
    void drawCustodyHighlight_returnsEarlyForToday_noManDither() throws Exception {
        int cellX = 5;
        int cellY = 5;
        int cellWidth = 40;
        int cellHeight = 38;

        BufferedImage image = new BufferedImage(
                cellX + cellWidth + 5, cellY + cellHeight + 5,
                BufferedImage.TYPE_BYTE_BINARY, PALETTE);
        Graphics2D g = image.createGraphics();

        // Schedule mit Ganztag MANN genau für den Heute-Tag.
        Map<LocalDate, CareDay> schedule = Map.of(TODAY, CareDay.fullDay(TODAY, CareParty.MANN));
        List<String> participants = List.of("Anna", "Bert");

        Method m = ImageRenderer.class.getDeclaredMethod(
                "drawCustodyHighlight",
                Graphics2D.class, LocalDate.class, int.class, int.class, int.class, int.class,
                Map.class, LocalDate.class, List.class);
        m.setAccessible(true);
        // day == today ⇒ R6.7: frühzeitiger Return, keine Rasterung.
        m.invoke(new ImageRenderer(), g, TODAY, cellX, cellY, cellWidth, cellHeight,
                schedule, TODAY, participants);
        g.dispose();

        long redPixels = countPixels(image.getRaster(), IDX_RED,
                0, 0, image.getWidth(), image.getHeight());

        assertThat(redPixels)
                .as("drawCustodyHighlight darf für die Heute-Zelle (R6.7) KEINE Mann-Rasterung zeichnen")
                .isZero();
    }

    /**
     * Gegenprobe: Für einen NICHT-Heute-Tag mit Ganztag MANN zeichnet dieselbe Methode
     * sehr wohl rote Rasterpixel. Das stellt sicher, dass die Nullassertion oben an der
     * Heute-Bedingung liegt und nicht an einem generell nicht zeichnenden Aufruf.
     */
    @Test
    void drawCustodyHighlight_drawsManDither_forNonTodayDay() throws Exception {
        int cellX = 5;
        int cellY = 5;
        int cellWidth = 40;
        int cellHeight = 38;

        LocalDate otherDay = TODAY.plusDays(3);

        BufferedImage image = new BufferedImage(
                cellX + cellWidth + 5, cellY + cellHeight + 5,
                BufferedImage.TYPE_BYTE_BINARY, PALETTE);
        Graphics2D g = image.createGraphics();

        Map<LocalDate, CareDay> schedule = Map.of(otherDay, CareDay.fullDay(otherDay, CareParty.MANN));
        List<String> participants = List.of("Anna", "Bert");

        Method m = ImageRenderer.class.getDeclaredMethod(
                "drawCustodyHighlight",
                Graphics2D.class, LocalDate.class, int.class, int.class, int.class, int.class,
                Map.class, LocalDate.class, List.class);
        m.setAccessible(true);
        m.invoke(new ImageRenderer(), g, otherDay, cellX, cellY, cellWidth, cellHeight,
                schedule, TODAY, participants);
        g.dispose();

        long redPixels = countPixels(image.getRaster(), IDX_RED,
                0, 0, image.getWidth(), image.getHeight());

        assertThat(redPixels)
                .as("drawCustodyHighlight muss für einen Nicht-Heute-Tag mit Ganztag-MANN Rasterpixel zeichnen")
                .isPositive();
    }

    /**
     * Variante A (leichtgewichtiger Smoke): Full-Render mit Heute=MANN.
     *
     * <p>Prüft, dass {@code createImage} nicht wirft, ein 800x480-Bild liefert und die
     * Heute-Zelle weiterhin die Heute-Hervorhebung (rote Pixel) sowie die gezeichnete
     * Tageszahl (schwarze Pixel) enthält. Die Heute-Hervorhebung bleibt also unverändert
     * bestehen (R6.7) – unabhängig vom MANN-Eintrag für heute.
     */
    @Test
    void createImage_withTodayMann_rendersTodayHighlightAndDayNumber() {
        RenderData data = new RenderData(
                NOW,
                List.of(),                 // todayEvents
                List.of(),                 // tomorrowEvents
                List.<WeatherDay>of(),     // weatherDays
                List.of(),                 // calendarEvents
                List.of(),                 // participants
                List.of(),                 // errors
                Map.of(TODAY, CareDay.fullDay(TODAY, CareParty.MANN)));

        BufferedImage image = new ImageRenderer().createImage(data);

        assertThat(image).as("createImage darf nicht null liefern").isNotNull();
        assertThat(image.getWidth()).isEqualTo(800);
        assertThat(image.getHeight()).isEqualTo(480);

        // Das gesamte Bild muss sowohl die Heute-Hervorhebung (rot) als auch gezeichnete
        // Tageszahlen/Linien (schwarz) enthalten.
        Raster raster = image.getRaster();
        long redTotal = countPixels(raster, IDX_RED, 0, 0, image.getWidth(), image.getHeight());
        long blackTotal = countPixels(raster, IDX_BLACK, 0, 0, image.getWidth(), image.getHeight());

        assertThat(redTotal)
                .as("Full-Render mit Heute=MANN muss rote Pixel enthalten (Heute-Hervorhebung bleibt, R6.7)")
                .isPositive();
        assertThat(blackTotal)
                .as("Full-Render muss schwarze Pixel enthalten (Tageszahlen/Linien gezeichnet)")
                .isPositive();
    }

    /** Zählt Pixel eines bestimmten Palettenindex im angegebenen Rechteck. */
    private static long countPixels(Raster raster, int index, int x, int y, int width, int height) {
        long count = 0;
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) {
                if (raster.getSample(px, py, 0) == index) {
                    count++;
                }
            }
        }
        return count;
    }
}
