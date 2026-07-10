package de.goForFun.familienkalender;

import java.awt.image.BufferedImage;

/**
 * Extrahiert aus einem 3-Farben-IndexColorModel-Bild (800x480) zwei separate Bitplanes
 * für das e-ink Display:
 * <ul>
 *   <li>Black-Plane: 1 Bit pro Pixel, Bit=0 wenn Pixel schwarz oder rot, Bit=1 wenn weiß</li>
 *   <li>Red-Plane: 1 Bit pro Pixel, Bit=0 wenn Pixel rot, Bit=1 sonst (GxEPD2 invertiert intern)</li>
 * </ul>
 * <p>
 * Ausgabeformat: [48000 Bytes Black-Plane][48000 Bytes Red-Plane] = 96000 Bytes total.
 * Jedes Byte enthält 8 Pixel, MSB zuerst (links → rechts), Zeilen von oben nach unten.
 * Das Bild wird um 180° gedreht exportiert (Bottom-to-Top, Right-to-Left Scan),
 * da GxEPD2 writeImage direkt in den Panel-RAM schreibt ohne Rotation.
 * <p>
 * Für das Waveshare 7.5" B/W/R Panel mit GxEPD2 writeImage gilt:
 * Black-Plane (Command 0x10): Bit=0 → schwarzes Pixel, Bit=1 → weiß
 * Red-Plane (Command 0x13): Eingabe wird mit ~data invertiert, also Bit=0 in Eingabe → rot
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
     * <p>
     * Für das GxEPD2 Waveshare 7.5" B/W/R Panel (Command 0x10/0x13):
     * Black-Plane: Bit=0 → schwarzes Pixel, Bit=1 → weiß
     * Red-Plane: Bit=0 → rotes Pixel, Bit=1 → kein Rot (GxEPD2 invertiert intern mit ~data)
     * <p>
     * Das Bild wird um 180° gedreht exportiert, da writeImage direkt in den Panel-RAM
     * schreibt und die GxEPD2-Rotation (setRotation) dabei nicht wirkt.
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

        // Initialize both planes to 0xFF:
        // Black plane: 1=white (default)
        // Red plane: 1=no red (default, GxEPD2 inverts with ~data before sending)
        for (int i = 0; i < PLANE_SIZE; i++) {
            blackPlane[i] = (byte) 0xFF;
            redPlane[i] = (byte) 0xFF;
        }

        int byteIndex = 0;
        int bitIndex = 7; // MSB first

        // Scan in 180°-rotated order (bottom-to-top, right-to-left)
        // so the image appears correctly on the physically mounted display
        for (int y = HEIGHT - 1; y >= 0; y--) {
            for (int x = WIDTH - 1; x >= 0; x--) {
                int pixelIndex = image.getRaster().getSample(x, y, 0);

                if (pixelIndex == INDEX_BLACK) {
                    // Black: clear bit in black plane (0 = black pixel)
                    blackPlane[byteIndex] &= (byte) ~(1 << bitIndex);
                } else if (pixelIndex == INDEX_RED) {
                    // Red: clear bit in red plane (0 = red pixel, GxEPD2 inverts to 1)
                    redPlane[byteIndex] &= (byte) ~(1 << bitIndex);
                    // Also clear bit in black plane for red pixels
                    blackPlane[byteIndex] &= (byte) ~(1 << bitIndex);
                }
                // INDEX_TRANSPARENT (0) → both planes stay 0xFF (white, no red)

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
