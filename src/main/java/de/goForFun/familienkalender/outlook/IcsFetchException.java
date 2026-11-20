package de.goForFun.familienkalender.outlook;

/** ICS-Abruf fehlgeschlagen; die Meldung ist eine sichere Kurzmeldung (ohne URL/Secrets). */
public final class IcsFetchException extends Exception {

    private static final long serialVersionUID = 1L;

    public IcsFetchException(String message) {
        super(message);
    }

    public IcsFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
