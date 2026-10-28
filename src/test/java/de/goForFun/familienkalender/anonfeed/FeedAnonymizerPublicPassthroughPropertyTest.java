package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: anonymized-ical-feed, Property 5: Public-Passthrough ist wertidentisch.
 *
 * <p>Für jeden zufällig erzeugten Quell-{@link Calendar} gilt nach
 * {@link FeedAnonymizer#anonymize(Calendar, AnonConfig)}: Jedes gültige, nicht-private
 * Quell-Event (CLASS fehlend/{@code PUBLIC}/unbekannt) wird <em>wertidentisch</em>
 * durchgereicht. Für das per {@code UID} zugeordnete Ausgabe-Event gilt:
 * <ul>
 *   <li>Die vollständige Property-Menge der Quelle erscheint unverändert (gleiche Properties
 *       mit gleichen Werten und Parametern) – keine Normalisierung von Zeichen, Leerraum oder
 *       Kodierung (Requirements 3.1, 3.2, 3.3).</li>
 *   <li>In der Quelle fehlende Inhaltsfelder bleiben auch im Ziel abwesend; es wird kein
 *       leerer oder ersatzweiser Wert erzeugt (Requirement 3.4).</li>
 *   <li>Insgesamt: Property-Menge Ziel == Property-Menge Quelle (Requirement 2.5).</li>
 * </ul>
 *
 * <p>Der Vergleich erfolgt über die serialisierten Property-Zeilen als Multimenge, wodurch
 * sowohl Property-Namen als auch Werte, Parameter und Vielfachheiten wertgenau abgeglichen
 * werden – ohne Normalisierung.
 *
 * <p><strong>Validates: Requirements 2.5, 3.1, 3.2, 3.3, 3.4.</strong>
 */
class FeedAnonymizerPublicPassthroughPropertyTest extends CalendarArbitraries {

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", "Privat",
            "ANON_PARTICIPANT", "Familienkalender"));

    @Property(tries = 100)
    void publicEventsAreCarriedOverValueIdentically(@ForAll("calendarSpecs") CalendarSpec spec) {
        Calendar source = buildCalendar(spec);
        Calendar anonymized = anonymizer.anonymize(source, config);

        // Der FeedAnonymizer iteriert die Quell-VEVENTs der Reihe nach, lässt ungültige Events
        // (ohne einlesbare UID/DTSTART) aus und hängt die übrigen in gleicher Reihenfolge an.
        // Daher entsprechen sich gültige Quell-Events und Ausgabe-Events positionsweise. Diese
        // Zuordnung ist robust gegen (im Generator mögliche) UID-Kollisionen.
        List<VEvent> validSourceEvents = validSourceEventsInOrder(source);
        List<VEvent> outputEvents = eventsInOrder(anonymized);

        assertThat(outputEvents)
                .as("Anzahl der Ausgabe-Events muss der Anzahl gültiger Quell-Events entsprechen")
                .hasSameSizeAs(validSourceEvents);

        for (int i = 0; i < validSourceEvents.size(); i++) {
            VEvent sourceEvent = validSourceEvents.get(i);
            // Private-Events werden anonymisiert; hier nur Passthrough (nicht-private) prüfen.
            if (FeedAnonymizer.isPrivate(sourceEvent)) {
                continue;
            }
            VEvent outputEvent = outputEvents.get(i);

            String uid = sourceEvent.getProperty(net.fortuna.ical4j.model.Property.UID)
                    .map(net.fortuna.ical4j.model.Property::getValue).orElse("<keine UID>");

            List<String> sourceProps = serializedProperties(sourceEvent);
            List<String> outputProps = serializedProperties(outputEvent);

            // Property-Menge (mit Werten, Parametern und Vielfachheiten) muss identisch sein.
            // Fehlende Inhaltsfelder in der Quelle erscheinen so auch nicht im Ziel (3.4).
            assertThat(outputProps)
                    .as("Public-Event %s muss wertidentisch durchgereicht werden.%n"
                            + "Quelle:%n%s%nZiel:%n%s", uid,
                            String.join("\n", sourceProps), String.join("\n", outputProps))
                    .containsExactlyInAnyOrderElementsOf(sourceProps);
        }
    }

    /** Gültige Quell-Events (einlesbare UID und DTSTART) in Dokumentreihenfolge. */
    private static List<VEvent> validSourceEventsInOrder(Calendar calendar) {
        List<VEvent> result = new ArrayList<>();
        for (VEvent event : eventsInOrder(calendar)) {
            boolean hasUid = event.getProperty(net.fortuna.ical4j.model.Property.UID)
                    .map(net.fortuna.ical4j.model.Property::getValue)
                    .filter(v -> !v.isBlank()).isPresent();
            boolean hasDtStart = event.getProperty(net.fortuna.ical4j.model.Property.DTSTART)
                    .map(net.fortuna.ical4j.model.Property::getValue)
                    .filter(v -> !v.isBlank()).isPresent();
            if (hasUid && hasDtStart) {
                result.add(event);
            }
        }
        return result;
    }

    /** Alle {@code VEVENT}-Komponenten eines Kalenders in Dokumentreihenfolge. */
    private static List<VEvent> eventsInOrder(Calendar calendar) {
        List<VEvent> events = new ArrayList<>();
        for (CalendarComponent component : calendar.<CalendarComponent>getComponents(Component.VEVENT)) {
            events.add((VEvent) component);
        }
        return events;
    }

    /**
     * Serialisiert jede Property eines Events als eigene Zeile. Der Vergleich dieser Multimenge
     * deckt Property-Name, Wert, Parameter und Vielfachheit wertgenau ab – ohne Normalisierung,
     * da ical4j die Original-Property-Objekte der Kopie unverändert serialisiert.
     */
    private static List<String> serializedProperties(VEvent event) {
        List<String> lines = new ArrayList<>();
        for (net.fortuna.ical4j.model.Property property : event.getProperties()) {
            lines.add(property.toString());
        }
        return lines;
    }
}
