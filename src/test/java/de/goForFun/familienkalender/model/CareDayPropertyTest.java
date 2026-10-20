package de.goForFun.familienkalender.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

        Arbitrary<CareDay> fullDays = Combinators.combine(dates, parties)
                .as(CareDay::fullDay);

        Arbitrary<CareDay> switchDays = Combinators.combine(dates, parties, parties)
                .as(CareDay::switchDay);

        return Arbitraries.oneOf(fullDays, switchDays);
    }
}
