package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.component.VEvent;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import tools.jackson.databind.node.ObjectNode;

import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: outlook-graph-source, Property 18: CLASS-, TRANSP- und BUSYSTATUS-Mapping folgen den Tabellen.
 *
 * <p><b>Validates: Requirements 5.3, 5.4, 5.5, 5.6, 5.8, 5.9</b>
 */
class GraphMapperClassShowAsPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** Wert oder {@code null} (= JSON-null); {@code Optional.empty()} bedeutet: Feld fehlt. */
    private static Arbitrary<Optional<String>> fieldValues(List<String> known) {
        Arbitrary<String> any = Arbitraries.oneOf(
                Arbitraries.of(known),
                Arbitraries.of(known).map(GraphMapperClassShowAsPropertyTest::randomCase),
                Arbitraries.strings().ofMaxLength(12));
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(8, any.map(Optional::of)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(Optional.<String>empty())),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(Optional.of("\u0000null"))));
    }

    private static String randomCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append((i % 2 == 0) ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    @Provide
    Arbitrary<Optional<String>> sensitivities() {
        return fieldValues(List.of("private", "confidential", "normal", "personal", ""));
    }

    @Provide
    Arbitrary<Optional<String>> showAsValues() {
        return fieldValues(List.of("free", "tentative", "oof", "busy", "workingElsewhere", "unknown", ""));
    }

    private static VEvent mapped(Optional<String> sensitivity, Optional<String> showAs) {
        ObjectNode e = GraphArbitraries.MAPPER.createObjectNode();
        e.put("id", "id-1");
        e.put("iCalUId", "uid-1");
        e.put("subject", "S");
        e.put("isAllDay", false);
        e.put("isCancelled", false);
        e.put("type", "singleInstance");
        e.putObject("start").put("dateTime", "2027-02-14T10:00:00.0000000").put("timeZone", "UTC");
        e.putObject("end").put("dateTime", "2027-02-14T11:00:00.0000000").put("timeZone", "UTC");
        put(e, "sensitivity", sensitivity);
        put(e, "showAs", showAs);
        return GraphCalendarMapper.map(e, ZONE).orElseThrow().event();
    }

    private static void put(ObjectNode e, String field, Optional<String> v) {
        if (v.isEmpty()) {
            return;
        }
        if (v.get().equals("\u0000null")) {
            e.putNull(field);
        } else {
            e.put(field, v.get());
        }
    }

    private static String raw(Optional<String> v) {
        return v.filter(s -> !s.equals("\u0000null")).orElse(null);
    }

    private static String value(VEvent ev, String name) {
        return ev.getProperty(name).map(p -> p.getValue()).orElse(null);
    }

    @net.jqwik.api.Property(tries = 300)
    void classFollowsTable(@ForAll("sensitivities") Optional<String> sensitivity,
                           @ForAll("showAsValues") Optional<String> showAs) {
        String s = raw(sensitivity);
        String lower = s == null ? "" : s.toLowerCase(Locale.ROOT);
        String expected = switch (lower) {
            case "private" -> "PRIVATE";
            case "confidential" -> "CONFIDENTIAL";
            default -> "PUBLIC"; // normal, personal, leer, fehlend, null, beliebig
        };
        assertThat(value(mapped(sensitivity, showAs), "CLASS")).isEqualTo(expected);
    }

    @net.jqwik.api.Property(tries = 300)
    void transpAndBusyStatusFollowTable(@ForAll("sensitivities") Optional<String> sensitivity,
                                        @ForAll("showAsValues") Optional<String> showAs) {
        String s = raw(showAs);
        String lower = s == null ? "" : s.toLowerCase(Locale.ROOT);
        String transp = lower.equals("free") ? "TRANSPARENT" : "OPAQUE";
        String busy = switch (lower) {
            case "free" -> "FREE";
            case "tentative" -> "TENTATIVE";
            case "oof" -> "OOF";
            default -> "BUSY"; // busy, workingElsewhere, unknown, leer, fehlend, null, beliebig
        };
        VEvent ev = mapped(sensitivity, showAs);
        assertThat(value(ev, "TRANSP")).isEqualTo(transp);
        assertThat(value(ev, GraphCalendarMapper.X_BUSYSTATUS)).isEqualTo(busy);
    }
}
