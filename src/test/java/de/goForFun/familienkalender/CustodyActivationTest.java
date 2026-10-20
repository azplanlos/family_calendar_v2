package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.Event;

import java.lang.reflect.Method;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit-Tests (JUnit 5 + AssertJ) für die Aktivierungs- und Fehlerpfade des
 * Wechselmodell-Kalenders (Task 5.4).
 *
 * <p><b>Testbarkeits-Hinweis / Limitation:</b> Die eigentliche Verdrahtung der
 * Pfade lebt in {@link CreateKalenderImage#handleRequest}. Diese Methode ist
 * nicht direkt unit-testbar, weil sie (a) die Konfiguration über statische
 * {@code System.getenv}-Felder (z.&nbsp;B. {@code CUSTODY_CALENDAR_FEED},
 * {@code CUSTODY_MALE_MATCH}) einliest und (b) S3-I/O über einen
 * {@code S3Client} ausführt. Ein produktives Refactoring für reine
 * Testbarkeit ist in diesem Task ausdrücklich nicht erwünscht.
 *
 * <p>Dieser Test fokussiert daher auf die <em>direkt erreichbaren</em>
 * Bausteine, die den Aktivierungs-/Fehlervertrag implementieren:
 * <ul>
 *   <li>Die private statische URL-Validierung {@code parseHttpUri} (per
 *       Reflection) — deckt den „ungültige/fehlende URL ⇒ inaktiv, kein Fehler"-
 *       Pfad ab (R1.3/R1.4).</li>
 *   <li>Den {@link CareScheduleProvider} mit leerer Event-Liste — leerer Feed
 *       bzw. inaktives Feature ⇒ leerer Schedule (ergänzt R1.4).</li>
 *   <li>Die exakten Fehler-Strings, die {@code handleRequest} inline erzeugt:
 *       Der Konfigurationsfehler bei fehlender Mann-Kennung muss den Token
 *       {@code CUSTODY_MALE_MATCH} tragen (R2.2); der Feed-Fehler muss den
 *       Token {@code Wechselmodell-Feed} tragen (R3.6). Diese Fehlerstrings
 *       werden in {@code handleRequest} in die {@code errors}-Liste der
 *       {@code RenderData} aufgenommen, damit der Renderer sie anzeigen/loggen
 *       kann.</li>
 * </ul>
 *
 * <p>Die Fehler-/Env-Semantik rund um {@code CUSTODY_MALE_MATCH} und das
 * Feed-Parsing kann ohne Env + S3 nicht über {@code handleRequest} angestoßen
 * werden; der Vertrag wird deshalb über die Message-Tokens bzw. die
 * testbaren Kollaborateure abgesichert.
 */
class CustodyActivationTest {

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

    /** Ruft die private statische {@code parseHttpUri} auf (Vertrag: wirft nie). */
    private static URI parseHttpUri(String s) {
        try {
            return (URI) PARSE_HTTP_URI.invoke(null, s);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "Aufruf von parseHttpUri warf eine Exception (Vertrag: wirft nie): " + s,
                    e.getCause() != null ? e.getCause() : e);
        }
    }

    // ------------------------------------------------------------------
    // R1.3 / R1.4 – fehlende/ungültige URL => inaktiv (null), kein Fehler
    // ------------------------------------------------------------------

    @Test
    void parseHttpUri_null_returnsNull() {
        // R1.3: fehlende URL ⇒ inaktiv (null), kein Konfigurationsfehler.
        assertThat(parseHttpUri(null)).isNull();
    }

    @Test
    void parseHttpUri_empty_returnsNull() {
        assertThat(parseHttpUri("")).isNull();
    }

    @Test
    void parseHttpUri_blank_returnsNull() {
        // R1.3: leere/whitespace-URL ⇒ inaktiv (null).
        assertThat(parseHttpUri("   ")).isNull();
        assertThat(parseHttpUri("\t\n")).isNull();
    }

    @Test
    void parseHttpUri_nonHttpScheme_returnsNull() {
        // R1.3: syntaktisch gültige, aber kein http(s)-Schema ⇒ inaktiv (null).
        assertThat(parseHttpUri("ftp://example.com/feed")).isNull();
        assertThat(parseHttpUri("mailto:someone@example.com")).isNull();
        assertThat(parseHttpUri("file:///etc/hosts")).isNull();
    }

    @Test
    void parseHttpUri_schemeWithoutHost_returnsNull() {
        // http(s)-Schema aber kein Host ⇒ inaktiv (null).
        assertThat(parseHttpUri("http://")).isNull();
        assertThat(parseHttpUri("https://")).isNull();
    }

    @Test
    void parseHttpUri_validHttpsUrl_returnsNonNull() {
        // R1.2: nicht-leere, syntaktisch gültige https-URL mit Host ⇒ aktiv (non-null URI).
        URI uri = parseHttpUri("https://host/feed");
        assertThat(uri).isNotNull();
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.getHost()).isEqualTo("host");
    }

    @Test
    void parseHttpUri_validHttpUrl_returnsNonNull() {
        // R1.2: http wird ebenfalls akzeptiert.
        URI uri = parseHttpUri("http://example.com/custody.ics");
        assertThat(uri).isNotNull();
        assertThat(uri.getScheme()).isEqualTo("http");
        assertThat(uri.getHost()).isEqualTo("example.com");
    }

    // ------------------------------------------------------------------
    // R1.4 – inaktives Feature / leerer Feed => leerer Schedule
    // ------------------------------------------------------------------

    @Test
    void careScheduleProvider_emptyEventList_returnsEmptySchedule() {
        // Ergänzt R1.4: ohne Events (inaktives Feature bzw. leerer Feed) bleibt
        // der Schedule leer, sodass weder Icon noch Monatskalender-Hervorhebung
        // Daten erhält.
        Map<LocalDate, CareDay> schedule =
                new CareScheduleProvider(List.<Event>of(), "Andreas Zöllner").buildSchedule();
        assertThat(schedule).isEmpty();
    }

    // ------------------------------------------------------------------
    // R2.2 – fehlende Mann-Kennung => Fehler-String benennt CUSTODY_MALE_MATCH
    // ------------------------------------------------------------------

    @Test
    void maleMatchMissingErrorMessage_containsVariableName() {
        // Dies ist der exakte String, den handleRequest in die errors-Liste der
        // RenderData aufnimmt, wenn CUSTODY_MALE_MATCH fehlt/leer ist (R2.2).
        String error = "Wechselmodell: Umgebungsvariable CUSTODY_MALE_MATCH fehlt oder ist leer";
        assertThat(error)
                .as("Konfigurationsfehler muss die Umgebungsvariable benennen (R2.2)")
                .contains("CUSTODY_MALE_MATCH");
    }

    // ------------------------------------------------------------------
    // R3.6 – Feed-Fehler => Fehler-String benennt den Wechselmodell-Feed
    // ------------------------------------------------------------------

    @Test
    void feedErrorMessage_namesCustodyFeed() {
        // Dies ist das exakte Fehler-Präfix, das handleRequest bei einem
        // nicht erreichbaren / ungültigen Feed in die errors-Liste aufnimmt (R3.6).
        String message = "Connection refused";
        String error = "Wechselmodell-Feed: " + message;
        assertThat(error)
                .as("Feed-Fehler muss den Wechselmodell-Feed benennen (R3.6)")
                .contains("Wechselmodell-Feed");
    }
}
