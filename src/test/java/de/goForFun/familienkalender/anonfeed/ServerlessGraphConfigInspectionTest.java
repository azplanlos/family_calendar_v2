package de.goForFun.familienkalender.anonfeed;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Textbasierter Inspektionstest für die Graph-Erweiterung von {@code serverless.yml} (Task 14.2).
 *
 * <p><b>Validates: Requirements 9.2, 9.3, 9.4, 9.5, 10.4, 10.10, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.14</b>
 */
class ServerlessGraphConfigInspectionTest {

    private static String content;
    private static String feedFn;
    private static String kalenderFn;
    private static String imageFn;
    private static String feedRole;
    private static String kalenderRole;

    @BeforeAll
    static void load() throws IOException {
        Path yml = Path.of("serverless.yml");
        assertThat(yml).as("serverless.yml im Modul-Root: %s", yml.toAbsolutePath()).exists();
        content = Files.readString(yml, StandardCharsets.UTF_8).replace("\r\n", "\n");
        feedFn = block(content, "  ServeAnonymizedFeed:", 2);
        kalenderFn = block(content, "  FamilyWall-Kalender:", 2);
        imageFn = block(content, "  ServeCalendarImage:", 2);
        feedRole = block(content, "    ServeAnonymizedFeedRole:", 4);
        kalenderRole = block(content, "    FamilyWallKalenderRole:", 4);
    }

    /** Liefert den Block ab der Kopfzeile bis zur nächsten Zeile mit Einrückung <= indent. */
    private static String block(String text, String header, int indent) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean in = false;
        for (String line : lines) {
            if (!in) {
                if (line.equals(header)) {
                    in = true;
                    sb.append(line).append('\n');
                }
                continue;
            }
            if (!line.isBlank() && leadingSpaces(line) <= indent) {
                break;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static int count(String text, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    void blocksAreIsolated() {
        assertThat(feedFn).isNotEmpty();
        assertThat(kalenderFn).isNotEmpty();
        assertThat(imageFn).isNotEmpty();
        assertThat(feedRole).isNotEmpty();
        assertThat(kalenderRole).isNotEmpty();
    }

    @Test
    void graphEnvOnlyInTheTwoFunctions() {
        for (String fn : new String[]{feedFn, kalenderFn}) {
            assertThat(fn).contains("GRAPH_CLIENT_ID: ${param:graphClientId, ''}");
            assertThat(fn).contains("GRAPH_REFRESH_TOKEN_PARAM: ${self:custom.graphRefreshTokenParam}");
        }
        assertThat(feedFn).contains("GRAPH_HORIZON_DAYS: ${param:graphHorizonDays, 365}");
        assertThat(kalenderFn).doesNotContain("GRAPH_HORIZON_DAYS");
        assertThat(imageFn).doesNotContain("GRAPH_");
        assertThat(count(content, "GRAPH_HORIZON_DAYS")).isEqualTo(1);
        assertThat(count(content, "GRAPH_CLIENT_ID")).isEqualTo(2);
        assertThat(count(content, "GRAPH_REFRESH_TOKEN_PARAM")).isEqualTo(2);
    }

    @Test
    void customRefreshTokenParamDefaultsToEmpty() {
        assertThat(content).contains("graphRefreshTokenParam: ${param:graphRefreshTokenParam, ''}");
    }

    @Test
    void imageFunctionHasNoOwnRoleAndNoSsmKms() {
        assertThat(imageFn).doesNotContain("role:");
        assertThat(imageFn).doesNotContain("ssm:").doesNotContain("kms:");
        assertThat(content).doesNotContain("ServeCalendarImageRole");
    }

    @Test
    void functionsUseDedicatedRolesAndTimeout() {
        assertThat(kalenderFn).contains("role: FamilyWallKalenderRole").contains("timeout: 60");
        assertThat(feedFn).contains("role: ServeAnonymizedFeedRole");
    }

    @Test
    void anonSourceFeedUnchanged() {
        assertThat(feedFn).contains("ANON_SOURCE_FEED: ${param:anonSourceFeed}\n");
        assertThat(kalenderFn).contains("ANON_SOURCE_FEED: ${param:anonSourceFeed, ''}");
    }

    @Test
    void graphConfiguredCondition() {
        assertThat(content).contains("  Conditions:\n    GraphConfigured:");
        assertThat(content).contains("Fn::Equals: ['${self:custom.graphRefreshTokenParam}', '']");
        assertThat(count(content, "- GraphConfigured")).isEqualTo(2);
        assertThat(count(content, "- Ref: AWS::NoValue")).isEqualTo(2);
    }

    @Test
    void ssmKmsActionsExactAndScoped() {
        assertThat(count(content, "- ssm:")).isEqualTo(4);
        assertThat(count(content, "ssm:GetParameter")).isEqualTo(2);
        assertThat(count(content, "ssm:PutParameter")).isEqualTo(2);
        assertThat(count(content, "kms:Decrypt")).isEqualTo(2);
        assertThat(count(content, "kms:Encrypt")).isEqualTo(2);
        assertThat(content).doesNotContain("ssm:*").doesNotContain("kms:*");
        // Alle kms:-Treffer sind Decrypt, Encrypt oder ViaService
        Matcher m = Pattern.compile("kms:(\\w+)").matcher(content);
        while (m.find()) {
            assertThat(m.group(1)).isIn("Decrypt", "Encrypt", "ViaService");
        }
        for (String role : new String[]{feedRole, kalenderRole}) {
            assertThat(role).contains("kms:ViaService");
            assertThat(role).contains(
                    "parameter${self:custom.graphRefreshTokenParam}'");
            assertThat(role).doesNotContain("ssm:DeleteParameter");
        }
    }

    @Test
    void noWildcardResourceForSsm() {
        for (String line : content.split("\n")) {
            if (line.contains("arn:aws:ssm:")) {
                assertThat(line).doesNotContain(":parameter*").doesNotContain("parameter/*");
                assertThat(line).doesNotContain("Resource: '*'");
            }
        }
        assertThat(content).doesNotContain("Resource: '*'").doesNotContain("Resource: \"*\"");
        assertThat(content).doesNotContainPattern("Resource:\\s*\\n\\s*-?\\s*'?\\*'?\\s*\\n");
    }

    @Test
    void rolesGrantLogsAndOnlyKalenderRoleGetsS3() {
        assertThat(feedRole).contains("logs:CreateLogStream").contains("logs:PutLogEvents");
        assertThat(kalenderRole).contains("logs:CreateLogStream").contains("logs:PutLogEvents");
        assertThat(kalenderRole).contains("s3:*").contains("arn:aws:s3:::familienkalender/*");
        assertThat(feedRole).doesNotContain("s3:");
    }

    @Test
    void containsNoTokenValue() {
        assertThat(content).doesNotContainPattern("(?i)refresh_?token\\s*:\\s*['\"]?[A-Za-z0-9._-]{40,}");
        assertThat(content).doesNotContainPattern("\\b0\\.A[A-Za-z0-9_-]{30,}");
    }
}
