package de.goForFun.familienkalender.outlook;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import net.fortuna.ical4j.model.Calendar;

/** Skriptierbarer {@link IcsFetcher} mit Aufrufprotokoll. */
public final class FakeIcsFetcher implements IcsFetcher {

    private Calendar result = new Calendar();
    private IcsFetchException failure;
    private RuntimeException runtimeFailure;
    private final List<URI> calls = new ArrayList<>();

    public FakeIcsFetcher returning(Calendar calendar) {
        this.result = calendar;
        this.failure = null;
        this.runtimeFailure = null;
        return this;
    }

    public FakeIcsFetcher failingWith(IcsFetchException e) {
        this.failure = e;
        this.runtimeFailure = null;
        return this;
    }

    public FakeIcsFetcher failingWithRuntime(RuntimeException e) {
        this.runtimeFailure = e;
        this.failure = null;
        return this;
    }

    public List<URI> calls() {
        return List.copyOf(calls);
    }

    public int callCount() {
        return calls.size();
    }

    @Override
    public Calendar fetch(URI url) throws IcsFetchException {
        calls.add(url);
        if (runtimeFailure != null) {
            throw runtimeFailure;
        }
        if (failure != null) {
            throw failure;
        }
        return result;
    }
}
