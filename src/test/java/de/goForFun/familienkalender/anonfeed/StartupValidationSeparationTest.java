package de.goForFun.familienkalender.anonfeed;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup-Validierungs- und Separations-Tests (Task 4.4).
 *
 * <p><b>Teil 1 – Startup-Validierung (Requirement 8.3):</b> Für jede fehlende/ungültige
 * Pflicht-ENV muss ein definierter Init-Fehler entstehen, der die betroffene Variable benennt.
 * Unter der im Design festgelegten R7.3/R8.3-Auslegung dürfen {@code ANON_API_SECRET} und
 * {@code ANON_PARTICIPANT} leer sein (leer = gültiger Zustand gemäß 7.3/4.4); {@code ANON_PLACEHOLDER}
 * besitzt einen Default. Harte Pflicht ist daher allein ein gültiges {@code ANON_SOURCE_FEED}.
 * Geprüft wird {@link AnonConfig#validateRequired()}.
 *
 * <p><b>Teil 2 – Separation (Requirements 5.2, 5.3):</b> {@link FeedAnonymizer} und
 * {@link ServeAnonymizedFeed} dürfen den S3-Pfad nicht berühren. Der Nachweis erfolgt
 * umgebungsunabhängig über den Konstanten-Pool der kompilierten {@code .class}-Dateien: keine der
 * beiden Klassen darf einen Typ aus {@code software.amazon.awssdk.services.s3} (insb.
 * {@code S3Client}) referenzieren. Jeder Typbezug einer Klasse erscheint als UTF-8-Konstante im
 * Konstanten-Pool, sodass eine spätere Verdrahtung von S3 diesen Test zuverlässig rot werden ließe.
 */
class StartupValidationSeparationTest {

    // ----------------------------------------------------------------------
    // Teil 1: Startup-Validierung (Requirement 8.3, R7.3/R8.3-Auslegung)
    // ----------------------------------------------------------------------

    @Nested
    class StartupValidation {

        private static Map<String, String> validEnv() {
            Map<String, String> env = new HashMap<>();
            env.put("ANON_SOURCE_FEED", "https://outlook.example.com/owa/calendar/abc/calendar.ics");
            env.put("ANON_PLACEHOLDER", "Privat");
            env.put("ANON_PARTICIPANT", "Anna");
            env.put("ANON_API_SECRET", "s3cr3t");
            return env;
        }

        @Test
        void vollstaendigeKonfiguration_keinFehler() {
            AnonConfig config = AnonConfig.fromMap(validEnv());
            assertThatCode(config::validateRequired).doesNotThrowAnyException();
        }

        @Test
        void fehlendesSourceFeed_initFehlerBenenntVariable() {
            Map<String, String> env = validEnv();
            env.remove("ANON_SOURCE_FEED");
            AnonConfig config = AnonConfig.fromMap(env);

            assertThatThrownBy(config::validateRequired)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ANON_SOURCE_FEED");
        }

        @Test
        void leeresSourceFeed_initFehlerBenenntVariable() {
            Map<String, String> env = validEnv();
            env.put("ANON_SOURCE_FEED", "   ");
            AnonConfig config = AnonConfig.fromMap(env);

            assertThatThrownBy(config::validateRequired)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ANON_SOURCE_FEED");
        }

        @Test
        void ungueltigesSourceFeed_initFehlerBenenntVariable() {
            Map<String, String> env = validEnv();
            env.put("ANON_SOURCE_FEED", "ftp://example.com/feed.ics");
            AnonConfig config = AnonConfig.fromMap(env);

            assertThatThrownBy(config::validateRequired)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ANON_SOURCE_FEED");
        }

        @Test
        void fehlendesApiSecret_keinFehler_darfLeerSein() {
            // R7.3-Auslegung: leeres/fehlendes ANON_API_SECRET = offener Zugriff, gültiger Zustand.
            Map<String, String> env = validEnv();
            env.remove("ANON_API_SECRET");
            AnonConfig config = AnonConfig.fromMap(env);

            assertThatCode(config::validateRequired).doesNotThrowAnyException();
        }

        @Test
        void leeresApiSecret_keinFehler_darfLeerSein() {
            Map<String, String> env = validEnv();
            env.put("ANON_API_SECRET", "   ");
            AnonConfig config = AnonConfig.fromMap(env);

            assertThatCode(config::validateRequired).doesNotThrowAnyException();
        }

        @Test
        void fehlenderParticipant_keinFehler_darfLeerSein() {
            // R4.4-Auslegung: fehlendes ANON_PARTICIPANT = kein X-WR-CALNAME, gültiger Zustand.
            Map<String, String> env = validEnv();
            env.remove("ANON_PARTICIPANT");
            AnonConfig config = AnonConfig.fromMap(env);

            assertThatCode(config::validateRequired).doesNotThrowAnyException();
        }

        @Test
        void fehlenderPlaceholder_keinFehler_defaultGreift() {
            // ANON_PLACEHOLDER hat Default "Privat" → nicht hart erforderlich.
            Map<String, String> env = validEnv();
            env.remove("ANON_PLACEHOLDER");
            AnonConfig config = AnonConfig.fromMap(env);

            assertThatCode(config::validateRequired).doesNotThrowAnyException();
        }
    }

    // ----------------------------------------------------------------------
    // Teil 2: Separation vom S3-/Device-Pfad (Requirements 5.2, 5.3)
    // ----------------------------------------------------------------------

    @Nested
    class S3Separation {

        /** Interner Klassenname (Slash-Notation) des S3-SDK-Pakets, wie er im Konstanten-Pool steht. */
        private static final String S3_PACKAGE_INTERNAL = "software/amazon/awssdk/services/s3";

        /** Dotted-Variante als zusätzliche Sicherheitsnetz-Prüfung. */
        private static final String S3_PACKAGE_DOTTED = "software.amazon.awssdk.services.s3";

        @Test
        void feedAnonymizer_referenziertKeinS3() throws IOException {
            assertNoS3Reference(FeedAnonymizer.class);
        }

        @Test
        void serveAnonymizedFeed_referenziertKeinS3() throws IOException {
            assertNoS3Reference(ServeAnonymizedFeed.class);
        }

        @Test
        void kontrolle_deviceKlasseReferenziertS3() throws Exception {
            // Positivkontrolle: Die Scan-Methode erkennt S3 tatsächlich – verhindert einen
            // falsch-grünen Test, falls der Konstanten-Pool-Scan selbst kaputt wäre.
            Class<?> deviceClass =
                    Class.forName("de.goForFun.familienkalender.CreateKalenderImage");
            byte[] bytecode = readClassBytes(deviceClass);
            String poolText = new String(bytecode, StandardCharsets.ISO_8859_1);
            assertThat(poolText)
                    .as("Device-Pfad-Klasse CreateKalenderImage muss S3 referenzieren (Positivkontrolle)")
                    .contains(S3_PACKAGE_INTERNAL);
        }

        /**
         * Scannt den Bytecode (Konstanten-Pool) der Klasse und stellt sicher, dass kein Typ aus
         * dem S3-SDK-Paket referenziert wird. Jeder im Klassenbytecode referenzierte Typname liegt
         * als UTF-8-Konstante vor; eine spätere Einbindung von {@code S3Client} o.Ä. ließe diesen
         * Test fehlschlagen.
         */
        private void assertNoS3Reference(Class<?> clazz) throws IOException {
            byte[] bytecode = readClassBytes(clazz);
            // ISO-8859-1 bildet jedes Byte 1:1 auf ein Zeichen ab – geeignet für einen Substring-Scan
            // des Konstanten-Pools (UTF-8-Konstanten mit ASCII-Typnamen).
            String poolText = new String(bytecode, StandardCharsets.ISO_8859_1);
            assertThat(poolText)
                    .as("%s darf keinen S3-SDK-Typ referenzieren (keine S3-Nutzung, Requirement 5.2/5.3)",
                            clazz.getName())
                    .doesNotContain(S3_PACKAGE_INTERNAL)
                    .doesNotContain(S3_PACKAGE_DOTTED);
        }

        /** Liest die kompilierte {@code .class}-Datei der Klasse als Byte-Array vom Classpath. */
        private byte[] readClassBytes(Class<?> clazz) throws IOException {
            String resource = clazz.getName().replace('.', '/') + ".class";
            URL url = clazz.getClassLoader().getResource(resource);
            assertThat(url)
                    .as("kompilierte .class-Datei für %s muss auf dem Classpath liegen", clazz.getName())
                    .isNotNull();
            try {
                Path path = Path.of(URI.create(url.toString()));
                return Files.readAllBytes(path);
            } catch (RuntimeException e) {
                // Fallback: Ressource als Stream lesen (z. B. wenn URL kein file:-Schema trägt).
                try (var in = clazz.getClassLoader().getResourceAsStream(resource)) {
                    assertThat(in).as("Stream für %s", resource).isNotNull();
                    return in.readAllBytes();
                }
            }
        }
    }
}
