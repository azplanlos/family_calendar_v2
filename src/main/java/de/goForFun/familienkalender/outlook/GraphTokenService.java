package de.goForFun.familienkalender.outlook;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tauscht den im SSM abgelegten Refresh_Token gegen einen Access_Token und schreibt einen
 * rotierten Refresh_Token vor dem ersten Graph-Request zurück. Hält keinen Zustand zwischen
 * Läufen; pro {@link #acquire(TimeBudget)} höchstens ein Token-Tausch, kein Retry.
 */
final class GraphTokenService {

    private static final Duration STEP_CAP = Duration.ofSeconds(10);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Ergebnis eines erfolgreichen Token-Tauschs; {@code refreshToken} darf {@code null} sein. */
    record TokenOutcome(String accessToken, String refreshToken) {
        @Override
        public String toString() {
            return "TokenOutcome[]";
        }
    }

    private final GraphConfig config;
    private final RefreshTokenStore store;
    private final GraphHttp http;
    private final OutlookLog log;

    GraphTokenService(GraphConfig config, RefreshTokenStore store, GraphHttp http, OutlookLog log) {
        this.config = Objects.requireNonNull(config, "config");
        this.store = Objects.requireNonNull(store, "store");
        this.http = Objects.requireNonNull(http, "http");
        this.log = Objects.requireNonNull(log, "log");
    }

    /** @return Access_Token für diesen Lauf */
    String acquire(TimeBudget budget) throws GraphException {
        String param = config.refreshTokenParam();

        String read = store.read(param, budget.stepTimeout(STEP_CAP));
        String refreshToken = read == null ? "" : read.trim();
        if (refreshToken.isEmpty()) {
            throw new GraphException(GraphErrorCategory.SSM_UNAVAILABLE);
        }

        URI tokenUri = URI.create(GraphEndpoints.TOKEN_URL);
        if (!GraphEndpoints.isLoginUri(tokenUri)) {
            throw new GraphException(GraphErrorCategory.TOKEN_EXCHANGE_FAILED);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        HttpCall call = new HttpCall("POST", tokenUri, headers,
                tokenRequestBody(config.clientId(), refreshToken));

        HttpResult result = http.send(call, budget.stepTimeout(STEP_CAP));
        TokenOutcome outcome = classify(result.status(), result.body());

        if (shouldPersist(refreshToken, outcome.refreshToken())) {
            try {
                store.write(param, outcome.refreshToken().trim(), budget.stepTimeout(STEP_CAP));
            } catch (GraphException e) {
                log.warn("WARN: Rotierter Refresh_Token konnte nicht gespeichert werden (SSM_Parameter: "
                        + param + ", " + e.category().displayName() + ")");
            }
        }
        return outcome.accessToken();
    }

    /** Form-urlencoded Body mit genau den vier vorgeschriebenen Feldern (kein client_secret). */
    static String tokenRequestBody(String clientId, String refreshToken) {
        return field("client_id", clientId)
                + "&" + field("grant_type", "refresh_token")
                + "&" + field("refresh_token", refreshToken)
                + "&" + field("scope", GraphEndpoints.SCOPE);
    }

    private static String field(String name, String value) {
        return name + "=" + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Klassifikation der Token-Antwort.
     *
     * @throws GraphException laut Klassifikationstabelle
     */
    static TokenOutcome classify(int status, String body) throws GraphException {
        JsonNode root = parse(body);

        if (status == 400 || status == 401 || "invalid_grant".equals(text(root, "error"))) {
            throw new GraphException(GraphErrorCategory.REFRESH_TOKEN_INVALID, status, null);
        }
        if (status != 200) {
            throw new GraphException(GraphErrorCategory.TOKEN_EXCHANGE_FAILED, status, null);
        }
        if (root == null || !root.isObject()) {
            throw new GraphException(GraphErrorCategory.RESPONSE_UNPARSABLE);
        }
        String access = text(root, "access_token");
        if (access == null || access.isBlank()) {
            throw new GraphException(GraphErrorCategory.TOKEN_EXCHANGE_FAILED, status, null);
        }
        return new TokenOutcome(access, text(root, "refresh_token"));
    }

    /** Rotationsentscheidung: nur ein nicht-leerer, vom gelesenen Wert abweichender Token wird geschrieben. */
    static boolean shouldPersist(String readToken, String returnedOrNull) {
        return returnedOrNull != null && !returnedOrNull.isBlank()
                && !returnedOrNull.trim().equals(readToken == null ? "" : readToken.trim());
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String text(JsonNode root, String field) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode n = root.get(field);
        return n != null && n.isString() ? n.asString() : null;
    }
}
