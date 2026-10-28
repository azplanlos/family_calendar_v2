package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Provide;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Geteilte jqwik-{@link Provide}-Generatoren für zufällige {@code VCALENDAR}-Eingaben.
 *
 * <p>Diese Klasse ist die gemeinsame Testoberfläche der acht Property-Tests (Tasks 2.3–2.10)
 * zum Feature <em>anonymized-ical-feed</em>. Ein {@link Provide}-Generator liefert echte
 * {@link net.fortuna.ical4j.model.Calendar}-Objekte des ical4j-Modells.
 *
 * <h2>Erzeugungsstrategie</h2>
 * Statt die (über ical4j 4.x hinweg wechselhaften) Property-Konstruktoren direkt aufzurufen,
 * baut der Generator gültigen RFC-5545-Text (`VCALENDAR` mit optionaler `VTIMEZONE` und 0..n
 * `VEVENT`) und parst ihn mit demselben {@link CalendarBuilder}, den auch {@code IcalParser}
 * und der {@code FeedAnonymizer}-Pfad verwenden. Dadurch
 * <ul>
 *   <li>entstehen immer wirklich parsebare {@code Calendar}-Modellobjekte,</li>
 *   <li>werden `TZID`-Referenzen, Zeitfelder, Recurrence- und Inhaltsfelder RFC-konform
 *       kodiert/gefaltet,</li>
 *   <li>lassen sich Outlook-Eigenheiten (leere Felder, Mehrfach-Properties, Unicode) direkt
 *       in den Text einstreuen.</li>
 * </ul>
 *
 * <h2>Abgedeckte Variationen (Design: Testing Strategy / Generator)</h2>
 * <ul>
 *   <li>Grundgerüst: {@code PRODID}, {@code VERSION}, optional {@code METHOD:PUBLISH},
 *       optional {@code X-WR-CALNAME}.</li>
 *   <li>0..n {@code VEVENT} mit garantiert gültiger {@code UID}/{@code DTSTART}.</li>
 *   <li>timed (mit {@code TZID}-Referenz auf eine erzeugte {@code VTIMEZONE} „Romance Standard
 *       Time") vs. all-day; {@code DTEND} vs. {@code DURATION}; optionale Recurrence-Felder.</li>
 *   <li>zufällige {@code CLASS} aus der P6-Menge; zufällige Inhaltsfelder (auch leer/mehrfach)
 *       mit Unicode/Sonderzeichen.</li>
 *   <li>gezielte Varianten für Events ohne {@code UID}/{@code DTSTART}.</li>
 *   <li>die reale Fixture {@code src/test/resources/outlook-published-sample.ics} als „Seed".</li>
 * </ul>
 *
 * <h2>Verwendung</h2>
 * Die acht Property-Testklassen (Tasks 2.3–2.10) <em>erweitern</em> diese Klasse und
 * referenzieren die Generatoren per {@code @ForAll("randomCalendars")} bzw.
 * {@code @ForAll("calendarSpecs")}. jqwik löst {@link Provide}-Methoden auch aus der
 * Oberklasse auf, sodass die Generatoren nur hier gepflegt werden müssen.
 *
 * <p>Requirements: 1.1, 2.4, 6.1.
 */
public abstract class CalendarArbitraries {

    /** TZID der erzeugten {@code VTIMEZONE} (wie in der Outlook-Fixture). */
    static final String TZID = "Romance Standard Time";

    /** Pfad der realen Outlook-Fixture (Classpath), die als Seed eingespeist wird. */
    static final String FIXTURE_RESOURCE = "/outlook-published-sample.ics";

    /** CLASS-Varianten gemäß P6-Menge (inkl. Casing, Leerraum, unbekannt; {@code null} = fehlend). */
    static final List<String> CLASS_VALUES = List.of(
            "PRIVATE", "private", "PrIvAtE", "  private  ",
            "CONFIDENTIAL", "confidential", " Confidential ",
            "PUBLIC", "public", "", "   ", "secret", "geheim",
            "\u0000NULL\u0000" // Platzhalter, wird unten auf "fehlend" gemappt
    );

    /** Gültige {@code VTIMEZONE}-Komponente „Romance Standard Time" (wie Outlook sie liefert). */
    private static final String VTIMEZONE_BLOCK = String.join("\r\n",
            "BEGIN:VTIMEZONE",
            "TZID:" + TZID,
            "BEGIN:STANDARD",
            "DTSTART:16010101T030000",
            "TZOFFSETFROM:+0200",
            "TZOFFSETTO:+0100",
            "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=-1SU;BYMONTH=10",
            "END:STANDARD",
            "BEGIN:DAYLIGHT",
            "DTSTART:16010101T020000",
            "TZOFFSETFROM:+0100",
            "TZOFFSETTO:+0200",
            "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=-1SU;BYMONTH=3",
            "END:DAYLIGHT",
            "END:VTIMEZONE");

    protected CalendarArbitraries() {
        // Basis für die erweiternden Property-Testklassen.
    }

    // ==================================================================
    // Öffentliche Generatoren
    // ==================================================================

    /**
     * Haupt-Generator: zufällige, parsebare {@link Calendar}-Objekte mit 0..n {@code VEVENT}
     * (gemischt privat/öffentlich), optionaler {@code VTIMEZONE}, variierenden Zeit- und
     * Inhaltsfeldern sowie – in einem Teil der Fälle – der realen Outlook-Fixture als Seed.
     */
    @Provide
    Arbitrary<Calendar> randomCalendars() {
        Arbitrary<Calendar> generated = calendarSpecs().map(CalendarArbitraries::buildCalendar);
        // In einem Teil der Fälle die reale Fixture als Seed einspeisen (Outlook-Eigenheiten).
        Arbitrary<Calendar> seeded = Arbitraries.just(loadFixture());
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(5, generated),
                net.jqwik.api.Tuple.of(1, seeded));
    }

    // ==================================================================
    // Spec-Modell (deterministisch nachvollziehbar in den Property-Tests)
    // ==================================================================

    /**
     * Beschreibt einen zu erzeugenden Kalender rein datenseitig. Die Property-Tests können aus
     * dem Spec die erwarteten Invarianten (UID-Menge, Zeitfelder, Privacy-Zustand …) unabhängig
     * vom erzeugten {@code Calendar} ableiten.
     */
    record CalendarSpec(boolean withMethod, boolean withCalName, String calName,
                        boolean withTimezone, List<EventSpec> events) {
    }

    /** Beschreibung eines {@code VEVENT}. */
    record EventSpec(
            String uid,            // null ⇒ kein UID
            boolean timed,         // true: DTSTART mit Uhrzeit (TZID), false: all-day DATE
            int startOffsetDays,   // Tagesoffset ab Basisdatum
            int startHour,         // nur relevant bei timed
            String clazz,          // null ⇒ keine CLASS
            boolean useDuration,   // true: DURATION statt DTEND
            int durationHours,     // Länge bei timed-DTEND/DURATION
            boolean withRecurrence,// RRULE anhängen
            String summary,        // SUMMARY-Wert
            List<String> descriptions, // 0..n DESCRIPTION (auch leer)
            List<String> locations,    // 0..n LOCATION (auch leer)
            String url,            // null ⇒ keine URL
            int attendeeCount,     // 0..n ATTENDEE
            boolean omitDtStart    // gezielt ohne DTSTART (ungültiges Event)
    ) {
        boolean isPrivate() {
            if (clazz == null) {
                return false;
            }
            String n = clazz.trim().toLowerCase(java.util.Locale.ROOT);
            return n.equals("private") || n.equals("confidential");
        }

        boolean valid() {
            return uid != null && !uid.isBlank() && !omitDtStart;
        }
    }

    // ==================================================================
    // Spec-Generatoren
    // ==================================================================

    @Provide
    Arbitrary<CalendarSpec> calendarSpecs() {
        Arbitrary<Boolean> method = Arbitraries.of(true, false);
        Arbitrary<Boolean> withCalName = Arbitraries.of(true, false);
        Arbitrary<String> calName = calNameArbitrary();
        Arbitrary<Boolean> withTz = Arbitraries.of(true, false);
        Arbitrary<List<EventSpec>> events = eventSpec().list().ofMinSize(0).ofMaxSize(6);

        return Combinators.combine(method, withCalName, calName, withTz, events)
                .as(CalendarSpec::new);
    }

    @Provide
    Arbitrary<EventSpec> eventSpec() {
        Arbitrary<String> uid = Arbitraries.oneOf(
                Arbitraries.strings().withChars("0123456789ABCDEFabcdef-@.").ofMinLength(5).ofMaxLength(40),
                Arbitraries.just((String) null)); // selten: fehlende UID (ungültig)
        Arbitrary<Boolean> timed = Arbitraries.of(true, false);
        Arbitrary<Integer> startOffset = Arbitraries.integers().between(0, 400);
        Arbitrary<Integer> startHour = Arbitraries.integers().between(0, 23);
        Arbitrary<String> clazz = classArbitrary();
        Arbitrary<Boolean> useDuration = Arbitraries.of(true, false);
        Arbitrary<Integer> durationHours = Arbitraries.integers().between(1, 48);
        Arbitrary<Boolean> recurrence = Arbitraries.of(true, false);
        Arbitrary<String> summary = contentValue();
        Arbitrary<List<String>> descriptions = contentValue().list().ofMinSize(0).ofMaxSize(2);
        Arbitrary<List<String>> locations = contentValue().list().ofMinSize(0).ofMaxSize(2);
        Arbitrary<String> url = Arbitraries.oneOf(
                Arbitraries.of("https://example.com/x", "http://host/a?b=c", "https://ümlaut.example/ü"),
                Arbitraries.just((String) null));
        Arbitrary<Integer> attendees = Arbitraries.integers().between(0, 3);
        // omitDtStart selten; meist false, sodass die meisten Events gültig sind.
        Arbitrary<Boolean> omitDtStart = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(9, Arbitraries.just(false)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(true)));

        return Combinators.combine(
                        Combinators.combine(uid, timed, startOffset, startHour).as(EventHead::new),
                        clazz, useDuration, durationHours, recurrence,
                        Combinators.combine(summary, descriptions, locations, url, attendees).as(EventBody::new),
                        omitDtStart)
                .as((head, cls, useDur, durH, rec, body, omit) -> new EventSpec(
                        head.uid, head.timed, head.startOffset, head.startHour,
                        cls, useDur, durH, rec,
                        body.summary, body.descriptions, body.locations, body.url, body.attendees,
                        omit));
    }

    /** Hilfs-Tupel, um die 8-Argument-Grenze von {@link Combinators} zu umgehen. */
    private record EventHead(String uid, boolean timed, int startOffset, int startHour) {
    }

    private record EventBody(String summary, List<String> descriptions, List<String> locations,
                             String url, int attendees) {
    }

    @Provide
    Arbitrary<String> classArbitrary() {
        return Arbitraries.of(CLASS_VALUES.toArray(new String[0]))
                .map(v -> "\u0000NULL\u0000".equals(v) ? null : v);
    }

    /** Participant-Werte inkl. RFC-5545-Sonderzeichen und Unicode (getrimmt 1..200). */
    @Provide
    Arbitrary<String> calNameArbitrary() {
        return Arbitraries.of(
                "Kalender",
                "Papa",
                "Name, mit Komma",
                "Semikolon;Wert",
                "Back\\slash",
                "Ümläute & Ünïcode \u2603",
                "Lang".repeat(40)); // 160 Zeichen
    }

    // ==================================================================
    // Textbausteine
    // ==================================================================

    /** Inhaltswert mit Unicode/Sonderzeichen oder leer. */
    private Arbitrary<String> contentValue() {
        return Arbitraries.oneOf(
                Arbitraries.just(""),
                Arbitraries.of(
                        "Normaler Text",
                        "Komma, Semikolon; Backslash \\ Ende",
                        "Zeile1\nZeile2",
                        "Unicode \u2603 \u00e4\u00f6\u00fc\u00df",
                        "Emoji \uD83D\uDE00 Text",
                        "   Leerraum   "),
                Arbitraries.strings().ofMinLength(1).ofMaxLength(30));
    }

    // ==================================================================
    // Calendar-Bau (Spec -> ICS-Text -> parse)
    // ==================================================================

    static Calendar buildCalendar(CalendarSpec spec) {
        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        if (spec.withMethod()) {
            line(sb, "METHOD:PUBLISH");
        }
        line(sb, "PRODID:-//goForFun//anonymized-ical-feed test generator//EN");
        line(sb, "VERSION:2.0");
        if (spec.withCalName()) {
            line(sb, "X-WR-CALNAME:" + escapeText(spec.calName()));
        }
        boolean needsTimezone = spec.withTimezone()
                || spec.events().stream().anyMatch(e -> e.timed() && !e.omitDtStart());
        if (needsTimezone) {
            sb.append(VTIMEZONE_BLOCK).append("\r\n");
        }
        for (EventSpec e : spec.events()) {
            appendEvent(sb, e);
        }
        line(sb, "END:VCALENDAR");
        return parse(sb.toString());
    }

    private static void appendEvent(StringBuilder sb, EventSpec e) {
        line(sb, "BEGIN:VEVENT");
        if (e.uid() != null) {
            line(sb, "UID:" + e.uid());
        }
        // DTSTAMP ist für gültige VEVENTs üblich; hilft dem strikten Parser nicht zu meckern.
        line(sb, "DTSTAMP:20240101T000000Z");

        if (!e.omitDtStart()) {
            if (e.timed()) {
                String start = dateTime(e.startOffsetDays(), e.startHour());
                line(sb, "DTSTART;TZID=" + TZID + ":" + start);
                if (e.useDuration()) {
                    line(sb, "DURATION:PT" + e.durationHours() + "H");
                } else {
                    String end = dateTimePlusHours(e.startOffsetDays(), e.startHour(), e.durationHours());
                    line(sb, "DTEND;TZID=" + TZID + ":" + end);
                }
            } else {
                String startDate = date(e.startOffsetDays());
                line(sb, "DTSTART;VALUE=DATE:" + startDate);
                if (e.useDuration()) {
                    line(sb, "DURATION:P" + Math.max(1, e.durationHours() / 24 + 1) + "D");
                } else {
                    line(sb, "DTEND;VALUE=DATE:" + date(e.startOffsetDays() + 1));
                }
            }
        }

        if (e.clazz() != null) {
            line(sb, "CLASS:" + e.clazz());
        }
        if (e.summary() != null) {
            line(sb, "SUMMARY:" + escapeText(e.summary()));
        }
        for (String d : e.descriptions()) {
            line(sb, "DESCRIPTION:" + escapeText(d));
        }
        for (String l : e.locations()) {
            line(sb, "LOCATION:" + escapeText(l));
        }
        if (e.url() != null) {
            line(sb, "URL:" + e.url());
        }
        for (int i = 0; i < e.attendeeCount(); i++) {
            line(sb, "ATTENDEE:mailto:person" + i + "@example.com");
        }
        if (e.withRecurrence() && !e.omitDtStart()) {
            line(sb, "RRULE:FREQ=WEEKLY;COUNT=3");
        }
        // Outlook-typische X-MICROSOFT-Property, damit Passthrough auch solche Felder trifft.
        line(sb, "X-MICROSOFT-CDO-BUSYSTATUS:BUSY");
        line(sb, "END:VEVENT");
    }

    // ==================================================================
    // Low-level helpers
    // ==================================================================

    private static void line(StringBuilder sb, String content) {
        sb.append(content).append("\r\n");
    }

    /** Minimales RFC-5545-Text-Escaping für generierte Werte (Backslash, Komma, Semikolon, Newline). */
    private static String escapeText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "")
                .replace(",", "\\,")
                .replace(";", "\\;");
    }

    private static final java.time.LocalDate BASE = java.time.LocalDate.of(2026, 1, 1);

    private static String date(int offsetDays) {
        java.time.LocalDate d = BASE.plusDays(offsetDays);
        return String.format("%04d%02d%02d", d.getYear(), d.getMonthValue(), d.getDayOfMonth());
    }

    private static String dateTime(int offsetDays, int hour) {
        java.time.LocalDate d = BASE.plusDays(offsetDays);
        return String.format("%04d%02d%02dT%02d0000", d.getYear(), d.getMonthValue(), d.getDayOfMonth(), hour);
    }

    private static String dateTimePlusHours(int offsetDays, int hour, int durationHours) {
        java.time.LocalDateTime dt = BASE.plusDays(offsetDays).atTime(hour, 0).plusHours(durationHours);
        return String.format("%04d%02d%02dT%02d%02d00",
                dt.getYear(), dt.getMonthValue(), dt.getDayOfMonth(), dt.getHour(), dt.getMinute());
    }

    // ==================================================================
    // Parsing / Fixture
    // ==================================================================

    static Calendar parse(String ics) {
        try (InputStream in = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))) {
            return new CalendarBuilder().build(in);
        } catch (IOException | ParserException ex) {
            throw new IllegalStateException(
                    "Generator erzeugte nicht-parsebares ICS:\n" + ics, ex);
        }
    }

    static Calendar loadFixture() {
        try (InputStream in = CalendarArbitraries.class.getResourceAsStream(FIXTURE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Fixture nicht gefunden: " + FIXTURE_RESOURCE);
            }
            return new CalendarBuilder().build(in);
        } catch (IOException | ParserException ex) {
            throw new IllegalStateException("Fixture konnte nicht geparst werden: " + FIXTURE_RESOURCE, ex);
        }
    }

    /** Hilfsmethode für Property-Tests: Liste der gültigen EventSpecs (mit UID und DTSTART). */
    static List<EventSpec> validEvents(CalendarSpec spec) {
        List<EventSpec> valid = new ArrayList<>();
        for (EventSpec e : spec.events()) {
            if (e.valid()) {
                valid.add(e);
            }
        }
        return valid;
    }
}
