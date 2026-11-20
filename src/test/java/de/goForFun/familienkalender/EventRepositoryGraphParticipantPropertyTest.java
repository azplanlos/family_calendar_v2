package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.outlook.GraphArbitraries;
import de.goForFun.familienkalender.outlook.GraphCalendarMapper;
import de.goForFun.familienkalender.outlook.OutlookLoad;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 27: Teilnehmerzuordnung der Outlook-Events.
 *
 * <p><b>Validates: Requirements 13.9, 13.10</b>
 */
class EventRepositoryGraphParticipantPropertyTest {

    private static final LocalDate REF = LocalDate.of(2026, 11, 10);
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private static final URI MAIN_FEED = createMainFeed();

    private static URI createMainFeed() {
        String main = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n"
                + "BEGIN:VEVENT\r\nUID:main-1\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261124T100000Z\r\n"
                + "DTEND:20261124T110000Z\r\nSUMMARY:Mit Teilnehmer\r\nATTENDEE:mailto:anna@example.org\r\nEND:VEVENT\r\n"
                + "BEGIN:VEVENT\r\nUID:main-2\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261124T120000Z\r\n"
                + "DTEND:20261124T130000Z\r\nSUMMARY:Ohne Teilnehmer\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n";
        try {
            Path p = Files.createTempFile("main-participant-prop", ".ics");
            p.toFile().deleteOnExit();
            Files.writeString(p, main, StandardCharsets.UTF_8);
            return p.toUri();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Provide
    Arbitrary<String> participants() {
        Arbitrary<String> padding = Arbitraries.of("", " ", "  ", "\t", " \t ");
        Arbitrary<String> text = Arbitraries.of("Papa", "Anna-Lena", "Müller, Max; Jr.", "back\\slash",
                "Ünï 日本語 😀", "x");
        Arbitrary<String> padded = net.jqwik.api.Combinators.combine(padding, text, padding)
                .as((a, t, b) -> a + t + b);
        return Arbitraries.oneOf(
                Arbitraries.just((String) null), Arbitraries.just(""), Arbitraries.of(" ", "   ", "\t", " \t \n"),
                padded, text);
    }

    @Provide
    Arbitrary<List<JsonNode>> graphEventsInWindow() {
        return GraphArbitraries.graphEventListArbitrary(0, 6).map(list -> {
            List<JsonNode> all = new ArrayList<>();
            all.add(fixedEvent());
            all.addAll(list);
            return all;
        });
    }

    private static ObjectNode fixedEvent() {
        JsonMapper m = JsonMapper.builder().build();
        ObjectNode e = m.createObjectNode();
        e.put("id", "AAMkFixed");
        e.put("iCalUId", "040000008200E000FIXED");
        e.put("subject", "Fix");
        e.put("sensitivity", "normal");
        e.put("showAs", "busy");
        e.put("isAllDay", false);
        e.put("isCancelled", false);
        e.put("type", "singleInstance");
        e.putNull("originalStart");
        e.putObject("start").put("dateTime", "2026-11-24T14:00:00.0000000").put("timeZone", "UTC");
        e.putObject("end").put("dateTime", "2026-11-24T15:00:00.0000000").put("timeZone", "UTC");
        return e;
    }

    private static EventRepository repo(net.fortuna.ical4j.model.Calendar cal, String name) throws Exception {
        return new EventRepository(MAIN_FEED, null, null,
                w -> new OutlookLoad.Loaded(cal, OutlookLoad.Origin.GRAPH), name, REF, null);
    }

    private static boolean isMain(Event e) {
        return "Mit Teilnehmer".equals(e.summary()) || "Ohne Teilnehmer".equals(e.summary());
    }

    private static List<Event> outlookEvents(List<Event> all) {
        return all.stream().filter(e -> !isMain(e)).toList();
    }

    private static List<Event> mainEvents(List<Event> all) {
        return all.stream().filter(e -> isMain(e)).toList();
    }

    @Property(tries = 100)
    void outlookEventsGetTrimmedParticipantOrStayUnchanged(
            @ForAll("participants") String name,
            @ForAll("graphEventsInWindow") List<JsonNode> graphEvents) throws Exception {
        net.fortuna.ical4j.model.Calendar cal =
                GraphCalendarMapper.toCalendar(graphEvents, ZoneId.of("Europe/Berlin")).calendar();

        List<Event> actual = repo(cal, name).getEventsForRange(FROM, TO);
        List<Event> baseline = repo(cal, null).getEventsForRange(FROM, TO);

        List<Event> actualOutlook = outlookEvents(actual);
        List<Event> baselineOutlook = outlookEvents(baseline);
        assertFalse(actualOutlook.isEmpty(), "Fix-Event im Fenster muss geladen werden");
        assertEquals(baselineOutlook.size(), actualOutlook.size());

        String trimmed = name == null ? "" : name.trim();
        for (int i = 0; i < actualOutlook.size(); i++) {
            Event a = actualOutlook.get(i);
            Event b = baselineOutlook.get(i);
            if (trimmed.isEmpty()) {
                assertEquals(b.participants(), a.participants());
            } else {
                assertEquals(List.of(trimmed), a.participants());
            }
            assertEquals(b.summary(), a.summary());
            assertEquals(b.startTime(), a.startTime());
        }

        // Hauptfeed-Events: unabhängig vom Participant, nie dem Participant zugeordnet
        List<Event> actualMain = mainEvents(actual);
        assertEquals(2, actualMain.size());
        assertEquals(mainEvents(baseline), actualMain);
        for (Event e : actualMain) {
            assertEquals(List.of("anna@example.org"), e.participants());
            assertTrue(trimmed.isEmpty() || !e.participants().contains(trimmed));
        }
    }
}
