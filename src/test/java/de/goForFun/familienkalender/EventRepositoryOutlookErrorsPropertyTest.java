package de.goForFun.familienkalender;

import de.goForFun.familienkalender.outlook.OutlookCalendarLoader;
import de.goForFun.familienkalender.outlook.OutlookLoad;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * **Feature: outlook-graph-source, Property 28: Die Device-Fehlerliste folgt der Entscheidungstabelle**
 *
 * **Validates: Requirements 13.3, 13.11, 13.13, 13.14, 13.15**
 */
class EventRepositoryOutlookErrorsPropertyTest {

    private static final LocalDate REF = LocalDate.of(2026, 11, 10);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);
    private static final String PREFIX = "Outlook-Feed:";

    private static final String MAIN_ICS = ics("main-1", "Haupt");
    private static final URI GOOD_FEED = writeTemp(MAIN_ICS);
    private static final URI BROKEN_FEED = GOOD_FEED.resolve("does-not-exist-property-28.ics");

    private static String ics(String uid, String summary) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20261101T000000Z\r\n"
                + "DTSTART:20261124T080000Z\r\nDTEND:20261124T090000Z\r\nSUMMARY:" + summary + "\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    private static URI writeTemp(String content) {
        try {
            Path p = Files.createTempFile("prop28-main", ".ics");
            p.toFile().deleteOnExit();
            Files.writeString(p, content, StandardCharsets.UTF_8);
            return p.toUri();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Calendar calendar() throws Exception {
        return new CalendarBuilder().build(new ByteArrayInputStream(
                ics("ol-1", "Outlook").getBytes(StandardCharsets.UTF_8)));
    }

    private static long outlookErrors(List<String> errors) {
        return errors.stream().filter(e -> e.startsWith(PREFIX)).count();
    }

    @Provide
    Arbitrary<String> details() {
        Arbitrary<String> free = Arbitraries.strings().alpha().numeric().withChars(' ', ':', '(', ')', '-')
                .ofMinLength(1).ofMaxLength(40);
        Arbitrary<String> graph = Arbitraries.of("Authentifizierung", "Berechtigung", "Netzwerk",
                "Graph_Zeitbudget überschritten", "Graph-Antwort ungültig")
                .map(c -> "Graph nicht verfügbar (" + c + ")");
        Arbitrary<String> ics = Arbitraries.integers().between(100, 599).map(c -> "HTTP " + c);
        return Arbitraries.oneOf(free, graph, ics);
    }

    @Property(tries = 100)
    void unavailable_addsExactlyOneErrorWithDetail(@ForAll("details") String detail,
                                                   @ForAll boolean mainBroken) throws Exception {
        URI main = mainBroken ? BROKEN_FEED : GOOD_FEED;
        EventRepository base = new EventRepository(main, null, null, (OutlookCalendarLoader) null, "Papa", REF, null);
        EventRepository r = new EventRepository(main, null, null,
                w -> new OutlookLoad.Unavailable(detail), "Papa", REF, null);

        assertThat(outlookErrors(r.getErrors())).isEqualTo(1);
        assertThat(r.getErrors()).contains(PREFIX + " " + detail);
        // übrige Fehlereinträge und Events unverändert
        assertThat(r.getErrors().stream().filter(e -> !e.startsWith(PREFIX)).toList())
                .isEqualTo(base.getErrors());
        assertThat(r.getEventsForRange(DAY, DAY)).isEqualTo(base.getEventsForRange(DAY, DAY));
    }

    @Property(tries = 50)
    void notConfigured_addsNoErrorAndNoEvents(@ForAll boolean mainBroken) throws Exception {
        URI main = mainBroken ? BROKEN_FEED : GOOD_FEED;
        EventRepository base = new EventRepository(main, null, null, (OutlookCalendarLoader) null, "Papa", REF, null);
        EventRepository r = new EventRepository(main, null, null,
                w -> new OutlookLoad.NotConfigured(), "Papa", REF, null);

        assertThat(outlookErrors(r.getErrors())).isZero();
        assertThat(r.getErrors()).isEqualTo(base.getErrors());
        assertThat(r.getEventsForRange(DAY, DAY)).isEqualTo(base.getEventsForRange(DAY, DAY));
    }

    @Property(tries = 50)
    void loaded_addsNoErrorEntry(@ForAll boolean mainBroken,
                                 @ForAll("origins") OutlookLoad.Origin origin) throws Exception {
        URI main = mainBroken ? BROKEN_FEED : GOOD_FEED;
        EventRepository base = new EventRepository(main, null, null, (OutlookCalendarLoader) null, "Papa", REF, null);
        Calendar cal = calendar();
        EventRepository r = new EventRepository(main, null, null,
                w -> new OutlookLoad.Loaded(cal, origin), "Papa", REF, null);

        assertThat(outlookErrors(r.getErrors())).isZero();
        assertThat(r.getErrors()).isEqualTo(base.getErrors());
        assertThat(r.getEventsForRange(DAY, DAY)).hasSize(base.getEventsForRange(DAY, DAY).size() + 1);
    }

    @Provide
    Arbitrary<OutlookLoad.Origin> origins() {
        return Arbitraries.of(OutlookLoad.Origin.values());
    }

    @Property(tries = 50)
    void throwingLoader_addsExactlyOneErrorWithoutLeakingMessage(@ForAll boolean mainBroken) throws Exception {
        URI main = mainBroken ? BROKEN_FEED : GOOD_FEED;
        EventRepository base = new EventRepository(main, null, null, (OutlookCalendarLoader) null, "Papa", REF, null);
        EventRepository r = new EventRepository(main, null, null,
                w -> { throw new IllegalStateException("geheim-token-123"); }, "Papa", REF, null);

        assertThat(outlookErrors(r.getErrors())).isEqualTo(1);
        assertThat(String.join("|", r.getErrors())).doesNotContain("geheim-token-123");
        assertThat(r.getErrors().stream().filter(e -> !e.startsWith(PREFIX)).toList())
                .isEqualTo(base.getErrors());
        assertThat(r.getEventsForRange(DAY, DAY)).isEqualTo(base.getEventsForRange(DAY, DAY));
    }
}
