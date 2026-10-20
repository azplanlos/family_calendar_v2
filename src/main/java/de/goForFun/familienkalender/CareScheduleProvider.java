package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;
import de.goForFun.familienkalender.model.Event;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reine Logik-Klasse (keine I/O), die aus ganztägigen Betreuungs-Events
 * (Wechselmodell_Feed, {@code EventSource.CUSTODY}) eine deterministische
 * Tag-zu-{@link CareDay}-Zuordnung ableitet.
 *
 * <p>Der Algorithmus folgt dem Design ("Key Algorithm: Block-Erkennung und
 * Wechseltag-Ableitung"):
 * <ol>
 *   <li>Jedes Event wird zu einem {@link CareBlock} über den halboffenen
 *       Bereich {@code [DTSTART, DTEND)}.</li>
 *   <li>Pro Tag gewinnt der Block mit dem lexikografisch größten Tie-Key
 *       (Overlap-Determinismus, reihenfolgeunabhängig).</li>
 *   <li>Je abgedecktem Tag wird ein {@link CareDay} abgeleitet; der letzte
 *       Blocktag wird nur dann zum Wechseltag, wenn am Folgetag ein
 *       <em>anderer</em> maßgeblicher Block gilt.</li>
 * </ol>
 *
 * <p>Die Klasse ist bewusst frei von I/O, damit sie property-testbar ist.
 */
public class CareScheduleProvider {

    private final List<Event> custodyEvents;
    private final String maleMatch;

    /**
     * @param custodyEvents ganztägige Betreuungs-Events ({@code EventSource.CUSTODY});
     *                      {@code startTime}/{@code endTime} als {@code [DTSTART, DTEND)}
     *                      (jeweils {@code 00:00})
     * @param maleMatch     Mann_Kennung (nicht leer – der Aufrufer stellt das sicher)
     */
    public CareScheduleProvider(List<Event> custodyEvents, String maleMatch) {
        this.custodyEvents = custodyEvents;
        this.maleMatch = maleMatch;
    }

    /**
     * Interne Zwischenstruktur: ein Betreuungsblock über den halboffenen
     * Datumsbereich {@code [startInclusive, endExclusive)}.
     *
     * @param startInclusive erster abgedeckter Tag (einschließlich)
     * @param endExclusive   erster nicht mehr abgedeckter Tag (ausschließlich)
     * @param party          Betreuende_Partei dieses Blocks
     * @param tieKey         deterministischer Tie-Key für den Overlap-Determinismus
     */
    record CareBlock(LocalDate startInclusive, LocalDate endExclusive, CareParty party, String tieKey) {
        /** Letzter abgedeckter Tag des Blocks ({@code endExclusive - 1}). */
        LocalDate lastDay() {
            return endExclusive.minusDays(1);
        }

        /** {@code true}, wenn der Tag {@code d} im halboffenen Bereich des Blocks liegt. */
        boolean covers(LocalDate d) {
            return !d.isBefore(startInclusive) && d.isBefore(endExclusive);
        }
    }

    /**
     * Liefert die deterministische Tag-zu-{@link CareDay}-Zuordnung für alle
     * abgedeckten Tage. Nicht abgedeckte Tage besitzen keinen Eintrag.
     */
    public Map<LocalDate, CareDay> buildSchedule() {
        // 1) Events -> Blöcke über halboffenes [DTSTART, DTEND)
        List<CareBlock> blocks = new java.util.ArrayList<>();
        for (Event e : custodyEvents) {
            if (e == null || e.startTime() == null || e.endTime() == null) {
                continue;
            }
            LocalDate startDate = e.startTime().toLocalDate();
            LocalDate endExcl = e.endTime().toLocalDate();
            // Robustheit: mind. 1 Tag (deckt u.a. den Länge-1-/degenerierten Randfall ab)
            if (!endExcl.isAfter(startDate)) {
                endExcl = startDate.plusDays(1);
            }
            String summary = e.summary();
            CareParty party = summaryContainsIgnoreCase(summary, maleMatch) ? CareParty.MANN : CareParty.FRAU;
            String tieKey = startDate + "|" + endExcl + "|" + (summary == null ? "" : summary);
            blocks.add(new CareBlock(startDate, endExcl, party, tieKey));
        }

        // 2) Pro Tag den maßgeblichen Block bestimmen (lexikografisch größter Tie-Key gewinnt)
        Map<LocalDate, CareBlock> dayToBlock = new HashMap<>();
        for (CareBlock b : blocks) {
            for (LocalDate d = b.startInclusive(); d.isBefore(b.endExclusive()); d = d.plusDays(1)) {
                CareBlock cur = dayToBlock.get(d);
                if (cur == null || b.tieKey().compareTo(cur.tieKey()) > 0) {
                    dayToBlock.put(d, b);
                }
            }
        }

        // 3) CareDay je abgedecktem Tag ableiten
        Map<LocalDate, CareDay> schedule = new HashMap<>();
        for (Map.Entry<LocalDate, CareBlock> entry : dayToBlock.entrySet()) {
            LocalDate d = entry.getKey();
            CareBlock b = entry.getValue();
            boolean isLastDayOfBlock = d.equals(b.lastDay());
            if (isLastDayOfBlock) {
                CareBlock next = dayToBlock.get(d.plusDays(1));
                if (next != null && next != b) {
                    // Wechseltag: firstHalf = endender Block, secondHalf = Folgeblock
                    schedule.put(d, CareDay.switchDay(d, b.party(), next.party()));
                } else {
                    schedule.put(d, CareDay.fullDay(d, b.party()));
                }
            } else {
                schedule.put(d, CareDay.fullDay(d, b.party()));
            }
        }
        return schedule;
    }

    /**
     * {@code true}, wenn {@code summary} die {@code maleMatch}-Kennung
     * (case-insensitive, {@link Locale#ROOT}) als Teilstring enthält.
     * Ein leeres bzw. {@code null}-Summary enthält die Kennung nie ⇒ {@code FRAU}.
     */
    private static boolean summaryContainsIgnoreCase(String summary, String maleMatch) {
        if (summary == null || summary.isEmpty() || maleMatch == null || maleMatch.isEmpty()) {
            return false;
        }
        return summary.toLowerCase(Locale.ROOT).contains(maleMatch.toLowerCase(Locale.ROOT));
    }
}
