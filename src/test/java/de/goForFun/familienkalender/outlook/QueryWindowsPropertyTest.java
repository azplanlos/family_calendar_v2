package de.goForFun.familienkalender.outlook;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feature: outlook-graph-source, Property 14: Zeitfenster werden korrekt gebildet und formatiert.
 *
 * <p><b>Validates: Requirements 4.1, 4.2, 4.15, 13.4</b>
 */
class QueryWindowsPropertyTest {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Provide
    Arbitrary<Instant> instants() {
        return Arbitraries.longs().between(0L, 4_102_444_800L)
                .flatMap(sec -> Arbitraries.integers().between(0, 999_999_999)
                        .map(nano -> Instant.ofEpochSecond(sec, nano)));
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        return Arbitraries.longs().between(LocalDate.of(1990, 1, 1).toEpochDay(),
                        LocalDate.of(2060, 12, 31).toEpochDay())
                .map(LocalDate::ofEpochDay);
    }

    @Provide
    Arbitrary<ZoneId> zones() {
        return Arbitraries.of(List.of("UTC", "Europe/Berlin", "America/New_York", "Asia/Kolkata",
                        "Australia/Lord_Howe", "Pacific/Auckland", "America/Sao_Paulo", "Africa/Cairo"))
                .map(ZoneId::of);
    }

    @Property(tries = 200)
    void feedWindowIsOneDayBackAndHorizonForward(
            @ForAll("instants") Instant now,
            @ForAll("horizons") int horizon) {
        QueryWindow w = QueryWindows.feed(now, horizon);
        assertEquals(now.minus(1, ChronoUnit.DAYS), w.start());
        assertEquals(now.plus(horizon, ChronoUnit.DAYS), w.end());
    }

    @Provide
    Arbitrary<Integer> horizons() {
        return Arbitraries.integers().between(1, 730);
    }

    @Property(tries = 300)
    void deviceWindowSpansCurrentAndNextMonthInZone(
            @ForAll("dates") LocalDate ref,
            @ForAll("zones") ZoneId zone) {
        QueryWindow w = QueryWindows.device(ref, zone);

        LocalDate firstDay = YearMonth.from(ref).atDay(1);
        LocalDate dayAfterNextMonth = YearMonth.from(ref).plusMonths(1).atEndOfMonth().plusDays(1);
        assertEquals(firstDay.atStartOfDay(zone).toInstant(), w.start());
        assertEquals(dayAfterNextMonth.atStartOfDay(zone).toInstant(), w.end());

        assertEquals(firstDay, w.start().atZone(zone).toLocalDate());
        assertEquals(dayAfterNextMonth, w.end().atZone(zone).toLocalDate());
        assertEquals(dayAfterNextMonth.getDayOfMonth(), 1);
        assertTrue(w.start().isBefore(w.end()));
    }

    @Property(tries = 200)
    void formattedBoundsParseBackToTruncatedInstants(
            @ForAll("instants") Instant now,
            @ForAll("horizons") int horizon) {
        QueryWindow w = QueryWindows.feed(now, horizon);
        for (Instant bound : List.of(w.start(), w.end())) {
            String text = FORMAT.format(bound);
            assertTrue(text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"), text);
            Instant parsed = LocalDateTime.parse(text.substring(0, text.length() - 1))
                    .toInstant(ZoneOffset.UTC);
            assertEquals(bound.truncatedTo(ChronoUnit.SECONDS), parsed);
        }
    }

    @Property(tries = 100)
    void deviceBoundsFormatAndParseBack(
            @ForAll("dates") LocalDate ref,
            @ForAll("zones") ZoneId zone) {
        QueryWindow w = QueryWindows.device(ref, zone);
        for (Instant bound : List.of(w.start(), w.end())) {
            String text = FORMAT.format(bound);
            Instant parsed = LocalDateTime.parse(text.substring(0, text.length() - 1))
                    .toInstant(ZoneOffset.UTC);
            assertEquals(bound, parsed);
        }
    }
}
