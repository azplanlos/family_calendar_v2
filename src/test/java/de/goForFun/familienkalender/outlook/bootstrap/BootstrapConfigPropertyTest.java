package de.goForFun.familienkalender.outlook.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import de.goForFun.familienkalender.outlook.RefreshTokenStore;
import de.goForFun.familienkalender.outlook.ScriptedGraphHttp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: outlook-graph-source, Property 29: Bootstrap-Konfiguration hat Argument-Vorrang und bricht
 * ohne Netzwerkzugriff ab.
 *
 * <p><b>Validates: Requirements 8.2, 8.3</b>
 */
class BootstrapConfigPropertyTest {

    /** Eine Angabe: {@code null}, leer, nur Leerraum oder Text mit Leerraum-Rand. */
    record Value(String raw) {
        String trimmed() {
            return raw == null ? "" : raw.trim();
        }
    }

    @Provide
    Arbitrary<Value> values() {
        Arbitrary<String> ws = Arbitraries.strings().withChars(' ', '\t').ofMaxLength(4);
        Arbitrary<String> text = Arbitraries.strings().alpha().numeric().withChars('-', '_', '/', '.')
                .ofMinLength(1).ofMaxLength(20);
        Arbitrary<String> padded = Combinators.combine(ws, text, ws).as((a, t, b) -> a + t + b);
        return Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.just(""),
                ws,
                padded).map(Value::new);
    }

    private static String[] args(Value clientId, Value param, boolean equalsForm) {
        List<String> list = new ArrayList<>();
        add(list, "--client-id", clientId, equalsForm);
        add(list, "--param", param, equalsForm);
        return list.toArray(new String[0]);
    }

    private static void add(List<String> list, String name, Value v, boolean equalsForm) {
        if (v.raw() == null) {
            return;
        }
        if (equalsForm) {
            list.add(name + "=" + v.raw());
        } else {
            list.add(name);
            list.add(v.raw());
        }
    }

    private static Map<String, String> env(Value clientId, Value param) {
        Map<String, String> env = new HashMap<>();
        if (clientId.raw() != null) {
            env.put("GRAPH_CLIENT_ID", clientId.raw());
        }
        if (param.raw() != null) {
            env.put("GRAPH_REFRESH_TOKEN_PARAM", param.raw());
        }
        return env;
    }

    private static String expected(Value arg, Value env) {
        return !arg.trimmed().isEmpty() ? arg.trimmed() : env.trimmed();
    }

    @Property
    void argumentHasPrecedenceOverEnvironment(
            @ForAll("values") Value argClient, @ForAll("values") Value envClient,
            @ForAll("values") Value argParam, @ForAll("values") Value envParam,
            @ForAll boolean equalsForm) {
        String expClient = expected(argClient, envClient);
        String expParam = expected(argParam, envParam);
        String[] args = args(argClient, argParam, equalsForm);
        Map<String, String> env = env(envClient, envParam);

        try {
            BootstrapConfig config = BootstrapConfig.resolve(args, env);
            assertThat(expClient).isNotEmpty();
            assertThat(expParam).isNotEmpty();
            assertThat(config.clientId()).isEqualTo(expClient);
            assertThat(config.parameterName()).isEqualTo(expParam);
        } catch (ConfigException e) {
            String missing = expClient.isEmpty() ? "--client-id" : "--param";
            assertThat(expClient.isEmpty() || expParam.isEmpty()).isTrue();
            assertThat(e.missingName()).isEqualTo(missing);
            assertThat(e.getMessage()).contains(missing);
        }
    }

    @Property
    void missingConfigurationFailsWithoutHttpCall(
            @ForAll("values") Value argClient, @ForAll("values") Value envClient,
            @ForAll("values") Value argParam, @ForAll("values") Value envParam,
            @ForAll boolean equalsForm) {
        String expClient = expected(argClient, envClient);
        String expParam = expected(argParam, envParam);
        if (!expClient.isEmpty() && !expParam.isEmpty()) {
            return; // vollständige Konfiguration: nicht Gegenstand dieser Eigenschaft
        }
        ScriptedGraphHttp http = new ScriptedGraphHttp();
        RefreshTokenStore store = new RefreshTokenStore() {
            @Override
            public String read(String parameterName, java.time.Duration timeout) {
                throw new AssertionError("store.read darf nicht aufgerufen werden");
            }

            @Override
            public void write(String parameterName, String value, java.time.Duration timeout) {
                throw new AssertionError("store.write darf nicht aufgerufen werden");
            }
        };
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(outBytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBytes, true, StandardCharsets.UTF_8);

        int exit = GraphBootstrap.run(args(argClient, argParam, equalsForm), env(envClient, envParam),
                http, store, Clock.systemUTC(), d -> {
                    throw new AssertionError("sleep darf nicht aufgerufen werden");
                }, out, err);

        String missing = expClient.isEmpty() ? "--client-id" : "--param";
        assertThat(exit).isNotZero();
        assertThat(http.callCount()).isZero();
        assertThat(errBytes.toString(StandardCharsets.UTF_8)).contains(missing);
        assertThat(outBytes.toString(StandardCharsets.UTF_8)).isEmpty();
    }
}
