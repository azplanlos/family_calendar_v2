package de.goForFun.familienkalender.outlook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.goForFun.familienkalender.AppTimeZone;
import java.time.DateTimeException;
import java.time.ZoneId;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * **Feature: outlook-graph-source, Property 15: Konfigurationswerte werden robust aufgelöst**
 *
 * **Validates: Requirements 4.3, 6.2**
 */
class ConfigResolutionPropertyTest {

    private static boolean isValidHorizon(String raw) {
        if (raw == null || !raw.matches("[0-9]{1,3}")) {
            return false;
        }
        int v = Integer.parseInt(raw);
        return v >= 1 && v <= 730;
    }

    @Provide
    Arbitrary<String> horizonInputs() {
        Arbitrary<String> fixed = Arbitraries.of(
                "", " ", "  ", "\t", "-1", "+5", "+30", "-0", "1.5", "30.0", "0x10", "0", "1", "730", "731",
                "999", "1000", "00", "007", "0730", "99999999999999999999", "abc", "30 ", " 30", "3 0",
                "1e2", "٣٠", "30\n", "365");
        Arbitrary<String> numbers = Arbitraries.integers().between(-2000, 2000).map(String::valueOf);
        Arbitrary<String> digits = Arbitraries.strings().numeric().ofMinLength(1).ofMaxLength(25);
        Arbitrary<String> text = Arbitraries.strings().ofMaxLength(12);
        return Arbitraries.oneOf(fixed, numbers, digits, text);
    }

    @Property(tries = 300)
    void horizonDaysResolvesRobustly(@ForAll("horizonInputs") String raw) {
        HorizonDays.Result r = HorizonDays.parse(raw);
        assertTrue(r.days() >= 1 && r.days() <= 730, "Tage im Bereich 1..730");
        if (isValidHorizon(raw)) {
            assertEquals(Integer.parseInt(raw), r.days());
            assertFalse(r.invalidWarning());
        } else {
            assertEquals(365, r.days());
            assertEquals(!raw.isEmpty(), r.invalidWarning(),
                    "Warnung nur bei gesetztem, aber ungültigem Wert");
        }
    }

    @Test
    void horizonDaysNullAndEmptyGiveDefaultWithoutWarning() {
        assertEquals(new HorizonDays.Result(365, false), HorizonDays.parse(null));
        assertEquals(new HorizonDays.Result(365, false), HorizonDays.parse(""));
        assertEquals(new HorizonDays.Result(1, false), HorizonDays.parse("1"));
        assertEquals(new HorizonDays.Result(730, false), HorizonDays.parse("730"));
        assertEquals(new HorizonDays.Result(365, true), HorizonDays.parse("731"));
        assertEquals(new HorizonDays.Result(365, true), HorizonDays.parse("0"));
    }

    @Provide
    Arbitrary<String> timezoneInputs() {
        Arbitrary<String> valid = Arbitraries.of(
                "Europe/Berlin", "UTC", "America/New_York", "Asia/Kolkata", "Australia/Sydney",
                "  Europe/Paris  ", "Pacific/Auckland");
        Arbitrary<String> fixed = Arbitraries.of(
                "", " ", "\t", "Mars/Olympus", "W. Europe Standard Time", "Europe/", "Europe/Berlin/x",
                "+25:00", "foo", "Europe\\Berlin");
        Arbitrary<String> text = Arbitraries.strings().ofMaxLength(20);
        return Arbitraries.oneOf(valid, fixed, text, Arbitraries.just(null));
    }

    private static ZoneId expectedZone(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return ZoneId.of(raw.trim());
            } catch (DateTimeException e) {
                // Default
            }
        }
        return null;
    }

    @Property(tries = 300)
    void timezoneResolvesRobustly(@ForAll("timezoneInputs") String raw) {
        AppTimeZone.Resolution res = AppTimeZone.resolveWithReason(raw);
        assertNotNull(res.zone());
        ZoneId expected = expectedZone(raw);
        if (expected != null) {
            assertEquals(expected, res.zone());
            assertNull(res.fallbackReason());
        } else {
            assertEquals(ZoneId.of("Europe/Berlin"), res.zone());
            assertNotNull(res.fallbackReason());
            assertFalse(res.fallbackReason().isBlank());
        }
    }
}
