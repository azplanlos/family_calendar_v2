package de.goForFun.familienkalender.outlook;

import java.util.ArrayList;
import java.util.List;
import net.fortuna.ical4j.model.Calendar;

/** Skriptierbare {@link GraphCalendarSource} mit Aufrufprotokoll. */
public final class FakeGraphSource implements GraphCalendarSource {

    private Calendar result = new Calendar();
    private GraphException failure;
    private RuntimeException runtimeFailure;
    private final List<QueryWindow> calls = new ArrayList<>();

    public FakeGraphSource returning(Calendar calendar) {
        this.result = calendar;
        this.failure = null;
        this.runtimeFailure = null;
        return this;
    }

    public FakeGraphSource failingWith(GraphException e) {
        this.failure = e;
        this.runtimeFailure = null;
        return this;
    }

    public FakeGraphSource failingWithRuntime(RuntimeException e) {
        this.runtimeFailure = e;
        this.failure = null;
        return this;
    }

    public List<QueryWindow> calls() {
        return List.copyOf(calls);
    }

    public int callCount() {
        return calls.size();
    }

    @Override
    public Calendar fetch(QueryWindow window) throws GraphException {
        calls.add(window);
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
