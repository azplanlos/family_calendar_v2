package de.goForFun.familienkalender.outlook;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Bildung der Abfragefenster für Feed und Device.
 */
public final class QueryWindows {

    private QueryWindows() {
    }

    /** Feed-Fenster: {@code [now - 1 Tag, now + horizonDays Tage]}. */
    public static QueryWindow feed(Instant now, int horizonDays) {
        return new QueryWindow(now.minus(1, ChronoUnit.DAYS), now.plus(horizonDays, ChronoUnit.DAYS));
    }

    /**
     * Geräte-Fenster: Beginn des ersten Tages des Monats von {@code referenceDate} 00:00 bis
     * Beginn des Tages nach dem Ende des Folgemonats (exklusiv), jeweils in {@code zone}.
     * Identisch zu {@code rangeStart}/{@code rangeEnd} im {@code EventRepository}.
     */
    public static QueryWindow device(LocalDate referenceDate, ZoneId zone) {
        LocalDate rangeStart = YearMonth.from(referenceDate).atDay(1);
        LocalDate rangeEnd = YearMonth.from(referenceDate).plusMonths(1).atEndOfMonth();
        return new QueryWindow(
                rangeStart.atStartOfDay(zone).toInstant(),
                rangeEnd.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
