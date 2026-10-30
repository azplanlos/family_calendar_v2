package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.ForAll;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: anonymized-ical-feed, Property 2: Erhaltung von UID und Zeitfeldern pro Event.
 *
 * <p>Für jedes Quell-{@link VEvent} mit einlesbarer {@code UID} und {@code DTSTART} existiert
 * nach dem Round-Trip (anonymisieren → serialisieren → neu einlesen) genau ein {@link VEvent}
 * mit identischer {@code UID}, identischem {@code DTSTART} und – sofern in der Quelle
 * vorhanden – identischem {@code DTEND}/{@code DURATION}-Wert. Die Wiederholungsfelder
 * ({@code RRULE}/{@code RDATE}/{@code EXDATE}/{@code RECURRENCE-ID}) bleiben unverändert.
 *
 * <p>Verglichen wird die Multimenge der gültigen {@code UID}s (Events ohne einlesbare
 * {@code UID}/{@code DTSTART} werden laut Requirement 6.4 bewusst verworfen, daher gehen
 * nur gültige Quell-Events in den Vergleich ein) sowie pro {@code UID} die Zeit- und
 * Recurrence-Felder.
 *
 * <p><strong>Validates: Requirements 1.1, 2.3, 3.3, 6.2.</strong>
 */
class FeedAnonymizerUidTimePropertyTest extends CalendarArbitraries {

    /** Zeit- und Wiederholungsfelder, die pro UID erhalten bleiben müssen. */
    private static final String[] PRESERVED_TIME_FIELDS = {
            Property.DTSTART, Property.DTEND, Property.DURATION,
            Property.RRULE, Property.RDATE, Property.EXDATE, Property.RECURRENCE_ID
    };

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", "Privat",
            "ANON_PARTICIPANT", "Familienkalender"));

    @net.jqwik.api.Property(tries = 100)
    void uidAndTimeFieldsArePreservedPerEvent(@ForAll("randomCalendars") Calendar source) {
        Calendar anonymized = anonymizer.anonymize(source, config, java.time.Instant.parse("2000-01-01T00:00:00Z"));
        Calendar reparsed = reparse(anonymized.toString());

        // Nur gültige Quell-Events (mit einlesbarer UID + DTSTART) sind zu erhalten (6.4).
        List<VEvent> validSourceEvents = validEvents(source);
        List<VEvent> outputEvents = events(reparsed);

        // Multimenge der gültigen UIDs: vorher == nachher.
        assertThat(uidMultiset(outputEvents))
                .as("Multimenge der gültigen UIDs muss vor und nach dem Round-Trip gleich sein")
                .isEqualTo(uidMultiset(validSourceEvents));

        // Pro UID: Multimenge der Zeit-/Recurrence-Feld-Signaturen identisch. Da der Generator
        // mehrere Events mit identischer UID erzeugen kann, wird je UID die Multimenge der
        // Signaturen verglichen (nicht "genau ein Event"), sodass die Zuordnung reihenfolge-
        // und duplikat-robust bleibt.
        Map<String, List<List<String>>> sourceByUid = timeSignaturesByUid(validSourceEvents);
        Map<String, List<List<String>>> outputByUid = timeSignaturesByUid(outputEvents);

        for (Map.Entry<String, List<List<String>>> entry : sourceByUid.entrySet()) {
            String uid = entry.getKey();
            assertThat(outputByUid.get(uid))
                    .as("Zeit-/Recurrence-Feld-Signaturen müssen pro UID=%s (Multimenge) erhalten bleiben", uid)
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** Gültige Quell-Events: einlesbare, nicht-leere UID UND DTSTART (vgl. Requirement 6.4). */
    private static List<VEvent> validEvents(Calendar calendar) {
        List<VEvent> valid = new ArrayList<>();
        for (VEvent event : events(calendar)) {
            if (hasNonBlank(event, Property.UID) && hasNonBlank(event, Property.DTSTART)) {
                valid.add(event);
            }
        }
        return valid;
    }

    private static List<VEvent> events(Calendar calendar) {
        List<VEvent> result = new ArrayList<>();
        for (CalendarComponent component : calendar.<CalendarComponent>getComponents(Component.VEVENT)) {
            result.add((VEvent) component);
        }
        return result;
    }

    private static boolean hasNonBlank(VEvent event, String name) {
        return event.getProperty(name)
                .map(Property::getValue)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    private static Optional<String> uid(VEvent event) {
        return event.getProperty(Property.UID).map(Property::getValue);
    }

    /** Multimenge der UIDs als UID -> Anzahl (TreeMap für stabilen, reihenfolge-unabhängigen Vergleich). */
    private static Map<String, Integer> uidMultiset(List<VEvent> events) {
        Map<String, Integer> counts = new TreeMap<>();
        for (VEvent event : events) {
            uid(event).ifPresent(uid -> counts.merge(uid, 1, Integer::sum));
        }
        return counts;
    }

    /**
     * Gruppiert Events nach UID und bildet je Event die geordnete "Zeit-Signatur" – die Liste
     * der Wertemengen aller erhaltenen Zeit-/Recurrence-Felder. So lassen sich auch mehrere
     * Events mit identischer UID als Multimenge korrekt vergleichen.
     */
    private static Map<String, List<List<String>>> timeSignaturesByUid(List<VEvent> events) {
        Map<String, List<List<String>>> byUid = new TreeMap<>();
        for (VEvent event : events) {
            uid(event).ifPresent(uid ->
                    byUid.computeIfAbsent(uid, k -> new ArrayList<>()).add(timeSignature(event)));
        }
        return byUid;
    }

    /** Zeit-Signatur eines Events: pro erhaltenem Feld die sortierte Werteliste, feldweise konkateniert. */
    private static List<String> timeSignature(VEvent event) {
        List<String> signature = new ArrayList<>();
        for (String field : PRESERVED_TIME_FIELDS) {
            signature.add(field + "=" + propertyValues(event, field));
        }
        return signature;
    }

    /**
     * Liefert die (Multi-)Menge der Werte einer Property, sortiert, damit Reihenfolge bei
     * Mehrfach-Properties (z. B. mehrere {@code RDATE}) den Vergleich nicht verfälscht.
     */
    private static List<String> propertyValues(VEvent event, String name) {
        List<String> values = new ArrayList<>();
        for (Property property : event.getProperties(name)) {
            values.add(property.getValue());
        }
        values.sort(java.util.Comparator.naturalOrder());
        return values;
    }

    /** Parst den Output mit demselben Builder wie der Produktionspfad. */
    private static Calendar reparse(String ics) {
        try (InputStream in = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))) {
            return new CalendarBuilder().build(in);
        } catch (IOException | ParserException ex) {
            throw new IllegalStateException("Anonymisierter Feed nicht reparsebar:\n" + ics, ex);
        }
    }
}
