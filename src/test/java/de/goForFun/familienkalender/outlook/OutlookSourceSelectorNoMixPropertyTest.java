package de.goForFun.familienkalender.outlook;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Uid;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 2: Quellen werden nie gemischt.
 *
 * <p>Die Graph-Quelle sammelt Seite für Seite UIDs mit Präfix {@code GRAPH-} und wirft ggf. auf einer
 * beliebigen Seite; der ICS-Fetcher liefert UIDs mit Präfix {@code ICS-}.
 */
class OutlookSourceSelectorNoMixPropertyTest {

    private static final QueryWindow WINDOW =
            new QueryWindow(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-01-03T00:00:00Z"));
    private static final URI ICS_URL = URI.create("https://example.org/outlook.ics");

    /** Seitengrößen (Events je Seite); Fehlerseite: Index in {@code [0, pages]} (pages = kein Fehler). */
    record Scenario(List<Integer> pageSizes, int failAt, int icsEvents, boolean icsConfigured,
                    GraphErrorCategory category) { }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<Integer>> sizes = Arbitraries.integers().between(0, 4).list().ofMinSize(1).ofMaxSize(6);
        return sizes.flatMap(s -> Combinators.combine(
                Arbitraries.just(s),
                Arbitraries.integers().between(0, s.size()),
                Arbitraries.integers().between(0, 4),
                Arbitraries.of(true, true, false),
                Arbitraries.of(GraphErrorCategory.values()))
                .as(Scenario::new));
    }

    private static Calendar calendar(String prefix, List<String> uids) {
        Calendar cal = new Calendar();
        for (String uid : uids) {
            VEvent event = new VEvent();
            event.add(new Uid(prefix + uid));
            cal.add((CalendarComponent) event);
        }
        return cal;
    }

    private static Set<String> uids(Calendar cal) {
        Set<String> out = new TreeSet<>();
        for (CalendarComponent c : cal.getComponents()) {
            c.getProperty(net.fortuna.ical4j.model.Property.UID)
                    .ifPresent(p -> out.add(((net.fortuna.ical4j.model.Property) p).getValue()));
        }
        return out;
    }

    @Property(tries = 200)
    // **Validates: Requirements 2.9, 4.5, 4.14, 13.18**
    void sourcesAreNeverMixed(@ForAll("scenarios") Scenario s) {
        List<String> icsUids = new ArrayList<>();
        for (int i = 0; i < s.icsEvents(); i++) {
            icsUids.add(String.valueOf(i));
        }
        List<String> graphFetched = new ArrayList<>();
        GraphCalendarSource graph = window -> {
            List<String> acc = new ArrayList<>();
            for (int page = 0; page < s.pageSizes().size(); page++) {
                if (page == s.failAt()) {
                    graphFetched.addAll(acc);
                    throw new GraphException(s.category());
                }
                for (int i = 0; i < s.pageSizes().get(page); i++) {
                    acc.add(page + "-" + i);
                }
            }
            if (s.failAt() == s.pageSizes().size()) {
                graphFetched.addAll(acc);
                throw new GraphException(s.category());
            }
            return calendar("GRAPH-", acc);
        };
        FakeIcsFetcher ics = new FakeIcsFetcher().returning(calendar("ICS-", icsUids));
        OutlookSourceSelector selector = new OutlookSourceSelector(new GraphConfig("client", "param"),
                () -> graph, s.icsConfigured() ? ICS_URL : null, ics, new RecordingLog());

        OutlookLoad result = selector.load(WINDOW);

        // failAt in [0, size]: immer Graph-Fehler
        if (!s.icsConfigured()) {
            assertInstanceOf(OutlookLoad.Unavailable.class, result);
            return;
        }
        OutlookLoad.Loaded loaded = assertInstanceOf(OutlookLoad.Loaded.class, result);
        assertEquals(OutlookLoad.Origin.ICS, loaded.origin());
        Set<String> found = uids(loaded.calendar());
        assertEquals(s.icsEvents(), found.size());
        assertTrue(found.stream().allMatch(u -> u.startsWith("ICS-")), "nur ICS-UIDs: " + found);
        assertTrue(found.stream().noneMatch(u -> u.startsWith("GRAPH-")));
    }

    @Property(tries = 100)
    // **Validates: Requirements 2.9, 4.5, 13.18**
    void graphSuccessContainsOnlyGraphUids(@ForAll("scenarios") Scenario s) {
        List<String> graphUids = new ArrayList<>();
        for (int p = 0; p < s.pageSizes().size(); p++) {
            for (int i = 0; i < s.pageSizes().get(p); i++) {
                graphUids.add(p + "-" + i);
            }
        }
        FakeGraphSource graph = new FakeGraphSource().returning(calendar("GRAPH-", graphUids));
        FakeIcsFetcher ics = new FakeIcsFetcher().returning(calendar("ICS-", List.of("x")));
        OutlookSourceSelector selector = new OutlookSourceSelector(new GraphConfig("client", "param"),
                () -> graph, ICS_URL, ics, new RecordingLog());

        OutlookLoad.Loaded loaded = assertInstanceOf(OutlookLoad.Loaded.class, selector.load(WINDOW));

        assertEquals(OutlookLoad.Origin.GRAPH, loaded.origin());
        assertEquals(0, ics.callCount());
        assertTrue(uids(loaded.calendar()).stream().allMatch(u -> u.startsWith("GRAPH-")));
    }
}
