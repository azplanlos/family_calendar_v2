package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.Calendar;

/** Seam für die Graph-Quelle, damit der {@link OutlookSourceSelector} ohne Netzwerk testbar ist. */
@FunctionalInterface
public interface GraphCalendarSource {

    /** Vollständiger Calendar oder GraphException; nie Teilergebnisse. */
    Calendar fetch(QueryWindow window) throws GraphException;
}
