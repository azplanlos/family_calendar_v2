package de.goForFun.familienkalender.outlook;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Feature: outlook-graph-source, Property 13: Fehlerhafte Graph-Antworten werden als
 * Graph_Fehler ohne Body-Leck klassifiziert</b>
 *
 * <p><b>Validates: Requirements 4.12, 4.13</b>
 */
class GraphCalendarClientErrorPropertyTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Pattern ALLOWLIST = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final QueryWindow WINDOW = new QueryWindow(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));

    // ---------------------------------------------------------------- Generatoren

    @Provide
    Arbitrary<Integer> errorStatus() {
        return Arbitraries.oneOf(
                Arbitraries.integers().between(100, 199),
                Arbitraries.integers().between(300, 599));
    }

    /** Fehlercodes: gültig laut Allowlist, oder ungültig (Sonderzeichen, zu lang, leer). */
    @Provide
    Arbitrary<String> rawCodes() {
        Arbitrary<String> valid = Arbitraries.strings().withChars("abcdefghijklmnopqrstuvwxyz"
                + "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-").ofMinLength(1).ofMaxLength(64);
        Arbitrary<String> tooLong = Arbitraries.strings().alpha().ofMinLength(65).ofMaxLength(90);
        Arbitrary<String> invalidChars = Arbitraries.strings().alpha().ofMinLength(0).ofMaxLength(10)
                .flatMap(p -> Arbitraries.of(" ", "!", "/", "\n", "\"", "ü", "<", ":", "=")
                        .map(c -> p + c + "x"));
        return Arbitraries.oneOf(valid, valid, tooLong, invalidChars, Arbitraries.just(""));
    }

    @Provide
    Arbitrary<String> canaries() {
        return GraphArbitraries.canaryStringsArbitrary();
    }

    // ---------------------------------------------------------------- Properties

    @Property(tries = 300)
    void nonSuccessStatusYieldsGraphHttpWithSingleRequestAndNoBodyLeak(
            @ForAll("errorStatus") int status,
            @ForAll("rawCodes") String rawCode,
            @ForAll("canaries") String canary,
            @ForAll boolean withCode,
            @ForAll boolean jsonBody) throws Exception {
        String body;
        if (jsonBody) {
            ObjectNode root = MAPPER.createObjectNode();
            ObjectNode error = root.putObject("error");
            if (withCode) {
                error.put("code", rawCode);
            }
            error.put("message", canary);
            error.putObject("innerError").put("request-id", canary);
            body = MAPPER.writeValueAsString(root);
        } else {
            body = canary + " " + (withCode ? rawCode : "");
        }
        ScriptedGraphHttp http = new ScriptedGraphHttp().enqueue(status, body);

        GraphException e = assertThrows(GraphException.class, () -> client(http).fetchAll(WINDOW, "tok", budget()));

        assertEquals(1, http.callCount(), "kein Retry");
        assertEquals(GraphErrorCategory.GRAPH_HTTP, e.category());
        assertEquals(status, e.httpStatus().orElseThrow());
        boolean expectCode = jsonBody && withCode && ALLOWLIST.matcher(rawCode).matches();
        if (expectCode) {
            assertEquals(rawCode, e.graphCode().orElseThrow());
        } else {
            assertTrue(e.graphCode().isEmpty(), "Code außerhalb der Allowlist: " + e.graphCode());
        }
        assertFalse(e.safeMessage().contains(canary), "Body-Leck: " + e.safeMessage());
        assertFalse(String.valueOf(e.getMessage()).contains(canary));
        assertFalse(e.safeMessage().contains("tok"), "Token in Meldung");
    }

    @Property(tries = 200)
    void successBodyWithoutValueArrayYieldsResponseUnparsable(
            @ForAll("canaries") String canary,
            @ForAll("successStatus") int status,
            @ForAll("bodyIndex") int index) {
        String[] all = variants(canary);
        String body = all[index % all.length];
        ScriptedGraphHttp http = new ScriptedGraphHttp().enqueue(status, body);

        GraphException e = assertThrows(GraphException.class, () -> client(http).fetchAll(WINDOW, "tok", budget()));

        assertEquals(1, http.callCount());
        assertEquals(GraphErrorCategory.RESPONSE_UNPARSABLE, e.category());
        assertFalse(e.safeMessage().contains(canary), "Body-Leck: " + e.safeMessage());
        assertTrue(e.graphCode().isEmpty());
    }

    @Provide
    Arbitrary<Integer> successStatus() {
        return Arbitraries.integers().between(200, 299);
    }

    @Provide
    Arbitrary<Integer> bodyIndex() {
        return Arbitraries.integers().between(0, 1000);
    }

    private static String[] variants(String canary) {
        return new String[] {
                canary, "<html>" + canary + "</html>", "{" + canary, "", "   ", "null", "[]",
                "[{\"value\":[]}]", "\"" + canary + "\"", "42", "{}", "{\"value\":null}",
                "{\"value\":\"" + canary + "\"}", "{\"value\":{\"a\":\"" + canary + "\"}}",
                "{\"value\":42}", "{\"Value\":[]}",
                "{\"error\":{\"code\":\"X\",\"message\":\"" + canary + "\"}}"
        };
    }

    // ---------------------------------------------------------------- Hilfen

    private static GraphCalendarClient client(GraphHttp http) {
        return new GraphCalendarClient(http, ZoneId.of("Europe/Berlin"));
    }

    private static TimeBudget budget() {
        return TimeBudget.start(new MutableClock(Instant.parse("2026-01-01T00:00:00Z")), Duration.ofSeconds(15));
    }
}
