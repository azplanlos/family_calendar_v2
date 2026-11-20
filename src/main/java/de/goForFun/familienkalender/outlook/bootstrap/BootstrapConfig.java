package de.goForFun.familienkalender.outlook.bootstrap;

import java.util.Map;

/**
 * Konfiguration des Bootstrap-Programms. Programmargumente ({@code --client-id}, {@code --param}) haben
 * Vorrang vor den Umgebungsvariablen {@code GRAPH_CLIENT_ID} und {@code GRAPH_REFRESH_TOKEN_PARAM}.
 * Werte werden getrimmt; ist eine Angabe danach leer, wird eine {@link ConfigException} geworfen.
 */
public record BootstrapConfig(String clientId, String parameterName) {

    static final String ARG_CLIENT_ID = "--client-id";
    static final String ARG_PARAM = "--param";
    static final String ENV_CLIENT_ID = "GRAPH_CLIENT_ID";
    static final String ENV_PARAM = "GRAPH_REFRESH_TOKEN_PARAM";

    public static BootstrapConfig resolve(String[] args, Map<String, String> env) throws ConfigException {
        String clientId = pick(args, env, ARG_CLIENT_ID, ENV_CLIENT_ID);
        if (clientId.isEmpty()) {
            throw missing(ARG_CLIENT_ID, ENV_CLIENT_ID);
        }
        String param = pick(args, env, ARG_PARAM, ENV_PARAM);
        if (param.isEmpty()) {
            throw missing(ARG_PARAM, ENV_PARAM);
        }
        return new BootstrapConfig(clientId, param);
    }

    private static ConfigException missing(String argName, String envName) {
        return new ConfigException(argName,
                "Fehlende Angabe: " + argName + " (oder Umgebungsvariable " + envName + ")");
    }

    private static String pick(String[] args, Map<String, String> env, String argName, String envName) {
        String fromArg = fromArgs(args, argName);
        if (!fromArg.isEmpty()) {
            return fromArg;
        }
        return trim(env == null ? null : env.get(envName));
    }

    /** Liest {@code --name wert} oder {@code --name=wert}; bei Wiederholung zählt das letzte nicht leere Vorkommen. */
    private static String fromArgs(String[] args, String name) {
        String result = "";
        if (args == null) {
            return result;
        }
        String prefix = name + "=";
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a == null) {
                continue;
            }
            String value = null;
            if (a.equals(name)) {
                value = i + 1 < args.length ? args[i + 1] : null;
                i++;
            } else if (a.startsWith(prefix)) {
                value = a.substring(prefix.length());
            }
            String t = trim(value);
            if (!t.isEmpty()) {
                result = t;
            }
        }
        return result;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
