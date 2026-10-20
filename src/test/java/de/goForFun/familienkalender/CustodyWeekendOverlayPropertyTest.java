package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
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
 * Property-Test (jqwik) für die Überlagerung von grauem Wochenend-Raster und roter
 * Mann-Schraffur in derselben Monatskalenderzelle (R6.10).
 *
 * <p>Reproduziert die reale Zeichenreihenfolge aus {@code drawMonthCalendar} für eine
 * Mann-Wochenend-Zelle: zuerst {@code fillDitheredLight(COLOR_BLACK)} (Wochenend-Grau,
 * Schachbrett-Light {@code px%2==0 && py%2==0}), danach {@code fillCustodyDither(COLOR_RED)}
 * (Mann-Raster {@code px%3==0 && py%3==0}). Beide privaten Methoden werden per Reflection
 * in einen {@code TYPE_BYTE_BINARY}-Puffer mit demselben 3-Farben-{@link IndexColorModel}
 * wie {@code ImageRenderer.createImage} gezeichnet.
 */
class CustodyWeekendOverlayPropertyTest {

    /** 3-Farben-Palette identisch zu {@code ImageRenderer.createImage}. */
    private static IndexColorModel palette() {
        return new IndexColorModel(2, 3,
                new byte[]{(byte) 255, 0, (byte) 255},
                new byte[]{(byte) 255, 0, 0},
                new byte[]{(byte) 255, 0, 0},
                0);
    }

    private static Method method(String name) throws Exception {
        Method m = ImageRenderer.class.getDeclaredMethod(
                name, Graphics2D.class, int.class, int.class, int.class, int.class, Color.class);
        m.setAccessible(true);
        return m;
    }

    private static Method custodyDither() throws Exception {
        Method m = ImageRenderer.class.getDeclaredMethod(
                "fillCustodyDither", Graphics2D.class, int.class, int.class, int.class, int.class);
        m.setAccessible(true);
        return m;
    }

    // Feature: wechselmodell-kalender, Property 14: Wochenend- und Mann-Raster erzeugen nur erlaubte Volltonfarben (rot gewinnt an Kollisionen)
    // Validates: Requirements 6.10
    @Property(tries = 100)
    void weekendPlusMannOverlayProducesOnlyAllowedIndices(@ForAll("rects") int[] rect) throws Exception {
        int x = rect[0];
        int y = rect[1];
        int width = rect[2];
        int height = rect[3];

        BufferedImage image = new BufferedImage(x + width, y + height,
                BufferedImage.TYPE_BYTE_BINARY, palette());
        Graphics2D g = image.createGraphics();
        ImageRenderer renderer = new ImageRenderer();

        // Reale Reihenfolge: zuerst graues Wochenend-Raster, dann rote Mann-Schraffur.
        method("fillDitheredLight").invoke(renderer, g, x, y, width, height, Color.BLACK);
        custodyDither().invoke(renderer, g, x, y, width, height);
        g.dispose();

        Raster raster = image.getRaster();
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) {
                int index = raster.getSample(px, py, 0);
                // Nur erlaubte Paletten-Indizes: 0=transparent, 1=schwarz, 2=rot.
                assertThat(index)
                        .as("Pixel (%d,%d) muss ein erlaubter Index sein (keine Mischfarbe/Graustufe)", px, py)
                        .isIn(0, 1, 2);
                // Kollisionspixel (beide Raster würden setzen) müssen rot sein (rot zuletzt gezeichnet).
                boolean wouldBeGrey = (px % 2 == 0) && (py % 2 == 0);
                boolean wouldBeRed = (px % 3 == 0) && (py % 3 == 0);
                if (wouldBeGrey && wouldBeRed) {
                    assertThat(index)
                            .as("Kollisionspixel (%d,%d): Rot muss gewinnen", px, py)
                            .isEqualTo(2);
                }
            }
        }
    }

    /** Zellrealistische Rechtecke [x, y, w, h] mit w,h >= 4 und variablen Offsets. */
    @Provide
    Arbitrary<int[]> rects() {
        Arbitrary<Integer> offset = Arbitraries.integers().between(0, 60);
        Arbitrary<Integer> w = Arbitraries.integers().between(8, 60);
        Arbitrary<Integer> h = Arbitraries.integers().between(8, 60);
        return Combinators.combine(offset, offset, w, h)
                .as((xx, yy, ww, hh) -> new int[]{xx, yy, ww, hh});
    }
}
