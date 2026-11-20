package de.goForFun.familienkalender.outlook;

import de.goForFun.familienkalender.EventRepository;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 7: Ausnahmen der Graph_Source erreichen den Aufrufer nie.
 *
 * <p>Ausnahmen werden im Token-Store, im HTTP-Transport, im Parser (kaputte JSON-Antworten), im
 * Mapper (unbrauchbare Events) und in der Graph-Quelle selbst injiziert. {@code load} liefert immer
 * ein {@link OutlookLoad}; ein {@link EventRepository} mit diesem Loader wird konstruiert, und die
 * Ereignisse und Fehler der übrigen Feeds bleiben gegenüber einem Lauf ohne Outlook unverändert.
 *
 * <p><b>Validates: Requirements 2.7, 13.17</b>
 */
class GraphSourceExceptionContainmentPropertyTest {

    private static final String PARAM = "/familienkalender/outlook/refresh-token";
    private static final ZoneId ZONE = ZoneId.of("Europe/Berlin");
    private static final LocalDate REF = LocalDate.of(2026, 11, 10);
    private static final URI ICS_URL = URI.create("https://example.org/outlook.ics");
    private static final QueryWindow WINDOW = new QueryWindow(
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"));

    enum Point { STORE_RUNTIME, STORE_GRAPH, HTTP_RUNTIME, HTTP_GRAPH, PARSER_TOKEN, PARSER_PAGE,
        MAPPER, SOURCE_RUNTIME, SOURCE_GRAPH }

    /** Auswahl der injizierten Laufzeitausnahme (0..4). */
    record Scenario(Point point, int exceptionKind, GraphErrorCategory category, String garbage,
            boolean icsConfigured, boolean icsFails, boolean partialConfig) { }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(
                Arbitraries.of(Point.values()),
                Arbitraries.integers().between(0, 4),
                Arbitraries.of(GraphErrorCategory.values()),
                Arbitraries.strings().ofMaxLength(40),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false),
                Arbitraries.of(false, false, false, true))
                .as(Scenario::new);
    }

    private static RuntimeException runtime(int kind) {
        return switch (kind) {
            case 0 -> new IllegalStateException("boom");
            case 1 -> new NullPointerException();
            case 2 -> new UnsupportedOperationException("x");
            case 3 -> new ArrayIndexOutOfBoundsException(3);
            default -> new RuntimeException((String) null);
        };
    }

    private static final String TOKEN_OK = "{\"access_token\":\"acc\"}";

    private static String weirdEvents(String garbage) {
        String g = garbage.replace("\\", "").replace("\"", "");
        return "{\"value\":[null,1,\"" + g + "\",[],{\"start\":{\"dateTime\":\"" + g
                + "\",\"timeZone\":\"" + g + "\"},\"end\":5,\"subject\":{},\"categories\":7},"
                + "{\"isAllDay\":\"x\",\"start\":null,\"end\":null,\"showAs\":[1]}]}";
    }

    private static GraphCalendarSource sourceFor(Scenario s) {
        RuntimeException rte = runtime(s.exceptionKind());
        GraphException ge = new GraphException(s.category());
        MutableClock clock = new MutableClock(Instant.parse("2026-11-10T08:00:00Z"));
        FakeRefreshTokenStore store = new FakeRefreshTokenStore(PARAM, "refresh");
        ScriptedGraphHttp http = new ScriptedGraphHttp(clock);
        OutlookLog quiet = new RecordingLog();
        switch (s.point()) {
            case STORE_RUNTIME -> store.failReadsWith(rte);
            case STORE_GRAPH -> store.failReads(ge);
            case HTTP_RUNTIME -> {
                return real(s, store, (call, timeout) -> {
                    throw rte;
                }, clock, quiet);
            }
            case HTTP_GRAPH -> {
                return real(s, store, (call, timeout) -> {
                    throw ge;
                }, clock, quiet);
            }
            case PARSER_TOKEN -> http.enqueue(200, s.garbage());
            case PARSER_PAGE -> http.enqueue(200, TOKEN_OK).enqueue(200, s.garbage());
            case MAPPER -> http.enqueue(200, TOKEN_OK).enqueue(200, weirdEvents(s.garbage()));
            case SOURCE_RUNTIME -> {
                return w -> {
                    throw rte;
                };
            }
            case SOURCE_GRAPH -> {
                return w -> {
                    throw ge;
                };
            }
            default -> throw new AssertionError();
        }
        return real(s, store, http, clock, quiet);
    }

    private static GraphCalendarSource real(Scenario s, RefreshTokenStore store, GraphHttp http,
            MutableClock clock, OutlookLog log) {
        return new GraphSource(new GraphConfig("client", PARAM), store, http, clock, ZONE, log);
    }

    private static OutlookSourceSelector selector(Scenario s, RecordingLog log) {
        GraphConfig config = s.partialConfig() ? new GraphConfig("client", "") : new GraphConfig("client", PARAM);
        FakeIcsFetcher ics = new FakeIcsFetcher();
        if (s.icsFails()) {
            ics.failingWithRuntime(runtime(s.exceptionKind()));
        }
        Supplier<GraphCalendarSource> factory = () -> sourceFor(s);
        return new OutlookSourceSelector(config, factory, s.icsConfigured() ? ICS_URL : null, ics, log);
    }

    @Property(tries = 300)
    void selectorNeverThrows(@ForAll("scenarios") Scenario s) {
        OutlookLoad load = selector(s, new RecordingLog()).load(WINDOW);
        assertNotNull(load);
    }

    private static URI emptyFeed;

    private static synchronized URI emptyFeed() throws Exception {
        if (emptyFeed == null) {
            Path p = Files.createTempFile("containment-main-feed", ".ics");
            p.toFile().deleteOnExit();
            Files.writeString(p, "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\nEND:VCALENDAR\r\n",
                    StandardCharsets.UTF_8);
            emptyFeed = p.toUri();
        }
        return emptyFeed;
    }

    @Property(tries = 100)
    void repositoryIsConstructedAndOtherFeedsAreUnchanged(@ForAll("scenarios") Scenario s) throws Exception {
        URI missingSchool = Path.of(System.getProperty("java.io.tmpdir"), "no-such-school-feed.ics").toUri();
        EventRepository baseline = new EventRepository(emptyFeed(), missingSchool, null, (URI) null, "Papa", REF, null);
        EventRepository repo = new EventRepository(emptyFeed(), missingSchool, null,
                selector(s, new RecordingLog()), "Papa", REF, null);
        assertNotNull(repo);
        List<String> others = new ArrayList<>(repo.getErrors());
        others.removeIf(e -> e.startsWith("Outlook-Feed: "));
        assertEquals(baseline.getErrors(), others);
        assertTrue(repo.getErrors().stream().filter(e -> e.startsWith("Outlook-Feed: ")).count() <= 1);
        // Garbage in the Graph path must not add events; ICS fallback may return an empty calendar only.
        assertEquals(baseline.getEventsForRange(REF.withDayOfMonth(1), REF.plusMonths(1).withDayOfMonth(28)),
                repo.getEventsForRange(REF.withDayOfMonth(1), REF.plusMonths(1).withDayOfMonth(28)));
    }

    @Property(tries = 50)
    void loaderThrowingDirectlyDoesNotBreakRepository(@ForAll("scenarios") Scenario s) throws Exception {
        RuntimeException rte = runtime(s.exceptionKind());
        EventRepository repo = new EventRepository(emptyFeed(), null, null,
                w -> {
                    throw rte;
                }, "Papa", REF, null);
        assertEquals(1, repo.getErrors().size());
        assertTrue(repo.getErrors().get(0).startsWith("Outlook-Feed: "));
    }
}
