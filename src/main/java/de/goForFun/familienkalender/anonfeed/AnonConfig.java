package de.goForFun.familienkalender.anonfeed;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Unveränderlicher Konfigurations-Holder für den anonymisierenden iCal-Feed.
 *
 * <p>Kapselt das Einlesen der {@code ANON_*}-Umgebungsvariablen samt der zugehörigen
 * Validierungs- und Default-Regeln (Requirements 1.3, 1.4, 2.6, 4.1, 4.2, 4.5, 7.1, 7.3).
 *
 * @param sourceFeed validierte http/https-URL aus {@code ANON_SOURCE_FEED}; {@code null}
 *                   wenn fehlend/leer/ungültig (führt beim Handler auf den 500-Pfad)
 * @param placeholder bereinigter Anonymisierter_Platzhalter aus {@code ANON_PLACEHOLDER};
 *                    Default {@code "Privat"}
 * @param calName getrimmter Participant aus {@code ANON_PARTICIPANT}; nur gesetzt bei Länge 1..200
 * @param apiSecret getrimmtes Geheimnis aus {@code ANON_API_SECRET}; nur gesetzt bei Länge ≥ 1
 */
public record AnonConfig(
        URI sourceFeed,
        String placeholder,
        Optional<String> calName,
        Optional<String> apiSecret
) {

    private static final Logger LOG = Logger.getLogger(AnonConfig.class.getName());

    /** Vorgabewert für den Anonymisierten_Platzhalter (Requirement 2.6). */
    static final String DEFAULT_PLACEHOLDER = "Privat";

    /** Maximale Länge des Platzhalters; darüber greift der Default (Requirement 2.6). */
    static final int MAX_PLACEHOLDER_LENGTH = 255;

    /** Maximale getrimmte Länge des Participant für {@code X-WR-CALNAME} (Requirement 4.2/4.5). */
    static final int MAX_CALNAME_LENGTH = 200;

    /**
     * Liest die Konfiguration aus den Prozess-Umgebungsvariablen via {@link System#getenv(String)}.
     */
    static AnonConfig fromEnv() {
        Map<String, String> env = new HashMap<>();
        putIfPresent(env, "ANON_SOURCE_FEED");
        putIfPresent(env, "ANON_PLACEHOLDER");
        putIfPresent(env, "ANON_PARTICIPANT");
        putIfPresent(env, "ANON_API_SECRET");
        return fromMap(env);
    }

    private static void putIfPresent(Map<String, String> env, String key) {
        String value = System.getenv(key);
        if (value != null) {
            env.put(key, value);
        }
    }

    /**
     * Prüft beim Start, ob die erforderlichen {@code ANON_*}-Umgebungsvariablen vorhanden sind,
     * und benennt im Fehlerfall die betroffene Variable (Requirement 8.3).
     *
     * <p><b>R7.3/R8.3-Auslegung (Design: Error Handling / Startup-Validierung):</b> Requirement 8.3
     * verlangt einen Init-Abbruch bei fehlender/leerer Pflicht-ENV; Requirement 7.3 (und 4.4)
     * definieren ein leeres {@code ANON_API_SECRET} bzw. ein fehlendes {@code ANON_PARTICIPANT}
     * jedoch ausdrücklich als gültigen Zustand ("offener Zugriff" bzw. "kein X-WR-CALNAME"). Um die
     * Kollision aufzulösen, werden {@code ANON_API_SECRET} und {@code ANON_PARTICIPANT} als
     * "gesetzt, dürfen aber leer sein" behandelt – ihr Fehlen/Leersein ist kein harter Fehler.
     * Harte Pflicht ist allein die inhaltliche Gültigkeit von {@code ANON_SOURCE_FEED}: fehlt sie,
     * ist leer oder keine gültige http(s)-URL, bricht die Initialisierung mit einer Fehlermeldung
     * ab, die {@code ANON_SOURCE_FEED} benennt. {@code ANON_PLACEHOLDER} besitzt einen Default
     * ({@code "Privat"}) und ist daher ebenfalls nicht hart erforderlich.
     *
     * <p>Diese Validierung verändert das bestehende Laufzeitverhalten des Handlers nicht; sie stellt
     * lediglich einen expliziten, variablenbenennenden Init-Pfad für Requirement 8.3 bereit.
     *
     * @throws IllegalStateException wenn eine erforderliche Variable fehlt/ungültig ist; die Meldung
     *                               benennt die betroffene Variable
     */
    void validateRequired() {
        if (sourceFeed == null) {
            throw new IllegalStateException(
                    "Pflicht-Umgebungsvariable ANON_SOURCE_FEED fehlt, ist leer oder keine gültige "
                            + "http(s)-URL");
        }
    }

    /**
     * Baut die Konfiguration aus einer bereitgestellten Map (testbar).
     *
     * @param env Map der {@code ANON_*}-Variablen; fehlende Schlüssel werden wie {@code null} behandelt
     */
    static AnonConfig fromMap(Map<String, String> env) {
        URI sourceFeed = parseHttpUri(env.get("ANON_SOURCE_FEED"));
        String placeholder = resolvePlaceholder(env.get("ANON_PLACEHOLDER"));
        Optional<String> calName = resolveCalName(env.get("ANON_PARTICIPANT"));
        Optional<String> apiSecret = resolveApiSecret(env.get("ANON_API_SECRET"));
        return new AnonConfig(sourceFeed, placeholder, calName, apiSecret);
    }

    /**
     * {@code ANON_PLACEHOLDER}: fehlt/leer/>255 Zeichen → Default {@code "Privat"} (Requirement 2.6).
     */
    private static String resolvePlaceholder(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_PLACEHOLDER_LENGTH) {
            return DEFAULT_PLACEHOLDER;
        }
        return raw;
    }

    /**
     * {@code ANON_PARTICIPANT}: getrimmt; nur gesetzt bei Länge 1..200 (Requirement 4.1/4.2/4.5).
     * Bei "leer nach Trim" bzw. ">200" wird der Grund der Nichtübernahme protokolliert (Requirement 4.5).
     */
    private static Optional<String> resolveCalName(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            LOG.info("ANON_PARTICIPANT leer nach Trim – X-WR-CALNAME wird nicht gesetzt");
            return Optional.empty();
        }
        if (trimmed.length() > MAX_CALNAME_LENGTH) {
            LOG.info("ANON_PARTICIPANT länger als " + MAX_CALNAME_LENGTH
                    + " Zeichen – X-WR-CALNAME wird nicht gesetzt");
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    /**
     * {@code ANON_API_SECRET}: getrimmt; nur gesetzt bei Länge ≥ 1 (Requirement 7.1/7.3).
     */
    private static Optional<String> resolveApiSecret(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? Optional.empty() : Optional.of(trimmed);
    }

    /**
     * Validiert eine Feed-URL syntaktisch (Requirement 1.3/1.4).
     *
     * <p>Identische Logik wie {@code CreateKalenderImage.parseHttpUri}: gültig nur als nicht-leere,
     * syntaktisch gültige http/https-URL mit nicht-leerem Host; andernfalls {@code null}. Wirft nie.
     *
     * @param s roher URL-String aus {@code ANON_SOURCE_FEED} (darf null sein)
     */
    private static URI parseHttpUri(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            URI u = URI.create(s.trim());
            String scheme = u.getScheme();
            if (scheme == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https"))
                    && u.getHost() != null && !u.getHost().isBlank()) {
                return u;
            }
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
