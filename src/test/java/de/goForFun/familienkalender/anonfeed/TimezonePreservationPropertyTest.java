package de.goForFun.familienkalender.anonfeed;

import static org.assertj.core.api.Assertions.assertThat;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.jqwik.api.ForAll;
import net.jqwik.api.Tag;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Property 3: Erhaltung von VTIMEZONE und TZID (Task 2.5).
 *
 * <p>Für jeden Quell-{@link Calendar} gilt nach {@code anonymize → serialisieren → reparsen}:
 * <ul>
 *   <li>Für jede von einem {@code DTSTART}/{@code DTEND} referenzierte {@code TZID} existiert im
 *       Ziel eine zugehörige {@code VTIMEZONE}-Komponente.</li>
 *   <li>Die {@code TZID}-Parameterwerte der {@code DTSTART}/{@code DTEND}-Eigenschaften jedes
 *       erzeugten Events (keyed per {@code UID}) sind identisch zur Quelle.</li>
 * </ul>
 *
 * <p><strong>Validates: Requirements 1.7, 6.5</strong>
 */
@Tag("property")
class TimezonePreservationPropertyTest extends CalendarArbitraries {

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    @net.jqwik.api.Property(tries = 100)
    void vtimezoneAndTzidArePreserved(@ForAll("randomCalendars") Calendar source) {
        AnonConfig config = AnonConfig.fromMap(Map.of());

        Calendar anonymized = anonymizer.anonymize(source, config, java.time.Instant.parse("2000-01-01T00:00:00Z"));
        Calendar reparsed = reparse(anonymized.toString());

        // 1) Jede von einem Event referenzierte TZID hat eine VTIMEZONE im Ziel.
        var declaredTzids = vtimezoneIds(reparsed);
        for (VEvent event : reparsed.<VEvent>getComponents(Component.VEVENT)) {
            for (String tzid : referencedTzids(event)) {
                assertThat(declaredTzids)
                        .as("VTIMEZONE für referenzierte TZID '%s' fehlt im Ziel", tzid)
                        .contains(tzid);
            }
        }

        // 2) TZID-Parameter je VEVENT (keyed per UID) identisch zur Quelle.
        Map<String, List<String>> expected = tzidsByUid(source);
        Map<String, List<String>> actual = tzidsByUid(reparsed);

        assertThat(actual.keySet())
                .as("UID-Menge der erzeugten Events weicht von der Quelle ab")
                .isEqualTo(expected.keySet());

        for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
            assertThat(actual.get(entry.getKey()))
                    .as("TZID-Parameter (DTSTART/DTEND) für UID '%s' weichen ab", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    /** Sammelt die TZID-Werte aller deklarierten {@code VTIMEZONE}-Komponenten. */
    private static java.util.Set<String> vtimezoneIds(Calendar calendar) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (VTimeZone vtz : calendar.<VTimeZone>getComponents(Component.VTIMEZONE)) {
            Optional<Property> tzid = vtz.getProperty(Property.TZID);
            if (tzid.isPresent()) {
                ids.add(tzid.get().getValue());
            }
        }
        return ids;
    }

    /** TZID-Parameterwerte, die {@code DTSTART}/{@code DTEND} eines Events referenzieren. */
    private static List<String> referencedTzids(VEvent event) {
        List<String> tzids = new java.util.ArrayList<>();
        tzidOf(event, Property.DTSTART).ifPresent(tzids::add);
        tzidOf(event, Property.DTEND).ifPresent(tzids::add);
        return tzids;
    }

    private static Optional<String> tzidOf(VEvent event, String propertyName) {
        Optional<Property> property = event.getProperty(propertyName);
        if (property.isEmpty()) {
            return Optional.empty();
        }
        Optional<Parameter> tzid = property.get().getParameter(Parameter.TZID);
        return tzid.map(Parameter::getValue);
    }

    /**
     * Ordnet jeder Event-UID die geordnete TZID-Liste [DTSTART, DTEND] zu; fehlender TZID-Parameter
     * wird als leerer String erfasst, sodass „timed mit TZID" von „all-day ohne TZID" unterscheidbar
     * bleibt.
     *
     * <p>Nur Events mit einlesbarer {@code UID} <em>und</em> einlesbarem {@code DTSTART} werden
     * erfasst – exakt die Auslass-Regel des {@link FeedAnonymizer} (Requirement 6.4), damit die
     * Quell-Erwartung mit der Menge der tatsächlich erzeugten Events übereinstimmt.
     */
    private static Map<String, List<String>> tzidsByUid(Calendar calendar) {
        Map<String, List<String>> byUid = new LinkedHashMap<>();
        for (VEvent event : calendar.<VEvent>getComponents(Component.VEVENT)) {
            if (!hasNonBlank(event, Property.UID) || !hasNonBlank(event, Property.DTSTART)) {
                continue;
            }
            String uid = event.getProperty(Property.UID).get().getValue();
            byUid.put(uid, List.of(
                    tzidOf(event, Property.DTSTART).orElse(""),
                    tzidOf(event, Property.DTEND).orElse("")));
        }
        return byUid;
    }

    /** Spiegelt {@code FeedAnonymizer.hasNonBlankProperty}: Property vorhanden und Wert nicht leer. */
    private static boolean hasNonBlank(VEvent event, String propertyName) {
        Optional<Property> property = event.getProperty(propertyName);
        return property.isPresent() && !property.get().getValue().isBlank();
    }

    private static Calendar reparse(String ics) {
        try (var in = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))) {
            return new CalendarBuilder().build(in);
        } catch (Exception e) {
            throw new AssertionError("Erzeugter Feed ist nicht reparsebar:\n" + ics, e);
        }
    }
}
