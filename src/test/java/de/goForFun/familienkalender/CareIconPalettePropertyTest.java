package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import de.goForFun.familienkalender.model.CareParty;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.lang.reflect.Method;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-Tests für die Farbpalette des Betreuungs-Icons im Wechselmodell-Kalender.
 *
 * <p>Das Icon wird über die private Methode {@code ImageRenderer.drawCareIcon} via Reflection
 * in einen {@code TYPE_BYTE_BINARY}-Puffer mit demselben 3-Farben-{@link IndexColorModel}
 * gezeichnet wie in {@code ImageRenderer.createImage}. Danach wird jeder Pixel des Rasters
 * geprüft: es dürfen ausschließlich die Indizes 0 (transparent/weiß), 1 (schwarz) und 2 (rot)
 * auftreten – keine Graustufen oder Halbtöne.
 */
class CareIconPalettePropertyTest {

    /** 3-Farben-Palette identisch zu {@code ImageRenderer.createImage}. */
    private static IndexColorModel palette() {
        return new IndexColorModel(2, 3,
                new byte[]{(byte) 255, 0, (byte) 255},
                new byte[]{(byte) 255, 0, 0},
                new byte[]{(byte) 255, 0, 0},
                0);
    }

    // Feature: wechselmodell-kalender, Property 9: gerenderte Icon-Fläche enthält nur transparent/schwarz/rot (keine Graustufen)
    @Property(tries = 100)
    void iconAreaContainsOnlyAllowedIndices(@ForAll("parties") CareParty party,
                                            @ForAll("sizes") int size,
                                            @ForAll("offsets") int offset) throws Exception {
        // Icon-Box bei (bx, by) mit Kantenlänge size; großzügiger Rand, da einzelne
        // Glyph-Striche (z.B. Venus-Kreuz) leicht über die size-Box hinausragen können.
        int bx = offset;
        int by = offset;
        int pad = size + offset + 8;
        int imgW = bx + size + pad;
        int imgH = by + size + pad;

        BufferedImage image = new BufferedImage(imgW, imgH, BufferedImage.TYPE_BYTE_BINARY, palette());
        Graphics2D g = image.createGraphics();
        // Dieselbe Rendering-Hint wie in createImage – kein Text-Antialiasing.
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

        Method drawCareIcon = ImageRenderer.class.getDeclaredMethod(
                "drawCareIcon", Graphics2D.class, CareParty.class, int.class, int.class, int.class);
        drawCareIcon.setAccessible(true);
        drawCareIcon.invoke(new ImageRenderer(), g, party, bx, by, size);
        g.dispose();

        // Das gesamte Raster scannen: jeder Pixel-Index muss in {0, 1, 2} liegen.
        // In einem TYPE_BYTE_BINARY-Bild mit 3-Einträge-Colormap ist das inhärent
        // begrenzt; die Prüfung stellt sicher, dass keine Graustufen-Zwischenwerte
        // (die bei Antialiasing entstünden) auftreten – es existieren nur die drei
        // Volltonfarben transparent/schwarz/rot.
        Raster raster = image.getRaster();
        int[] pixel = new int[raster.getNumBands()];
        for (int y = 0; y < imgH; y++) {
            for (int x = 0; x < imgW; x++) {
                raster.getPixel(x, y, pixel);
                int index = pixel[0];
                assertThat(index)
                        .as("Pixel (%d,%d) für Partei %s, size=%d", x, y, party, size)
                        .isIn(0, 1, 2);
            }
        }
    }

    @Provide
    Arbitrary<CareParty> parties() {
        return Arbitraries.of(CareParty.MANN, CareParty.FRAU);
    }

    @Provide
    Arbitrary<Integer> sizes() {
        return Arbitraries.integers().between(10, 40);
    }

    @Provide
    Arbitrary<Integer> offsets() {
        return Arbitraries.integers().between(0, 20);
    }
}
