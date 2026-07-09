package de.goForFun.familienkalender.model;

import java.time.LocalDateTime;
import java.util.List;

public record Event(
        List<String> participants,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String summary,
        String color
) {
}
