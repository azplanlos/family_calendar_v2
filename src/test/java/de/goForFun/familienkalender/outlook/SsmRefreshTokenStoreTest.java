package de.goForFun.familienkalender.outlook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;
import software.amazon.awssdk.services.ssm.model.Parameter;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;
import software.amazon.awssdk.services.ssm.model.ParameterType;
import software.amazon.awssdk.services.ssm.model.PutParameterRequest;
import software.amazon.awssdk.services.ssm.model.PutParameterResponse;
import software.amazon.awssdk.services.ssm.model.SsmException;

class SsmRefreshTokenStoreTest {

    private static final String NAME = "/family/graph/refresh";
    private static final Duration TIMEOUT = Duration.ofSeconds(7);
    private static final String SECRET = "secret-token-value";

    private final List<Object> requests = new ArrayList<>();
    private boolean closed;

    private SsmClient client(Function<Object, Object> handler) {
        return (SsmClient) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {SsmClient.class}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closed = true;
                        return null;
                    }
                    requests.add(args[0]);
                    try {
                        return handler.apply(args[0]);
                    } catch (RuntimeException e) {
                        throw new InvocationTargetException(e).getCause();
                    }
                });
    }

    private static GetParameterResponse value(String v) {
        return GetParameterResponse.builder().parameter(Parameter.builder().value(v).build()).build();
    }

    @Test
    void readUsesDecryptionAndTimeout() throws Exception {
        SsmRefreshTokenStore store = new SsmRefreshTokenStore(client(r -> value(SECRET)));
        assertEquals(SECRET, store.read(NAME, TIMEOUT));
        GetParameterRequest req = (GetParameterRequest) requests.get(0);
        assertEquals(NAME, req.name());
        assertTrue(req.withDecryption());
        assertEquals(TIMEOUT, req.overrideConfiguration().get().apiCallTimeout().get());
    }

    @Test
    void readFailuresMapToSsmUnavailableWithoutLeaks() {
        List<Function<Object, Object>> failures = List.of(
                r -> { throw ParameterNotFoundException.builder().message(SECRET).build(); },
                r -> { throw SsmException.builder().message(SECRET).build(); },
                r -> { throw SdkClientException.create(SECRET); },
                r -> value(""),
                r -> value(null),
                r -> GetParameterResponse.builder().build());
        for (Function<Object, Object> f : failures) {
            SsmRefreshTokenStore store = new SsmRefreshTokenStore(client(f));
            GraphException e = assertThrows(GraphException.class, () -> store.read(NAME, TIMEOUT));
            assertEquals(GraphErrorCategory.SSM_UNAVAILABLE, e.category());
            assertFalse(e.getMessage().contains(SECRET));
            assertEquals(null, e.getCause());
        }
    }

    @Test
    void writeUsesSecureStringOverwriteAndTimeout() throws Exception {
        SsmRefreshTokenStore store = new SsmRefreshTokenStore(
                client(r -> PutParameterResponse.builder().build()));
        store.write(NAME, SECRET, TIMEOUT);
        PutParameterRequest req = (PutParameterRequest) requests.get(0);
        assertEquals(NAME, req.name());
        assertEquals(SECRET, req.value());
        assertEquals(ParameterType.SECURE_STRING, req.type());
        assertTrue(req.overwrite());
        assertEquals(TIMEOUT, req.overrideConfiguration().get().apiCallTimeout().get());
    }

    @Test
    void writeFailureMapsToSsmUnavailableWithoutLeaks() {
        SsmRefreshTokenStore store = new SsmRefreshTokenStore(
                client(r -> { throw SdkClientException.create(SECRET); }));
        GraphException e = assertThrows(GraphException.class,
                () -> store.write(NAME, SECRET, TIMEOUT));
        assertEquals(GraphErrorCategory.SSM_UNAVAILABLE, e.category());
        assertFalse(e.getMessage().contains(SECRET));
    }

    @Test
    void closeClosesClient() {
        new SsmRefreshTokenStore(client(r -> null)).close();
        assertTrue(closed);
    }

    @Test
    void fakeStoreRoundTrip() throws Exception {
        FakeRefreshTokenStore fake = new FakeRefreshTokenStore();
        assertThrows(GraphException.class, () -> fake.read(NAME, TIMEOUT));
        fake.write(NAME, "x", TIMEOUT);
        assertEquals("x", fake.read(NAME, TIMEOUT));
        assertEquals(1, fake.writes().size());
    }
}
