package de.goForFun.familienkalender.outlook;

/**
 * Feste Menge der Fehlerkategorien der Graph-Anbindung. {@link #displayName()} ist der einzige
 * Text, der in Protokollzeilen und Fehlerlisten erscheinen darf.
 */
public enum GraphErrorCategory {
    REFRESH_TOKEN_INVALID("Refresh_Token ungültig, Bootstrap erneut ausführen"),
    SSM_UNAVAILABLE("SSM-Lesefehler"),
    TOKEN_EXCHANGE_FAILED("Token-Tausch fehlgeschlagen"),
    NETWORK("Netzwerkfehler"),
    TIMEOUT("Timeout"),
    GRAPH_HTTP("Graph-HTTP-Status"),
    RESPONSE_UNPARSABLE("Antwort nicht parsebar"),
    PAGE_LIMIT("Seitenobergrenze überschritten"),
    BUDGET("Graph_Zeitbudget überschritten");

    private final String displayName;

    GraphErrorCategory(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
