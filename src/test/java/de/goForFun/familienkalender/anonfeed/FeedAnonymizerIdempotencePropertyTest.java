package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

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
 * Feature: anonymized-ical-feed, Property 7: Idempotenz der Anonymisierung.
 *
 * <p>Für jeden zufällig erzeugten Quell-{@link Calendar} gilt: erneutes Einlesen und erneutes
 * Anonymisieren des bereits anonymisierten Feeds verändert das Ergebnis nicht mehr. Formal
 * {@code anonymize(parse(anonymize(src))) ≡ anonymize(src)} bezogen auf
 * <ul>
 *   <li>die Multimenge der (gültigen) {@code UID}s,</li>
 *   <li>die Zeit-/Recurrence-Felder pro {@code UID}
 *       ({@code DTSTART}/{@code DTEND}/{@code DURATION}/{@code RRULE}/{@code RDATE}/{@code EXDATE}/{@code RECURRENCE-ID}),</li>
 *   <li>den Privacy-Zustand pro {@code UID} (privat/nicht-privat),</li>
 *   <li>und die Tatsache, dass Private-Events im zweiten Durchlauf nicht weiter verändert
 *       werden ({@code SUMMARY} bleibt der Platzhalter, keine Inhaltsfelder tauchen wieder
 *       auf).</li>
 * </ul>
 *
 * <p>Verglichen werden ausschließlich gültige Events; Events ohne einlesbare {@code UID}/
 * {@code DTSTART} werden bereits im ersten Durchlauf laut Requirement 6.4 verworfen und sind
 * daher im Vergleich beider Durchläufe nicht relevant.
 *
 * <p><strong>Validates: Requirements 6.3.</strong>
 */
class FeedAnonymizerIdempotencePropertyTest extends CalendarArbitraries {

    /** Zeit- und Wiederholungsfelder, die über beide Durchläufe stabil bleiben müssen. */
    private static final String[] PRESERVED_TIME_FIELDS = {
            net.fortuna.ical4j.model.Property.DTSTART,
            net.fortuna.ical4j.model.Property.DTEND,
            net.fortuna.ical4j.model.Property.DURATION,
            net.fortuna.ical4j.model.Property.RRULE,
            net.fortuna.ical4j.model.Property.RDATE,
            net.fortuna.ical4j.model.Property.EXDATE,
            net.fortuna.ical4j.model.Property.RECURRENCE_ID
    };

    /** Inhaltsfelder, die bei Private-Events auch im zweiten Durchlauf fehlen müssen. */
    private static final String[] CONTENT_FIELDS = {
            net.fortuna.ical4j.model.Property.DESCRIPTION,
            net.fortuna.ical4j.model.Property.LOCATION,
            net.fortuna.ical4j.model.Property.URL,
            net.fortuna.ical4j.model.Property.ATTENDEE,
            net.fortuna.ical4j.model.Property.ORGANIZER,
            net.fortuna.ical4j.model.Property.ATTACH
    };

    /** Markanter Platzhalter, damit der Private-Zustand eindeutig prüfbar ist. */
    private static final String PLACEHOLDER = "Privat";

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", PLACEHOLDER,
            "ANON_PARTICIPANT", "Familienkalender"));

    @Property(tries = 100)
    void secondPassDoesNotChangeTheFeed(@ForAll("randomCalendars") Calendar source) {
        // Erster Durchlauf.
        Calendar once = anonymizer.anonymize(source, config, java.time.Instant.parse("2000-01-01T00:00:00Z"));
        // Zweiter Durchlauf über den (neu eingelesenen) bereits anonymisierten Feed.
        Calendar twice = anonymizer.anonymize(reparse(once.toString()), config, java.time.Instant.parse("2000-01-01T00:00:00Z"));

        List<VEvent> onceEvents = events(once);
        List<VEvent> twiceEvents = events(twice);

        // (1) UID-Multimenge stabil zwischen erstem und zweitem Durchlauf.
        assertThat(uidMultiset(twiceEvents))
                .as("UID-Multimenge muss im zweiten Durchlauf identisch zum ersten sein")
                .isEqualTo(uidMultiset(onceEvents));

        // (2) Zeit-/Recurrence-Signaturen pro UID stabil (Multimenge, duplikat- und
        //     reihenfolge-robust).
        Map<String, List<List<String>>> onceByUid = timeSignaturesByUid(onceEvents);
        Map<String, List<List<String>>> twiceByUid = timeSignaturesByUid(twiceEvents);
        for (Map.Entry<String, List<List<String>>> entry : onceByUid.entrySet()) {
            String uid = entry.getKey();
            assertThat(twiceByUid.get(uid))
                    .as("Zeit-/Recurrence-Signaturen müssen pro UID=%s über beide Durchläufe gleich sein", uid)
                    .containsExactlyInAnyOrderElementsOf(entry.getValue());
        }

        // (3) Privacy-Zustand pro UID stabil (Multimenge privat/nicht-privat je UID).
        assertThat(privacyStateByUid(twiceEvents))
                .as("Privacy-Zustand pro UID muss über beide Durchläufe identisch sein")
                .isEqualTo(privacyStateByUid(onceEvents));

        // (4) Private-Events werden im zweiten Durchlauf nicht weiter verändert:
        //     SUMMARY bleibt der Platzhalter, keine Inhaltsfelder sind wieder aufgetaucht.
        for (VEvent event : twiceEvents) {
            if (!FeedAnonymizer.isPrivate(event)) {
                continue;
            }
            String uid = uid(event).orElse("<keine UID>");
            String serialized = event.toString();

            List<net.fortuna.ical4j.model.Property> summaries =
                    event.getProperties(net.fortuna.ical4j.model.Property.SUMMARY);
            assertThat(summaries)
                    .as("Private-Event %s muss auch nach dem zweiten Durchlauf genau einen SUMMARY haben:%n%s",
                            uid, serialized)
                    .hasSize(1);
            assertThat(summaries.get(0).getValue())
                    .as("Private-Event %s SUMMARY muss im zweiten Durchlauf der Platzhalter bleiben:%n%s",
                            uid, serialized)
                    .isEqualTo(PLACEHOLDER);

            for (String field : CONTENT_FIELDS) {
                assertThat(event.getProperties(field))
                        .as("Private-Event %s darf im zweiten Durchlauf kein %s-Feld enthalten:%n%s",
                                uid, field, serialized)
                        .isEmpty();
            }
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private static List<VEvent> events(Calendar calendar) {
        List<VEvent> result = new ArrayList<>();
        for (CalendarComponent component : calendar.<CalendarComponent>getComponents(Component.VEVENT)) {
            result.add((VEvent) component);
        }
        return result;
    }

    private static Optional<String> uid(VEvent event) {
        return event.getProperty(net.fortuna.ical4j.model.Property.UID)
                .map(net.fortuna.ical4j.model.Property::getValue);
    }

    /** Multimenge der UIDs als UID -> Anzahl (TreeMap für stabilen Vergleich). */
    private static Map<String, Integer> uidMultiset(List<VEvent> events) {
        Map<String, Integer> counts = new TreeMap<>();
        for (VEvent event : events) {
            uid(event).ifPresent(uid -> counts.merge(uid, 1, Integer::sum));
        }
        return counts;
    }

    /** Privacy-Zustand als UID -> Liste {privat?} (Multimenge je UID, reihenfolge-robust). */
    private static Map<String, List<Boolean>> privacyStateByUid(List<VEvent> events) {
        Map<String, List<Boolean>> byUid = new TreeMap<>();
        for (VEvent event : events) {
            uid(event).ifPresent(uid -> {
                List<Boolean> states =
                        byUid.computeIfAbsent(uid, k -> new ArrayList<>());
                states.add(FeedAnonymizer.isPrivate(event));
                states.sort(java.util.Comparator.naturalOrder());
            });
        }
        return byUid;
    }

    private static Map<String, List<List<String>>> timeSignaturesByUid(List<VEvent> events) {
        Map<String, List<List<String>>> byUid = new TreeMap<>();
        for (VEvent event : events) {
            uid(event).ifPresent(uid ->
                    byUid.computeIfAbsent(uid, k -> new ArrayList<>()).add(timeSignature(event)));
        }
        return byUid;
    }

    private static List<String> timeSignature(VEvent event) {
        List<String> signature = new ArrayList<>();
        for (String field : PRESERVED_TIME_FIELDS) {
            signature.add(field + "=" + propertyValues(event, field));
        }
        return signature;
    }

    private static List<String> propertyValues(VEvent event, String name) {
        List<String> values = new ArrayList<>();
        for (net.fortuna.ical4j.model.Property property : event.getProperties(name)) {
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
