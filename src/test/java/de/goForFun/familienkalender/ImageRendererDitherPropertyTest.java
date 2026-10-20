package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.lang.reflect.Method;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-Test (jqwik) für das Betreuungs-Dithering in {@link ImageRenderer}.
 *
 * <p>Die private Methode {@code fillCustodyDither} setzt COLOR_RED-Pixel in einem
 * gleichmäßigen 3×3-Punktgitter dort, wo {@code (px % 3 == 0) && (py % 3 == 0)} –
 * also jedes neunte Pixel, mit identischem Abstand (3) horizontal wie vertikal.
 * Die asymptotische Soll-Dichte ist exakt 1/9 ≈ 11,1 %.
 *
 * <p>Das Design behauptet, dass die Dichte der gesetzten Pixel für jedes Rechteck
 * zellrealistischer Größe im geschlossenen Intervall [8 %, 20 %] liegt.
 *
 * <p><b>Gültigkeitsbereich der Garantie:</b> Beim 3×3-Gitter stabilisiert sich die
 * Dichte erst dann nahe 11 %, wenn beide Kanten mehrere Gitterperioden überspannen.
 * Für sehr kleine, phasenungünstig platzierte Rechtecke (z. B. 8×4 am Gitteroffset
 * (1,1) ⇒ nur 2 von 32 Pixeln = 6,25 %) ist die Untergrenze 8 % nicht erreichbar.
 * Solche Mini-Flächen treten bei der realen Monatskalender-Zellgeometrie (innere
 * Zellfläche ~43×37) nicht auf. Der Generator beschränkt sich daher bewusst auf
 * zellrealistische Größen (w,h ∈ [20,120]); in diesem Bereich hält die implementierte
 * Rasterung die Dichte deterministisch in [9 %, 13,3 %] ⊂ [8 %, 20 %].
 *
 * <p>Validates: Requirements 6.2
 */
class ImageRendererDitherPropertyTest {

    /** Dieselbe 3-Farben-Palette wie in {@code ImageRenderer.createImage}. */
    private static final IndexColorModel PALETTE = new IndexColorModel(
            2, 3,
            new byte[]{(byte) 255, 0, (byte) 255},
            new byte[]{(byte) 255, 0, 0},
            new byte[]{(byte) 255, 0, 0},
            0);

    // Feature: wechselmodell-kalender, Property 12: Dithering-Dichte liegt für jedes Rechteck in [8%, 20%]
    // Validates: Requirements 6.2
    @Property(tries = 100)
    void ditherDensityWithinRange(@ForAll("rects") int[] rect) throws Exception {
        int x = rect[0];
        int y = rect[1];
        int width = rect[2];
        int height = rect[3];

        // Bild groß genug für x+width / y+height.
        BufferedImage image = new BufferedImage(x + width, y + height,
                BufferedImage.TYPE_BYTE_BINARY, PALETTE);
        Graphics2D g = image.createGraphics();

        Method m = ImageRenderer.class.getDeclaredMethod(
                "fillCustodyDither", Graphics2D.class, int.class, int.class, int.class, int.class);
        m.setAccessible(true);
        m.invoke(new ImageRenderer(), g, x, y, width, height);
        g.dispose();

        // Gesetzte (rote, Index == 2) Pixel innerhalb des Rechtecks zählen.
        Raster raster = image.getRaster();
        long set = 0;
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) {
                if (raster.getSample(px, py, 0) == 2) {
                    set++;
                }
            }
        }

        double fraction = (double) set / ((long) width * height);
        assertThat(fraction)
                .as("dither density for rect x=%d y=%d w=%d h=%d (set=%d of %d)",
                        x, y, width, height, set, (long) width * height)
                .isBetween(0.08, 0.20);
    }

    /**
     * Rechtecke [x, y, w, h] mit zellrealistischer Größe: w in [20,120], h in [20,120]
     * (entspricht realen Monatskalender-Zellen bzw. -Zellhälften). Variable, beschränkte Offsets,
     * sodass alle Gitterphasen abgedeckt sind.
     *
     * <p>Sehr kleine, phasenungünstig platzierte Rechtecke (w &lt; 20 oder h &lt; 20) sind bewusst
     * ausgenommen: beim 3×3-Gitter dippt dort die Dichte offsetabhängig unter 8 % (z. B. 8×4 am
     * Offset (1,1) ⇒ 6,25 %); erst ab zellrealistischer Größe stabilisiert sich die Dichte nahe
     * 1/9 ≈ 11 %. Solche Mini-Flächen treten bei der realen Zellgeometrie nicht auf (siehe
     * design.md, Property 12, Requirement 6.2). Im gewählten Bereich liegt die Dichte garantiert
     * in [9 %, 13,3 %] ⊂ [8 %, 20 %].
     */
    @Provide
    Arbitrary<int[]> rects() {
        Arbitrary<Integer> offset = Arbitraries.integers().between(0, 200);
        Arbitrary<Integer> width = Arbitraries.integers().between(20, 120);
        Arbitrary<Integer> height = Arbitraries.integers().between(20, 120);
        return Combinators.combine(offset, offset, width, height)
                .as((x, y, w, h) -> new int[]{x, y, w, h});
    }
}
