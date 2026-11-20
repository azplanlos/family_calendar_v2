package de.goForFun.familienkalender.outlook.bootstrap;

import de.goForFun.familienkalender.outlook.GraphEndpoints;
import de.goForFun.familienkalender.outlook.GraphException;
import de.goForFun.familienkalender.outlook.GraphHttp;
import de.goForFun.familienkalender.outlook.HttpCall;
import de.goForFun.familienkalender.outlook.HttpResult;
import de.goForFun.familienkalender.outlook.JdkGraphHttp;
import de.goForFun.familienkalender.outlook.RefreshTokenStore;
import de.goForFun.familienkalender.outlook.SsmRefreshTokenStore;
import java.io.PrintStream;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import software.amazon.awssdk.services.ssm.SsmClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Einmaliges lokales Bootstrap-Programm: meldet sich per Device-Code-Flow bei Microsoft an, prüft
 * Refresh-Token und Scope, probiert eine calendarView-Abfrage und speichert den Refresh-Token erst
 * danach als SecureString im SSM Parameter Store. Token werden nie ausgegeben. Wird vom
 * Lambda-Handler nicht referenziert und ist aus dem Shade-JAR ausgeschlossen.
 */
public final class GraphBootstrap {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final int EXIT_OK = 0;
    private static final int EXIT_ERROR = 1;
    private static final Pattern CODE_ALLOWLIST = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private GraphBootstrap() {
    }

    public static void main(String[] args) {
        int exit;
        try (SsmRefreshTokenStore store = new SsmRefreshTokenStore(SsmClient.create())) {
            exit = run(args, System.getenv(), new JdkGraphHttp(), store, Clock.systemUTC(),
                    d -> Thread.sleep(d), System.out, System.err);
        } catch (RuntimeException e) {
            // Meldung des SDK nicht ausgeben (könnte Konfigurationswerte enthalten).
            System.err.println("Fehler: SSM-Client konnte nicht erstellt werden");
            exit = EXIT_ERROR;
        }
        System.exit(exit);
    }

    /** @return Exit-Code; 0 nur nach erfolgreichem Schreiben des Refresh-Tokens */
    static int run(String[] args, Map<String, String> env, GraphHttp http, RefreshTokenStore store,
            Clock clock, Sleeper sleeper, PrintStream out, PrintStream err) {
        BootstrapConfig config;
        try {
            config = BootstrapConfig.resolve(args, env);
        } catch (ConfigException e) {
            err.println("Fehler: " + e.getMessage());
            return EXIT_ERROR;
        }

        try {
            DeviceCodeFlow.Tokens tokens = new DeviceCodeFlow(http, clock, sleeper, out)
                    .run(config.clientId());

            String refreshToken = tokens.refreshToken();
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new BootstrapException("Antwort enthält keinen refresh_token");
            }
            if (!ScopeCheck.includesCalendarsRead(tokens.scope())) {
                throw new BootstrapException("Antwort enthält den Scope Calendars.Read nicht");
            }

            probe(http, clock, tokens.accessToken());

            try {
                store.write(config.parameterName(), refreshToken.trim(), TIMEOUT);
            } catch (GraphException e) {
                throw new BootstrapException("Speichern in SSM fehlgeschlagen (SSM_Parameter: "
                        + config.parameterName() + ", " + e.safeMessage() + ")");
            } catch (RuntimeException e) {
                throw new BootstrapException("Speichern in SSM fehlgeschlagen (SSM_Parameter: "
                        + config.parameterName() + ")");
            }
            out.println("Refresh_Token wurde gespeichert (SSM_Parameter: " + config.parameterName() + ")");
            return EXIT_OK;
        } catch (BootstrapException e) {
            err.println("Fehler: " + e.getMessage());
            return EXIT_ERROR;
        } catch (RuntimeException e) {
            err.println("Fehler: unerwarteter Fehler im Bootstrap");
            return EXIT_ERROR;
        }
    }

    /** Probe-Abfrage der calendarView für ein 1-Tages-Fenster; Fehler brechen vor dem SSM-Write ab. */
    private static void probe(GraphHttp http, Clock clock, String accessToken) throws BootstrapException {
        Instant now = clock.instant();
        String start = UTC_FORMAT.format(now.atOffset(ZoneOffset.UTC));
        String end = UTC_FORMAT.format(now.plusSeconds(24 * 3600L).atOffset(ZoneOffset.UTC));
        URI uri = URI.create(GraphEndpoints.CALENDAR_VIEW + "?startDateTime=" + start
                + "&endDateTime=" + end + "&$select=id&$top=1");
        if (!GraphEndpoints.isGraphUri(uri)) {
            throw new BootstrapException("Probe-Abfrage: ungültiger Endpunkt");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        headers.put("Accept", "application/json");
        HttpResult result;
        try {
            result = http.send(new HttpCall("GET", uri, headers, null), TIMEOUT);
        } catch (GraphException e) {
            throw new BootstrapException("Probe-Abfrage fehlgeschlagen: " + e.safeMessage());
        }
        if (result.status() < 200 || result.status() > 299) {
            String code = graphErrorCode(result.body());
            throw new BootstrapException("Probe-Abfrage fehlgeschlagen (HTTP " + result.status()
                    + (code == null ? "" : ", " + code) + ")");
        }
    }

    private static String graphErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = MAPPER.readTree(body).get("error");
            JsonNode code = error == null ? null : error.get("code");
            if (code != null && code.isString() && CODE_ALLOWLIST.matcher(code.asString()).matches()) {
                return code.asString();
            }
        } catch (RuntimeException e) {
            // nicht parsebar: kein Code
        }
        return null;
    }
}
