package de.goForFun.familienkalender.outlook;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test-Fake für {@link GraphHttp}: liefert vorbereitete Antworten (pro URI oder der Reihe nach)
 * oder wirft {@link GraphException}, protokolliert jeden Aufruf und kann eine {@link MutableClock}
 * pro Aufruf vorrücken. Überschreitet die vorgegebene Dauer den übergebenen Timeout, wird
 * {@code TIMEOUT} geworfen (die Uhr rückt dann um den Timeout vor).
 */
public final class ScriptedGraphHttp implements GraphHttp {

    /** Ein protokollierter Aufruf. */
    public record Recorded(HttpCall call, Duration timeout) {
        public URI uri() {
            return call.uri();
        }
    }

    /** Ein Skriptschritt: Ergebnis oder Fehler, optional mit simulierter Dauer. */
    public record Step(HttpResult result, GraphException error, Duration duration) {
        public static Step ok(int status, String body) {
            return new Step(new HttpResult(status, body), null, Duration.ZERO);
        }

        public static Step fail(GraphException e) {
            return new Step(null, e, Duration.ZERO);
        }

        public Step taking(Duration d) {
            return new Step(result, error, d);
        }
    }

    private final Deque<Step> sequence = new ArrayDeque<>();
    private final Map<URI, Deque<Step>> perUri = new HashMap<>();
    private final List<Recorded> calls = new ArrayList<>();
    private final MutableClock clock;

    public ScriptedGraphHttp() {
        this(null);
    }

    /** @param clock wird pro Aufruf um {@link Step#duration()} vorgerückt; darf {@code null} sein */
    public ScriptedGraphHttp(MutableClock clock) {
        this.clock = clock;
    }

    /** Nächster Aufruf (URI-unabhängig, sofern kein URI-Skript existiert). */
    public ScriptedGraphHttp enqueue(Step step) {
        sequence.add(step);
        return this;
    }

    public ScriptedGraphHttp enqueue(int status, String body) {
        return enqueue(Step.ok(status, body));
    }

    public ScriptedGraphHttp enqueue(GraphException e) {
        return enqueue(Step.fail(e));
    }

    /** Nächster Aufruf an genau diese URI. */
    public ScriptedGraphHttp enqueueFor(URI uri, Step step) {
        perUri.computeIfAbsent(uri, u -> new ArrayDeque<>()).add(step);
        return this;
    }

    public synchronized List<Recorded> calls() {
        return Collections.unmodifiableList(new ArrayList<>(calls));
    }

    public synchronized int callCount() {
        return calls.size();
    }

    @Override
    public synchronized HttpResult send(HttpCall call, Duration timeout) throws GraphException {
        calls.add(new Recorded(call, timeout));
        Deque<Step> uriSteps = perUri.get(call.uri());
        Step step = uriSteps != null && !uriSteps.isEmpty() ? uriSteps.poll() : sequence.poll();
        if (step == null) {
            throw new IllegalStateException("Kein Skriptschritt für Aufruf " + calls.size() + " an " + call.uri());
        }
        if (step.duration().compareTo(timeout) > 0) {
            if (clock != null) {
                clock.advance(timeout);
            }
            throw new GraphException(GraphErrorCategory.TIMEOUT);
        }
        if (clock != null) {
            clock.advance(step.duration());
        }
        if (step.error() != null) {
            throw step.error();
        }
        return step.result();
    }
}
