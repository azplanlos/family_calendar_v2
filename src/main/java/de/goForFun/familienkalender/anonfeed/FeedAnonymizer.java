package de.goForFun.familienkalender.anonfeed;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.XProperty;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Reine, I/O-freie Transformation auf dem rohen ical4j-Objektmodell.
 *
 * <p>Nimmt einen geparsten Quell-{@link Calendar} und liefert einen NEUEN {@link Calendar},
 * in dem als privat markierte Events anonymisiert und nicht-private Events wertidentisch
 * durchgereicht werden. {@code VTIMEZONE}-Komponenten werden unverändert übernommen,
 * {@code X-WR-CALNAME} wird gemäß Participant-Regeln gesetzt bzw. entfernt.
 *
 * <p>Abgedeckte Requirements: 1.1, 1.6, 1.7, 2.1, 2.2, 2.3, 2.4, 2.5, 3.1, 3.2, 3.3, 3.4,
 * 4.2, 4.3, 4.4, 4.5, 6.4, 6.5 (Design: Components/FeedAnonymizer, Feld-Transformationstabelle).
 */
public class FeedAnonymizer {

    private static final Logger LOG = Logger.getLogger(FeedAnonymizer.class.getName());

    /** iCal-Kalendername, über den FamilyWall den Participant zuordnet (Requirement 4). */
    static final String X_WR_CALNAME = "X-WR-CALNAME";

    /** CLASS-Werte, die ein Event als privat kennzeichnen (Requirement 2.4). */
    private static final String CLASS_PRIVATE = "private";
    private static final String CLASS_CONFIDENTIAL = "confidential";

    /** iCal-Eigenschaft für Frei/Belegt (RFC 5545): TRANSPARENT = frei (Requirement 9.5). */
    private static final String TRANSP = "TRANSP";
    private static final String TRANSP_TRANSPARENT = "transparent";
    /** Outlook-Erweiterung für Belegt-Status: FREE = frei (Requirement 9.5). */
    private static final String X_MS_BUSYSTATUS = "X-MICROSOFT-CDO-BUSYSTATUS";
    private static final String BUSYSTATUS_FREE = "free";
    /** Wiederholungsregel – markiert Serientermine (Requirement 9.4). */
    private static final String RRULE = "RRULE";

    /**
     * Kalender-Properties der Quelle, die unverändert in den Ziel-Kalender übernommen werden.
     */
    private static final String[] PRESERVED_CALENDAR_PROPERTIES = {
            Property.PRODID, Property.VERSION, Property.METHOD, Property.CALSCALE
    };

    /**
     * Inhaltsfelder, die bei einem Private_Event vollständig entfernt werden – auch wenn sie
     * vorhanden, aber wertlos sind (Requirement 1.6, 2.2).
     */
    private static final String[] PRIVATE_FIELDS_TO_REMOVE = {
            Property.DESCRIPTION, Property.LOCATION, Property.URL,
            Property.ATTENDEE, Property.ORGANIZER, Property.ATTACH
    };

    /**
     * Prüft, ob ein {@link VEvent} als privat einzustufen ist.
     *
     * <p>Der {@code CLASS}-Wert wird getrimmt und case-insensitiv verglichen: {@code PRIVATE}
     * oder {@code CONFIDENTIAL} ⇒ privat. In allen anderen Fällen – fehlende, leere,
     * {@code PUBLIC} oder unbekannte {@code CLASS}-Eigenschaft – ⇒ nicht-privat
     * (Requirement 2.4).
     *
     * @param event das zu prüfende Event (darf nicht {@code null} sein)
     * @return {@code true} genau dann, wenn das Event privat ist
     */
    public static boolean isPrivate(VEvent event) {
        Optional<Property> clazz = event.getProperty(Property.CLASS);
        if (clazz.isEmpty()) {
            return false;
        }
        String value = clazz.get().getValue();
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals(CLASS_PRIVATE) || normalized.equals(CLASS_CONFIDENTIAL);
    }

    /**
     * Prüft, ob ein {@link VEvent} als „frei" markiert ist (Requirement 9.2, 9.5).
     *
     * <p>Frei genau dann, wenn {@code TRANSP} den Wert {@code TRANSPARENT} trägt ODER
     * {@code X-MICROSOFT-CDO-BUSYSTATUS} den Wert {@code FREE} trägt (jeweils getrimmt,
     * case-insensitiv). Fehlen beide bzw. tragen sie andere Werte (z. B. {@code OPAQUE},
     * {@code BUSY}, {@code TENTATIVE}, {@code OOF}), gilt das Event als belegt.
     */
    public static boolean isFree(VEvent event) {
        if (propertyEquals(event, TRANSP, TRANSP_TRANSPARENT)) {
            return true;
        }
        return propertyEquals(event, X_MS_BUSYSTATUS, BUSYSTATUS_FREE);
    }

    private static boolean propertyEquals(VEvent event, String name, String expectedLower) {
        return event.getProperty(name)
                .map(Property::getValue)
                .filter(v -> v != null)
                .map(v -> v.trim().toLowerCase(Locale.ROOT).equals(expectedLower))
                .orElse(false);
    }

    /**
     * Prüft, ob ein {@link VEvent} ein Vergangenes_Event relativ zu {@code now} ist
     * (Requirement 9.1, 9.3, 9.4).
     *
     * <p>Ein Serientermin (mit {@code RRULE}) gilt nie allein aufgrund eines vergangenen
     * {@code DTSTART} als vergangen und wird daher nie über diese Methode ausgeschlossen.
     * Andernfalls wird das Event-Ende bestimmt ({@code DTEND}; ersatzweise {@code DTSTART},
     * bei Ganztags-Events {@code DTSTART} + 1 Tag) und mit {@code now} verglichen: Ende
     * {@code <= now} ⇒ vergangen. Lässt sich kein Ende bestimmen, gilt das Event als
     * nicht vergangen (konservativ: behalten).
     */
    public static boolean isPast(VEvent event, Instant now) {
        if (event.getProperty(RRULE).isPresent()) {
            return false; // Serientermine nicht allein wegen vergangenem DTSTART auslassen
        }
        Instant end = eventEndInstant(event);
        return end != null && !end.isAfter(now);
    }

    /**
     * Bestimmt das Ende eines Events als {@link Instant}. Nutzt {@code DTEND}; fehlt es, wird
     * bei getimten Events {@code DTSTART} (Null-Dauer), bei Ganztags-Events {@code DTSTART}+1 Tag
     * verwendet. Liefert {@code null}, wenn kein verwertbares Datum vorliegt.
     */
    private static Instant eventEndInstant(VEvent event) {
        Temporal start = temporalOf(event, Property.DTSTART);
        Temporal end = temporalOf(event, Property.DTEND);
        if (end != null) {
            return toInstant(end, false);
        }
        if (start == null) {
            return null;
        }
        boolean allDay = start instanceof LocalDate;
        return toInstant(start, allDay); // all-day: +1 Tag; timed: Null-Dauer (Start==Ende)
    }

    /** Liest eine Datums-Eigenschaft (DTSTART/DTEND) als ical4j-Temporal, oder null. */
    @SuppressWarnings("unchecked")
    private static Temporal temporalOf(VEvent event, String name) {
        return event.getProperty(name)
                .filter(prop -> prop instanceof net.fortuna.ical4j.model.property.DateProperty)
                .map(prop -> ((net.fortuna.ical4j.model.property.DateProperty<Temporal>) prop).getDate())
                .orElse(null);
    }

    /**
     * Konvertiert ein ical4j-Datum in einen {@link Instant}.
     *
     * @param addAllDayBuffer bei {@code true} und einem reinen {@link LocalDate} wird +1 Tag
     *                        gerechnet (Ende eines Ganztags-Events ohne DTEND)
     */
    private static Instant toInstant(Temporal t, boolean addAllDayBuffer) {
        if (t instanceof ZonedDateTime zdt) {
            return zdt.toInstant();
        }
        if (t instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (t instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant();
        }
        if (t instanceof LocalDate ld) {
            LocalDate day = addAllDayBuffer ? ld.plusDays(1) : ld;
            return day.atStartOfDay(ZoneId.systemDefault()).toInstant();
        }
        if (t instanceof Instant inst) {
            return inst;
        }
        return null;
    }

    /**
     * Transformiert den Quell-Kalender in einen neuen, anonymisierten Kalender.
     *
     * @param source der geparste Quell-{@link Calendar}
     * @param config die Konfiguration (liefert u. a. Platzhalter und Participant)
     * @param now    Referenzzeitpunkt für den Vergangenheits-Filter (Requirement 9)
     * @return ein NEUER {@link Calendar} mit anonymisierten/durchgereichten/gefilterten Events
     */
    public Calendar anonymize(Calendar source, AnonConfig config, Instant now) {
        PropertyList calendarProperties = buildCalendarProperties(source, config);
        ComponentList<CalendarComponent> components = buildComponents(source, config, now);
        return new Calendar(calendarProperties, components);
    }

    /**
     * Baut die Kalender-Property-Liste: übernimmt {@code PRODID}/{@code VERSION}/{@code METHOD}/
     * {@code CALSCALE} der Quelle und setzt {@code X-WR-CALNAME} gemäß Participant-Regeln. Ein
     * aus der Quelle stammendes {@code X-WR-CALNAME} wird nur übernommen, wenn ein gültiger
     * Participant vorliegt – andernfalls entfällt es (Requirement 4.2, 4.3, 4.4, 4.5).
     */
    private PropertyList buildCalendarProperties(Calendar source, AnonConfig config) {
        List<Property> props = new ArrayList<>();
        for (String name : PRESERVED_CALENDAR_PROPERTIES) {
            props.addAll(source.getProperties(name));
        }
        // X-WR-CALNAME der Quelle NICHT übernehmen; nur bei gültigem Participant neu setzen.
        config.calName()
                .map(FeedAnonymizer::sanitizeCalName)
                .ifPresent(name -> props.add(new XProperty(X_WR_CALNAME, name)));
        return new PropertyList(props);
    }

    /**
     * Bereinigt den Participant zu einem einzeiligen Anzeigenamen für {@code X-WR-CALNAME}
     * (Requirement 4.2, 4.3).
     *
     * <p>{@code X-WR-CALNAME} ist ein einzeiliges Anzeige-Label. Wird der Participant als roher
     * ical4j-{@link XProperty}-Wert gesetzt, rundet ical4j einen eingebetteten echten
     * Zeilenumbruch beim Serialisieren/Reparsen <em>nicht</em> verlustfrei: ein {@code CR}/{@code LF}
     * kommt als die beiden Literalzeichen {@code \n} zurück und verletzt damit die
     * Round-Trip-Invariante aus Requirement 4.3. Da ein Kalender-Anzeigename ohnehin einzeilig ist
     * und eingebettete Zeilenumbrüche keine fachliche Bedeutung tragen, werden alle Steuerzeichen
     * (inkl. {@code CR}/{@code LF}/{@code TAB}) zu je einem Leerzeichen zusammengefasst und das
     * Ergebnis getrimmt. Der so normalisierte, einzeilige Wert rundet über ical4j verlustfrei
     * (Standard-TEXT-Escaping von Komma/Semikolon/Backslash bleibt unberührt).
     *
     * @param calName der von {@link AnonConfig} getrimmte Participant (Länge 1..200)
     * @return der einzeilige, getrimmte Anzeigename für {@code X-WR-CALNAME}
     */
    static String sanitizeCalName(String calName) {
        // Alle Steuerzeichen (C0-Bereich inkl. CR/LF/TAB) auf ein Leerzeichen abbilden …
        String singleLine = calName.replaceAll("[\\x00-\\x1F]+", " ");
        // … und den so entstehenden Rand-Leerraum entfernen.
        return singleLine.trim();
    }

    /**
     * Baut die Komponentenliste des Ziel-Kalenders: jede {@code VTIMEZONE} unverändert
     * (Requirement 1.7, 6.5), gefolgt von den transformierten {@code VEVENT}-Komponenten.
     */
    private ComponentList<CalendarComponent> buildComponents(Calendar source, AnonConfig config, Instant now) {
        List<CalendarComponent> result = new ArrayList<>();

        for (CalendarComponent component : source.<CalendarComponent>getComponents(Component.VTIMEZONE)) {
            result.add((VTimeZone) component);
        }

        for (CalendarComponent component : source.<CalendarComponent>getComponents(Component.VEVENT)) {
            VEvent sourceEvent = (VEvent) component;
            transformEvent(sourceEvent, config, now).ifPresent(result::add);
        }

        return new ComponentList<>(result);
    }

    /**
     * Transformiert ein einzelnes {@code VEVENT}. Arbeitet stets auf einer Kopie
     * ({@link VEvent#copy()}), sodass die Quelle unberührt bleibt.
     *
     * <ul>
     *   <li>Fehlende/uneinlesbare {@code UID} oder {@code DTSTART} → Event auslassen + Log
     *       (Requirement 6.4).</li>
     *   <li>Privat → Private-Transformation (Requirement 1.6, 2.1, 2.2, 2.3).</li>
     *   <li>Nicht-privat → wertidentische Übernahme der Kopie (Requirement 2.5, 3.1–3.4).</li>
     * </ul>
     */
    private Optional<VEvent> transformEvent(VEvent sourceEvent, AnonConfig config, Instant now) {
        if (!hasReadableUidAndDtStart(sourceEvent)) {
            LOG.info("VEVENT ohne einlesbare UID oder DTSTART wird ausgelassen: "
                    + describeEvent(sourceEvent));
            return Optional.empty();
        }

        // Status "frei" → auslassen (Requirement 9.2), gilt für private und öffentliche Events.
        if (isFree(sourceEvent)) {
            LOG.info("VEVENT mit Status frei wird ausgelassen: " + describeEvent(sourceEvent));
            return Optional.empty();
        }

        // Vergangenes Event → auslassen (Requirement 9.1/9.3); Serientermine bleiben (9.4).
        if (isPast(sourceEvent, now)) {
            LOG.info("Vergangenes VEVENT wird ausgelassen: " + describeEvent(sourceEvent));
            return Optional.empty();
        }

        VEvent copy = sourceEvent.copy();

        if (isPrivate(copy)) {
            anonymizePrivateEvent(copy, config);
        }
        // Nicht-private Events: die Kopie bleibt wertidentisch – nichts zu tun.

        return Optional.of(copy);
    }

    /**
     * Wendet die Private-Transformation auf eine Event-Kopie an: {@code SUMMARY} wird durch den
     * Platzhalter ersetzt, und alle Inhaltsfelder werden vollständig entfernt
     * (Requirement 1.6, 2.1, 2.2). Zeitfelder, {@code UID} und {@code CLASS} bleiben unberührt
     * (Requirement 2.3).
     */
    private void anonymizePrivateEvent(VEvent event, AnonConfig config) {
        event.removeAll(Property.SUMMARY);
        event.add(new Summary(config.placeholder()));
        event.removeAll(PRIVATE_FIELDS_TO_REMOVE);
    }

    /**
     * Prüft, ob ein Event eine einlesbare {@code UID} und ein einlesbares {@code DTSTART} mit
     * jeweils nicht-leerem Wert besitzt (Requirement 6.4).
     */
    private boolean hasReadableUidAndDtStart(VEvent event) {
        return hasNonBlankProperty(event, Property.UID)
                && hasNonBlankProperty(event, Property.DTSTART);
    }

    private boolean hasNonBlankProperty(VEvent event, String name) {
        return event.getProperty(name)
                .map(Property::getValue)
                .filter(value -> !value.isBlank())
                .isPresent();
    }

    /**
     * Liefert eine knappe Beschreibung des Events für Logzwecke (Requirement 6.4), ohne
     * bei fehlenden Feldern zu scheitern.
     */
    private String describeEvent(VEvent event) {
        String uid = event.getProperty(Property.UID)
                .map(Property::getValue).orElse("<keine UID>");
        String dtStart = event.getProperty(Property.DTSTART)
                .map(Property::getValue).orElse("<kein DTSTART>");
        return "UID=" + uid + ", DTSTART=" + dtStart;
    }
}
