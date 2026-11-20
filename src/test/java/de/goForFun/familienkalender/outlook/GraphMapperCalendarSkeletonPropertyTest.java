package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 22: Kalender-Grundgerüst und VTIMEZONE-Menge sind konsistent.
 *
 * <p><b>Validates: Requirements 6.8, 6.9</b>
 */
class GraphMapperCalendarSkeletonPropertyTest extends GraphArbitraries {

    private static final ZoneId APP_ZONE = ZoneId.of("Europe/Berlin");

    @net.jqwik.api.Property(tries = 200)
    void skeletonAndVtimezoneSetAreConsistent(@ForAll("graphEventLists") List<JsonNode> events) throws Exception {
        check(GraphCalendarMapper.toCalendar(events, APP_ZONE).calendar());
    }

    @Example
    void emptyEventListYieldsValidSkeleton() throws Exception {
        Calendar calendar = GraphCalendarMapper.toCalendar(List.of(), APP_ZONE).calendar();
        assertTrue(calendar.getComponents().isEmpty());
        check(calendar);
    }

    private static void check(Calendar calendar) throws IOException, Exception {
        String prodId = calendar.getProperty(net.fortuna.ical4j.model.Property.PRODID).orElseThrow().getValue();
        assertFalse(prodId.isEmpty());
        assertEquals("2.0", calendar.getProperty(net.fortuna.ical4j.model.Property.VERSION).orElseThrow().getValue());

        Calendar reparsed = roundTrip(calendar);
        assertFalse(reparsed.getProperty(net.fortuna.ical4j.model.Property.PRODID).orElseThrow().getValue().isEmpty());
        assertEquals("2.0", reparsed.getProperty(net.fortuna.ical4j.model.Property.VERSION).orElseThrow().getValue());

        for (Calendar c : List.of(calendar, reparsed)) {
            List<String> vtzList = vtimezoneIds(c);
            Set<String> vtzSet = new HashSet<>(vtzList);
            assertEquals(vtzList.size(), vtzSet.size(), "genau eine VTIMEZONE je TZID: " + vtzList);
            assertEquals(referencedTzids(c), vtzSet);
        }
    }

    private static Calendar roundTrip(Calendar calendar) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CalendarOutputter().output(calendar, out);
        return new CalendarBuilder().build(new ByteArrayInputStream(out.toByteArray()));
    }

    private static List<String> vtimezoneIds(Calendar c) {
        return c.<CalendarComponent>getComponents(Component.VTIMEZONE).stream()
                .map(v -> v.getProperty(net.fortuna.ical4j.model.Property.TZID).orElseThrow().getValue())
                .toList();
    }

    private static Set<String> referencedTzids(Calendar c) {
        Set<String> tzids = new HashSet<>();
        for (CalendarComponent v : c.<CalendarComponent>getComponents(Component.VEVENT)) {
            for (String name : List.of(net.fortuna.ical4j.model.Property.DTSTART, net.fortuna.ical4j.model.Property.DTEND)) {
                v.getProperty(name).ifPresent(p ->
                        p.getParameter(Parameter.TZID).ifPresent(tz -> tzids.add(((Parameter) tz).getValue())));
            }
        }
        return tzids;
    }
}
