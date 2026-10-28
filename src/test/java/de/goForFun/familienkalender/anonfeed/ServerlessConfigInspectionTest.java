package de.goForFun.familienkalender.anonfeed;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inspektionstest für {@code serverless.yml} (Task 6.2).
 *
 * <p>Prüft textbasiert (ohne YAML-Parser-Abhängigkeit), dass die Deployment-Konfiguration
 * die Funktion {@code ServeAnonymizedFeed} mit Function URL und allen vier {@code ANON_*}-
 * Umgebungsvariablen bereitstellt.
 *
 * <p><b>Validates: Requirements 8.1, 8.2</b>
 */
class ServerlessConfigInspectionTest {

    /**
     * Isoliert den Funktionsblock {@code ServeAnonymizedFeed:} (2-Space-Einrückung unter
     * {@code functions:}) vom Anfang bis zur nächsten gleichrangigen Funktion oder bis zum
     * Dateiende. Dadurch werden Environment-Einträge anderer Funktionen nicht fälschlich
     * mitgezählt.
     */
    private static String isolateFunctionBlock(String content, String functionKey) {
        String[] lines = content.split("\n", -1);
        StringBuilder block = new StringBuilder();
        boolean inBlock = false;
        for (String line : lines) {
            if (!inBlock) {
                if (line.equals("  " + functionKey + ":")) {
                    inBlock = true;
                    block.append(line).append('\n');
                }
                continue;
            }
            // Sibling-Funktion beginnt bei exakt 2-Space-Einrückung + Nicht-Space.
            boolean isSiblingKey = line.length() > 2
                    && line.charAt(0) == ' '
                    && line.charAt(1) == ' '
                    && line.charAt(2) != ' ';
            if (isSiblingKey) {
                break;
            }
            block.append(line).append('\n');
        }
        return block.toString();
    }

    @Test
    void serverlessYmlDefinesAnonymizedFeedFunctionWithUrlAndEnv() throws IOException {
        Path serverlessYml = Path.of("serverless.yml");
        assertThat(serverlessYml)
                .as("serverless.yml muss im Modul-Root (CWD der Tests) auffindbar sein: %s",
                        serverlessYml.toAbsolutePath())
                .exists();

        String content = Files.readString(serverlessYml, StandardCharsets.UTF_8);

        // 8.1: Funktion ServeAnonymizedFeed vorhanden.
        assertThat(content)
                .as("serverless.yml muss die Funktion 'ServeAnonymizedFeed' definieren (Req 8.1)")
                .contains("  ServeAnonymizedFeed:");

        String block = isolateFunctionBlock(content, "ServeAnonymizedFeed");
        assertThat(block)
                .as("Funktionsblock 'ServeAnonymizedFeed' muss isolierbar sein (Req 8.1)")
                .isNotEmpty();

        // Handler verweist auf die anonfeed-Klasse.
        assertThat(block)
                .as("Funktionsblock muss den korrekten Handler referenzieren (Req 8.1)")
                .contains("handler: de.goForFun.familienkalender.anonfeed.ServeAnonymizedFeed");

        // 8.1: Function URL gesetzt.
        assertThat(block)
                .as("Funktionsblock muss einen 'url:'-Block (Function URL) enthalten (Req 8.1)")
                .contains("url:");

        // 8.2: Alle vier ANON_*-Environment-Einträge vorhanden.
        assertThat(block)
                .as("Funktionsblock muss alle vier ANON_*-Umgebungsvariablen übergeben (Req 8.2)")
                .contains("ANON_SOURCE_FEED")
                .contains("ANON_PARTICIPANT")
                .contains("ANON_PLACEHOLDER")
                .contains("ANON_API_SECRET");
    }
}
