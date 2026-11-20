package de.goForFun.familienkalender.outlook;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Provide;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Geteilte jqwik-Generatoren des Features <em>outlook-graph-source</em> (analog
 * {@code CalendarArbitraries}). Testklassen erben von dieser Klasse und verwenden die
 * {@code @Provide}-Methoden per {@code @ForAll("graphEvents")} usw.; die statischen
 * {@code *Arbitrary()}-Varianten stehen für programmatische Nutzung zur Verfügung.
 *
 * <p>Die Event-JSONs folgen den realen Graph-Antworten (spike-findings.md): {@code start.dateTime}
 * mit sieben Nachkommastellen ohne Offset, {@code timeZone} als IANA-ID (daneben gezielt
 * {@code UTC}, Windows-Namen und fehlend), {@code originalStart} als ISO mit Offset oder
 * {@code null}, {@code type} {@code singleInstance}/{@code occurrence} (und Fremdwerte).
 */
public class GraphArbitraries {

    static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final DateTimeFormatter GRAPH_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSS");

    // ---------------------------------------------------------------- Wertepools

    static final List<String> ZONES = List.of(
            "Europe/Berlin", "Europe/Berlin", "UTC", "America/New_York", "Asia/Kolkata",
            "Australia/Sydney", "W. Europe Standard Time", "Romance Standard Time",
            "Eastern Standard Time", "Not/AZone");

    static final List<String> SUBJECTS = List.of(
            "Zahnarzt", "Meeting, Q1; Plan", "back\\slash", "Ünïcödé 日本語 😀", "",
            "  Rand  ", "Zeile1\nZeile2", "Schule: Elternabend", "A,B;C\\D");

    static final List<String> SENSITIVITIES = List.of(
            "normal", "personal", "private", "confidential", "Private", "PRIVATE",
            "Confidential", "NORMAL", " private ", "secret", "");

    static final List<String> SHOW_AS = List.of(
            "free", "tentative", "busy", "oof", "workingElsewhere", "unknown",
            "Free", "BUSY", "Tentative", " busy", "weird", "");

    static final List<String> TYPES = List.of(
            "singleInstance", "singleInstance", "occurrence", "occurrence", "exception", "seriesMaster", "other");

    static final List<List<String>> CATEGORY_LISTS = List.of(
            List.of(), List.of("Public"), List.of("public"), List.of("a,b", "x;y"),
            List.of("", " ", "Privat"), List.of("back\\slash"), List.of("Rot", "Public", "Blau"),
            List.of("Ünï 😀", "Zeile\nUmbruch"));

    /** Lokale Zeitpunkte rund um DST-Lücken/-Überlappungen und Jahresgrenzen (Europe/Berlin, New York). */
    static final List<LocalDateTime> SPECIAL_TIMES = List.of(
            LocalDateTime.of(2026, 3, 29, 2, 30),   // Berlin: Lücke
            LocalDateTime.of(2026, 10, 25, 2, 30),  // Berlin: Überlappung
            LocalDateTime.of(2026, 3, 8, 2, 30),    // New York: Lücke
            LocalDateTime.of(2026, 11, 1, 1, 30),   // New York: Überlappung
            LocalDateTime.of(2025, 12, 31, 23, 30), // Jahresgrenze
            LocalDateTime.of(2026, 1, 1, 0, 0),
            LocalDateTime.of(2027, 2, 28, 23, 59),
            LocalDateTime.of(2028, 2, 29, 12, 0));  // Schalttag

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0/me/calendarView";

    // ---------------------------------------------------------------- graphEvents

    @Provide
    public Arbitrary<JsonNode> graphEvents() {
        return graphEventArbitrary();
    }

    /** Liste von Events; mit Wahrscheinlichkeit wiederholt sie {@code iCalUId}s (doppelte UIDs). */
    @Provide
    public Arbitrary<List<JsonNode>> graphEventLists() {
        return graphEventListArbitrary(0, 8);
    }

    public static Arbitrary<JsonNode> graphEventArbitrary() {
        return Arbitraries.randomValue(GraphArbitraries::randomEvent);
    }

    public static Arbitrary<List<JsonNode>> graphEventListArbitrary(int min, int max) {
        return Arbitraries.randomValue(rnd -> {
            int n = min + rnd.nextInt(max - min + 1);
            List<JsonNode> events = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ObjectNode e = randomEvent(rnd);
                if (!events.isEmpty() && rnd.nextInt(5) == 0) {
                    // doppelte iCalUId (Serien-Occurrences teilen sie in der Realität)
                    JsonNode other = events.get(rnd.nextInt(events.size()));
                    if (other.hasNonNull("iCalUId")) {
                        e.put("iCalUId", other.get("iCalUId").asString());
                    }
                }
                events.add(e);
            }
            return events;
        });
    }

    /** Ein Graph-Event als {@link ObjectNode} (auch mit fehlenden Pflichtfeldern, selten). */
    static ObjectNode randomEvent(Random rnd) {
        ObjectNode e = MAPPER.createObjectNode();
        if (rnd.nextInt(20) != 0) {
            e.put("id", "AAMk" + randomToken(rnd, 20));
        }
        if (rnd.nextInt(20) != 0) {
            e.put("iCalUId", "040000008200E000" + randomToken(rnd, 24));
        }
        int subj = rnd.nextInt(SUBJECTS.size() + 3);
        if (subj < SUBJECTS.size()) {
            e.put("subject", SUBJECTS.get(subj));
        } else if (subj == SUBJECTS.size()) {
            e.putNull("subject");
        } // sonst: Feld fehlt
        putOptional(e, "sensitivity", SENSITIVITIES, rnd);
        putOptional(e, "showAs", SHOW_AS, rnd);

        List<String> cats = CATEGORY_LISTS.get(rnd.nextInt(CATEGORY_LISTS.size()));
        if (rnd.nextInt(10) == 0) {
            e.putNull("categories");
        } else if (rnd.nextInt(10) != 0) {
            ArrayNode arr = e.putArray("categories");
            cats.forEach(arr::add);
        }

        boolean allDay = rnd.nextInt(4) == 0;
        e.put("isAllDay", allDay);
        e.put("isCancelled", rnd.nextInt(10) == 0);
        String type = TYPES.get(rnd.nextInt(TYPES.size()));
        e.put("type", type);
        if ("occurrence".equals(type) || "exception".equals(type)) {
            e.put("originalStart", "2027-02-14T00:00:00+01:00");
        } else {
            e.putNull("originalStart");
        }

        // Zeiten
        String zone = ZONES.get(rnd.nextInt(ZONES.size()));
        boolean zoneMissing = rnd.nextInt(12) == 0;
        LocalDateTime start = randomLocal(rnd);
        LocalDateTime end;
        if (allDay) {
            start = start.toLocalDate().atStartOfDay();
            end = start.plusDays(1 + rnd.nextInt(3));
        } else {
            end = start.plus(Duration.ofMinutes(15L * (1 + rnd.nextInt(32))));
        }
        int startMode = rnd.nextInt(25);
        if (startMode == 0) {
            // Feld start fehlt
        } else if (startMode == 1) {
            e.putNull("start");
        } else {
            e.set("start", dateTime(start, zone, zoneMissing));
        }
        if (rnd.nextInt(25) != 0) {
            e.set("end", dateTime(end, zone, zoneMissing));
        }

        // Felder außerhalb des $select, die nie in der Ausgabe erscheinen dürfen
        if (rnd.nextInt(3) == 0) {
            e.put("bodyPreview", "Vertraulich " + randomToken(rnd, 6));
        }
        if (rnd.nextInt(3) == 0) {
            e.putObject("location").put("displayName", "Praxis " + randomToken(rnd, 4));
        }
        return e;
    }

    private static ObjectNode dateTime(LocalDateTime t, String zone, boolean zoneMissing) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("dateTime", GRAPH_DATE_TIME.format(t));
        if (!zoneMissing) {
            n.put("timeZone", zone);
        }
        return n;
    }

    private static LocalDateTime randomLocal(Random rnd) {
        if (rnd.nextInt(4) == 0) {
            return SPECIAL_TIMES.get(rnd.nextInt(SPECIAL_TIMES.size()));
        }
        return LocalDateTime.of(2024 + rnd.nextInt(5), 1 + rnd.nextInt(12), 1 + rnd.nextInt(28),
                rnd.nextInt(24), 15 * rnd.nextInt(4));
    }

    private static void putOptional(ObjectNode e, String field, List<String> values, Random rnd) {
        int i = rnd.nextInt(values.size() + 2);
        if (i < values.size()) {
            e.put(field, values.get(i));
        } else if (i == values.size()) {
            e.putNull(field);
        } // sonst: Feld fehlt
    }

    private static String randomToken(Random rnd, int len) {
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- graphPages

    /**
     * 1 bis 60 Seiten (je 0 bis 3 Events, leere Seiten erlaubt). Alle Seiten außer der letzten
     * tragen einen {@code @odata.nextLink} auf {@code graph.microsoft.com} mit {@code $skip}.
     */
    @Provide
    public Arbitrary<List<JsonNode>> graphPages() {
        return graphPagesArbitrary(1, 60);
    }

    public static Arbitrary<List<JsonNode>> graphPagesArbitrary(int minPages, int maxPages) {
        return Arbitraries.randomValue(rnd -> {
            int pages = minPages + rnd.nextInt(maxPages - minPages + 1);
            List<JsonNode> result = new ArrayList<>();
            for (int p = 0; p < pages; p++) {
                ObjectNode page = MAPPER.createObjectNode();
                ArrayNode value = page.putArray("value");
                int n = rnd.nextInt(4);
                for (int i = 0; i < n; i++) {
                    value.add(randomEvent(rnd));
                }
                if (p < pages - 1) {
                    page.put("@odata.nextLink", GRAPH_BASE
                            + "?startDateTime=2026-01-01T00:00:00Z&endDateTime=2026-12-31T00:00:00Z"
                            + "&$select=id,subject&$top=100&$skip=" + (p + 1) * 100);
                }
                result.add(page);
            }
            return result;
        });
    }

    // ---------------------------------------------------------------- uris

    /** URIs mit Schemata, Hosts (Angriffsmuster), Ports und Userinfo. */
    @Provide
    public Arbitrary<URI> uris() {
        return urisArbitrary();
    }

    public static Arbitrary<URI> urisArbitrary() {
        Arbitrary<String> scheme = Arbitraries.of("https", "https", "http", "HTTPS", "ftp");
        Arbitrary<String> host = Arbitraries.of(
                "graph.microsoft.com", "GRAPH.Microsoft.COM", "login.microsoftonline.com",
                "Login.MicrosoftOnline.com", "graph.microsoft.com.evil.example",
                "evil.example", "evilgraph.microsoft.com", "graph.microsoft.com.", "localhost",
                "login.microsoftonline.com.evil.example");
        Arbitrary<String> userInfo = Arbitraries.of("", "", "", "x", "graph.microsoft.com", "user:pw");
        Arbitrary<String> port = Arbitraries.of("", "", ":443", ":8443", ":80", ":0443");
        Arbitrary<String> path = Arbitraries.of("", "/", "/v1.0/me/calendarView", "/consumers/oauth2/v2.0/token");
        return Combinators.combine(scheme, userInfo, host, port, path).as((s, u, h, p, pa) ->
                URI.create(s + "://" + (u.isEmpty() ? "" : u + "@") + h + p + pa));
    }

    // ---------------------------------------------------------------- tokenResponses

    /** Antwort des Token-Endpoints (Status und Body). */
    public record TokenResponse(int status, String body) {
    }

    @Provide
    public Arbitrary<TokenResponse> tokenResponses() {
        return tokenResponsesArbitrary();
    }

    public static Arbitrary<TokenResponse> tokenResponsesArbitrary() {
        Arbitrary<String> token = Arbitraries.strings().alpha().numeric().ofMinLength(8).ofMaxLength(40);
        Arbitrary<TokenResponse> success = Combinators.combine(token, token).as((a, r) ->
                new TokenResponse(200, "{\"token_type\":\"Bearer\",\"scope\":\"Calendars.Read\","
                        + "\"expires_in\":3600,\"access_token\":\"" + a + "\",\"refresh_token\":\"" + r + "\"}"));
        Arbitrary<TokenResponse> successWithoutRefresh = token.map(a ->
                new TokenResponse(200, "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"" + a + "\"}"));
        Arbitrary<TokenResponse> successEmptyRefresh = token.map(a ->
                new TokenResponse(200, "{\"access_token\":\"" + a + "\",\"refresh_token\":\"\"}"));
        Arbitrary<TokenResponse> successWithoutAccess = Arbitraries.just(
                new TokenResponse(200, "{\"token_type\":\"Bearer\",\"refresh_token\":\"r\"}"));
        Arbitrary<TokenResponse> invalidGrant = Arbitraries.of(400, 401).map(s ->
                new TokenResponse(s, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70000: expired\"}"));
        Arbitrary<TokenResponse> otherError = Combinators.combine(
                Arbitraries.of(400, 401, 403, 429, 500, 502, 503),
                Arbitraries.of("invalid_request", "invalid_client", "temporarily_unavailable", "server_error"))
                .as((s, c) -> new TokenResponse(s, "{\"error\":\"" + c + "\"}"));
        Arbitrary<TokenResponse> garbage = Arbitraries.of(
                new TokenResponse(200, "not json"), new TokenResponse(200, ""),
                new TokenResponse(200, "[]"), new TokenResponse(500, "<html>oops</html>"),
                new TokenResponse(302, ""));
        return Arbitraries.oneOf(success, success, successWithoutRefresh, successEmptyRefresh,
                successWithoutAccess, invalidGrant, otherError, garbage);
    }

    // ---------------------------------------------------------------- ssmResults

    public enum SsmKind { VALUE, EMPTY, BLANK, MISSING, ERROR }

    /** Ergebnis des SSM-Lesens: Wert (nur bei {@link SsmKind#VALUE}) oder Fehlerart. */
    public record SsmResult(SsmKind kind, String value) {
        public boolean isUsable() {
            return kind == SsmKind.VALUE;
        }
    }

    @Provide
    public Arbitrary<SsmResult> ssmResults() {
        return ssmResultsArbitrary();
    }

    public static Arbitrary<SsmResult> ssmResultsArbitrary() {
        Arbitrary<SsmResult> value = Arbitraries.strings().alpha().numeric().withChars('.', '-', '_', '+', '/', '=', '&', '%')
                .ofMinLength(1).ofMaxLength(60).map(s -> new SsmResult(SsmKind.VALUE, s));
        return Arbitraries.oneOf(value, value, value,
                Arbitraries.just(new SsmResult(SsmKind.EMPTY, "")),
                Arbitraries.just(new SsmResult(SsmKind.BLANK, " \t ")),
                Arbitraries.just(new SsmResult(SsmKind.MISSING, null)),
                Arbitraries.just(new SsmResult(SsmKind.ERROR, null)));
    }

    // ---------------------------------------------------------------- deviceCodeScripts

    /**
     * Ablauf des Device-Code-Pollings: {@code pollErrors} sind die Fehlercodes der ersten Polls
     * ({@code authorization_pending} oder {@code slow_down}); danach folgt {@code terminal}
     * ({@code SUCCESS}, {@code authorization_declined}, {@code expired_token}, {@code bad_verification_code},
     * {@code NETWORK}). {@code interval} ist {@code null}, wenn die Antwort kein Intervall nennt.
     */
    public record DeviceCodeScript(List<String> pollErrors, String terminal, Integer interval, int expiresIn) {
    }

    @Provide
    public Arbitrary<DeviceCodeScript> deviceCodeScripts() {
        return deviceCodeScriptsArbitrary();
    }

    public static Arbitrary<DeviceCodeScript> deviceCodeScriptsArbitrary() {
        Arbitrary<List<String>> polls = Arbitraries.of("authorization_pending", "slow_down").list().ofMaxSize(6);
        Arbitrary<String> terminal = Arbitraries.of("SUCCESS", "SUCCESS", "authorization_declined",
                "expired_token", "bad_verification_code", "NETWORK");
        Arbitrary<Integer> interval = Arbitraries.of(null, 1, 5, 10);
        Arbitrary<Integer> expiresIn = Arbitraries.of(900, 600, 30);
        return Combinators.combine(polls, terminal, interval, expiresIn).as(DeviceCodeScript::new);
    }

    // ---------------------------------------------------------------- scopeStrings

    /** Leerzeichengetrennte Scope-Listen mit Groß-/Kleinschreibung, Fremd-Scopes und leeren Werten. */
    @Provide
    public Arbitrary<String> scopeStrings() {
        return scopeStringsArbitrary();
    }

    public static Arbitrary<String> scopeStringsArbitrary() {
        Arbitrary<String> scope = Arbitraries.of(
                "Calendars.Read", "calendars.read", "CALENDARS.READ", "offline_access", "openid", "profile",
                "User.Read", "Calendars.ReadWrite", "Calendars.Read.Shared", "Calendars.ReadBasic",
                "https://graph.microsoft.com/Calendars.Read");
        Arbitrary<String> separator = Arbitraries.of(" ", " ", "  ");
        return Combinators.combine(scope.list().ofMaxSize(5), separator).as((l, sep) -> String.join(sep, l));
    }

    // ---------------------------------------------------------------- canaryStrings

    /** Eindeutig erkennbare Strings, die in keiner Ausgabe vorkommen dürfen. */
    @Provide
    public Arbitrary<String> canaryStrings() {
        return canaryStringsArbitrary();
    }

    public static Arbitrary<String> canaryStringsArbitrary() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(12).ofMaxLength(32)
                .map(s -> "CANARY" + s + "ZZ");
    }
}
