package de.goForFun.familienkalender.outlook.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import de.goForFun.familienkalender.outlook.FakeRefreshTokenStore;
import de.goForFun.familienkalender.outlook.GraphArbitraries;
import de.goForFun.familienkalender.outlook.MutableClock;
import de.goForFun.familienkalender.outlook.ScriptedGraphHttp;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Feature: outlook-graph-source, Property 31: Die Scope-Prüfung erkennt Calendars.Read.
 *
 * <p><b>Validates: Requirements 8.8, 8.9</b>
 */
class ScopeCheckPropertyTest {

    private static final String DEVICE_OK = "{\"device_code\":\"DEVCODE\",\"user_code\":\"ABCD-1234\","
            + "\"verification_uri\":\"https://login.microsoft.com/device\",\"expires_in\":900,\"interval\":5}";

    @Provide
    Arbitrary<String> scopeStrings() {
        return GraphArbitraries.scopeStringsArbitrary();
    }

    /** Orakel: ein Element ist (nach optionalem Graph-Präfix, case-insensitiv) genau Calendars.Read. */
    private static boolean expected(String scope) {
        return Arrays.stream(scope.trim().split("\\s+"))
                .map(s -> s.toLowerCase(Locale.ROOT))
                .map(s -> s.startsWith("https://graph.microsoft.com/")
                        ? s.substring("https://graph.microsoft.com/".length()) : s)
                .anyMatch("calendars.read"::equals);
    }

    @Property
    void includesCalendarsRead_matchesOracle(@ForAll("scopeStrings") String scope) {
        assertThat(ScopeCheck.includesCalendarsRead(scope)).isEqualTo(expected(scope));
    }

    @Property
    void offlineAccessIsNotRequired_andIrrelevant(@ForAll("scopeStrings") String scope) {
        // offline_access hinzuzufügen oder zu entfernen ändert das Ergebnis nicht.
        String without = String.join(" ", Arrays.stream(scope.trim().split("\\s+"))
                .filter(s -> !s.equals("offline_access")).toList());
        assertThat(ScopeCheck.includesCalendarsRead(scope + " offline_access"))
                .isEqualTo(ScopeCheck.includesCalendarsRead(without));
    }

    @Property
    void realResponseListingOnlyCalendarsRead_isAccepted(@ForAll("caseVariants") String variant) {
        assertThat(ScopeCheck.includesCalendarsRead(variant)).isTrue();
        assertThat(ScopeCheck.includesCalendarsRead("https://graph.microsoft.com/" + variant)).isTrue();
    }

    @Provide
    Arbitrary<String> caseVariants() {
        return Arbitraries.of("Calendars.Read", "calendars.read", "CALENDARS.READ", "cAlEnDaRs.ReAd");
    }

    @Property
    void nullEmptyAndForeignScopes_areRejected(@ForAll("foreignScopes") String scope) {
        assertThat(ScopeCheck.includesCalendarsRead(scope)).isFalse();
    }

    @Provide
    Arbitrary<String> foreignScopes() {
        Arbitrary<String> item = Arbitraries.of("offline_access", "openid", "profile", "User.Read",
                "Calendars.ReadWrite", "Calendars.Read.Shared", "Calendars.ReadBasic");
        return item.list().ofMaxSize(5).map(l -> String.join(" ", l));
    }

    @Property
    void nullScope_isRejected() {
        assertThat(ScopeCheck.includesCalendarsRead(null)).isFalse();
    }

    /** Fehlender/leerer refresh_token oder fehlender Scope ⇒ Abbruch ohne SSM-Write. */
    @Property(tries = 100)
    void run_writesOnlyIfRefreshTokenNonBlankAndScopeOk(
            @ForAll("scopeStrings") String scope,
            @ForAll("refreshTokens") String refreshToken) {
        boolean tokenOk = refreshToken != null && !refreshToken.isBlank();
        boolean scopeOk = expected(scope);

        ScriptedGraphHttp http = new ScriptedGraphHttp();
        FakeRefreshTokenStore store = new FakeRefreshTokenStore();
        MutableClock clock = new MutableClock(Instant.parse("2025-06-01T10:00:00Z"));
        String tokenJson = "{\"access_token\":\"ACC\","
                + (refreshToken == null ? "" : "\"refresh_token\":\"" + refreshToken + "\",")
                + "\"scope\":\"" + scope + "\",\"expires_in\":3600}";
        http.enqueue(200, DEVICE_OK).enqueue(200, tokenJson).enqueue(200, "{\"value\":[]}");

        int exit = GraphBootstrap.run(new String[] {"--client-id", "c", "--param", "/p"}, Map.of(), http, store,
                clock, d -> clock.advance(d),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));

        if (tokenOk && scopeOk) {
            assertThat(exit).isZero();
            assertThat(store.writes()).hasSize(1);
        } else {
            assertThat(exit).isNotZero();
            assertThat(store.writes()).isEmpty();
            assertThat(http.callCount()).isEqualTo(2); // keine Probe-Abfrage
        }
    }

    @Provide
    Arbitrary<String> refreshTokens() {
        return Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.just(""),
                Arbitraries.just("   "),
                Arbitraries.strings().alpha().numeric().ofMinLength(5).ofMaxLength(30));
    }
}
