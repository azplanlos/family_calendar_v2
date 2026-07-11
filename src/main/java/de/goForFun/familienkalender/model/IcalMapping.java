package de.goForFun.familienkalender.model;

import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.Summary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Optional;

@Mapper
public interface IcalMapping {

    @Mapping(target = "participants", source = "attendees")
    @Mapping(target = "summary", expression = "java(mapSummary(vEvent.getSummary()))")
    @Mapping(target = "startTime", expression = "java(unwrapDateProperty(vEvent.getStartDate()))")
    @Mapping(target = "endTime", expression = "java(unwrapDateProperty(vEvent.getEndDate()))")
    @Mapping(target = "color", expression = "java(extractColor(vEvent))")
    @Mapping(target = "source", expression = "java(de.goForFun.familienkalender.model.EventSource.CALENDAR)")
    Event mapVeventEvent(VEvent vEvent);

    List<String> mapAttendees(List<Attendee> value);

    default String mapAttendee(Attendee value) {
        if (value == null) {
            return null;
        }
        String calAddress = value.getCalAddress().getSchemeSpecificPart();
        return calAddress != null ? calAddress : value.getValue();
    }

    default String mapSummary(Summary value) {
        return value != null ? value.getValue() : null;
    }

    default <T extends Temporal> LocalDateTime unwrapDateProperty(Optional<? extends DateProperty<T>> optional) {
        if (optional == null || optional.isEmpty()) {
            return null;
        }
        Temporal date = optional.get().getDate();
        if (date instanceof LocalDateTime ldt) {
            return ldt;
        } else if (date instanceof LocalDate ld) {
            return ld.atStartOfDay();
        } else if (date instanceof java.time.ZonedDateTime zdt) {
            return zdt.toLocalDateTime();
        }
        return null;
    }

    default String extractColor(VEvent vEvent) {
        return vEvent.getProperty("COLOR")
                .map(p -> p.getValue())
                .orElse(null);
    }
}
