package de.goForFun.familienkalender;

import de.goForFun.familienkalender.outlook.OutlookCalendarLoader;
import de.goForFun.familienkalender.outlook.OutlookLoad;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EventRepositoryOutlookLoaderTest {

    private static final LocalDate REF = LocalDate.of(2026, 11, 10);
    private static URI mainFeed;

    private static String ics(String uid, String summary) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:" + uid + "\r\nDTSTAMP:20261101T000000Z\r\n"
                + "DTSTART:20261124T080000Z\r\nDTEND:20261124T090000Z\r\nSUMMARY:" + summary + "\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n";
    }

    @BeforeAll
    static void setUp() throws Exception {
        Path p = Files.createTempFile("main-feed", ".ics");
        Files.writeString(p, ics("main-1", "Haupt"), StandardCharsets.UTF_8);
        p.toFile().deleteOnExit();
        mainFeed = p.toUri();
    }

    private static Calendar calendar() throws Exception {
        return new CalendarBuilder().build(
                new ByteArrayInputStream(ics("ol-1", "Outlook").getBytes(StandardCharsets.UTF_8)));
    }

    private static EventRepository repo(OutlookCalendarLoader loader, String name) throws Exception {
        return new EventRepository(mainFeed, null, null, loader, name, REF, null);
    }

    @Test
    void loaded_appendsEventsWithTrimmedParticipant_noError() throws Exception {
        Calendar cal = calendar();
        EventRepository r = repo(w -> new OutlookLoad.Loaded(cal, OutlookLoad.Origin.GRAPH), "  Papa ");
        assertThat(r.getErrors()).isEmpty();
        var events = r.getEventsForRange(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 24));
        assertThat(events).hasSize(2);
        assertThat(events).filteredOn(e -> "Outlook".equals(e.summary()))
                .singleElement().satisfies(e -> assertThat(e.participants()).containsExactly("Papa"));
    }

    @Test
    void notConfigured_addsNothing() throws Exception {
        EventRepository r = repo(w -> new OutlookLoad.NotConfigured(), "Papa");
        assertThat(r.getErrors()).isEmpty();
        assertThat(r.getEventsForRange(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 24))).hasSize(1);
    }

    @Test
    void unavailable_addsExactlyOneError() throws Exception {
        EventRepository r = repo(w -> new OutlookLoad.Unavailable("Graph nicht erreichbar"), "Papa");
        assertThat(r.getErrors()).containsExactly("Outlook-Feed: Graph nicht erreichbar");
    }

    private static Calendar parse(String ics) throws Exception {
        return new CalendarBuilder().build(new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8)));
    }

    private static final LocalDate DAY = LocalDate.of(2026, 11, 24);

    @Test
    void privateGraphEvent_isShownWithOriginalTitle() throws Exception {
        // 13.2 / 13.6: Titel „Zahnarzt“ bleibt auch bei sensitivity=private (CLASS:PRIVATE) unverändert.
        Calendar cal = parse(ics("ol-priv", "Zahnarzt").replace("END:VEVENT", "CLASS:PRIVATE\r\nEND:VEVENT"));
        EventRepository r = repo(w -> new OutlookLoad.Loaded(cal, OutlookLoad.Origin.GRAPH), "Papa");
        assertThat(r.getErrors()).isEmpty();
        assertThat(r.getEventsForRange(DAY, DAY)).filteredOn(e -> "Zahnarzt".equals(e.summary()))
                .singleElement().satisfies(e -> assertThat(e.participants()).containsExactly("Papa"));
    }

    @Test
    void outlookEvents_areAppendedAfterParticipantAssignment() throws Exception {
        // 13.10: Hauptfeed-Event ohne Teilnehmer erhält nur bekannte Hauptfeed-Teilnehmer, nicht den Outlook-Namen.
        String main = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:m1\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261124T100000Z\r\n"
                + "DTEND:20261124T110000Z\r\nSUMMARY:Mit Teilnehmer\r\nATTENDEE:mailto:anna@example.org\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:m2\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261124T120000Z\r\n"
                + "DTEND:20261124T130000Z\r\nSUMMARY:Ohne Teilnehmer\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        Path p = Files.createTempFile("main-assign", ".ics");
        p.toFile().deleteOnExit();
        Files.writeString(p, main, StandardCharsets.UTF_8);
        Calendar cal = calendar();
        EventRepository r = new EventRepository(p.toUri(), null, null,
                w -> new OutlookLoad.Loaded(cal, OutlookLoad.Origin.GRAPH), "Papa", REF, null);

        var events = r.getEventsForRange(DAY, DAY);
        assertThat(events).filteredOn(e -> "Ohne Teilnehmer".equals(e.summary()))
                .singleElement().satisfies(e -> assertThat(e.participants()).containsExactly("anna@example.org"));
        assertThat(events).filteredOn(e -> "Outlook".equals(e.summary()))
                .singleElement().satisfies(e -> assertThat(e.participants()).containsExactly("Papa"));
    }

    @Test
    void nullLoader_behavesLikeLegacyConstructorWithoutParticipantFeed() throws Exception {
        EventRepository legacy = new EventRepository(mainFeed, null, null, (URI) null, null, REF, null);
        EventRepository viaLoader = new EventRepository(mainFeed, null, null, (OutlookCalendarLoader) null,
                "Papa", REF, null);
        assertThat(viaLoader.getEventsForRange(DAY, DAY)).isEqualTo(legacy.getEventsForRange(DAY, DAY));
        assertThat(viaLoader.getErrors()).isEqualTo(legacy.getErrors());
    }

    @Test
    void legacyConstructor_stillAppendsParticipantFeedWithLegacyErrorPrefix() throws Exception {
        Path outlook = Files.createTempFile("legacy-outlook", ".ics");
        outlook.toFile().deleteOnExit();
        Files.writeString(outlook, ics("ol-legacy", "Legacy"), StandardCharsets.UTF_8);
        EventRepository ok = new EventRepository(mainFeed, null, null, outlook.toUri(), " Papa ", REF, null);
        assertThat(ok.getErrors()).isEmpty();
        assertThat(ok.getEventsForRange(DAY, DAY)).filteredOn(e -> "Legacy".equals(e.summary()))
                .singleElement().satisfies(e -> assertThat(e.participants()).containsExactly("Papa"));

        EventRepository broken = new EventRepository(mainFeed, null, null,
                outlook.resolveSibling("does-not-exist-" + System.nanoTime() + ".ics").toUri(), "Papa", REF, null);
        assertThat(broken.getErrors()).hasSize(1);
        assertThat(broken.getErrors().get(0)).startsWith("Outlook-Feed: ");
    }

    @Test
    void runtimeException_isTreatedAsUnavailable() throws Exception {
        EventRepository r = repo(w -> { throw new IllegalStateException("geheim"); }, "Papa");
        assertThat(r.getErrors()).hasSize(1);
        assertThat(r.getErrors().get(0)).startsWith("Outlook-Feed: ").doesNotContain("geheim");
    }
}
