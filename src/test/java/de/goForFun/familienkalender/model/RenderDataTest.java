package de.goForFun.familienkalender.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-Tests für die Abwärtskompatibilität der {@link RenderData}-Konstruktoren.
 *
 * <p>Verifiziert, dass bestehende Aufrufe ohne {@code custodySchedule} weiterhin
 * funktionieren und eine leere Map liefern, während der neue kanonische
 * Konstruktor die übergebene Map übernimmt.
 *
 * <p>Validates: Requirements 3.2, 7.3
 */
class RenderDataTest {

    private final LocalDateTime now = LocalDateTime.of(2024, 1, 15, 10, 0);

    @Test
    void sixArgConstructor_withoutErrorsAndWithoutCustodySchedule_yieldsEmptyErrorsAndEmptySchedule() {
        RenderData data = new RenderData(
                now,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());

        assertThat(data.errors()).isEmpty();
        assertThat(data.custodySchedule()).isEmpty();
    }

    @Test
    void sevenArgConstructor_withErrorsAndWithoutCustodySchedule_keepsErrorsAndYieldsEmptySchedule() {
        List<String> errors = List.of("Feed nicht erreichbar");

        RenderData data = new RenderData(
                now,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                errors);

        assertThat(data.errors()).isEqualTo(errors);
        assertThat(data.custodySchedule()).isEmpty();
    }

    @Test
    void canonicalConstructor_withCustodySchedule_returnsPassedMap() {
        LocalDate date = LocalDate.of(2024, 1, 15);
        Map<LocalDate, CareDay> schedule = Map.of(date, CareDay.fullDay(date, CareParty.MANN));

        RenderData data = new RenderData(
                now,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                schedule);

        assertThat(data.custodySchedule()).isEqualTo(schedule);
    }
}
