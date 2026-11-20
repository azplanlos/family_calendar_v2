package de.goForFun.familienkalender.outlook;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;

/** Ruft den ICS-Fallback-Feed per HTTP ab (Connect- und Request-Timeout je 10 s). */
public final class IcsCalendarFetcher implements IcsFetcher {

    static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public Calendar fetch(URI url) throws IcsFetchException {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build();
        } catch (RuntimeException e) {
            throw new IcsFetchException("Outlook_Source_Feed konnte nicht abgerufen oder gelesen werden", e);
        }
        try {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status < 200 || status >= 300) {
                    throw new IcsFetchException(
                            "Outlook_Source_Feed antwortete mit HTTP-Status " + status);
                }
                return new CalendarBuilder().build(body);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IcsFetchException("Outlook_Source_Feed konnte nicht abgerufen oder gelesen werden", e);
        } catch (IOException | ParserException | RuntimeException e) {
            throw new IcsFetchException("Outlook_Source_Feed konnte nicht abgerufen oder gelesen werden", e);
        }
    }
}
