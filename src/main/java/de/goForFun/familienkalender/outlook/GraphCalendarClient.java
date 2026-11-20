package de.goForFun.familienkalender.outlook;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Liest die calendarView seitenweise (Paging über {@code @odata.nextLink}). Alles oder nichts:
 * Bei jedem Fehler wird eine {@link GraphException} geworfen und kein Teilergebnis geliefert.
 * Kein Retry; Fehlertexte enthalten weder Body noch Token.
 */
final class GraphCalendarClient {

    static final int MAX_PAGES = 50;
    static final int TOP = 100;
    static final String SELECT = "id,iCalUId,subject,start,end,isAllDay,isCancelled,sensitivity,"
            + "categories,showAs,location,bodyPreview,type,originalStart";

    private static final Duration STEP_CAP = Duration.ofSeconds(10);
    private static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final GraphHttp http;
    private final ZoneId zone;

    GraphCalendarClient(GraphHttp http, ZoneId zone) {
        this.http = Objects.requireNonNull(http, "http");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    static URI initialUri(QueryWindow window) {
        String start = UTC_FORMAT.format(window.start().atOffset(ZoneOffset.UTC));
        String end = UTC_FORMAT.format(window.end().atOffset(ZoneOffset.UTC));
        return URI.create(GraphEndpoints.CALENDAR_VIEW
                + "?startDateTime=" + start
                + "&endDateTime=" + end
                + "&$select=" + SELECT
                + "&$top=" + TOP);
    }

    List<JsonNode> fetchAll(QueryWindow window, String accessToken, TimeBudget budget)
            throws GraphException {
        Objects.requireNonNull(accessToken, "accessToken");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Prefer", "outlook.timezone=\"" + zone.getId() + "\"");
        headers.put("Accept", "application/json");

        List<JsonNode> events = new ArrayList<>();
        URI uri = initialUri(window);
        int pages = 0;
        while (true) {
            if (!GraphEndpoints.isGraphUri(uri)) {
                throw new GraphException(GraphErrorCategory.RESPONSE_UNPARSABLE);
            }
            Duration timeout = budget.stepTimeout(STEP_CAP);
            HttpResult result = http.send(new HttpCall("GET", uri, headers, null), timeout);
            pages++;
            if (result.status() < 200 || result.status() > 299) {
                throw new GraphException(GraphErrorCategory.GRAPH_HTTP, result.status(),
                        errorCode(result.body()));
            }
            JsonNode root = parse(result.body());
            JsonNode value = root == null ? null : root.get("value");
            if (value == null || !value.isArray()) {
                throw new GraphException(GraphErrorCategory.RESPONSE_UNPARSABLE);
            }
            for (JsonNode event : value) {
                events.add(event);
            }
            JsonNode next = root.get("@odata.nextLink");
            if (next == null || next.isNull()) {
                return events;
            }
            if (!next.isString() || next.asString().isEmpty()) {
                throw new GraphException(GraphErrorCategory.RESPONSE_UNPARSABLE);
            }
            URI nextUri;
            try {
                nextUri = new URI(next.asString());
            } catch (java.net.URISyntaxException e) {
                throw new GraphException(GraphErrorCategory.RESPONSE_UNPARSABLE);
            }
            if (!GraphEndpoints.isGraphUri(nextUri)) {
                throw new GraphException(GraphErrorCategory.RESPONSE_UNPARSABLE);
            }
            if (pages >= MAX_PAGES) {
                throw new GraphException(GraphErrorCategory.PAGE_LIMIT);
            }
            uri = nextUri;
        }
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            return root != null && root.isObject() ? root : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Roher {@code error.code}; die Allowlist-Prüfung übernimmt {@link GraphException}. */
    private static String errorCode(String body) {
        JsonNode root = parse(body);
        if (root == null) {
            return null;
        }
        JsonNode code = root.path("error").path("code");
        return code.isString() ? code.asString() : null;
    }
}
