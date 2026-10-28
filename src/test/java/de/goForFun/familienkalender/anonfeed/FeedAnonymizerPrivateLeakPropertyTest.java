package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: anonymized-ical-feed, Property 4: Private-Events leaken keinen Inhalt.
 *
 * <p>Für jeden zufällig erzeugten Quell-{@link Calendar} gilt nach
 * {@link FeedAnonymizer#anonymize(Calendar, AnonConfig)}: Jedes als privat markierte Event
 * (CLASS {@code PRIVATE}/{@code CONFIDENTIAL}) besitzt im Output genau den konfigurierten
 * Platzhalter als {@code SUMMARY}, und keine der Inhalts-Properties
 * {@code DESCRIPTION}/{@code LOCATION}/{@code URL}/{@code ATTENDEE}/{@code ORGANIZER}/{@code ATTACH}
 * ist mehr vorhanden. Zusätzlich darf der ursprüngliche (nicht-leere) Summary-Text eines
 * Private-Events nirgends im serialisierten Event auftauchen.
 *
 * <p><strong>Validates: Requirements 1.6, 2.1, 2.2.</strong>
 */
class FeedAnonymizerPrivateLeakPropertyTest extends CalendarArbitraries {

    /** Bewusst markanter, unverwechselbarer Platzhalter, damit Vergleiche eindeutig sind. */
    private static final String PLACEHOLDER = "GEHEIM-PLATZHALTER-42";

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", PLACEHOLDER,
            "ANON_PARTICIPANT", "Familienkalender"));

    /** Inhalts-Properties, die bei Private-Events vollständig fehlen müssen (Requirement 1.6/2.2). */
    private static final String[] CONTENT_FIELDS = {
            net.fortuna.ical4j.model.Property.DESCRIPTION,
            net.fortuna.ical4j.model.Property.LOCATION,
            net.fortuna.ical4j.model.Property.URL,
            net.fortuna.ical4j.model.Property.ATTENDEE,
            net.fortuna.ical4j.model.Property.ORGANIZER,
            net.fortuna.ical4j.model.Property.ATTACH
    };

    @Property(tries = 200)
    void privateEventsLeakNoContent(@ForAll("calendarSpecs") CalendarSpec spec) {
        Calendar source = buildCalendar(spec);
        Calendar anonymized = anonymizer.anonymize(source, config);

        // Erwartete Original-Summaries der gültigen Private-Events, per UID zuordenbar.
        Map<String, String> privateSummariesByUid = new HashMap<>();
        for (EventSpec e : validEvents(spec)) {
            if (e.isPrivate()) {
                privateSummariesByUid.put(e.uid(), e.summary());
            }
        }

        for (CalendarComponent component : anonymized.<CalendarComponent>getComponents(Component.VEVENT)) {
            VEvent event = (VEvent) component;
            if (!FeedAnonymizer.isPrivate(event)) {
                continue;
            }

            String uid = event.getProperty(net.fortuna.ical4j.model.Property.UID)
                    .map(net.fortuna.ical4j.model.Property::getValue).orElse(null);
            String serialized = event.toString();

            // (1) SUMMARY == Platzhalter.
            List<net.fortuna.ical4j.model.Property> summaries =
                    event.getProperties(net.fortuna.ical4j.model.Property.SUMMARY);
            assertThat(summaries)
                    .as("Private-Event %s muss genau einen SUMMARY-Platzhalter haben:%n%s", uid, serialized)
                    .hasSize(1);
            assertThat(summaries.get(0).getValue())
                    .as("Private-Event %s SUMMARY muss der Platzhalter sein:%n%s", uid, serialized)
                    .isEqualTo(PLACEHOLDER);

            // (2) Keine Inhalts-Properties mehr vorhanden.
            for (String field : CONTENT_FIELDS) {
                assertThat(event.getProperties(field))
                        .as("Private-Event %s darf keine %s-Property mehr enthalten:%n%s",
                                uid, field, serialized)
                        .isEmpty();
            }

            // (3) Original-Summary-Text taucht nirgends im serialisierten Event auf.
            String originalSummary = privateSummariesByUid.get(uid);
            if (originalSummary != null && !originalSummary.isBlank()
                    && !originalSummary.equals(PLACEHOLDER)) {
                assertThat(serialized)
                        .as("Serialisiertes Private-Event %s darf die Original-Summary nicht leaken:%n%s",
                                uid, serialized)
                        .doesNotContain(originalSummary);
            }
        }
    }
}
