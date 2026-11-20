package de.goForFun.familienkalender;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Textinspektion von README.md und .env.example (Outlook-Graph-Quelle).
 * Validates: Requirements 11.7 bis 11.11, 11.15, 12.9, 12.10
 */
class DocumentationInspectionTest {

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file), StandardCharsets.UTF_8);
    }

    private static void assertContainsAll(String text, String file, String... terms) {
        for (String term : terms) {
            assertTrue(text.contains(term), file + " fehlt: " + term);
        }
    }

    /** Req 11.7: Kennzeichnung, Vorgabewert, Wertebereich, Wirkung je Variable. */
    @Test
    void envExample_documentsGraphVariables() throws IOException {
        String env = read(".env.example");
        assertContainsAll(env, ".env.example",
                "GRAPH_CLIENT_ID", "GRAPH_REFRESH_TOKEN_PARAM", "GRAPH_HORIZON_DAYS",
                "optional", "Default", "365", "1 bis 730", "ANON_SOURCE_FEED",
                "nur auf den anonymisierten Feed");
    }

    /** Req 11.8: Einrichtung in Reihenfolge (a) bis (d). */
    @Test
    void readme_setupStepsInOrder() throws IOException {
        String readme = read("README.md");
        assertContainsAll(readme, "README.md",
                "personal Microsoft accounts only", "Allow public client flows", "No client secret",
                "Calendars.Read", "./mvnw", ".\\mvnw.cmd");
        int app = readme.indexOf("Create the app registration");
        int bootstrap = readme.indexOf("Run the bootstrap program");
        int params = readme.indexOf("Set the deploy parameters");
        int deploy = readme.indexOf("**Deploy**");
        assertTrue(app >= 0 && app < bootstrap && bootstrap < params && params < deploy,
                "Einrichtungsschritte nicht in der Reihenfolge a-d");
    }

    /** Req 11.9: Fallback-Verhalten inkl. Zeiten, 502, Device, Fehlerlisten-Regel. */
    @Test
    void readme_documentsFallbackBehavior() throws IOException {
        String readme = read("README.md");
        assertContainsAll(readme, "README.md",
                "15 s", "10 s", "502", "CreateKalenderImage", "Outlook-Feed:",
                "ANON_SOURCE_FEED", "real title");
    }

    /** Req 11.10, 12.9: Wiederherstellung, 90 Tage, Log-Meldungen. */
    @Test
    void readme_documentsRecovery() throws IOException {
        String readme = read("README.md");
        assertContainsAll(readme, "README.md",
                "90 days", "run the bootstrap program again",
                "Refresh_Token ungültig, Bootstrap erneut ausführen",
                "SSM_Parameter nicht verfügbar, Bootstrap erneut ausführen oder Parameter prüfen");
    }

    /** Req 12.10: Einschränkungen des ICS-Fallbacks. */
    @Test
    void readme_documentsIcsFallbackLimits() throws IOException {
        String readme = read("README.md");
        assertContainsAll(readme, "README.md", "Privater Termin", "CATEGORIES");
    }

    /** Req 11.11, 11.15: Env-Tabelle, Architektur, Timeout, SSM/KMS-Rollen. */
    @Test
    void readme_documentsEnvTableTimeoutPermissionsArchitecture() throws IOException {
        String readme = read("README.md");
        assertContainsAll(readme, "README.md",
                "| `GRAPH_CLIENT_ID` |", "| `GRAPH_REFRESH_TOKEN_PARAM` |", "| `GRAPH_HORIZON_DAYS` |",
                "365", "1 to 730",
                "outlook", "ServeCalendarImage",
                "FamilyWall-Kalender", "60 s", "ServeAnonymizedFeed",
                "ssm:GetParameter", "ssm:PutParameter", "kms:Decrypt");
    }
}
