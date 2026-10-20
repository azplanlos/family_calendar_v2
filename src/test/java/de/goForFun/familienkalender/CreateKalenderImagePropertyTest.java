package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.Locale;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-Tests für die Aktivierungs-Logik in {@link CreateKalenderImage}.
 *
 * <p>Die zu prüfende Methode {@code parseHttpUri(String)} ist privat und statisch;
 * sie wird per Reflection zugänglich gemacht.</p>
 */
class CreateKalenderImagePropertyTest {

    private static final Method PARSE_HTTP_URI = resolveParseHttpUri();

    private static Method resolveParseHttpUri() {
        try {
            Method m = CreateKalenderImage.class.getDeclaredMethod("parseHttpUri", String.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError("parseHttpUri(String) nicht gefunden", e);
        }
    }

    /** Ruft die private statische parseHttpUri auf und liefert das Ergebnis (URI oder null). */
    private static URI parseHttpUri(String s) {
        try {
            return (URI) PARSE_HTTP_URI.invoke(null, s);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Aufruf von parseHttpUri warf eine Exception (Vertrag: wirft nie): " + s,
                    e.getCause() != null ? e.getCause() : e);
        }
    }

    /**
     * Unabhängiges Orakel für den Aktivierungsvertrag (R1.2/R1.3): eine Eingabe ist genau dann
     * eine gültige Feed-URL, wenn sie nicht-leer ist und nach {@code trim()} eine syntaktisch
     * gültige http/https-URL mit nicht-leerem Host ergibt. {@code URI.create} kann für
     * degenerierte Eingaben eine {@link IllegalArgumentException} werfen – dann ungültig.
     */
    private static boolean expectedValid(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        try {
            URI u = URI.create(s.trim());
            String scheme = u.getScheme();
            if (scheme == null) {
                return false;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            boolean httpScheme = scheme.equals("http") || scheme.equals("https");
            return httpScheme && u.getHost() != null && !u.getHost().isBlank();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Feature: wechselmodell-kalender, Property 1: parseHttpUri liefert URI gdw. gültige http/https-URL mit Host; sonst null (kein Custody-Fehler)
    @Property(tries = 100)
    void parseHttpUriReturnsUriIffValidHttpUrlWithHost(@ForAll("feedCandidates") String candidate) {
        URI result = parseHttpUri(candidate);
        boolean expected = expectedValid(candidate);

        // IFF: ein nicht-null Ergebnis (Feature aktiv) genau dann, wenn die Eingabe gültig ist.
        assertThat(result != null)
                .as("Aktivierung (nicht-null) muss der Gültigkeit entsprechen für Eingabe: %s", candidate)
                .isEqualTo(expected);

        if (result != null) {
            // Gültiger Zweig: Schema ist http/https (case-insensitive) und Host vorhanden.
            String scheme = result.getScheme();
            assertThat(scheme).isNotNull();
            assertThat(scheme.toLowerCase(Locale.ROOT)).isIn("http", "https");
            assertThat(result.getHost())
                    .as("gültige URI muss einen nicht-leeren Host haben")
                    .isNotNull().isNotBlank();
        }
        // result == null bedeutet Feature inaktiv: diese Property fokussiert den parseHttpUri-Vertrag,
        // der die Aktivierungsentscheidung steuert. null => kein Custody-Konfigurationsfehler (R1.3).
    }

    @Provide
    Arbitrary<String> feedCandidates() {
        return Arbitraries.oneOf(validHttpUrls(), invalidCandidates());
    }

    /** Gültige http/https-URLs mit Host (sollen eine nicht-null URI liefern). */
    @Provide
    Arbitrary<String> validHttpUrls() {
        // Schema in gemischtem Casing, um case-insensitive Behandlung zu prüfen.
        Arbitrary<String> schemes = Arbitraries.of(
                "http", "https", "HTTP", "HTTPS", "HtTp", "HttPS", "hTTpS");

        // Host-Label: Buchstaben/Ziffern, optional ein zweites Label (Domain).
        Arbitrary<String> hostLabel = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars("0123456789".toCharArray())
                .ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> hosts = Combinators.combine(hostLabel, hostLabel)
                .as((a, b) -> a + "." + b);

        // Optionaler Pfad.
        Arbitrary<String> paths = Arbitraries.of(
                "", "/", "/calendar.ics", "/feed/cal", "/a/b/c", "/custody-feed");

        return Combinators.combine(schemes, hosts, paths)
                .as((scheme, host, path) -> scheme + "://" + host + path);
    }

    /** Ungültige Kandidaten: leer/null, falsches Schema, kein Schema/Host, Zufallstext. */
    @Provide
    Arbitrary<String> invalidCandidates() {
        Arbitrary<String> fixed = Arbitraries.of(
                null,
                "",
                "   ",
                "\t\n",
                "ftp://example.com/feed",
                "mailto:someone@example.com",
                "file:///etc/hosts",
                "ws://example.com",
                "example.com",            // kein Schema
                "www.example.com/cal",    // kein Schema
                "/relative/path",         // relativer Pfad
                "./cal.ics",
                "../cal.ics",
                "http://",                // Schema aber kein Host
                "https://",
                "http:///pathonly",       // leerer Host
                "just some random text",
                "123456",
                ":://nonsense"
        );

        // Zufallstext ohne http(s)-Schema (praktisch sicher ungültig; das Orakel entscheidet verbindlich).
        Arbitrary<String> randomText = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withChars(" ./:-_".toCharArray())
                .ofMinLength(0).ofMaxLength(20);

        return Arbitraries.oneOf(fixed, randomText);
    }
}
