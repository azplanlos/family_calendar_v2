package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: outlook-graph-source, Property 21: Zeitangaben überstehen die Serialisierung (Round-Trip).
 *
 * <p>Verglichen werden Instants bzw. TZID und lokale Werte (nicht {@code ZoneId}-Objekte, da ical4j
 * beim Einlesen eigene Zonen-IDs bildet).
 *
 * <p><b>Validates: Requirements 6.3, 6.4, 6.5, 6.6, 6.10</b>
 */
class GraphMapperTimeRoundTripPropertyTest {

    private static final ZoneId APP_ZONE = ZoneId.of("Europe/Berlin");
    private static final DateTimeFormatter ICS_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter ICS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter GRAPH = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final List<String> ZONES = List.of(
            "UTC", "Europe/Berlin", "America/New_York", "Asia/Kolkata", "Australia/Sydney",
            "Pacific/Auckland", "Europe/London", "America/Los_Angeles", "Asia/Tokyo");

    record Input(boolean allDay, String zone, LocalDateTime start, LocalDateTime end, int startNanos, int endNanos,
                 String sensitivity) {
    }

    @Provide
    Arbitrary<Input> inputs() {
        Arbitrary<LocalDateTime> times = Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.of(GraphArbitraries.SPECIAL_TIMES)),
                Tuple.of(3, Combinators.combine(
                        Arbitraries.integers().between(2024, 2029), Arbitraries.integers().between(1, 12),
                        Arbitraries.integers().between(1, 28), Arbitraries.integers().between(0, 23),
                        Arbitraries.integers().between(0, 59), Arbitraries.integers().between(0, 59))
                        .as((y, mo, d, h, mi, s) -> LocalDateTime.of(y, mo, d, h, mi, s))));
        Arbitrary<Long> deltaMinutes = Arbitraries.longs().between(-3 * 1440L, 3 * 1440L);
        Arbitrary<Integer> nanos = Arbitraries.of(0, 0, 1_000, 5_000_000, 123_456_700, 999_999_900);
        return Combinators.combine(Arbitraries.of(true, false), Arbitraries.of(ZONES), times, deltaMinutes,
                        nanos, nanos, Arbitraries.of("normal", "private", "confidential", "personal"))
                .as((allDay, zone, start, delta, n1, n2, sens) -> {
                    LocalDateTime s = allDay ? start.toLocalDate().atStartOfDay() : start;
                    LocalDateTime e = allDay ? s.plusDays(delta / 1440) : s.plusMinutes(Math.abs(delta) + 1);
                    return new Input(allDay, zone, s, e, n1, n2, sens);
                });
    }

    @Property(tries = 300)
    void timesSurviveSerialization(@ForAll("inputs") Input in) throws Exception {
        ObjectNode e = GraphArbitraries.MAPPER.createObjectNode();
        e.put("id", "id-1");
        e.put("iCalUId", "uid-1");
        e.put("subject", "Termin");
        e.put("sensitivity", in.sensitivity());
        e.put("isAllDay", in.allDay());
        e.put("isCancelled", false);
        e.put("type", "singleInstance");
        e.putObject("start").put("dateTime", graph(in.start(), in.startNanos())).put("timeZone", in.zone());
        e.putObject("end").put("dateTime", graph(in.end(), in.endNanos())).put("timeZone", in.zone());

        Calendar mapped = GraphCalendarMapper.toCalendar(List.of(e), APP_ZONE).calendar();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CalendarOutputter().output(mapped, out);
        Calendar reparsed = new CalendarBuilder().build(new ByteArrayInputStream(out.toByteArray()));

        List<VEvent> events = reparsed.getComponents(Component.VEVENT);
        assertThat(events).hasSize(1);
        VEvent ev = events.get(0);

        assertThat(value(ev, net.fortuna.ical4j.model.Property.UID)).as("UID").contains("uid-1");
        assertThat(value(ev, net.fortuna.ical4j.model.Property.SUMMARY)).as("SUMMARY").contains("Termin");
        assertThat(value(ev, net.fortuna.ical4j.model.Property.CLASS)).as("CLASS")
                .contains(switch (in.sensitivity()) {
                    case "private" -> "PRIVATE";
                    case "confidential" -> "CONFIDENTIAL";
                    default -> "PUBLIC";
                });

        net.fortuna.ical4j.model.Property ds = ev.getProperty(net.fortuna.ical4j.model.Property.DTSTART).orElseThrow();
        net.fortuna.ical4j.model.Property de = ev.getProperty(net.fortuna.ical4j.model.Property.DTEND).orElseThrow();

        if (in.allDay()) {
            LocalDate sd = in.start().toLocalDate();
            LocalDate ed = in.end().toLocalDate();
            if (!ed.isAfter(sd)) {
                ed = sd.plusDays(1);
            }
            assertThat(ds.getValue()).as("DTSTART").isEqualTo(ICS_DATE.format(sd));
            assertThat(de.getValue()).as("DTEND").isEqualTo(ICS_DATE.format(ed));
            assertThat(ds.getParameter(Parameter.VALUE).map(Parameter::getValue)).contains("DATE");
            assertThat(de.getParameter(Parameter.VALUE).map(Parameter::getValue)).contains("DATE");
            assertThat(ds.getParameter(Parameter.TZID)).isEmpty();
            assertThat(LocalDate.parse(de.getValue(), ICS_DATE)).isAfter(LocalDate.parse(ds.getValue(), ICS_DATE));
        } else if (in.zone().equals("UTC")) {
            assertThat(ds.getValue()).as("DTSTART").isEqualTo(ICS_DATE_TIME.format(in.start()) + "Z");
            assertThat(de.getValue()).as("DTEND").isEqualTo(ICS_DATE_TIME.format(in.end()) + "Z");
            assertThat(ds.getParameter(Parameter.TZID)).isEmpty();
            assertThat(de.getParameter(Parameter.TZID)).isEmpty();
        } else {
            assertZoned(ds, in.zone(), in.start());
            assertZoned(de, in.zone(), in.end());
        }
    }

    /** Gleicher Instant wie {@code (LocalDateTime, Zone)}, TZID gesetzt, keine Bruchteile. */
    private static void assertZoned(net.fortuna.ical4j.model.Property p, String zone, LocalDateTime local) {
        assertThat(p.getParameter(Parameter.TZID).map(Parameter::getValue)).as("TZID").contains(zone);
        assertThat(p.getValue()).as("ohne Bruchteile/Z").matches("\\d{8}T\\d{6}");
        LocalDateTime read = LocalDateTime.parse(p.getValue(), ICS_DATE_TIME);
        ZonedDateTime expected = ZonedDateTime.of(local, ZoneId.of(zone));
        assertThat(ZonedDateTime.of(read, ZoneId.of(zone)).toInstant()).as("Instant")
                .isEqualTo(expected.toInstant());
    }

    private static String graph(LocalDateTime t, int nanos) {
        // Graph liefert sieben Nachkommastellen; auf 100 ns gerundet
        return GRAPH.format(t) + "." + String.format("%07d", nanos / 100);
    }

    private static Optional<String> value(VEvent ev, String name) {
        return ev.getProperty(name).map(net.fortuna.ical4j.model.Property::getValue);
    }
}
