package de.goForFun.familienkalender;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Zentrale Zeitzone der Anwendung.
 * <p>
 * Wird über die Umgebungsvariable {@code TIMEZONE} (IANA-ID, z. B. {@code Europe/Berlin})
 * konfiguriert. Fehlt sie oder ist sie ungültig, gilt {@value #DEFAULT_ZONE_ID}.
 * Auf AWS Lambda ist die JVM-Zeitzone sonst UTC; deshalb darf nicht auf
 * {@link ZoneId#systemDefault()} zurückgegriffen werden.
 */
public final class AppTimeZone {

    public static final String ENV_NAME = "TIMEZONE";
    public static final String DEFAULT_ZONE_ID = "Europe/Berlin";

    private AppTimeZone() {
    }

    /** Liefert die konfigurierte Zeitzone (aus der Umgebung gelesen). */
    public static ZoneId get() {
        return resolve(System.getenv(ENV_NAME));
    }

    /** Wandelt eine Zeitzonen-ID in eine {@link ZoneId}; null/leer/ungültig => Default. */
    static ZoneId resolve(String value) {
        if (value != null && !value.isBlank()) {
            try {
                return ZoneId.of(value.trim());
            } catch (DateTimeException e) {
                // ungültige ID => Default
            }
        }
        return ZoneId.of(DEFAULT_ZONE_ID);
    }
}
