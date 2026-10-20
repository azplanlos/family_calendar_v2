# Implementation Plan: Wechselmodell-Kalender

## Overview

Dieser Plan setzt das Design in inkrementelle, test-getriebene Java-Schritte um (Build via Maven Wrapper `./mvnw`). Reihenfolge: zuerst Datenmodell und reine Logik (`CareScheduleProvider`) samt Property-Tests, dann die Integration in `CreateKalenderImage`, danach die Rendering-Erweiterungen (Icon + Monatskalender-Rasterung) mit ihren Property-/Unit-Tests, zuletzt Demo-Daten und die Deployment-Konfiguration. Jeder Schritt baut auf den vorherigen auf und wird am Ende verdrahtet, sodass kein isolierter, nicht integrierter Code entsteht.

Die Correctness Properties aus dem Design (Property 1–13) werden mit **jqwik** als Property-Based Tests umgesetzt. Jeder `@Property` ist mit `min 100 tries` konfiguriert und mit `Feature: wechselmodell-kalender, Property N: ...` annotiert. Test-Unterschritte sind mit `*` als optional markiert.

## Tasks

- [x] 1. jqwik-Testabhängigkeit und Datenmodell-Grundlagen anlegen
  - [x] 1.1 jqwik als Test-Dependency in `pom.xml` ergänzen
    - jqwik (JUnit-5-Plattform) im `test`-Scope zu `pom.xml` hinzufügen, keine Eigenimplementierung
    - Sicherstellen, dass Surefire die jqwik-`@Property`-Tests ausführt (JUnit Platform)
    - Build verifizieren: `./mvnw -q test-compile`
    - _Requirements: 3.5, 3.7, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 6.2_

  - [x] 1.2 Enum `CareParty` erstellen
    - Datei `src/main/java/de/goForFun/familienkalender/model/CareParty.java` mit Werten `MANN`, `FRAU`
    - _Requirements: 4.1, 4.2_

  - [x] 1.3 Record `CareDay` erstellen
    - Datei `src/main/java/de/goForFun/familienkalender/model/CareDay.java` als Record `(LocalDate date, CareParty fullDay, CareParty firstHalf, CareParty secondHalf, boolean switchDay)`
    - Factory-Methoden `fullDay(date, party)` und `switchDay(date, first, second)` gemäß Design
    - Methode `iconParty()`: liefert `secondHalf` bei Wechseltag, sonst `fullDay`
    - _Requirements: 4.3, 4.4, 4.5, 5.3, 5.4_

  - [x] 1.4 Property-Test für `CareDay.iconParty()` schreiben
    - **Property 8: Icon-Partei-Auswahl** — `iconParty()` ist `secondHalf` bei Wechseltag, sonst `fullDay`
    - jqwik `@Property` mit min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 8: ...`
    - **Validates: Requirements 5.3, 5.4**

  - [x] 1.5 `EventSource.CUSTODY` ergänzen
    - Enum-Wert `CUSTODY` zu `src/main/java/de/goForFun/familienkalender/model/EventSource.java` hinzufügen (mit Javadoc)
    - _Requirements: 3.1, 3.2_

- [x] 2. Reine Betreuungslogik: CareScheduleProvider
  - [x] 2.1 `CareScheduleProvider` mit Block-Erkennung und Wechseltag-Ableitung implementieren
    - Datei `src/main/java/de/goForFun/familienkalender/CareScheduleProvider.java`, Konstruktor `(List<Event> custodyEvents, String maleMatch)`, Methode `buildSchedule()`
    - Interne `CareBlock`-Zwischenstruktur (`startInclusive`, `endExclusive`, `party`, `tieKey`) mit `lastDay()`/`covers()`
    - Events → Blöcke über halboffenes `[DTSTART, DTEND)`; Robustheit: mind. 1 Tag; Länge-1-Randfall abdecken
    - Parteiklassifikation `summaryContainsIgnoreCase(summary, maleMatch)` (leeres summary ⇒ FRAU)
    - Tie-Key `DTSTART + "|" + DTEND + "|" + SUMMARY`; pro Tag gewinnt lexikografisch größter Tie-Key (reihenfolgeunabhängig)
    - Wechseltag nur, wenn am Folgetag ein anderer maßgeblicher Block beginnt; sonst Ganztag
    - Nicht abgedeckte Tage: kein Eintrag in der Map
    - _Requirements: 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

  - [x] 2.2 Property-Test: halboffene Bereichsabdeckung
    - **Property 2: Halboffene Bereichsabdeckung** — jeder Tag `DTSTART ≤ d < DTEND` abgedeckt, `DTEND` nicht; Länge 1 ⇒ genau `DTSTART`
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 2: ...`
    - **Validates: Requirements 3.3, 3.4**

  - [x] 2.3 Property-Test: Overlap-Determinismus über den Tie-Key
    - **Property 3: Overlap-Determinismus über den Tie-Key** — für jede Permutation der Einlesereihenfolge identische Map; überlappter Tag dem größten Tie-Key zugeordnet
    - Generator für sich überlappende Events + Permutationen
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 3: ...`
    - **Validates: Requirements 3.5**

  - [x] 2.4 Property-Test: Wiederholdeterminismus
    - **Property 4: Wiederholdeterminismus** — zweimaliges `buildSchedule` liefert pro abgedecktem Tag dasselbe Ergebnis
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 4: ...`
    - **Validates: Requirements 3.7**

  - [x] 2.5 Property-Test: Parteiklassifikation (case-insensitive Teilstring)
    - **Property 5: Parteiklassifikation per Teilstring (case-insensitive)** — `MANN` gdw. Kennung als Teilstring (casing-unabhängig) enthalten, sonst `FRAU`
    - Generatoren: Kennung gemischt gecased in/neben dem Summary
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 5: ...`
    - **Validates: Requirements 4.1, 4.2**

  - [x] 2.6 Property-Test: Block- und Wechseltag-Invariante
    - **Property 6: Block- und Wechseltag-Invariante** — innerer Tag ⇒ Ganztag mit Blockpartei; letzter Tag mit Folgeblock am Folgetag ⇒ Wechseltag (firstHalf=endend, secondHalf=Folge); sonst Ganztag
    - Generator: Folgen aufeinanderfolgender/lückenbehafteter Spannen
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 6: ...`
    - **Validates: Requirements 4.3, 4.4, 4.5**

  - [x] 2.7 Property-Test: keine Partei an nicht abgedeckten Tagen
    - **Property 7: Keine Partei an nicht abgedeckten Tagen** — kein Schedule-Eintrag für nicht abgedeckte Tage
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 7: ...`
    - **Validates: Requirements 4.6**

- [x] 3. Checkpoint - Logik und Property-Tests prüfen
  - Ensure all tests pass, ask the user if questions arise.
  - `./mvnw -q test` ausführen und sicherstellen, dass Datenmodell + CareScheduleProvider samt Properties grün sind

- [x] 4. RenderData um custodySchedule erweitern
  - [x] 4.1 Feld `custodySchedule` und abwärtskompatible Konstruktoren ergänzen
    - `src/main/java/de/goForFun/familienkalender/model/RenderData.java`: neues Feld `Map<LocalDate, CareDay> custodySchedule` am Ende
    - Bestehende Konstruktoren (mit/ohne errors) als Overloads mit `custodySchedule = Map.of()` erhalten
    - Javadoc für das neue Feld ergänzen
    - _Requirements: 3.2, 5.9, 6.9, 7.3_

  - [x] 4.2 Unit-Test für Konstruktor-Abwärtskompatibilität
    - Bestehende Aufrufe ohne `custodySchedule` liefern leere Map; neuer Konstruktor übernimmt die Map
    - _Requirements: 3.2, 7.3_

- [x] 5. CreateKalenderImage-Integration (Aktivierung, Parsing, Schedule)
  - [x] 5.1 URL-Validierung `parseHttpUri` implementieren
    - Private statische Methode in `CreateKalenderImage`: null/leer/ungültig/kein http(s) ⇒ `null`; gültige http/https mit Host ⇒ `URI`; wirft nicht
    - _Requirements: 1.2, 1.3_

  - [x] 5.2 Property-Test: URL-Validierung steuert Aktivierung ohne Fehler
    - **Property 1: URL-Validierung steuert Aktivierung ohne Fehler** — `parseHttpUri` liefert URI gdw. gültige http/https-URL mit Host; sonst `null`, und kein Custody-Konfigurationsfehler
    - Generatoren: gültige http/https-URLs, leer/null, `ftp:`/`mailto:`, relative Pfade, Zufallstext
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 1: ...`
    - **Validates: Requirements 1.2, 1.3**

  - [x] 5.3 Custody-ENV-Reads und Verarbeitungsblock verdrahten
    - Statische ENV-Reads `CUSTODY_CALENDAR_FEED`, `CUSTODY_MALE_MATCH` ergänzen
    - Ablauf vor `new RenderData(...)`: `parseHttpUri` → bei aktivem Feature `IcalParser().parse(uri, calendarStart, calendarEnd, EventSource.CUSTODY)` → `CareScheduleProvider.buildSchedule()` → `custodySchedule` in RenderData
    - Custody-Events NICHT in `EventRepository`/`todayEvents`/`tomorrowEvents`/`calendarEvents` einspeisen
    - Fehlerbehandlung: fehlende/leere `CUSTODY_MALE_MATCH` ⇒ errors-Eintrag mit Variablennamen + Schedule leer (R2.2); Feed-/Parse-Fehler via try/catch ⇒ errors-Eintrag `Wechselmodell-Feed: ...` + Schedule leer (R3.6); ungültige URL ⇒ kein Fehler (R1.3)
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 2.1, 2.2, 3.1, 3.2, 3.6_

  - [x] 5.4 Unit-Tests für Aktivierungs- und Fehlerpfade
    - `CUSTODY_MALE_MATCH` null/leer/Whitespace ⇒ `errors` enthält `CUSTODY_MALE_MATCH`, Schedule leer, Rendern läuft weiter (R2.2)
    - Unerreichbare URL / Garbage-iCal ⇒ `errors` enthält `Wechselmodell-Feed`, Schedule leer (R3.6)
    - Fehlende/ungültige Feed-URL ⇒ Schedule leer, kein Custody-Fehler (R1.3/R1.4)
    - _Requirements: 1.3, 1.4, 2.2, 3.6_

- [x] 6. Checkpoint - Integration prüfen
  - Ensure all tests pass, ask the user if questions arise.
  - `./mvnw -q test` ausführen und Integration in `CreateKalenderImage` validieren

- [x] 7. ImageRenderer: Betreuungs-Icon in der Tagesansicht
  - [x] 7.1 Schedule durchreichen und `drawCareIcon` implementieren
    - `createImage` → `drawDayColumns` → `drawDayColumn` um den Schedule erweitern; pro Spalte maßgebliche Partei über `CareDay.iconParty()` (kann `null` sein)
    - `drawCareIcon(g, party, bx, by, size)`: Mars-Glyph (MANN, COLOR_RED) / Venus-Glyph (FRAU, schwarz), vektoriell, nur Schwarz/COLOR_RED/transparent, kein Antialiasing-Grau
    - Icon links neben der Überschrift (reservierte Zone), Überschrift um Iconbreite einrücken; bei `null` Überschrift wie bisher über volle Breite
    - Icon links neben Heute- und Morgen-Überschrift zeichnen; Partei am jeweiligen Tag
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8, 5.9_

  - [x] 7.2 Property-Test: Icon-Farbpalette
    - **Property 9: Icon-Farbpalette** — Icon-Fläche enthält nur transparent/schwarz/COLOR_RED, keine Graustufen
    - In `TYPE_BYTE_BINARY`-Puffer mit demselben 3-Farben-`IndexColorModel` zeichnen und Raster prüfen
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 9: ...`
    - **Validates: Requirements 5.7**

  - [x] 7.3 Property-Test: Icon-Layout ist disjunkt
    - **Property 10: Icon-Layout ist disjunkt** — Icon-Bounding-Box disjunkt zu Überschrift- und Event-Bereich derselben Spalte
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 10: ...`
    - **Validates: Requirements 5.8**

  - [x] 7.4 Unit-Tests für Party→Icon-Mapping und Platzierung
    - MANN⇒Mann-Icon, FRAU⇒Frau-Icon; Icon links an Heute/Morgen; leerer Schedule ⇒ kein Icon, Darstellung unverändert
    - _Requirements: 5.1, 5.2, 5.5, 5.6, 5.9_

- [x] 8. ImageRenderer: Monatskalender-Hervorhebung
  - [x] 8.1 `fillCustodyDither` (~33 %) implementieren
    - Private Methode in `ImageRenderer`: Diagonalraster `(px + 2*py) % 3 == 0` in COLOR_RED, Dichte sicher in [20 %, 40 %]
    - _Requirements: 6.2_

  - [x] 8.2 Property-Test: Dithering-Dichte in [20 %, 40 %]
    - **Property 12: Dithering-Dichte in [20 %, 40 %]** — für `w,h ∈ [1,120]` liegt der gesetzte Pixelanteil im geschlossenen Intervall [0,20, 0,40]
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 12: ...`
    - **Validates: Requirements 6.2**

  - [x] 8.3 `drawCustodyHighlight` mit reservierten Zonen implementieren und verdrahten
    - `drawMonthCalendar` erhält Schedule; Rasterung vor Tageszahl/Rahmen zeichnen
    - Reservierte Zonen aussparen: oben `participants.size()*2 px`, unten `2 px`; Heute-Zelle ⇒ keine zusätzliche Mann-Rasterung (Heute dominiert)
    - Ganztag MANN ⇒ voller innerer Bereich; Ganztag FRAU / kein CareDay ⇒ keine Rasterung; Wechseltag ⇒ obere Hälfte gdw. firstHalf==MANN, untere Hälfte gdw. secondHalf==MANN (disjunkt)
    - _Requirements: 6.1, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9_

  - [x] 8.4 Property-Test: Highlight-Region-Korrektheit
    - **Property 11: Highlight-Region-Korrektheit** — Ganztag MANN ⇒ voller Bereich; FRAU/kein CareDay ⇒ leer; Wechseltag ⇒ obere/untere Hälfte je Partei, disjunkt
    - Generatoren: CareDay-Varianten + Zellgeometrien
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 11: ...`
    - **Validates: Requirements 6.1, 6.3, 6.4, 6.5, 6.6, 6.9**

  - [x] 8.5 Property-Test: Trennung von Vollton-Indikator und Mann-Raster
    - **Property 13: Trennung von Vollton-Indikator und Mann-Raster** — Vollton-Balkenzonen und Dithering-Region sind pixel-disjunkt
    - jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 13: ...`
    - **Validates: Requirements 6.8**

  - [x] 8.6 Unit-Test: Heute-Zelle mit MANN
    - Heute-Hervorhebung vorhanden, Tageszahl gezeichnet, keine zusätzliche Mann-Rasterung
    - _Requirements: 6.7_

- [x] 9. Checkpoint - Rendering prüfen
  - Ensure all tests pass, ask the user if questions arise.
  - `./mvnw -q test` ausführen und Icon- sowie Monatskalender-Rendering validieren

- [x] 10. Demo-Daten im Rendering-Test
  - [x] 10.1 `TestImageCreation.buildRenderData` um custodySchedule erweitern
    - Parameter/Überladung `Map<LocalDate, CareDay> custodySchedule` ergänzen und an den neuen `RenderData`-Konstruktor durchreichen
    - Demodaten relativ zu `now`: mind. ein MANN-Ganztag, ein FRAU-Ganztag und ein MANN↔FRAU-Wechseltag; Heute + Folgetag mit Partei belegen, damit beide Icons erscheinen
    - Rendering-Durchlauf bleibt Smoke-Test (erzeugt `test.png` ohne Exception)
    - _Requirements: 7.1, 7.2, 7.3_

- [x] 11. Deployment-Konfiguration (nur Konfig, keine Deployment-Ausführung)
  - [x] 11.1 serverless.yml um Custody-ENV-Variablen erweitern (config only)
    - Unter `functions.FamilyWall-Kalender.environment` ergänzen: `CUSTODY_CALENDAR_FEED: ${param:custodyCalendarFeed, ''}` und `CUSTODY_MALE_MATCH: ${param:custodyMaleMatch, ''}`
    - Reine Konfigurationsänderung; kein `serverless deploy` ausführen
    - _Requirements: 8.1, 8.2_

- [x] 12. Finaler Checkpoint - gesamte Suite prüfen
  - Ensure all tests pass, ask the user if questions arise.
  - `./mvnw -q test` über die vollständige Suite ausführen

- [x] 13. Optik-Überarbeitung (Icon, Schraffur, Wechseltag, Wochenend-Überlagerung)
  - [x] 13.1 Betreuungs-Icon: WC-Schild-Piktogramme am Ende der Wochentag-Zeile
    - `drawCareIcon` zeichnet gefüllte WC-Schild-Figuren (Mann: Rumpf + zwei Beine, rot; Frau: Dreieckskleid, schwarz), Größe 16, am rechten Spaltenrand auf der Überschriftzeile
    - _Requirements: 5.1, 5.2, 5.5, 5.6, 5.7, 5.8, 5.9_
  - [x] 13.2 Schraffur: gleichmäßiges 3×3-Gitter (~11 %), ganze Zelle, Balken obenauf
    - `fillCustodyDither` setzt `(px % 3 == 0) && (py % 3 == 0)`; füllt gesamten Zellhintergrund; Vollton-Indikatorbalken werden danach darübergezeichnet
    - Zielband 8–20 %; Property 12 entsprechend angepasst
    - _Requirements: 6.1, 6.2, 6.8_
  - [x] 13.3 Wechseltage diagonal teilen (unten-links → oben-rechts)
    - `fillCustodyDitherTriangle` + `drawCustodyHighlight`: oberes-linkes Dreieck = Erste_Tageshälfte, unteres-rechtes = Zweite_Tageshälfte; Property 11 auf diagonale Teilung aktualisiert
    - _Requirements: 6.4, 6.5, 6.6_
  - [x] 13.4 Wochenend-Überlagerung spezifizieren und absichern
    - Mann-Wochenend-Zelle: graues Wochenend-Raster + rote Mann-Schraffur überlagert; Rot gewinnt an Kollisionen, keine Mischfarbe (IndexColorModel)
    - **Property 14: Wochenend- und Mann-Raster erzeugen nur erlaubte Volltonfarben** — jqwik `@Property` min 100 tries, Kommentar `Feature: wechselmodell-kalender, Property 14: ...`
    - **Validates: Requirements 6.10**
  - [x] 13.5 Betroffene Property-/Unit-Tests an neue Optik angepasst
    - Property 10 (Icon rechts), 11 (diagonal), 12 (8–20 %), 13 (Balken überdecken Schraffur), Icon-Rendering-Unit-Tests
    - _Requirements: 5.8, 6.1, 6.2, 6.4, 6.5, 6.6, 6.8_

## Notes

- Tasks mit `*` sind optional (Unit-/Property-/Integrationstests) und können für einen schnelleren MVP übersprungen werden; Kern-Implementierungstasks sind nie optional.
- Jeder Task referenziert konkrete Requirement-Klauseln zur Nachverfolgbarkeit.
- Property-Tests (jqwik, min 100 tries, annotiert mit `Feature: wechselmodell-kalender, Property N: ...`) decken die universellen Korrektheitseigenschaften ab; Unit-Tests decken konkrete Fehlerfälle, Mappings und Integrationspunkte ab.
- Build erfolgt durchgängig über den Maven Wrapper (`./mvnw`).
- Die reine Logik (CareScheduleProvider) und ihre Properties kommen vor der Rendering-Integration, damit Fehler früh gefunden werden.
- Task 11 ist eine reine Konfigurationsänderung; es wird kein Deployment ausgeführt.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2", "1.5", "11.1"] },
    { "id": 1, "tasks": ["1.3", "2.1"] },
    { "id": 2, "tasks": ["1.4", "2.2", "2.3", "2.4", "2.5", "2.6", "2.7", "4.1", "5.1"] },
    { "id": 3, "tasks": ["4.2", "5.2", "5.3", "7.1", "8.1"] },
    { "id": 4, "tasks": ["5.4", "7.2", "7.3", "7.4", "8.2", "8.3"] },
    { "id": 5, "tasks": ["8.4", "8.5", "8.6", "10.1"] }
  ]
}
```
