# Requirements Document

## Introduction

Diese Erweiterung ergänzt den bestehenden Familienkalender um einen optionalen **Wechselmodell-Kalender** für die Kinderbetreuung im Wechselmodell (shared custody / alternating care). Ziel ist es, für jeden Tag zu kennzeichnen, welcher Elternteil an diesem Tag die Betreuung übernimmt.

Die Betreuungsdaten stammen aus einem **externen iCal-Feed**, den der Betreiber selbst pflegt. Dieser Feed (Wechselmodell_Feed) wird über die bestehende Feed-Infrastruktur (`EventRepository` / `IcalParser`, ical4j) eingelesen und getrennt von den regulären Terminen verarbeitet. Er enthält ganztägige Events, deren Titel (`SUMMARY`) benennt, wer das Kind an den betroffenen Tagen betreut (z. B. „X bei A" oder „X bei Elternteil B").

Ein Tag kann dabei zwei Ausprägungen haben: Er ist entweder ein **ganztägiger Betreuungstag** mit einer einzigen betreuenden Partei oder ein **Wechseltag**, an dem die Übergabe stattfindet und an dem die erste Tageshälfte der endenden und die zweite Tageshälfte der beginnenden Partei gehört.

Die Funktion umfasst drei Bausteine:

1. **Einlesen** des externen Wechselmodell_Feed über die bestehende iCal-Infrastruktur und Bestimmung der betreuenden Partei je Tag anhand des Event-Titels, einschließlich der Erkennung von Wechseltagen mit zwei Tageshälften.
2. **Darstellung** im gerenderten e-ink-Bild: ein Betreuungs-Icon („Mann"/„Frau") neben der Tagesüberschrift (Heute/Morgen) sowie eine leichte rote Hervorhebung der Mann-Tage im 5-Wochen-Monatskalender, inklusive Halbzellen-Hervorhebung an Wechseltagen.
3. **Bereitstellung** der Konfiguration über die bestehende Serverless-Konfiguration (ENV-Variablen an die `CreateKalenderImage`-Funktion).

Der Wechselmodell_Feed ist ein eigener, separat verarbeiteter Feed und wird **nicht** als regulärer Termin in der Tages-Eventliste angezeigt. Er dient ausschließlich der Steuerung des Betreuungs-Icons und der Monatskalender-Hervorhebung.

Das Feature besitzt **keinen separaten Ein-/Aus-Schalter**. Es aktiviert sich automatisch, sobald in der Umgebungsvariable `CUSTODY_CALENDAR_FEED` eine nicht-leere, syntaktisch gültige HTTP- oder HTTPS-URL hinterlegt ist. Fehlt diese URL, ist sie leer oder ungültig, bleibt das Feature still inaktiv (ohne Konfigurationsfehler) und der Familienkalender verhält sich exakt wie bisher.

## Glossary

- **Wechselmodell_Kalender**: Funktionsbaustein, der pro Tag die betreuende Partei aus dem Wechselmodell_Feed bestimmt und darstellt.
- **Wechselmodell_Feed (Custody_Feed)**: Externer iCal-Feed (RFC 5545) mit ganztägigen Betreuungs-Events, der vom Betreiber gepflegt und über die ENV-Variable `CUSTODY_CALENDAR_FEED` als URL konfiguriert wird.
- **Mann_Kennung (Male_Match)**: Konfigurierbarer Namens-Teilstring zur Identifizierung des Manns (z. B. „Andreas Zöllner"), über die ENV-Variable `CUSTODY_MALE_MATCH` konfiguriert.
- **Betreuende_Partei (Care_Party)**: Die an einem Tag oder einer Tageshälfte betreuende Person, einer von zwei Werten: `MANN` oder `FRAU`. Ein Tag ohne Betreuungs-Event hat keine Betreuende_Partei.
- **Betreuungsblock (Care_Block)**: Die zusammenhängende Folge von Kalendertagen, die durch ein einzelnes Betreuungs_Event über den halboffenen Bereich `[DTSTART, DTEND)` abgedeckt wird und einer einzigen Betreuenden_Partei zugeordnet ist.
- **Wechseltag (Switch_Day)**: Der letzte abgedeckte Tag eines Betreuungsblocks, an dem die Übergabe stattfindet, sofern unmittelbar ein Folgeblock beginnt. Ein Wechseltag besitzt eine Partei der Erste_Tageshälfte (= Partei des endenden Blocks) und eine Partei der Zweite_Tageshälfte (= Partei des beginnenden Folgeblocks).
- **Erste_Tageshälfte (First_Half)**: Die erste Hälfte eines Wechseltags; ihre Partei ist die Betreuende_Partei des endenden (aktuellen) Betreuungsblocks.
- **Zweite_Tageshälfte (Second_Half)**: Die zweite Hälfte eines Wechseltags; ihre Partei ist die Betreuende_Partei des unmittelbar beginnenden Folgeblocks.
- **Ganztägige_Partei (Full_Day_Party)**: Die einzige Betreuende_Partei eines normalen (nicht als Wechseltag erkannten) abgedeckten Tages, die für den gesamten Tag gilt.
- **Betreuungs_Icon (Care_Icon)**: Grafisches Symbol neben der Tagesüberschrift, das die betreuende Partei anzeigt (Icon „Mann" oder Icon „Frau").
- **Betreuungs_Event (Care_Event)**: Ein ganztägiges `VEVENT` im Wechselmodell_Feed mit `DTSTART`/`DTEND` als `VALUE=DATE` und einem `SUMMARY`, das die betreuende Partei für den abgedeckten Datumsbereich benennt.
- **CreateKalenderImage**: Bestehende Lambda-Funktion (EventBridge-getriggert), die das Kalenderbild erzeugt und PNG + BIN nach S3 lädt.
- **ImageRenderer**: Bestehende Klasse, die das 800×480 3-Farben-Bild (weiß/schwarz/rot) rendert.
- **RenderData**: Bestehender Record, der alle Renderdaten für den `ImageRenderer` bündelt, einschließlich einer Fehlerliste.
- **EventRepository**: Bestehende Komponente, die iCal-Feeds abruft und über den `IcalParser` einliest.
- **IcalParser**: Bestehende Komponente, die iCal-Daten (ical4j) in Events parst.
- **Demo_Rendering_Test**: Der bestehende Rendering-Test `TestImageCreation` (`src/test/java/de/goForFun/familienkalender/TestImageCreation.java`), der über eine Hilfsmethode (`buildRenderData`) RenderData aufbaut und Demo-PNGs durch den ImageRenderer rendert.
- **COLOR_RED**: Die im `ImageRenderer` definierte Rotfarbe (180, 30, 30).
- **S3_Bucket**: Der AWS-S3-Bucket `familienkalender` in Region `eu-central-1`.

## Requirements

### Requirement 1: Aktivierung über die Feed-URL

**User Story:** Als Betreiber des Familienkalenders möchte ich, dass der Wechselmodell_Kalender automatisch dann aktiv ist, wenn ich eine gültige Feed-URL hinterlege, damit ich keine separate Schaltervariable pflegen muss und der Kalender sich ohne Feed-URL unverändert verhält.

#### Acceptance Criteria

1. THE Wechselmodell_Kalender SHALL die URL des Wechselmodell_Feed aus der Umgebungsvariable `CUSTODY_CALENDAR_FEED` einlesen.
2. WHERE `CUSTODY_CALENDAR_FEED` eine nicht-leere, syntaktisch gültige HTTP- oder HTTPS-URL enthält, THE Wechselmodell_Kalender SHALL als aktiv gelten und den Wechselmodell_Feed einlesen, die Betreuende_Partei je Tag bestimmen, das Betreuungs_Icon darstellen und die Monatskalender-Hervorhebung ausführen.
3. IF `CUSTODY_CALENDAR_FEED` fehlt, leer ist oder keine syntaktisch gültige HTTP- oder HTTPS-URL enthält, THEN THE Wechselmodell_Kalender SHALL als inaktiv gelten, den Wechselmodell_Feed nicht abrufen, kein Betreuungs_Icon und keine Mann-Tag-Hervorhebung rendern und KEINEN Konfigurationsfehler in die Fehlerliste der RenderData aufnehmen (stilles Ignorieren).
4. WHERE das Feature inaktiv ist, THE CreateKalenderImage SHALL sich exakt wie vor Einführung des Wechselmodell_Kalender verhalten.

### Requirement 2: Konfiguration der Mann-Kennung

**User Story:** Als Betreiber möchte ich die Mann_Kennung konfigurieren, damit der Kalender die betreuende Partei korrekt dem Mann oder der Frau zuordnet.

#### Acceptance Criteria

1. THE Wechselmodell_Kalender SHALL die Mann_Kennung aus der Umgebungsvariable `CUSTODY_MALE_MATCH` einlesen.
2. IF bei aktivem Feature die Mann_Kennung fehlt oder leer ist, THEN THE Wechselmodell_Kalender SHALL einen Konfigurationsfehler, der die Umgebungsvariable `CUSTODY_MALE_MATCH` benennt, in die Fehlerliste der RenderData aufnehmen und die Wechselmodell-Verarbeitung (Betreuungs_Icon und Mann-Tag-Hervorhebung) für diese Ausführung überspringen, ohne das übrige Rendern des Kalenderbilds abzubrechen.

### Requirement 3: Einlesen und Verarbeiten des Wechselmodell-Feeds

**User Story:** Als Betreiber möchte ich, dass der externe Wechselmodell_Feed über die bestehende iCal-Infrastruktur eingelesen und zu einer Tag-zu-Partei-Zuordnung aufgelöst wird, damit die Betreuung stets dem gepflegten Feed entspricht.

#### Acceptance Criteria

1. WHERE das Feature aktiv ist, THE Wechselmodell_Kalender SHALL den Wechselmodell_Feed über EventRepository und IcalParser abrufen und einlesen.
2. THE Wechselmodell_Kalender SHALL den Wechselmodell_Feed getrennt von den regulären Terminen verarbeiten und keine Betreuungs_Events in die Tages-Eventliste der RenderData aufnehmen.
3. WHEN ein Betreuungs_Event mit `DTSTART` und `DTEND` als `VALUE=DATE` eingelesen wird, THE Wechselmodell_Kalender SHALL jeden Kalendertag im halboffenen Bereich von einschließlich `DTSTART` bis ausschließlich `DTEND` dem durch dieses Betreuungs_Event definierten Betreuungsblock und der im `SUMMARY` benannten Betreuenden_Partei zuordnen.
4. WHEN ein Betreuungs_Event eine einzelne Tagesspanne (`DTEND` gleich `DTSTART` plus ein Tag) abdeckt, THE Wechselmodell_Kalender SHALL genau den Tag `DTSTART` dem durch dieses Betreuungs_Event definierten Betreuungsblock und der im `SUMMARY` benannten Betreuenden_Partei zuordnen.
5. IF mehrere Betreuungs_Events denselben Kalendertag abdecken, THEN THE Wechselmodell_Kalender SHALL diesem Tag den Betreuungsblock und die Betreuende_Partei des Betreuungs_Events zuordnen, dessen Schlüssel aus (`DTSTART`, `DTEND`, `SUMMARY`) lexikografisch am größten ist, sodass das Ergebnis deterministisch und von der Einlesereihenfolge unabhängig ist.
6. IF der Wechselmodell_Feed nicht erreichbar ist oder keine gültigen iCal-Daten enthält, THEN THE Wechselmodell_Kalender SHALL einen Fehler, der den Wechselmodell_Feed benennt, in die Fehlerliste der RenderData aufnehmen und das übrige Rendern des Kalenderbilds ohne Betreuungs_Icon und ohne Mann-Tag-Hervorhebung fortsetzen.
7. WHEN derselbe Wechselmodell_Feed zweimal mit derselben Mann_Kennung eingelesen wird, THE Wechselmodell_Kalender SHALL für jeden abgedeckten Tag beide Male dasselbe Ergebnis (Ganztägige_Partei bzw. Wechseltag-Paar aus Erste_Tageshälfte und Zweite_Tageshälfte) zuordnen.

### Requirement 4: Bestimmung der betreuenden Partei je Tag

**User Story:** Als Familienmitglied möchte ich, dass jeder Betreuungstag eindeutig einer Partei bzw. an Wechseltagen zwei Tageshälften zugeordnet wird, damit die Anzeige die tatsächliche Betreuung einschließlich der Übergabe widerspiegelt.

#### Acceptance Criteria

1. WHEN das `SUMMARY` des für einen Betreuungsblock maßgeblichen Betreuungs_Events die Mann_Kennung als Teilstring ohne Berücksichtigung der Groß-/Kleinschreibung enthält, THE Wechselmodell_Kalender SHALL die Betreuende_Partei dieses Betreuungsblocks als `MANN` bestimmen.
2. WHEN das `SUMMARY` des für einen Betreuungsblock maßgeblichen Betreuungs_Events die Mann_Kennung nicht als Teilstring enthält, THE Wechselmodell_Kalender SHALL die Betreuende_Partei dieses Betreuungsblocks als `FRAU` bestimmen.
3. WHEN der letzte abgedeckte Tag eines Betreuungsblocks zugleich der Tag unmittelbar vor dem `DTSTART` eines Folgeblocks ist (das heißt, ein Folgeblock beginnt am Tag Wechseltag plus ein Tag), THE Wechselmodell_Kalender SHALL diesen Tag als Wechseltag kennzeichnen, die Partei der Erste_Tageshälfte auf die Betreuende_Partei des endenden Blocks setzen und die Partei der Zweite_Tageshälfte auf die Betreuende_Partei des beginnenden Folgeblocks setzen.
4. IF der letzte abgedeckte Tag eines Betreuungsblocks keinen unmittelbar am Folgetag beginnenden Folgeblock besitzt, THEN THE Wechselmodell_Kalender SHALL diesen Tag nicht als Wechseltag kennzeichnen und ihm die Ganztägige_Partei des aktuellen Betreuungsblocks zuordnen.
5. WHERE ein abgedeckter Tag nicht der letzte abgedeckte Tag seines Betreuungsblocks ist, THE Wechselmodell_Kalender SHALL diesem Tag die Ganztägige_Partei des Betreuungsblocks zuordnen.
6. WHERE für einen Tag kein Betreuungs_Event vorliegt, THE Wechselmodell_Kalender SHALL diesem Tag keine Betreuende_Partei zuordnen.

### Requirement 5: Betreuungs-Icon in der Tagesansicht

**User Story:** Als Familienmitglied möchte ich neben der Tagesüberschrift sehen, wer das Kind an diesem Tag betreut, damit ich die Betreuung auf einen Blick erkenne.

#### Acceptance Criteria

1. WHERE das Feature aktiv ist, THE ImageRenderer SHALL auf derselben Zeile wie die Überschrift der Heute-Spalte, am Ende dieser Zeile (am rechten Spaltenrand), ein Betreuungs_Icon für die an diesem Tag maßgebliche Partei zeichnen.
2. WHERE das Feature aktiv ist, THE ImageRenderer SHALL auf derselben Zeile wie die Überschrift der Morgen-Spalte, am Ende dieser Zeile (am rechten Spaltenrand), ein Betreuungs_Icon für die am Folgetag maßgebliche Partei zeichnen.
3. WHERE ein darzustellender Tag ein Wechseltag ist, THE ImageRenderer SHALL als maßgebliche Partei dieses Tages die Partei der Zweite_Tageshälfte verwenden, also die Partei, die das Kind am Ende des Tages betreut.
4. WHERE ein darzustellender Tag ein normaler Tag mit Ganztägige_Partei ist, THE ImageRenderer SHALL als maßgebliche Partei dieses Tages die Ganztägige_Partei verwenden.
5. IF die maßgebliche Partei eines Tages `MANN` ist, THEN THE ImageRenderer SHALL das Mann-Piktogramm (stilisierte männliche Personen-Silhouette) für diesen Tag zeichnen.
6. IF die maßgebliche Partei eines Tages `FRAU` ist, THEN THE ImageRenderer SHALL das Frau-Piktogramm (stilisierte weibliche Personen-Silhouette) für diesen Tag zeichnen.
7. THE ImageRenderer SHALL das Betreuungs_Icon ausschließlich aus den Farben Schwarz und COLOR_RED sowie transparentem Hintergrund zeichnen und keine Graustufen oder Halbtöne verwenden.
8. THE ImageRenderer SHALL das Betreuungs_Icon so positionieren, dass es weder die Tagesüberschrift noch die Events der jeweiligen Spalte überlappt.
9. IF für einen darzustellenden Tag keine Betreuende_Partei vorliegt, THEN THE ImageRenderer SHALL die betroffene Spalte ohne Betreuungs_Icon zeichnen und die übrige Darstellung von Tagesüberschrift und Events unverändert beibehalten.

### Requirement 6: Hervorhebung der Mann-Tage im Monatskalender

**User Story:** Als Familienmitglied möchte ich im 5-Wochen-Monatskalender erkennen, an welchen Tagen und Tageshälften der Mann betreut, damit ich den Betreuungsrhythmus über den Monat überblicke.

#### Acceptance Criteria

1. WHERE das Feature aktiv ist, THE ImageRenderer SHALL jede Tageszelle eines normalen Tages, dessen Ganztägige_Partei `MANN` ist, flächig mit einer gerasterten roten Hervorhebung versehen, die den Hintergrund der gesamten Tageszelle bedeckt.
2. WHERE das Feature aktiv ist, THE ImageRenderer SHALL für die gerasterte rote Hervorhebung ein leichtes Dithering-Pattern in COLOR_RED mit einer Deckungsdichte zwischen einschließlich 8 % und einschließlich 20 % der eingefärbten Fläche verwenden, sodass die Hervorhebung dezent bleibt und die Zellinhalte (Tageszahl, Indikatorbalken) gut lesbar bleiben.
3. WHERE das Feature aktiv ist, THE ImageRenderer SHALL Tageszellen eines normalen Tages, dessen Ganztägige_Partei `FRAU` ist, ohne Mann-Tag-Hervorhebung zeichnen, sodass 0 % der Zellfläche mit dem Mann-Tag-Pattern eingefärbt sind.
4. WHERE eine Tageszelle einen Wechseltag darstellt, THE ImageRenderer SHALL die Tageszelle entlang der Diagonale von unten-links nach oben-rechts in ein oberes-linkes Dreieck für die Erste_Tageshälfte und ein unteres-rechtes Dreieck für die Zweite_Tageshälfte aufteilen.
5. WHERE eine Tageszelle einen Wechseltag darstellt und die Partei der Erste_Tageshälfte `MANN` ist, THE ImageRenderer SHALL das obere-linke Dreieck der Tageszelle mit der gerasterten roten Hervorhebung (COLOR_RED-Dithering, 8 %–20 %) versehen, und WHERE die Partei der Erste_Tageshälfte `FRAU` ist, THE ImageRenderer SHALL das obere-linke Dreieck ohne Hervorhebung lassen.
6. WHERE eine Tageszelle einen Wechseltag darstellt und die Partei der Zweite_Tageshälfte `MANN` ist, THE ImageRenderer SHALL das untere-rechte Dreieck der Tageszelle mit der gerasterten roten Hervorhebung (COLOR_RED-Dithering, 8 %–20 %) versehen, und WHERE die Partei der Zweite_Tageshälfte `FRAU` ist, THE ImageRenderer SHALL das untere-rechte Dreieck ohne Hervorhebung lassen.
7. WHERE eine Tageszelle gleichzeitig der aktuelle Tag ist, THE ImageRenderer SHALL die bestehende Heute-Hervorhebung unverändert anzeigen und die Tageszahl vollständig und ohne Überzeichnung durch das Mann-Tag-Pattern darstellen.
8. WHERE eine Tageszelle gleichzeitig einen bestehenden Vollton-Indikatorbalken (Teilnehmerbalken oben bzw. Unassigned-Balken unten) trägt, THE ImageRenderer SHALL die Mann-Tag-Hervorhebung als Dithering-Pattern in den Zellhintergrund zeichnen und die Vollton-Indikatorbalken danach (darüber) in Volltonfarbe zeichnen, sodass die Balken die darunterliegende Schraffur vollständig überdecken und als durchgehende Volltonflächen erkennbar bleiben.
9. IF für eine Tageszelle keine Betreuende_Partei vorliegt, THEN THE ImageRenderer SHALL die Tageszelle ohne Mann-Tag-Hervorhebung zeichnen.
10. WHERE eine Tageszelle gleichzeitig ein Wochenendtag (mit dem leichten grauen Wochenend-Raster) und ein Mann-Tag ist, THE ImageRenderer SHALL beide Raster überlagert in dieselbe Zelle zeichnen – zuerst das graue Wochenend-Raster, danach die rote Mann-Schraffur –, sodass an Pixeln, die beide Raster setzen würden, die rote Volltonfarbe COLOR_RED erscheint und keine Mischfarbe oder Graustufe entsteht.

### Requirement 7: Demo-Daten im Rendering-Test

**User Story:** Als Entwickler möchte ich, dass der bestehende Demo-Rendering-Test repräsentative Wechselmodell-Daten rendert, damit das erzeugte Demo-Bild sowohl das Betreuungs_Icon (inklusive der Wechseltag-Icon-Regel) als auch die Monatskalender-Hervorhebung (inklusive der Halbzellen-Hervorhebung) sichtbar prüft.

#### Acceptance Criteria

1. WHEN der Demo_Rendering_Test seine RenderData über die Hilfsmethode aufbaut, THE Demo_Rendering_Test SHALL repräsentative Wechselmodell-Demodaten bereitstellen, die mindestens einen ganztägigen `MANN`-Tag, mindestens einen ganztägigen `FRAU`-Tag und mindestens einen Wechseltag mit einer `MANN`↔`FRAU`-Übergabe enthalten.
2. WHEN der Demo_Rendering_Test die RenderData mit den Wechselmodell-Demodaten an den ImageRenderer übergibt, THE ImageRenderer SHALL das Demo-Bild mit dem Betreuungs_Icon gemäß der Wechseltag-Icon-Regel und mit der Monatskalender-Hervorhebung einschließlich der Halbzellen-Hervorhebung für den Wechseltag rendern.
3. THE Demo_Rendering_Test SHALL die Wechselmodell-Demodaten so in die RenderData aufnehmen, dass die per-Tag-Betreuungsinformation (Ganztägige_Partei bzw. Wechseltag mit Erste_Tageshälfte und Zweite_Tageshälfte) dem ImageRenderer zum Rendern zur Verfügung steht.

### Requirement 8: Deployment-Konfiguration

**User Story:** Als Betreiber möchte ich die Wechselmodell-Funktion über die bestehende Serverless-Konfiguration bereitstellen, damit die Einrichtung dem bestehenden Deployment-Prozess folgt.

#### Acceptance Criteria

1. THE serverless.yml SHALL die Umgebungsvariable `CUSTODY_CALENDAR_FEED` an die CreateKalenderImage-Funktion übergeben.
2. THE serverless.yml SHALL die Umgebungsvariable `CUSTODY_MALE_MATCH` an die CreateKalenderImage-Funktion übergeben.
