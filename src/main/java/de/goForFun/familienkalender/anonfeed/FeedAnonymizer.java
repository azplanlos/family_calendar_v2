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
     * Transformiert den Quell-Kalender in einen neuen, anonymisierten Kalender.
     *
     * @param source der geparste Quell-{@link Calendar}
     * @param config die Konfiguration (liefert u. a. Platzhalter und Participant)
     * @return ein NEUER {@link Calendar} mit anonymisierten/durchgereichten Events
     */
    public Calendar anonymize(Calendar source, AnonConfig config) {
        PropertyList calendarProperties = buildCalendarProperties(source, config);
        ComponentList<CalendarComponent> components = buildComponents(source, config);
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
    private ComponentList<CalendarComponent> buildComponents(Calendar source, AnonConfig config) {
        List<CalendarComponent> result = new ArrayList<>();

        for (CalendarComponent component : source.<CalendarComponent>getComponents(Component.VTIMEZONE)) {
            result.add((VTimeZone) component);
        }

        for (CalendarComponent component : source.<CalendarComponent>getComponents(Component.VEVENT)) {
            VEvent sourceEvent = (VEvent) component;
            transformEvent(sourceEvent, config).ifPresent(result::add);
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
    private Optional<VEvent> transformEvent(VEvent sourceEvent, AnonConfig config) {
        if (!hasReadableUidAndDtStart(sourceEvent)) {
            LOG.info("VEVENT ohne einlesbare UID oder DTSTART wird ausgelassen: "
                    + describeEvent(sourceEvent));
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
