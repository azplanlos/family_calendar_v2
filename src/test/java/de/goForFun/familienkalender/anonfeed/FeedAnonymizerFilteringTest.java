package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beispiel-Tests für die Filterung vergangener und als frei markierter Events
 * (Requirement 9 / Design-Property 9).
 *
 * <p>Verifiziert gegen handgebaute iCal-Feeds mit kontrolliertem Referenzzeitpunkt {@code now}:
 * <ul>
 *   <li>Events mit Status „frei" ({@code TRANSP:TRANSPARENT} bzw. {@code X-MICROSOFT-CDO-BUSYSTATUS:FREE})
 *       werden ausgelassen – auch private.</li>
 *   <li>Events, deren Ende {@code <= now} liegt, werden ausgelassen; laufende/künftige bleiben.</li>
 *   <li>Serientermine ({@code RRULE}) bleiben trotz vergangenem {@code DTSTART}.</li>
 *   <li>Ganztags-Events ohne {@code DTEND} enden erst am Folgetag (bleiben am selben Tag sichtbar).</li>
 * </ul>
 */
class FeedAnonymizerFilteringTest {

    private final FeedAnonymizer anonymizer = new FeedAnonymizer();
    private final AnonConfig config = AnonConfig.fromMap(Map.of(
            "ANON_SOURCE_FEED", "https://example.com/source.ics",
            "ANON_PLACEHOLDER", "Privat"));

    private static final Instant NOW = Instant.parse("2040-06-15T12:00:00Z");

    @Test
    void freeTransparentEventIsDropped() {
        Calendar src = parse(cal(
                vevent("ev-free-transp", "DTSTART:20400701T100000Z", "DTEND:20400701T110000Z",
                        "TRANSP:TRANSPARENT")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).doesNotContain("ev-free-transp");
    }

    @Test
    void freeBusyStatusEventIsDropped() {
        Calendar src = parse(cal(
                vevent("ev-free-ms", "DTSTART:20400701T100000Z", "DTEND:20400701T110000Z",
                        "X-MICROSOFT-CDO-BUSYSTATUS:FREE")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).doesNotContain("ev-free-ms");
    }

    @Test
    void freePrivateEventIsAlsoDropped() {
        Calendar src = parse(cal(
                vevent("ev-free-private", "DTSTART:20400701T100000Z", "DTEND:20400701T110000Z",
                        "CLASS:PRIVATE", "TRANSP:TRANSPARENT")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).doesNotContain("ev-free-private");
    }

    @Test
    void busyFutureEventIsKept() {
        Calendar src = parse(cal(
                vevent("ev-busy-future", "DTSTART:20400701T100000Z", "DTEND:20400701T110000Z",
                        "TRANSP:OPAQUE")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).contains("ev-busy-future");
    }

    @Test
    void pastEventIsDropped() {
        // Ende 2040-06-15 11:00Z liegt vor NOW (12:00Z) → vergangen.
        Calendar src = parse(cal(
                vevent("ev-past", "DTSTART:20400615T100000Z", "DTEND:20400615T110000Z")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).doesNotContain("ev-past");
    }

    @Test
    void ongoingEventSpanningNowIsKept() {
        // Beginn vor, Ende nach NOW → läuft gerade, bleibt sichtbar.
        Calendar src = parse(cal(
                vevent("ev-ongoing", "DTSTART:20400615T110000Z", "DTEND:20400615T130000Z")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).contains("ev-ongoing");
    }

    @Test
    void recurringEventWithPastDtStartIsKept() {
        // DTSTART in der Vergangenheit, aber RRULE → nicht als vergangen auslassen.
        Calendar src = parse(cal(
                vevent("ev-recur", "DTSTART:20400101T100000Z", "DTEND:20400101T110000Z",
                        "RRULE:FREQ=WEEKLY")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).contains("ev-recur");
    }

    @Test
    void allDayEventEndsNextDay_todayStillVisible() {
        // Ganztags-Event am NOW-Tag (15.06.) ohne DTEND → Ende erst 16.06. → bleibt sichtbar.
        Calendar src = parse(cal(
                vevent("ev-allday-today", "DTSTART;VALUE=DATE:20400615")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).contains("ev-allday-today");
    }

    @Test
    void allDayEventYesterdayIsDropped() {
        // Ganztags-Event am Vortag (14.06.) ohne DTEND → Ende 15.06. 00:00 (lokal) <= NOW → vergangen.
        Calendar src = parse(cal(
                vevent("ev-allday-yesterday", "DTSTART;VALUE=DATE:20400613")));
        List<String> uids = outputUids(anonymizer.anonymize(src, config, NOW));
        assertThat(uids).doesNotContain("ev-allday-yesterday");
    }

    // ---- helpers ----

    private static String vevent(String uid, String... props) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:").append(uid).append("\r\n");
        sb.append("DTSTAMP:20400101T000000Z\r\n");
        sb.append("SUMMARY:Test ").append(uid).append("\r\n");
        for (String p : props) {
            sb.append(p).append("\r\n");
        }
        sb.append("END:VEVENT\r\n");
        return sb.toString();
    }

    private static String cal(String... vevents) {
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//Test//Filtering//EN\r\n");
        for (String v : vevents) {
            sb.append(v);
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static Calendar parse(String ics) {
        try {
            return new CalendarBuilder().build(
                    new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8)));
        } catch (IOException | ParserException e) {
            throw new RuntimeException(e);
        }
    }

    private static List<String> outputUids(Calendar cal) {
        List<String> uids = new ArrayList<>();
        for (CalendarComponent c : cal.<CalendarComponent>getComponents(Component.VEVENT)) {
            Optional<Property> uid = ((VEvent) c).getProperty(Property.UID);
            uid.ifPresent(p -> uids.add(p.getValue()));
        }
        return uids;
    }
}
