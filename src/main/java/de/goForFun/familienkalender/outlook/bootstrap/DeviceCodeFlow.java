package de.goForFun.familienkalender.outlook.bootstrap;

import de.goForFun.familienkalender.outlook.GraphEndpoints;
import de.goForFun.familienkalender.outlook.GraphException;
import de.goForFun.familienkalender.outlook.GraphHttp;
import de.goForFun.familienkalender.outlook.HttpCall;
import de.goForFun.familienkalender.outlook.HttpResult;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OAuth-Device-Code-Ablauf: {@code devicecode}-Anfrage, Anzeige von URL und Code, Polling. */
final class DeviceCodeFlow {

    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    static final long DEFAULT_INTERVAL_SECONDS = 5;
    static final long SLOW_DOWN_INCREMENT_SECONDS = 5;
    static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    private static final Pattern CODE_ALLOWLIST = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** Erfolgreiches Token-Ergebnis. Werte sind geheim und erscheinen nie in {@code toString}. */
    record Tokens(String accessToken, String refreshToken, String scope) {
        @Override
        public String toString() {
            return "Tokens[]";
        }
    }

    private record DeviceAuthorization(String deviceCode, String userCode, String verificationUri,
            long intervalSeconds, long expiresInSeconds) {
        @Override
        public String toString() {
            return "DeviceAuthorization[]";
        }
    }

    private final GraphHttp http;
    private final Clock clock;
    private final Sleeper sleeper;
    private final PrintStream out;

    DeviceCodeFlow(GraphHttp http, Clock clock, Sleeper sleeper, PrintStream out) {
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.out = Objects.requireNonNull(out, "out");
    }

    /** Führt den kompletten Ablauf aus und liefert die Tokens nach erfolgreicher Anmeldung. */
    Tokens run(String clientId) throws BootstrapException {
        DeviceAuthorization auth = requestDeviceCode(clientId);
        out.println("Bitte im Browser öffnen: " + auth.verificationUri());
        out.println("und diesen Code eingeben: " + auth.userCode());
        out.flush();
        return poll(clientId, auth);
    }

    private DeviceAuthorization requestDeviceCode(String clientId) throws BootstrapException {
        URI uri = URI.create(GraphEndpoints.DEVICE_URL);
        if (!GraphEndpoints.isLoginUri(uri)) {
            throw new BootstrapException("Device-Code-Anfrage: ungültiger Endpunkt");
        }
        String body = field("client_id", clientId) + "&" + field("scope", GraphEndpoints.SCOPE);
        HttpResult result = send(new HttpCall("POST", uri, formHeaders(), body), "Device-Code-Anfrage");
        JsonNode root = parse(result.body());
        if (result.status() < 200 || result.status() > 299) {
            throw new BootstrapException("Device-Code-Anfrage fehlgeschlagen (HTTP " + result.status()
                    + codeSuffix(text(root, "error")) + ")");
        }
        String deviceCode = text(root, "device_code");
        String userCode = text(root, "user_code");
        String verificationUri = text(root, "verification_uri");
        if (isBlank(deviceCode) || isBlank(userCode) || isBlank(verificationUri)) {
            throw new BootstrapException("Device-Code-Anfrage: Antwort nicht parsebar");
        }
        long interval = number(root, "interval", DEFAULT_INTERVAL_SECONDS);
        if (interval <= 0) {
            interval = DEFAULT_INTERVAL_SECONDS;
        }
        long expiresIn = number(root, "expires_in", -1);
        if (expiresIn <= 0) {
            throw new BootstrapException("Device-Code-Anfrage: Antwort nicht parsebar");
        }
        return new DeviceAuthorization(deviceCode, userCode.trim(), verificationUri.trim(), interval,
                expiresIn);
    }

    private Tokens poll(String clientId, DeviceAuthorization auth) throws BootstrapException {
        URI uri = URI.create(GraphEndpoints.TOKEN_URL);
        if (!GraphEndpoints.isLoginUri(uri)) {
            throw new BootstrapException("Anmeldung: ungültiger Endpunkt");
        }
        String body = field("grant_type", GRANT_TYPE) + "&" + field("client_id", clientId) + "&"
                + field("device_code", auth.deviceCode());
        Instant deadline = clock.instant().plusSeconds(auth.expiresInSeconds());
        long interval = auth.intervalSeconds();
        while (true) {
            if (!clock.instant().isBefore(deadline)) {
                throw expired();
            }
            try {
                sleeper.sleep(Duration.ofSeconds(interval));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BootstrapException("Anmeldung abgebrochen (unterbrochen)");
            }
            if (!clock.instant().isBefore(deadline)) {
                throw expired();
            }
            HttpResult result = send(new HttpCall("POST", uri, formHeaders(), body), "Anmeldung");
            JsonNode root = parse(result.body());
            if (result.status() >= 200 && result.status() <= 299) {
                String access = text(root, "access_token");
                if (isBlank(access)) {
                    throw new BootstrapException("Anmeldung: Antwort nicht parsebar");
                }
                return new Tokens(access, text(root, "refresh_token"), text(root, "scope"));
            }
            String error = text(root, "error");
            if ("authorization_pending".equals(error)) {
                continue;
            }
            if ("slow_down".equals(error)) {
                interval += SLOW_DOWN_INCREMENT_SECONDS;
                continue;
            }
            if (error == null) {
                throw new BootstrapException("Anmeldung fehlgeschlagen (HTTP " + result.status() + ")");
            }
            throw new BootstrapException("Anmeldung abgebrochen (HTTP " + result.status()
                    + codeSuffix(error) + ")");
        }
    }

    private static BootstrapException expired() {
        return new BootstrapException("Anmeldung abgebrochen: Device-Code abgelaufen (expires_in)");
    }

    private HttpResult send(HttpCall call, String step) throws BootstrapException {
        try {
            return http.send(call, HTTP_TIMEOUT);
        } catch (GraphException e) {
            throw new BootstrapException(step + " fehlgeschlagen: " + e.safeMessage());
        }
    }

    private static Map<String, String> formHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        return headers;
    }

    private static String field(String name, String value) {
        return name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String codeSuffix(String code) {
        return code != null && CODE_ALLOWLIST.matcher(code).matches() ? ", " + code : "";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
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

    private static long number(JsonNode root, String field, long fallback) {
        if (root == null || !root.isObject()) {
            return fallback;
        }
        JsonNode n = root.get(field);
        if (n == null) {
            return fallback;
        }
        if (n.isNumber()) {
            return n.longValue();
        }
        if (n.isString()) {
            try {
                return Long.parseLong(n.asString().trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }
}
