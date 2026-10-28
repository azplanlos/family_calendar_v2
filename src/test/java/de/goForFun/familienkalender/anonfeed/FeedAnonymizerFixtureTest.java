package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beispiel-Tests für die konkrete Transformation gegen die reale Outlook-Fixture
 * {@code src/test/resources/outlook-published-sample.ics} (Task 2.11).
 *
 * <p>Im Gegensatz zu den Property-Tests prüft diese Klasse exakte, konkrete Werte für die
 * aus Outlook stammenden Events und deckt gezielt die Outlook-Eigenheiten ab (leeres
 * {@code LOCATION:}, {@code DESCRIPTION:\n}).
 *
 * <ul>
 *   <li>Public-Event „Test normal" bleibt wertgleich (Requirements 2.5, 3.1, 3.2, 3.3, 3.4).</li>
 *   <li>Private-Event „Privater Termin" → {@code SUMMARY=Privat}, keine Inhaltsfelder,
 *       {@code DTSTART}/{@code DTEND}/{@code TZID}/{@code UID} erhalten
 *       (Requirements 1.6, 2.1, 2.2, 2.3).</li>
 *   <li>Feed mit {@code VEVENT} ohne {@code UID}/{@code DTSTART} → fehlt im Output
 *       (Requirement 6.4).</li>
 * </ul>
 *
 * <p>Validates: Requirements 1.6, 2.1, 2.2, 2.3, 2.5, 3.1, 3.2, 3.3, 3.4, 6.4.
 */
class FeedAnonymizerFixtureTest {

    /** UID des öffentlichen Events „Test normal" aus der Fixture. */
    private static final String PUBLIC_UID =
            "040000008200E00074C5B7101A82E0080000000036953804C066DD010000000000000000"
                    + "100000005980B267F07AF8478A0935B5A2A07267";

    /** UID des privaten Events „Privater Termin" aus der Fixture. */
    private static final String PRIVATE_UID =
            "040000008200E00074C5B7101A82E008000000002E78C80CC066DD010000000000000000"
                    + "100000009C31329527338F4C893933EB5DD0379E";

    private static final String TZID = "Romance Standard Time";

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", "Privat"));

    @Nested
    class PublicEventPassthrough {

        @Test
        void publicEventBleibtWertgleich() {
            Calendar source = CalendarArbitraries.loadFixture();
            Calendar anonymized = anonymizer.anonymize(source, config);

            VEvent sourceEvent = eventByUid(source, PUBLIC_UID);
            VEvent outEvent = eventByUid(anonymized, PUBLIC_UID);

            // SUMMARY wertidentisch (kein Platzhalter).
            assertThat(value(outEvent, Property.SUMMARY)).isEqualTo("Test normal");

            // Zeitfelder inkl. TZID erhalten.
            assertThat(value(outEvent, Property.DTSTART)).isEqualTo(value(sourceEvent, Property.DTSTART));
            assertThat(value(outEvent, Property.DTEND)).isEqualTo(value(sourceEvent, Property.DTEND));
            assertThat(tzid(outEvent, Property.DTSTART)).contains(TZID);
            assertThat(tzid(outEvent, Property.DTEND)).contains(TZID);

            // UID & CLASS erhalten.
            assertThat(value(outEvent, Property.UID)).isEqualTo(PUBLIC_UID);
            assertThat(value(outEvent, Property.CLASS)).isEqualTo("PUBLIC");

            // Outlook-Eigenheiten: leeres LOCATION und DESCRIPTION:\n bleiben als Passthrough
            // erhalten (vorhanden, aber wertlos) – nichts wird beim Public-Event entfernt.
            assertThat(outEvent.getProperty(Property.LOCATION))
                    .as("leeres LOCATION bleibt beim Public-Event erhalten")
                    .isPresent();
            assertThat(value(outEvent, Property.LOCATION)).isEqualTo(value(sourceEvent, Property.LOCATION));

            assertThat(outEvent.getProperty(Property.DESCRIPTION))
                    .as("DESCRIPTION:\\n bleibt beim Public-Event erhalten")
                    .isPresent();
            assertThat(value(outEvent, Property.DESCRIPTION)).isEqualTo(value(sourceEvent, Property.DESCRIPTION));

            // Weitere Felder wertidentisch durchgereicht.
            assertThat(value(outEvent, Property.STATUS)).isEqualTo("CONFIRMED");
            assertThat(value(outEvent, Property.TRANSP)).isEqualTo("OPAQUE");
        }
    }

    @Nested
    class PrivateEventAnonymization {

        @Test
        void privateEventWirdAnonymisiert() {
            Calendar source = CalendarArbitraries.loadFixture();
            Calendar anonymized = anonymizer.anonymize(source, config);

            VEvent sourceEvent = eventByUid(source, PRIVATE_UID);
            VEvent outEvent = eventByUid(anonymized, PRIVATE_UID);

            // SUMMARY durch Platzhalter ersetzt.
            assertThat(value(outEvent, Property.SUMMARY)).isEqualTo("Privat");

            // Keine Inhaltsfelder mehr.
            assertThat(outEvent.getProperty(Property.DESCRIPTION)).isEmpty();
            assertThat(outEvent.getProperty(Property.LOCATION)).isEmpty();
            assertThat(outEvent.getProperty(Property.URL)).isEmpty();
            assertThat(outEvent.getProperties(Property.ATTENDEE)).isEmpty();
            assertThat(outEvent.getProperty(Property.ORGANIZER)).isEmpty();
            assertThat(outEvent.getProperties(Property.ATTACH)).isEmpty();

            // Zeitfelder inkl. TZID, UID erhalten.
            assertThat(value(outEvent, Property.DTSTART)).isEqualTo(value(sourceEvent, Property.DTSTART));
            assertThat(value(outEvent, Property.DTEND)).isEqualTo(value(sourceEvent, Property.DTEND));
            assertThat(tzid(outEvent, Property.DTSTART)).contains(TZID);
            assertThat(tzid(outEvent, Property.DTEND)).contains(TZID);
            assertThat(value(outEvent, Property.UID)).isEqualTo(PRIVATE_UID);

            // Original-Summary leakt nicht in den serialisierten Feed.
            assertThat(anonymized.toString()).doesNotContain("Privater Termin");
        }
    }

    @Nested
    class EventOhneUidOderDtStartWirdAusgelassen {

        /** Fixture-Variante mit einem zusätzlichen, ungültigen VEVENT (ohne UID und DTSTART). */
        private static final String ICS_WITH_INVALID_EVENT = String.join("\r\n",
                "BEGIN:VCALENDAR",
                "METHOD:PUBLISH",
                "PRODID:Microsoft Exchange Server 2010",
                "VERSION:2.0",
                "BEGIN:VEVENT",
                "UID:valid-event-123",
                "SUMMARY:Gültiges Event",
                "DTSTART:20261028T103000Z",
                "DTEND:20261028T110000Z",
                "CLASS:PUBLIC",
                "DTSTAMP:20261028T093825Z",
                "END:VEVENT",
                "BEGIN:VEVENT",
                "SUMMARY:Kaputtes Event ohne UID und DTSTART",
                "DTSTAMP:20261028T093825Z",
                "END:VEVENT",
                "END:VCALENDAR",
                "");

        @Test
        void veventOhneUidUndDtStartFehltImOutput() {
            Calendar source = parse(ICS_WITH_INVALID_EVENT);
            Calendar anonymized = anonymizer.anonymize(source, config);

            List<VEvent> outEvents = events(anonymized);

            // Nur das gültige Event bleibt übrig; das ungültige ist ausgelassen (6.4).
            assertThat(outEvents).hasSize(1);
            assertThat(value(outEvents.get(0), Property.UID)).isEqualTo("valid-event-123");
            assertThat(anonymized.toString()).doesNotContain("Kaputtes Event");
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private static List<VEvent> events(Calendar calendar) {
        List<VEvent> result = new java.util.ArrayList<>();
        for (CalendarComponent component : calendar.<CalendarComponent>getComponents(Component.VEVENT)) {
            result.add((VEvent) component);
        }
        return result;
    }

    private static VEvent eventByUid(Calendar calendar, String uid) {
        return events(calendar).stream()
                .filter(e -> uid.equals(value(e, Property.UID)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Event mit UID nicht gefunden: " + uid));
    }

    private static String value(VEvent event, String property) {
        return event.getProperty(property).map(Property::getValue).orElse(null);
    }

    private static Optional<String> tzid(VEvent event, String property) {
        Optional<Property> prop = event.getProperty(property);
        if (prop.isEmpty()) {
            return Optional.empty();
        }
        Optional<Parameter> tzid = prop.get().getParameter(Parameter.TZID);
        return tzid.map(Parameter::getValue);
    }

    private static Calendar parse(String ics) {
        try (InputStream in = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))) {
            return new CalendarBuilder().build(in);
        } catch (IOException | ParserException ex) {
            throw new IllegalStateException("ICS nicht parsebar:\n" + ics, ex);
        }
    }
}
