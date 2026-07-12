package de.goForFun.familienkalender.model;

/**
 * Wetterdaten für einen einzelnen Tag (von OpenWeatherMap).
 *
 * @param iconCode    OpenWeatherMap Icon-Code (z.B. "01d", "10d", "13d")
 * @param minTemp     Minimale Tagestemperatur in °C
 * @param maxTemp     Maximale Tagestemperatur in °C
 */
public record WeatherDay(
        String iconCode,
        int minTemp,
        int maxTemp
) {
    /**
     * Formatierte Temperaturanzeige für das Rendering (z.B. "-4 / 2").
     */
    public String temperatureDisplay() {
        return minTemp + " / " + maxTemp;
    }
}
