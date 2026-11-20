package de.goForFun.familienkalender.outlook;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Textbasierte Inspektion der Paketgrenzen unter {@code src/main/java}.
 *
 * <p>Validates: Requirements 1.8, 8.17, 9.10, 10.2, 10.3, 10.5, 10.9, 13.19
 */
class PackageBoundaryInspectionTest {

    private static final Path BASE = Path.of("src/main/java/de/goForFun/familienkalender");

    private static final Pattern S3_USAGE =
            Pattern.compile("\\bS3Client\\b|software\\.amazon\\.awssdk\\.services\\.s3");
    private static final Pattern ANONFEED_REF =
            Pattern.compile("familienkalender\\.anonfeed|\\banonfeed\\b");
    private static final Pattern OUTLOOK_REF =
            Pattern.compile("familienkalender\\.outlook|\\bOutlook(CalendarLoader|Load|SourceSelector)\\b"
                    + "|\\bGraph(Source|CalendarSource|CalendarClient|Config|TokenService|Http|Bootstrap)\\b"
                    + "|\\bQueryWindows?\\b|\\bHorizonDays\\b");
    private static final Pattern BOOTSTRAP_REF =
            Pattern.compile("outlook\\.bootstrap|\\bGraphBootstrap\\b");

    private static String code(Path file) throws IOException {
        String src = Files.readString(file);
        // Kommentare entfernen, damit nur echter Code geprüft wird
        return src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    private static List<Path> javaFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static void assertNoMatch(List<Path> files, Pattern p, String what) throws IOException {
        assertFalse(files.isEmpty(), "Keine Quelldateien gefunden für: " + what);
        for (Path f : files) {
            assertFalse(p.matcher(code(f)).find(), f + " darf nicht referenzieren: " + what);
        }
    }

    @Test
    void outlookImportsNeitherAnonfeedNorS3() throws IOException {
        List<Path> files = javaFiles(BASE.resolve("outlook"));
        assertNoMatch(files, ANONFEED_REF, "anonfeed");
        assertNoMatch(files, S3_USAGE, "S3");
    }

    @Test
    void anonfeedUsesNoS3Client() throws IOException {
        assertNoMatch(javaFiles(BASE.resolve("anonfeed")), S3_USAGE, "S3Client");
    }

    @Test
    void eventRepositoryAndCreateKalenderImageDoNotImportAnonfeed() throws IOException {
        List<Path> files = List.of(BASE.resolve("EventRepository.java"), BASE.resolve("CreateKalenderImage.java"));
        assertNoMatch(files, ANONFEED_REF, "anonfeed");
    }

    @Test
    void serveCalendarImageAndImageRendererDoNotReferenceOutlook() throws IOException {
        List<Path> files = List.of(BASE.resolve("ServeCalendarImage.java"), BASE.resolve("ImageRenderer.java"));
        assertNoMatch(files, OUTLOOK_REF, "outlook/Graph");
    }

    @Test
    void handlersDoNotReferenceBootstrap() throws IOException {
        List<Path> files = List.of(
                BASE.resolve("ServeCalendarImage.java"),
                BASE.resolve("CreateKalenderImage.java"),
                BASE.resolve("anonfeed/ServeAnonymizedFeed.java"));
        assertNoMatch(files, BOOTSTRAP_REF, "Bootstrap");
    }

    @Test
    void nothingOutsideBootstrapPackageReferencesBootstrap() throws IOException {
        Path bootstrap = BASE.resolve("outlook/bootstrap");
        List<Path> files;
        try (Stream<Path> s = Files.walk(BASE)) {
            files = s.filter(p -> p.toString().endsWith(".java") && !p.startsWith(bootstrap)).toList();
        }
        assertNoMatch(files, BOOTSTRAP_REF, "Bootstrap");
    }

    @Test
    void serveCalendarImageStillUsesS3AndNoGraph() throws IOException {
        String code = code(BASE.resolve("ServeCalendarImage.java"));
        assertFalse(Pattern.compile("graph", Pattern.CASE_INSENSITIVE).matcher(code).find(), "ServeCalendarImage darf keinen Graph-Bezug haben");
        assertTrue(code.contains("S3"), "ServeCalendarImage nutzt weiterhin S3");
    }
}
