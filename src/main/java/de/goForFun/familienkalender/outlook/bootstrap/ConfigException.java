package de.goForFun.familienkalender.outlook.bootstrap;

/**
 * Fehlende oder leere Konfigurationsangabe des Bootstrap-Programms. Die Meldung nennt den Namen der
 * Angabe (Argument und Umgebungsvariable), enthält aber nie Werte.
 */
public final class ConfigException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String missingName;

    public ConfigException(String missingName, String message) {
        super(message);
        this.missingName = missingName;
    }

    /** Name der fehlenden Angabe, z. B. {@code --client-id}. */
    public String missingName() {
        return missingName;
    }
}
