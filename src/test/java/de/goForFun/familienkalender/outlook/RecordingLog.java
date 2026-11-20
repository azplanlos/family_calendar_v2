package de.goForFun.familienkalender.outlook;

import java.util.ArrayList;
import java.util.List;

/** Sammelt WARN- und ERROR-Zeilen für Assertions. */
public final class RecordingLog implements OutlookLog {

    private final List<String> warnings = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    @Override
    public void warn(String message) {
        warnings.add(message);
    }

    @Override
    public void error(String message) {
        errors.add(message);
    }

    public List<String> warnings() {
        return warnings;
    }

    public List<String> errors() {
        return errors;
    }

    /** Alle Zeilen (zuerst WARN, dann ERROR). */
    public List<String> all() {
        List<String> all = new ArrayList<>(warnings);
        all.addAll(errors);
        return all;
    }
}
