package de.goForFun.familienkalender.outlook;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * **Feature: outlook-graph-source, Property 8: Token-Request enthält genau die vorgeschriebenen Formfelder**
 *
 * <p><b>Validates: Requirements 3.2, 3.3, 9.1</b>
 */
class GraphTokenRequestPropertyTest {

    private static final List<String> PIECES = List.of(
            "&", "=", "+", "%", " ", "%20", "%2B", "&client_secret=x", "=&", "/", "?", "#", "~", "*",
            "ä", "ö", "ü", "ß", "€", "日本", "😀", "\n", "\t", "a", "Z", "0", "9", "-", "_", ".");

    @Provide
    Arbitrary<String> anyValue() {
        return Arbitraries.oneOf(
                Arbitraries.of(PIECES).list().ofMaxSize(30).map(l -> String.join("", l)),
                Arbitraries.strings().alpha().numeric().ofMaxLength(40));
    }

    private static Map<String, String> decode(String body) {
        Map<String, String> fields = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        for (String pair : body.split("&", -1)) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            keys.add(key);
            fields.put(key, URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        assertThat(keys).as("keine doppelten Schlüssel").hasSameSizeAs(fields.keySet());
        return fields;
    }

    @Property(tries = 300)
    void bodyContainsExactlyPrescribedFieldsAndRoundTrips(
            @ForAll("anyValue") String clientId, @ForAll("anyValue") String refreshToken) {
        String body = GraphTokenService.tokenRequestBody(clientId, refreshToken);

        assertThat(body).doesNotContain(" ").doesNotContain("\n");
        Map<String, String> fields = decode(body);

        assertThat(fields.keySet())
                .containsExactlyInAnyOrder("client_id", "grant_type", "refresh_token", "scope")
                .doesNotContain("client_secret");
        assertThat(fields.get("client_id")).isEqualTo(clientId);
        assertThat(fields.get("refresh_token")).isEqualTo(refreshToken);
        assertThat(fields.get("grant_type")).isEqualTo("refresh_token");
        assertThat(fields.get("scope")).isEqualTo("Calendars.Read offline_access");
    }
}
