package de.goForFun.familienkalender;

import static org.assertj.core.api.Assertions.assertThat;

import de.goForFun.familienkalender.model.CareDay;
import de.goForFun.familienkalender.model.CareParty;
import de.goForFun.familienkalender.model.Event;
import de.goForFun.familienkalender.model.EventSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property-Tests (jqwik) für {@link CareScheduleProvider} im Wechselmodell-Kalender.
 *
 * <p>Deckt die Correctness Properties 2–7 aus dem Design ab. Alle Generatoren
 * ({@link Provide}) sind in dieser Klasse gebündelt. Datumsbereiche und
 * Spannenlängen sind bewusst beschränkt, damit die Tests schnell laufen.
 */
class CareSchedulePropertyTest {

    /** Basisdatum für alle generierten Spannen. */
    private static final LocalDate BASE = LocalDate.of(2024, 1, 1);

    /** Mann-Kennung, die in Property 2/3/4/6 nicht in den Summaries vorkommt (⇒ alle Parteien steuerbar). */
    private static final String MALE_MATCH = "MANN";

    // ------------------------------------------------------------------
    // Property 2
    // ------------------------------------------------------------------

    // Feature: wechselmodell-kalender, Property 2: Halboffene Bereichsabdeckung
    // Validates: Requirements 3.3, 3.4
    @Property(tries = 100)
    void halfOpenRangeCoverage(@ForAll("singleSpan") int[] span) {
        LocalDate start = BASE.plusDays(span[0]);
        int length = span[1]; // >= 1
        LocalDate endExclusive = start.plusDays(length);

        Event e = custodyEvent(start, endExclusive, "Betreuung FRAU");
        Map<LocalDate, CareDay> schedule =
                new CareScheduleProvider(List.of(e), MALE_MATCH).buildSchedule();

        // Jeder Tag DTSTART <= d < DTEND ist abgedeckt.
        for (LocalDate d = start; d.isBefore(endExclusive); d = d.plusDays(1)) {
            assertThat(schedule).containsKey(d);
        }
        // DTEND selbst ist NICHT abgedeckt.
        assertThat(schedule).doesNotContainKey(endExclusive);
        // Der Tag unmittelbar vor DTSTART ist nicht abgedeckt.
        assertThat(schedule).doesNotContainKey(start.minusDays(1));
        // Genau 'length' abgedeckte Tage.
        assertThat(schedule).hasSize(length);
        // Länge 1 ⇒ genau DTSTART abgedeckt.
        if (length == 1) {
            assertThat(schedule).containsOnlyKeys(start);
        }
    }

    // ------------------------------------------------------------------
    // Property 3
    // ------------------------------------------------------------------

    // Feature: wechselmodell-kalender, Property 3: Overlap-Determinismus über den Tie-Key
    // Validates: Requirements 3.5
    @Property(tries = 100)
    void overlapDeterminismViaTieKey(@ForAll("overlappingEvents") List<Event> events,
                                     @ForAll long seed) {
        // Referenz-Schedule aus der ursprünglichen Reihenfolge.
        Map<LocalDate, CareDay> reference =
                new CareScheduleProvider(events, MALE_MATCH).buildSchedule();

        // Mehrere Permutationen erzeugen identische Maps.
        java.util.Random rnd = new java.util.Random(seed);
        for (int i = 0; i < 4; i++) {
            List<Event> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, rnd);
            Map<LocalDate, CareDay> other =
                    new CareScheduleProvider(shuffled, MALE_MATCH).buildSchedule();
            assertThat(other).isEqualTo(reference);
        }

        // Unabhängig berechnete Erwartung: pro Tag gewinnt der größte Tie-Key.
        for (Map.Entry<LocalDate, CareDay> entry : reference.entrySet()) {
            LocalDate day = entry.getKey();
            Event winner = expectedWinner(events, day);
            assertThat(winner).as("day %s must be covered by some event", day).isNotNull();
            CareParty expectedParty = partyOf(winner);
            // firstHalf stammt immer vom maßgeblichen Block dieses Tages
            // (bei Ganztag == fullDay == firstHalf; bei Wechseltag == firstHalf).
            assertThat(entry.getValue().firstHalf())
                    .as("winning party for day %s", day)
                    .isEqualTo(expectedParty);
        }
    }

    // ------------------------------------------------------------------
    // Property 4
    // ------------------------------------------------------------------

    // Feature: wechselmodell-kalender, Property 4: Wiederholdeterminismus
    // Validates: Requirements 3.7
    @Property(tries = 100)
    void repeatedRunDeterminism(@ForAll("mixedEvents") List<Event> events,
                                @ForAll("maleMatch") String maleMatch) {
        CareScheduleProvider provider = new CareScheduleProvider(events, maleMatch);
        Map<LocalDate, CareDay> first = provider.buildSchedule();
        Map<LocalDate, CareDay> second = provider.buildSchedule();

        assertThat(second).isEqualTo(first);
        for (LocalDate day : first.keySet()) {
            assertThat(second.get(day)).isEqualTo(first.get(day));
        }

        // Auch eine frische Provider-Instanz mit gleicher Eingabe liefert dasselbe.
        Map<LocalDate, CareDay> fresh =
                new CareScheduleProvider(events, maleMatch).buildSchedule();
        assertThat(fresh).isEqualTo(first);
    }

    // ------------------------------------------------------------------
    // Property 5
    // ------------------------------------------------------------------

    // Feature: wechselmodell-kalender, Property 5: Parteiklassifikation per Teilstring (case-insensitive)
    // Validates: Requirements 4.1, 4.2
    @Property(tries = 100)
    void partyClassificationBySubstringCaseInsensitive(@ForAll("classificationCase") String[] data) {
        String maleMatch = data[0];
        String summary = data[1];
        boolean expectMann = data[2].equals("MANN");

        Event e = custodyEvent(BASE, BASE.plusDays(1), summary);
        Map<LocalDate, CareDay> schedule =
                new CareScheduleProvider(List.of(e), maleMatch).buildSchedule();

        CareParty actual = schedule.get(BASE).fullDay();
        if (expectMann) {
            assertThat(actual).as("summary '%s' contains '%s'", summary, maleMatch)
                    .isEqualTo(CareParty.MANN);
        } else {
            assertThat(actual).as("summary '%s' does not contain '%s'", summary, maleMatch)
                    .isEqualTo(CareParty.FRAU);
        }
    }

    // ------------------------------------------------------------------
    // Property 6
    // ------------------------------------------------------------------

    // Feature: wechselmodell-kalender, Property 6: Wechseltag entsteht NICHT aus Blockgrenzen
    // (nur ganztägige Blöcke als Eingabe ⇒ jeder abgedeckte Tag ist Ganztag mit Blockpartei;
    //  keine Wechseltage).
    // Validates: Requirements 4.4, 4.5
    @Property(tries = 100)
    void blockOnlyInputYieldsOnlyFullDays(@ForAll("blockSequence") List<Block> blocks) {
        List<Event> events = new ArrayList<>();
        for (Block b : blocks) {
            events.add(custodyEvent(b.start, b.start.plusDays(b.length),
                    b.party == CareParty.MANN ? "Papa MANN" : "Mama"));
        }

        Map<LocalDate, CareDay> schedule =
                new CareScheduleProvider(events, MALE_MATCH).buildSchedule();

        // Non-overlapping blocks ⇒ jeder abgedeckte Tag ist genau der ihn deckende Block.
        // Da KEINE getimten Wechsel-Events vorliegen, gibt es keine Wechseltage:
        // jeder Tag ist ein Ganztag mit der Partei seines Blocks.
        for (Block b : blocks) {
            LocalDate last = b.start.plusDays(b.length - 1);
            for (LocalDate d = b.start; !d.isAfter(last); d = d.plusDays(1)) {
                CareDay cd = schedule.get(d);
                assertThat(cd).as("day %s must be covered", d).isNotNull();

                assertThat(cd.switchDay())
                        .as("day %s: block-only input must never produce a switch day", d)
                        .isFalse();
                assertThat(cd.fullDay())
                        .as("day %s: full-day party must equal its block party", d)
                        .isEqualTo(b.party);
                assertThat(cd.firstHalf()).isEqualTo(b.party);
                assertThat(cd.secondHalf()).isEqualTo(b.party);
                assertThat(cd.switchTime())
                        .as("day %s: full day has no switch time", d)
                        .isNull();
            }
        }

        // Es existiert überhaupt kein Wechseltag.
        assertThat(schedule.values())
                .as("block-only input must not contain any switch day")
                .allMatch(cd -> !cd.switchDay());
    }

    // Feature: wechselmodell-kalender, Property 6: Wechseltag entsteht durch getimtes Wechsel-Event
    // (secondHalf=uebernehmende Partei, firstHalf=Blockpartei des Tages bzw. Gegenpartei, switchTime=Event-Uhrzeit);
    // Tage ohne Wechsel-Event bleiben Ganztage.
    // Validates: Requirements 4.3, 4.4, 4.5
    @Property(tries = 100)
    void timedSwitchEventCreatesSwitchDay(@ForAll("switchScenario") SwitchScenario scenario) {
        List<Event> events = new ArrayList<>();

        // 1) Ganztägige Blöcke.
        for (Block b : scenario.blocks) {
            events.add(custodyEvent(b.start, b.start.plusDays(b.length),
                    b.party == CareParty.MANN ? "Papa MANN" : "Mama"));
        }

        // 2) Getimte Wechsel-Events: pro Eintrag ein "Wechsel zu …" am gegebenen Tag mit Uhrzeit.
        for (SwitchEvent se : scenario.switches) {
            String summary = "Wechsel zu " + (se.party == CareParty.MANN ? "Papa MANN" : "Mama");
            events.add(timedSwitchEvent(se.day, se.time, summary));
        }

        Map<LocalDate, CareDay> schedule =
                new CareScheduleProvider(events, MALE_MATCH).buildSchedule();

        // Erwartung je Tag: der späteste Wechsel pro Tag gewinnt.
        Map<LocalDate, SwitchEvent> latestSwitch = new java.util.HashMap<>();
        for (SwitchEvent se : scenario.switches) {
            SwitchEvent cur = latestSwitch.get(se.day);
            if (cur == null || se.time.isAfter(cur.time)) {
                latestSwitch.put(se.day, se);
            }
        }

        // 2a) Jeder Tag mit (spätestem) Wechsel-Event ist ein Wechseltag mit den erwarteten Hälften.
        for (Map.Entry<LocalDate, SwitchEvent> entry : latestSwitch.entrySet()) {
            LocalDate day = entry.getKey();
            SwitchEvent se = entry.getValue();
            CareDay cd = schedule.get(day);
            assertThat(cd).as("switch day %s must be in schedule", day).isNotNull();

            assertThat(cd.switchDay()).as("day %s must be a switch day", day).isTrue();
            assertThat(cd.secondHalf())
                    .as("day %s: secondHalf = übernehmende Partei des Wechsel-Events", day)
                    .isEqualTo(se.party);
            assertThat(cd.switchTime())
                    .as("day %s: switchTime = Uhrzeit des Wechsel-Events", day)
                    .isEqualTo(se.time);

            Block cover = blockCovering(scenario.blocks, day);
            CareParty expectedFirst = (cover != null) ? cover.party : otherParty(se.party);
            assertThat(cd.firstHalf())
                    .as("day %s: firstHalf = Blockpartei (bzw. Gegenpartei der übernehmenden)", day)
                    .isEqualTo(expectedFirst);
            assertThat(cd.fullDay())
                    .as("day %s: Wechseltag hat keine Ganztagspartei", day)
                    .isNull();
        }

        // 2b) Abgedeckte Tage OHNE Wechsel-Event bleiben Ganztage mit der Blockpartei.
        for (Block b : scenario.blocks) {
            LocalDate last = b.start.plusDays(b.length - 1);
            for (LocalDate d = b.start; !d.isAfter(last); d = d.plusDays(1)) {
                if (latestSwitch.containsKey(d)) {
                    continue; // als Wechseltag oben geprüft
                }
                CareDay cd = schedule.get(d);
                assertThat(cd).as("covered day %s must be in schedule", d).isNotNull();
                assertThat(cd.switchDay())
                        .as("day %s without Wechsel-Event must stay a full day", d)
                        .isFalse();
                // Maßgeblicher Block (größter Tie-Key) – bei nicht-überlappenden Blöcken = b.
                assertThat(cd.fullDay())
                        .as("day %s full-day party", d)
                        .isEqualTo(b.party);
            }
        }
    }

    private static CareParty otherParty(CareParty p) {
        return p == CareParty.MANN ? CareParty.FRAU : CareParty.MANN;
    }

    // ------------------------------------------------------------------
    // Property 7
    // ------------------------------------------------------------------

    // Feature: wechselmodell-kalender, Property 7: Keine Partei an nicht abgedeckten Tagen
    // Validates: Requirements 4.6
    @Property(tries = 100)
    void noPartyOnUncoveredDays(@ForAll("mixedEvents") List<Event> events) {
        Map<LocalDate, CareDay> schedule =
                new CareScheduleProvider(events, MALE_MATCH).buildSchedule();

        // Prüfe einen breiten Bereich rund um BASE: jeder Tag, der von keinem
        // Event (halboffen) abgedeckt wird, hat keinen Schedule-Eintrag.
        LocalDate from = BASE.minusDays(5);
        LocalDate to = BASE.plusDays(400);
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) {
            boolean covered = isCovered(events, d);
            if (!covered) {
                assertThat(schedule).doesNotContainKey(d);
                assertThat(schedule.get(d)).isNull();
            }
        }
        // Umkehrung: jeder Schedule-Eintrag ist tatsächlich abgedeckt.
        for (LocalDate d : schedule.keySet()) {
            assertThat(isCovered(events, d)).as("schedule day %s must be covered", d).isTrue();
        }
    }

    // ==================================================================
    // Generators
    // ==================================================================

    /** Einzelne Spanne: [offset, length] mit offset in [0,300], length in [1,30]. */
    @Provide
    Arbitrary<int[]> singleSpan() {
        Arbitrary<Integer> offset = Arbitraries.integers().between(0, 300);
        Arbitrary<Integer> length = Arbitraries.integers().between(1, 30);
        return Combinators.combine(offset, length).as((o, l) -> new int[]{o, l});
    }

    /** Menge sich überlappender Events: alle teilen mindestens einen gemeinsamen Tag. */
    @Provide
    Arbitrary<List<Event>> overlappingEvents() {
        // Alle Events enthalten den Anker-Tag BASE+100 (⇒ garantierte Überlappung).
        LocalDate anchor = BASE.plusDays(100);
        Arbitrary<Event> overlapping = Combinators.combine(
                        Arbitraries.integers().between(0, 20),   // Tage vor dem Anker
                        Arbitraries.integers().between(1, 20),   // Tage ab dem Anker (>=1 ⇒ deckt Anker)
                        Arbitraries.of("a", "b", "c", "x", "y", "z", "FRAU", "MANN"))
                .as((before, after, sum) -> {
                    LocalDate start = anchor.minusDays(before);
                    LocalDate endExcl = anchor.plusDays(after);
                    return custodyEvent(start, endExcl, sum);
                });
        return overlapping.list().ofMinSize(2).ofMaxSize(6);
    }

    /** Gemischte, beliebig platzierte Events (können überlappen, benachbart oder isoliert sein). */
    @Provide
    Arbitrary<List<Event>> mixedEvents() {
        Arbitrary<Event> ev = Combinators.combine(
                        Arbitraries.integers().between(0, 300),  // Start-Offset ab BASE
                        Arbitraries.integers().between(1, 30),   // Länge
                        Arbitraries.of("Papa", "Mama", "Papa MANN", "frau", "MANN im Titel", "x"))
                .as((off, len, sum) -> {
                    LocalDate start = BASE.plusDays(off);
                    return custodyEvent(start, start.plusDays(len), sum);
                });
        return ev.list().ofMinSize(0).ofMaxSize(8);
    }

    /** maleMatch-Kennung (nicht leer) für den Wiederholdeterminismus. */
    @Provide
    Arbitrary<String> maleMatch() {
        return Arbitraries.of("MANN", "papa", "Pa", "X", "mann");
    }

    /**
     * Klassifikations-Fall: [maleMatch, summary, expected("MANN"/"FRAU")].
     * Entweder bettet das Summary die Kennung (gemischt gecased, ggf. mit Umgebung) ein
     * oder es enthält sie definitiv nicht.
     */
    @Provide
    Arbitrary<String[]> classificationCase() {
        // Kennung aus einem festen Alphabet, damit "definitiv nicht enthalten" sicher konstruierbar ist.
        Arbitrary<String> keys = Arbitraries.strings()
                .withChars("abcDEF")
                .ofMinLength(1).ofMaxLength(6);
        Arbitrary<String> prefix = Arbitraries.strings().withChars("0123456789 -").ofMaxLength(5);
        Arbitrary<String> suffix = Arbitraries.strings().withChars("0123456789 -").ofMaxLength(5);
        Arbitrary<Boolean> embed = Arbitraries.of(true, false);
        Arbitrary<Long> casingSeed = Arbitraries.longs();

        return Combinators.combine(keys, prefix, suffix, embed, casingSeed)
                .as((key, pre, suf, doEmbed, seed) -> {
                    if (doEmbed) {
                        String mixed = randomMixedCase(key, seed);
                        String summary = pre + mixed + suf;
                        return new String[]{key, summary, "MANN"};
                    } else {
                        // Summary ausschließlich aus Ziffern/Leer/Bindestrich ⇒ enthält den
                        // Buchstaben-Key (case-insensitive) definitiv nicht.
                        String summary = pre + suf + "123";
                        return new String[]{key, summary, "FRAU"};
                    }
                });
    }

    /**
     * Folge nicht-überlappender Blöcke: Startdatum + Liste von Spannenlängen (>=1)
     * mit zufälligen Lücken und zufälligen Parteien.
     */
    @Provide
    Arbitrary<List<Block>> blockSequence() {
        Arbitrary<Integer> startOffset = Arbitraries.integers().between(0, 50);
        Arbitrary<List<Integer>> lengths =
                Arbitraries.integers().between(1, 30).list().ofMinSize(1).ofMaxSize(6);
        Arbitrary<List<Integer>> gaps =
                Arbitraries.integers().between(0, 5).list().ofMinSize(1).ofMaxSize(6);
        Arbitrary<List<CareParty>> parties =
                Arbitraries.of(CareParty.class).list().ofMinSize(1).ofMaxSize(6);

        return Combinators.combine(startOffset, lengths, gaps, parties)
                .as((startOff, lens, gapList, partyList) -> {
                    List<Block> blocks = new ArrayList<>();
                    LocalDate cursor = BASE.plusDays(startOff);
                    int n = Math.min(lens.size(), Math.min(gapList.size(), partyList.size()));
                    for (int i = 0; i < n; i++) {
                        int len = lens.get(i);
                        CareParty party = partyList.get(i);
                        blocks.add(new Block(cursor, len, party));
                        // nächster Block beginnt nach der Länge + optionaler Lücke ⇒ keine Überlappung
                        cursor = cursor.plusDays(len + gapList.get(i));
                    }
                    return blocks;
                });
    }

    /**
     * Szenario für die getimte Wechsel-Semantik: eine Folge nicht-überlappender Blöcke
     * plus eine Menge getimter Wechsel-Events, die (überwiegend) auf abgedeckte Tage fallen.
     */
    @Provide
    Arbitrary<SwitchScenario> switchScenario() {
        return blockSequence().flatMap(blocks -> {
            if (blocks.isEmpty()) {
                return Arbitraries.just(new SwitchScenario(blocks, List.of()));
            }
            // Zulässige Wechseltage: alle von einem Block abgedeckten Tage (als Offset ab BASE).
            List<Integer> coveredOffsets = new ArrayList<>();
            for (Block b : blocks) {
                for (int i = 0; i < b.length; i++) {
                    coveredOffsets.add((int) (b.start.plusDays(i).toEpochDay() - BASE.toEpochDay()));
                }
            }
            Arbitrary<Integer> dayOffset = Arbitraries.of(coveredOffsets);
            Arbitrary<LocalTime> times = Combinators.combine(
                            Arbitraries.integers().between(0, 23),
                            Arbitraries.of(0, 15, 30, 45))
                    .as(LocalTime::of);
            Arbitrary<CareParty> parties = Arbitraries.of(CareParty.class);

            Arbitrary<SwitchEvent> switchEvent = Combinators.combine(dayOffset, times, parties)
                    .as((off, t, p) -> new SwitchEvent(BASE.plusDays(off), t, p));

            return switchEvent.list().ofMinSize(1).ofMaxSize(5)
                    .map(switches -> new SwitchScenario(blocks, switches));
        });
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    /** Ein getimtes Wechsel-Event an einem Tag mit übernehmender Partei. */
    static final class SwitchEvent {
        final LocalDate day;
        final LocalTime time;
        final CareParty party; // übernehmende Partei (secondHalf)

        SwitchEvent(LocalDate day, LocalTime time, CareParty party) {
            this.day = day;
            this.time = time;
            this.party = party;
        }

        @Override
        public String toString() {
            return "Switch[" + day + " " + time + " -> " + party + "]";
        }
    }

    /** Blöcke + getimte Wechsel-Events für die Wechseltag-Property. */
    static final class SwitchScenario {
        final List<Block> blocks;
        final List<SwitchEvent> switches;

        SwitchScenario(List<Block> blocks, List<SwitchEvent> switches) {
            this.blocks = blocks;
            this.switches = switches;
        }

        @Override
        public String toString() {
            return "SwitchScenario{blocks=" + blocks + ", switches=" + switches + "}";
        }
    }

    /** Nicht-überlappender Betreuungsblock für Property 6. */
    static final class Block {
        final LocalDate start;
        final int length; // >= 1
        final CareParty party;

        Block(LocalDate start, int length, CareParty party) {
            this.start = start;
            this.length = length;
            this.party = party;
        }

        @Override
        public String toString() {
            return "Block[" + start + ", +" + length + ", " + party + "]";
        }
    }

    private static Event custodyEvent(LocalDate startDate, LocalDate endExclusive, String summary) {
        return new Event(
                List.of(),
                startDate.atStartOfDay(),
                endExclusive.atStartOfDay(),
                summary,
                null,
                EventSource.CUSTODY,
                null);
    }

    /** Getimtes Wechsel-Event: Start = day+time, Ende = +15 min (⇒ nicht ganztägig). */
    private static Event timedSwitchEvent(LocalDate day, LocalTime time, String summary) {
        return new Event(
                List.of(),
                day.atTime(time),
                day.atTime(time).plusMinutes(15),
                summary,
                null,
                EventSource.CUSTODY,
                null);
    }

    /** Spiegelt die Klassifikationsregel des Providers: MANN gdw. summary MALE_MATCH enthält. */
    private static CareParty partyOf(Event e) {
        String s = e.summary();
        if (s == null) {
            return CareParty.FRAU;
        }
        return s.toLowerCase(java.util.Locale.ROOT).contains(MALE_MATCH.toLowerCase(java.util.Locale.ROOT))
                ? CareParty.MANN : CareParty.FRAU;
    }

    /** tieKey wie im Provider: DTSTART + "|" + DTEND + "|" + SUMMARY. */
    private static String tieKey(Event e) {
        LocalDate start = e.startTime().toLocalDate();
        LocalDate end = e.endTime().toLocalDate();
        if (!end.isAfter(start)) {
            end = start.plusDays(1);
        }
        String summary = e.summary() == null ? "" : e.summary();
        return start + "|" + end + "|" + summary;
    }

    /** Erwarteter Gewinner-Event an einem Tag: größter Tie-Key unter den abdeckenden Events. */
    private static Event expectedWinner(List<Event> events, LocalDate day) {
        Event best = null;
        String bestKey = null;
        for (Event e : events) {
            if (covers(e, day)) {
                String key = tieKey(e);
                if (bestKey == null || key.compareTo(bestKey) > 0) {
                    bestKey = key;
                    best = e;
                }
            }
        }
        return best;
    }

    private static boolean covers(Event e, LocalDate day) {
        LocalDate start = e.startTime().toLocalDate();
        LocalDate end = e.endTime().toLocalDate();
        if (!end.isAfter(start)) {
            end = start.plusDays(1);
        }
        return !day.isBefore(start) && day.isBefore(end);
    }

    private static boolean isCovered(List<Event> events, LocalDate day) {
        for (Event e : events) {
            if (covers(e, day)) {
                return true;
            }
        }
        return false;
    }

    private static Block blockCovering(List<Block> blocks, LocalDate day) {
        for (Block b : blocks) {
            LocalDate last = b.start.plusDays(b.length - 1);
            if (!day.isBefore(b.start) && !day.isAfter(last)) {
                return b;
            }
        }
        return null;
    }

    /** Mischt die Groß-/Kleinschreibung eines Strings deterministisch anhand eines Seeds. */
    private static String randomMixedCase(String s, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(rnd.nextBoolean() ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
