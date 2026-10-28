package de.goForFun.familienkalender.model;

import java.time.LocalDateTime;
import java.util.List;

public record Event(
        List<String> participants,
        LocalDateTime startTime,
        LocalDateTime endTime,
        String summary,
        String color,
        EventSource source,
        String url,
        String uid
) {
    /**
     * Abwärtskompatibler Konstruktor ohne UID (setzt {@code uid = null}).
     * Erhält bestehende 7-Argument-Aufrufe.
     */
    public Event(List<String> participants, LocalDateTime startTime, LocalDateTime endTime,
                 String summary, String color, EventSource source, String url) {
        this(participants, startTime, endTime, summary, color, source, url, null);
    }
}
