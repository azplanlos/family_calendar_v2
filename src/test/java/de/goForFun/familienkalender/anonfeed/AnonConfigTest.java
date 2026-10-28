package de.goForFun.familienkalender.anonfeed;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Edge-Case-Tests für {@link AnonConfig#fromMap(Map)}.
 *
 * <p>Deckt die ENV-Auswertungs- und Default-Regeln ab:
 * <ul>
 *   <li>{@code ANON_PLACEHOLDER} fehlt/leer/&gt;255 → {@code "Privat"} (Requirement 2.6)</li>
 *   <li>{@code ANON_PARTICIPANT} getrimmt / fehlend / leer nach Trim / &gt;200 (Requirements 4.1, 4.4, 4.5)</li>
 *   <li>{@code ANON_SOURCE_FEED} fehlend / leer / nicht-http(s) / gültig (Requirements 1.3, 1.4)</li>
 *   <li>{@code ANON_API_SECRET} fehlend / leer / nicht-leer (Requirements 7.1, 7.3)</li>
 * </ul>
 */
class AnonConfigTest {

    /** Baut eine veränderbare Map; {@code null}-Werte werden nicht eingetragen (fehlender Schlüssel). */
    private static Map<String, String> env(String key, String value) {
        Map<String, String> m = new HashMap<>();
        if (value != null) {
            m.put(key, value);
        }
        return m;
    }

    @Nested
    class Placeholder {

        @Test
        void fehlend_verwendetDefault() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_PLACEHOLDER", null));
            assertThat(config.placeholder()).isEqualTo("Privat");
        }

        @Test
        void leer_verwendetDefault() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_PLACEHOLDER", ""));
            assertThat(config.placeholder()).isEqualTo("Privat");
        }

        @Test
        void laengerAls255_verwendetDefault() {
            String tooLong = "x".repeat(AnonConfig.MAX_PLACEHOLDER_LENGTH + 1);
            AnonConfig config = AnonConfig.fromMap(env("ANON_PLACEHOLDER", tooLong));
            assertThat(config.placeholder()).isEqualTo("Privat");
        }

        @Test
        void genau255_wirdUebernommen() {
            String exact = "x".repeat(AnonConfig.MAX_PLACEHOLDER_LENGTH);
            AnonConfig config = AnonConfig.fromMap(env("ANON_PLACEHOLDER", exact));
            assertThat(config.placeholder()).isEqualTo(exact);
        }

        @Test
        void gueltig_wirdUebernommen() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_PLACEHOLDER", "Belegt"));
            assertThat(config.placeholder()).isEqualTo("Belegt");
        }
    }

    @Nested
    class Participant {

        @Test
        void fehlend_calNameLeer() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_PARTICIPANT", null));
            assertThat(config.calName()).isEmpty();
        }

        @Test
        void leerNachTrim_calNameLeer() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_PARTICIPANT", "   "));
            assertThat(config.calName()).isEmpty();
        }

        @Test
        void leererString_calNameLeer() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_PARTICIPANT", ""));
            assertThat(config.calName()).isEmpty();
        }

        @Test
        void gueltig_wirdGetrimmtUebernommen() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_PARTICIPANT", "  Anna  "));
            assertThat(config.calName()).contains("Anna");
        }

        @Test
        void genau200NachTrim_wirdUebernommen() {
            String exact = "a".repeat(AnonConfig.MAX_CALNAME_LENGTH);
            AnonConfig config = AnonConfig.fromMap(env("ANON_PARTICIPANT", exact));
            assertThat(config.calName()).contains(exact);
        }

        @Test
        void laengerAls200NachTrim_calNameLeer() {
            String tooLong = "a".repeat(AnonConfig.MAX_CALNAME_LENGTH + 1);
            AnonConfig config = AnonConfig.fromMap(env("ANON_PARTICIPANT", tooLong));
            assertThat(config.calName()).isEmpty();
        }

        @Test
        void ueberlangMitLeerraum_aberGetrimmtGueltig_wirdUebernommen() {
            String padded = "   " + "a".repeat(AnonConfig.MAX_CALNAME_LENGTH) + "   ";
            AnonConfig config = AnonConfig.fromMap(env("ANON_PARTICIPANT", padded));
            assertThat(config.calName()).contains("a".repeat(AnonConfig.MAX_CALNAME_LENGTH));
        }
    }

    @Nested
    class SourceFeed {

        @Test
        void fehlend_sourceFeedNull() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_SOURCE_FEED", null));
            assertThat(config.sourceFeed()).isNull();
        }

        @Test
        void leer_sourceFeedNull() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_SOURCE_FEED", "   "));
            assertThat(config.sourceFeed()).isNull();
        }

        @Test
        void nichtHttp_sourceFeedNull() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_SOURCE_FEED", "ftp://example.com/feed.ics"));
            assertThat(config.sourceFeed()).isNull();
        }

        @Test
        void ohneSchema_sourceFeedNull() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_SOURCE_FEED", "example.com/feed.ics"));
            assertThat(config.sourceFeed()).isNull();
        }

        @Test
        void httpOhneHost_sourceFeedNull() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_SOURCE_FEED", "http:///feed.ics"));
            assertThat(config.sourceFeed()).isNull();
        }

        @Test
        void gueltigHttp_sourceFeedGesetzt() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_SOURCE_FEED", "http://example.com/feed.ics"));
            assertThat(config.sourceFeed()).isEqualTo(URI.create("http://example.com/feed.ics"));
        }

        @Test
        void gueltigHttps_sourceFeedGesetzt() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_SOURCE_FEED", "https://outlook.office365.com/owa/calendar/abc/calendar.ics"));
            assertThat(config.sourceFeed())
                    .isEqualTo(URI.create("https://outlook.office365.com/owa/calendar/abc/calendar.ics"));
        }

        @Test
        void gueltigMitLeerraum_wirdGetrimmt() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_SOURCE_FEED", "  https://example.com/feed.ics  "));
            assertThat(config.sourceFeed()).isEqualTo(URI.create("https://example.com/feed.ics"));
        }
    }

    @Nested
    class ApiSecret {

        @Test
        void fehlend_apiSecretLeer() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_API_SECRET", null));
            assertThat(config.apiSecret()).isEmpty();
        }

        @Test
        void leererString_apiSecretLeer() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_API_SECRET", ""));
            assertThat(config.apiSecret()).isEmpty();
        }

        @Test
        void leerNachTrim_apiSecretLeer() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_API_SECRET", "   "));
            assertThat(config.apiSecret()).isEmpty();
        }

        @Test
        void nichtLeer_apiSecretGetrimmtGesetzt() {
            AnonConfig config = AnonConfig.fromMap(env("ANON_API_SECRET", "  s3cr3t  "));
            assertThat(config.apiSecret()).contains("s3cr3t");
        }
    }

    @Test
    void leereMap_liefertDefaultsUndLeereOptionals() {
        AnonConfig config = AnonConfig.fromMap(new HashMap<>());
        assertThat(config.sourceFeed()).isNull();
        assertThat(config.placeholder()).isEqualTo("Privat");
        assertThat(config.calName()).isEmpty();
        assertThat(config.apiSecret()).isEmpty();
    }
}
