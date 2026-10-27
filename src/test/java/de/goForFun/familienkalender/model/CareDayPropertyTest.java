package de.goForFun.familienkalender.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-Tests für {@link CareDay} im Wechselmodell-Kalender.
 */
class CareDayPropertyTest {

    // Feature: wechselmodell-kalender, Property 8: iconParty() ist secondHalf bei Wechseltag, sonst fullDay
    @Property(tries = 100)
    void iconPartyIsSecondHalfOnSwitchDayOtherwiseFullDay(@ForAll("careDays") CareDay day) {
        if (day.switchDay()) {
            assertThat(day.iconParty()).isEqualTo(day.secondHalf());
        } else {
            assertThat(day.iconParty()).isEqualTo(day.fullDay());
        }
    }

    @Provide
    Arbitrary<CareDay> careDays() {
        Arbitrary<LocalDate> dates = Arbitraries.longs()
                .between(LocalDate.of(2000, 1, 1).toEpochDay(), LocalDate.of(2100, 12, 31).toEpochDay())
                .map(LocalDate::ofEpochDay);
        Arbitrary<CareParty> parties = Arbitraries.of(CareParty.class);
        // Übergabezeit: Stunde 0..23, Minute in {0,15,30,45}.
        Arbitrary<LocalTime> switchTimes = Combinators.combine(
                        Arbitraries.integers().between(0, 23),
                        Arbitraries.of(0, 15, 30, 45))
                .as(LocalTime::of);

        Arbitrary<CareDay> fullDays = Combinators.combine(dates, parties)
                .as(CareDay::fullDay);

        Arbitrary<CareDay> switchDays = Combinators.combine(dates, parties, parties, switchTimes)
                .as(CareDay::switchDay);

        return Arbitraries.oneOf(fullDays, switchDays);
    }
}
