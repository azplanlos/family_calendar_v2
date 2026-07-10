package de.goForFun.familienkalender.model;

/**
 * Wetterdaten für einen einzelnen Tag.
 *
 * @param icon  Symbol/Icon als Text (z.B. Unicode-Zeichen)
 * @param temperature Temperaturanzeige (z.B. "-4 / -1")
 */
public record WeatherDay(
        String icon,
        String temperature
) {
}
