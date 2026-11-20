package de.goForFun.familienkalender.outlook;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.TextList;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.CalScale;
import net.fortuna.ical4j.model.property.Categories;
import net.fortuna.ical4j.model.property.Clazz;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.ProdId;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Transp;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.Version;
import net.fortuna.ical4j.model.property.XProperty;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Reine Abbildung von Graph-Event-JSON auf ical4j-{@code VEVENT}s (Requirement 5 und 6).
 *
 * <p>Kein Netzwerk-, SSM- oder Systemuhr-Zugriff; gleiche Eingaben liefern gleiche Ergebnisse.
 * Gefiltert wird nur nach {@code isCancelled} und nach Auswertbarkeit (UID/Start/Ende/Zeitzone),
 * nie nach {@code sensitivity}, {@code categories} oder {@code showAs}. Protokolliert werden
 * ausschließlich Anzahlen, nie Termininhalte.
 */
public final class GraphCalendarMapper {

    private static final Logger LOG = Logger.getLogger(GraphCalendarMapper.class.getName());

    static final String PRODID = "-//familienkalender//outlook-graph-source//DE";
    static final String X_BUSYSTATUS = "X-MICROSOFT-CDO-BUSYSTATUS";

    private static final DateTimeFormatter UID_SUFFIX = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private GraphCalendarMapper() {
    }

    /** {@code TRANSP} und {@code X-MICROSOFT-CDO-BUSYSTATUS} zu einem {@code showAs}-Wert. */
    public record ShowAs(String transp, String busyStatus) {
    }

    /** Ergebnis von {@link #toCalendar}: Kalender sowie Anzahl stornierter und ausgelassener Events. */
    public record MappingResult(Calendar calendar, int cancelled, int skipped) {
    }

    /** Ein gemapptes Event samt den für die UID-Bildung nötigen Angaben. */
    record MappedEvent(VEvent event, String baseUid, boolean seriesMember, String suffix, Set<String> tzids) {
    }

    // ------------------------------------------------------------------ Kalender

    public static MappingResult toCalendar(List<JsonNode> graphEvents, ZoneId appZone) {
        int cancelled = 0;
        int skipped = 0;
        List<MappedEvent> mapped = new ArrayList<>();
        for (JsonNode node : graphEvents) {
            if (node == null || !node.isObject()) {
                skipped++;
                continue;
            }
            if (node.path("isCancelled").asBoolean(false)) {
                cancelled++;
                continue;
            }
            Optional<MappedEvent> m = map(node, appZone);
            if (m.isPresent()) {
                mapped.add(m.get());
            } else {
                skipped++;
            }
        }

        applyUniqueUids(mapped);

        List<CalendarComponent> components = new ArrayList<>();
        Set<String> tzids = new LinkedHashSet<>();
        mapped.forEach(m -> tzids.addAll(m.tzids()));
        TimeZoneRegistry registry = TimeZoneRegistryFactory.getInstance().createRegistry();
        for (String tzid : tzids) {
            TimeZone tz = registry.getTimeZone(tzid);
            if (tz != null) {
                VTimeZone vtz = tz.getVTimeZone();
                components.add(vtz);
            }
        }
        mapped.forEach(m -> components.add(m.event()));

        List<Property> props = new ArrayList<>();
        props.add(new ProdId(PRODID));
        props.add(new Version(new net.fortuna.ical4j.model.ParameterList(), Version.VALUE_2_0));
        props.add(new CalScale(CalScale.VALUE_GREGORIAN));
        Calendar calendar = new Calendar(new PropertyList(props), new ComponentList<>(components));

        if (cancelled > 0 || skipped > 0) {
            LOG.info("Graph-Mapping: " + mapped.size() + " Events übernommen, " + cancelled
                    + " storniert, " + skipped + " ausgelassen");
        }
        return new MappingResult(calendar, cancelled, skipped);
    }

    // ------------------------------------------------------------------ UIDs

    private static void applyUniqueUids(List<MappedEvent> mapped) {
        Map<String, Integer> counts = new HashMap<>();
        for (MappedEvent m : mapped) {
            counts.merge(m.baseUid(), 1, Integer::sum);
        }
        Set<String> used = new HashSet<>();
        for (MappedEvent m : mapped) {
            boolean needsSuffix = m.seriesMember() || counts.get(m.baseUid()) > 1;
            String uid = needsSuffix ? m.baseUid() + "-" + m.suffix() : m.baseUid();
            if (!used.add(uid)) {
                int n = 2;
                while (!used.add(uid + "-" + n)) {
                    n++;
                }
                uid = uid + "-" + n;
            }
            m.event().removeAll(Property.UID);
            m.event().add(new Uid(uid));
        }
    }

    // ------------------------------------------------------------------ Einzelereignis

    /**
     * Bildet ein einzelnes (nicht storniertes) Graph-Event ab. Leer, wenn UID, Start oder Ende
     * nicht auswertbar sind (Requirement 5.14, 6.7). Die UID ist hier bereits die Basis-UID
     * (mit Suffix bei Serienmitgliedern); Duplikate löst {@link #toCalendar}.
     */
    static Optional<MappedEvent> map(JsonNode event, ZoneId appZone) {
        String baseUid = nonEmpty(text(event, "iCalUId")).orElseGet(() -> text(event, "id"));
        if (baseUid == null || baseUid.isEmpty()) {
            return Optional.empty();
        }

        boolean allDay = event.path("isAllDay").asBoolean(false);
        Optional<ResolvedTime> start = resolveTime(event.path("start"), allDay);
        Optional<ResolvedTime> end = resolveTime(event.path("end"), allDay);
        if (start.isEmpty() || end.isEmpty()) {
            return Optional.empty();
        }
        ResolvedTime s = start.get();
        ResolvedTime e = end.get();

        VEvent vevent = new VEvent();
        vevent.removeAll(Property.DTSTAMP);
        vevent.removeAll(Property.UID);

        Set<String> tzids = new LinkedHashSet<>();
        Instant stampInstant;
        if (allDay) {
            LocalDate sd = s.local().toLocalDate();
            LocalDate ed = e.local().toLocalDate();
            if (!ed.isAfter(sd)) {
                ed = sd.plusDays(1);
            }
            vevent.add(new DtStart<LocalDate>(sd));
            vevent.add(new DtEnd<LocalDate>(ed));
            stampInstant = sd.atStartOfDay(ZoneOffset.UTC).toInstant();
        } else {
            vevent.add(timed(s, true, tzids));
            vevent.add(timed(e, false, tzids));
            stampInstant = s.instant();
        }
        vevent.add(new DtStamp(stampInstant));

        vevent.add(new Uid(baseUid));
        vevent.add(new Summary(Optional.ofNullable(text(event, "subject")).orElse("")));
        vevent.add(new Clazz(mapClass(text(event, "sensitivity"))));

        List<String> categories = nonEmptyCategories(event.path("categories"));
        if (!categories.isEmpty()) {
            vevent.add(new Categories(new TextList(categories.toArray(new String[0]))));
        }

        ShowAs showAs = mapShowAs(text(event, "showAs"));
        vevent.add(new Transp(showAs.transp()));
        vevent.add(new XProperty(X_BUSYSTATUS, showAs.busyStatus()));

        String location = text(event.path("location"), "displayName");
        if (location != null && !location.trim().isEmpty()) {
            vevent.add(new Location(location));
        }
        String body = text(event, "bodyPreview");
        if (body != null && !body.trim().isEmpty()) {
            vevent.add(new Description(body));
        }

        String type = Optional.ofNullable(text(event, "type")).orElse("").trim().toLowerCase(Locale.ROOT);
        boolean seriesMember = type.equals("occurrence") || type.equals("exception");
        Instant suffixInstant = originalStart(event).orElse(s.instant());
        String suffix = UID_SUFFIX.format(suffixInstant.atOffset(ZoneOffset.UTC));

        return Optional.of(new MappedEvent(vevent, baseUid, seriesMember, suffix, tzids));
    }

    // ------------------------------------------------------------------ Felder

    static String mapClass(String sensitivity) {
        String v = sensitivity == null ? "" : sensitivity.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "private" -> "PRIVATE";
            case "confidential" -> "CONFIDENTIAL";
            default -> "PUBLIC";
        };
    }

    static ShowAs mapShowAs(String showAs) {
        String v = showAs == null ? "" : showAs.toLowerCase(Locale.ROOT);
        return switch (v) {
            case "free" -> new ShowAs("TRANSPARENT", "FREE");
            case "tentative" -> new ShowAs("OPAQUE", "TENTATIVE");
            case "oof" -> new ShowAs("OPAQUE", "OOF");
            default -> new ShowAs("OPAQUE", "BUSY");
        };
    }

    static List<String> nonEmptyCategories(JsonNode categories) {
        List<String> result = new ArrayList<>();
        if (categories != null && categories.isArray()) {
            for (JsonNode c : categories) {
                if (c.isString() && !c.asString().isEmpty()) {
                    result.add(c.asString());
                }
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ Zeit

    /** Lokale Zeit (ohne Sekundenbruchteile) plus Zone ({@code null} = UTC). */
    private record ResolvedTime(LocalDateTime local, String zoneId, Instant instant) {
        boolean isUtc() {
            return zoneId == null;
        }
    }

    private static Optional<ResolvedTime> resolveTime(JsonNode node, boolean allDay) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        String dateTime = text(node, "dateTime");
        String tz = text(node, "timeZone");
        if (dateTime == null || tz == null) {
            return Optional.empty();
        }
        LocalDateTime local;
        try {
            local = LocalDateTime.parse(dateTime.trim()).withNano(0);
        } catch (RuntimeException ex) {
            if (!allDay) {
                return Optional.empty();
            }
            try {
                local = LocalDate.parse(dateTime.trim().substring(0, 10)).atStartOfDay();
            } catch (RuntimeException ex2) {
                return Optional.empty();
            }
        }
        if (tz.equals("UTC")) {
            return Optional.of(new ResolvedTime(local, null, local.toInstant(ZoneOffset.UTC)));
        }
        if (!isKnownIana(tz)) {
            return Optional.empty();
        }
        return Optional.of(new ResolvedTime(local, tz, ZonedDateTime.of(local, ZoneId.of(tz)).toInstant()));
    }

    private static boolean isKnownIana(String id) {
        try {
            ZoneId.of(id);
        } catch (RuntimeException ex) {
            return false;
        }
        return TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone(id) != null;
    }

    private static Property timed(ResolvedTime t, boolean isStart, Set<String> tzids) {
        if (t.isUtc()) {
            return isStart ? new DtStart<Instant>(t.instant()) : new DtEnd<Instant>(t.instant());
        }
        tzids.add(t.zoneId());
        ZonedDateTime zdt = ZonedDateTime.of(t.local(), ZoneId.of(t.zoneId()));
        return isStart ? new DtStart<ZonedDateTime>(zdt) : new DtEnd<ZonedDateTime>(zdt);
    }

    private static Optional<Instant> originalStart(JsonNode event) {
        String raw = text(event, "originalStart");
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(raw.trim()).toInstant());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ Helfer

    /** Textwert eines Feldes; {@code null} bei fehlendem, {@code null}- oder Nicht-Text-Feld. */
    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.path(field);
        return v.isString() ? v.asString() : null;
    }

    private static Optional<String> nonEmpty(String s) {
        return s == null || s.isEmpty() ? Optional.empty() : Optional.of(s);
    }
}
