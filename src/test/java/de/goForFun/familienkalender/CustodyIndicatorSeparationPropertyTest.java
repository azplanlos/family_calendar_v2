package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-Test (jqwik) dafür, dass die Vollton-Indikatorbalken die Mann-Rasterung
 * im Monatskalender des Wechselmodell-Kalenders vollständig überdecken (R6.8).
 *
 * <p><b>Geänderte Semantik.</b> Die Mann-Rasterung füllt jetzt den GESAMTEN Zellhintergrund;
 * die Vollton-Indikatorbalken werden in {@code drawMonthCalendar} in einer <em>späteren</em>
 * Zeichenphase darübergelegt. Es gibt also keine disjunkten "reservierten" Zonen mehr –
 * stattdessen verlässt sich das Rendering auf die Zeichenreihenfolge: wer zuletzt zeichnet,
 * gewinnt. Dieser Test prüft genau diese Überdeckung.
 *
 * <p><b>Teststrategie – Zeichenreihenfolge im Test nachbilden.</b> In EIN einziges
 * {@code TYPE_BYTE_BINARY}-Bild mit dem 3-Farben-{@link IndexColorModel} wird
 * <ol>
 *   <li>zuerst {@code drawCustodyHighlight} (privat, via Reflection) für einen
 *       Ganztag-MANN-Tag (nicht heute) aufgerufen, sodass die ganze Zelle gerastert wird, und</li>
 *   <li>danach ein Vollton-Indikatorbalken exakt so gezeichnet, wie es
 *       {@code drawMonthCalendar} tut: ein 2px hoher {@code fillRect} über die Zellbreite
 *       {@code (cellX + 1, barY, cellWidth - 1, 2)} in einer Vollton-Balkenfarbe
 *       (COLOR_BLACK oder COLOR_RED).</li>
 * </ol>
 * Anschließend wird geprüft, dass JEDES Pixel in diesem 2px-Balkenband über die Zellbreite
 * exakt die Balkenfarbe trägt (100 % Vollton, keine durchscheinenden transparenten/roten
 * Raster-Lücken). Das beweist, dass der zuletzt gezeichnete Vollton-Balken die darunterliegende
 * Schraffur in seiner Zone vollständig überdeckt (R6.8).
 *
 * <p>Die beiden geprüften Balkenzonen entsprechen den realen Positionen aus
 * {@code drawMonthCalendar}:
 * <ul>
 *   <li>Oberer Teilnehmerbalken (Index {@code pIdx}): {@code barY = cellY + 1 + pIdx*2}.</li>
 *   <li>Unterer Unassigned-Balken: {@code barY = cellY + cellHeight - 1 - 2}.</li>
 * </ul>
 */
class CustodyIndicatorSeparationPropertyTest {

    /** COLOR_RED exakt wie in {@code ImageRenderer}: wird auf Palette-Index 2 gemappt. */
    private static final Color COLOR_RED = new Color(180, 30, 30);
    private static final Color COLOR_BLACK = Color.BLACK;

    // Palette-Indizes.
    private static final int IDX_BLACK = 1;
    private static final int IDX_RED = 2;

    private static final int BAR_HEIGHT = 2;

    /** 3-Farben-Palette identisch zu {@code ImageRenderer.createImage}. */
    private static IndexColorModel palette() {
        return new IndexColorModel(2, 3,
                new byte[]{(byte) 255, 0, (byte) 255},
                new byte[]{(byte) 255, 0, 0},
                new byte[]{(byte) 255, 0, 0},
                0);
    }

    /** Welche Balkenzone (und damit welche Balkenfarbe) geprüft wird. */
    enum BarKind {
        TOP_PARTICIPANT_BLACK,   // oberer Teilnehmerbalken, gerader Index -> schwarz
        TOP_PARTICIPANT_RED,     // oberer Teilnehmerbalken, ungerader Index -> rot
        BOTTOM_UNASSIGNED_RED,   // unterer Unassigned-Balken, HOLIDAY/VACATION -> rot
        BOTTOM_UNASSIGNED_BLACK  // unterer Unassigned-Balken, SCHOOL -> schwarz
    }

    // Feature: wechselmodell-kalender, Property 13: Vollton-Indikatorbalken überdecken die Mann-Rasterung vollständig
    // Validates: Requirements 6.8
    @Property(tries = 100)
    void solidIndicatorBarFullyCoversManDither(@ForAll("scenarios") Scenario sc) throws Exception {
        int cellX = sc.cellX;
        int cellY = sc.cellY;
        int cellWidth = sc.cellWidth;
        int cellHeight = sc.cellHeight;

        // Zielfarbe und y-Position des Balkens je nach Zone, exakt wie in drawMonthCalendar.
        Color barColor;
        int barColorIndex;
        int barY;
        switch (sc.barKind) {
            case TOP_PARTICIPANT_BLACK:
                barColor = COLOR_BLACK;
                barColorIndex = IDX_BLACK;
                barY = cellY + 1 + sc.participantIndex * BAR_HEIGHT;
                break;
            case TOP_PARTICIPANT_RED:
                barColor = COLOR_RED;
                barColorIndex = IDX_RED;
                barY = cellY + 1 + sc.participantIndex * BAR_HEIGHT;
                break;
            case BOTTOM_UNASSIGNED_RED:
                barColor = COLOR_RED;
                barColorIndex = IDX_RED;
                barY = cellY + cellHeight - 1 - BAR_HEIGHT;
                break;
            case BOTTOM_UNASSIGNED_BLACK:
            default:
                barColor = COLOR_BLACK;
                barColorIndex = IDX_BLACK;
                barY = cellY + cellHeight - 1 - BAR_HEIGHT;
                break;
        }

        // Ein Bild, in das BEIDE Phasen (Raster zuerst, Balken danach) in der realen
        // Reihenfolge gezeichnet werden.
        int imgW = cellX + cellWidth + 8;
        int imgH = cellY + cellHeight + 8;
        BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY, palette());
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        // (a) Mann-Rasterung über die GESAMTE Zelle (Ganztag MANN, nicht heute).
        LocalDate today = LocalDate.of(2024, 6, 15);
        LocalDate day = LocalDate.of(2024, 6, 10);
        Map<LocalDate, CareDay> schedule = Map.of(day, CareDay.fullDay(day, CareParty.MANN));

        List<String> participants = new ArrayList<>();
        for (int i = 0; i < sc.participantCount; i++) {
            participants.add("P" + i);
        }

        Method drawCustodyHighlight = ImageRenderer.class.getDeclaredMethod(
                "drawCustodyHighlight",
                Graphics2D.class, LocalDate.class,
                int.class, int.class, int.class, int.class,
                Map.class, LocalDate.class, List.class);
        drawCustodyHighlight.setAccessible(true);
        drawCustodyHighlight.invoke(new ImageRenderer(), g, day,
                cellX, cellY, cellWidth, cellHeight, schedule, today, participants);

        // (b) Vollton-Indikatorbalken DANACH – exakt wie in drawMonthCalendar:
        //     fillRect(cellX + 1, barY, cellWidth - 1, BAR_HEIGHT) in der Balkenfarbe.
        g.setColor(barColor);
        g.fillRect(cellX + 1, barY, cellWidth - 1, BAR_HEIGHT);
        g.dispose();

        // Jedes Pixel im 2px-Balkenband über die Zellbreite muss die Vollton-Balkenfarbe tragen.
        Raster raster = image.getRaster();
        int bandX0 = cellX + 1;
        int bandX1 = cellX + 1 + (cellWidth - 1); // exclusive
        for (int py = barY; py < barY + BAR_HEIGHT; py++) {
            for (int px = bandX0; px < bandX1; px++) {
                int idx = raster.getSample(px, py, 0);
                assertThat(idx)
                        .as("Balkenpixel (%d,%d) muss Vollton-Balkenfarbe (Index %d) sein – der "
                                + "zuletzt gezeichnete Balken überdeckt die Rasterung vollständig "
                                + "(Zone %s, Zelle %d,%d %dx%d)",
                                px, py, barColorIndex, sc.barKind, cellX, cellY, cellWidth, cellHeight)
                        .isEqualTo(barColorIndex);
            }
        }
    }

    /**
     * Szenario: Zellgeometrie, Teilnehmeranzahl/-index und Balkenzone.
     * Geometrie so gewählt, dass der Balken vollständig innerhalb der Zelle liegt und die
     * Zelle groß genug ist, dass die Rasterung tatsächlich Pixel setzt (sonst wäre nichts
     * zu überdecken).
     */
    record Scenario(int cellX, int cellY, int cellWidth, int cellHeight,
                    int participantCount, int participantIndex, BarKind barKind) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<Integer> cellX = Arbitraries.integers().between(0, 30);
        Arbitrary<Integer> cellY = Arbitraries.integers().between(0, 30);
        Arbitrary<Integer> cellWidth = Arbitraries.integers().between(20, 120);
        Arbitrary<Integer> cellHeight = Arbitraries.integers().between(24, 60);
        Arbitrary<Integer> participantCount = Arbitraries.integers().between(1, 4);
        Arbitrary<BarKind> barKind = Arbitraries.of(BarKind.class);
        return Combinators.combine(cellX, cellY, cellWidth, cellHeight, participantCount, barKind)
                .as((cx, cy, cw, ch, pc, bk) -> {
                    // Teilnehmer-Index liegt im Bereich [0, pc) und so, dass der obere Balken
                    // (barY = cellY + 1 + idx*2, Höhe 2) noch innerhalb der Zelle bleibt.
                    int idx = 0;
                    if (bk == BarKind.TOP_PARTICIPANT_BLACK || bk == BarKind.TOP_PARTICIPANT_RED) {
                        idx = Math.min(pc - 1, (ch - 3) / BAR_HEIGHT - 1);
                        if (idx < 0) {
                            idx = 0;
                        }
                    }
                    return new Scenario(cx, cy, cw, ch, pc, idx, bk);
                });
    }
}
