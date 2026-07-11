# Maven Build (Windows)

## Voraussetzungen

- **Java 21** muss installiert und `JAVA_HOME` gesetzt sein
- Kein separates Maven nötig – das Projekt enthält den Maven Wrapper (v3.3.2), der Maven 3.9.9 automatisch herunterlädt

## Befehle

Maven wird in diesem Projekt **immer** über den Wrapper aufgerufen. Auf Windows:

```
.\mvnw.cmd <goal>
```

### Häufige Ziele

| Befehl | Beschreibung |
|--------|--------------|
| `.\mvnw.cmd clean compile` | Kompilieren (inkl. Annotation Processing: Lombok + MapStruct) |
| `.\mvnw.cmd clean test` | Tests ausführen |
| `.\mvnw.cmd clean package` | Fat-JAR erzeugen (maven-shade-plugin) |
| `.\mvnw.cmd clean package -DskipTests` | Fat-JAR ohne Tests |
| `.\mvnw.cmd clean verify` | Kompilieren + Tests + Package + Integrationstests |

### Arbeitsverzeichnis

Befehle müssen im Projekt-Root ausgeführt werden:

```
c:\Users\Andi\Documents\Programmierung\family_calendar_v2
```

## Hinweise

- Der Wrapper nutzt `distributionType=only-script` und legt Maven unter `%USERPROFILE%\.m2\wrapper\dists\apache-maven-3.9.9` ab.
- Annotation Processors (Lombok, MapStruct, lombok-mapstruct-binding) sind in der `maven-compiler-plugin`-Konfiguration definiert.
- Das Shade-Plugin erzeugt ein Uber-JAR für AWS Lambda Deployment.
- Encoding ist UTF-8 (`project.build.sourceEncoding`).
