package de.goForFun.familienkalender.outlook;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * Fehler der Graph-Anbindung. Trägt weder eine Ursache noch Body-, Token- oder Termininhalte;
 * {@link #safeMessage()} (auch {@link #getMessage()}) enthält nur Kategorie, Status und Code.
 */
public final class GraphException extends Exception {

    private static final long serialVersionUID = 1L;
    private static final Pattern CODE_ALLOWLIST = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final GraphErrorCategory category;
    private final Integer httpStatus;
    private final String graphCode;

    public GraphException(GraphErrorCategory category) {
        this(category, null, null);
    }

    /**
     * @param httpStatus HTTP-Status oder {@code null}
     * @param rawGraphCode Fehlercode aus dem Graph-Body oder {@code null}; wird verworfen, wenn er
     *     nicht {@code [A-Za-z0-9_.-]{1,64}} entspricht
     */
    public GraphException(GraphErrorCategory category, Integer httpStatus, String rawGraphCode) {
        super(buildMessage(Objects.requireNonNull(category, "category"), httpStatus,
                sanitize(rawGraphCode)), null, false, true);
        this.category = category;
        this.httpStatus = httpStatus;
        this.graphCode = sanitize(rawGraphCode);
    }

    private static String sanitize(String code) {
        return code != null && CODE_ALLOWLIST.matcher(code).matches() ? code : null;
    }

    private static String buildMessage(GraphErrorCategory category, Integer status, String code) {
        StringBuilder sb = new StringBuilder(category.displayName());
        if (status != null) {
            sb.append(" (HTTP ").append(status).append(')');
        }
        if (code != null) {
            sb.append(" [").append(code).append(']');
        }
        return sb.toString();
    }

    public GraphErrorCategory category() {
        return category;
    }

    public OptionalInt httpStatus() {
        return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }

    /** Bereits gegen die Allowlist bereinigt. */
    public Optional<String> graphCode() {
        return Optional.ofNullable(graphCode);
    }

    /** Nur Kategorie, Status und Code; nie Body, Token oder Termininhalte. */
    public String safeMessage() {
        return getMessage();
    }
}
