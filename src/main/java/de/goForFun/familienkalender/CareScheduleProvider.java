package de.goForFun.familienkalender;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;
import de.goForFun.familienkalender.model.Event;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reine Logik-Klasse (keine I/O), die aus den Events des Wechselmodell-Feeds
 * ({@code EventSource.CUSTODY}) eine deterministische Tag-zu-{@link CareDay}-Zuordnung
 * ableitet.
 *
 * <p>Der Feed enthält zwei Event-Arten:
 * <ol>
 *   <li><b>Ganztägige Betreuungsblöcke</b> ({@code VALUE=DATE}, z. B. „Lukas bei Andreas Zöllner"):
 *       jeder Kalendertag im halboffenen Bereich {@code [DTSTART, DTEND)} gehört zur im
 *       {@code SUMMARY} genannten Partei.</li>
 *   <li><b>Getimte Wechsel-Events</b> (mit Uhrzeit, z. B. „Wechsel zu Andreas Zöllner" um 16:00):
 *       markieren explizit den Übergabe-Zeitpunkt. Der Tag des Events ist ein Wechseltag;
 *       die im {@code SUMMARY} genannte Partei ist die <em>übernehmende</em> (zweite Tageshälfte),
 *       die abgebende (erste Tageshälfte) ist die Ganztagspartei dieses Tages aus den Blöcken.</li>
 * </ol>
 *
 * <p>Die Klasse ist bewusst frei von I/O, damit sie property-testbar ist.
 */
public class CareScheduleProvider {

    private final List<Event> custodyEvents;
    private final String maleMatch;

    /**
     * @param custodyEvents Events des Wechselmodell-Feeds ({@code EventSource.CUSTODY}) –
     *                      ganztägige Betreuungsblöcke und getimte Wechsel-Events gemischt
     * @param maleMatch     Mann_Kennung (nicht leer – der Aufrufer stellt das sicher)
     */
    public CareScheduleProvider(List<Event> custodyEvents, String maleMatch) {
        this.custodyEvents = custodyEvents;
        this.maleMatch = maleMatch;
    }

    /**
     * Interne Zwischenstruktur: ein ganztägiger Betreuungsblock über den halboffenen
     * Datumsbereich {@code [startInclusive, endExclusive)}.
     */
    record CareBlock(LocalDate startInclusive, LocalDate endExclusive, CareParty party, String tieKey) {
        boolean covers(LocalDate d) {
            return !d.isBefore(startInclusive) && d.isBefore(endExclusive);
        }
    }

    /**
     * Liefert die deterministische Tag-zu-{@link CareDay}-Zuordnung für alle abgedeckten
     * Tage. Nicht abgedeckte Tage besitzen keinen Eintrag.
     */
    public Map<LocalDate, CareDay> buildSchedule() {
        List<CareBlock> blocks = new ArrayList<>();
        List<Event> switchEvents = new ArrayList<>();

        // 1) Events in ganztägige Blöcke und getimte Wechsel-Events aufteilen.
        for (Event e : custodyEvents) {
            if (e == null || e.startTime() == null || e.endTime() == null) {
                continue;
            }
            if (isAllDay(e)) {
                LocalDate startDate = e.startTime().toLocalDate();
                LocalDate endExcl = e.endTime().toLocalDate();
                if (!endExcl.isAfter(startDate)) {
                    endExcl = startDate.plusDays(1); // Robustheit: mind. 1 Tag
                }
                String summary = e.summary();
                CareParty party = partyOf(summary);
                String tieKey = startDate + "|" + endExcl + "|" + (summary == null ? "" : summary);
                blocks.add(new CareBlock(startDate, endExcl, party, tieKey));
            } else {
                switchEvents.add(e);
            }
        }

        // 2) Pro Tag den maßgeblichen Ganztagsblock bestimmen
        //    (Overlap-Determinismus: lexikografisch größter Tie-Key gewinnt).
        Map<LocalDate, CareBlock> dayToBlock = new HashMap<>();
        for (CareBlock b : blocks) {
            for (LocalDate d = b.startInclusive(); d.isBefore(b.endExclusive()); d = d.plusDays(1)) {
                CareBlock cur = dayToBlock.get(d);
                if (cur == null || b.tieKey().compareTo(cur.tieKey()) > 0) {
                    dayToBlock.put(d, b);
                }
            }
        }

        // 3) Basis: jeder abgedeckte Tag ist zunächst ein Ganztag mit der Blockpartei.
        Map<LocalDate, CareDay> schedule = new HashMap<>();
        for (Map.Entry<LocalDate, CareBlock> entry : dayToBlock.entrySet()) {
            LocalDate d = entry.getKey();
            schedule.put(d, CareDay.fullDay(d, entry.getValue().party()));
        }

        // 4) Getimte Wechsel-Events anwenden: markieren den Tag als Wechseltag.
        //    Bei mehreren Wechsel-Events am selben Tag gewinnt das späteste (deterministisch).
        Map<LocalDate, Event> switchByDay = new HashMap<>();
        for (Event e : switchEvents) {
            LocalDate d = e.startTime().toLocalDate();
            Event cur = switchByDay.get(d);
            if (cur == null || e.startTime().isAfter(cur.startTime())) {
                switchByDay.put(d, e);
            }
        }

        for (Map.Entry<LocalDate, Event> entry : switchByDay.entrySet()) {
            LocalDate d = entry.getKey();
            Event e = entry.getValue();
            CareParty second = partyOf(e.summary());                 // übernehmende Partei ("Wechsel zu …")
            CareBlock block = dayToBlock.get(d);
            // Abgebende Partei = Ganztagspartei dieses Tages (Vormittag); Fallback: Gegenpartei.
            CareParty first = (block != null) ? block.party() : otherParty(second);
            LocalTime switchTime = e.startTime().toLocalTime();
            schedule.put(d, CareDay.switchDay(d, first, second, switchTime));
        }

        return schedule;
    }

    /** Ganztägig, wenn Start und Ende jeweils auf Mitternacht (00:00) liegen. */
    private static boolean isAllDay(Event e) {
        return e.startTime().getHour() == 0 && e.startTime().getMinute() == 0
                && e.endTime().getHour() == 0 && e.endTime().getMinute() == 0;
    }

    private CareParty partyOf(String summary) {
        return summaryContainsIgnoreCase(summary, maleMatch) ? CareParty.MANN : CareParty.FRAU;
    }

    private static CareParty otherParty(CareParty p) {
        return p == CareParty.MANN ? CareParty.FRAU : CareParty.MANN;
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
