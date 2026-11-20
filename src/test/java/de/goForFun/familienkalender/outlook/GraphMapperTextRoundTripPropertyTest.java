package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Categories;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: outlook-graph-source, Property 19: Textfelder überstehen die Serialisierung unverändert.
 *
 * <p><b>Validates: Requirements 5.2, 5.7, 5.10, 5.11</b>
 */
class GraphMapperTextRoundTripPropertyTest {

    private static final ZoneId APP_ZONE = ZoneId.of("Europe/Berlin");

    /** Text mit Sonderzeichen; optional {@code null} (Feld fehlt) bzw. leer. */
    private static Arbitrary<String> texts() {
        return texts(true);
    }

    /**
     * {@code backslashes}: ob der Pool Backslashes enthält. Der ical4j-Parser liest in CATEGORIES
     * ein maskiertes {@code \\\\} als einzelnen Backslash und {@code \\\\n} als Zeilenumbruch
     * (Fehler der Bibliothek beim Einlesen; der vom Mapper erzeugte ICS-Text ist korrekt). Für
     * Kategorien bleiben Backslashes deshalb ausgespart. Ebenso werden Kategorieeinträge ohne
     * Rand-Leerzeichen/-Tabs erzeugt, da der Parser den gesamten Wert (also den ersten und letzten
     * Eintrag) trimmt.
     */
    private static Arbitrary<String> texts(boolean backslashes) {
        Arbitrary<String> special = Arbitraries.of(
                ",", ";", "\"", "\n", "\r\n", " ", "  ", "\t", ":", "ä", "Ü", "ß", "日本", "😀", "é",
                backslashes ? "\\" : "'", backslashes ? "\\n" : "n", backslashes ? "\\," : ",,");
        Arbitrary<String> letters = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(6);
        Arbitrary<String> pieces = Arbitraries.oneOf(special, special, letters);
        // Ein Backslash direkt vor einem Zeilenumbruch (ICS: Backslash-Backslash-Backslash-n) liest der ical4j-Parser
        // falsch zurück; der erzeugte ICS-Text ist korrekt. Diese Kombination bleibt ausgespart.
        return pieces.list().ofMaxSize(12).map(l -> String.join("", l))
                .filter(t -> !t.contains("\\\n") && !t.contains("\\\r"));
    }

    private static Arbitrary<String> optionalTexts() {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(8, texts()),
                net.jqwik.api.Tuple.of(1, Arbitraries.just((String) null)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just("")));
    }

    @Provide
    Arbitrary<List<String>> categoryLists() {
        Arbitrary<String> entry = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(6, texts(false).map(GraphMapperTextRoundTripPropertyTest::parsed)),
                net.jqwik.api.Tuple.of(2, Arbitraries.just("")));
        return entry.list().ofMaxSize(6);
    }

    @Provide
    Arbitrary<Object[]> inputs() {
        return Combinators.combine(optionalTexts(), optionalTexts(), optionalTexts(), categoryLists())
                .as((s, l, b, c) -> new Object[]{s, l, b, c});
    }

    @Property(tries = 300)
    void textFieldsSurviveSerialization(@ForAll("inputs") Object[] in) throws Exception {
        String subject = (String) in[0];
        String location = (String) in[1];
        String body = (String) in[2];
        @SuppressWarnings("unchecked")
        List<String> categories = (List<String>) in[3];

        ObjectNode e = GraphArbitraries.MAPPER.createObjectNode();
        e.put("id", "id-1");
        e.put("iCalUId", "uid-1");
        e.put("isAllDay", false);
        e.put("isCancelled", false);
        e.put("type", "singleInstance");
        e.putObject("start").put("dateTime", "2026-05-04T10:00:00.0000000").put("timeZone", "Europe/Berlin");
        e.putObject("end").put("dateTime", "2026-05-04T11:00:00.0000000").put("timeZone", "Europe/Berlin");
        if (subject != null) {
            e.put("subject", subject);
        }
        if (location != null) {
            e.putObject("location").put("displayName", location);
        }
        if (body != null) {
            e.put("bodyPreview", body);
        }
        ArrayNode cats = e.putArray("categories");
        categories.forEach(cats::add);

        Calendar mapped = GraphCalendarMapper.toCalendar(List.of(e), APP_ZONE).calendar();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new CalendarOutputter().output(mapped, out);
        Calendar reparsed = new CalendarBuilder().build(new ByteArrayInputStream(out.toByteArray()));

        List<VEvent> events = reparsed.getComponents(Component.VEVENT);
        assertThat(events).hasSize(1);
        VEvent ev = events.get(0);

        assertThat(value(ev, net.fortuna.ical4j.model.Property.SUMMARY))
                .as("SUMMARY").contains(parsed(subject == null ? "" : subject));

        assertOptional(ev, net.fortuna.ical4j.model.Property.LOCATION, location, "LOCATION");
        assertOptional(ev, net.fortuna.ical4j.model.Property.DESCRIPTION, body, "DESCRIPTION");

        List<String> expected = new ArrayList<>();
        categories.stream().filter(c -> !c.isEmpty()).filter(c -> !expected.contains(c)).forEach(expected::add);
        // Doppelte Einträge fasst ical4j (TextList ist ein Set) zusammen; die Reihenfolge bleibt erhalten.
        List<net.fortuna.ical4j.model.Property> catProps =
                ev.getProperties(net.fortuna.ical4j.model.Property.CATEGORIES);
        if (expected.isEmpty()) {
            assertThat(catProps).as("CATEGORIES").isEmpty();
        } else {
            assertThat(catProps).as("CATEGORIES").hasSize(1);
            assertThat(new ArrayList<>(((Categories) catProps.get(0)).getCategories().getTexts()))
                    .as("CATEGORIES-Einträge").isEqualTo(expected);
        }
    }

    /**
     * Der ical4j-Parser entfernt Leerzeichen und Tabs am Anfang und Ende des Property-Werts
     * (ein unmaskierbarer Verlust der Bibliothek, nicht des Mappers). Zeilenumbrüche werden als
     * {@code \\n} maskiert und bleiben daher erhalten; {@code CRLF} wird (RFC 5545 TEXT) zu {@code LF} normalisiert.
     */
    private static String parsed(String raw) {
        String s = raw.replace("\r\n", "\n");
        int b = 0;
        int e = s.length();
        while (b < e && (s.charAt(b) == ' ' || s.charAt(b) == '\t')) {
            b++;
        }
        while (e > b && (s.charAt(e - 1) == ' ' || s.charAt(e - 1) == '\t')) {
            e--;
        }
        return s.substring(b, e);
    }

    private static Optional<String> value(VEvent ev, String name) {
        return ev.getProperty(name).map(net.fortuna.ical4j.model.Property::getValue);
    }

    private static void assertOptional(VEvent ev, String name, String input, String label) {
        if (input == null || input.trim().isEmpty()) {
            assertThat(value(ev, name)).as(label).isEmpty();
        } else {
            assertThat(value(ev, name)).as(label).contains(parsed(input));
        }
    }
}
