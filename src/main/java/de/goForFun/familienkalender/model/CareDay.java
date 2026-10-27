package de.goForFun.familienkalender.model;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Betreuungsinformation für genau einen Kalendertag.
 *
 * <p>Normaler Tag: {@code switchDay == false}, {@code fullDay != null},
 * {@code firstHalf == secondHalf == fullDay}, {@code switchTime == null}.
 * <p>Wechseltag: {@code switchDay == true}, {@code fullDay == null},
 * {@code firstHalf} = abgebende Partei (vor der Übergabe),
 * {@code secondHalf} = übernehmende Partei (nach der Übergabe),
 * {@code switchTime} = Uhrzeit der Übergabe (aus dem getimten „Wechsel zu …"-Event).
 *
 * @param date       der Kalendertag
 * @param fullDay    Ganztägige_Partei; {@code null} an Wechseltagen
 * @param firstHalf  Partei der ersten Tageshälfte (vor der Übergabe)
 * @param secondHalf Partei der zweiten Tageshälfte (nach der Übergabe)
 * @param switchDay  {@code true}, wenn der Tag ein Wechseltag ist
 * @param switchTime Uhrzeit der Übergabe an Wechseltagen; {@code null} an normalen Tagen
 */
public record CareDay(
        LocalDate date,
        CareParty fullDay,     // null an Wechseltagen
        CareParty firstHalf,   // erste Tageshälfte (vor der Übergabe)
        CareParty secondHalf,  // zweite Tageshälfte (nach der Übergabe)
        boolean switchDay,
        LocalTime switchTime   // Übergabezeit am Wechseltag; null an normalen Tagen
) {
    /**
     * Erzeugt einen Ganztag: beide Hälften gehören derselben Partei, kein Wechseltag.
     */
    public static CareDay fullDay(LocalDate date, CareParty party) {
        return new CareDay(date, party, party, party, false, null);
    }

    /**
     * Erzeugt einen Wechseltag mit Übergabezeit: erste und zweite Tageshälfte gehören
     * unterschiedlichen Parteien; {@code fullDay} ist {@code null}.
     *
     * @param switchTime Uhrzeit der Übergabe (z. B. 16:00); darf {@code null} sein, falls
     *                   keine Zeit bekannt ist.
     */
    public static CareDay switchDay(LocalDate date, CareParty first, CareParty second, LocalTime switchTime) {
        return new CareDay(date, null, first, second, true, switchTime);
    }

    /**
     * Für das Betreuungs-Icon maßgebliche Partei (R5.3/R5.4):
     * bei einem Wechseltag die zweite Tageshälfte, sonst die Ganztägige_Partei.
     */
    public CareParty iconParty() {
        return switchDay ? secondHalf : fullDay;
    }
}
