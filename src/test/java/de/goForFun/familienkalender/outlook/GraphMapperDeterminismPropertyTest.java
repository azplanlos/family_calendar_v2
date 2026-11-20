package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DtStart;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 17: Das Mapping ist deterministisch und frei von
 * Umgebungszugriffen.
 *
 * <p><b>Validates: Requirements 5.15, 5.16</b>
 */
class GraphMapperDeterminismPropertyTest extends GraphArbitraries {

    @Provide
    Arbitrary<ZoneId> appZones() {
        return Arbitraries.of("Europe/Berlin", "UTC", "America/New_York", "Asia/Kolkata")
                .map(ZoneId::of);
    }

    @Property(tries = 200)
    void mappingTwiceYieldsByteIdenticalOutput(
            @ForAll("graphEventLists") List<JsonNode> events,
            @ForAll("appZones") ZoneId zone) throws Exception {
        byte[] first = serialize(GraphCalendarMapper.toCalendar(events, zone).calendar());
        Thread.sleep(2); // Systemzeit verstreichen lassen
        byte[] second = serialize(GraphCalendarMapper.toCalendar(events, zone).calendar());
        assertTrue(java.util.Arrays.equals(first, second), "Serialisierung nicht byte-identisch");
    }

    @Property(tries = 200)
    void dtstampEqualsDtstartInUtc(
            @ForAll("graphEventLists") List<JsonNode> events,
            @ForAll("appZones") ZoneId zone) {
        Calendar calendar = GraphCalendarMapper.toCalendar(events, zone).calendar();
        for (CalendarComponent c : calendar.getComponents()) {
            if (!(c instanceof VEvent ve)) {
                continue;
            }
            DtStart<?> start = ve.<DtStart<?>>getProperty(net.fortuna.ical4j.model.Property.DTSTART).orElseThrow();
            Instant expected = toUtcInstant(start.getDate());
            Instant stamp = ve.getDateTimeStamp().getDate();
            assertEquals(expected, stamp);
        }
    }

    private static Instant toUtcInstant(Temporal t) {
        if (t instanceof Instant i) {
            return i;
        }
        if (t instanceof ZonedDateTime z) {
            return z.toInstant();
        }
        if (t instanceof LocalDate d) {
            return d.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        throw new IllegalStateException("Unerwarteter Typ: " + t.getClass());
    }

    private static byte[] serialize(Calendar calendar) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CalendarOutputter().output(calendar, out);
        return out.toByteArray();
    }
}
