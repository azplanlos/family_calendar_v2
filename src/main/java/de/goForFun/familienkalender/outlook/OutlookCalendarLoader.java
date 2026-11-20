package de.goForFun.familienkalender.outlook;

/** Lädt den Outlook-Kalender für ein Zeitfenster aus der passenden Quelle. */
@FunctionalInterface
public interface OutlookCalendarLoader {
    OutlookLoad load(QueryWindow window);
}
