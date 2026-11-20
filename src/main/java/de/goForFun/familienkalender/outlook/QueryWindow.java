package de.goForFun.familienkalender.outlook;

import java.time.Instant;

/**
 * Zeitfenster einer calendarView-Abfrage (Beginn inklusive, Ende exklusive).
 */
public record QueryWindow(Instant start, Instant end) {
}
