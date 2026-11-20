package de.goForFun.familienkalender.outlook;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import net.fortuna.ical4j.model.Calendar;
import tools.jackson.databind.JsonNode;

/**
 * Lädt den Outlook-Kalender über Microsoft Graph. Alles oder nichts: liefert den vollständigen
 * {@link Calendar} oder wirft eine {@link GraphException}. Hält keinen Zustand zwischen Aufrufen
 * und liest weder die Systemuhr (nur die injizierte {@link Clock} für das Budget) noch
 * {@code GRAPH_HORIZON_DAYS}.
 */
public final class GraphSource implements GraphCalendarSource {

    static final Duration TOTAL_BUDGET = Duration.ofSeconds(15);

    private final GraphConfig config;
    private final RefreshTokenStore tokenStore;
    private final GraphHttp http;
    private final Clock clock;
    private final ZoneId zone;
    private final OutlookLog log;

    public GraphSource(GraphConfig config, RefreshTokenStore tokenStore, GraphHttp http,
            Clock clock, ZoneId zone, OutlookLog log) {
        this.config = Objects.requireNonNull(config, "config");
        this.tokenStore = Objects.requireNonNull(tokenStore, "tokenStore");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public Calendar fetch(QueryWindow window) throws GraphException {
        Objects.requireNonNull(window, "window");
        TimeBudget budget = TimeBudget.start(clock, TOTAL_BUDGET);
        String accessToken = new GraphTokenService(config, tokenStore, http, log).acquire(budget);
        List<JsonNode> events = new GraphCalendarClient(http, zone)
                .fetchAll(window, accessToken, budget);
        return GraphCalendarMapper.toCalendar(events, zone).calendar();
    }
}
