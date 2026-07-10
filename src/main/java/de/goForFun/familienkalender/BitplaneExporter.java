package de.goForFun.familienkalender;

import java.awt.image.BufferedImage;

/**
 * Extrahiert aus einem 3-Farben-IndexColorModel-Bild (800x480) zwei separate Bitplanes
 * für das e-ink Display:
 * <ul>
 *   <li>Black-Plane: 1 Bit pro Pixel, gesetzt wenn Pixel-Index == 1 (schwarz)</li>
 *   <li>Red-Plane: 1 Bit pro Pixel, gesetzt wenn Pixel-Index == 2 (rot)</li>
 * </ul>
 * <p>
 * Ausgabeformat: [48000 Bytes Black-Plane][48000 Bytes Red-Plane] = 96000 Bytes total.
 * Jedes Byte enthält 8 Pixel, MSB zuerst (links → rechts), Zeilen von oben nach unten.
 * <p>
 * Für das Waveshare 7.5" B/W/R Panel gilt:
 * Black-Plane: Bit=1 → schwarzes Pixel, Bit=0 → weiß
 * Red-Plane: Bit=1 → rotes Pixel, Bit=0 → kein Rot
 */
public class BitplaneExporter {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 480;
    private static final int PLANE_SIZE = WIDTH * HEIGHT / 8; // 48000 bytes

    // IndexColorModel-Indizes (aus ImageRenderer)
    private static final int INDEX_TRANSPARENT = 0; // weiß auf dem Display
    private static final int INDEX_BLACK = 1;
    private static final int INDEX_RED = 2;

    /**
     * Konvertiert das gerenderte BufferedImage in das Raw-Bitplane-Format.
     *
     * @param image 800x480 BufferedImage mit IndexColorModel (2 Bit, 3 Farben)
     * @return byte[96000]: erste 48000 Bytes = Black-Plane, nächste 48000 = Red-Plane
     */
    public byte[] export(BufferedImage image) {
        if (image.getWidth() != WIDTH || image.getHeight() != HEIGHT) {
            throw new IllegalArgumentException(
                    String.format("Erwartete Bildgröße %dx%d, erhalten: %dx%d",
                            WIDTH, HEIGHT, image.getWidth(), image.getHeight()));
        }

        byte[] output = new byte[PLANE_SIZE * 2];
        byte[] blackPlane = new byte[PLANE_SIZE];
        byte[] redPlane = new byte[PLANE_SIZE];

        int byteIndex = 0;
        int bitIndex = 7; // MSB first

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                // getRGB() liefert bei IndexColorModel den gemappten RGB-Wert,
                // wir brauchen aber den Pixel-Index direkt aus dem Raster
                int pixelIndex = image.getRaster().getSample(x, y, 0);

                if (pixelIndex == INDEX_BLACK) {
                    blackPlane[byteIndex] |= (byte) (1 << bitIndex);
                } else if (pixelIndex == INDEX_RED) {
                    redPlane[byteIndex] |= (byte) (1 << bitIndex);
                }
                // INDEX_TRANSPARENT (0) → beide Planes bleiben 0 → weiß

                bitIndex--;
                if (bitIndex < 0) {
                    bitIndex = 7;
                    byteIndex++;
                }
            }
        }

        // Zusammensetzen: Black-Plane gefolgt von Red-Plane
        System.arraycopy(blackPlane, 0, output, 0, PLANE_SIZE);
        System.arraycopy(redPlane, 0, output, PLANE_SIZE, PLANE_SIZE);

        return output;
    }

    /**
     * @return Größe einer einzelnen Plane in Bytes (48000)
     */
    public static int getPlaneSize() {
        return PLANE_SIZE;
    }

    /**
     * @return Gesamtgröße des Ausgabeformats in Bytes (96000)
     */
    public static int getTotalSize() {
        return PLANE_SIZE * 2;
    }
}
