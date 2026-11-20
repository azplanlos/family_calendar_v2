package de.goForFun.familienkalender.outlook;

import java.util.Map;

/**
 * Konfiguration der Microsoft-Graph-Anbindung (Requirements 1.3, 1.7, 1.9).
 *
 * <p>Werte werden getrimmt gespeichert, {@code null} wird zu {@code ""}.
 * Die Konfiguration wird je Aufruf frisch aus der Umgebung gelesen.
 *
 * @param clientId          Wert von {@code GRAPH_CLIENT_ID}
 * @param refreshTokenParam Wert von {@code GRAPH_REFRESH_TOKEN_PARAM}
 */
public record GraphConfig(String clientId, String refreshTokenParam) {

    public static final String ENV_CLIENT_ID = "GRAPH_CLIENT_ID";
    public static final String ENV_REFRESH_TOKEN_PARAM = "GRAPH_REFRESH_TOKEN_PARAM";

    /** Zustand der Graph-Konfiguration. */
    public enum State { CONFIGURED, NOT_CONFIGURED, PARTIAL }

    public GraphConfig {
        clientId = clientId == null ? "" : clientId.trim();
        refreshTokenParam = refreshTokenParam == null ? "" : refreshTokenParam.trim();
    }

    /** Liest die aktuellen Werte aus {@link System#getenv()}. */
    public static GraphConfig fromEnv() {
        return fromMap(System.getenv());
    }

    /** Liest die Werte aus der übergebenen Map (testbar). */
    public static GraphConfig fromMap(Map<String, String> env) {
        if (env == null) {
            return new GraphConfig(null, null);
        }
        return new GraphConfig(env.get(ENV_CLIENT_ID), env.get(ENV_REFRESH_TOKEN_PARAM));
    }

    /** {@code CONFIGURED} nur, wenn beide Werte nicht leer sind; {@code PARTIAL}, wenn genau einer leer ist. */
    public State state() {
        boolean hasClient = !clientId.isEmpty();
        boolean hasParam = !refreshTokenParam.isEmpty();
        if (hasClient && hasParam) {
            return State.CONFIGURED;
        }
        if (!hasClient && !hasParam) {
            return State.NOT_CONFIGURED;
        }
        return State.PARTIAL;
    }

    /**
     * Name der fehlenden Variable; nur bei {@link State#PARTIAL} sinnvoll, sonst {@code null}.
     * Enthält nie einen Variablenwert.
     */
    public String missingVariable() {
        if (state() != State.PARTIAL) {
            return null;
        }
        return clientId.isEmpty() ? ENV_CLIENT_ID : ENV_REFRESH_TOKEN_PARAM;
    }
}
