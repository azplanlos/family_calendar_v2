package de.goForFun.familienkalender.outlook;

import java.net.URI;
import java.util.Locale;

/**
 * Konstanten der Microsoft-Endpunkte und Host-/Schema-Guard. Authorization-Header gehen nur an
 * {@link #isGraphUri(URI)}, der Refresh-Token nur an {@link #isLoginUri(URI)}.
 */
public final class GraphEndpoints {

    public static final String TOKEN_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    public static final String DEVICE_URL =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    public static final String CALENDAR_VIEW = "https://graph.microsoft.com/v1.0/me/calendarView";
    public static final String SCOPE = "Calendars.Read offline_access";

    private static final String GRAPH_HOST = "graph.microsoft.com";
    private static final String LOGIN_HOST = "login.microsoftonline.com";

    private GraphEndpoints() {
    }

    /** https, Host graph.microsoft.com (case-insensitiv), keine Userinfo, Port 443 oder nicht gesetzt. */
    public static boolean isGraphUri(URI u) {
        return matches(u, GRAPH_HOST);
    }

    /** https, Host login.microsoftonline.com (case-insensitiv), keine Userinfo, Port 443 oder nicht gesetzt. */
    public static boolean isLoginUri(URI u) {
        return matches(u, LOGIN_HOST);
    }

    private static boolean matches(URI u, String expectedHost) {
        if (u == null) {
            return false;
        }
        String scheme = u.getScheme();
        if (scheme == null || !"https".equals(scheme.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String host = u.getHost();
        if (host == null || !expectedHost.equals(host.toLowerCase(Locale.ROOT))) {
            return false;
        }
        if (u.getRawUserInfo() != null || u.getRawAuthority() == null) {
            return false;
        }
        int port = u.getPort();
        return port == -1 || port == 443;
    }
}
