package de.goForFun.familienkalender.outlook;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 3: Graph-Zustand ergibt sich aus den getrimmten Konfigurationswerten.
 *
 * <p>Die Warnung wird im Selector erzeugt; hier bildet ein minimaler Stub die vorgesehene Logik nach
 * (eine Warnung mit {@link GraphConfig#missingVariable()}, nie mit einem Wert).
 */
class GraphConfigPropertyTest {

    @Provide
    Arbitrary<String> rawValues() {
        Arbitrary<String> nonEmpty = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(20)
                .map(s -> "secret-" + s);
        Arbitrary<String> padded = Arbitraries.strings().withChars(' ', '\t', '\n').ofMaxLength(4)
                .flatMap(pad -> nonEmpty.map(v -> pad + v + pad));
        Arbitrary<String> blank = Arbitraries.strings().withChars(' ', '\t', '\n', '\r').ofMaxLength(5);
        return Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.just(""),
                blank,
                nonEmpty,
                padded);
    }

    /** Minimaler Selector-Stub: genau eine Warnung bei PARTIAL, nur mit Variablennamen. */
    private static void warnIfPartial(GraphConfig config, OutlookLog log) {
        if (config.state() == GraphConfig.State.PARTIAL) {
            log.warn("WARN: Graph-Konfiguration unvollständig, fehlende Variable: " + config.missingVariable());
        }
    }

    private static boolean present(String raw) {
        return raw != null && !raw.trim().isEmpty();
    }

    @Property(tries = 200)
    // **Validates: Requirements 1.3, 1.7, 1.9**
    void stateFollowsTrimmedValues(@ForAll("rawValues") String clientId,
                                   @ForAll("rawValues") String param) {
        Map<String, String> env = new HashMap<>();
        if (clientId != null) {
            env.put(GraphConfig.ENV_CLIENT_ID, clientId);
        }
        if (param != null) {
            env.put(GraphConfig.ENV_REFRESH_TOKEN_PARAM, param);
        }
        GraphConfig config = GraphConfig.fromMap(env);

        boolean hasClient = present(clientId);
        boolean hasParam = present(param);

        GraphConfig.State expected = hasClient && hasParam ? GraphConfig.State.CONFIGURED
                : (!hasClient && !hasParam ? GraphConfig.State.NOT_CONFIGURED : GraphConfig.State.PARTIAL);
        assertEquals(expected, config.state());

        // Werte werden getrimmt gespeichert
        assertEquals(clientId == null ? "" : clientId.trim(), config.clientId());
        assertEquals(param == null ? "" : param.trim(), config.refreshTokenParam());

        RecordingLog log = new RecordingLog();
        warnIfPartial(config, log);

        if (expected == GraphConfig.State.PARTIAL) {
            String missing = hasClient ? GraphConfig.ENV_REFRESH_TOKEN_PARAM : GraphConfig.ENV_CLIENT_ID;
            assertEquals(missing, config.missingVariable());
            assertEquals(1, log.warnings().size());
            String line = log.warnings().get(0);
            assertTrue(line.contains(missing));
            assertFalse(line.contains("secret-"), "Warnung darf keinen Variablenwert enthalten");
        } else {
            assertNull(config.missingVariable());
            assertTrue(log.all().isEmpty());
        }
    }

    @Property(tries = 100)
    // **Validates: Requirements 1.7**
    void stateIsReevaluatedPerCall(@ForAll("rawValues") String clientId,
                                   @ForAll("rawValues") String param) {
        Map<String, String> env = new HashMap<>();
        env.put(GraphConfig.ENV_CLIENT_ID, clientId == null ? "" : clientId);
        env.put(GraphConfig.ENV_REFRESH_TOKEN_PARAM, param == null ? "" : param);
        GraphConfig first = GraphConfig.fromMap(env);
        // Umgebung ändern: je Aufruf frisch gelesen, kein Zustand zwischen Aufrufen
        env.clear();
        GraphConfig second = GraphConfig.fromMap(env);
        assertEquals(GraphConfig.State.NOT_CONFIGURED, second.state());
        assertEquals(first.state() == GraphConfig.State.CONFIGURED, present(clientId) && present(param));
    }
}
