package de.goForFun.familienkalender.outlook;

import java.util.regex.Pattern;

/**
 * Auswertung von {@code GRAPH_HORIZON_DAYS}.
 */
public final class HorizonDays {

    public static final int DEFAULT = 365;
    public static final int MIN = 1;
    public static final int MAX = 730;

    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,3}");

    private HorizonDays() {
    }

    /**
     * @param days           aufgelöste Anzahl Tage (1..730)
     * @param invalidWarning {@code true}, wenn der Wert gesetzt, aber ungültig war
     */
    public record Result(int days, boolean invalidWarning) {
    }

    /**
     * Akzeptiert nur ganzzahlige Dezimalwerte 1..730 (ohne Vorzeichen, Leerraum, Dezimalpunkt).
     * {@code null} und leer ergeben 365 ohne Warnung; jeder andere ungültige Wert ergibt 365 mit Warnung.
     */
    public static Result parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Result(DEFAULT, false);
        }
        if (DIGITS.matcher(raw).matches()) {
            int value = Integer.parseInt(raw);
            if (value >= MIN && value <= MAX) {
                return new Result(value, false);
            }
        }
        return new Result(DEFAULT, true);
    }
}
