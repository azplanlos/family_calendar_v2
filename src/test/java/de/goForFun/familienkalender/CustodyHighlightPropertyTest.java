package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-Test (jqwik) für die Highlight-Region-Korrektheit der Mann-Tag-Rasterung
 * im Monatskalender ({@link ImageRenderer#drawCustodyHighlight}, privat, via Reflection).
 *
 * <p>Geprüft wird, WELCHE Pixel einer Zelle nach dem Aufruf rot (Palette-Index 2)
 * sind, und dass diese Region exakt der im Design beschriebenen Geometrie entspricht.
 * Die Rasterung füllt den GESAMTEN nutzbaren Zellhintergrund; es gibt keine
 * reservierten Teilnehmer-/Unassigned-Zonen mehr (die Vollton-Balken werden erst in
 * einer späteren Zeichenphase darübergelegt – nicht Gegenstand dieses Tests).
 *
 * <p>Innere Geometrie laut design.md ("ImageRenderer: Monatskalender-Hervorhebung (R6)"):
 * innerX = cellX+1, innerY = cellY+1, innerW = cellWidth-1, innerH = cellHeight-1.
 *
 * <ul>
 *   <li>Ganztag MANN: rote Pixel nur im inneren Bereich, dort vorhanden (Dither &gt; 0);
 *       außerhalb (Rand) keine roten Pixel.</li>
 *   <li>Ganztag FRAU / fehlender CareDay / Heute: gar keine roten Pixel.</li>
 *   <li>Wechseltag: diagonale Teilung (unten-links -&gt; oben-rechts). Für ein rotes Pixel
 *       mit normierter Lage {@code u = (px-innerX+0.5)/innerW}, {@code v = (py-innerY+0.5)/innerH}
 *       gilt: liegt es im oberen-linken Dreieck ({@code u+v < 1}), impliziert das
 *       firstHalf==MANN; liegt es im unteren-rechten Dreieck ({@code u+v >= 1}), impliziert
 *       das secondHalf==MANN. Beide Dreiecke sind disjunkt.</li>
 * </ul>
 */
class CustodyHighlightPropertyTest {

    /** Dieselbe 3-Farben-Palette wie in {@code ImageRenderer.createImage}. */
    private static final IndexColorModel PALETTE = new IndexColorModel(
            2, 3,
            new byte[]{(byte) 255, 0, (byte) 255},
            new byte[]{(byte) 255, 0, 0},
            new byte[]{(byte) 255, 0, 0},
            0);

    private static final LocalDate TODAY = LocalDate.of(2024, 6, 15);
    /** Ein Tag != TODAY, der zum Rendern verwendet wird. */
    private static final LocalDate DAY = LocalDate.of(2024, 6, 10);

    private static Method highlightMethod() throws Exception {
        Method m = ImageRenderer.class.getDeclaredMethod(
                "drawCustodyHighlight",
                Graphics2D.class, LocalDate.class, int.class, int.class, int.class, int.class,
                Map.class, LocalDate.class, List.class);
        m.setAccessible(true);
        return m;
    }

    /** Beschreibt die zu zeichnende CareDay-Variante. */
    enum Variant { FULL_MANN, FULL_FRAU, MISSING, SWITCH, TODAY_FULL_MANN }

    /** Zählt rote Pixel (Index 2) in einem Rechteck des gerenderten Bildes. */
    private static long countRed(Raster raster, int x, int y, int w, int h) {
        long set = 0;
        for (int py = y; py < y + h; py++) {
            for (int px = x; px < x + w; px++) {
                if (raster.getSample(px, py, 0) == 2) {
                    set++;
                }
            }
        }
        return set;
    }

    // Feature: wechselmodell-kalender, Property 11: Highlight-Region entspricht CareDay (Ganztag/FRAU/Wechseltag-Dreiecke diagonal, disjunkt)
    // Validates: Requirements 6.1, 6.3, 6.4, 6.5, 6.6, 6.9
    @Property(tries = 100)
    void highlightRegionMatchesCareDay(@ForAll("scenarios") Object[] scenario) throws Exception {
        Variant variant = (Variant) scenario[0];
        int cellX = (int) scenario[1];
        int cellY = (int) scenario[2];
        int cellWidth = (int) scenario[3];
        int cellHeight = (int) scenario[4];
        int participantCount = (int) scenario[5];
        CareParty firstHalf = (CareParty) scenario[6];
        CareParty secondHalf = (CareParty) scenario[7];

        List<String> participants = new ArrayList<>();
        for (int i = 0; i < participantCount; i++) {
            participants.add("P" + i);
        }

        // Für MISSING: leerer Schedule. Sonst einen CareDay für DAY (bzw. TODAY) eintragen.
        LocalDate renderDay = (variant == Variant.TODAY_FULL_MANN) ? TODAY : DAY;
        Map<LocalDate, CareDay> schedule;
        switch (variant) {
            case FULL_MANN:
            case TODAY_FULL_MANN:
                schedule = Map.of(renderDay, CareDay.fullDay(renderDay, CareParty.MANN));
                break;
            case FULL_FRAU:
                schedule = Map.of(renderDay, CareDay.fullDay(renderDay, CareParty.FRAU));
                break;
            case SWITCH:
                schedule = Map.of(renderDay, CareDay.switchDay(renderDay, firstHalf, secondHalf, LocalTime.of(16, 0)));
                break;
            case MISSING:
            default:
                schedule = Map.of();
                break;
        }

        // Bild groß genug für die gesamte Zelle.
        BufferedImage image = new BufferedImage(cellX + cellWidth, cellY + cellHeight,
                BufferedImage.TYPE_BYTE_BINARY, PALETTE);
        Graphics2D g = image.createGraphics();
        highlightMethod().invoke(new ImageRenderer(), g, renderDay,
                cellX, cellY, cellWidth, cellHeight, schedule, TODAY, participants);
        g.dispose();

        Raster raster = image.getRaster();

        // Innere Geometrie exakt wie im Design / in der Implementierung:
        // keine reservierten Zonen mehr – der gesamte nutzbare Zellhintergrund.
        int innerX = cellX + 1;
        int innerY = cellY + 1;
        int innerW = cellWidth - 1;
        int innerH = cellHeight - 1;
        assertThat(innerW).as("generator must guarantee innerW > 0").isGreaterThan(0);
        assertThat(innerH).as("generator must guarantee innerH > 0").isGreaterThan(0);

        long redWholeCell = countRed(raster, cellX, cellY, cellWidth, cellHeight);
        long redInner = countRed(raster, innerX, innerY, innerW, innerH);
        // Rote Pixel AUSSERHALB des inneren Bereichs = alle roten minus die im inneren Bereich.
        long redOutsideInner = redWholeCell - redInner;

        switch (variant) {
            case FULL_FRAU:
            case MISSING:
            case TODAY_FULL_MANN:
                // Keine roten Pixel irgendwo in der Zelle.
                assertThat(redWholeCell)
                        .as("variant %s must produce NO red pixels (cell %d,%d %dx%d, participants=%d)",
                                variant, cellX, cellY, cellWidth, cellHeight, participantCount)
                        .isZero();
                break;

            case FULL_MANN: {
                // Rote Pixel vorhanden, ausschließlich im inneren Bereich.
                assertThat(redInner)
                        .as("full-day MANN must fill inner region with dither (>0 red)")
                        .isGreaterThan(0);
                assertThat(redOutsideInner)
                        .as("full-day MANN must NOT paint red outside inner region (borders)")
                        .isZero();
                break;
            }

            case SWITCH: {
                // Keine roten Pixel außerhalb des inneren Bereichs.
                assertThat(redOutsideInner)
                        .as("switch-day must NOT paint red outside inner region")
                        .isZero();

                // Jedes rote Pixel nach Diagonale klassifizieren:
                // u + v < 1  => oberes-linkes Dreieck; sonst unteres-rechtes Dreieck.
                long redUpperLeft = 0;
                long redLowerRight = 0;
                for (int py = innerY; py < innerY + innerH; py++) {
                    for (int px = innerX; px < innerX + innerW; px++) {
                        if (raster.getSample(px, py, 0) != 2) {
                            continue;
                        }
                        double u = (px - innerX + 0.5) / innerW;
                        double v = (py - innerY + 0.5) / innerH;
                        if (u + v < 1.0) {
                            redUpperLeft++;
                        } else {
                            redLowerRight++;
                        }
                    }
                }

                // Alle roten Pixel liegen in genau einem der beiden disjunkten Dreiecke.
                assertThat(redUpperLeft + redLowerRight)
                        .as("all inner red pixels must fall into one of the two diagonal triangles")
                        .isEqualTo(redInner);

                // Oberes-linkes Dreieck rot genau dann, wenn firstHalf == MANN.
                if (firstHalf == CareParty.MANN) {
                    assertThat(redUpperLeft)
                            .as("switch-day firstHalf=MANN must paint UPPER_LEFT triangle")
                            .isGreaterThan(0);
                } else {
                    assertThat(redUpperLeft)
                            .as("switch-day firstHalf=%s must leave UPPER_LEFT triangle empty", firstHalf)
                            .isZero();
                }

                // Unteres-rechtes Dreieck rot genau dann, wenn secondHalf == MANN.
                if (secondHalf == CareParty.MANN) {
                    assertThat(redLowerRight)
                            .as("switch-day secondHalf=MANN must paint LOWER_RIGHT triangle")
                            .isGreaterThan(0);
                } else {
                    assertThat(redLowerRight)
                            .as("switch-day secondHalf=%s must leave LOWER_RIGHT triangle empty", secondHalf)
                            .isZero();
                }
                break;
            }
            default:
                throw new IllegalStateException("unhandled variant " + variant);
        }
    }

    /**
     * Szenarien als Object[]: [Variant, cellX, cellY, cellWidth, cellHeight,
     * participantCount, firstHalf, secondHalf].
     *
     * <p>Geometrie: cellWidth in [20,120], cellHeight in [20,60], offsets in [0,40],
     * participantCount in [0,4]. Daraus folgt innerW = cellWidth-1 &gt;= 19 und
     * innerH = cellHeight-1 &gt;= 19, sodass jedes Diagonal-Dreieck groß genug ist,
     * um gesetzte Gitterpunkte (Periode 4 in x, 2 in y) zu enthalten.
     *
     * <p>firstHalf/secondHalf decken alle vier MANN/FRAU-Kombinationen ab (nur für SWITCH
     * relevant; für andere Varianten ignoriert).
     */
    @Provide
    Arbitrary<Object[]> scenarios() {
        Arbitrary<Variant> variant = Arbitraries.of(Variant.class);
        Arbitrary<Integer> cellX = Arbitraries.integers().between(0, 40);
        Arbitrary<Integer> cellY = Arbitraries.integers().between(0, 40);
        Arbitrary<Integer> cellWidth = Arbitraries.integers().between(20, 120);
        Arbitrary<Integer> cellHeight = Arbitraries.integers().between(20, 60);
        Arbitrary<Integer> participants = Arbitraries.integers().between(0, 4);
        Arbitrary<CareParty> firstHalf = Arbitraries.of(CareParty.class);
        Arbitrary<CareParty> secondHalf = Arbitraries.of(CareParty.class);

        return Combinators.combine(
                        variant, cellX, cellY, cellWidth, cellHeight, participants, firstHalf, secondHalf)
                .as((v, cx, cy, cw, ch, p, fh, sh) ->
                        new Object[]{v, cx, cy, cw, ch, p, fh, sh});
    }
}
