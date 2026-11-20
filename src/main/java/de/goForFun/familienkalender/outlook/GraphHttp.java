package de.goForFun.familienkalender.outlook;

import java.time.Duration;

/** HTTP-Transport der Graph-Anbindung (austauschbar für Tests). */
public interface GraphHttp {

    /**
     * Sendet den Aufruf und liefert Status und Body (auch bei Nicht-2xx-Status).
     *
     * @throws GraphException Kategorie {@code NETWORK} oder {@code TIMEOUT}
     */
    HttpResult send(HttpCall call, Duration timeout) throws GraphException;
}
