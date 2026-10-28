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
 * ({@code EventSource.CUSTODY}) eine deterministische Tag-zu-{@link CareDay}-Zuordnung ableitet.
 *
 * <p>Der wechselplan.de-Feed (siehe README) enthält mehrere Event-Arten, die über das
 * <b>UID-Präfix</b> eindeutig unterschieden werden:
 * <ul>
 *   <li>{@code wp-care-…} und {@code wp-vacation-…}: ganztägige <b>Betreuungsblöcke</b>
 *       ("Lukas bei …" bzw. "Lukas: Urlaub – …"). Jeder Kalendertag im halboffenen Bereich
 *       {@code [DTSTART, DTEND)} gehört zur im {@code SUMMARY} genannten Partei.</li>
 *   <li>{@code wp-handover-…}: getimte <b>Wechsel-Events</b> ("Wechsel zu …") – markieren den
 *       Übergabe-Zeitpunkt; die genannte Partei ist die übernehmende (Zweite_Tageshälfte).</li>
 *   <li>Alle anderen (z. B. {@code wp-schoolvac-…} für "Schulferien: …") sind <b>keine</b>
 *       Betreuungszuordnung und werden bei der Partei-Bestimmung ignoriert.</li>
 * </ul>
 *
 * <p>Fehlt eine UID (fremde Feeds), gilt ein sicherer Fallback: getimte Events sind Wechsel,
 * ganztägige Events sind Betreuungsblöcke – mit Ausnahme offensichtlicher Schulferien-Titel.
 *
 * <p>Die Klasse ist bewusst frei von I/O, damit sie property-testbar ist.
 */
public class CareScheduleProvider {

    private static final String UID_CARE = "wp-care-";
    private static final String UID_VACATION = "wp-vacation-";
    private static final String UID_HANDOVER = "wp-handover-";

    private final List<Event> custodyEvents;
    private final String maleMatch;

    public CareScheduleProvider(List<Event> custodyEvents, String maleMatch) {
        this.custodyEvents = custodyEvents;
        this.maleMatch = maleMatch;
    }

    /** Ganztägiger Betreuungsblock über den halboffenen Datumsbereich {@code [startInclusive, endExclusive)}. */
    record CareBlock(LocalDate startInclusive, LocalDate endExclusive, CareParty party, String tieKey) {
        boolean covers(LocalDate d) {
            return !d.isBefore(startInclusive) && d.isBefore(endExclusive);
        }
    }

    /** Welche Rolle ein Event im Feed spielt. */
    private enum Kind { CARE_BLOCK, HANDOVER, IGNORE }

    /**
     * Liefert die deterministische Tag-zu-{@link CareDay}-Zuordnung für alle abgedeckten Tage.
     * Nicht abgedeckte Tage besitzen keinen Eintrag.
     */
    public Map<LocalDate, CareDay> buildSchedule() {
        List<CareBlock> blocks = new ArrayList<>();
        List<Event> handovers = new ArrayList<>();

        // 1) Events nach UID-Präfix (mit Fallback) klassifizieren.
        for (Event e : custodyEvents) {
            if (e == null || e.startTime() == null || e.endTime() == null) {
                continue;
            }
            switch (classify(e)) {
                case CARE_BLOCK -> {
                    LocalDate startDate = e.startTime().toLocalDate();
                    LocalDate endExcl = e.endTime().toLocalDate();
                    if (!endExcl.isAfter(startDate)) {
                        endExcl = startDate.plusDays(1); // Robustheit: mind. 1 Tag
                    }
                    CareParty party = partyOf(e.summary());
                    String tieKey = startDate + "|" + endExcl + "|" + (e.summary() == null ? "" : e.summary());
                    blocks.add(new CareBlock(startDate, endExcl, party, tieKey));
                }
                case HANDOVER -> handovers.add(e);
                case IGNORE -> { /* z. B. Schulferien: nicht betreuungsrelevant */ }
            }
        }

        // 2) Pro Tag maßgeblichen Betreuungsblock bestimmen (groesster tieKey, reihenfolgeunabhaengig, R3.5).
        Map<LocalDate, CareBlock> dayToBlock = new HashMap<>();
        for (CareBlock b : blocks) {
            for (LocalDate d = b.startInclusive(); d.isBefore(b.endExclusive()); d = d.plusDays(1)) {
                CareBlock cur = dayToBlock.get(d);
                if (cur == null || b.tieKey().compareTo(cur.tieKey()) > 0) {
                    dayToBlock.put(d, b);
                }
            }
        }

        // 3) Basis: jeder abgedeckte Tag ist zunächst ein Ganztag mit der Blockpartei (R4.5).
        Map<LocalDate, CareDay> schedule = new HashMap<>();
        for (Map.Entry<LocalDate, CareBlock> entry : dayToBlock.entrySet()) {
            LocalDate d = entry.getKey();
            schedule.put(d, CareDay.fullDay(d, entry.getValue().party()));
        }

        // 4) Getimte Wechsel-Events anwenden (R3.4/R4.3). Bei mehreren am selben Tag gewinnt das spaeteste.
        Map<LocalDate, Event> switchByDay = new HashMap<>();
        for (Event e : handovers) {
            LocalDate d = e.startTime().toLocalDate();
            Event cur = switchByDay.get(d);
            if (cur == null || e.startTime().isAfter(cur.startTime())) {
                switchByDay.put(d, e);
            }
        }

        for (Map.Entry<LocalDate, Event> entry : switchByDay.entrySet()) {
            LocalDate d = entry.getKey();
            Event e = entry.getValue();
            CareParty second = partyOf(e.summary());                 // uebernehmende Partei ("Wechsel zu …")
            // Abgebende Partei = Ganztagspartei des VORTAGS (die Seite, die bis zur Uebergabe betreut).
            // Bei Mitternachts-Uebergaben deckt der Block des DTSTART-Tages bereits die neue Partei ab,
            // daher den Vortag heranziehen; Fallback: Block dieses Tages bzw. Gegenpartei.
            CareParty first;
            CareBlock prevBlock = dayToBlock.get(d.minusDays(1));
            if (prevBlock != null) {
                first = prevBlock.party();
            } else {
                CareBlock sameBlock = dayToBlock.get(d);
                first = (sameBlock != null && sameBlock.party() != second)
                        ? sameBlock.party()
                        : otherParty(second);
            }
            LocalTime switchTime = e.startTime().toLocalTime();
            schedule.put(d, CareDay.switchDay(d, first, second, switchTime));
        }

        return schedule;
    }

    /** Klassifiziert ein Event primär über das UID-Präfix, mit sicherem Fallback. */
    private Kind classify(Event e) {
        String uid = e.uid();
        if (uid != null && !uid.isBlank()) {
            String u = uid.toLowerCase(Locale.ROOT);
            if (u.startsWith(UID_CARE) || u.startsWith(UID_VACATION)) {
                return Kind.CARE_BLOCK;
            }
            if (u.startsWith(UID_HANDOVER)) {
                return Kind.HANDOVER;
            }
            // andere wp-* (z. B. wp-schoolvac-) sowie unbekannte UID-Typen: ignorieren
            if (u.startsWith("wp-")) {
                return Kind.IGNORE;
            }
        }
        // Fallback ohne (bekannte) UID: getimt => Wechsel, ganztägig => Betreuungsblock,
        // offensichtliche Schulferien-Titel ausschließen.
        String summary = e.summary() == null ? "" : e.summary().toLowerCase(Locale.ROOT);
        if (summary.startsWith("schulferien")) {
            return Kind.IGNORE;
        }
        return isAllDay(e) ? Kind.CARE_BLOCK : Kind.HANDOVER;
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

    private static boolean summaryContainsIgnoreCase(String summary, String maleMatch) {
        if (summary == null || summary.isEmpty() || maleMatch == null || maleMatch.isEmpty()) {
            return false;
        }
        return summary.toLowerCase(Locale.ROOT).contains(maleMatch.toLowerCase(Locale.ROOT));
    }
}
