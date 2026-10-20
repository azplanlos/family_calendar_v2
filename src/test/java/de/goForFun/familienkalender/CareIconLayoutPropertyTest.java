package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Rectangle;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-Test (jqwik) für das disjunkte Icon-Layout in der Tagesansicht
 * ({@link ImageRenderer#drawDayColumn}).
 *
 * <p>Die Geometrie von {@code drawDayColumn} ist privat, daher spiegelt dieser
 * Test die dort dokumentierten Layout-Konstanten wider und berechnet die drei
 * Bounding-Boxen (Icon, Überschrift, Event-Bereich) rein funktional. Ändern sich
 * die Konstanten in {@code ImageRenderer.drawDayColumn}
 * (ICON = 16, Offset = 4, DAY_EVENTS_Y_START = 60, Icon am rechten Spaltenrand),
 * MUSS dieser Test angepasst werden.
 *
 * <p>Das Icon sitzt am <b>rechten</b> Spaltenende auf Höhe der Überschriftzeile; die
 * rechtsbündige Überschrift wird in den um die Iconbreite (+ Lücke) verkürzten Bereich
 * gesetzt. Gespiegelte Konstanten:
 * <ul>
 *   <li>ICON = 16</li>
 *   <li>Icon-X = {@code x + width - ICON} (linke Icon-Kante am rechten Spaltenende)</li>
 *   <li>iconTop = {@code DAY_EVENTS_Y_START - 9} = 51 (vertikal mittig zur Überschriftzeile)</li>
 * </ul>
 *
 * <p>Abgeleitete Boxen für eine Spalte bei {@code x} mit Breite {@code width = 220}:
 * <ul>
 *   <li>Icon-Box:  {@code (x + width - ICON, 51, ICON, ICON) = (x + 204, 51, 16, 16)}</li>
 *   <li>Header-Box: rechtsbündiger Text in {@code (x, 65, width - (ICON + 4), 16)
 *                   = (x, 65, width - 20, 16)}, belegt also {@code [x, x + width - 20)}</li>
 *   <li>Event-Bereich: {@code (x, eventTop >= DAY_EVENTS_Y_START + 25, width, H) = (x, >= 85, width, H)}</li>
 * </ul>
 */
class CareIconLayoutPropertyTest {

    // Layout-Konstanten gespiegelt aus ImageRenderer.drawDayColumn:
    private static final int DAY_EVENTS_Y_START = 60;
    private static final int ICON = 16;
    private static final int ICON_OFFSET = 4; // Lücke zwischen Überschriftstext und Icon

    /** Icon-Box am rechten Spaltenende, auf Höhe der Überschriftzeile. */
    private static Rectangle iconBox(int x, int width) {
        return new Rectangle(x + width - ICON, DAY_EVENTS_Y_START - 9, ICON, ICON);
    }

    /** Rechtsbündiger Überschrifttext im um (ICON + Lücke) verkürzten Bereich links des Icons. */
    private static Rectangle headerBox(int x, int width) {
        int headerWidth = width - (ICON + ICON_OFFSET);
        return new Rectangle(x, DAY_EVENTS_Y_START + 5, headerWidth, 16);
    }

    private static Rectangle eventArea(int x, int width, int eventTop, int height) {
        return new Rectangle(x, eventTop, width, height);
    }

    // Feature: wechselmodell-kalender, Property 10: Icon-Box liegt am rechten Spaltenrand und ist disjunkt zu Überschrift- und Event-Bereich derselben Spalte
    // Validates: Requirements 5.8
    @Property(tries = 100)
    void iconBoxIsAtRightEndAndDisjointFromHeaderAndEventArea(@ForAll("columns") int[] col) {
        int x = col[0];
        int width = col[1];
        int eventTop = col[2];
        int eventHeight = col[3];

        Rectangle icon = iconBox(x, width);
        Rectangle header = headerBox(x, width);
        Rectangle events = eventArea(x, width, eventTop, eventHeight);

        // Icon liegt RECHTS vom Überschrifttext: linke Icon-Kante >= rechte Header-Kante
        // (mit ~4px Lücke: icon.x = x + width - 16, header rechte Kante = x + width - 20).
        assertThat(icon.x)
                .as("icon left edge (%d) must be at/right of header right edge (%d) with a gap",
                        icon.x, header.x + header.width)
                .isGreaterThanOrEqualTo(header.x + header.width);
        assertThat(icon.intersects(header))
                .as("icon box %s must be disjoint from header box %s", icon, header)
                .isFalse();

        // Icon vertikal oberhalb des Event-Bereichs: Icon-Unterkante (51+16=67) <= Event-Oberkante (>=85).
        assertThat(icon.y + icon.height)
                .as("icon bottom edge (%d) must not exceed event area top (%d)",
                        icon.y + icon.height, events.y)
                .isLessThanOrEqualTo(events.y);
        assertThat(icon.intersects(events))
                .as("icon box %s must be disjoint from event area %s", icon, events)
                .isFalse();
    }

    /**
     * Spalten [x, width, eventTop, eventHeight]:
     * <ul>
     *   <li>x in {TODAY_COL_X=10, TOMORROW_COL_X=240}, width=220 (feste Layout-Werte)</li>
     *   <li>eventTop >= DAY_EVENTS_Y_START + 25 = 85 (Events beginnen unterhalb der Header-Zone)</li>
     *   <li>eventHeight >= 1</li>
     * </ul>
     */
    @Provide
    Arbitrary<int[]> columns() {
        Arbitrary<Integer> x = Arbitraries.of(10, 240);
        Arbitrary<Integer> eventTop = Arbitraries.integers().between(DAY_EVENTS_Y_START + 25, 400);
        Arbitrary<Integer> eventHeight = Arbitraries.integers().between(1, 300);
        return Combinators.combine(x, eventTop, eventHeight)
                .as((xx, top, h) -> new int[]{xx, 220, top, h});
    }
}
