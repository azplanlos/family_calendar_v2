package de.goForFun.familienkalender.outlook;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** In-Memory-{@link RefreshTokenStore} mit Aufrufprotokoll und einstellbaren Fehlern. */
public final class FakeRefreshTokenStore implements RefreshTokenStore {

    public record Call(String parameterName, String value, Duration timeout) { }

    private final Map<String, String> values = new HashMap<>();
    private final List<Call> reads = new ArrayList<>();
    private final List<Call> writes = new ArrayList<>();
    private GraphException readFailure;
    private GraphException writeFailure;
    private RuntimeException readRuntimeFailure;

    public FakeRefreshTokenStore() { }

    public FakeRefreshTokenStore(String parameterName, String value) {
        values.put(parameterName, value);
    }

    public FakeRefreshTokenStore put(String parameterName, String value) {
        values.put(parameterName, value);
        return this;
    }

    public FakeRefreshTokenStore failReads(GraphException e) {
        this.readFailure = e;
        return this;
    }

    public FakeRefreshTokenStore failWrites(GraphException e) {
        this.writeFailure = e;
        return this;
    }

    public FakeRefreshTokenStore failReadsWith(RuntimeException e) {
        this.readRuntimeFailure = e;
        return this;
    }

    @Override
    public String read(String parameterName, Duration timeout) throws GraphException {
        reads.add(new Call(parameterName, null, timeout));
        if (readRuntimeFailure != null) {
            throw readRuntimeFailure;
        }
        if (readFailure != null) {
            throw readFailure;
        }
        String v = values.get(parameterName);
        if (v == null || v.isEmpty()) {
            throw new GraphException(GraphErrorCategory.SSM_UNAVAILABLE);
        }
        return v;
    }

    @Override
    public void write(String parameterName, String value, Duration timeout) throws GraphException {
        writes.add(new Call(parameterName, value, timeout));
        if (writeFailure != null) {
            throw writeFailure;
        }
        values.put(parameterName, value);
    }

    public String stored(String parameterName) {
        return values.get(parameterName);
    }

    public List<Call> reads() {
        return reads;
    }

    public List<Call> writes() {
        return writes;
    }
}
