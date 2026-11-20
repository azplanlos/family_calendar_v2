package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.data.CalendarOutputter;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Fixture-Test: anonymisiertes Beispiel-JSON einer Graph-calendarView-Antwort wird auf das erwartete ICS abgebildet.
 *
 * <p><b>Validates: Requirements 4.7, 5.2 bis 5.13, 6.3 bis 6.7</b>
 */
class GraphCalendarMapperFixtureTest {

    private static String resource(String name) throws IOException {
        try (InputStream in = GraphCalendarMapperFixtureTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Je VEVENT die sortierten Zeilen (Eigenschaftsreihenfolge ist nicht Teil des Vertrags). */
    private static List<List<String>> veventBlocks(String ics) {
        String unfolded = ics.replace("\r\n ", "").replace("\n ", "");
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        for (String line : unfolded.split("\r?\n")) {
            if (line.equals("BEGIN:VEVENT")) {
                current = new ArrayList<>();
            } else if (line.equals("END:VEVENT")) {
                Collections.sort(current);
                blocks.add(current);
                current = null;
            } else if (current != null && !line.isEmpty()) {
                current.add(line);
            }
        }
        return blocks;
    }

    @Test
    void sampleJson_mapsToExpectedIcs() throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(resource("graph-calendarview-sample.json"));
        List<JsonNode> events = new ArrayList<>();
        root.path("value").forEach(events::add);

        GraphCalendarMapper.MappingResult result = GraphCalendarMapper.toCalendar(events, ZoneId.of("Europe/Berlin"));

        assertEquals(1, result.cancelled());
        assertEquals(1, result.skipped(), "Windows-Zonenname wird ausgelassen");

        StringWriter out = new StringWriter();
        new CalendarOutputter(false).output(result.calendar(), out);
        String actual = out.toString();

        assertEquals(veventBlocks(resource("graph-calendarview-sample.expected.ics")), veventBlocks(actual));

        // genau eine VTIMEZONE (nur Europe/Berlin referenziert), Pflichteigenschaften vorhanden
        assertEquals(1, actual.split("BEGIN:VTIMEZONE", -1).length - 1);
        assertEquals(true, actual.contains("TZID:Europe/Berlin"));
        assertEquals(true, actual.contains("VERSION:2.0"));
        assertEquals(true, actual.contains("PRODID:" + GraphCalendarMapper.PRODID));
    }
}
