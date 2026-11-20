package de.goForFun.familienkalender.outlook;

import java.net.URI;
import java.util.Objects;
import java.util.function.Supplier;
import net.fortuna.ical4j.model.Calendar;

/**
 * Wählt die Quelle des Outlook-Kalenders (Graph oder ICS-Fallback) gemäß Entscheidungstabelle.
 * Wirft nie nach außen; Protokollzeilen enthalten nur Kategorien und Parameternamen.
 */
public final class OutlookSourceSelector implements OutlookCalendarLoader {

    private static final String ICS_GENERIC_FAILURE = "Outlook-Feed konnte nicht abgerufen werden";

    private final GraphConfig graphConfig;
    private final Supplier<? extends GraphCalendarSource> graphSourceFactory;
    private final URI icsFallbackUrl;
    private final IcsFetcher icsFetcher;
    private final OutlookLog log;

    /**
     * @param graphSourceFactory erzeugt je {@link #load} eine frische Graph-Quelle
     * @param icsFallbackUrl {@code null} = fehlend/ungültig
     */
    public OutlookSourceSelector(GraphConfig graphConfig,
            Supplier<? extends GraphCalendarSource> graphSourceFactory, URI icsFallbackUrl,
            IcsFetcher icsFetcher, OutlookLog log) {
        this.graphConfig = Objects.requireNonNull(graphConfig, "graphConfig");
        this.graphSourceFactory = Objects.requireNonNull(graphSourceFactory, "graphSourceFactory");
        this.icsFallbackUrl = icsFallbackUrl;
        this.icsFetcher = Objects.requireNonNull(icsFetcher, "icsFetcher");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public OutlookLoad load(QueryWindow window) {
        GraphConfig.State state = graphConfig.state();
        if (state == GraphConfig.State.PARTIAL) {
            safeWarn("Graph-Konfiguration unvollständig, Variable fehlt: "
                    + graphConfig.missingVariable() + "; Graph wird nicht verwendet");
        }
        if (state != GraphConfig.State.CONFIGURED) {
            return icsFallbackUrl == null ? new OutlookLoad.NotConfigured() : loadIcs();
        }

        GraphErrorCategory category;
        try {
            Calendar calendar = graphSourceFactory.get().fetch(window);
            if (calendar == null) {
                category = GraphErrorCategory.RESPONSE_UNPARSABLE;
            } else {
                return new OutlookLoad.Loaded(calendar, OutlookLoad.Origin.GRAPH);
            }
        } catch (GraphException e) {
            category = e.category();
        } catch (Exception | LinkageError e) {
            category = GraphErrorCategory.RESPONSE_UNPARSABLE;
        }

        logGraphFailure(category);
        if (icsFallbackUrl == null) {
            return new OutlookLoad.Unavailable(
                    "Graph nicht verfügbar (" + category.displayName() + ")");
        }
        return loadIcs();
    }

    private OutlookLoad loadIcs() {
        try {
            Calendar calendar = icsFetcher.fetch(icsFallbackUrl);
            if (calendar == null) {
                return new OutlookLoad.Unavailable(ICS_GENERIC_FAILURE);
            }
            return new OutlookLoad.Loaded(calendar, OutlookLoad.Origin.ICS);
        } catch (IcsFetchException e) {
            String message = e.getMessage();
            return new OutlookLoad.Unavailable(
                    message == null || message.isBlank() ? ICS_GENERIC_FAILURE : message);
        } catch (Exception | LinkageError e) {
            return new OutlookLoad.Unavailable(ICS_GENERIC_FAILURE);
        }
    }

    private void logGraphFailure(GraphErrorCategory category) {
        safeWarn("Graph nicht verfügbar (" + category.displayName() + ")"
                + (icsFallbackUrl != null ? ", Fallback auf ICS-Feed" : ""));
        if (category == GraphErrorCategory.REFRESH_TOKEN_INVALID
                || category == GraphErrorCategory.SSM_UNAVAILABLE) {
            try {
                String text = category == GraphErrorCategory.REFRESH_TOKEN_INVALID
                        ? "Refresh_Token ungültig, Bootstrap erneut ausführen"
                        : "SSM_Parameter nicht verfügbar, Bootstrap erneut ausführen oder Parameter prüfen";
                log.error(text + " (SSM_Parameter: " + graphConfig.refreshTokenParam() + ")");
            } catch (RuntimeException ignored) {
                // Protokollfehler dürfen die Quellenauswahl nicht beeinträchtigen
            }
        }
    }

    private void safeWarn(String message) {
        try {
            log.warn(message);
        } catch (RuntimeException ignored) {
            // Protokollfehler dürfen die Quellenauswahl nicht beeinträchtigen
        }
    }
}
