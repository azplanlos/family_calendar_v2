package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.JsonNode;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 20: UIDs sind eindeutig und stabil.
 *
 * <p><b>Validates: Requirements 5.12, 5.13</b>
 */
class GraphMapperUidPropertyTest extends GraphArbitraries {

    private static final ZoneId APP_ZONE = ZoneId.of("Europe/Berlin");
    private static final String SUFFIX_REGEX = "-\\d{8}T\\d{6}Z";
    /** originalStart 2027-02-14T00:00:00+01:00 in UTC (Generator-Konstante). */
    private static final String ORIGINAL_START_SUFFIX = "-20270213T230000Z";

    @Property(tries = 300)
    void uidsAreUniqueAndStable(@ForAll("graphEventLists") List<JsonNode> events) {
        List<String> first = uids(events);
        List<String> second = uids(events);

        // Stabilität: wiederholtes Mappen derselben Antwort liefert dieselben UIDs in derselben Reihenfolge
        assertEquals(first, second);
        // Eindeutigkeit im gesamten Calendar
        assertEquals(first.size(), new HashSet<>(first).size(), "UIDs nicht eindeutig: " + first);
    }

    @Property(tries = 300)
    void uidFormationFollowsRules(@ForAll("graphEventLists") List<JsonNode> events) {
        // Auswertbare, nicht stornierte Events samt Basis-UID (Eingabereihenfolge)
        List<JsonNode> valid = new ArrayList<>();
        for (JsonNode e : events) {
            if (!e.path("isCancelled").asBoolean(false)
                    && GraphCalendarMapper.toCalendar(List.of(e), APP_ZONE).calendar()
                    .getComponents(Component.VEVENT).size() == 1) {
                valid.add(e);
            }
        }
        List<String> uids = uids(events);
        assertEquals(valid.size(), uids.size());

        Map<String, Integer> counts = new HashMap<>();
        valid.forEach(e -> counts.merge(baseUid(e), 1, Integer::sum));

        for (int i = 0; i < valid.size(); i++) {
            JsonNode e = valid.get(i);
            String base = baseUid(e);
            String uid = uids.get(i);
            boolean series = isSeries(e);
            if (!series && counts.get(base) == 1) {
                assertEquals(base, uid, "Einzeltermin ohne Duplikat behält die Basis-UID");
            } else {
                assertTrue(uid.startsWith(base), uid);
                assertTrue(uid.substring(base.length()).matches(SUFFIX_REGEX + "(-\\d+)?"),
                        "Suffix unerwartet: " + uid);
                if (series && e.path("originalStart").isString()) {
                    assertTrue(uid.substring(base.length()).startsWith(ORIGINAL_START_SUFFIX), uid);
                }
            }
        }
    }

    @Property(tries = 200)
    void identicalEventsGetCountersInInputOrder(@ForAll("graphEvents") JsonNode event, @ForAll("copies") int copies) {
        if (event.path("isCancelled").asBoolean(false)
                || GraphCalendarMapper.toCalendar(List.of(event), APP_ZONE).calendar()
                .getComponents(Component.VEVENT).isEmpty()) {
            return;
        }
        List<JsonNode> events = new ArrayList<>();
        for (int i = 0; i < copies; i++) {
            events.add(event.deepCopy());
        }
        List<String> uids = uids(events);

        assertEquals(copies, new HashSet<>(uids).size());
        String suffixed = uids.get(0);
        assertTrue(suffixed.substring(baseUid(event).length()).matches(SUFFIX_REGEX), suffixed);
        for (int k = 1; k < copies; k++) {
            assertEquals(suffixed + "-" + (k + 1), uids.get(k));
        }
        assertEquals(uids, uids(events));
    }

    @Property(tries = 200)
    void uidDoesNotDependOnOtherDistinctEvents(@ForAll("graphEvents") JsonNode event,
                                               @ForAll("graphEventLists") List<JsonNode> others) {
        if (event.path("isCancelled").asBoolean(false)) {
            return;
        }
        List<String> alone = uids(List.of(event));
        if (alone.isEmpty()) {
            return;
        }
        String base = baseUid(event);
        boolean clash = others.stream().anyMatch(o -> base.equals(baseUid(o)));
        if (clash) {
            return;
        }
        List<JsonNode> all = new ArrayList<>(others);
        all.add(event);
        List<String> together = uids(all);
        assertEquals(alone.get(0), together.get(together.size() - 1));
    }

    @Provide
    Arbitrary<Integer> copies() {
        return Arbitraries.integers().between(2, 5);
    }

    // ---------------------------------------------------------------- Hilfen

    private static List<String> uids(List<JsonNode> events) {
        List<String> result = new ArrayList<>();
        for (CalendarComponent c : GraphCalendarMapper.toCalendar(events, APP_ZONE).calendar()
                .getComponents(Component.VEVENT)) {
            result.add(c.getProperty(net.fortuna.ical4j.model.Property.UID).orElseThrow().getValue());
        }
        return result;
    }

    private static String baseUid(JsonNode e) {
        String ical = e.path("iCalUId").isString() ? e.path("iCalUId").asString() : null;
        if (ical != null && !ical.isEmpty()) {
            return ical;
        }
        return e.path("id").isString() ? e.path("id").asString() : null;
    }

    private static boolean isSeries(JsonNode e) {
        String type = e.path("type").isString() ? e.path("type").asString().trim().toLowerCase() : "";
        return type.equals("occurrence") || type.equals("exception");
    }
}
