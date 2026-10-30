# Requirements Document

## Introduction

Diese Erweiterung ergänzt den bestehenden Familienkalender um einen **anonymisierenden iCal-Feed**. Ziel ist es, einen eingehenden iCal-Feed aus outlook.com so aufzubereiten, dass er in FamilyWall als externer Kalender abonniert werden kann, ohne private Termininhalte preiszugeben.

Der Dienst stellt eine neue **HTTP-Endpunkt-URL** bereit (Anonymized_Feed_Endpoint), die FamilyWall als ICS-Abonnement-URL nutzt. Der Endpunkt lädt bei jedem Aufruf den konfigurierten eingehenden Outlook-Feed (Outlook_Source_Feed), transformiert dessen Events und liefert einen gültigen iCal-Feed (RFC 5545) zurück. Für Events, die in Outlook als **privat** markiert sind (CLASS-Eigenschaft mit Wert `PRIVATE` oder `CONFIDENTIAL`), ersetzt der Endpunkt den Titel (`SUMMARY`) und die inhaltlichen Felder (`DESCRIPTION`, `LOCATION`, `URL`, `ATTENDEE`, `ORGANIZER`) durch einen neutralen Platzhaltertext. Datum, Uhrzeit (`DTSTART`/`DTEND`), die Dauer und die eindeutige Kennung (`UID`) bleiben erhalten, sodass FamilyWall die Termine korrekt in Datum und Uhrzeit darstellt.

In FamilyWall erfolgt die Zuordnung eines externen Kalenders zu einer Person über das **Teilen des abonnierten Kalenders mit einem Mitglied** – nicht über Teilnehmerfelder (`ATTENDEE`) einzelner Events. Damit der anonymisierte Kalender in FamilyWall sauber einem Mitglied (dem Participant) zugeordnet werden kann, stellt der Endpunkt den Participant als iCal-Kalendernamen (`X-WR-CALNAME`) bereit und setzt für jedes Event einen konsistenten Organizer-/Kalender-Bezug. Der Participant wird in der Serverless-Konfiguration als Umgebungsvariable hinterlegt.

Wichtig für die Abgrenzung: Dieses Feature betrifft **ausschließlich** den nach außen (an FamilyWall) ausgelieferten anonymisierten Feed. Die Anzeige auf dem eigenen e-ink-Device (`CreateKalenderImage` / `ImageRenderer`) verwendet weiterhin die **Original-Informationen** des Outlook-Feeds und wird durch dieses Feature nicht verändert.

Die Funktion umfasst vier Bausteine:

1. **Bereitstellung** eines neuen HTTP-Endpunkts (AWS Lambda mit Function URL), der den anonymisierten iCal-Feed ausliefert.
2. **Einlesen** des Outlook_Source_Feed über die bestehende iCal-Infrastruktur (ical4j) bzw. durch Transformation auf iCal-Ebene.
3. **Anonymisierung** privater Events durch Ersetzen von Titel und Inhaltsfeldern unter Beibehaltung von Datum, Uhrzeit und Kennung.
   Zusätzlich werden Events, die in der Vergangenheit liegen oder deren Status auf „frei“ steht, gar nicht erst in den ausgelieferten Feed übernommen.
4. **FamilyWall-Zuordnung** des Participant über den iCal-Kalendernamen, sodass der abonnierte Kalender in FamilyWall einem Mitglied zugeordnet werden kann.

### Abgrenzung zu FamilyWall-Bordmitteln

Dieses Feature wurde bewusst als eigenständiger Dienst umgesetzt, weil die eingebauten Mechanismen von outlook.com und FamilyWall den vorliegenden Anwendungsfall nicht abdecken:

- FamilyWalls eigene Sichtbarkeitssteuerung arbeitet auf Ebene des Familienkreises (Circle): Veröffentlichte Daten sind entweder für alle Mitglieder eines Kreises sichtbar oder – über getrennte Kreise – für andere gar nicht. Eine Zwischenstufe „Zeit bzw. Belegung sichtbar, Titel und Inhalt jedoch verborgen" auf Ebene einzelner Termine existiert nicht ([FamilyWall: Who can see the information I publish](https://support.familywall.com/en/support/solutions/articles/47001013685-who-can-see-the-information-i-publish-)).
- FamilyWall kann externe ICS-Kalender per URL abonnieren (Premium), übernimmt die Events dabei aber unverändert. Eine selektive Anonymisierung einzelner – privater – Termine beim Import ist nicht vorgesehen ([FamilyWall: Import events from an external calendar](https://support.familywall.com/en/support/solutions/articles/47001250207-import-events-file-or-url-from-an-external-calendar)).
- Outlook kann beim Veröffentlichen die Detailstufe zwar auf „nur Verfügbarkeit" reduzieren, dies würde jedoch ALLE Termine betreffen und damit auch die e-ink-Device-Anzeige entwerten, die bewusst die Originaltitel zeigen soll.
- Daher ist ein eigener Proxy erforderlich, der pro Termin anhand von `CLASS:PRIVATE` selektiv anonymisiert, die belegte Zeit erhält und aus derselben Quelle zwei Sichten bereitstellt (Device = original, FamilyWall = anonymisiert). Dieser Ansatz ist zugleich datenschutzfreundlicher als kommerzielle Dritt-Proxys, da die Kalenderdaten die eigene Infrastruktur nicht verlassen.

Hinweis: Inhalte aus den oben verlinkten FamilyWall-Quellen wurden zur Einhaltung der Lizenzbedingungen paraphrasiert.

## Glossary

- **Anonymized_Feed**: Funktionsbaustein, der aus dem Outlook_Source_Feed einen anonymisierten iCal-Feed für FamilyWall erzeugt und über den Anonymized_Feed_Endpoint ausliefert.
- **Anonymized_Feed_Endpoint**: Neue AWS-Lambda-Funktion mit Function URL, die bei HTTP-Aufruf den anonymisierten iCal-Feed als Antwort liefert.
- **Outlook_Source_Feed**: Der eingehende iCal-Feed (RFC 5545) aus outlook.com, dessen URL über die Umgebungsvariable `ANON_SOURCE_FEED` konfiguriert wird.
- **Participant**: Die Person, der der anonymisierte Kalender in FamilyWall zugeordnet wird; als Anzeigename über die Umgebungsvariable `ANON_PARTICIPANT` konfiguriert.
- **Private_Event**: Ein `VEVENT` des Outlook_Source_Feed, dessen `CLASS`-Eigenschaft den Wert `PRIVATE` oder `CONFIDENTIAL` trägt.
- **Public_Event**: Ein `VEVENT` des Outlook_Source_Feed, das kein Private_Event ist (CLASS fehlt oder hat den Wert `PUBLIC`).
- **Anonymisierter_Platzhalter (Anonymized_Placeholder)**: Der neutrale Ersatztext, der bei einem Private_Event als `SUMMARY` gesetzt wird; über die Umgebungsvariable `ANON_PLACEHOLDER` konfigurierbar, mit Vorgabewert „Privat".
- **Inhaltsfelder (Content_Fields)**: Die Eigenschaften eines `VEVENT`, die Rückschlüsse auf den Terminzweck erlauben: `DESCRIPTION`, `LOCATION`, `URL`, `ATTENDEE`, `ORGANIZER`.
- **Zeitfelder (Time_Fields)**: Die Eigenschaften `DTSTART`, `DTEND` bzw. `DURATION` sowie `RRULE`, `RDATE`, `EXDATE` und `RECURRENCE-ID`, die Zeitpunkt, Dauer und Wiederholung eines Events bestimmen.
- **Frei_Status (Free_Status)**: Ein `VEVENT` gilt als „frei“, WHERE seine `TRANSP`-Eigenschaft den Wert `TRANSPARENT` trägt ODER seine `X-MICROSOFT-CDO-BUSYSTATUS`-Eigenschaft den Wert `FREE` trägt (Vergleich ohne Berücksichtigung der Groß-/Kleinschreibung, getrimmt). Solche Events blockieren keine Zeit und werden nicht in den anonymisierten Feed übernommen.
- **Vergangenes_Event (Past_Event)**: Ein nicht wiederkehrendes `VEVENT`, dessen Ende (`DTEND`; ersatzweise `DTSTART` bzw. bei Ganztags-Events `DTSTART` + 1 Tag) vor dem aktuellen Zeitpunkt (Jetzt) liegt. Ein Event mit `RRULE` (Serientermin) gilt nie allein aufgrund eines vergangenen `DTSTART` als Vergangenes_Event.
- **VTIMEZONE**: iCal-Komponente, die eine Zeitzonendefinition enthält und von Events über den TZID-Parameter referenziert wird.
- **iCal_Kalendername (X-WR-CALNAME)**: Die iCal-Kalendereigenschaft `X-WR-CALNAME`, die FamilyWall als Name des abonnierten Kalenders anzeigt.
- **CreateKalenderImage**: Bestehende Lambda-Funktion (EventBridge-getriggert), die das e-ink-Kalenderbild erzeugt und PNG + BIN nach S3 lädt. Sie nutzt die Original-Informationen des Outlook-Feeds.
- **ImageRenderer**: Bestehende Klasse, die das e-ink-Bild aus den Original-Events rendert.
- **IcalParser**: Bestehende Komponente, die iCal-Daten (ical4j) in Events parst.
- **API_Secret**: Gemeinsames Geheimnis zur optionalen Authentifizierung eines Endpunkts, analog zum bestehenden `ServeCalendarImage`.
- **serverless.yml**: Bestehende Serverless-Framework-Konfiguration, über die Lambda-Funktionen und Umgebungsvariablen bereitgestellt werden.

## Requirements

### Requirement 1: Anonymisierender iCal-Endpunkt

**User Story:** Als Betreiber des Familienkalenders möchte ich eine abonnierbare iCal-URL bereitstellen, die FamilyWall als externen Kalender einbinden kann, damit meine Outlook-Termine in FamilyWall erscheinen, ohne private Inhalte offenzulegen.

#### Acceptance Criteria

1. WHEN der Anonymized_Feed_Endpoint per HTTP-GET aufgerufen wird, THE Anonymized_Feed SHALL den Outlook_Source_Feed abrufen, dessen Events transformieren und einen gültigen iCal-Feed nach RFC 5545 im Antworttext zurückgeben, wobei pro Event mindestens Beginn (`DTSTART`), Ende (`DTEND` bzw. `DURATION`), Zeitzone und Ganztags-Status übernommen werden.
2. WHEN der Anonymized_Feed_Endpoint eine Antwort mit iCal-Daten liefert, THE Anonymized_Feed SHALL den HTTP-Statuscode 200 und den Content-Type `text/calendar; charset=utf-8` setzen.
3. THE Anonymized_Feed SHALL die URL des Outlook_Source_Feed aus der Umgebungsvariable `ANON_SOURCE_FEED` einlesen.
4. IF `ANON_SOURCE_FEED` fehlt, leer ist oder keine syntaktisch gültige HTTP- oder HTTPS-URL enthält, THEN THE Anonymized_Feed SHALL den HTTP-Statuscode 500 und eine Fehlermeldung, die die fehlende oder ungültige Konfiguration des Outlook_Source_Feed benennt, zurückgeben.
5. IF der Outlook_Source_Feed nicht innerhalb von 10 Sekunden erreichbar ist oder keine gültigen iCal-Daten enthält, THEN THE Anonymized_Feed SHALL den HTTP-Statuscode 502 und eine Fehlermeldung, die den nicht verarbeitbaren Outlook_Source_Feed benennt, zurückgeben.
6. WHEN ein Private_Event in den ausgelieferten Feed übernommen wird, THE Anonymized_Feed SHALL dessen Titel durch den Anonymisierten_Platzhalter ersetzen und `DESCRIPTION`, `LOCATION`, `ATTENDEE`, `ORGANIZER` sowie `ATTACH` vollständig entfernen, sodass keine privaten Inhalte offengelegt werden.
7. WHERE der Outlook_Source_Feed eine oder mehrere `VTIMEZONE`-Komponenten enthält, THE Anonymized_Feed SHALL jede `VTIMEZONE`-Komponente unverändert in den erzeugten Feed übernehmen und die `TZID`-Parameter der `DTSTART`- und `DTEND`-Eigenschaften jedes Events erhalten, sodass FamilyWall die Terminzeiten identisch zur Quelle auflöst.

### Requirement 2: Anonymisierung privater Events

**User Story:** Als Participant möchte ich, dass als privat markierte Outlook-Termine in FamilyWall ohne Titel und Inhalt erscheinen, damit nur Datum, Uhrzeit und meine Belegung sichtbar sind, nicht aber der Zweck des Termins.

#### Acceptance Criteria

1. WHEN ein Private_Event transformiert wird, THE Anonymized_Feed SHALL dessen `SUMMARY` auf den Anonymisierten_Platzhalter setzen.
2. WHEN ein Private_Event transformiert wird, THE Anonymized_Feed SHALL alle vorhandenen Inhaltsfelder (`DESCRIPTION`, `LOCATION`, `URL`, `ATTENDEE`, `ORGANIZER`) aus dem ausgelieferten Event entfernen, einschließlich solcher Inhaltsfelder, die zwar vorhanden sind, aber keinen Wert tragen (z. B. `DESCRIPTION:` ohne nachfolgenden Wert oder `LOCATION:` ohne Wert).
3. WHEN ein Private_Event transformiert wird, THE Anonymized_Feed SHALL die Zeitfelder (`DTSTART`, `DTEND` bzw. `DURATION`, `RRULE`, `RDATE`, `EXDATE`, `RECURRENCE-ID`) und die `UID` unverändert aus dem Quell-Event übernehmen.
4. THE Anonymized_Feed SHALL ein `VEVENT` genau dann als Private_Event behandeln, WHERE dessen `CLASS`-Eigenschaft den Wert `PRIVATE` oder `CONFIDENTIAL` trägt (Vergleich ohne Berücksichtigung der Groß-/Kleinschreibung); in allen anderen Fällen – einschließlich fehlender, leerer oder nicht erkannter `CLASS`-Eigenschaft – SHALL das `VEVENT` als nicht-privat eingestuft werden.
5. WHEN ein als nicht-privat eingestuftes `VEVENT` transformiert wird, THE Anonymized_Feed SHALL alle Felder dieses Events (einschließlich `SUMMARY` und aller Inhaltsfelder) unverändert aus dem Quell-Event übernehmen.
6. THE Anonymized_Feed SHALL den Anonymisierten_Platzhalter aus der Umgebungsvariable `ANON_PLACEHOLDER` einlesen und, WHERE diese fehlt, leer ist oder länger als 255 Zeichen ist, den Vorgabewert „Privat" verwenden.

### Requirement 3: Unveränderte Weitergabe öffentlicher Events

**User Story:** Als Participant möchte ich, dass nicht-private Outlook-Termine mit ihren Originalinformationen in FamilyWall erscheinen, damit öffentliche Termine vollständig nutzbar bleiben.

#### Acceptance Criteria

1. WHEN ein Public_Event transformiert wird, THE Anonymized_Feed SHALL dessen `SUMMARY` wertidentisch aus dem Quell-Event übernehmen, ohne Normalisierung von Zeichen, Leerraum oder Kodierung.
2. WHEN ein Public_Event transformiert wird UND die Inhaltsfelder `DESCRIPTION`, `LOCATION` oder `URL` im Quell-Event vorhanden sind, THE Anonymized_Feed SHALL jedes vorhandene dieser Felder wertidentisch aus dem Quell-Event übernehmen, ohne Normalisierung von Zeichen, Leerraum oder Kodierung.
3. WHEN ein Public_Event transformiert wird, THE Anonymized_Feed SHALL dessen Zeitfelder und `UID` wertidentisch aus dem Quell-Event übernehmen, ohne Normalisierung von Zeichen, Leerraum oder Kodierung.
4. IF ein Inhaltsfeld (`DESCRIPTION`, `LOCATION` oder `URL`) im Quell-Event eines Public_Event fehlt, THEN THE Anonymized_Feed SHALL dieses Feld im transformierten Event weglassen und keinen leeren oder ersatzweisen Wert erzeugen.

### Requirement 4: Zuordnung des Participant in FamilyWall

**User Story:** Als Betreiber möchte ich den anonymisierten Kalender in FamilyWall einem Mitglied (dem Participant) zuordnen können, damit die Termine in FamilyWall mit der richtigen Person verknüpft dargestellt werden.

#### Acceptance Criteria

1. THE Anonymized_Feed SHALL den Participant aus der Umgebungsvariable `ANON_PARTICIPANT` einlesen und dabei führende und nachgestellte Leerzeichen entfernen.
2. WHERE der getrimmte Wert von `ANON_PARTICIPANT` mindestens 1 und höchstens 200 Zeichen enthält, THE Anonymized_Feed SHALL die iCal-Kalendereigenschaft `X-WR-CALNAME` des ausgelieferten Feeds auf diesen Wert setzen.
3. WHEN THE Anonymized_Feed den Participant in die Eigenschaft `X-WR-CALNAME` schreibt, THE Anonymized_Feed SHALL Sonderzeichen gemäß RFC 5545 maskieren und überlange Zeilen gemäß RFC 5545 falten, sodass der Feed gültig bleibt.
4. IF `ANON_PARTICIPANT` fehlt, THEN THE Anonymized_Feed SHALL den iCal-Feed ohne `X-WR-CALNAME`-Eigenschaft ausliefern und den Feed dennoch gültig nach RFC 5545 erzeugen.
5. IF der getrimmte Wert von `ANON_PARTICIPANT` leer ist oder mehr als 200 Zeichen enthält, THEN THE Anonymized_Feed SHALL den iCal-Feed ohne `X-WR-CALNAME`-Eigenschaft ausliefern, den Feed dennoch gültig nach RFC 5545 erzeugen und den Vorgang mit einer Meldung protokollieren, die den Grund der Nichtübernahme angibt.

### Requirement 5: Trennung von Device-Anzeige und externem Feed

**User Story:** Als Betreiber möchte ich, dass die Anzeige auf meinem e-ink-Device weiterhin die Originaltitel und -inhalte zeigt, während FamilyWall die anonymisierte Variante erhält, damit die Anonymisierung ausschließlich den externen Feed betrifft.

#### Acceptance Criteria

1. WHEN CreateKalenderImage das e-ink-Kalenderbild erzeugt, THE CreateKalenderImage SHALL die Original-Informationen (`SUMMARY`, Inhaltsfelder) des Outlook_Source_Feed verwenden und ein Bild erzeugen, das mit und ohne aktiviertes Feature byte-identisch ist.
2. THE Anonymized_Feed SHALL die Anonymisierung ausschließlich auf den über den Anonymized_Feed_Endpoint ausgelieferten iCal-Feed anwenden.
3. WHEN der Anonymized_Feed_Endpoint aufgerufen wird, THE Anonymized_Feed SHALL die auf S3 abgelegten Artefakte `calendar.png` und `calendar.bin` unverändert (byte-identisch vor und nach dem Abruf) lassen.
4. IF die Anonymisierung oder der Abruf des Anonymized_Feed fehlschlägt, THEN THE CreateKalenderImage SHALL die e-ink-Device-Anzeige weiterhin aus den Original-Informationen rendern und der Fehler SHALL auf den Anonymized_Feed_Endpoint beschränkt bleiben.

### Requirement 6: Round-Trip-Korrektheit des erzeugten iCal-Feeds

**User Story:** Als Betreiber möchte ich sicher sein, dass der erzeugte anonymisierte Feed ein gültiger, wiedereinlesbarer iCal-Feed ist, damit FamilyWall ihn fehlerfrei abonnieren kann.

#### Acceptance Criteria

1. WHEN der Anonymized_Feed einen iCal-Feed erzeugt hat, THE Anonymized_Feed SHALL einen Feed ausgeben, der durch den IcalParser (ical4j) ohne Fehler wieder eingelesen werden kann.
2. WHEN ein erzeugter Feed wieder eingelesen wird, THE Anonymized_Feed SHALL für jedes Quell-`VEVENT` genau ein `VEVENT` mit identischer `UID` und identischen `DTSTART`-Werten sowie, WHERE ein `DTEND` oder eine `DURATION` im Quell-`VEVENT` vorhanden ist, identischem `DTEND`- bzw. `DURATION`-Wert im erzeugten Feed enthalten.
3. WHEN ein erzeugter Feed wieder eingelesen und erneut anonymisiert wird, THE Anonymized_Feed SHALL für jedes Private_Event dasselbe Ergebnis (Anonymisierter_Platzhalter als `SUMMARY`, keine Inhaltsfelder) liefern wie beim ersten Durchlauf (Idempotenz der Anonymisierung).
4. IF ein Quell-`VEVENT` keine einlesbare `UID` oder kein einlesbares `DTSTART` besitzt, THEN THE Anonymized_Feed SHALL dieses `VEVENT` aus dem erzeugten Feed auslassen und den Vorgang mit einer Meldung protokollieren, die das ausgelassene `VEVENT` benennt.
5. WHEN ein erzeugter Feed wieder eingelesen wird, THE Anonymized_Feed SHALL für jedes erzeugte `VEVENT` die `TZID`-Parameterwerte der `DTSTART`- und `DTEND`-Eigenschaften identisch zum Quell-`VEVENT` halten und für jede von einem Event referenzierte `TZID` eine zugehörige `VTIMEZONE`-Komponente im erzeugten Feed bereitstellen.

### Requirement 7: Authentifizierung und Zugriff

**User Story:** Als Betreiber möchte ich den Anonymized_Feed_Endpoint optional absichern können, damit nur berechtigte Abonnenten die Termine abrufen.

#### Acceptance Criteria

1. WHERE die Umgebungsvariable `ANON_API_SECRET` nach Entfernen führender und nachfolgender Leerzeichen einen nicht-leeren Wert (Länge größer oder gleich 1 Zeichen) enthält, THE Anonymized_Feed SHALL einen Aufruf nur dann mit iCal-Daten beantworten, wenn der Aufruf das API_Secret als Query-Parameter `token` enthält und dessen Wert zeichengenau (Groß-/Kleinschreibung beachtend) mit dem Wert von `ANON_API_SECRET` übereinstimmt.
2. IF bei gesetzter `ANON_API_SECRET` der Query-Parameter `token` fehlt, einen leeren Wert besitzt oder nicht zeichengenau mit `ANON_API_SECRET` übereinstimmt, THEN THE Anonymized_Feed SHALL den HTTP-Statuscode 401 zurückgeben und keine iCal-Daten ausliefern.
3. WHERE `ANON_API_SECRET` fehlt oder nach Entfernen führender und nachfolgender Leerzeichen einen leeren Wert (Länge 0 Zeichen) besitzt, THE Anonymized_Feed SHALL Aufrufe ohne Token-Prüfung mit iCal-Daten beantworten.
4. IF bei gesetzter `ANON_API_SECRET` der Query-Parameter `token` mehr als einmal im Aufruf vorkommt, THEN THE Anonymized_Feed SHALL den HTTP-Statuscode 401 zurückgeben und keine iCal-Daten ausliefern.

### Requirement 8: Deployment-Konfiguration

**User Story:** Als Betreiber möchte ich den anonymisierenden Feed über die bestehende Serverless-Konfiguration bereitstellen, damit die Einrichtung dem bestehenden Deployment-Prozess folgt.

#### Acceptance Criteria

1. THE serverless.yml SHALL innerhalb der bestehenden Serverless-Konfiguration eine neue Lambda-Funktion für den Anonymized_Feed_Endpoint mit einer Function URL definieren.
2. THE serverless.yml SHALL die Umgebungsvariablen `ANON_SOURCE_FEED`, `ANON_PARTICIPANT`, `ANON_PLACEHOLDER` und `ANON_API_SECRET` an die Anonymized_Feed_Endpoint-Funktion übergeben.
3. IF beim Start der Anonymized_Feed_Endpoint-Funktion eine der erforderlichen Umgebungsvariablen `ANON_SOURCE_FEED`, `ANON_PARTICIPANT`, `ANON_PLACEHOLDER` oder `ANON_API_SECRET` nicht gesetzt oder leer ist, THEN THE Anonymized_Feed_Endpoint SHALL die Initialisierung abbrechen und eine Fehlermeldung ausgeben, die die fehlende Umgebungsvariable benennt.

### Requirement 9: Filterung vergangener und als frei markierter Events

**User Story:** Als Participant möchte ich, dass im anonymisierten Feed nur relevante Belegungen erscheinen, damit vergangene Termine und als „frei“ markierte Zeiten FamilyWall nicht unnötig belasten oder Belegung vortäuschen.

#### Acceptance Criteria

1. WHEN ein `VEVENT` ein Vergangenes_Event ist, THE Anonymized_Feed SHALL dieses Event nicht in den ausgelieferten Feed übernehmen.
2. WHEN ein `VEVENT` ein Frei_Status-Event ist, THE Anonymized_Feed SHALL dieses Event nicht in den ausgelieferten Feed übernehmen, unabhängig davon, ob es privat oder öffentlich ist.
3. THE Anonymized_Feed SHALL den Vergangenheitsbezug am Ende des Events festmachen: ein Event, dessen Ende (`DTEND`; ersatzweise `DTSTART`, bei Ganztags-Events `DTSTART` + 1 Tag) zum aktuellen Zeitpunkt oder davor liegt, SHALL ausgelassen werden; ein zu diesem Zeitpunkt noch laufendes oder künftiges Event SHALL übernommen werden.
4. WHERE ein `VEVENT` eine `RRULE` besitzt (Serientermin), THE Anonymized_Feed SHALL es nicht allein aufgrund eines in der Vergangenheit liegenden `DTSTART` als Vergangenes_Event auslassen, sondern anhand des Frei_Status und - sofern bestimmbar - des Endes der Serie entscheiden; im Zweifel SHALL das Serien-Event übernommen werden.
5. THE Anonymized_Feed SHALL einen `TRANSP`-Wert `TRANSPARENT` oder einen `X-MICROSOFT-CDO-BUSYSTATUS`-Wert `FREE` (jeweils getrimmt, ohne Berücksichtigung der Groß-/Kleinschreibung) als Frei_Status werten; fehlen beide Eigenschaften oder tragen sie andere Werte (z. B. `OPAQUE`, `BUSY`, `TENTATIVE`, `OOF`), SHALL das Event als belegt gelten und - vorbehaltlich der übrigen Regeln - übernommen werden.
6. WHEN ein Event wegen Vergangenheit oder Frei_Status ausgelassen wird, THE Anonymized_Feed SHALL den erzeugten Feed dennoch gültig nach RFC 5545 halten und die übrigen Events unverändert ausliefern.
