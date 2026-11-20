package de.goForFun.familienkalender.outlook.bootstrap;

import java.util.Locale;

/** Prüft, ob die Scope-Angabe der Token-Antwort {@code Calendars.Read} enthält. */
final class ScopeCheck {

    private static final String REQUIRED = "calendars.read";
    private static final String GRAPH_PREFIX = "https://graph.microsoft.com/";

    private ScopeCheck() {
    }

    /**
     * @param scopeResponse leerzeichengetrennte Scope-Liste (oder {@code null})
     * @return {@code true}, wenn ein Element nach Entfernen des optionalen Graph-Präfixes
     *     case-insensitiv {@code Calendars.Read} ist. {@code offline_access} wird nicht verlangt.
     */
    static boolean includesCalendarsRead(String scopeResponse) {
        if (scopeResponse == null) {
            return false;
        }
        for (String element : scopeResponse.trim().split("\\s+")) {
            String s = element.toLowerCase(Locale.ROOT);
            if (s.startsWith(GRAPH_PREFIX.toLowerCase(Locale.ROOT))) {
                s = s.substring(GRAPH_PREFIX.length());
            }
            if (s.equals(REQUIRED)) {
                return true;
            }
        }
        return false;
    }
}
