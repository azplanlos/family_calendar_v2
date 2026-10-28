package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Feature: anonymized-ical-feed, Property 1: Round-Trip-Parsebarkeit des erzeugten Feeds.
 *
 * <p>Für jeden zufällig erzeugten Quell-{@link Calendar} muss der serialisierte Output von
 * {@link FeedAnonymizer#anonymize(Calendar, AnonConfig)} wieder mit einem frischen
 * {@link CalendarBuilder} einlesbar sein, ohne dass eine {@link ParserException} auftritt.
 * Der anonymisierte Feed bleibt also ein wohlgeformtes, RFC-5545-konformes VCALENDAR.
 *
 * <p><strong>Validates: Requirements 1.1, 6.1.</strong>
 */
class FeedAnonymizerRoundTripPropertyTest extends CalendarArbitraries {

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();

    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", "Privat",
            "ANON_PARTICIPANT", "Familienkalender"));

    @Property(tries = 200)
    void anonymizedFeedIsReparsableWithoutParserException(@ForAll("randomCalendars") Calendar source) {
        Calendar anonymized = anonymizer.anonymize(source, config);
        String ics = anonymized.toString();

        assertThatCode(() -> reparse(ics))
                .as("Anonymisierter Feed muss ohne ParserException reparsebar sein:%n%s", ics)
                .doesNotThrowAnyException();
    }

    /** Parst den Output mit demselben Builder wie der Produktionspfad; wirft bei Nicht-Parsebarkeit. */
    private static Calendar reparse(String ics) throws IOException, ParserException {
        try (InputStream in = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))) {
            Calendar reparsed = new CalendarBuilder().build(in);
            assertThat(reparsed).isNotNull();
            return reparsed;
        }
    }
}
