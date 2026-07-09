package de.goForFun.familienkalender;

import org.apache.commons.lang3.StringUtils;

import java.awt.*;

public class FontHelper {

    public static Graphics drawString(Graphics g, String text, Aligment aligment, Font font, int size, Color color, int x, int y, int width, int height) {
        int xCalc = x;
        int yCalc = y;
        String textCalc = text;
        g.setColor(color);
        g.setFont(font.deriveFont(Integer.valueOf(size).floatValue()));
        int strWidth;
        do {
            strWidth = g.getFontMetrics().stringWidth(textCalc);
            if (strWidth > width) {
                int len = Math.min(Math.max(4, Math.round(((float) width / strWidth) * textCalc.length())), textCalc.length() - 1);
                textCalc = StringUtils.abbreviate(textCalc, len);
            }
        } while (strWidth > width && strWidth > 0);
        switch (aligment) {
            case RIGHT -> xCalc = x + width - strWidth;
            case CENTER -> xCalc = xCalc + ((width - strWidth) / 2);
        }
        g.drawString(textCalc, xCalc, yCalc);
        return g;
    }
}
