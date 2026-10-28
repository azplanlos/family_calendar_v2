package de.goForFun.familienkalender.anonfeed;

import static org.assertj.core.api.Assertions.assertThat;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Smoke-Test für den geteilten {@link CalendarArbitraries}-Generator (Task 2.2).
 *
 * <p>Verifiziert, dass der {@code @Provide}-Generator tatsächlich wohlgeformte, parsebare
 * {@code VCALENDAR}-Modellobjekte liefert. Dies ist NICHT eine der acht Correctness-Properties
 * (Tasks 2.3–2.10), sondern eine Absicherung der gemeinsamen Testoberfläche selbst.
 */
class CalendarArbitrariesSmokeTest extends CalendarArbitraries {

    @Property(tries = 50)
    void generatedCalendarsAreWellFormedAndReparsable(@ForAll("randomCalendars") Calendar calendar) {
        // Grundgerüst vorhanden.
        assertThat(calendar.getProperty(net.fortuna.ical4j.model.Property.PRODID)).isPresent();
        assertThat(calendar.getProperty(net.fortuna.ical4j.model.Property.VERSION)).isPresent();

        // Serialisieren und mit demselben Builder wie im Produktionspfad erneut einlesen.
        String ics = calendar.toString();
        Calendar reparsed = reparse(ics);

        // Jedes VEVENT im (re)geparsten Kalender ist konsistent zugreifbar.
        for (Component c : reparsed.getComponents(Component.VEVENT)) {
            assertThat(c.getName()).isEqualTo(Component.VEVENT);
        }
        // Die VEVENT-Anzahl bleibt über den Round-Trip stabil.
        assertThat(reparsed.getComponents(Component.VEVENT))
                .hasSameSizeAs(calendar.getComponents(Component.VEVENT));
    }

    @Property(tries = 50)
    void generatedCalendarSpecsBuildSuccessfully(@ForAll("calendarSpecs") CalendarArbitraries.CalendarSpec spec) {
        Calendar calendar = CalendarArbitraries.buildCalendar(spec);
        // Anzahl der VEVENTs == Anzahl der spezifizierten Events.
        assertThat(calendar.getComponents(Component.VEVENT))
                .hasSize(spec.events().size());
    }

    private static Calendar reparse(String ics) {
        try (var in = new ByteArrayInputStream(ics.getBytes(StandardCharsets.UTF_8))) {
            return new CalendarBuilder().build(in);
        } catch (Exception e) {
            throw new AssertionError("Erzeugter Feed ist nicht reparsebar:\n" + ics, e);
        }
    }
}
