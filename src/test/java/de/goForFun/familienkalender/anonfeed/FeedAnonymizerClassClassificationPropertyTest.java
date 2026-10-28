package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: anonymized-ical-feed, Property 6: CLASS-Klassifikation ist case-insensitiv und robust.
 *
 * <p>Für jeden {@code CLASS}-Wert gilt: Das Event wird durch
 * {@link FeedAnonymizer#isPrivate(VEvent)} genau dann als privat eingestuft, wenn der getrimmte
 * Wert ohne Berücksichtigung der Groß-/Kleinschreibung {@code private} oder {@code confidential}
 * ist; in allen anderen Fällen – fehlende, leere, {@code PUBLIC}, nur aus Leerraum bestehende
 * oder beliebige andere {@code CLASS}-Eigenschaft – wird das Event als nicht-privat eingestuft.
 *
 * <p>Die {@code net.jqwik.api.Property}-Annotation ist bewusst voll qualifiziert, um den
 * Namenskonflikt mit {@code net.fortuna.ical4j.model.Property} zu vermeiden.
 *
 * <p><strong>Validates: Requirements 2.4.</strong>
 */
class FeedAnonymizerClassClassificationPropertyTest {

    /**
     * Prüft die Äquivalenz {@code isPrivate(event) == (lowercase(trim(class)) ∈ {private,
     * confidential})} über zufällig gewürfelte CLASS-Varianten – inklusive Casing, umgebendem
     * Leerraum, fehlender/leerer Eigenschaft, {@code PUBLIC} und unbekannten Werten.
     */
    @net.jqwik.api.Property(tries = 200)
    void isPrivateMatchesTrimmedCaseInsensitiveClassValue(@ForAll("classVariants") String clazz) {
        VEvent event = buildSingleEvent(clazz);

        boolean expected = clazz != null
                && isPrivateKeyword(clazz.trim().toLowerCase(Locale.ROOT));

        assertThat(FeedAnonymizer.isPrivate(event))
                .as("isPrivate muss für CLASS=%s dem getrimmten, case-insensitiven Vergleich entsprechen",
                        clazz == null ? "<fehlend>" : "\"" + clazz + "\"")
                .isEqualTo(expected);
    }

    private static boolean isPrivateKeyword(String normalized) {
        return normalized.equals("private") || normalized.equals("confidential");
    }

    /**
     * CLASS-Varianten gemäß der P6-Menge: private/confidential in diversen Schreibweisen,
     * mit umgebendem Leerraum, sowie PUBLIC, leer, nur Leerraum, unbekannte Werte und
     * {@code null} (= CLASS fehlt vollständig).
     */
    @Provide
    Arbitrary<String> classVariants() {
        List<String> values = List.of(
                "PRIVATE", "private", "PrIvAtE", "  private  ", "\tprivate\t",
                "PRIVATE ", " private",
                "CONFIDENTIAL", "confidential", "CoNfIdEnTiAl", " Confidential ",
                "PUBLIC", "public", "Public", " PUBLIC ",
                "", "   ", "\t",
                "secret", "geheim", "x-personal", "privately", "confidentiality",
                "\u0000NULL\u0000"); // Platzhalter: wird auf "fehlend" (null) gemappt
        return Arbitraries.of(values.toArray(new String[0]))
                .map(v -> "\u0000NULL\u0000".equals(v) ? null : v);
    }

    /**
     * Baut ein einzelnes gültiges {@code VEVENT} mit (optionaler) {@code CLASS}-Property, indem
     * ein minimaler {@code VCALENDAR}-Text erzeugt und mit demselben Parser wie der Produktivpfad
     * eingelesen wird. Bei {@code clazz == null} wird keine {@code CLASS}-Zeile geschrieben.
     */
    private static VEvent buildSingleEvent(String clazz) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("PRODID:-//goForFun//p6-class-test//EN\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:p6-class-test@example.com\r\n");
        sb.append("DTSTAMP:20240101T000000Z\r\n");
        sb.append("DTSTART:20260101T090000Z\r\n");
        sb.append("DTEND:20260101T100000Z\r\n");
        sb.append("SUMMARY:Testtermin\r\n");
        if (clazz != null) {
            sb.append("CLASS:").append(clazz).append("\r\n");
        }
        sb.append("END:VEVENT\r\n");
        sb.append("END:VCALENDAR\r\n");

        Calendar calendar = parse(sb.toString());
        for (CalendarComponent component : calendar.<CalendarComponent>getComponents(Component.VEVENT)) {
            return (VEvent) component;
        }
        throw new IllegalStateException("Erzeugtes VCALENDAR enthielt kein VEVENT für CLASS=" + clazz);
    }

    /** Parst ICS-Text mit demselben {@link CalendarBuilder} wie der Produktivpfad. */
    private static Calendar parse(String ics) {
        try (InputStream in = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))) {
            return new CalendarBuilder().build(in);
        } catch (IOException | ParserException ex) {
            throw new IllegalStateException("Nicht-parsebares ICS:\n" + ics, ex);
        }
    }
}
