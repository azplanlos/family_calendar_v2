package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.Calendar;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Beispielbasierte Tests für {@link OutlookSourceSelector} (Requirements 1.3, 2.8, 12.1, 12.2). */
class OutlookSourceSelectorTest {

    private static final String PARAM = "/familienkalender/outlook/refresh-token";
    private static final String CLIENT = "CLIENTIDCANARY0123";
    private static final URI ICS = URI.create("https://example.invalid/feed.ics");
    private static final QueryWindow WINDOW =
            new QueryWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));

    @Test
    void icsFallbackReturnsSameCalendarInstanceFromFetcher() {
        Calendar fromIcs = new Calendar();
        FakeIcsFetcher fetcher = new FakeIcsFetcher().returning(fromIcs);
        FakeGraphSource graph = new FakeGraphSource().failingWith(new GraphException(GraphErrorCategory.NETWORK));

        OutlookLoad result = new OutlookSourceSelector(new GraphConfig(CLIENT, PARAM), () -> graph, ICS, fetcher,
                new RecordingLog()).load(WINDOW);

        OutlookLoad.Loaded loaded = assertInstanceOf(OutlookLoad.Loaded.class, result);
        assertSame(fromIcs, loaded.calendar());
        assertEquals(OutlookLoad.Origin.ICS, loaded.origin());
    }

    @Test
    void icsOnlyConfigurationReturnsSameCalendarInstance() {
        Calendar fromIcs = new Calendar();
        OutlookLoad result = new OutlookSourceSelector(new GraphConfig("", ""), FakeGraphSource::new, ICS,
                new FakeIcsFetcher().returning(fromIcs), new RecordingLog()).load(WINDOW);

        assertSame(fromIcs, assertInstanceOf(OutlookLoad.Loaded.class, result).calendar());
    }

    @Test
    void refreshTokenInvalidLogsWarnWithCategoryAndErrorWithParameterNameOnly() {
        RecordingLog log = new RecordingLog();
        FakeGraphSource graph = new FakeGraphSource()
                .failingWith(new GraphException(GraphErrorCategory.REFRESH_TOKEN_INVALID));

        new OutlookSourceSelector(new GraphConfig(CLIENT, PARAM), () -> graph, ICS, new FakeIcsFetcher(), log)
                .load(WINDOW);

        assertEquals(1, log.warnings().size());
        assertEquals("Graph nicht verfügbar (" + GraphErrorCategory.REFRESH_TOKEN_INVALID.displayName()
                + "), Fallback auf ICS-Feed", log.warnings().get(0));
        assertEquals(1, log.errors().size());
        assertEquals("Refresh_Token ungültig, Bootstrap erneut ausführen (SSM_Parameter: " + PARAM + ")",
                log.errors().get(0));
        log.all().forEach(l -> assertFalse(l.contains(CLIENT), l));
    }

    @Test
    void ssmUnavailableWithoutIcsLogsWarnWithoutFallbackHintAndError() {
        RecordingLog log = new RecordingLog();
        FakeGraphSource graph = new FakeGraphSource()
                .failingWith(new GraphException(GraphErrorCategory.SSM_UNAVAILABLE));

        OutlookLoad result = new OutlookSourceSelector(new GraphConfig(CLIENT, PARAM), () -> graph, null,
                new FakeIcsFetcher(), log).load(WINDOW);

        assertEquals("Graph nicht verfügbar (" + GraphErrorCategory.SSM_UNAVAILABLE.displayName() + ")",
                log.warnings().get(0));
        assertEquals(1, log.errors().size());
        assertEquals("SSM_Parameter nicht verfügbar, Bootstrap erneut ausführen oder Parameter prüfen"
                + " (SSM_Parameter: " + PARAM + ")", log.errors().get(0));
        assertInstanceOf(OutlookLoad.Unavailable.class, result);
    }

    @Test
    void otherCategoryLogsNoErrorLine() {
        RecordingLog log = new RecordingLog();
        FakeGraphSource graph = new FakeGraphSource().failingWith(new GraphException(GraphErrorCategory.NETWORK));

        new OutlookSourceSelector(new GraphConfig(CLIENT, PARAM), () -> graph, ICS, new FakeIcsFetcher(), log)
                .load(WINDOW);

        assertEquals(1, log.warnings().size());
        assertTrue(log.errors().isEmpty());
    }

    @Test
    void partialConfigMissingClientIdWarnsWithVariableNameOnly() {
        RecordingLog log = new RecordingLog();
        String paramValue = "/secret/param/value";

        OutlookLoad result = new OutlookSourceSelector(new GraphConfig("", paramValue), FakeGraphSource::new, null,
                new FakeIcsFetcher(), log).load(WINDOW);

        assertEquals(1, log.warnings().size());
        String warn = log.warnings().get(0);
        assertTrue(warn.contains(GraphConfig.ENV_CLIENT_ID), warn);
        assertFalse(warn.contains(paramValue), warn);
        assertTrue(log.errors().isEmpty());
        assertInstanceOf(OutlookLoad.NotConfigured.class, result);
    }

    @Test
    void partialConfigMissingParamWarnsWithVariableNameOnlyAndFallsBackToIcs() {
        RecordingLog log = new RecordingLog();
        FakeGraphSource graph = new FakeGraphSource();

        OutlookLoad result = new OutlookSourceSelector(new GraphConfig(CLIENT, ""), () -> graph, ICS,
                new FakeIcsFetcher(), log).load(WINDOW);

        assertEquals(1, log.warnings().size());
        String warn = log.warnings().get(0);
        assertTrue(warn.contains(GraphConfig.ENV_REFRESH_TOKEN_PARAM), warn);
        assertFalse(warn.contains(CLIENT), warn);
        assertEquals(0, graph.callCount());
        assertEquals(OutlookLoad.Origin.ICS, assertInstanceOf(OutlookLoad.Loaded.class, result).origin());
    }

    @Test
    void notConfiguredLogsNothing() {
        RecordingLog log = new RecordingLog();

        new OutlookSourceSelector(new GraphConfig(null, null), FakeGraphSource::new, ICS, new FakeIcsFetcher(), log)
                .load(WINDOW);

        assertTrue(log.all().isEmpty());
    }
}
