package de.goForFun.familienkalender.outlook.bootstrap;

import static org.junit.jupiter.api.Assertions.assertFalse;

import de.goForFun.familienkalender.outlook.FakeRefreshTokenStore;
import de.goForFun.familienkalender.outlook.GraphArbitraries;
import de.goForFun.familienkalender.outlook.GraphErrorCategory;
import de.goForFun.familienkalender.outlook.GraphException;
import de.goForFun.familienkalender.outlook.MutableClock;
import de.goForFun.familienkalender.outlook.RefreshTokenStore;
import de.goForFun.familienkalender.outlook.ScriptedGraphHttp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: outlook-graph-source, Property 32: Der Bootstrap gibt nie Token aus.
 *
 * <p>Canary-Strings als {@code access_token}, {@code refresh_token}, {@code device_code} und in
 * Fehler-Bodys / Store-Fehlermeldungen tauchen weder in {@code out} noch in {@code err} auf.
 *
 * <p><b>Validates: Requirements 8.16</b>
 */
class BootstrapNoTokenOutputPropertyTest extends GraphArbitraries {

    private static final String PARAM = "/familienkalender/graph/refresh-token";
    private static final String[] ARGS = {"--client-id", "client-123", "--param", PARAM};

    /** 0 = Probe OK, 1 = HTTP-Fehler mit Canary im Body, 2 = Netzwerkfehler. */
    @Provide
    Arbitrary<Integer> probeModes() {
        return Arbitraries.integers().between(0, 2);
    }

    /** 0 = Schreiben OK, 1 = GraphException, 2 = RuntimeException mit Canary in der Meldung. */
    @Provide
    Arbitrary<Integer> writeModes() {
        return Arbitraries.integers().between(0, 2);
    }

    @Provide
    Arbitrary<Integer> pollErrorStatuses() {
        return Arbitraries.of(400, 401, 500);
    }

    @Property(tries = 200)
    void neverPrintsTokens(@ForAll("canaryStrings") String canary,
            @ForAll("deviceCodeScripts") DeviceCodeScript script,
            @ForAll("probeModes") int probeMode,
            @ForAll("writeModes") int writeMode,
            @ForAll("pollErrorStatuses") int pollStatus,
            @ForAll("scopeStrings") String scope) {
        MutableClock clock = new MutableClock(Instant.parse("2025-06-01T10:00:00Z"));
        ScriptedGraphHttp http = new ScriptedGraphHttp();

        String interval = script.interval() == null ? "" : ",\"interval\":" + script.interval();
        http.enqueue(200, "{\"device_code\":\"DEV" + canary + "\",\"user_code\":\"ABCD-1234\","
                + "\"verification_uri\":\"https://login.microsoft.com/device\",\"expires_in\":"
                + script.expiresIn() + interval + "}");
        for (String error : script.pollErrors()) {
            http.enqueue(400, "{\"error\":\"" + error + "\",\"error_description\":\"desc " + canary + "\"}");
        }
        switch (script.terminal()) {
            case "SUCCESS" -> {
                String tokenScope = scope.isEmpty() || probeMode == 0 ? "Calendars.Read" : scope;
                http.enqueue(200, "{\"token_type\":\"Bearer\",\"scope\":\"" + tokenScope
                        + "\",\"access_token\":\"ACC" + canary + "\",\"refresh_token\":\"REF" + canary + "\"}");
                switch (probeMode) {
                    case 0 -> http.enqueue(200, "{\"value\":[]}");
                    case 1 -> http.enqueue(403, "{\"error\":{\"code\":\"ErrorAccessDenied\",\"message\":\""
                            + canary + "\"},\"access_token\":\"" + canary + "\"}");
                    default -> http.enqueue(new GraphException(GraphErrorCategory.NETWORK));
                }
            }
            case "NETWORK" -> http.enqueue(new GraphException(GraphErrorCategory.NETWORK));
            default -> http.enqueue(pollStatus, "{\"error\":\"" + script.terminal()
                    + "\",\"error_description\":\"" + canary + "\",\"refresh_token\":\"" + canary + "\"}");
        }

        RefreshTokenStore store = store(writeMode, canary);

        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        GraphBootstrap.run(ARGS, Map.of(), http, store, clock, d -> clock.advance(d),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));

        String out = outBytes.toString(StandardCharsets.UTF_8);
        String err = errBytes.toString(StandardCharsets.UTF_8);
        assertFalse(out.contains(canary), out);
        assertFalse(err.contains(canary), err);
        assertFalse(out.contains("CANARY"), out);
        assertFalse(err.contains("CANARY"), err);
    }

    private static RefreshTokenStore store(int writeMode, String canary) {
        if (writeMode == 2) {
            return new RefreshTokenStore() {
                @Override
                public String read(String p, Duration t) throws GraphException {
                    throw new GraphException(GraphErrorCategory.SSM_UNAVAILABLE);
                }

                @Override
                public void write(String p, String v, Duration t) {
                    throw new IllegalStateException(v + canary);
                }
            };
        }
        FakeRefreshTokenStore fake = new FakeRefreshTokenStore();
        if (writeMode == 1) {
            fake.failWrites(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));
        }
        return fake;
    }
}
