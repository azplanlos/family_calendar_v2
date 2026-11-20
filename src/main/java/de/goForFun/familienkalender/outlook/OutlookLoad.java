package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.Calendar;

/** Ergebnis der Quellenauswahl für den Outlook-Kalender. */
public sealed interface OutlookLoad {

    /** Herkunft eines geladenen Kalenders. */
    enum Origin { GRAPH, ICS }

    /** Kalender wurde erfolgreich geladen. */
    record Loaded(Calendar calendar, Origin origin) implements OutlookLoad { }

    /** Weder Graph noch eine gültige ICS-URL ist konfiguriert. */
    record NotConfigured() implements OutlookLoad { }

    /** Quelle nicht verfügbar; {@code detail} ist eine sichere Kurzmeldung. */
    record Unavailable(String detail) implements OutlookLoad { }
}
