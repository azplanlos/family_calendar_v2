# Spike-Findings: outlook-graph-source

## ical4j/Jackson/Serverless (Task 1.1)

Ausgeführt am Linux-System (JDK 25 laut pom `release 25`, ical4j 4.4.0, `./mvnw -o`). Die Spike-Testklasse
und alle lokalen Hilfsdateien wurden nach der Prüfung wieder entfernt.

### ical4j 4.4.0

| Prüfung | Ergebnis |
|---|---|
| `DtStart<>(ZonedDateTime)` / `DtEnd<>(ZonedDateTime)` (America/New_York) serialisieren, mit `CalendarBuilder` zurücklesen | OK. Ausgabe `DTSTART;TZID=America/New_York:20250310T093000`; Rücklesen liefert denselben Instant, `TZID`-Parameter vorhanden. |
| `TimeZoneRegistry.getTimeZone(id)` für `UTC`, `Europe/Berlin`, `America/New_York`, `Asia/Kolkata` | Alle vier ungleich `null` (Registry via `TimeZoneRegistryFactory.getInstance().createRegistry()`). |
| `Categories(new TextList(...))` mit `a,b`, `x;y`, `back\slash` | OK. Ausgabe `CATEGORIES:a\,b,x\;y,back\\slash`; Rücklesen über `getCategories().getTexts()` zeichenidentisch. |
| `CalendarOutputter(true)` (Validierung) mit programmatisch gebautem `Calendar` (PRODID, VERSION, UID, DTSTAMP, DTSTART) | OK, keine `ValidationException`. |

Hinweise / nötige Anpassungen:

- Beim Zurücklesen ist die `ZoneId` des `ZonedDateTime` nicht `America/New_York`, sondern eine interne ID der Form
  `ical4j~<uuid>`. Der Instant ist korrekt, der Zonenname steht nur im `TZID`-Parameter. Tests dürfen daher
  Instants bzw. den `TZID`-Parameter vergleichen, nicht `ZoneId.equals`.
- `new VEvent()` setzt bereits ein `DTSTAMP`. Ein zusätzliches `add(new DtStamp())` führt zu einem doppelten
  `DTSTAMP` in der Ausgabe. Im `GraphCalendarMapper` kein zweites `DtStamp` hinzufügen (oder bestehendes ersetzen).
- `Categories` bildet Maskierung und Entmaskierung selbst; eigene Escape-Logik ist nicht nötig.
- Aus dem Spike folgt keine Abweichung vom Design; Properties bleiben unverändert.

### Jackson (`./mvnw dependency:tree`)

- Aufgelöst wird **kein** `com.fasterxml.jackson.core:jackson-databind` (2.x). Transitiv (über `jollyday-jackson 2.23.0`)
  liegt `tools.jackson.dataformat:jackson-dataformat-xml:3.2.4` mit `tools.jackson.core:jackson-databind:3.2.4` und
  `tools.jackson.core:jackson-core:3.2.4` (Jackson 3, Groupid `tools.jackson`, Paket `tools.jackson.*`) auf dem Classpath.
  Außerdem `com.fasterxml.jackson.core:jackson-annotations:2.22` (wird auch von Jackson 3 verwendet).
- AWS-SDK bringt nur `software.amazon.awssdk:third-party-jackson-core:2.57.7` (relocated, nicht nutzbar).
- Im lokalen `~/.m2` liegt nur `jackson-bom:2.19.1` (kein `jackson-databind` 2.x gecached).
- **Vorgabe für Task 2.1:** Entweder
  (a) Jackson 3 verwenden: `tools.jackson.core:jackson-databind` in der bereits aufgelösten Version **3.2.4** direkt
  deklarieren (API: `tools.jackson.databind.json.JsonMapper`, ungeprüfte Exceptions, `JsonNode` unter `tools.jackson.databind`), kein
  Versionskonflikt mit jollyday; oder
  (b) Jackson 2 (`com.fasterxml.jackson.core:jackson-databind`) mit `jackson-bom` 2.22.x (passend zu `jackson-annotations:2.22`)
  zusätzlich einbinden. Das koexistiert mit Jackson 3 (unterschiedliche Group/Packages), vergrößert aber das Shade-JAR.
  Empfehlung: (a), da bereits vorhanden. Der Design-/Task-Text („`com.fasterxml.jackson.core:jackson-databind`“ mit
  `jackson-bom`) ist entsprechend anzupassen (Paketnamen `tools.jackson.databind.*` im Code, Maven-Koordinaten
  `tools.jackson.core:jackson-databind`).

### Serverless / cfn-lint

Werkzeuge: `serverless@3.40.0` (via `npx`, ohne Login; das projektweite Serverless v4 braucht Org-Login) und `cfn-lint 1.60.0`
(temporäres venv). Geprüft mit einer Entwurfs-`serverless.yml`, danach alles gelöscht.

| Konstrukt | Ergebnis |
|---|---|
| `Fn::If` als Element der `Policies`-Liste einer `AWS::IAM::Role` (mit Condition aus `resources.Conditions`) | OK. `sls package` übernimmt es unverändert ins Template; cfn-lint ohne Fehler. |
| `{ "Ref": "AWS::NoValue" }` als Else-Zweig | OK, unverändert im Template; cfn-lint ohne Fehler. |
| `${sls:stage}` | OK, wird zur Packaging-Zeit aufgelöst (`dev`). |
| `${aws:region}` | OK, wird aufgelöst (`eu-central-1`). |
| `${aws:accountId}` | **Benötigt gültige AWS-Credentials** (STS-Aufruf bei Variablenauflösung); mit Dummy-Credentials Fehler „security token included in the request is invalid“. Für Template-Tests ohne Credentials stattdessen in `Fn::Sub` die Pseudo-Variable `${AWS::AccountId}` verwenden (funktioniert, wird von Serverless nicht angefasst). |
| `functions.<name>.role: Fn1Role` (Logical ID der eigenen `AWS::IAM::Role` in `resources`) | OK. Serverless erzeugt `Role: { Fn::GetAtt: [Fn1Role, Arn] }`. Die Logical ID als String-Wert ist der richtige Weg; ein `Ref` auf die Rolle würde den Namen statt der ARN liefern und ist nicht zu verwenden. |
| Condition-Definition in `resources.Conditions` mit `Fn::Equals` auf `${param:...}` | OK. cfn-lint meldet nur Warnungen (W8003 „always True/False“ wegen statisch aufgelöstem Parameter, W3005 redundantes DependsOn). |

Anpassung gegenüber Entwurf: `${aws:accountId}` in Policies nur verwenden, wenn der Deploy ohnehin mit Credentials läuft; für den
Inspektionstest (Task 11.1) offline besser `${AWS::AccountId}` in `Fn::Sub` bzw. `Fn::Sub`-Ressourcen-ARNs nutzen. Funktionsrolle
über `role: <LogicalId>` statt `Ref` referenzieren.

## Graph (Task 1.2)

Durchgeführt mit persönlichem Microsoft-Konto, öffentlicher Client (`/consumers`), Device Code Flow, Scopes `Calendars.Read offline_access`.

| Prüfung | Ergebnis |
|---|---|
| Device Code Flow (`/consumers`) | OK. Verification-URI `https://www.microsoft.com/link`, Code 15 Minuten gültig, Polling-Intervall 5 s. |
| Token-Antwort | `access_token` (ca. 3600 s) und `refresh_token` vorhanden. Das Feld `scope` nennt nur `Calendars.Read`, `offline_access` fehlt dort, obwohl ein Refresh-Token ausgegeben wird. Die Scope-Prüfung im Bootstrap darf `offline_access` daher nicht aus dem `scope`-Feld verlangen. |
| `GET /me/calendarView` | HTTP 200, **kein 403**. Persönliche Konten werden unterstützt. |
| `Prefer: outlook.timezone="Europe/Berlin"` | `start.timeZone` und `end.timeZone` sind `Europe/Berlin` (IANA-ID), 12 von 12 Events. Keine Windows→IANA-Tabelle nötig. `dateTime` hat 7 Nachkommastellen ohne Offset (`2026-11-23T10:00:00.0000000`). |
| `@odata.nextLink` | Enthält `startDateTime`, `endDateTime`, `$select`, `$top` und zusätzlich `$skip`. Paging über `$skip`, nicht über `$skiptoken`. Host `graph.microsoft.com`. |
| `type` | Beobachtet: `singleInstance`, `occurrence`. Serienereignisse kommen bereits expandiert. |
| `originalStart` | `null` bei `singleInstance`. Bei `occurrence` ISO-8601 mit Offset, z. B. `2027-02-14T00:00:00+01:00`. Der Parser muss `OffsetDateTime` lesen. |
| Ganztägige Events | `isAllDay=true`, Start und Ende um 00:00:00 in `Europe/Berlin`, Ende ist der Folgetag (exklusiv). |
| `sensitivity` / `showAs` | Beobachtet: `private`, `busy`. |

Offen (nicht im Spike geprüft, betrifft nur das Deployment): `kms:EncryptionContext`-Bedingung für den SecureString.
