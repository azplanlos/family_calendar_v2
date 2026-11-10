package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der Original-Outlook-Feed (ANON_SOURCE_FEED) wird im Device-Render zusätzlich zum
 * Hauptfeed geladen – unanonymisiert und dem konfigurierten Participant zugeordnet.
 */
class EventRepositoryParticipantFeedTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);
    private static final URI OUTLOOK_FIXTURE =
            Path.of("src/test/resources/outlook-published-sample.ics").toUri();

    /** Hauptfeed: ein Event ohne Teilnehmer und eins mit Teilnehmer "Anna" (Prefix-Konvention). */
    private static URI mainFeed(Path dir) throws Exception {
        String ics = String.join("\r\n",
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN",
                "BEGIN:VEVENT", "UID:main-1", "DTSTAMP:20260101T000000Z",
                "DTSTART;TZID=Europe/Berlin:20261028T080000", "DTEND;TZID=Europe/Berlin:20261028T090000",
                "SUMMARY:Familienfrühstück", "END:VEVENT",
                "END:VCALENDAR", "");
        Path f = dir.resolve("main.ics");
        Files.writeString(f, ics, StandardCharsets.UTF_8);
        return f.toUri();
    }

    private static List<Event> findBySummary(EventRepository repo, String summary) {
        return repo.getEventsForDay(DAY).stream()
                .filter(e -> summary.equals(e.summary()))
                .toList();
    }

    @Test
    void outlookEventsWerdenUnanonymisiertDemParticipantZugeordnet(@TempDir Path dir) throws Exception {
        EventRepository repo = new EventRepository(mainFeed(dir), null, null,
                OUTLOOK_FIXTURE, "  Max  ", DAY, null);

        List<Event> privat = findBySummary(repo, "Privater Termin");
        assertThat(privat).hasSize(1);
        assertThat(privat.get(0).participants()).containsExactly("Max");
        assertThat(findBySummary(repo, "Test normal")).singleElement()
                .satisfies(e -> assertThat(e.participants()).containsExactly("Max"));
        assertThat(repo.getEventsForDay(DAY)).noneMatch(e -> "Privat".equals(e.summary()));

        assertThat(repo.getAllParticipants()).contains("Max");
        assertThat(repo.getErrors()).isEmpty();
    }

    @Test
    void unzugeordneteHauptfeedEventsBekommenNichtDenParticipant(@TempDir Path dir) throws Exception {
        EventRepository repo = new EventRepository(mainFeed(dir), null, null,
                OUTLOOK_FIXTURE, "Max", DAY, null);

        assertThat(findBySummary(repo, "Familienfrühstück")).singleElement()
                .satisfies(e -> assertThat(e.participants()).doesNotContain("Max"));
    }

    @Test
    void ohneParticipantFeedBleibtAllesUnveraendert(@TempDir Path dir) throws Exception {
        URI main = mainFeed(dir);
        EventRepository withNull = new EventRepository(main, null, null, null, "Max", DAY, null);
        EventRepository legacy = new EventRepository(main, null, null, DAY, null);

        assertThat(withNull.getEventsForDay(DAY)).isEqualTo(legacy.getEventsForDay(DAY));
        assertThat(withNull.getAllParticipants()).doesNotContain("Max");
    }

    @Test
    void leererParticipantLaesstGeparsteTeilnehmerUnveraendert(@TempDir Path dir) throws Exception {
        EventRepository repo = new EventRepository(mainFeed(dir), null, null,
                OUTLOOK_FIXTURE, "   ", DAY, null);

        assertThat(findBySummary(repo, "Privater Termin")).hasSize(1);
        assertThat(repo.getAllParticipants()).doesNotContain("");
    }

    @Test
    void fehlerImParticipantFeedWirdGesammelt(@TempDir Path dir) throws Exception {
        URI missing = dir.resolve("gibt-es-nicht.ics").toUri();
        EventRepository repo = new EventRepository(mainFeed(dir), null, null,
                missing, "Max", DAY, null);

        assertThat(repo.getErrors()).anyMatch(m -> m.startsWith("Outlook-Feed:"));
        assertThat(findBySummary(repo, "Familienfrühstück")).hasSize(1);
    }
}
