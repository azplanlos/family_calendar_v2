package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;
import de.goForFun.familienkalender.model.RenderData;
import de.goForFun.familienkalender.model.WeatherDay;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-Tests für das Party→Icon-Mapping und die Platzierung des Betreuungs-Piktogramms
 * in der Tagesansicht.
 *
 * <p>Verifiziert über {@link ImageRenderer#createImage(RenderData)} direkt am
 * gerenderten Raster (Index 0=transparent, 1=schwarz, 2=rot):
 * <ul>
 *   <li>MANN ⇒ rotes WC-Piktogramm (rote Pixel in der Icon-Box), kein schwarzer Icon-Pixel</li>
 *   <li>FRAU ⇒ schwarzes WC-Piktogramm (schwarze Pixel in der Icon-Box), kein roter</li>
 *   <li>Icon am rechten Ende der Heute/Morgen-Überschrift (rechter Spaltenrand)</li>
 *   <li>Leerer Schedule ⇒ kein Icon in beiden Boxen</li>
 * </ul>
 *
 * <p>Das Icon sitzt bei {@code (x + width - 16, DAY_EVENTS_Y_START - 9)} mit Kantenlänge 16.
 * Für die Heute-Spalte (x=10, width=220) ⇒ Box {@code x∈[214,230], y∈[51,67]};
 * für die Morgen-Spalte (x=240, width=220) ⇒ Box {@code x∈[444,460], y∈[51,67]}.
 * Es wird eng innerhalb der 16×16-Box gescannt, damit rechtsbündiger Überschrifttext
 * nicht in die Zählung hineinblutet.
 *
 * _Requirements: 5.1, 5.2, 5.5, 5.6, 5.9_
 */
class CareIconRenderingTest {

    // Raster-Farbindizes des 3-Farben-IndexColorModel
    private static final int IDX_BLACK = 1;
    private static final int IDX_RED = 2;

    // Icon-Box: (x + width - ICON_SIZE, DAY_EVENTS_Y_START - 9, 16, 16).
    // Heute-Spalte: TODAY_COL_X=10, width=220  -> iconX = 214, iconTop = 51 (x∈[214,230], y∈[51,67]).
    // Morgen-Spalte: TOMORROW_COL_X=240, width=220 -> iconX = 444, iconTop = 51 (x∈[444,460], y∈[51,67]).
    private static final int ICON_SIZE = 16;
    private static final int HEUTE_ICON_X = 214;
    private static final int MORGEN_ICON_X = 444;
    private static final int ICON_Y = 51;   // DAY_EVENTS_Y_START (60) - 9

    // Fester Zeitpunkt: today/tomorrow deterministisch
    private static final LocalDateTime NOW = LocalDateTime.of(2024, 6, 10, 8, 0);
    private static final LocalDate TODAY = NOW.toLocalDate();
    private static final LocalDate TOMORROW = TODAY.plusDays(1);

    private RenderData renderData(Map<LocalDate, CareDay> custodySchedule) {
        return new RenderData(
                NOW,
                List.of(),                 // todayEvents
                List.of(),                 // tomorrowEvents
                List.<WeatherDay>of(),     // weatherDays
                List.of(),                 // calendarEvents
                List.of(),                 // participants
                List.of(),                 // errors
                custodySchedule
        );
    }

    /** Zählt Pixel mit dem gegebenen Farbindex in der angegebenen Box. */
    private int countIndex(BufferedImage image, int x, int y, int size, int index) {
        Raster raster = image.getRaster();
        int[] px = new int[1];
        int count = 0;
        for (int dy = 0; dy < size; dy++) {
            for (int dx = 0; dx < size; dx++) {
                raster.getPixel(x + dx, y + dy, px);
                if (px[0] == index) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    void mann_drawsRedPiktogramm_inHeuteWcIconBox() {
        Map<LocalDate, CareDay> schedule = Map.of(TODAY, CareDay.fullDay(TODAY, CareParty.MANN));
        BufferedImage image = new ImageRenderer().createImage(renderData(schedule));

        assertEquals(800, image.getWidth());
        assertEquals(480, image.getHeight());

        int red = countIndex(image, HEUTE_ICON_X, ICON_Y, ICON_SIZE, IDX_RED);
        int black = countIndex(image, HEUTE_ICON_X, ICON_Y, ICON_SIZE, IDX_BLACK);

        assertTrue(red > 0, "MANN-Piktogramm muss rote Pixel in der Heute-Icon-Box haben");
        assertEquals(0, black, "MANN-Piktogramm darf keine schwarzen Pixel in der Icon-Box haben");
    }

    @Test
    void frau_drawsBlackPiktogramm_inHeuteWcIconBox() {
        Map<LocalDate, CareDay> schedule = Map.of(TODAY, CareDay.fullDay(TODAY, CareParty.FRAU));
        BufferedImage image = new ImageRenderer().createImage(renderData(schedule));

        int red = countIndex(image, HEUTE_ICON_X, ICON_Y, ICON_SIZE, IDX_RED);
        int black = countIndex(image, HEUTE_ICON_X, ICON_Y, ICON_SIZE, IDX_BLACK);

        assertTrue(black > 0, "FRAU-Piktogramm muss schwarze Pixel in der Heute-Icon-Box haben");
        assertEquals(0, red, "FRAU-Piktogramm darf keine roten Pixel in der Icon-Box haben");
    }

    @Test
    void piktogramm_placedAtRightEndOf_bothHeuteAndMorgenHeaders() {
        // Heute = MANN (rot), Morgen = FRAU (schwarz)
        Map<LocalDate, CareDay> schedule = Map.of(
                TODAY, CareDay.fullDay(TODAY, CareParty.MANN),
                TOMORROW, CareDay.fullDay(TOMORROW, CareParty.FRAU)
        );
        BufferedImage image = new ImageRenderer().createImage(renderData(schedule));

        // Heute-Spalte: rotes Piktogramm am rechten Spaltenende
        assertTrue(countIndex(image, HEUTE_ICON_X, ICON_Y, ICON_SIZE, IDX_RED) > 0,
                "Heute-Piktogramm (MANN) muss in der Icon-Box am rechten Rand der Heute-Spalte liegen");
        // Morgen-Spalte: schwarzes Piktogramm am rechten Spaltenende
        assertTrue(countIndex(image, MORGEN_ICON_X, ICON_Y, ICON_SIZE, IDX_BLACK) > 0,
                "Morgen-Piktogramm (FRAU) muss in der Icon-Box am rechten Rand der Morgen-Spalte liegen");
    }

    @Test
    void emptySchedule_drawsNoPiktogramm_inEitherWcIconBox() {
        BufferedImage empty = new ImageRenderer().createImage(renderData(Map.of()));

        assertEquals(800, empty.getWidth());
        assertEquals(480, empty.getHeight());

        // Ohne CareDay wird KEIN Icon gezeichnet. Rot wird in der Icon-Box ausschließlich
        // vom MANN-Piktogramm gesetzt – die (immer schwarze) rechtsbündige Überschrift
        // zeichnet nie Rot. Daher ist "0 rote Pixel in beiden Boxen" der eindeutige Beweis,
        // dass kein (MANN-)Piktogramm gerendert wurde.
        assertEquals(0, countIndex(empty, HEUTE_ICON_X, ICON_Y, ICON_SIZE, IDX_RED),
                "Leerer Schedule: keine roten Pixel in der Heute-Icon-Box (kein MANN-Piktogramm)");
        assertEquals(0, countIndex(empty, MORGEN_ICON_X, ICON_Y, ICON_SIZE, IDX_RED),
                "Leerer Schedule: keine roten Pixel in der Morgen-Icon-Box (kein MANN-Piktogramm)");

        // Gegenprobe für das FRAU-(schwarze)-Piktogramm: Da die rechtsbündige Überschrift
        // selbst schwarze Pixel in die Box legt, ist ein reiner Nullvergleich auf Schwarz
        // nicht aussagekräftig. Stattdessen wird gezeigt, dass ein gezeichnetes FRAU-Icon
        // NACHWEISLICH zusätzliche schwarze Pixel erzeugt: Bei leerem Schedule liegen echt
        // weniger schwarze Pixel in der Box als bei einem FRAU-Tag.
        BufferedImage frau = new ImageRenderer().createImage(
                renderData(Map.of(TODAY, CareDay.fullDay(TODAY, CareParty.FRAU))));
        int blackEmpty = countIndex(empty, HEUTE_ICON_X, ICON_Y, ICON_SIZE, IDX_BLACK);
        int blackFrau = countIndex(frau, HEUTE_ICON_X, ICON_Y, ICON_SIZE, IDX_BLACK);
        assertTrue(blackFrau > blackEmpty,
                "FRAU-Tag muss zusätzliche schwarze Icon-Pixel gegenüber dem leeren Schedule erzeugen "
                        + "(empty=" + blackEmpty + ", frau=" + blackFrau + ")");
    }
}
