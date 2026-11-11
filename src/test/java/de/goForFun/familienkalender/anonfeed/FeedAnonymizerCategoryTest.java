package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Outlook-Kategorie „privat" kennzeichnet ein Event zusätzlich zu CLASS:PRIVATE/CONFIDENTIAL als
 * privat (Requirement 2.7). So bleibt der Titel im veröffentlichten Outlook-Feed (und auf dem
 * Device) im Klartext, während der FamilyWall-Feed ihn anonymisiert.
 */
class FeedAnonymizerCategoryTest {

    private static Calendar calendar(String clazz, String... categoryLines) {
        StringBuilder sb = new StringBuilder(String.join("\r\n",
                "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//test//EN",
                "BEGIN:VEVENT", "UID:cat-1@test", "DTSTAMP:20260101T000000Z",
                "DTSTART;VALUE=DATE:20991231", "SUMMARY:Arzttermin Dr. X",
                "DESCRIPTION:Details", "LOCATION:Praxis", ""));
        if (clazz != null) sb.append("CLASS:").append(clazz).append("\r\n");
        for (String c : categoryLines) sb.append(c).append("\r\n");
        sb.append("END:VEVENT\r\nEND:VCALENDAR\r\n");
        try {
            return new CalendarBuilder().build(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static VEvent event(Calendar cal) {
        return (VEvent) cal.getComponents(Component.VEVENT).get(0);
    }

    private static VEvent anonymized(Calendar source) {
        AnonConfig config = AnonConfig.fromMap(Map.of(
                "ANON_SOURCE_FEED", "https://example.com/source.ics", "ANON_PLACEHOLDER", "Privat"));
        return event(new FeedAnonymizer().anonymize(source, config, java.time.Instant.now()));
    }

    private static List<String> categories(VEvent e) {
        return e.getProperties(Property.CATEGORIES).stream()
                .flatMap(p -> ((net.fortuna.ical4j.model.property.Categories) p).getCategories().getTexts().stream())
                .toList();
    }

    @Test
    void kleingeschriebenePrivatKategorieWirdErkannt() {
        assertThat(FeedAnonymizer.isPrivate(event(calendar(null, "CATEGORIES:privat")))).isTrue();
        assertThat(FeedAnonymizer.isPrivate(event(calendar("PUBLIC", "CATEGORIES:PRIVAT")))).isTrue();
    }

    @Test
    void privatesEventBehaeltNurDiePrivatKategorie() {
        VEvent e = anonymized(calendar("PUBLIC", "CATEGORIES:Arzt,privat", "CATEGORIES:Gesundheit"));
        assertThat(categories(e)).containsExactly("privat");
        assertThat(e.toString()).doesNotContain("Arzt").doesNotContain("Gesundheit");
    }

    @Test
    void privatUeberClassVerliertAlleKategorien() {
        VEvent e = anonymized(calendar("PRIVATE", "CATEGORIES:Arzt"));
        assertThat(e.getProperties(Property.CATEGORIES)).isEmpty();
    }

    @Test
    void zweiterDurchlaufBleibtPrivat() {
        AnonConfig config = AnonConfig.fromMap(Map.of(
                "ANON_SOURCE_FEED", "https://example.com/source.ics", "ANON_PLACEHOLDER", "Privat"));
        FeedAnonymizer anonymizer = new FeedAnonymizer();
        Calendar once = anonymizer.anonymize(calendar("PUBLIC", "CATEGORIES:Arzt,privat"), config, java.time.Instant.now());
        try {
            Calendar reparsed = new CalendarBuilder().build(
                    new ByteArrayInputStream(once.toString().getBytes(StandardCharsets.UTF_8)));
            assertThat(FeedAnonymizer.isPrivate(event(reparsed))).isTrue();
            assertThat(categories(event(anonymizer.anonymize(reparsed, config, java.time.Instant.now()))))
                    .containsExactly("privat");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void oeffentlichesEventBehaeltSeineKategorien() {
        VEvent e = anonymized(calendar("PUBLIC", "CATEGORIES:Arbeit,Sport"));
        assertThat(categories(e)).containsExactlyInAnyOrder("Arbeit", "Sport");
    }

    @Test
    void kategoriePrivatMachtEventPrivat() {
        for (String categories : List.of("CATEGORIES:Privat", "CATEGORIES:privat", "CATEGORIES: PRIVAT ",
                "CATEGORIES:Arbeit,Privat", "CATEGORIES:Familie,privat,Sport")) {
            assertThat(FeedAnonymizer.isPrivate(event(calendar("PUBLIC", categories)))).as(categories).isTrue();
            assertThat(FeedAnonymizer.isPrivate(event(calendar(null, categories)))).as(categories).isTrue();
        }
    }

    @Test
    void kategoriePrivatInZweiterCategoriesZeile() {
        assertThat(FeedAnonymizer.isPrivate(event(calendar(null, "CATEGORIES:Arbeit", "CATEGORIES:Privat")))).isTrue();
    }

    @Test
    void andereKategorienBleibenOeffentlich() {
        for (String categories : List.of("CATEGORIES:Arbeit", "CATEGORIES:Privatsache",
                "CATEGORIES:Privat-Familie", "CATEGORIES:Familie,Sport")) {
            assertThat(FeedAnonymizer.isPrivate(event(calendar("PUBLIC", categories)))).as(categories).isFalse();
        }
    }

    @Test
    void ohneKategorieUndPublicBleibtOeffentlich() {
        assertThat(FeedAnonymizer.isPrivate(event(calendar("PUBLIC")))).isFalse();
    }

    @Test
    void classPrivateWirktWeiterhin() {
        assertThat(FeedAnonymizer.isPrivate(event(calendar("PRIVATE", "CATEGORIES:Arbeit")))).isTrue();
    }

    @Test
    void kategoriePrivatWirdImFamilyWallFeedAnonymisiert() {
        AnonConfig config = AnonConfig.fromMap(Map.of(
                "ANON_SOURCE_FEED", "https://example.com/source.ics", "ANON_PLACEHOLDER", "Privat"));
        Calendar out = new FeedAnonymizer().anonymize(calendar("PUBLIC", "CATEGORIES:Privat"), config, java.time.Instant.now());

        VEvent e = event(out);
        assertThat(e.getProperty(Property.SUMMARY)).get().extracting(Property::getValue).isEqualTo("Privat");
        for (String f : List.of(Property.DESCRIPTION, Property.LOCATION)) {
            assertThat(e.getProperties(f)).isEmpty();
        }
        assertThat(out.toString()).doesNotContain("Arzttermin").doesNotContain("Praxis");
    }
}
