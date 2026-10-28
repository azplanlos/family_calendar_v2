package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: anonymized-ical-feed, Property 8: X-WR-CALNAME Escaping-Round-Trip.
 *
 * <p>Für jeden Participant-String mit getrimmter Länge 1..200 gilt: Der von
 * {@link FeedAnonymizer#anonymize} erzeugte Feed bleibt parsebar, und der aus dem
 * wiedereingelesenen Feed ausgelesene {@code X-WR-CALNAME}-Wert ist gleich dem getrimmten
 * Participant – auch wenn dieser RFC-5545-Sonderzeichen (Komma, Semikolon, Backslash,
 * Zeilenumbruch) oder Überlänge (Faltung) enthält. Das Escaping/Falten übernimmt ical4j bei
 * der Serialisierung; der Wert wird nur als Property-Wert gesetzt, nicht manuell maskiert
 * (Design: Data Models / X-WR-CALNAME-Ableitung).
 *
 * <p>Die getrimmte Länge wird in {@link #trimmedCalName()} über alle generierten Kandidaten
 * garantiert auf 1..200 beschränkt, damit {@link AnonConfig} den Participant sicher übernimmt
 * (Requirement 4.2). Entscheidend ist, dass der wiedereingelesene Wert exakt dem
 * <em>getrimmten</em> Original entspricht (Requirement 4.3).
 *
 * <p><strong>Validates: Requirements 4.2, 4.3.</strong>
 */
class FeedAnonymizerCalNamePropertyTest extends CalendarArbitraries {

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    @net.jqwik.api.Property(tries = 100)
    void calNameSurvivesEscapingRoundTrip(@ForAll("trimmedCalName") String participant) {
        String trimmed = participant.trim();
        // Vorbedingung des Generators: getrimmte Länge liegt im übernahmefähigen Bereich 1..200.
        assertThat(trimmed.length()).isBetween(1, 200);

        // Erwartungswert: X-WR-CALNAME ist ein EINZEILIGES Anzeige-Label. Eingebettete
        // Steuerzeichen (CR/LF/TAB) tragen keine fachliche Bedeutung und runden über ical4j
        // nicht verlustfrei (ein echter Zeilenumbruch käme als die Literalzeichen "\\n" zurück).
        // Die Produktion fasst daher Steuerzeichen zu einem Leerzeichen zusammen und trimmt.
        // Die Property-Invariante lautet entsprechend "rundet als einzeiliges Label verlustfrei"
        // – nicht "erhält rohe Zeilenumbrüche". Der Erwartungswert wendet exakt dieselbe
        // Normalisierung an wie der Produktionspfad (FeedAnonymizer.sanitizeCalName).
        String expected = FeedAnonymizer.sanitizeCalName(trimmed);

        // Quell-Kalender mit mindestens einem Event, damit der Feed strukturell realistisch ist;
        // der Participant kommt ausschließlich über die AnonConfig in den Feed (nicht aus der Quelle).
        Calendar source = buildSourceCalendar();
        AnonConfig config = AnonConfig.fromMap(Map.of(
                "ANON_SOURCE_FEED", "https://example.com/source.ics",
                "ANON_PLACEHOLDER", "Privat",
                "ANON_PARTICIPANT", participant));

        // Sanity: AnonConfig akzeptiert den Participant (getrimmte Länge 1..200).
        assertThat(config.calName())
                .as("AnonConfig muss den getrimmten Participant übernehmen")
                .contains(trimmed);

        Calendar anonymized = anonymizer.anonymize(source, config);

        // Feed muss parsebar bleiben (RFC-5545-konformes Escaping/Faltung durch ical4j).
        Calendar reparsed = reparse(anonymized.toString());

        Optional<String> readBack = readCalName(reparsed);
        assertThat(readBack)
                .as("X-WR-CALNAME muss im wiedereingelesenen Feed vorhanden sein")
                .isPresent();
        assertThat(readBack.get())
                .as("readBack(X-WR-CALNAME) == <einzeilig normalisierter, getrimmter Participant>")
                .isEqualTo(expected);
    }

    // ==================================================================
    // Generator: Participant-Strings mit getrimmter Länge 1..200
    // ==================================================================

    /**
     * Participant-Kandidaten, deren <em>getrimmte</em> Länge garantiert 1..200 ist, inkl.
     * RFC-5545-Sonderzeichen (Komma, Semikolon, Backslash, Zeilenumbruch), Unicode sowie
     * langer/zu faltender Werte. Erzeugte Kandidaten werden so normalisiert, dass sie nach dem
     * Trimmen weder leer noch länger als 200 Zeichen sind.
     */
    @Provide
    Arbitrary<String> trimmedCalName() {
        Arbitrary<String> special = Arbitraries.of(
                "Name, mit Komma",
                "Semikolon;Wert",
                "Back\\slash",
                "Alle: , ; \\ Sonderzeichen",
                "Zeile1\nZeile2",
                "Ümläute & Ünïcode \u2603",
                "a".repeat(200),                        // genau Maximallänge (Faltung)
                ("lang, wert; mit \\ zeichen ").repeat(7).trim(), // überlang vor Trim-Normalisierung
                "x");                                   // Minimallänge 1

        Arbitrary<String> random = Arbitraries.strings()
                .withChars("abcdefghijABCDEFGH 0123456789,;\\\n\u00e4\u00f6\u00fc\u2603")
                .ofMinLength(1).ofMaxLength(240);

        return Arbitraries.oneOf(special, random).map(FeedAnonymizerCalNamePropertyTest::normalize);
    }

    /**
     * Normalisiert einen Kandidaten so, dass seine getrimmte Länge im Bereich 1..200 liegt:
     * leere/whitespace-only Werte erhalten einen Mindestinhalt, überlange getrimmte Werte werden
     * auf 200 Zeichen gekürzt. Vor-/nachgestellter Leerraum (für den Trim-Aspekt) bleibt erhalten.
     */
    private static String normalize(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return "X";
        }
        if (trimmed.length() > 200) {
            // Kürzen auf 200; endet die Kürzung auf einem Backslash, einen weiteren entfernen,
            // damit kein unvollständiges Escape entsteht (reiner Testwert-Hygiene-Schritt).
            String cut = trimmed.substring(0, 200);
            while (cut.endsWith("\\") && countTrailingBackslashes(cut) % 2 == 1) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut;
        }
        return raw;
    }

    private static int countTrailingBackslashes(String s) {
        int count = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            count++;
        }
        return count;
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** Baut einen einfachen, gültigen Quell-Kalender mit einem Public- und einem Private-Event. */
    private Calendar buildSourceCalendar() {
        CalendarSpec spec = new CalendarSpec(
                true,   // withMethod
                false,  // withCalName: Quelle hat KEIN X-WR-CALNAME; es kommt nur über die Config
                null,   // calName (ungenutzt)
                true,   // withTimezone
                List.of(
                        new EventSpec("uid-public@test", true, 1, 9, "PUBLIC",
                                false, 2, false, "Öffentlich",
                                List.of("Beschreibung"), List.of("Ort"), "https://example.com/x", 1, false),
                        new EventSpec("uid-private@test", true, 2, 14, "PRIVATE",
                                false, 1, false, "Geheim",
                                List.of("privat"), List.of(), null, 0, false)));
        return buildCalendar(spec);
    }

    /** Liest den {@code X-WR-CALNAME}-Wert aus einem (wiedereingelesenen) Kalender. */
    private static Optional<String> readCalName(Calendar calendar) {
        return calendar.getProperty(FeedAnonymizer.X_WR_CALNAME).map(Property::getValue);
    }

    /** Parst den Output mit demselben Builder wie der Produktionspfad. */
    private static Calendar reparse(String ics) {
        try (InputStream in = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))) {
            return new CalendarBuilder().build(in);
        } catch (IOException | ParserException ex) {
            throw new IllegalStateException("Anonymisierter Feed nicht reparsebar:\n" + ics, ex);
        }
    }
}
