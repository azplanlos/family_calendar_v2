package de.goForFun.familienkalender.outlook;

import java.time.Duration;

/** Zugriff auf den im SSM Parameter Store abgelegten Refresh_Token. */
public interface RefreshTokenStore {

    /**
     * @return Klartext des Parameters (nie leer)
     * @throws GraphException {@link GraphErrorCategory#SSM_UNAVAILABLE}, wenn der Parameter fehlt,
     *     leer oder nicht lesbar ist
     */
    String read(String parameterName, Duration timeout) throws GraphException;

    /**
     * Schreibt den Wert als SecureString und überschreibt einen vorhandenen Wert.
     *
     * @throws GraphException {@link GraphErrorCategory#SSM_UNAVAILABLE} bei jedem Fehler
     */
    void write(String parameterName, String value, Duration timeout) throws GraphException;
}
