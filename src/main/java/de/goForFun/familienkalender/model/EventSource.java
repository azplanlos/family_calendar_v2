package de.goForFun.familienkalender.model;

/**
 * Herkunft eines Events – ermöglicht unterschiedliches Rendering je nach Quelle.
 */
public enum EventSource {
    /** Event aus einem iCal-Feed */
    CALENDAR,
    /** Gesetzlicher Feiertag */
    HOLIDAY
}
