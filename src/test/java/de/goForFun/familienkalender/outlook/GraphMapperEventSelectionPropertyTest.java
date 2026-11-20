package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import tools.jackson.databind.JsonNode;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 16: Mapping erzeugt genau ein VEVENT je gültigem,
 * nicht storniertem Event und filtert nichts weiter.
 *
 * <p><b>Validates: Requirements 4.7, 4.8, 5.1, 5.14, 5.16, 6.7, 13.20</b>
 */
class GraphMapperEventSelectionPropertyTest extends GraphArbitraries {

    private static final ZoneId APP_ZONE = ZoneId.of("Europe/Berlin");

    /** Zonen, die der Generator liefert und die als IANA-ID auswertbar sind (Windows-Namen nicht). */
    private static final Set<String> USABLE_ZONES =
            Set.of("UTC", "Europe/Berlin", "America/New_York", "Asia/Kolkata", "Australia/Sydney");

    @Property(tries = 200)
    void oneVeventPerValidNonCancelledEvent(@ForAll("graphEventLists") List<JsonNode> events) {
        List<String> expectedUidPrefixes = new ArrayList<>();
        int expectedCancelled = 0;
        int expectedSkipped = 0;
        for (JsonNode e : events) {
            if (e.path("isCancelled").asBoolean(false)) {
                expectedCancelled++;
            } else if (isEvaluable(e)) {
                expectedUidPrefixes.add(baseUid(e));
            } else {
                expectedSkipped++;
            }
        }

        GraphCalendarMapper.MappingResult result = GraphCalendarMapper.toCalendar(events, APP_ZONE);

        List<CalendarComponent> vevents = result.calendar().getComponents(Component.VEVENT);
        assertEquals(expectedUidPrefixes.size(), vevents.size());
        assertEquals(expectedCancelled, result.cancelled());
        assertEquals(expectedSkipped, result.skipped());

        // Eingabereihenfolge: die UID jedes VEVENT beginnt mit der Basis-UID des Eingabe-Events
        for (int i = 0; i < vevents.size(); i++) {
            CalendarComponent v = vevents.get(i);
            String uid = v.getProperty(net.fortuna.ical4j.model.Property.UID).orElseThrow().getValue();
            assertTrue(uid.startsWith(expectedUidPrefixes.get(i)),
                    "UID " + uid + " passt nicht zu " + expectedUidPrefixes.get(i));
            assertTrue(v.getProperty(net.fortuna.ical4j.model.Property.RRULE).isEmpty());
            assertTrue(v.getProperty(net.fortuna.ical4j.model.Property.RDATE).isEmpty());
            assertTrue(v.getProperty(net.fortuna.ical4j.model.Property.EXDATE).isEmpty());
        }
    }

    @Property(tries = 200)
    void sensitivityCategoriesShowAsNeverFilter(@ForAll("graphEvents") JsonNode event) {
        boolean expected = !event.path("isCancelled").asBoolean(false) && isEvaluable(event);
        GraphCalendarMapper.MappingResult result = GraphCalendarMapper.toCalendar(List.of(event), APP_ZONE);
        assertEquals(expected ? 1 : 0, result.calendar().getComponents(Component.VEVENT).size());
    }

    // ---------------------------------------------------------------- Orakel

    private static String baseUid(JsonNode e) {
        String ical = textOrNull(e, "iCalUId");
        return ical != null && !ical.isEmpty() ? ical : textOrNull(e, "id");
    }

    private static boolean isEvaluable(JsonNode e) {
        String uid = baseUid(e);
        return uid != null && !uid.isEmpty() && usableTime(e.path("start")) && usableTime(e.path("end"));
    }

    private static boolean usableTime(JsonNode t) {
        if (!t.isObject()) {
            return false;
        }
        String dt = textOrNull(t, "dateTime");
        String tz = textOrNull(t, "timeZone");
        return dt != null && tz != null && USABLE_ZONES.contains(tz);
    }

    private static String textOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isString() ? v.asString() : null;
    }
}
