package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integrationstest gegen einen wechselplan.de-ähnlichen Feed.
 *
 * <p><b>Datenschutz:</b> Die Testressource {@code wechselplan-sample.ics} ist vollständig
 * synthetisch und anonymisiert – erfundene Namen (Max Mustermann, Erika Beispiel, „Kind")
 * und ein frei erfundener Datumsbereich (März 2040). Sie enthält KEINE realen Personen-
 * oder Betreuungsdaten, bildet aber dieselben Strukturmerkmale des realen Feeds ab
 * (UID-Präfixe {@code wp-care-}/{@code wp-vacation-}/{@code wp-handover-}/{@code wp-schoolvac-},
 * ganztägige Blöcke, getimte Übergaben inkl. Mitternachts-Wechsel, überlappende Schulferien).
 *
 * <p>Pinnt zwei früher fehlerhafte Fälle:
 * <ul>
 *   <li>Urlaubswoche ({@code wp-vacation-}, „Kind: Urlaub – Max Mustermann", 12.–19.03.2040) muss
 *       durchgehend MANN sein – das überlappende {@code wp-schoolvac-}-Event darf die Partei NICHT
 *       auf FRAU kippen.</li>
 *   <li>Die Mitternachts-Übergabe am 11.03.2040 00:00 („Wechsel zu Max Mustermann") muss ein echter
 *       Wechseltag FRAU→MANN sein (firstHalf = abgebende Partei des Vortags = Erika/FRAU).</li>
 * </ul>
 */
class WechselplanFeedTest {

    /** Mann-Kennung = erfundener Vatername aus der synthetischen Fixture. */
    private static final String MALE_MATCH = "Max Mustermann";

    private Map<LocalDate, CareDay> schedule() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/wechselplan-sample.ics")) {
            assertThat(in).as("Testressource wechselplan-sample.ics muss vorhanden sein").isNotNull();
            List<Event> events = new IcalParser().parse(
                    in, LocalDate.of(2040, 3, 1), LocalDate.of(2040, 3, 31), EventSource.CUSTODY);
            return new CareScheduleProvider(events, MALE_MATCH).buildSchedule();
        }
    }

    @Test
    void vacationWeekIsFullyMann_schoolHolidaysIgnored() throws Exception {
        Map<LocalDate, CareDay> s = schedule();
        // Urlaub 12.03.–19.03.2040 (halboffen bis 20.03.): jeder Tag MANN, kein Wechseltag.
        // Die überlappenden Frühjahrsferien (14.–17.03.) dürfen die Partei nicht beeinflussen.
        for (LocalDate d = LocalDate.of(2040, 3, 12); d.isBefore(LocalDate.of(2040, 3, 20)); d = d.plusDays(1)) {
            CareDay cd = s.get(d);
            assertThat(cd).as("Tag %s muss im Schedule sein", d).isNotNull();
            assertThat(cd.switchDay()).as("Tag %s ist Ganztag", d).isFalse();
            assertThat(cd.fullDay()).as("Tag %s muss MANN sein (Urlaub bei Max)", d).isEqualTo(CareParty.MANN);
        }
    }

    @Test
    void midnightHandoverIsRealSwitchDay() throws Exception {
        Map<LocalDate, CareDay> s = schedule();
        CareDay cd = s.get(LocalDate.of(2040, 3, 11));
        assertThat(cd).isNotNull();
        assertThat(cd.switchDay()).as("11.03. ist Wechseltag").isTrue();
        assertThat(cd.firstHalf()).as("abgebende Partei = Vortag (Erika/FRAU)").isEqualTo(CareParty.FRAU);
        assertThat(cd.secondHalf()).as("übernehmende Partei = Max/MANN").isEqualTo(CareParty.MANN);
        assertThat(cd.switchTime()).as("Übergabezeit 00:00").isEqualTo(LocalTime.MIDNIGHT);
    }

    @Test
    void afternoonHandoverCarriesTimeAndParties() throws Exception {
        Map<LocalDate, CareDay> s = schedule();
        // 23.03. 16:00: Wechsel zurück zu Erika. Abgebende Partei = Vortag (Max/MANN).
        CareDay cd = s.get(LocalDate.of(2040, 3, 23));
        assertThat(cd).isNotNull();
        assertThat(cd.switchDay()).isTrue();
        assertThat(cd.firstHalf()).isEqualTo(CareParty.MANN);
        assertThat(cd.secondHalf()).isEqualTo(CareParty.FRAU);
        assertThat(cd.switchTime()).isEqualTo(LocalTime.of(16, 0));
    }

    @Test
    void schoolHolidayMidVacationStaysMann() throws Exception {
        // Ein Tag mitten in den Frühjahrsferien (16.03.) muss MANN bleiben – das Schulferien-Event
        // (wp-schoolvac-) ist für die Partei-Bestimmung irrelevant.
        Map<LocalDate, CareDay> s = schedule();
        CareDay cd = s.get(LocalDate.of(2040, 3, 16));
        assertThat(cd).isNotNull();
        assertThat(cd.fullDay()).isEqualTo(CareParty.MANN);
    }
}
