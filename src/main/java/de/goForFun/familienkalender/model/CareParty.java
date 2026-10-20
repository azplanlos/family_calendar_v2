package de.goForFun.familienkalender.model;

/**
 * Betreuende Partei im Wechselmodell – einer von genau zwei Werten.
 *
 * <p>Ein Tag ohne Betreuungs-Event hat keine Betreuende_Partei (kein Enum-Wert,
 * sondern das Fehlen eines Schedule-Eintrags).
 */
public enum CareParty {
    /** Der Mann (identifiziert über die konfigurierbare Mann_Kennung im Event-Titel). */
    MANN,
    /** Die Frau (Default, wenn der Event-Titel die Mann_Kennung nicht enthält). */
    FRAU
}
