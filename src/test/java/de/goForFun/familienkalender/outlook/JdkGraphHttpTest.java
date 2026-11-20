package de.goForFun.familienkalender.outlook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdkGraphHttpTest {

    private HttpServer server;
    private final AtomicReference<String> seenAuth = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private final AtomicReference<String> seenMethod = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/echo", ex -> {
            seenAuth.set(ex.getRequestHeaders().getFirst("Authorization"));
            seenMethod.set(ex.getRequestMethod());
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "äö-ok".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/other");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/err", ex -> {
            byte[] out = "{\"error\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(403, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void postSendsHeadersAndUtf8BodyAndReturnsUtf8Body() throws Exception {
        HttpResult r = new JdkGraphHttp().send(
                new HttpCall("POST", uri("/echo"), Map.of("Authorization", "Bearer x"), "a=ä&b=c"),
                Duration.ofSeconds(5));
        assertEquals(200, r.status());
        assertEquals("äö-ok", r.body());
        assertEquals("Bearer x", seenAuth.get());
        assertEquals("POST", seenMethod.get());
        assertEquals("a=ä&b=c", seenBody.get());
    }

    @Test
    void getWithoutBody() throws Exception {
        HttpResult r = new JdkGraphHttp().send(
                new HttpCall("GET", uri("/echo"), Map.of(), null), Duration.ofSeconds(5));
        assertEquals(200, r.status());
        assertEquals("GET", seenMethod.get());
        assertEquals("", seenBody.get());
    }

    @Test
    void nonSuccessStatusIsReturnedNotThrown() throws Exception {
        HttpResult r = new JdkGraphHttp().send(
                new HttpCall("GET", uri("/err"), Map.of(), null), Duration.ofSeconds(5));
        assertEquals(403, r.status());
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        HttpResult r = new JdkGraphHttp().send(
                new HttpCall("GET", uri("/redirect"), Map.of("Authorization", "Bearer x"), null),
                Duration.ofSeconds(5));
        assertEquals(302, r.status());
    }

    @Test
    void requestTimeoutMapsToTimeout() {
        GraphException e = assertThrows(GraphException.class, () -> new JdkGraphHttp().send(
                new HttpCall("GET", uri("/slow"), Map.of(), null), Duration.ofMillis(200)));
        assertEquals(GraphErrorCategory.TIMEOUT, e.category());
    }

    @Test
    void connectionRefusedMapsToNetwork() throws Exception {
        int freePort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            freePort = s.getLocalPort();
        }
        GraphException e = assertThrows(GraphException.class, () -> new JdkGraphHttp().send(
                new HttpCall("GET", URI.create("http://127.0.0.1:" + freePort + "/"), Map.of(), null),
                Duration.ofSeconds(2)));
        assertEquals(GraphErrorCategory.NETWORK, e.category());
        assertNull(e.getCause());
    }

    @Test
    void interruptSetsFlagAndMapsToNetwork() {
        Thread.currentThread().interrupt();
        try {
            GraphException e = assertThrows(GraphException.class, () -> new JdkGraphHttp().send(
                    new HttpCall("GET", uri("/echo"), Map.of(), null), Duration.ofSeconds(5)));
            assertEquals(GraphErrorCategory.NETWORK, e.category());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted(); // Flag zurücksetzen
        }
    }
}
