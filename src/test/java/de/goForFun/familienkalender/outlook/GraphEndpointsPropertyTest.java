package de.goForFun.familienkalender.outlook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Feature: outlook-graph-source, Property 12: Host- und Schema-Guard schützt die Token.
 *
 * <p><b>Validates: Requirements 4.11, 9.6, 9.7</b>
 */
class GraphEndpointsPropertyTest {

    private static final String GRAPH = "graph.microsoft.com";
    private static final String LOGIN = "login.microsoftonline.com";

    private record Parts(String scheme, String userInfo, String host, String port, String path) {
        URI toUri() {
            return URI.create(scheme + "://" + (userInfo.isEmpty() ? "" : userInfo + "@") + host
                    + (port.isEmpty() ? "" : ":" + port) + path);
        }

        boolean expected(String expectedHost) {
            return scheme.equalsIgnoreCase("https")
                    && host.equalsIgnoreCase(expectedHost)
                    && userInfo.isEmpty()
                    && (port.isEmpty() || port.equals("443"));
        }
    }

    private static Arbitrary<String> caseVariants(String s) {
        return Arbitraries.of(true, false).list().ofSize(s.length()).map(flags -> {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                sb.append(flags.get(i) ? Character.toUpperCase(c) : c);
            }
            return sb.toString();
        });
    }

    private static Arbitrary<String> hosts(String target) {
        return Arbitraries.oneOf(
                caseVariants(target),
                Arbitraries.just(target + ".evil.example"),
                Arbitraries.just("evil." + target),
                Arbitraries.just("evil.example"),
                Arbitraries.just(target.replace(".com", ".co")),
                Arbitraries.just(GRAPH.equals(target) ? LOGIN : GRAPH));
    }

    private Arbitrary<Parts> parts(String target) {
        Arbitrary<String> schemes = Arbitraries.of("https", "HTTPS", "Https", "http", "ftp", "file", "wss");
        Arbitrary<String> users = Arbitraries.of("", "", "", "x", "evil", "x:y");
        Arbitrary<String> ports = Arbitraries.of("", "", "443", "80", "8443", "8080", "1");
        Arbitrary<String> paths = Arbitraries.of("", "/", "/v1.0/me/calendarView?$skip=100", "/x");
        return Combinators.combine(schemes, users, hosts(target), ports, paths).as(Parts::new);
    }

    @Provide
    Arbitrary<Parts> graphParts() {
        return parts(GRAPH);
    }

    @Provide
    Arbitrary<Parts> loginParts() {
        return parts(LOGIN);
    }

    @Property(tries = 1000)
    void isGraphUri_trueExactlyForHttpsGraphHostWithoutUserinfoAndStandardPort(
            @ForAll("graphParts") Parts p) {
        assertEquals(p.expected(GRAPH), GraphEndpoints.isGraphUri(p.toUri()), p.toUri().toString());
    }

    @Property(tries = 1000)
    void isLoginUri_trueExactlyForHttpsLoginHostWithoutUserinfoAndStandardPort(
            @ForAll("loginParts") Parts p) {
        assertEquals(p.expected(LOGIN), GraphEndpoints.isLoginUri(p.toUri()), p.toUri().toString());
    }

    @Property(tries = 500)
    void graphAndLoginHostsNeverAcceptEachOthersUris(@ForAll("graphParts") Parts p) {
        URI u = p.toUri();
        assertFalse(GraphEndpoints.isGraphUri(u) && GraphEndpoints.isLoginUri(u));
    }

    @Test
    void attackPatternsAreRejected() {
        for (String s : new String[] {
                "https://graph.microsoft.com.evil.example/v1.0/me/calendarView",
                "https://x@graph.microsoft.com/v1.0/me/calendarView",
                "https://graph.microsoft.com@evil.example/v1.0/me/calendarView",
                "https://graph.microsoft.com:8443/v1.0/me/calendarView",
                "http://graph.microsoft.com/v1.0/me/calendarView",
                "https://evil.example/graph.microsoft.com",
                "//graph.microsoft.com/v1.0",
                "/v1.0/me/calendarView"}) {
            assertFalse(GraphEndpoints.isGraphUri(URI.create(s)), s);
        }
        for (String s : new String[] {
                "https://login.microsoftonline.com.evil.example/token",
                "https://x@login.microsoftonline.com/token",
                "https://login.microsoftonline.com@evil.example/token",
                "http://login.microsoftonline.com/token",
                "https://login.microsoftonline.com:444/token"}) {
            assertFalse(GraphEndpoints.isLoginUri(URI.create(s)), s);
        }
        assertFalse(GraphEndpoints.isGraphUri(null));
        assertFalse(GraphEndpoints.isLoginUri(null));
    }

    @Test
    void legitimateUrisAreAccepted() {
        assertTrue(GraphEndpoints.isGraphUri(URI.create(GraphEndpoints.CALENDAR_VIEW)));
        assertTrue(GraphEndpoints.isGraphUri(URI.create("HTTPS://GRAPH.MICROSOFT.COM:443/v1.0/x")));
        assertTrue(GraphEndpoints.isLoginUri(URI.create(GraphEndpoints.TOKEN_URL)));
        assertTrue(GraphEndpoints.isLoginUri(URI.create(GraphEndpoints.DEVICE_URL)));
    }
}
