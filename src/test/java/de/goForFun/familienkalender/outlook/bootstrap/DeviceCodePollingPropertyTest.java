package de.goForFun.familienkalender.outlook.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.goForFun.familienkalender.outlook.FakeRefreshTokenStore;
import de.goForFun.familienkalender.outlook.GraphArbitraries;
import de.goForFun.familienkalender.outlook.GraphArbitraries.DeviceCodeScript;
import de.goForFun.familienkalender.outlook.GraphErrorCategory;
import de.goForFun.familienkalender.outlook.GraphException;
import de.goForFun.familienkalender.outlook.MutableClock;
import de.goForFun.familienkalender.outlook.ScriptedGraphHttp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitrary;

/**
 * Feature: outlook-graph-source, Property 30: Der Device-Code-Polling-Automat folgt Intervall- und
 * Abbruchregeln.
 *
 * <p><b>Validates: Requirements 8.6, 8.7</b>
 */
class DeviceCodePollingPropertyTest {

    private static final String PARAM = "/familienkalender/graph/refresh-token";
    private static final String REFRESH = "REFRESH-SECRET-bbb";
    private static final String[] ARGS = {"--client-id", "client-123", "--param", PARAM};

    @Provide
    Arbitrary<DeviceCodeScript> scripts() {
        return GraphArbitraries.deviceCodeScriptsArbitrary();
    }

    @Property(tries = 300)
    void pollingFollowsIntervalAndAbortRules(@ForAll("scripts") DeviceCodeScript script) {
        MutableClock clock = new MutableClock(Instant.parse("2025-06-01T10:00:00Z"));
        ScriptedGraphHttp http = new ScriptedGraphHttp();
        FakeRefreshTokenStore store = new FakeRefreshTokenStore();
        List<Duration> sleeps = new ArrayList<>();
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        StringBuilder device = new StringBuilder("{\"device_code\":\"DEVCODE-SECRET\",\"user_code\":\"ABCD-1234\","
                + "\"verification_uri\":\"https://login.microsoft.com/device\",\"expires_in\":" + script.expiresIn());
        if (script.interval() != null) {
            device.append(",\"interval\":").append(script.interval());
        }
        device.append('}');
        http.enqueue(200, device.toString());

        List<String> responses = new ArrayList<>(script.pollErrors());
        responses.add(script.terminal());
        for (String r : responses) {
            switch (r) {
                case "SUCCESS" -> http.enqueue(200, "{\"access_token\":\"ACCESS-SECRET\",\"refresh_token\":\""
                        + REFRESH + "\",\"scope\":\"Calendars.Read\"}");
                case "NETWORK" -> http.enqueue(new GraphException(GraphErrorCategory.NETWORK));
                default -> http.enqueue(400, "{\"error\":\"" + r + "\"}");
            }
        }
        http.enqueue(200, "{}"); // Probe

        // Orakel: erwartete Wartezeiten und Ausgang.
        long interval = script.interval() != null ? script.interval() : 5;
        long elapsed = 0;
        int expectedPolls = 0;
        List<Duration> expectedSleeps = new ArrayList<>();
        String outcome = null; // null = Erfolg
        for (String r : responses) {
            if (elapsed >= script.expiresIn()) {
                outcome = "EXPIRED";
                break;
            }
            expectedSleeps.add(Duration.ofSeconds(interval));
            elapsed += interval;
            if (elapsed >= script.expiresIn()) {
                outcome = "EXPIRED";
                break;
            }
            expectedPolls++;
            if (r.equals("authorization_pending")) {
                continue;
            }
            if (r.equals("slow_down")) {
                interval += 5;
                continue;
            }
            outcome = r.equals("SUCCESS") ? null : r;
            break;
        }

        int exit = GraphBootstrap.run(ARGS, Map.of(), http, store, clock,
                d -> {
                    sleeps.add(d);
                    clock.advance(d);
                },
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        String err = errBytes.toString(StandardCharsets.UTF_8);
        String out = outBytes.toString(StandardCharsets.UTF_8);

        assertEquals(expectedSleeps, sleeps, "Wartezeiten");
        // 1 Device-Code-Anfrage + Polls (+ Probe bei Erfolg)
        long tokenCalls = http.calls().stream().filter(c -> c.uri().toString().endsWith("/token")).count();
        assertEquals(expectedPolls, tokenCalls, "Anzahl Polls");

        if (outcome == null) {
            assertEquals(0, exit);
            assertEquals(1, store.writes().size());
        } else {
            assertTrue(exit != 0, "Exit-Code muss != 0 sein");
            assertTrue(store.writes().isEmpty(), "kein SSM-Write");
            assertFalse(out.contains("gespeichert"));
            if ("EXPIRED".equals(outcome)) {
                assertTrue(err.contains("abgelaufen"), err);
            } else if ("NETWORK".equals(outcome)) {
                assertTrue(err.contains(GraphErrorCategory.NETWORK.displayName()), err);
            } else {
                assertTrue(err.contains(outcome), err);
            }
        }
        assertFalse((out + err).contains("DEVCODE-SECRET"));
    }
}
