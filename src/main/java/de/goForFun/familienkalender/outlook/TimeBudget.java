package de.goForFun.familienkalender.outlook;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Gesamtzeitbudget eines Graph-Laufs, gemessen mit einer injizierten {@link Clock}. */
public final class TimeBudget {

    private final Clock clock;
    private final Instant deadline;

    private TimeBudget(Clock clock, Instant deadline) {
        this.clock = clock;
        this.deadline = deadline;
    }

    public static TimeBudget start(Clock clock, Duration total) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(total, "total");
        return new TimeBudget(clock, clock.instant().plus(total));
    }

    /** Restbudget; negativ oder null, wenn abgelaufen. */
    public Duration remaining() {
        return Duration.between(clock.instant(), deadline);
    }

    /** @throws GraphException Kategorie {@code BUDGET}, wenn das Restbudget &le; 0 ist */
    public void checkpoint() throws GraphException {
        if (remaining().compareTo(Duration.ZERO) <= 0) {
            throw new GraphException(GraphErrorCategory.BUDGET);
        }
    }

    /** @return min(cap, Restbudget) nach {@link #checkpoint()} */
    public Duration stepTimeout(Duration cap) throws GraphException {
        Objects.requireNonNull(cap, "cap");
        Duration rest = remaining();
        if (rest.compareTo(Duration.ZERO) <= 0) {
            throw new GraphException(GraphErrorCategory.BUDGET);
        }
        return cap.compareTo(rest) < 0 ? cap : rest;
    }
}
