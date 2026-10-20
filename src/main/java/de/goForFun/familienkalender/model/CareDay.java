package de.goForFun.familienkalender.model;

import java.time.LocalDate;

/**
 * Betreuungsinformation für genau einen Kalendertag.
 *
 * <p>Normaler Tag: {@code switchDay == false}, {@code fullDay != null},
 * {@code firstHalf == secondHalf == fullDay}.
 * <p>Wechseltag: {@code switchDay == true}, {@code fullDay == null},
 * {@code firstHalf} = Partei des endenden Blocks,
 * {@code secondHalf} = Partei des beginnenden Folgeblocks.
 *
 * @param date       der Kalendertag
 * @param fullDay    Ganztägige_Partei; {@code null} an Wechseltagen
 * @param firstHalf  Partei der ersten Tageshälfte
 * @param secondHalf Partei der zweiten Tageshälfte
 * @param switchDay  {@code true}, wenn der Tag ein Wechseltag ist
 */
public record CareDay(
        LocalDate date,
        CareParty fullDay,     // null an Wechseltagen
        CareParty firstHalf,   // erste Tageshälfte
        CareParty secondHalf,  // zweite Tageshälfte
        boolean switchDay
) {
    /**
     * Erzeugt einen Ganztag: beide Hälften gehören derselben Partei, kein Wechseltag.
     */
    public static CareDay fullDay(LocalDate date, CareParty party) {
        return new CareDay(date, party, party, party, false);
    }

    /**
     * Erzeugt einen Wechseltag: erste und zweite Tageshälfte gehören
     * unterschiedlichen Parteien; {@code fullDay} ist {@code null}.
     */
    public static CareDay switchDay(LocalDate date, CareParty first, CareParty second) {
        return new CareDay(date, null, first, second, true);
    }

    /**
     * Für das Betreuungs-Icon maßgebliche Partei (R5.3/R5.4):
     * bei einem Wechseltag die zweite Tageshälfte, sonst die Ganztägige_Partei.
     */
    public CareParty iconParty() {
        return switchDay ? secondHalf : fullDay;
    }
}
