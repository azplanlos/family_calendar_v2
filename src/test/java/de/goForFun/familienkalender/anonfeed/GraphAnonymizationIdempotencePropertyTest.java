package de.goForFun.familienkalender.anonfeed;

import de.goForFun.familienkalender.outlook.GraphArbitraries;
import de.goForFun.familienkalender.outlook.GraphCalendarMapper;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: outlook-graph-source, Property 24: Idempotenz der Anonymisierung auf
 * Graph-abgeleiteten Kalendern.
 *
 * <p>{@code anonymize(parse(serialize(anonymize(map(events))))) ≡ anonymize(map(events))}
 * bezogen auf {@code UID}, {@code DTSTART}, {@code DTEND}, {@code SUMMARY}, {@code CLASS} und
 * {@code TRANSP} je {@code VEVENT}.
 *
 * <p><b>Validates: Requirements 7.8</b>
 */
class GraphAnonymizationIdempotencePropertyTest {

    private static final String PLACEHOLDER = "Privat";
    private static final Instant NOW = Instant.parse("2000-01-01T00:00:00Z");
    private static final String[] FIELDS = {"UID", "DTSTART", "DTEND", "SUMMARY", "CLASS", "TRANSP"};

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", PLACEHOLDER,
            "ANON_PARTICIPANT", "Familienkalender"));

    @Provide
    Arbitrary<List<JsonNode>> graphEventLists() {
        return GraphArbitraries.graphEventListArbitrary(0, 8);
    }

    @Provide
    Arbitrary<ZoneId> appZones() {
        return Arbitraries.of("Europe/Berlin", "UTC", "America/New_York", "Asia/Kolkata")
                .map(ZoneId::of);
    }

    @Property(tries = 200)
    void secondAnonymizationDoesNotChangeGraphDerivedCalendar(
            @ForAll("graphEventLists") List<JsonNode> events,
            @ForAll("appZones") ZoneId zone) throws Exception {
        Calendar mapped = GraphCalendarMapper.toCalendar(events, zone).calendar();
        Calendar once = anonymizer.anonymize(mapped, config, NOW);
        Calendar reparsed = new CalendarBuilder().build(
                new ByteArrayInputStream(once.toString().getBytes(StandardCharsets.UTF_8)));
        Calendar twice = anonymizer.anonymize(reparsed, config, NOW);

        assertThat(signatures(twice))
                .as("Signaturen nach zweitem Durchlauf müssen denen des ersten entsprechen")
                .containsExactlyInAnyOrderElementsOf(signatures(once));
    }

    private static List<List<String>> signatures(Calendar calendar) {
        List<List<String>> result = new ArrayList<>();
        for (CalendarComponent component : calendar.<CalendarComponent>getComponents(Component.VEVENT)) {
            VEvent event = (VEvent) component;
            List<String> signature = new ArrayList<>();
            for (String field : FIELDS) {
                // Der ical4j-Parser trimmt beim Wiedereinlesen den gesamten Property-Wert (Rand-
                // Leerraum im SUMMARY geht dabei verloren, siehe GraphMapperTextRoundTripPropertyTest);
                // daher wird der Wert getrimmt verglichen.
                signature.add(field + "=" + event.getProperty(field)
                        .map(p -> p.getParameters() + "|" + p.getValue().trim()).orElse("<fehlt>"));
            }
            result.add(signature);
        }
        return result;
    }
}
