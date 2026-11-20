package de.goForFun.familienkalender.outlook.bootstrap;

/**
 * Abbruch des Bootstrap-Ablaufs. Die Meldung enthält nur Kategorie, Status und Fehlercode, nie
 * Token oder Antwort-Bodys.
 */
final class BootstrapException extends Exception {

    private static final long serialVersionUID = 1L;

    BootstrapException(String safeMessage) {
        super(safeMessage, null, false, false);
    }
}
