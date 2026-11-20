package de.goForFun.familienkalender.outlook;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * {@link GraphHttp} auf Basis von {@code java.net.http}. Redirects werden nie gefolgt, damit ein
 * {@code Authorization}-Header nicht an einen anderen Host gelangt.
 */
public final class JdkGraphHttp implements GraphHttp {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;

    public JdkGraphHttp() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public HttpResult send(HttpCall call, Duration timeout) throws GraphException {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(timeout, "timeout");
        HttpRequest request;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(call.uri()).timeout(timeout);
            for (Map.Entry<String, String> h : call.headers().entrySet()) {
                builder.header(h.getKey(), h.getValue());
            }
            HttpRequest.BodyPublisher body = call.formOrNullBody() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(call.formOrNullBody(), StandardCharsets.UTF_8);
            request = builder.method(call.method(), body).build();
        } catch (IllegalArgumentException e) {
            // Ungültige Methode/Header/URI; Meldung kann Headerwerte enthalten und wird verworfen.
            throw new GraphException(GraphErrorCategory.NETWORK);
        }
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new HttpResult(response.statusCode(), response.body());
        } catch (HttpTimeoutException e) { // inkl. HttpConnectTimeoutException
            throw new GraphException(GraphErrorCategory.TIMEOUT);
        } catch (IOException e) {
            throw new GraphException(GraphErrorCategory.NETWORK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GraphException(GraphErrorCategory.NETWORK);
        }
    }
}
