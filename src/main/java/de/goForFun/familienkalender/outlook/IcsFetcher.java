package de.goForFun.familienkalender.outlook;

import java.net.URI;
import net.fortuna.ical4j.model.Calendar;

/** Ruft einen ICS-Feed ab und parst ihn. */
@FunctionalInterface
public interface IcsFetcher {
    Calendar fetch(URI url) throws IcsFetchException;
}
