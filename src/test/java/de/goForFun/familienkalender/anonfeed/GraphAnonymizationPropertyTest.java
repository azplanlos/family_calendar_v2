package de.goForFun.familienkalender.anonfeed;

import de.goForFun.familienkalender.outlook.GraphArbitraries;
import de.goForFun.familienkalender.outlook.GraphCalendarMapper;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: outlook-graph-source, Property 23: Anonymisierung von Graph-Daten folgt sensitivity
 * und Kategorie und leakt nichts.
 *
 * <p>Jedes Graph-Event wird einzeln über {@code GraphCalendarMapper.toCalendar} abgebildet und
 * durch den unveränderten {@link FeedAnonymizer} geschickt. Die Erwartung wird aus dem
 * Graph-JSON abgeleitet (sensitivity, categories, showAs), nicht aus dem Mapping-Ergebnis.
 * Subject, bodyPreview, location und Kategorien werden durch eindeutige Canary-Werte ersetzt,
 * damit ein Leck im serialisierten Feed erkennbar ist.
 *
 * <p><strong>Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5, 7.9</strong>
 */
class GraphAnonymizationPropertyTest extends GraphArbitraries {

    private static final String PLACEHOLDER = "GEHEIM-PLATZHALTER-42";
    private static final ZoneId APP_ZONE = ZoneId.of("Europe/Berlin");
    private static final Instant EARLY = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant LATE = Instant.parse("2100-01-01T00:00:00Z");

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", PLACEHOLDER,
            "ANON_PARTICIPANT", "Familienkalender"));

    @Provide
    Arbitrary<String> canary() {
        return GraphArbitraries.canaryStringsArbitrary();
    }

    @Property(tries = 300)
    void graphEventsAreAnonymizedBySensitivityAndCategory(
            @ForAll("graphEventLists") List<JsonNode> events,
            @ForAll("canary") String canary) {
        for (int i = 0; i < events.size(); i++) {
            ObjectNode event = withCanaries(events.get(i), canary + i);
            checkEvent(event, canary + i);
        }
    }

    private void checkEvent(ObjectNode event, String canary) {
        Calendar mapped = GraphCalendarMapper.toCalendar(List.of(event), APP_ZONE).calendar();
        boolean mappable = !mapped.<CalendarComponent>getComponents(Component.VEVENT).isEmpty();

        // Vergangene Events fehlen (7.5): Referenzzeit weit nach allen Events.
        Calendar late = anonymizer.anonymize(mapped, config, LATE);
        assertThat(late.<CalendarComponent>getComponents(Component.VEVENT))
                .as("Vergangene Events dürfen nicht im Feed stehen: %s", event)
                .isEmpty();

        Calendar result = anonymizer.anonymize(mapped, config, EARLY);
        List<CalendarComponent> out = result.<CalendarComponent>getComponents(Component.VEVENT);

        boolean free = "free".equals(lower(text(event, "showAs")));
        if (!mappable || free) {
            assertThat(out).as("Nicht abbildbare oder freie Events fehlen (7.5): %s", event).isEmpty();
            return;
        }
        assertThat(out).as("Event muss im Feed stehen: %s", event).hasSize(1);
        VEvent vevent = (VEvent) out.get(0);

        String sensitivity = lower(text(event, "sensitivity"));
        boolean expectPrivate = sensitivity.equals("private") || sensitivity.equals("confidential")
                || hasPrivateCategory(event);

        String serialized = result.toString().replace("\r\n ", "").replace("\n ", "");

        if (expectPrivate) {
            // 7.2, 7.3, 7.9
            assertThat(vevent.getProperties(net.fortuna.ical4j.model.Property.SUMMARY)).hasSize(1);
            assertThat(vevent.getProperty(net.fortuna.ical4j.model.Property.SUMMARY).get().getValue())
                    .isEqualTo(PLACEHOLDER);
            for (String field : new String[]{"DESCRIPTION", "LOCATION", "URL", "ATTENDEE", "ORGANIZER", "ATTACH"}) {
                assertThat(vevent.getProperties(field)).as("%s bei privatem Event: %s", field, event).isEmpty();
            }
            assertThat(serialized).as("Leck im Feed für %s", event).doesNotContain(canary);
            for (net.fortuna.ical4j.model.Property p : vevent.getProperties(net.fortuna.ical4j.model.Property.CATEGORIES)) {
                String v = p.getValue();
                for (String c : v.split(",")) {
                    assertThat(c.trim()).as("nur Privat-Kategorie darf bleiben: %s", event)
                            .isEqualToIgnoringCase("privat");
                }
            }
        } else {
            // 7.4: Public_Event bleibt unverändert
            assertThat(vevent.getProperty(net.fortuna.ical4j.model.Property.SUMMARY).get().getValue())
                    .isEqualTo(text(event, "subject") == null ? "" : text(event, "subject"));
            String location = text(event.path("location"), "displayName");
            if (location != null && !location.trim().isEmpty()) {
                assertThat(vevent.getProperty(net.fortuna.ical4j.model.Property.LOCATION).get().getValue())
                        .isEqualTo(location);
            }
            String body = text(event, "bodyPreview");
            if (body != null && !body.trim().isEmpty()) {
                assertThat(vevent.getProperty(net.fortuna.ical4j.model.Property.DESCRIPTION).get().getValue())
                        .isEqualTo(body);
            }
        }
    }

    /** Ersetzt subject/bodyPreview/location und ergänzt eine Canary-Kategorie. */
    private static ObjectNode withCanaries(JsonNode original, String canary) {
        ObjectNode e = (ObjectNode) original.deepCopy();
        e.put("subject", canary + "-subject");
        e.put("bodyPreview", canary + "-body");
        e.putObject("location").put("displayName", canary + "-location");
        JsonNode cats = e.get("categories");
        ArrayNode arr = cats != null && cats.isArray() ? (ArrayNode) cats : e.putArray("categories");
        arr.add(canary + "-category");
        return e;
    }

    private static boolean hasPrivateCategory(JsonNode event) {
        JsonNode cats = event.path("categories");
        if (cats.isArray()) {
            for (JsonNode c : cats) {
                if (c.isString() && c.asString().trim().equalsIgnoreCase("privat")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isString() ? v.asString() : null;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
